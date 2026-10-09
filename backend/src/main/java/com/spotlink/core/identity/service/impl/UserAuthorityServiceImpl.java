package com.spotlink.identity.service.impl;

import com.spotlink.identity.entity.Permission;
import com.spotlink.identity.entity.Role;
import com.spotlink.identity.entity.RolePermission;
import com.spotlink.identity.entity.User;
import com.spotlink.identity.entity.UserRole;
import com.spotlink.identity.mapper.PermissionMapper;
import com.spotlink.identity.mapper.RoleMapper;
import com.spotlink.identity.mapper.RolePermissionMapper;
import com.spotlink.identity.mapper.UserMapper;
import com.spotlink.identity.mapper.UserRoleMapper;
import com.spotlink.shared.security.UserAuthority;
import com.spotlink.shared.security.UserAuthorityProvider;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.mapper.EnterpriseMapper;
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
 * 解析一个账号能做什么，数据来自数据库，中间隔着一层短缓存。
 *
 * <p><b>Redis 之所以会出现在这个项目里，原因在此。</b>它此前是一个声明了依赖、配置了连接，
 * 却没有一行 Java 代码使用它的东西。JwtTokenProvider 自己的文档就承诺了这次查询 ——
 * 「权限变化的频率远高于 token 过期，所以改为每个请求从缓存中查询」—— 而那个缓存并不存在。
 * 现在它存在了。
 *
 * <p><b>失败时回落到数据库，绝不回落到空。</b>这里可能犯的两个错误都很容易犯，而且都很糟：
 *
 * <ul>
 *   <li>因为 Redis 连不上就缓存一个空权限集 —— 那是把一次传输故障记成了关于该用户的事实，
 *       并把他锁在门外整整一个 TTL；</li>
 *   <li>把缓存未命中当作「没有权限」—— 未命中的意思是缓存不知道，而数据库知道。</li>
 * </ul>
 *
 * <p>所以当缓存答不上来时，{@link #load} 的每一条路径都以数据库为终点。缓存的代价最多是
 * 延迟，绝不会是正确性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthorityServiceImpl implements UserAuthorityProvider {

    /**
     * 一个已解析的结果可以被复用多久。
     *
     * <p>之所以短，是因为它就是吊销窗口：被收回的角色如果漏掉了显式清除，最多还能生效
     * 这么久。控制台的改动会立即清除缓存，所以这只是兜底，而不是主要机制。
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final String KEY_PREFIX = "perm:user:";

    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public LoginUser currentIdentity(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !Integer.valueOf(UserAuthority.ACTIVE).equals(user.getStatus())) {
            return null;
        }
        if (user.getEnterpriseId() != null) {
            Enterprise enterprise = enterpriseMapper.selectById(user.getEnterpriseId());
            if (enterprise == null || !Integer.valueOf(Enterprise.Status.APPROVED).equals(enterprise.getStatus())) {
                return null;
            }
        }
        return LoginUser.builder().userId(user.getId()).username(user.getUsername())
                .enterpriseId(user.getEnterpriseId()).userType(user.getUserType())
                .status(user.getStatus()).build();
    }

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
            // 一个指向已不存在用户的 token 算不上会话。这里不做缓存：这个答案是稳定的，
            // 但缓存一个 null 需要哨兵值，而这一行每个请求最多只读一次。
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
            // TTL 仍然限制着陈旧的程度。因为清不掉缓存就让调用方的事务失败，等于把五分钟的
            // 延迟变成一次故障。
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
        List<UserRole> grants = userRoleMapper.findByUserId(userId);
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
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 该账号经由其角色拥有的全部权限。
     *
     * <p>用三次查询而不是一次 join，因为这里要紧的 join 都作用在小规模 id 集合上，
     * 而这正是 MyBatis 不用手写语句就能表达的形态。它只在缓存未命中时执行。
     */
    private Set<String> permissionsOf(Long userId) {
        List<UserRole> grants = userRoleMapper.findByUserId(userId);
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
        rolePermissionMapper.findByRoleIds(roleIds)
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
            // 既包含「Redis 挂了」，也包含「缓存的值是旧结构」。两者对调用方含义相同：
            // 去问数据库。
            log.debug("Authority cache unavailable for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void writeCache(Long userId, UserAuthority authority) {
        try {
            redis.opsForValue().set(KEY_PREFIX + userId,
                    objectMapper.writeValueAsString(authority), TTL);
        } catch (Exception e) {
            // 写不进去的缓存，就是下一次会未命中的缓存。这不足以成为让一次认证失败的理由。
            log.debug("Could not cache authority for user {}: {}", userId, e.getMessage());
        }
    }
}
