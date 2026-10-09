package com.spotlink.bootstrap.data;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatasetPackageTest {
    @Test void arbitraryTablesColumnsAndNamespaceAreRejectedBeforeSql() throws Exception {
        for (String table : new String[]{"t_user; DROP TABLE t_user", "t_advisor_model_settings", "t_audit_log"}) {
            var records = DatasetPackage.JSON.readTree("[{\"scenarioId\":\"TEST\",\"table\":\"" + table + "\",\"values\":{}}]");
            assertThatThrownBy(() -> DatasetPackage.validate(records)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("受控");
        }
        assertThatThrownBy(() -> DatasetPackage.load(java.nio.file.Path.of("/workspace"), "../../outside")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyListAndMissingDependencyAreRejected() throws Exception {
        assertThatThrownBy(() -> DatasetPackage.validate(DatasetPackage.JSON.createArrayNode())).isInstanceOf(IllegalArgumentException.class);
        var values = DatasetPackage.JSON.createObjectNode().put("id", "8700000000000000700").put("doc_id", "8700000000000000600")
                .put("chunk_index", 0).put("content", "中文规则").put("token_count", 0).put("created_at", "2026-10-08 00:00:00");
        var row = DatasetPackage.JSON.createObjectNode().put("table", "t_knowledge_chunk").put("scenarioId", "TEST"); row.set("values", values);
        assertThatThrownBy(() -> DatasetPackage.validate(DatasetPackage.JSON.createArrayNode().add(row))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("关联");
    }
}
