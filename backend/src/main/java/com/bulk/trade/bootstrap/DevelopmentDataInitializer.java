package com.bulk.trade.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.entity.User;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.identity.entity.Permission;
import com.bulk.trade.identity.entity.Role;
import com.bulk.trade.identity.entity.RolePermission;
import com.bulk.trade.identity.entity.UserRole;
import com.bulk.trade.identity.mapper.PermissionMapper;
import com.bulk.trade.identity.mapper.RoleMapper;
import com.bulk.trade.identity.mapper.RolePermissionMapper;
import com.bulk.trade.identity.mapper.UserRoleMapper;
import java.util.List;
import com.bulk.trade.identity.mapper.UserMapper;
import com.bulk.trade.settlement.service.FundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 在启动时种下开发账号。
 *
 * <p>口令由真正的 {@link PasswordEncoder} 在这里现算，
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bulk.init", name = "enabled", havingValue = "true")
public class DevelopmentDataInitializer implements ApplicationRunner {

    private static final String DEFAULT_PASSWORD = "Admin@123";

    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final FundService fundService;

    @Override
    public void run(ApplicationArguments args) {
        OffsetDateTime now = OffsetDateTime.now();

        // ---- 平台运营：不隶属任何租户 ----
        //
        // 给两个，各自持有不同角色。只给一个全能的运营账号，能证明「权限」这套东西存在，
        // 却永远展示不出它做了什么——而两者的差别正是重点，所以它应该从登录页就能看见。
        User admin = createUserIfAbsent(null, "admin", "平台管理员",
                User.Type.PLATFORM_OPERATOR, now);
        grantRole(admin, "PLATFORM_ADMIN");

        User auditor = createUserIfAbsent(null, "auditor01", "审计员",
                User.Type.PLATFORM_OPERATOR, now);
        grantRole(auditor, "PLATFORM_AUDITOR");

        // ---- 已通过审核的卖方 ----
        Enterprise seller = createEnterpriseIfAbsent(
                "ENT20260920001", "华东金属材料有限公司", "华东金属",
                "91330100MA2XXXXX01", "T0001", "王经理", "13800000001",
                Enterprise.Status.APPROVED, now);
        createUserIfAbsent(seller.getId(), "seller01", "王经理",
                User.Type.ENTERPRISE, now);

        // ---- 已通过审核的买方 ----
        Enterprise buyer = createEnterpriseIfAbsent(
                "ENT20260920002", "浙江建工物资有限公司", "浙江建工",
                "91330100MA2XXXXX02", "T0002", "李采购", "13800000002",
                Enterprise.Status.APPROVED, now);
        createUserIfAbsent(buyer.getId(), "buyer01", "李采购",
                User.Type.ENTERPRISE, now);

        // ---- 还在等待审核的企业 ----
        Enterprise pending = createEnterpriseIfAbsent(
                "ENT20260920003", "安徽有色金属贸易有限公司", "安徽有色",
                "91330100MA2XXXXX03", null, "赵主管", "13800000003",
                Enterprise.Status.PENDING, now);
        createUserIfAbsent(pending.getId(), "pending01", "赵主管",
                User.Type.ENTERPRISE, now);
    }

    /**
     * 给一个账号授予平台角色，如果它还没有这个角色。
     *
     * <p>幂等判据是这条授权的自然键，而不是账号本身，所以在这里新增一个角色，会在已有
     * 数据库的下一次启动时生效——一台开发机就是这样捡到「首次种下之后才加进来」的角色的。
     *
     * <p>同时把系统角色的权限码补全到 {@code admin:*} 的全集。没有这一步，后来某个
     * 新增权限的迁移，会把所有现存运营账号锁在新页面之外，直到有人手写 SQL 去补。
     */
    private void grantRole(User user, String roleCode) {
        if (user == null) {
            return;
        }
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery()
                .eq(Role::getCode, roleCode)
                .isNull(Role::getEnterpriseId));
        if (role == null) {
            log.warn("Role {} is not seeded; skipping the grant to {}", roleCode, user.getUsername());
            return;
        }

        boolean held = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserId, user.getId())
                .eq(UserRole::getRoleId, role.getId())) > 0;
        if (!held) {
            UserRole grant = new UserRole();
            grant.setUserId(user.getId());
            grant.setRoleId(role.getId());
            userRoleMapper.insert(grant);
            log.info("Granted role {} to {}", roleCode, user.getUsername());
        }

        if (!"PLATFORM_ADMIN".equals(roleCode)) {
            return;
        }
        // 全部 admin:* 权限码，包括后来的迁移新增的那些。
        List<Permission> all = permissionMapper.selectList(Wrappers.<Permission>lambdaQuery()
                .likeRight(Permission::getCode, "admin:"));
        for (Permission permission : all) {
            boolean granted = rolePermissionMapper.selectCount(Wrappers.<RolePermission>lambdaQuery()
                    .eq(RolePermission::getRoleId, role.getId())
                    .eq(RolePermission::getPermissionId, permission.getId())) > 0;
            if (!granted) {
                RolePermission grant = new RolePermission();
                grant.setRoleId(role.getId());
                grant.setPermissionId(permission.getId());
                rolePermissionMapper.insert(grant);
                log.info("Added newly declared permission {} to {}", permission.getCode(), roleCode);
            }
        }
    }

    private Enterprise createEnterpriseIfAbsent(String code, String name, String shortName,
                                                String uscc, String traderCode,
                                                String contactName, String contactPhone,
                                                int status, OffsetDateTime now) {
        Enterprise existing = enterpriseMapper.selectOne(Wrappers.<Enterprise>lambdaQuery()
                .eq(Enterprise::getEnterpriseCode, code));
        if (existing != null) {
            ensureAccount(existing);
            return existing;
        }

        Enterprise enterprise = new Enterprise();
        enterprise.setEnterpriseCode(code);
        enterprise.setName(name);
        enterprise.setShortName(shortName);
        enterprise.setUnifiedSocialCreditCode(uscc);
        enterprise.setLegalPerson(contactName);
        enterprise.setContactName(contactName);
        enterprise.setContactPhone(contactPhone);
        enterprise.setProvince("浙江省");
        enterprise.setCity("杭州市");
        enterprise.setTraderCode(traderCode);
        enterprise.setStatus(status);
        enterprise.setQualifications("[]");
        enterprise.setRegisteredAt(now);
        if (status == Enterprise.Status.APPROVED) {
            enterprise.setApprovedAt(now);
        }
        enterpriseMapper.insert(enterprise);
        log.info("Seeded enterprise {} ({}), status={}", name, code, status);
        ensureAccount(enterprise);
        return enterprise;
    }

    /**
     * 已通过审核的企业要有一个资金账户。
     *
     * <p>这曾经是一条无人建立的不变量：迁移 V6 给当时已存在的企业各开了一个账户，
     * 此后通过审核的企业则没有——种子账号 {@code seller01} 就在其中，于是新库上的
     * 第一笔资金操作会撞上「资金账户不存在」。审核那边现在会开账户，这里补的是那些
     * 在那次改动之前就已经种下的库，包括这台机器上这一个。
     *
     * <p>幂等，所以每次启动都走一遍是安全的。
     */
    private void ensureAccount(Enterprise enterprise) {
        if (enterprise.getStatus() != null && enterprise.getStatus() == Enterprise.Status.APPROVED) {
            fundService.openAccountIfAbsent(enterprise.getId(), enterprise.getEnterpriseCode());
        }
    }

    /**
     * 账号不存在就创建，两种情况都把它返回。
     *
     * <p>这里返回已存在的行很要紧：角色由调用方授予，而一次只发生在「创建账号那一跑」
     * 里的授权，永远到不了一个在角色出现之前就已经种好的数据库。
     */
    private User createUserIfAbsent(Long enterpriseId, String username, String realName,
                                    int userType, OffsetDateTime now) {
        User existing = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username));
        if (existing != null) {
            return existing;
        }

        User user = new User();
        user.setEnterpriseId(enterpriseId);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setRealName(realName);
        user.setUserType(userType);
        user.setStatus(User.Status.ACTIVE);
        userMapper.insert(user);
        log.info("Seeded user '{}' ({}), enterpriseId={}", username, realName, enterpriseId);
        return user;
    }
}
