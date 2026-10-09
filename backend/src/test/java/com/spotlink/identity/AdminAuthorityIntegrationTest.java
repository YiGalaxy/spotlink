package com.spotlink.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.admin.service.AdminUserService;
import com.spotlink.identity.mapper.AuthorityRevisionMapper;
import com.spotlink.shared.security.UserAuthority;
import com.spotlink.shared.security.UserAuthorityProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.spotlink.shared.security.LoginUser;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAuthorityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAuthorityProvider authority;
    @Autowired AuthorityRevisionMapper revision;
    @Autowired AdminUserService users;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.apache.ibatis.session.SqlSession sqlSession;
    @Autowired com.spotlink.bootstrap.DevelopmentDataInitializer initializer;

    private JsonNode login(String username) throws Exception {
        var result = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", username, "password", "Admin@123"))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
    private String token(String username) throws Exception { return "Bearer " + login(username).path("accessToken").asText(); }
    private Long userId(String username) { return jdbc.queryForObject("SELECT id FROM t_user WHERE username=? AND deleted=0", Long.class, username); }
    private Long enterpriseId() { return jdbc.queryForObject("SELECT enterprise_id FROM t_user WHERE username='seller01'", Long.class); }
    private void actor(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.builder().userId(userId(username)).username(username).userType(2).status(1).build(), null, List.of()));
    }
    private void action(String auth, String path, Object body, boolean succeeds) throws Exception {
        mvc.perform(post(path).header("Authorization", auth).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(jsonPath("$.code").value(succeeds ? org.hamcrest.Matchers.is(0) : org.hamcrest.Matchers.not(0)));
    }

    @Test void auditorReadsButCannotMutateAndMemberCannotReadAdmin() throws Exception {
        String auditor = token("auditor01");
        mvc.perform(get("/api/admin/enterprises").header("Authorization", auditor)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/enterprises/" + enterpriseId() + "/freeze").header("Authorization", auditor)
                .contentType("application/json").content("{\"reason\":\"越权\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", auditor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/enterprises").header("Authorization", token("seller01"))).andExpect(status().isForbidden());
    }

    @Test void allUnavailableEnterpriseStatesDenyLoginAndPreviouslyIssuedToken() throws Exception {
        var credentials = login("seller01");
        String old = "Bearer " + credentials.path("accessToken").asText();
        for (int state : List.of(0, 2, 3, 4)) {
            jdbc.update("UPDATE t_enterprise SET status=? WHERE id=?", state, enterpriseId());
            mvc.perform(get("/api/auth/me").header("Authorization", old)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"seller01\",\"password\":\"Admin@123\"}"))
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        }
    }

    @Test void reviewTransitionsCannotBypassFrozenOrClosedAndFreezeReasonIsAudited() throws Exception {
        String admin = token("admin");
        String path = "/api/admin/enterprises/" + enterpriseId();
        action(admin, path + "/reject", Map.of("reason", "不能驳回已通过企业"), false);
        action(admin, path + "/freeze", Map.of("reason", " "), false);
        action(admin, path + "/freeze", Map.of("reason", "资质复核"), true);
        // 此测试整体回滚，提交后审计不得产生成功记录；提交审计由下方独立测试验证。
        action(admin, path + "/approve", Map.of(), false);
        action(admin, path + "/unfreeze", Map.of(), true);
        action(admin, path + "/unfreeze", Map.of(), false);
        jdbc.update("UPDATE t_enterprise SET status=4 WHERE id=?", enterpriseId());
        sqlSession.clearCache();
        for (String action : List.of("approve", "reject", "freeze", "unfreeze")) action(admin, path + "/" + action, Map.of("reason", "注销企业"), false);
    }

    @Test void pendingRejectionApprovalClearsReasonAndPreservesSeat() throws Exception {
        String admin = token("admin");
        Long id = enterpriseId();
        String seat = jdbc.queryForObject("SELECT trader_code FROM t_enterprise WHERE id=?", String.class, id);
        jdbc.update("UPDATE t_enterprise SET status=0 WHERE id=?", id);
        String path = "/api/admin/enterprises/" + id;
        action(admin, path + "/reject", Map.of("reason", "资料不全"), true);
        action(admin, path + "/approve", Map.of(), true);
        assertThat(jdbc.queryForObject("SELECT reject_reason FROM t_enterprise WHERE id=?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT trader_code FROM t_enterprise WHERE id=?", String.class, id)).isEqualTo(seat);
        action(admin, path + "/approve", Map.of(), false);
    }

    @Test void invalidStatusesSelfDisableAndTenantAdminGrantAreRejected() throws Exception {
        String admin = token("admin");
        String seller = "/api/admin/users/" + userId("seller01");
        action(admin, seller + "/status", Map.of(), false);
        action(admin, seller + "/status", Map.of("status", 99), false);
        action(admin, "/api/admin/users/" + userId("admin") + "/status", Map.of("status", 0), false);
        action(admin, seller + "/roles", Map.of("roleIds", List.of("9100")), false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_user_role WHERE user_id=? AND role_id=9100", Integer.class, userId("seller01"))).isZero();
    }

    @Test void onlyRemainingAdministratorCannotBeLockedOrLoseRole() throws Exception {
        String admin = token("admin");
        Long id = userId("admin");
        jdbc.update("UPDATE t_user SET status=0 WHERE id<>? AND enterprise_id IS NULL", id);
        action(admin, "/api/admin/users/" + id + "/roles", Map.of("roleIds", List.of()), false);
        assertThat(authority.load(id).has("admin:user:role")).isTrue();
        actor("auditor01");
        try { assertThatThrownBy(() -> users.changeStatus(id, 2, "锁定最后管理员")).hasMessageContaining("管理员"); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void unknownRoleRollsBackExistingGrantsAndVersion() throws Exception {
        String admin = token("admin");
        Long id = userId("auditor01");
        long before = revision.current();
        var grants = authority.rolesOf(id);
        action(admin, "/api/admin/users/" + id + "/roles", Map.of("roleIds", List.of("9100", "8700000000000099999")), false);
        assertThat(revision.current()).isEqualTo(before);
        assertThat(authority.rolesOf(id)).isEqualTo(grants);
    }

    @Test void restartingDevelopmentInitializerDoesNotRestoreRevokedGrants() {
        Long id = userId("auditor01");
        actor("admin");
        try {
            users.assignRoles(id, List.of());
            initializer.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
            assertThat(authority.rolesOf(id)).isEmpty();
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedFreezeAuditsReasonAndInvalidatesOldSession() throws Exception {
        long id = 8700000000000020101L;
        TransactionTemplate tx = new TransactionTemplate(transactions);
        String admin = token("admin");
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO t_enterprise(id,enterprise_code,name,unified_social_credit_code,status) VALUES(?,'AUTH-COMMIT-TEST','授权提交验收企业','AUTH-COMMIT-TEST',1)", id);
                jdbc.update("INSERT INTO t_user(id,enterprise_id,username,password,user_type,status) SELECT ?,?,'freeze_test_member',password,1,1 FROM t_user WHERE username='admin'", id + 1, id);
            });
            String member = token("freeze_test_member");
            action(admin, "/api/admin/enterprises/" + id + "/freeze", Map.of("reason", "资质复核提交验收"), true);
            mvc.perform(get("/api/auth/me").header("Authorization", member)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/audit-logs").header("Authorization", admin).param("module", "enterprise"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("资质复核提交验收")));
            action(admin, "/api/admin/enterprises/" + id + "/unfreeze", Map.of(), true);
            mvc.perform(get("/api/auth/me").header("Authorization", member)).andExpect(status().isOk());
        } finally {
            tx.executeWithoutResult(s -> {
                jdbc.update("DELETE FROM t_user WHERE id=?", id + 1);
                jdbc.update("DELETE FROM t_enterprise WHERE id=?", id);
            });
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSelfRevocationsKeepOneWorkingAdministrator() throws Exception {
        long first = 8700000000000020201L, second = first + 1;
        var statuses = jdbc.queryForList("SELECT id,status FROM t_user WHERE enterprise_id IS NULL AND deleted=0");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            tx.executeWithoutResult(s -> {
                authority.beginMutation();
                for (long id : List.of(first, second)) {
                    jdbc.update("INSERT INTO t_user(id,username,password,user_type,status) SELECT ?,?,password,2,1 FROM t_user WHERE username='admin'", id, "concurrent_admin_" + id);
                    jdbc.update("INSERT INTO t_user_role(id,user_id,role_id) VALUES(?,?,9100)", id + 10, id);
                }
                jdbc.update("UPDATE t_user SET status=0 WHERE enterprise_id IS NULL AND id NOT IN (?,?)", first, second);
                authority.evictAll();
            });
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (long id : List.of(first, second)) futures.add(workers.submit(() -> {
                actor("concurrent_admin_" + id);
                ready.countDown();
                try {
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("并发测试未启动");
                    users.assignRoles(id, List.of());
                    return true;
                } catch (com.spotlink.shared.exception.BusinessException e) {
                    assertThat(e.getMessage()).contains("管理员");
                    return false;
                } finally { SecurityContextHolder.clearContext(); }
            }));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int success = 0;
            for (var future : futures) if (future.get(20, java.util.concurrent.TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(1);
            assertThat(List.of(first, second).stream().filter(id -> authority.load(id).has("admin:user:role")).count()).isEqualTo(1);
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            tx.executeWithoutResult(s -> {
                authority.beginMutation();
                for (var row : statuses) jdbc.update("UPDATE t_user SET status=? WHERE id=?", row.get("status"), row.get("id"));
                jdbc.update("DELETE FROM t_user_role WHERE user_id IN (?,?)", first, second);
                jdbc.update("DELETE FROM t_user WHERE id IN (?,?)", first, second);
                authority.evictAll();
            });
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedRevocationIgnoresLateOldCacheAndRollbackDoesNotPublishPermissions() throws Exception {
        long id = 8700000000000020001L;
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO t_user(id,username,password,real_name,user_type,status) SELECT ?, 'revision_test_operator', password,'授权测试',2,1 FROM t_user WHERE username='admin'", id);
                jdbc.update("INSERT INTO t_user_role(id,user_id,role_id) VALUES(?,?,9100)", id + 1, id);
                authority.beginMutation(); authority.evictAll();
            });
            String oldToken = token("revision_test_operator");
            UserAuthority oldAuthority = authority.load(id);
            long oldRevision = revision.current();
            String admin = token("admin");
            action(admin, "/api/admin/users/" + id + "/roles", Map.of("roleIds", List.of("9101")), true);
            assertThat(revision.current()).isGreaterThan(oldRevision);
            // 模拟变更提交后才到达的旧查询缓存回填。
            redis.opsForValue().set("perm:user:" + oldRevision + ":" + id, json.writeValueAsString(oldAuthority), Duration.ofMinutes(5));
            mvc.perform(post("/api/admin/enterprises/" + enterpriseId() + "/freeze").header("Authorization", oldToken)
                    .contentType("application/json").content("{\"reason\":\"迟到旧权限\"}")).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/enterprises").header("Authorization", oldToken)).andExpect(status().isOk());
            long committedRevision = revision.current();
            actor("admin");
            tx.executeWithoutResult(s -> {
                users.assignRoles(id, List.of(9100L));
                assertThat(authority.load(id).has("admin:user:role")).isTrue();
                s.setRollbackOnly();
            });
            assertThat(revision.current()).isEqualTo(committedRevision);
            assertThat(authority.load(id).has("admin:user:role")).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
            tx.executeWithoutResult(s -> {
                authority.beginMutation();
                jdbc.update("DELETE FROM t_user_role WHERE user_id=?", id);
                jdbc.update("DELETE FROM t_user WHERE id=?", id);
                authority.evictAll();
            });
        }
    }
}
