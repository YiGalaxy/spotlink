package com.spotlink.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 一次性的修复开关，用于已应用迁移的校验和发生变动时。
 *
 * <p><b>为什么需要它。</b>Flyway 会为它执行的每个脚本记录一个校验和，一旦文件对不上就
 * 拒绝启动——这道守卫防的是「迁移在事后被改过」，因为那意味着某个数据库的 schema 是由
 * 另一份脚本建出来的，而不是现在磁盘上的这一份。
 *
 * <p>这道守卫分不清语义改动和表面改动。翻译一个已应用迁移里的注释，改动了校验和却什么
 * 都没改到 schema，于是那个迁移从此永远过不了校验。{@code repair} 是 Flyway 自己给出的
 * 答案：它把记录的校验和改写为与文件一致，不重跑任何东西。
 *
 * <p><b>默认关闭，而且刻意不自动执行。</b>每次启动都 repair，等于让项目顺利通过校验和
 * 本该拦住的那种情形——一个确实在应用之后被改过的迁移——而且没有人会看到它发生过。
 * 一个必须在命令行上传入的开关，是一个有人**决定**要去用的开关。
 *
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--bulk.flyway.repair=true
 * </pre>
 *
 * <p>用完就把它去掉。它不属于配置文件：下一个读 {@code application.yml} 的人会看到一项
 * 看起来像配置、实际上是一次性修复的东西。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "bulk.flyway", name = "repair", havingValue = "true")
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            log.warn("bulk.flyway.repair is on: realigning recorded checksums with the "
                    + "migration files on disk. Use this only for comment-only edits.");
            flyway.repair();
            flyway.migrate();
        };
    }
}
