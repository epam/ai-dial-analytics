package com.epam.aidial.analytics.integration;

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
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@code V42__defer_pipeline_validation.sql} against rows as {@code V41} leaves them, then probes the
 * re-scoped guards directly in SQL.
 *
 * <p>The Spring-context integration tests inherit an already-migrated schema and reach the table only
 * through the service layer, which refuses an incomplete armed row before the database is asked. So none
 * of them can see whether the CHECKs themselves still hold — which is the half of the invariant this
 * migration changes. What is asserted:
 *
 * <ul>
 *   <li><b>Every existing row survives</b>, enabled and complete ones included: the migration is data-safe
 *       and {@code pipeline_armed_is_complete} is satisfied by every row a pre-{@code V42} build could
 *       write.
 *   <li><b>A disabled draft is storable</b> with no trigger kind, no input, and a schedule without a cron.
 *   <li><b>Each re-scoped guard evaluates to FALSE, not NULL,</b> on an armed row whose
 *       {@code trigger_kind} is missing. A CHECK passes on UNKNOWN, so the naive rewrite would admit exactly
 *       the row the guard exists to refuse.
 *   <li><b>The two verbatim guards still catch a contradiction on a draft.</b>
 *   <li><b>No column is added</b>, so {@code pipeline_aud} needs no widening — an audited entity whose
 *       mirror lacks a column fails every write at commit.
 * </ul>
 */
class DeferPipelineValidationMigrationTest {

    private static final String DATABASE = "defer_validation_probe";
    private static final String LOCATION = "classpath:db/migration/postgres";

    /** The version to stop at: one below this migration. */
    private static final String BEFORE = "41";

    /**
     * The version under test. Migrated to exactly, not to the latest: a later migration may add a
     * {@code pipeline} column, which the "adds no column" assertion would then pin on this one.
     */
    private static final String UNDER_TEST = "42";

    /** The {@code pipeline} table's columns as {@code V41} leaves them. */
    private static Set<String> pipelineColumnsBefore;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        recreateDatabase();
        flyway(BEFORE).migrate();
        pipelineColumnsBefore = columns("pipeline");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO table_definition (name, type, status) VALUES "
                    + "('dv_events', 'SOURCE', 'ACTIVE'), ('dv_rollup', 'SOURCE', 'ACTIVE'), "
                    + "('dv_rollup_off', 'SOURCE', 'ACTIVE')");
            statement.execute("INSERT INTO table_definition (name, type, status, source_table, grain_key) "
                    + "VALUES ('dv_labels', 'ENRICHMENT', 'ACTIVE', 'dv_events', 'event_id'), "
                    + "('dv_groups', 'ENRICHMENT', 'ACTIVE', 'dv_events', 'event_id')");
            // Existing rows of every shape a pre-V42 build writes: an armed rollup, a disabled rollup, an armed
            // on_ingest enrich row following its target's parent, an armed group enrich row.
            insert(statement, "dv-rollup", "AGGREGATE", "dv_rollup", "'dv_events'", "'SCHEDULE'",
                    "'0 */5 * * * *'", "NULL", true);
            insert(statement, "dv-rollup-off", "AGGREGATE", "dv_rollup_off", "'dv_events'", "'SCHEDULE'",
                    "'0 */5 * * * *'", "NULL", false);
            insert(statement, "dv-labels", "ENRICH", "dv_labels", "NULL", "'ON_INGEST'", "NULL", "NULL", true);
            insert(statement, "dv-groups", "ENRICH", "dv_groups", "'dv_events'", "'GROUP'", "NULL",
                    "'chat_id'", true);
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
    void everyExistingRowSurvivesAndSatisfiesTheArmedGuard() throws SQLException {
        // Scoped to the four seeded rows: the other cases here add rows of their own, in no fixed order.
        final String seeded = " name IN ('dv-rollup', 'dv-rollup-off', 'dv-labels', 'dv-groups')";
        assertThat(count("SELECT count(*) FROM pipeline WHERE" + seeded)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM pipeline WHERE" + seeded + " AND NOT (NOT enabled OR"
                + " (trigger_kind IS NOT NULL AND (kind = 'ENRICH' OR input_table IS NOT NULL)))")).isZero();
    }

    @Test
    void disabledDraftIsStorableWithNoTriggerAndNoInput() throws SQLException {
        execute("INSERT INTO table_definition (name, type, status) VALUES ('dv_draft', 'SOURCE', 'ACTIVE')");
        execute(row("dv-draft", "AGGREGATE", "dv_draft", "NULL", "NULL", "NULL", "NULL", false));
        // "Picked schedule, has not typed the cron yet" is a draft too.
        execute("UPDATE pipeline SET trigger_kind = 'SCHEDULE' WHERE name = 'dv-draft'");
        assertThat(count("SELECT count(*) FROM pipeline WHERE name = 'dv-draft'")).isEqualTo(1);
    }

    @Test
    void anArmedRowWithNoTriggerKindIsRefusedNotAdmittedAsUnknown() throws SQLException {
        execute("INSERT INTO table_definition (name, type, status, source_table, grain_key) "
                + "VALUES ('dv_armed', 'ENRICHMENT', 'ACTIVE', 'dv_events', 'event_id')");
        assertThatThrownBy(() -> execute(row("dv-armed", "ENRICH", "dv_armed", "NULL", "NULL", "NULL", "NULL",
                true)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("violates check constraint");
    }

    @Test
    void eachRescopedGuardIsFalseRatherThanNullOnMissingTriggerKind() throws SQLException {
        // Evaluated as expressions over a synthetic armed row, so each guard is judged on its own rather than
        // masked by whichever constraint Postgres happens to check first.
        for (final String guard : Set.of("pipeline_cron_iff_schedule", "pipeline_group_by_iff_group",
                "pipeline_armed_is_complete")) {
            final String expression = checkExpression(guard);
            final String verdict = scalar("SELECT (" + expression + ")::text FROM (SELECT true AS enabled,"
                    + " NULL::text AS trigger_kind, NULL::text AS trigger_cron, NULL::text AS trigger_group_by,"
                    + " 'ENRICH'::text AS kind, NULL::text AS input_table) AS armed");
            assertThat(verdict).as("%s on an armed row with no trigger_kind", guard).isEqualTo("false");
        }
        final String input = scalar("SELECT (" + checkExpression("pipeline_input_required_unless_enrich")
                + ")::text FROM (SELECT true AS enabled, 'AGGREGATE'::text AS kind, NULL::text AS input_table)"
                + " AS armed");
        assertThat(input).isEqualTo("false");
    }

    @Test
    void theVerbatimGuardsStillRefuseContradictionOnDraft() throws SQLException {
        execute("INSERT INTO table_definition (name, type, status, source_table, grain_key) "
                + "VALUES ('dv_select', 'ENRICHMENT', 'ACTIVE', 'dv_events', 'event_id')");
        execute("INSERT INTO table_definition (name, type, status) VALUES ('dv_sched', 'SOURCE', 'ACTIVE')");
        assertThatThrownBy(() -> execute("INSERT INTO pipeline (name, kind, target, spec, trigger_kind,"
                + " member_select, enabled, generation, has_more, rebuilt_at, created_at, updated_at) VALUES"
                + " ('dv-select', 'ENRICH', 'dv_select', '{}'::jsonb, 'SCHEDULE', '{\"limit\": 1}'::jsonb,"
                + " false, 1, false, now(), now(), now())"))
                .hasMessageContaining("pipeline_member_select_only_group");
        assertThatThrownBy(() -> execute(row("dv-sched", "AGGREGATE", "dv_sched", "NULL", "'GROUP'", "NULL",
                "NULL", false)))
                .hasMessageContaining("pipeline_aggregate_is_scheduled");
    }

    @Test
    void theMigrationAddsNoColumnSoTheAuditMirrorNeedsNoWidening() throws SQLException {
        // An audited entity whose mirror lacks a column fails every write at commit. V42 re-scopes guards
        // and drops one NOT NULL; it must add nothing, and the columns it touches must stay mirrored.
        assertThat(columns("pipeline")).isEqualTo(pipelineColumnsBefore);
        assertThat(columns("pipeline_aud")).contains("trigger_kind", "trigger_cron", "trigger_group_by",
                "input_table", "enabled", "kind", "member_select");
        assertThat(scalar("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'pipeline_aud' AND column_name = 'trigger_kind'")).isEqualTo("YES");
    }

    // --- helpers --------------------------------------------------------------------------------------

    private static void insert(Statement statement, String name, String kind, String target, String input,
            String triggerKind, String cron, String groupBy, boolean enabled) throws SQLException {
        statement.execute(row(name, kind, target, input, triggerKind, cron, groupBy, enabled));
    }

    private static String row(String name, String kind, String target, String input, String triggerKind,
            String cron, String groupBy, boolean enabled) {
        return "INSERT INTO pipeline (name, kind, target, input_table, spec, trigger_kind, trigger_cron,"
                + " trigger_group_by, enabled, generation, has_more, rebuilt_at, created_at, updated_at) VALUES"
                + " ('" + name + "', '" + kind + "', '" + target + "', " + input + ", '{}'::jsonb, "
                + triggerKind + ", " + cron + ", " + groupBy + ", " + enabled + ", 1, false, now(), now(), now())";
    }

    private static String checkExpression(String constraint) throws SQLException {
        final String definition = scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conname = '" + constraint + "'");
        assertThat(definition).as("constraint %s exists", constraint).startsWith("CHECK");
        return definition.substring("CHECK ".length());
    }

    private static Set<String> columns(String table) throws SQLException {
        final Set<String> names = new LinkedHashSet<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static long count(String sql) throws SQLException {
        return Long.parseLong(scalar(sql));
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
        return (target == null ? configuration : configuration.target(target)).load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(PostgresContainerSupport.jdbcUrlFor(DATABASE),
                PostgresContainerSupport.username(), PostgresContainerSupport.password());
    }
}
