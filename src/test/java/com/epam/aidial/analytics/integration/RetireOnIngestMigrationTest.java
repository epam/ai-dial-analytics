package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.pipeline.DefaultSchedule;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@code V44__retire_on_ingest_trigger.sql} against rows as {@code V43} leaves them.
 *
 * <p>The load-bearing assertions are the ones a Spring-context test cannot make, because it only ever sees a
 * schema that is already migrated: that every {@code ON_INGEST} row becomes {@code SCHEDULE} with the cron the
 * rounding table names, that {@code generation}, {@code updated_at} and the Class-1 materialized position are
 * <b>untouched</b> (a bump would discard the position), and that the second the SQL derives is the one
 * {@link DefaultSchedule} derives, so a migrated pipeline and a re-registered one agree.
 */
class RetireOnIngestMigrationTest {

    private static final String DATABASE = "retire_on_ingest_probe";
    private static final String LOCATION = "classpath:db/migration/postgres";
    private static final String BEFORE = "43";
    private static final String UNDER_TEST = "44";

    /** Every {@code ON_INGEST} name seeded below, for the cross-check against the Java derivation. */
    private static final List<String> ON_INGEST = List.of(
            "ri-sql-60s", "ri-llm-90s", "ri-llm-7m", "ri-unset", "ri-garbage", "ri-legacy-cadence", "ri-day",
            "ri-hourly", "ri-5h", "ri-iso", "ri-bare-ms");

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        recreateDatabase();
        flyway(BEFORE).migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO table_definition (name, type, status) VALUES ('ri_events', 'SOURCE', 'ACTIVE')");
            for (String name : List.of("ri-sql-60s", "ri-llm-90s", "ri-llm-7m", "ri-unset", "ri-garbage",
                    "ri-legacy-cadence", "ri-day", "ri-hourly", "ri-5h", "ri-iso", "ri-bare-ms", "ri-group",
                    "ri-draft")) {
                statement.execute("INSERT INTO table_definition (name, type, status, source_table, grain_key) VALUES ('"
                        + target(name) + "', 'ENRICHMENT', 'ACTIVE', 'ri_events', 'event_id')");
            }
            statement.execute("INSERT INTO table_definition (name, type, status) VALUES ('ri_rollup', 'SOURCE', 'ACTIVE')");

            enrich(statement, "ri-sql-60s", "ON_INGEST", "{\"transform\":{\"type\":\"sql\"},\"scan_every\":\"60s\"}", true);
            statement.execute("UPDATE pipeline SET materialized_through_version = 1790333820358,"
                    + " materialized_through_identity = 'mark', materialized_generation = 7, generation = 7"
                    + " WHERE name = 'ri-sql-60s'");
            enrich(statement, "ri-llm-90s", "ON_INGEST", "{\"scan_every\":\"90s\",\"rows_per_scan\":1000}", true);
            enrich(statement, "ri-llm-7m", "ON_INGEST", "{\"scan_every\":\"7m\"}", true);
            enrich(statement, "ri-unset", "ON_INGEST", "{}", true);
            enrich(statement, "ri-garbage", "ON_INGEST", "{\"scan_every\":\"often\"}", false);
            enrich(statement, "ri-legacy-cadence", "ON_INGEST", "{\"cadence\":\"30s\"}", true);
            enrich(statement, "ri-day", "ON_INGEST", "{\"scan_every\":\"3d\"}", true);
            enrich(statement, "ri-hourly", "ON_INGEST", "{\"scan_every\":\"60m\"}", true);
            enrich(statement, "ri-5h", "ON_INGEST", "{\"scan_every\":\"5H\"}", true);
            enrich(statement, "ri-iso", "ON_INGEST", "{\"scan_every\":\"PT1M30S\"}", true);
            enrich(statement, "ri-bare-ms", "ON_INGEST", "{\"scan_every\":\"300000\"}", true);
            statement.execute(row("ri-group", "ENRICH", target("ri-group"), "NULL", "'GROUP'", "NULL", "'chat_id'",
                    "{\"scan_every\":\"5m\",\"rate_rpm\":75}", true));
            enrich(statement, "ri-draft", "SCHEDULE", "{}", false);
            statement.execute(row("ri-rollup", "AGGREGATE", "ri_rollup", "'ri_events'", "'SCHEDULE'",
                    "'0 */5 * * * *'", "NULL", "{\"group_by\":[{\"column\":\"chat_id\"}]}", true));
        }
        flyway(UNDER_TEST).migrate();
    }

    @AfterAll
    static void dropProbeDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(PostgresContainerSupport.jdbcUrl(),
                        PostgresContainerSupport.username(), PostgresContainerSupport.password());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    @Test
    void everyOnIngestRowBecomesScheduleWithItsRoundedCron() throws SQLException {
        assertThat(scalar("SELECT count(*) FROM pipeline WHERE trigger_kind = 'ON_INGEST'")).isEqualTo("0");
        assertThat(cron("ri-sql-60s")).isEqualTo(second("ri-sql-60s") + " * * * * *");
        assertThat(cron("ri-llm-90s")).isEqualTo(second("ri-llm-90s") + " */2 * * * *");
        assertThat(cron("ri-llm-7m")).isEqualTo(second("ri-llm-7m") + " */10 * * * *");
        assertThat(cron("ri-unset")).isEqualTo(second("ri-unset") + " * * * * *");
        assertThat(cron("ri-garbage")).isEqualTo(second("ri-garbage") + " * * * * *");
        // A sub-minute cadence rounds up to every minute, and the pre-rename spelling is read too.
        assertThat(cron("ri-legacy-cadence")).isEqualTo(second("ri-legacy-cadence") + " * * * * *");
        // Above a day there is no larger even step, so it is capped at daily.
        assertThat(cron("ri-day")).isEqualTo(second("ri-day") + " 0 0 * * *");
        // An hour is the top of the minute steps; above it the step is in hours, rounded up the same way.
        assertThat(cron("ri-hourly")).isEqualTo(second("ri-hourly") + " 0 * * * *");
        assertThat(cron("ri-5h")).isEqualTo(second("ri-5h") + " 0 */6 * * *");
        // Spring's other two duration forms: ISO-8601, and a bare number of milliseconds.
        assertThat(cron("ri-iso")).isEqualTo(second("ri-iso") + " */2 * * * *");
        assertThat(cron("ri-bare-ms")).isEqualTo(second("ri-bare-ms") + " */5 * * * *");
        assertThat(scalar("SELECT trigger_kind FROM pipeline WHERE name = 'ri-sql-60s'")).isEqualTo("SCHEDULE");
    }

    @Test
    void theMigratedSecondIsTheOneTheWritePathDerives() throws SQLException {
        for (String name : ON_INGEST) {
            assertThat(cron(name)).as(name).startsWith(DefaultSchedule.cronFor(name).split(" ")[0] + " ");
        }
        assertThat(cron("ri-draft")).isEqualTo(DefaultSchedule.cronFor("ri-draft"));
    }

    @Test
    void generationUpdatedAtAndTheMaterializedPositionAreUntouched() throws SQLException {
        assertThat(scalar("SELECT generation || '/' || materialized_generation || '/' || materialized_through_version"
                + " || '/' || materialized_through_identity FROM pipeline WHERE name = 'ri-sql-60s'"))
                .isEqualTo("7/7/1790333820358/mark");
        assertThat(scalar("SELECT count(*) FROM pipeline WHERE updated_at <> TIMESTAMPTZ '2026-09-01 00:00:00+00'"
                + " OR generation NOT IN (1, 7)")).isEqualTo("0");
    }

    @Test
    void theRetiredCadenceIsStrippedFromEveryEnrichSpecAndNothingElse() throws SQLException {
        assertThat(scalar("SELECT count(*) FROM pipeline WHERE kind = 'ENRICH'"
                + " AND (spec ? 'scan_every' OR spec ? 'cadence')")).isEqualTo("0");
        assertThat(scalar("SELECT spec::text FROM pipeline WHERE name = 'ri-llm-90s'")).isEqualTo("{\"rows_per_scan\": 1000}");
        // A group pipeline loses only its stored cadence: its trigger is not rewritten.
        assertThat(scalar("SELECT trigger_kind || '/' || coalesce(trigger_cron, 'none') || '/' || (spec ->> 'rate_rpm')"
                + " FROM pipeline WHERE name = 'ri-group'")).isEqualTo("GROUP/none/75");
        assertThat(scalar("SELECT spec::text || '/' || trigger_cron FROM pipeline WHERE name = 'ri-rollup'"))
                .isEqualTo("{\"group_by\": [{\"column\": \"chat_id\"}]}/0 */5 * * * *");
    }

    @Test
    void theNarrowedConstraintRefusesOnIngest() {
        assertThatThrownBy(() -> execute("UPDATE pipeline SET trigger_kind = 'ON_INGEST', trigger_cron = NULL"
                + " WHERE name = 'ri-garbage'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("pipeline_trigger_kind_check");
    }

    private static void enrich(Statement statement, String name, String triggerKind, String spec, boolean enabled)
            throws SQLException {
        statement.execute(row(name, "ENRICH", target(name), "NULL", "'" + triggerKind + "'", "NULL", "NULL", spec,
                enabled));
    }

    private static String row(String name, String kind, String target, String input, String triggerKind,
            String cron, String groupBy, String spec, boolean enabled) {
        return "INSERT INTO pipeline (name, kind, target, input_table, spec, trigger_kind, trigger_cron,"
                + " trigger_group_by, enabled, generation, has_more, rebuilt_at, created_at, updated_at) VALUES"
                + " ('" + name + "', '" + kind + "', '" + target + "', " + input + ", '" + spec + "'::jsonb, " + triggerKind
                + ", " + cron + ", " + groupBy + ", " + enabled + ", 1, false, now(),"
                + " TIMESTAMPTZ '2026-09-01 00:00:00+00', TIMESTAMPTZ '2026-09-01 00:00:00+00')";
    }

    private static String target(String name) {
        return name.replace('-', '_') + "_labels";
    }

    private static String second(String name) {
        return DefaultSchedule.cronFor(name).split(" ")[0];
    }

    private static String cron(String name) throws SQLException {
        return scalar("SELECT trigger_cron FROM pipeline WHERE name = '" + name + "'");
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("a row for %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void recreateDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(PostgresContainerSupport.jdbcUrl(),
                        PostgresContainerSupport.username(), PostgresContainerSupport.password());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
    }

    private static Flyway flyway(String target) {
        final FluentConfiguration configuration = Flyway.configure()
                .dataSource(PostgresContainerSupport.jdbcUrlFor(DATABASE), PostgresContainerSupport.username(),
                        PostgresContainerSupport.password())
                .locations(LOCATION);
        return configuration.target(target).load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(PostgresContainerSupport.jdbcUrlFor(DATABASE),
                PostgresContainerSupport.username(), PostgresContainerSupport.password());
    }
}
