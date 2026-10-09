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
import com.spotlink.identity.mapper.AuthorityRevisionMapper;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 数据库版本决定缓存键；Redis 故障、事务回滚或旧查询回填均不能恢复被撤销的权限。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthorityServiceImpl implements UserAuthorityProvider {

    /** 只控制旧键回收时间；撤销生效不依赖 TTL。 */
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final String KEY_PREFIX = "perm:user:";

    private final UserMapper userMapper;
    private final AuthorityRevisionMapper revisionMapper;
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

        long revision = revisionMapper.current();
        // 不把事务中尚未提交的授权写入共享缓存，也不读取事务前的副本。
        boolean cacheable = !TransactionSynchronizationManager.isActualTransactionActive();
        UserAuthority cached = cacheable ? readCache(userId, revision) : null;
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
        if (cacheable) writeCache(userId, revision, authority);
        return authority;
    }

    @Override
    public void beginMutation() {
        requireTransaction();
        revisionMapper.lock();
    }

    @Override
    public void evict(Long userId) {
        if (userId != null) evictAll();
    }

    @Override
    public void evictAll() {
        requireTransaction();
        if (revisionMapper.advance() != 1) throw new IllegalStateException("授权版本记录缺失");
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("授权变更必须在业务事务内执行");
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
        // 被逻辑删除的角色不再参与权限关联查询。
        roleIds.clear();
        roles.forEach(role -> roleIds.add(role.getId()));

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

    private UserAuthority readCache(Long userId, long revision) {
        try {
            String json = redis.opsForValue().get(cacheKey(userId, revision));
            return json == null ? null : objectMapper.readValue(json, UserAuthority.class);
        } catch (Exception e) {
            // 既包含「Redis 挂了」，也包含「缓存的值是旧结构」。两者对调用方含义相同：
            // 去问数据库。
            log.debug("Authority cache unavailable for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void writeCache(Long userId, long revision, UserAuthority authority) {
        try {
            redis.opsForValue().set(cacheKey(userId, revision),
                    objectMapper.writeValueAsString(authority), TTL);
        } catch (Exception e) {
            // 写不进去的缓存，就是下一次会未命中的缓存。这不足以成为让一次认证失败的理由。
            log.debug("Could not cache authority for user {}: {}", userId, e.getMessage());
        }
    }

    private String cacheKey(Long userId, long revision) {
        return KEY_PREFIX + revision + ":" + userId;
    }
}
