package com.epam.aidial.analytics.clickhouse.replication;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the ClickHouse {@link DatabaseEngineCheck} ahead of the PostgreSQL migrations, which Spring Boot applies during
 * context refresh and which can seed catalog rows: an {@code Atomic} database is then refused with the catalog
 * untouched.
 */
@Configuration(proxyBeanMethods = false)
public class StorageStartupConfiguration {

    @Bean
    public FlywayMigrationStrategy clickHouseEngineCheckFirst(DatabaseEngineCheck engineCheck) {
        return flyway -> {
            engineCheck.check();
            flyway.migrate();
        };
    }
}
