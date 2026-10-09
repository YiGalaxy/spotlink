package com.spotlink.bootstrap.data.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.spotlink.bootstrap.data.DatasetPackage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static com.spotlink.bootstrap.data.DatasetPackage.require;

/** 独立部署作业的数据库适配器。SQL 标识符仅来自应用内的固定白名单。 */
public final class DatasetRepository implements AutoCloseable {
    private final Connection connection;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final String lock;
    public static final String DEMO_PASSWORD = "Admin@123";
    public static final class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }

    public DatasetRepository(Connection connection) throws SQLException {
        this.connection = connection;
        this.jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        this.jdbc.setQueryTimeout(30);
        lock = "spotlink-data-" + DatasetPackage.hash(connection.getCatalog().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 32);
        if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, lock))) {
            connection.close(); throw new Conflict("另一个导入或验证作业正在运行");
        }
    }

    public String importPhase(DatasetPackage pack, String phase) throws Exception {
        require(Set.of("business", "knowledge").contains(phase), "未知导入阶段");
        checkSchema(pack);
        if (phase.equals("knowledge")) {
            var business = ledger(pack, "business");
            if (business.isEmpty()) throw new Conflict("先导入当前数据包的业务阶段");
            compatible(pack, "business", business.getFirst());
        }
        var existing = ledger(pack, phase);
        if (!existing.isEmpty()) {
            compatible(pack, phase, existing.getFirst());
            verifyPhase(pack, phase, false);
            return "SKIPPED";
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM t_dataset_import WHERE dataset<>? OR rule_version<>?", Integer.class, pack.dataset(), pack.version()) != 0)
            throw new Conflict("当前库已有其他数据包/版本；使用显式升级或新的演示库");
        List<DatasetPackage.Row> rows = pack.phase(phase);
        require(!rows.isEmpty(), "该阶段没有记录");
        connection.setAutoCommit(false);
        boolean commitAttempted = false;
        try {
            // 先记台账，再写归属；只有本事务提交后其他连接才能看到成功。
            jdbc.update("""
                    INSERT INTO t_dataset_import(dataset,rule_version,phase,source_hash,records_hash,batch,base_time,row_count)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, pack.dataset(), pack.version(), phase, pack.manifest().path("sourceHash").asText(), pack.recordsHash(),
                    pack.manifest().path("batch").asText(), pack.manifest().path("baseTime").asText(), rows.size());
            for (var row : rows) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM " + row.table() + " WHERE id=?", Integer.class, row.id()) != 0)
                    throw new Conflict("记录 ID 已存在且不属于本批次：" + row.table() + "/" + row.id());
                insert(row);
                jdbc.update("""
                        INSERT INTO t_dataset_record(table_name,record_id,business_key,dataset,rule_version,phase,scenario_id,record_hash)
                        VALUES(?,?,?,?,?,?,?,?)
                        """, row.table(), row.id(), row.key(), pack.dataset(), pack.version(), phase, row.scenarioId(),
                        DatasetPackage.hash(DatasetPackage.JSON.writeValueAsBytes(row.values())));
            }
            verifyPhase(pack, phase, true);
            commitAttempted = true;
            connection.commit();
            return "IMPORTED";
        } catch (Exception failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            if (commitAttempted) throw new SQLException("提交结果待核验，请查看 data-status.cmd 的数据库台账后重试；不能依据本地提示认定回滚", failure);
            throw failure;
        } finally { connection.setAutoCommit(true); }
    }

    private void insert(DatasetPackage.Row row) {
        List<String> columns = DatasetPackage.COLUMNS.get(row.table());
        Object[] values = columns.stream().map(column -> bound(row.values().get(column), column)).toArray();
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        try {
            jdbc.update("INSERT INTO " + row.table() + " (" + String.join(",", columns) + ") VALUES (" + placeholders + ")", values);
        } catch (org.springframework.dao.DuplicateKeyException failure) {
            throw new Conflict("业务编号或唯一字段与已有数据冲突：" + row.table() + "/" + row.key());
        }
    }

    private Object bound(JsonNode value, String column) {
        if (value.isNull()) return null;
        if (column.equals("password")) return passwords.encode(DEMO_PASSWORD);
        if (value.isContainerNode()) return value.toString();
        // DATETIME 是本地时间字段；使用 JDBC 4.2 LocalDateTime，避免 Timestamp
        // 经容器 UTC 和连接 Asia/Shanghai 转换后平移八小时。
        if (column.endsWith("_at")) return LocalDateTime.parse(value.asText().replace(' ', 'T'));
        if (column.endsWith("_quantity")) return new BigDecimal(value.asText());
        if (column.equals("id") || column.endsWith("_id")) return Long.parseLong(value.asText());
        return value.isNumber() ? value.numberValue() : value.asText();
    }

    public void verify(DatasetPackage pack, boolean initial) {
        checkSchema(pack);
        for (String phase : List.of("business", "knowledge")) verifyPhase(pack, phase, initial);
    }

    private void verifyPhase(DatasetPackage pack, String phase, boolean initial) {
        var history = ledger(pack, phase);
        if (history.isEmpty()) throw new Conflict("阶段尚未导入：" + phase);
        compatible(pack, phase, history.getFirst());
        List<DatasetPackage.Row> rows = pack.phase(phase);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM t_dataset_record WHERE dataset=? AND rule_version=? AND phase=?", Integer.class,
                pack.dataset(), pack.version(), phase) == rows.size(), "归属记录数量不一致");
        for (var row : rows) {
            var owned = jdbc.queryForList("SELECT * FROM t_dataset_record WHERE table_name=? AND record_id=?", row.table(), row.id());
            require(owned.size() == 1 && row.key().equals(owned.getFirst().get("business_key"))
                    && pack.dataset().equals(owned.getFirst().get("dataset")) && pack.version().equals(owned.getFirst().get("rule_version"))
                    && phase.equals(owned.getFirst().get("phase")) && row.scenarioId().equals(owned.getFirst().get("scenario_id")), "归属映射不一致：" + row.table());
            try { require(DatasetPackage.hash(DatasetPackage.JSON.writeValueAsBytes(row.values())).equals(owned.getFirst().get("record_hash")), "归属内容散列不一致"); }
            catch (java.io.IOException failure) { throw new IllegalArgumentException("记录散列失败", failure); }
            var found = jdbc.queryForList("SELECT * FROM " + row.table() + " WHERE id=?", row.id());
            require(found.size() == 1, "归属记录缺失：" + row.table() + "/" + row.id());
            var actual = found.getFirst();
            // 初始值仅在刚导入/隔离重建时核对；运行校验不要求库存或密码回到初始值。
            for (String column : DatasetPackage.COLUMNS.get(row.table())) {
                boolean identity = column.equals("id") || column.endsWith("_id") || Set.of("enterprise_code", "username", "code", "note_no", "doc_code", "chunk_index").contains(column);
                if (initial || identity) compare(row, actual, column);
            }
            if (row.table().equals("t_inventory_note")) {
                BigDecimal total = (BigDecimal) actual.get("total_quantity"), available = (BigDecimal) actual.get("available_quantity"), frozen = (BigDecimal) actual.get("frozen_quantity");
                require(total.signum() >= 0 && available.signum() >= 0 && frozen.signum() >= 0 && total.compareTo(available.add(frozen)) == 0, "运行库存不守恒");
                require(Objects.equals(actual.get("unit"), row.values().path("unit").asText()), "运行库存单位不一致");
            }
        }
    }

    private void compare(DatasetPackage.Row row, Map<String, Object> actual, String column) {
        JsonNode expected = row.values().get(column);
        Object current = actual.get(column);
        boolean equal;
        if (column.equals("password")) equal = current != null && passwords.matches(DEMO_PASSWORD, current.toString());
        else if (expected.isNull()) equal = current == null;
        else if (current == null) equal = false;
        else if (expected.isContainerNode()) {
            try { equal = DatasetPackage.JSON.readTree(current.toString()).equals(expected); }
            catch (Exception e) { equal = false; }
        } else if (column.endsWith("_at")) {
            LocalDateTime stored = current instanceof Timestamp ts ? ts.toLocalDateTime()
                    : current instanceof LocalDateTime time ? time : LocalDateTime.parse(current.toString().replace(' ', 'T'));
            equal = stored.equals(LocalDateTime.parse(expected.asText().replace(' ', 'T')));
        }
        else if (current instanceof Number) equal = new BigDecimal(current.toString()).compareTo(new BigDecimal(expected.asText())) == 0;
        else equal = current.toString().equals(expected.asText());
        require(equal, "初始值或身份不一致：" + row.table() + "/" + row.id() + "/" + column);
    }

    private List<Map<String, Object>> ledger(DatasetPackage pack, String phase) {
        return jdbc.queryForList("SELECT * FROM t_dataset_import WHERE dataset=? AND rule_version=? AND phase=?", pack.dataset(), pack.version(), phase);
    }
    private void compatible(DatasetPackage pack, String phase, Map<String, Object> history) {
        if (!pack.manifest().path("sourceHash").asText().equals(history.get("source_hash")) || !pack.recordsHash().equals(history.get("records_hash"))
                || !pack.manifest().path("baseTime").asText().equals(history.get("base_time"))
                || ((Number) history.get("row_count")).intValue() != pack.phase(phase).size())
            throw new Conflict("同一数据版本内容已变化；不能覆盖已成功批次");
    }
    private void checkSchema(DatasetPackage pack) {
        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1", String.class);
        for (JsonNode version : pack.manifest().path("schema").path("requiredMigrations")) require(versions.contains(version.asText()), "结构迁移缺失：V" + version.asText());
        require(versions.contains("14") && jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class) == 0, "数据库迁移不兼容");
    }

    public List<Map<String, Object>> status() {
        return jdbc.queryForList("SELECT dataset,rule_version,phase,batch,base_time,row_count,status,committed_at FROM t_dataset_import ORDER BY committed_at,phase");
    }
    public Map<String, Object> vectorStatus() {
        return jdbc.queryForMap("""
                SELECT COUNT(*) AS chunks, COALESCE(SUM(c.embedding IS NOT NULL),0) AS embedded
                FROM t_knowledge_chunk c JOIN t_dataset_record r ON r.table_name='t_knowledge_chunk' AND r.record_id=c.id
                """);
    }
    @Override public void close() throws SQLException {
        try { jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lock); }
        finally { connection.close(); }
    }
}
