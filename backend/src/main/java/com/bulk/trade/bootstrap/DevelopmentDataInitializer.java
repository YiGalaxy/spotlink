package com.bulk.trade.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.entity.User;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.identity.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Seeds development accounts on startup.
 *
 * <p>Passwords are hashed here by the real {@link PasswordEncoder} rather than
 * pasted into a migration as a pre-computed BCrypt string. A copied hash cannot
 * be verified by reading it, and a wrong one only fails later, at login.
 *
 * <p>Every insert is idempotent on its own natural key, so a partially
 * completed run (for example one that failed halfway) is repaired simply by
 * starting the application again — there is no global "already seeded" flag to
 * get stuck on.
 *
 * <p>Disabled by default; enabled through {@code bulk.init.enabled}. It must
 * stay off in any environment real users can reach.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "bulk.init", name = "enabled", havingValue = "true")
public class DevelopmentDataInitializer implements ApplicationRunner {

    private static final String DEFAULT_PASSWORD = "Admin@123";

    private final UserMapper userMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        OffsetDateTime now = OffsetDateTime.now();

        // ---- platform operator: no tenant scope ----
        createUserIfAbsent(null, "admin", "平台管理员",
                User.Type.PLATFORM_OPERATOR, now);

        // ---- approved seller ----
        Enterprise seller = createEnterpriseIfAbsent(
                "ENT20260920001", "华东金属材料有限公司", "华东金属",
                "91330100MA2XXXXX01", "T0001", "王经理", "13800000001",
                Enterprise.Status.APPROVED, now);
        createUserIfAbsent(seller.getId(), "seller01", "王经理",
                User.Type.ENTERPRISE, now);

        // ---- approved buyer ----
        Enterprise buyer = createEnterpriseIfAbsent(
                "ENT20260920002", "浙江建工物资有限公司", "浙江建工",
                "91330100MA2XXXXX02", "T0002", "李采购", "13800000002",
                Enterprise.Status.APPROVED, now);
        createUserIfAbsent(buyer.getId(), "buyer01", "李采购",
                User.Type.ENTERPRISE, now);

        // ---- enterprise still waiting for review ----
        Enterprise pending = createEnterpriseIfAbsent(
                "ENT20260920003", "安徽有色金属贸易有限公司", "安徽有色",
                "91330100MA2XXXXX03", null, "赵主管", "13800000003",
                Enterprise.Status.PENDING, now);
        createUserIfAbsent(pending.getId(), "pending01", "赵主管",
                User.Type.ENTERPRISE, now);
    }

    private Enterprise createEnterpriseIfAbsent(String code, String name, String shortName,
                                                String uscc, String traderCode,
                                                String contactName, String contactPhone,
                                                int status, OffsetDateTime now) {
        Enterprise existing = enterpriseMapper.selectOne(Wrappers.<Enterprise>lambdaQuery()
                .eq(Enterprise::getEnterpriseCode, code));
        if (existing != null) {
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
        return enterprise;
    }

    private void createUserIfAbsent(Long enterpriseId, String username, String realName,
                                    int userType, OffsetDateTime now) {
        boolean exists = userMapper.exists(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username));
        if (exists) {
            return;
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
    }
}
