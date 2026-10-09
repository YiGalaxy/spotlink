package com.spotlink.bootstrap.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** 部署作业的受控输入，独立于 HTTP 和模型工具。表列白名单编译进应用。 */
public record DatasetPackage(JsonNode manifest, List<Row> rows) {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final Map<String, List<String>> COLUMNS = Map.of(
            "t_enterprise", List.of("id", "enterprise_code", "name", "short_name", "unified_social_credit_code", "contact_name", "contact_phone", "contact_email", "trader_code", "status", "qualifications", "created_at", "updated_at"),
            "t_user", List.of("id", "enterprise_id", "username", "password", "real_name", "user_type", "status", "created_at", "updated_at"),
            "t_user_role", List.of("id", "user_id", "role_id", "created_at"),
            "t_warehouse", List.of("id", "code", "name", "province", "city", "address", "contact_name", "contact_phone", "status", "created_at", "updated_at"),
            "t_commodity_category", List.of("id", "code", "name", "path", "spec_schema", "unit", "status", "created_at", "updated_at"),
            "t_inventory_note", List.of("id", "note_no", "enterprise_id", "category_id", "warehouse_id", "commodity_name", "spec", "total_quantity", "available_quantity", "frozen_quantity", "unit", "status", "version", "remark", "created_at", "updated_at"),
            "t_knowledge_doc", List.of("id", "doc_code", "title", "category", "source", "version", "status", "remark", "created_at", "updated_at"),
            "t_knowledge_chunk", List.of("id", "doc_id", "chunk_index", "content", "token_count", "created_at"));
    public record Row(String scenarioId, String table, JsonNode values) {
        public long id() { return Long.parseLong(values.path("id").asText()); }
        public String phase() { return table.startsWith("t_knowledge_") ? "knowledge" : "business"; }
        public String key() {
            for (String name : List.of("enterprise_code", "username", "code", "note_no", "doc_code"))
                if (values.has(name)) return values.get(name).asText();
            return table.equals("t_user_role") ? values.path("user_id").asText() + ":" + values.path("role_id").asText()
                    : values.path("doc_id").asText() + ":" + values.path("chunk_index").asText();
        }
    }
    public String dataset() { return manifest.path("dataset").asText(); }
    public String version() { return manifest.path("ruleVersion").asText(); }
    public String recordsHash() { return manifest.path("files").path("records.json").path("sha256").asText(); }
    public List<Row> phase(String phase) { return rows.stream().filter(r -> r.phase().equals(phase)).toList(); }

    public static DatasetPackage load(Path root, String batch) throws IOException {
        require(batch != null && batch.matches("[a-z0-9][a-z0-9-]{0,63}"), "批次名无效");
        Path directory = root.resolve(".local/data/v1/" + batch).normalize();
        require(directory.startsWith(root.toAbsolutePath().normalize()), "数据路径越界");
        byte[] manifestBytes = read(directory.resolve("manifest.json"));
        JsonNode manifest = JSON.readTree(manifestBytes);
        require(manifest.path("formatVersion").asInt() == 1 && manifest.path("ruleVersion").asText().equals("v1")
                && manifest.path("generatorVersion").asText().equals("1.1.0"), "数据版本不兼容");
        require(manifest.path("batch").asText().equals(batch), "批次与清单不一致");
        require(Set.of("minimal", "acceptance", "performance").contains(manifest.path("dataset").asText()), "数据包尚未实现");
        require(manifest.path("sourceHash").asText().equals(sourceHash(root.resolve("data"))), "数据定义已变化，请生成新版本批次");
        Set<String> files = Set.of("records.json", "records.jsonl", "rendered.sql", "expected.json");
        require(fields(manifest.path("files")).equals(files), "清单文件列表不完整");
        for (String name : files) {
            byte[] bytes = read(directory.resolve(name));
            JsonNode description = manifest.path("files").path(name);
            require(bytes.length == description.path("bytes").asLong() && hash(bytes).equals(description.path("sha256").asText()), "文件损坏：" + name);
        }
        JsonNode records = JSON.readTree(read(directory.resolve("records.json")));
        List<Row> rows = validate(records);
        Map<String, Long> counts = new HashMap<>();
        COLUMNS.keySet().forEach(table -> counts.put(table, rows.stream().filter(r -> r.table().equals(table)).count()));
        require(fields(manifest.path("counts")).equals(COLUMNS.keySet()), "记录数量表列表不一致");
        counts.forEach((table, count) -> require(manifest.path("counts").path(table).isIntegralNumber()
                && manifest.path("counts").path(table).asLong() == count, "记录数不一致：" + table));
        return new DatasetPackage(manifest, rows);
    }

    static List<Row> validate(JsonNode records) {
        require(records != null && records.isArray() && !records.isEmpty() && records.size() <= 11000, "记录列表或规模无效");
        List<Row> rows = new ArrayList<>();
        Map<String, Row> ids = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode record : records) {
            require(fields(record).equals(Set.of("scenarioId", "table", "values")), "记录结构无效");
            String table = record.path("table").asText();
            require(COLUMNS.containsKey(table), "非受控数据表");
            JsonNode values = record.path("values");
            require(fields(values).equals(new HashSet<>(COLUMNS.get(table))), "插入列不匹配：" + table);
            String id = values.path("id").asText();
            require(values.path("id").isTextual() && id.matches("87000000000000[0-9]{5}"), "ID 不在演示命名空间");
            Row row = new Row(record.path("scenarioId").asText(), table, values);
            require(row.scenarioId().matches("[A-Z0-9-]{1,64}"), "场景编号无效");
            require(ids.put(table + ":" + id, row) == null && keys.add(table + ":" + row.key()), "重复 ID 或业务编号");
            require(row.key().length() <= 256, "业务编号过长");
            rows.add(row);
        }
        // 引用只能指向当前批次已定义的记录；插入顺序也须遵循依赖。
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            JsonNode v = row.values();
            switch (row.table()) {
                case "t_user" -> {
                    if (!v.path("enterprise_id").isNull()) link(seen, "t_enterprise", v.path("enterprise_id"));
                    require(v.path("password").asText().equals("LOCAL_DEMO_BCRYPT"), "密码必须由导入器编码");
                    require(v.path("username").asText().startsWith("min_"), "账号不在演示命名空间");
                    require(v.path("status").asInt() == 1 && v.path("user_type").asInt() == (v.path("enterprise_id").isNull() ? 2 : 1), "演示账号状态无效");
                }
                case "t_user_role" -> {
                    link(seen, "t_user", v.path("user_id"));
                    require(Set.of("9100", "9101").contains(v.path("role_id").asText()), "演示角色无效");
                    require(ids.get("t_user:" + v.path("user_id").asText()).values().path("enterprise_id").isNull(), "企业账号不能持有平台角色");
                }
                case "t_inventory_note" -> {
                    link(seen, "t_enterprise", v.path("enterprise_id")); link(seen, "t_warehouse", v.path("warehouse_id")); link(seen, "t_commodity_category", v.path("category_id"));
                    BigDecimal total = quantity(v.path("total_quantity")), available = quantity(v.path("available_quantity")), frozen = quantity(v.path("frozen_quantity"));
                    require(total.signum() > 0 && total.equals(available.add(frozen)) && frozen.signum() == 0 && v.path("status").asInt() == 2 && v.path("version").asInt() == 0, "基础库存数量或状态无效");
                    JsonNode category = ids.get("t_commodity_category:" + v.path("category_id").asText()).values();
                    require(v.path("unit").equals(category.path("unit")), "库存单位不一致");
                    for (JsonNode field : category.path("spec_schema")) require(!field.path("required").asBoolean() || v.path("spec").has(field.path("key").asText()), "规格缺少必填字段");
                }
                case "t_knowledge_chunk" -> {
                    link(seen, "t_knowledge_doc", v.path("doc_id"));
                    require(v.path("content").isTextual() && !v.path("content").asText().isBlank() && v.path("content").asText().length() <= 600 && v.path("chunk_index").asInt(-1) >= 0, "知识分块无效");
                }
                default -> { }
            }
            seen.add(row.table() + ":" + row.id());
        }
        return List.copyOf(rows);
    }
    private static void link(Set<String> seen, String table, JsonNode id) { require(id.isTextual() && seen.contains(table + ":" + id.asText()), "缺少前置关联：" + table); }
    private static BigDecimal quantity(JsonNode node) {
        require(node.isTextual() && node.asText().matches("(0|[1-9][0-9]{0,14})\\.[0-9]{3}"), "数量精度无效");
        return new BigDecimal(node.asText());
    }
    private static Set<String> fields(JsonNode node) { Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result; }
    private static byte[] read(Path file) throws IOException {
        for (Path cursor = file; cursor != null; cursor = cursor.getParent()) require(!Files.isSymbolicLink(cursor), "数据路径不允许符号链接");
        require(Files.isRegularFile(file) && Files.size(file) <= 64L * 1024 * 1024, "数据文件缺失或过大");
        return Files.readAllBytes(file);
    }
    private static String sourceHash(Path data) throws IOException {
        List<String> entries = new ArrayList<>();
        try (var paths = Files.walk(data)) {
            for (Path file : paths.sorted().toList()) {
                require(!Files.isSymbolicLink(file), "数据定义不能包含符号链接");
                if (Files.isRegularFile(file)) entries.add(data.relativize(file).toString().replace('\\', '/') + ":" + hash(read(file)));
            }
        }
        Collections.sort(entries);
        return hash(String.join("\n", entries).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
