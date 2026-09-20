package com.bulk.trade.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A one-time repair switch for when an applied migration's checksum has moved.
 *
 * <p><b>Why this is needed at all.</b> Flyway records a checksum of every script
 * it applies and refuses to start if the file no longer matches — a guard
 * against a migration being edited after the fact, which would mean the schema
 * in one database was built by a different script than the one now on disk.
 *
 * <p>That guard cannot tell a semantic edit from a cosmetic one. Translating the
 * comments in an applied migration changes the checksum and nothing else, and
 * the migration then fails validation forever. `repair` is Flyway's own answer:
 * it rewrites the recorded checksums to match the files, without re-running
 * anything.
 *
 * <p><b>Off by default, and deliberately not automatic.</b> Running repair on
 * every start would make the project boot through exactly the case the checksum
 * exists to catch — a migration that really was changed after being applied —
 * and nobody would ever see it happen. A switch that has to be passed on the
 * command line is one somebody had to decide to use.
 *
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--bulk.flyway.repair=true
 * </pre>
 *
 * <p>Then remove the flag. It does not belong in a config file: the next person
 * to read `application.yml` would find a setting that looks like configuration
 * and is really a one-time repair.
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
