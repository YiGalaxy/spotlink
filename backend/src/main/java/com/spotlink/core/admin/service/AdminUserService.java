package com.spotlink.admin.service;

import com.spotlink.admin.dto.AdminViews;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.entity.Permission;
import com.spotlink.identity.entity.Role;
import com.spotlink.identity.entity.RolePermission;
import com.spotlink.identity.entity.User;
import com.spotlink.identity.entity.UserRole;
import com.spotlink.identity.service.access.EnterpriseAccess;
import com.spotlink.identity.service.access.PermissionAccess;
import com.spotlink.identity.service.access.RoleAccess;
import com.spotlink.identity.service.access.RolePermissionAccess;
import com.spotlink.identity.service.access.UserAccess;
import com.spotlink.identity.service.access.UserRoleAccess;
import com.spotlink.shared.audit.AuditService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.SecurityUtils;
import com.spotlink.shared.security.UserAuthority;
import com.spotlink.shared.security.UserAuthorityProvider;
import com.spotlink.shared.web.ResultCode;
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
 * 账号，以及它们被允许是什么。
 *
 * <p>这里的一切都贯穿着两道防线，二者的存在都是为了不让运营人员把自己锁在脚下这
 * 个运营台之外：你不能禁用自己的账号，平台也不能落到没人能分配角色的地步。
 *
 * <p>第二道是容易被忽略的那一道。去掉自己的管理员角色，看起来是一次再合理不过的
 * 点击，而它从运营台里是救不回来的——唯一的路子是直接对数据库执行 SQL。拒绝一次
 * 点击，比等来一场只有 DBA 才能修好的故障要便宜得多。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 持有该权限的人可以把它授予他人。 */
    private static final String PERMISSION_ASSIGN_ROLE = "admin:user:role";

    private final UserAccess userAccess;
    private final EnterpriseAccess enterpriseAccess;
    private final RoleAccess roleAccess;
    private final RolePermissionAccess rolePermissionAccess;
    private final PermissionAccess permissionAccess;
    private final UserRoleAccess userRoleAccess;
    private final UserAuthorityProvider authorityProvider;
    private final AuditService audit;

    public List<AdminViews.UserRow> search(String keyword, Integer status) {
        List<User> users = userAccess.searchAccounts(keyword, status);
        if (users.isEmpty()) {
            return List.of();
        }

        Set<Long> enterpriseIds = users.stream()
                .map(User::getEnterpriseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> enterprises = enterpriseIds.isEmpty() ? Map.of()
                : enterpriseAccess.selectBatchIds(enterpriseIds).stream()
                        .collect(Collectors.toMap(Enterprise::getId, Enterprise::getName));

        return users.stream()
                .map(user -> toRow(user, enterprises.get(user.getEnterpriseId())))
                .toList();
    }

    /**
     * 启用或禁用一个账号。
     *
     * <p>在该账号的下一次请求上就生效，而不是等它的令牌过期：过滤器读取的权限里
     * 包含状态，所以被禁用的账号会立刻被拒。只有当"禁用"是这个意思时，这个按钮才
     * 值得存在。
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
        if (userAccess.updateById(user) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该账号正在被其他操作修改，请重试");
        }
        // 不做这一步，改动就得等缓存 TTL 到期——那意味着一个已被禁用的账号还能
        // 继续用上五分钟。
        authorityProvider.evict(id);

        audit.record("user", "change-status", "USER", id, before,
                "status=" + status + (reason == null || reason.isBlank() ? "" : ", reason=" + reason));
        log.info("User {} status set to {} by {}", user.getUsername(), status,
                SecurityUtils.currentUsername());
        return toRow(user, enterpriseName(user.getEnterpriseId()));
    }

    /**
     * 整体替换一个账号的平台角色。
     *
     * <p>先物理删除再插入，关联表本就是为此设计的：它没有软删除列，因为被撤销的授权
     * 必须真的被撤销，而不是被标记一下；否则每个查询都得记得去过滤，而一旦有一个查询
     * 忘了，那个角色就仍然在生效。
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

        userRoleAccess.deleteByUserId(userId);
        for (Long roleId : wanted) {
            if (roleAccess.selectById(roleId) == null) {
                throw BusinessException.of(ResultCode.ADMIN_ROLE_NOT_FOUND);
            }
            UserRole grant = new UserRole();
            grant.setUserId(userId);
            grant.setRoleId(roleId);
            userRoleAccess.insert(grant);
        }

        authorityProvider.evict(userId);
        audit.record("user", "assign-role", "USER", userId, rolesBefore, List.copyOf(wanted));
        log.info("Roles for {} set to {} by {}", user.getUsername(), wanted,
                SecurityUtils.currentUsername());
        return toRow(user, enterpriseName(user.getEnterpriseId()));
    }

    /** 全部平台角色，以及每个角色携带的权限。 */
    public List<AdminViews.RoleRow> roles() {
        List<Role> roles = roleAccess.findPlatformRoles();
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

    /** 所有可授予的权限，供勾选列表使用。 */
    public List<AdminViews.PermissionRow> permissions() {
        return permissionAccess.findAllOrdered().stream()
                .map(p -> new AdminViews.PermissionRow(
                        p.getId(), p.getCode(), p.getName(), p.getPermType(),
                        p.getPath(), p.getSortOrder()))
                .toList();
    }

    // ------------------------------------------------------------------

    /**
     * 这个账号是不是唯一还能发放角色的那一个。
     *
     * <p>在变更之前问，而不是变更之后，因为它所防范的那个状态，是运营台本身走不
     * 出来的。
     */
    private boolean isLastRoleHolder(Long exceptUserId) {
        List<User> candidates = userAccess.findActiveAccounts();
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

    /** 拟定的角色集合是否仍然带有分配角色的能力。 */
    private boolean proposedKeepsAssignRole(List<Long> roleIds) {
        return !roleIds.isEmpty()
                && permissionCodesByRole(new HashSet<>(roleIds)).values().stream()
                        .anyMatch(codes -> codes.contains(PERMISSION_ASSIGN_ROLE));
    }

    private Map<Long, List<String>> permissionCodesByRole(Set<Long> roleIds) {
        List<RolePermission> grants = rolePermissionAccess.findByRoleIds(roleIds);
        if (grants.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> codeById = permissionAccess
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
        User user = userAccess.selectById(id);
        if (user == null) {
            throw BusinessException.of(ResultCode.ADMIN_USER_NOT_FOUND);
        }
        return user;
    }

    private String enterpriseName(Long id) {
        if (id == null) {
            return null;
        }
        Enterprise enterprise = enterpriseAccess.selectById(id);
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
