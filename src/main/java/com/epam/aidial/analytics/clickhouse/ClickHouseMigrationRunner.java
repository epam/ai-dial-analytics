package com.epam.aidial.analytics.clickhouse;

import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import com.epam.aidial.analytics.sync.PayloadTtlReconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Provisions the ClickHouse schema (e.g. {@code dial_usage_log}) on startup by running the Flyway
 * migrations under {@code classpath:db/migration/clickhouse}.
 *
 * <p>Flyway is driven <strong>programmatically</strong> rather than through Spring Boot's Flyway
 * auto-configuration, because that auto-config only engages when a JDBC {@code DataSource} bean is
 * present and would pull in {@code spring-jdbc}. ADAS has no {@code DataSource} — its read/sync
 * paths go through the ClickHouse HTTP client, not JDBC. Calling Flyway directly builds a JDBC URL
 * used only for migrations and connects over the ClickHouse JDBC driver, with no {@code DataSource}
 * or {@code spring-jdbc} dragged into the app just to satisfy auto-config.
 *
 * <p>After the migrations it reconciles the payload table's {@code TTL} with {@code sync.payload.ttl-days}
 * ({@link PayloadTtlReconciler}): the migration only provisions the default, and applied migrations are frozen.
 * Done here so it shares this runner's gate and runs before every other startup step.
 *
 * <p>Connects with the <em>writer</em> credential, which must additionally hold {@code CREATE TABLE}
 * grants (DDL). Gated by {@code clickhouse.migrations.enabled} (default true); disable it when the
 * schema is operator-provisioned. A migration failure aborts startup.
 *
 * <p><b>The migration connection reads with {@code final=1}</b>, the same server setting every ADAS read
 * path applies ({@link FinalSettings}). Every ingest table is a
 * {@code ReplacingMergeTree}, so between merges a re-ingested row exists as several versions, and a
 * data-moving migration — an {@code INSERT … SELECT} that copies rows across tables — that read them all
 * would copy every version and let the target's version column decide arbitrarily which survives. The
 * setting makes a plain {@code FROM t} in a migration see what the service sees, so a migration author
 * cannot forget it; a migration MAY still spell {@code FINAL} explicitly for readers of the file.
 * Passed through the driver's {@code custom_settings} URL parameter rather than an {@code initSql}
 * {@code SET}, because the JDBC driver speaks HTTP and a per-connection {@code SET} would depend on
 * session stickiness the URL parameter does not need.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "clickhouse.migrations.enabled", havingValue = "true", matchIfMissing = true)
// Ordered ahead of SchemaValidator so the schema is provisioned before startup reconciliation runs.
@Order(0)
public class ClickHouseMigrationRunner implements ApplicationRunner {

    private static final String MIGRATIONS_LOCATION = "classpath:db/migration/clickhouse";

    /**
     * Server settings every migration statement runs under, in the driver's {@code custom_settings}
     * spelling ({@code name=value}, comma-separated). {@code final=1} is deliberately the only one: the
     * migration path is meant to behave like the read path, not to tune itself.
     */
    public static final String MIGRATION_SETTINGS = "final=1";

    private final ClickHouseProperties properties;
    private final ClickHouseWriterProperties writerProperties;
    private final PayloadTtlReconciler payloadTtlReconciler;

    @Override
    public void run(ApplicationArguments args) {
        final String url = jdbcUrl();
        log.info("Running ClickHouse schema migrations against {}", url);
        final Flyway flyway = Flyway.configure()
                .dataSource(url, writerProperties.user(), writerProperties.password())
                .locations(MIGRATIONS_LOCATION)
                .load();
        final int applied = flyway.migrate().migrationsExecuted;
        log.info("ClickHouse schema migrations complete ({} applied)", applied);
        payloadTtlReconciler.reconcile();
    }

    /**
     * The JDBC URL Flyway connects with: host, port and database from {@code clickhouse.*}, plus
     * {@link #MIGRATION_SETTINGS}. Package-private so a test can open the very same connection and
     * assert the setting is in effect rather than merely present in the string.
     */
    String jdbcUrl() {
        return jdbcUrl(properties.host(), properties.port(), properties.database());
    }

    public static String jdbcUrl(String host, int port, String database) {
        final String schema = StringUtils.isBlank(database) ? "default" : database.trim();
        return "jdbc:clickhouse://" + host + ":" + port + "/" + schema + "?custom_settings=" + MIGRATION_SETTINGS;
    }
}
