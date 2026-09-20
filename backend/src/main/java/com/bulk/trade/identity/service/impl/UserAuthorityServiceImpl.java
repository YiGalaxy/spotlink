package com.bulk.trade.identity.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.identity.entity.Permission;
import com.bulk.trade.identity.entity.Role;
import com.bulk.trade.identity.entity.RolePermission;
import com.bulk.trade.identity.entity.User;
import com.bulk.trade.identity.entity.UserRole;
import com.bulk.trade.identity.mapper.PermissionMapper;
import com.bulk.trade.identity.mapper.RoleMapper;
import com.bulk.trade.identity.mapper.RolePermissionMapper;
import com.bulk.trade.identity.mapper.UserMapper;
import com.bulk.trade.identity.mapper.UserRoleMapper;
import com.bulk.trade.shared.security.UserAuthority;
import com.bulk.trade.shared.security.UserAuthorityProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves what an account may do, from the database, through a short cache.
 *
 * <p><b>The reason Redis is in this project at all.</b> It was a declared
 * dependency with a configured connection and not one line of Java using it.
 * JwtTokenProvider's own documentation promised this lookup — "permissions
 * change far more often than tokens expire, so they are looked up per request
 * from cache instead" — and the cache did not exist. Now it does.
 *
 * <p><b>Failures fall through to the database, never to nothing.</b> The two
 * mistakes available here are both easy to make and both bad:
 *
 * <ul>
 *   <li>caching an empty permission set because Redis was unreachable — that
 *       records a transport failure as a fact about the user, and locks them
 *       out for the whole TTL;</li>
 *   <li>treating a cache miss as "no permissions" — a miss means the cache does
 *       not know, and the database does.</li>
 * </ul>
 *
 * <p>So every path through {@link #load} ends at the database when the cache
 * cannot answer. The cache costs latency or nothing; it never costs
 * correctness.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthorityServiceImpl implements UserAuthorityProvider {

    /**
     * How long a resolved answer may be reused.
     *
     * <p>Short because it is the revocation window: a role taken away stays
     * effective for at most this long if the explicit eviction is missed.
     * Console changes evict immediately, so this is the backstop rather than the
     * mechanism.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final String KEY_PREFIX = "perm:user:";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public UserAuthority load(Long userId) {
        if (userId == null) {
            return null;
        }

        UserAuthority cached = readCache(userId);
        if (cached != null) {
            return cached;
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            // A token naming a user who no longer exists is not a session. Not
            // cached: the answer is stable, but caching a null would need a
            // sentinel and the row is read once per request at most.
            return null;
        }

        UserAuthority authority = UserAuthority.of(
                user.getStatus() == null ? UserAuthority.ACTIVE : user.getStatus(),
                permissionsOf(userId));
        writeCache(userId, authority);
        return authority;
    }

    @Override
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            // The TTL still bounds the staleness. Failing the caller's
            // transaction because a cache could not be cleared would turn a
            // five-minute delay into an outage.
            log.warn("Could not evict authority for user {}: {}", userId, e.getMessage());
        }
    }

    @Override
    public void evictAll() {
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException e) {
            log.warn("Could not clear the authority cache: {}", e.getMessage());
        }
    }

    @Override
    public Set<String> rolesOf(Long userId) {
        List<UserRole> grants = userRoleMapper.selectList(
                Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
        if (grants.isEmpty()) {
            return Set.of();
        }
        Set<Long> roleIds = new LinkedHashSet<>();
        grants.forEach(grant -> roleIds.add(grant.getRoleId()));

        Set<String> codes = new LinkedHashSet<>();
        roleMapper.selectBatchIds(roleIds).forEach(role -> codes.add(role.getCode()));
        return codes;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Every authority the account holds, through its roles.
     *
     * <p>Three queries rather than one join, because the joins that matter are
     * on small sets of ids and this is the shape MyBatis can express without a
     * hand-written statement. It runs on a cache miss only.
     */
    private Set<String> permissionsOf(Long userId) {
        List<UserRole> grants = userRoleMapper.selectList(
                Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
        if (grants.isEmpty()) {
            return Set.of();
        }

        Set<Long> roleIds = new LinkedHashSet<>();
        grants.forEach(grant -> roleIds.add(grant.getRoleId()));

        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        if (roles.isEmpty()) {
            return Set.of();
        }

        Set<Long> permissionIds = new LinkedHashSet<>();
        rolePermissionMapper.selectList(Wrappers.<RolePermission>lambdaQuery()
                        .in(RolePermission::getRoleId, roleIds))
                .forEach(grant -> permissionIds.add(grant.getPermissionId()));
        if (permissionIds.isEmpty()) {
            return Set.of();
        }

        Set<String> codes = new LinkedHashSet<>();
        permissionMapper.selectBatchIds(permissionIds)
                .forEach(permission -> codes.add(permission.getCode()));
        return codes;
    }

    private UserAuthority readCache(Long userId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + userId);
            return json == null ? null : objectMapper.readValue(json, UserAuthority.class);
        } catch (Exception e) {
            // Includes "Redis is down" and "the cached value is from an older
            // shape". Both mean the same thing to the caller: ask the database.
            log.debug("Authority cache unavailable for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void writeCache(Long userId, UserAuthority authority) {
        try {
            redis.opsForValue().set(KEY_PREFIX + userId,
                    objectMapper.writeValueAsString(authority), TTL);
        } catch (Exception e) {
            // A cache that cannot be written is a cache that will be missed
            // next time. Not a reason to fail an authentication.
            log.debug("Could not cache authority for user {}: {}", userId, e.getMessage());
        }
    }
}
