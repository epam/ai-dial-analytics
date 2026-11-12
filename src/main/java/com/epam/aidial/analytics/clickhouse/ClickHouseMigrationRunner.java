package com.epam.aidial.analytics.clickhouse;

import com.epam.aidial.analytics.configuration.ClickHouseDdlProperties;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

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
 * <p><b>A step of the storage phase, not a runner of its own.</b> {@code ClickHouseStoragePhase} calls
 * {@link #migrate()} after the table conversion and before the retention reconciliation and the table check, under
 * the phase's advisory lock: Flyway takes no lock of its own on ClickHouse, and several instances starting at once
 * would otherwise migrate concurrently.
 *
 * <p><b>Replicated tables without editing applied migrations.</b> Before {@code migrate()} the Flyway history table
 * is created as a {@code ReplicatedMergeTree} with the plugin's own column list, so every replica sees one history;
 * before each pending migration, {@link MigrationPreCreate} creates the migration's {@code CREATE TABLE IF NOT EXISTS}
 * table in its replicated form, so the migration's own statement finds it and does nothing and its checksum is
 * unchanged. See {@code openspec/specs/data-sync/spec.md}.
 *
 * <p>Connects with the <em>writer</em> credential, which must additionally hold {@code CREATE TABLE}
 * grants (DDL). Run only when {@code clickhouse.migrations.enabled} (default true); disable it when the
 * schema is operator-provisioned. A migration failure aborts startup.
 *
 * <p><b>The migration connection runs every DDL under {@link DdlSettings}</b>, so a stopped replica holds a statement
 * for at most {@code clickhouse.ddl.task-timeout} instead of failing startup.
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
public class ClickHouseMigrationRunner {

    private static final String MIGRATIONS_FOLDER = "db/migration/clickhouse";
    private static final String MIGRATIONS_LOCATION = "classpath:" + MIGRATIONS_FOLDER;

    /** The Flyway history table, Flyway's default name. */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    /**
     * The read setting every migration statement runs under, in the driver's {@code custom_settings} spelling: the
     * migration path is meant to read like the read path. The DDL settings ({@link DdlSettings}) are appended to it in
     * {@link #jdbcUrl(String, int, String, Duration)}.
     */
    public static final String MIGRATION_SETTINGS = "final=1";

    /**
     * The Flyway ClickHouse plugin's history-table columns, verbatim, with a replicated engine and insert deduplication
     * off. Created before {@code migrate()}, so the plugin finds the table and never creates its plain form.
     */
    static final String HISTORY_DDL = "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
            + "installed_rank Int32, version Nullable(String), description String, type String, script String, "
            + "checksum Nullable(Int32), installed_by String, installed_on DateTime DEFAULT now(), execution_time Int32, "
            + "success Bool) ENGINE = ReplicatedMergeTree PRIMARY KEY (script) SETTINGS "
            + ReplicatedDdl.NO_INSERT_DEDUPLICATION;

    private final ClickHouseProperties properties;
    private final ClickHouseWriterProperties writerProperties;
    private final ClickHouseDdlProperties ddlProperties;

    /** Pre-creates the history table, then applies every pending migration with its table pre-created replicated. */
    public void migrate() {
        final String url = jdbcUrl();
        log.info("Running ClickHouse schema migrations against {}", url);
        createHistoryTable(url);
        final Flyway flyway = Flyway.configure()
                .dataSource(url, writerProperties.user(), writerProperties.password())
                .locations(MIGRATIONS_LOCATION)
                .table(HISTORY_TABLE)
                .callbacks(new MigrationPreCreate(MIGRATIONS_FOLDER))
                .load();
        final int applied = flyway.migrate().migrationsExecuted;
        log.info("ClickHouse schema migrations complete ({} applied)", applied);
    }

    /**
     * Pre-creates the history table, but only into a database that is empty or already has it: into a non-empty
     * database without one, Flyway refuses to migrate ("non-empty schema … without a schema history table"), and an
     * empty history table created here would bypass that guard and replay every migration over tables provisioned
     * some other way.
     */
    private void createHistoryTable(String url) {
        try (Connection connection = DriverManager.getConnection(url, writerProperties.user(), writerProperties.password());
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT countIf(name = '" + HISTORY_TABLE + "') AS history, "
                    + "countIf(name != '" + HISTORY_TABLE + "') AS others FROM system.tables WHERE database = currentDatabase()")) {
                rows.next();
                if (rows.getLong("history") == 0 && rows.getLong("others") > 0) {
                    log.warn("ClickHouse database holds tables but no {} table; not pre-creating it, so Flyway's "
                            + "non-empty-schema guard applies", HISTORY_TABLE);
                    return;
                }
            }
            statement.execute(HISTORY_DDL);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not create the replicated Flyway history table", ex);
        }
    }

    /**
     * The JDBC URL Flyway connects with: host, port and database from {@code clickhouse.*}, plus
     * {@link #MIGRATION_SETTINGS} and the DDL settings. Package-private so a test can open the very same connection
     * and assert the settings are in effect rather than merely present in the string.
     */
    String jdbcUrl() {
        return jdbcUrl(properties.host(), properties.port(), properties.database(), ddlProperties.taskTimeout());
    }

    /** {@link #jdbcUrl(String, int, String, Duration)} with the default DDL bound. */
    public static String jdbcUrl(String host, int port, String database) {
        return jdbcUrl(host, port, database, ClickHouseDdlProperties.defaults().taskTimeout());
    }

    public static String jdbcUrl(String host, int port, String database, Duration ddlTaskTimeout) {
        final String schema = StringUtils.isBlank(database) ? "default" : database.trim();
        return "jdbc:clickhouse://" + host + ":" + port + "/" + schema + "?custom_settings=" + customSettings(ddlTaskTimeout);
    }

    /** Every setting of the migration connection, in the driver's {@code custom_settings} spelling. */
    public static String customSettings(Duration ddlTaskTimeout) {
        return MIGRATION_SETTINGS + "," + DdlSettings.customSettings(ddlTaskTimeout);
    }
}
