package com.spotlink.shared;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 只在隔离 MySQL 上验证迁移与真实约束，失败不自动 repair 或清库。 */
@Tag("integration")
@SpringBootTest
class SchemaSmokeTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void allMigrationsSucceedOnTheSpecifiedMysqlVersion() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4.7");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY'", Integer.class)).isPositive();
    }
}
