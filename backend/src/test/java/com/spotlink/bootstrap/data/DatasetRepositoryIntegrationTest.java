package com.spotlink.bootstrap.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spotlink.bootstrap.data.repository.DatasetRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest
class DatasetRepositoryIntegrationTest {
    @Autowired DataSource datasource;
    @Autowired JdbcTemplate jdbc;
    private static final long PREFIX = 8700000000000009000L;

    @BeforeEach @AfterEach void cleanOwnedTestRows() {
        // 只删除本测试自己的固定编号及台账，不重建或清空隔离库。
        jdbc.update("DELETE FROM t_dataset_record WHERE record_id BETWEEN ? AND ?", PREFIX, PREFIX + 20);
        jdbc.update("DELETE FROM t_dataset_import WHERE batch='c09-integration'");
        for (String table : List.of("t_knowledge_chunk", "t_knowledge_doc", "t_inventory_note", "t_user", "t_warehouse", "t_commodity_category", "t_enterprise"))
            jdbc.update("DELETE FROM " + table + " WHERE id BETWEEN ? AND ?", PREFIX, PREFIX + 20);
    }

    private DatasetPackage pack() throws Exception {
        List<DatasetPackage.Row> rows = new ArrayList<>();
        rows.add(row("t_enterprise", 1, """
                {"enterprise_code":"C09-TEST-ENT","name":"虚构导入测试企业","short_name":"测试","unified_social_credit_code":"C09-SYNTHETIC","contact_name":"测试","contact_phone":"000","contact_email":"demo@example.invalid","trader_code":"C09TEST","status":1,"qualifications":[]}
                """));
        rows.add(row("t_user", 2, "{\"enterprise_id\":\"" + (PREFIX + 1) + "\",\"username\":\"min_c09_test\",\"password\":\"LOCAL_DEMO_BCRYPT\",\"real_name\":\"虚构用户\",\"user_type\":1,\"status\":1}"));
        rows.add(row("t_warehouse", 3, """
                {"code":"C09-TEST-WH","name":"虚构仓库","province":"示例省","city":"示例市","address":"虚构地址","contact_name":"测试","contact_phone":"000","status":1}
                """));
        rows.add(row("t_commodity_category", 4, """
                {"code":"C09-TEST-CAT","name":"测试电解铜","path":"/c09/","spec_schema":[],"unit":"吨","status":1}
                """));
        rows.add(row("t_inventory_note", 5, "{\"note_no\":\"C09-TEST-IN\",\"enterprise_id\":\"" + (PREFIX + 1) + "\",\"category_id\":\"" + (PREFIX + 4) + "\",\"warehouse_id\":\"" + (PREFIX + 3) + "\",\"commodity_name\":\"测试铜\",\"spec\":{},\"total_quantity\":\"10.000\",\"available_quantity\":\"10.000\",\"frozen_quantity\":\"0.000\",\"unit\":\"吨\",\"status\":2,\"version\":0,\"remark\":\"虚构库存\"}"));
        rows.add(row("t_knowledge_doc", 6, """
                {"doc_code":"C09-TEST-DOC","title":"测试原文","category":"GUIDE","source":"data/knowledge/test.md","version":"v1","status":1,"remark":"公开测试文档"}
                """));
        rows.add(row("t_knowledge_chunk", 7, "{\"doc_id\":\"" + (PREFIX + 6) + "\",\"chunk_index\":0,\"content\":\"运费需要实际物流报价，不得编造。\",\"token_count\":0}"));
        DatasetPackage.validate(DatasetPackage.JSON.valueToTree(rows));
        ObjectNode manifest = DatasetPackage.JSON.createObjectNode();
        manifest.put("dataset", "minimal").put("ruleVersion", "v1").put("batch", "c09-integration").put("baseTime", "2026-10-08T00:00:00.000Z").put("sourceHash", "a".repeat(64));
        manifest.putObject("schema").putArray("requiredMigrations").add(14);
        manifest.putObject("files").putObject("records.json").put("sha256", DatasetPackage.hash(DatasetPackage.JSON.writeValueAsBytes(rows)));
        return new DatasetPackage(manifest, List.copyOf(rows));
    }
    private DatasetPackage.Row row(String table, int suffix, String fields) throws Exception {
        ObjectNode values = (ObjectNode) DatasetPackage.JSON.readTree(fields);
        values.put("id", Long.toString(PREFIX + suffix)); values.put("created_at", "2026-10-08 00:00:00");
        if (DatasetPackage.COLUMNS.get(table).contains("updated_at")) values.put("updated_at", "2026-10-08 00:00:00");
        return new DatasetPackage.Row(table.startsWith("t_knowledge_") ? "KNOWLEDGE-BASE" : "IDENTITY-BASE", table, values);
    }

    @Test void repeatedImportPreservesUserChangesAndRuntimeChecksCurrentInvariants() throws Exception {
        var pack = pack();
        try (var repository = new DatasetRepository(datasource.getConnection())) {
            assertThat(repository.importPhase(pack, "business")).isEqualTo("IMPORTED");
            assertThat(repository.importPhase(pack, "knowledge")).isEqualTo("IMPORTED");
            repository.verify(pack, true);
            String password = jdbc.queryForObject("SELECT password FROM t_user WHERE id=?", String.class, PREFIX + 2);
            assertThat(password).startsWith("$2a$").doesNotContain("Admin@123");
            jdbc.update("UPDATE t_inventory_note SET total_quantity=8,available_quantity=6,frozen_quantity=2 WHERE id=?", PREFIX + 5);
            jdbc.update("UPDATE t_user SET password='changed-by-user' WHERE id=?", PREFIX + 2);
            assertThat(repository.importPhase(pack, "business")).isEqualTo("SKIPPED");
            assertThat(repository.importPhase(pack, "knowledge")).isEqualTo("SKIPPED");
            repository.verify(pack, false);
            assertThatThrownBy(() -> repository.verify(pack, true)).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT password FROM t_user WHERE id=?", String.class, PREFIX + 2)).isEqualTo("changed-by-user");
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM t_inventory_note WHERE id=?", java.math.BigDecimal.class, PREFIX + 5)).isEqualByComparingTo("6");
            assertThat(repository.vectorStatus().get("embedded").toString()).isEqualTo("0");
        }
    }

    @Test void businessNumberConflictRollsBackEarlierRowsAndLedgerWithoutTouchingManualData() throws Exception {
        jdbc.update("INSERT INTO t_warehouse(id,code,name,status) VALUES(?,'C09-TEST-WH','人工测试仓库',1)", PREFIX + 13);
        try (var repository = new DatasetRepository(datasource.getConnection())) {
            assertThatThrownBy(() -> repository.importPhase(pack(), "business")).isInstanceOf(DatasetRepository.Conflict.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_enterprise WHERE id=?", Integer.class, PREFIX + 1)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_dataset_import WHERE batch='c09-integration'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM t_warehouse WHERE id=?", String.class, PREFIX + 13)).isEqualTo("人工测试仓库");
    }

    @Test void databaseConstraintFailureRollsBackTheWholeBusinessBatch() throws Exception {
        var pack = pack();
        ((ObjectNode) pack.rows().get(4).values()).put("available_quantity", "11.000");
        try (var repository = new DatasetRepository(datasource.getConnection())) {
            assertThatThrownBy(() -> repository.importPhase(pack, "business")).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_inventory_quantity_balance");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_user WHERE id=?", Integer.class, PREFIX + 2)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_dataset_record WHERE record_id BETWEEN ? AND ?", Integer.class, PREFIX, PREFIX + 20)).isZero();
    }

    @Test void missingSchemaAndChangedSuccessfulContentAreRejected() throws Exception {
        var pack = pack();
        var invalid = (ObjectNode) pack.manifest().deepCopy();
        ((ObjectNode) invalid.path("schema")).putArray("requiredMigrations").add(999);
        try (var repository = new DatasetRepository(datasource.getConnection())) {
            assertThatThrownBy(() -> repository.importPhase(new DatasetPackage(invalid, pack.rows()), "business")).isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.importPhase(pack, "business")).isEqualTo("IMPORTED");
            var changed = (ObjectNode) pack.manifest().deepCopy(); changed.put("sourceHash", "b".repeat(64));
            assertThatThrownBy(() -> repository.importPhase(new DatasetPackage(changed, pack.rows()), "business")).isInstanceOf(DatasetRepository.Conflict.class);
            assertThatThrownBy(() -> repository.importPhase(new DatasetPackage(changed, pack.rows()), "knowledge")).isInstanceOf(DatasetRepository.Conflict.class);
        }
    }

    @Test void databaseLockExcludesOtherImportersAndReleasesAfterClosing() throws Exception {
        try (var first = new DatasetRepository(datasource.getConnection())) {
            assertThatThrownBy(() -> new DatasetRepository(datasource.getConnection())).isInstanceOf(DatasetRepository.Conflict.class);
        }
        try (var second = new DatasetRepository(datasource.getConnection())) { assertThat(second.status()).isNotNull(); }
    }

    @Test void knowledgeRequiresCommittedBusinessAndDoesNotInventVectors() throws Exception {
        var pack = pack();
        try (var repository = new DatasetRepository(datasource.getConnection())) {
            assertThatThrownBy(() -> repository.importPhase(pack, "knowledge")).isInstanceOf(DatasetRepository.Conflict.class);
            repository.importPhase(pack, "business"); repository.importPhase(pack, "knowledge");
            assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM t_knowledge_chunk WHERE id=?", Boolean.class, PREFIX + 7)).isTrue();
            assertThat(repository.status()).anyMatch(row -> row.get("phase").equals("knowledge"));
        }
    }
}
