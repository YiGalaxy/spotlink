package com.bulk.trade.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.admin.dto.AdminViews;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.entity.Permission;
import com.bulk.trade.identity.entity.Role;
import com.bulk.trade.identity.entity.RolePermission;
import com.bulk.trade.identity.entity.User;
import com.bulk.trade.identity.entity.UserRole;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.identity.mapper.PermissionMapper;
import com.bulk.trade.identity.mapper.RoleMapper;
import com.bulk.trade.identity.mapper.RolePermissionMapper;
import com.bulk.trade.identity.mapper.UserMapper;
import com.bulk.trade.identity.mapper.UserRoleMapper;
import com.bulk.trade.shared.audit.AuditService;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.security.UserAuthority;
import com.bulk.trade.shared.security.UserAuthorityProvider;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Accounts, and what they are allowed to be.
 *
 * <p>Two guards run through everything here, and both exist to stop an operator
 * locking themselves out of the console they are standing in: you may not
 * disable your own account, and the platform may not be left with nobody able
 * to assign roles.
 *
 * <p>The second is the one that is easy to miss. Dropping your own admin role
 * is a perfectly reasonable-looking click and it is unrecoverable from the
 * console — the only way back is SQL against the database. Refusing one click
 * is cheaper than an outage only a DBA can fix.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** The authority whose holders can hand it to others. */
    private static final String PERMISSION_ASSIGN_ROLE = "admin:user:role";

    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserAuthorityProvider authorityProvider;
    private final AuditService audit;

    public List<AdminViews.UserRow> search(String keyword, Integer status) {
        var query = Wrappers.<User>lambdaQuery().orderByDesc(User::getId).last("limit 200");
        if (status != null) {
            query.eq(User::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(User::getUsername, keyword.trim())
                    .or().like(User::getRealName, keyword.trim()));
        }

        List<User> users = userMapper.selectList(query);
        if (users.isEmpty()) {
            return List.of();
        }

        Set<Long> enterpriseIds = users.stream()
                .map(User::getEnterpriseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> enterprises = enterpriseIds.isEmpty() ? Map.of()
                : enterpriseMapper.selectBatchIds(enterpriseIds).stream()
                        .collect(Collectors.toMap(Enterprise::getId, Enterprise::getName));

        return users.stream()
                .map(user -> toRow(user, enterprises.get(user.getEnterpriseId())))
                .toList();
    }

    /**
     * Enables or disables an account.
     *
     * <p>Takes effect on the account's next request rather than when its token
     * expires: the authority the filter reads includes status, so a disabled
     * account is declined immediately. That is what "disable" has to mean for
     * the button to be worth having.
     */
    @Transactional
    public AdminViews.UserRow changeStatus(Long id, Integer status, String reason) {
        User user = require(id);
        if (id.equals(SecurityUtils.currentUserId())) {
            throw BusinessException.of(ResultCode.ADMIN_SELF_OPERATION, "不能禁用自己的账号");
        }
        if (status != null && status == User.Status.DISABLED && isLastRoleHolder(id)) {
            throw BusinessException.of(ResultCode.ADMIN_LAST_ADMIN);
        }

        String before = "status=" + user.getStatus();
        user.setStatus(status);
        if (userMapper.updateById(user) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该账号正在被其他操作修改，请重试");
        }
        // Without this the change waits for the cache TTL — five minutes of a
        // disabled account continuing to work.
        authorityProvider.evict(id);

        audit.record("user", "change-status", "USER", id, before,
                "status=" + status + (reason == null || reason.isBlank() ? "" : ", reason=" + reason));
        log.info("User {} status set to {} by {}", user.getUsername(), status,
                SecurityUtils.currentUsername());
        return toRow(user, enterpriseName(user.getEnterpriseId()));
    }

    /**
     * Replaces an account's platform roles.
     *
     * <p>Hard delete then insert, which is what the join table is shaped for: it
     * has no soft-delete column because a revoked grant has to actually be
     * revoked rather than marked, or every query would have to remember to
     * filter and one that forgot would leave the role in force.
     */
    @Transactional
    public AdminViews.UserRow assignRoles(Long userId, List<Long> roleIds) {
        User user = require(userId);
        List<Long> wanted = roleIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(roleIds));

        UserAuthority before = authorityProvider.load(userId);
        if (before != null && before.has(PERMISSION_ASSIGN_ROLE)
                && !proposedKeepsAssignRole(wanted) && isLastRoleHolder(userId)) {
            throw BusinessException.of(ResultCode.ADMIN_LAST_ADMIN);
        }

        List<String> rolesBefore = List.copyOf(authorityProvider.rolesOf(userId));

        userRoleMapper.delete(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
        for (Long roleId : wanted) {
            if (roleMapper.selectById(roleId) == null) {
                throw BusinessException.of(ResultCode.ADMIN_ROLE_NOT_FOUND);
            }
            UserRole grant = new UserRole();
            grant.setUserId(userId);
            grant.setRoleId(roleId);
            userRoleMapper.insert(grant);
        }

        authorityProvider.evict(userId);
        audit.record("user", "assign-role", "USER", userId, rolesBefore, List.copyOf(wanted));
        log.info("Roles for {} set to {} by {}", user.getUsername(), wanted,
                SecurityUtils.currentUsername());
        return toRow(user, enterpriseName(user.getEnterpriseId()));
    }

    /** Every platform role, with the authorities it carries. */
    public List<AdminViews.RoleRow> roles() {
        List<Role> roles = roleMapper.selectList(Wrappers.<Role>lambdaQuery()
                .isNull(Role::getEnterpriseId).orderByAsc(Role::getId));
        if (roles.isEmpty()) {
            return List.of();
        }
        Set<Long> roleIds = roles.stream().map(Role::getId).collect(Collectors.toSet());

        Map<Long, List<String>> byRole = permissionCodesByRole(roleIds);
        return roles.stream()
                .map(role -> new AdminViews.RoleRow(
                        role.getId(), role.getCode(), role.getName(), role.getDescription(),
                        Boolean.TRUE.equals(role.getIsSystem()),
                        byRole.getOrDefault(role.getId(), List.of())))
                .toList();
    }

    /** Everything that can be granted, for the checkbox list. */
    public List<AdminViews.PermissionRow> permissions() {
        return permissionMapper.selectList(Wrappers.<Permission>lambdaQuery()
                        .orderByAsc(Permission::getSortOrder).orderByAsc(Permission::getId)).stream()
                .map(p -> new AdminViews.PermissionRow(
                        p.getId(), p.getCode(), p.getName(), p.getPermType(),
                        p.getPath(), p.getSortOrder()))
                .toList();
    }

    // ------------------------------------------------------------------

    /**
     * Whether this account is the only one that could still hand out roles.
     *
     * <p>Asked before a change rather than after, because the state it guards
     * against is one the console cannot leave.
     */
    private boolean isLastRoleHolder(Long exceptUserId) {
        List<User> candidates = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getStatus, User.Status.ACTIVE));
        for (User candidate : candidates) {
            if (candidate.getId().equals(exceptUserId)) {
                continue;
            }
            UserAuthority authority = authorityProvider.load(candidate.getId());
            if (authority != null && authority.has(PERMISSION_ASSIGN_ROLE)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the proposed role set still carries the ability to assign roles. */
    private boolean proposedKeepsAssignRole(List<Long> roleIds) {
        return !roleIds.isEmpty()
                && permissionCodesByRole(new HashSet<>(roleIds)).values().stream()
                        .anyMatch(codes -> codes.contains(PERMISSION_ASSIGN_ROLE));
    }

    private Map<Long, List<String>> permissionCodesByRole(Set<Long> roleIds) {
        List<RolePermission> grants = rolePermissionMapper.selectList(
                Wrappers.<RolePermission>lambdaQuery().in(RolePermission::getRoleId, roleIds));
        if (grants.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> codeById = permissionMapper
                .selectBatchIds(grants.stream().map(RolePermission::getPermissionId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Permission::getId, Permission::getCode));

        Map<Long, List<String>> byRole = new java.util.HashMap<>();
        for (RolePermission grant : grants) {
            String code = codeById.get(grant.getPermissionId());
            if (code != null) {
                byRole.computeIfAbsent(grant.getRoleId(), key -> new ArrayList<>()).add(code);
            }
        }
        byRole.values().forEach(java.util.Collections::sort);
        return byRole;
    }

    private User require(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw BusinessException.of(ResultCode.ADMIN_USER_NOT_FOUND);
        }
        return user;
    }

    private String enterpriseName(Long id) {
        if (id == null) {
            return null;
        }
        Enterprise enterprise = enterpriseMapper.selectById(id);
        return enterprise == null ? null : enterprise.getName();
    }

    private AdminViews.UserRow toRow(User user, String enterpriseName) {
        return new AdminViews.UserRow(
                user.getId(), user.getUsername(), user.getRealName(), user.getPhone(),
                user.getEmail(), user.getUserType(), userTypeText(user.getUserType()),
                user.getStatus(), statusText(user.getStatus()),
                user.getEnterpriseId(), enterpriseName,
                List.copyOf(authorityProvider.rolesOf(user.getId())),
                user.getLastLoginAt(), user.getCreatedAt());
    }

    private String userTypeText(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case User.Type.ENTERPRISE -> "企业用户";
            case User.Type.PLATFORM_OPERATOR -> "平台运营";
            case User.Type.SUPER_ADMIN -> "超级管理员";
            default -> "未知";
        };
    }

    static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case User.Status.DISABLED -> "已禁用";
            case User.Status.ACTIVE -> "正常";
            case User.Status.LOCKED -> "已锁定";
            default -> "未知";
        };
    }
}
