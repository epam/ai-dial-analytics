package com.epam.aidial.analytics.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@code V25__denormalize_table_catalog.sql} against realistic pre-migration rows.
 *
 * <p>The other integration tests inherit an already-migrated schema from the Spring context, so none of
 * them can see what the backfill did — by the time they run, {@code enrichment_grain} and
 * {@code table_ordering_key} no longer exist. This test migrates a throwaway database to {@code V24},
 * seeds it as a pre-migration catalog would look, then applies {@code V25} and reads the result.
 *
 * <p>The ordering-key assertion is the one that matters. The backfill aggregates with
 * {@code array_agg(column_name ORDER BY ordinal)}; drop that {@code ORDER BY} and PostgreSQL is free to
 * return the elements in any order, which would silently permute the MergeTree {@code ORDER BY} every
 * subsequent {@code CREATE TABLE} is rendered from. A permutation is not an error anywhere — it would
 * surface much later as wrong query plans — so it has to be asserted here or not at all.
 */
class CatalogDenormalizationMigrationTest {

    private static final String DATABASE = "denormalization_probe";
    private static final String LOCATION = "classpath:db/migration/postgres";

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                        PostgresContainerSupport.jdbcUrl(),
                        PostgresContainerSupport.username(),
                        PostgresContainerSupport.password());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE);
        }

        flyway("24").migrate();
        seedPreMigrationCatalog();
        flyway(null).migrate();
    }

    private static Flyway flyway(String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(PostgresContainerSupport.jdbcUrlFor(DATABASE),
                        PostgresContainerSupport.username(),
                        PostgresContainerSupport.password())
                .locations(LOCATION);
        return (target == null ? configuration : configuration.target(target)).load();
    }

    /**
     * A catalog as V24 leaves it: a source keyed on three columns (so a permuted backfill is visible), an
     * ACTIVE enrichment carrying a grain, and a PENDING enrichment that has none yet — the state an
     * enrichment is in between {@code POST /v1/tables} and {@code POST /v1/tables/{name}/schema}.
     */
    private static void seedPreMigrationCatalog() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO table_definition (name, type, status) "
                    + "VALUES ('widget_metrics', 'SOURCE', 'ACTIVE')");
            statement.execute("INSERT INTO table_definition (name, type, source_table, status) VALUES "
                    + "('widget_labels', 'ENRICHMENT', 'widget_metrics', 'ACTIVE'), "
                    + "('widget_scores', 'ENRICHMENT', 'widget_metrics', 'PENDING')");

            // Deliberately inserted out of ordinal order: a backfill that leans on insertion order rather
            // than on ORDER BY ordinal would pass against an already-sorted table and fail here.
            statement.execute("INSERT INTO table_ordering_key (table_name, ordinal, column_name) VALUES "
                    + "('widget_metrics', 2, 'observed_at'), "
                    + "('widget_metrics', 0, 'project_id'), "
                    + "('widget_metrics', 1, 'widget_id'), "
                    + "('widget_labels', 0, 'widget_id')");

            statement.execute("INSERT INTO enrichment_grain (table_name, grain_key, cardinality) "
                    + "VALUES ('widget_labels', 'widget_id', 'ZERO_OR_ONE')");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                PostgresContainerSupport.jdbcUrlFor(DATABASE),
                PostgresContainerSupport.username(),
                PostgresContainerSupport.password());
    }

    @Test
    void orderingKeySurvivesInOrder() throws SQLException {
        assertThat(orderingKeyOf("widget_metrics"))
                .containsExactly("project_id", "widget_id", "observed_at");
        assertThat(orderingKeyOf("widget_labels")).containsExactly("widget_id");
    }

    /** A table that had no ordering-key rows lands on the empty array, not NULL — the column is NOT NULL. */
    @Test
    void tableWithoutOrderingKeyRowsBecomesEmptyArray() throws SQLException {
        assertThat(orderingKeyOf("widget_scores")).isEmpty();
    }

    @Test
    void grainLandsOnItsOwnRowOnly() throws SQLException {
        assertThat(grainKeyOf("widget_labels")).isEqualTo("widget_id");
        assertThat(grainKeyOf("widget_scores")).isNull();
        assertThat(grainKeyOf("widget_metrics")).isNull();
    }

    @Test
    void bothSourceTablesAreGone() throws SQLException {
        assertThat(tableExists("enrichment_grain")).isFalse();
        assertThat(tableExists("table_ordering_key")).isFalse();
    }

    /**
     * The invariant the split could not express: {@code enrichment_grain}'s foreign key pointed at
     * {@code table_definition(name)} and could not see the row's {@code type}, so nothing stopped a grain
     * row for a SOURCE. As a column on the row itself, the check has the type in hand.
     */
    @Test
    void grainKeyOnSourceRowIsRejected() {
        assertThatThrownBy(() -> execute(
                "UPDATE table_definition SET grain_key = 'widget_id' WHERE name = 'widget_metrics'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("grain_key_only_on_enrichment");
    }

    /**
     * The restore runs in a {@code finally} because the seeded catalog is built once in
     * {@code @BeforeAll} and shared by every method here: were the assertion to fail, a leftover
     * {@code grain_key} would also fail {@link #grainLandsOnItsOwnRowOnly()} with a message pointing at
     * the backfill rather than at the real cause.
     */
    @Test
    void grainKeyOnAnEnrichmentIsAccepted() throws SQLException {
        try {
            execute("UPDATE table_definition SET grain_key = 'widget_id' WHERE name = 'widget_scores'");
            assertThat(grainKeyOf("widget_scores")).isEqualTo("widget_id");
        } finally {
            execute("UPDATE table_definition SET grain_key = NULL WHERE name = 'widget_scores'");
        }
    }

    @AfterAll
    static void dropProbeDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                        PostgresContainerSupport.jdbcUrl(),
                        PostgresContainerSupport.username(),
                        PostgresContainerSupport.password());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> orderingKeyOf(String table) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT ordering_key FROM table_definition WHERE name = '" + table + "'")) {
            assertThat(rs.next()).as("row for %s", table).isTrue();
            Array array = rs.getArray(1);
            assertThat(array).as("ordering_key of %s is never NULL", table).isNotNull();
            List<String> values = new ArrayList<>();
            for (Object element : (Object[]) array.getArray()) {
                values.add((String) element);
            }
            return values;
        }
    }

    private static String grainKeyOf(String table) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT grain_key FROM table_definition WHERE name = '" + table + "'")) {
            assertThat(rs.next()).as("row for %s", table).isTrue();
            return rs.getString(1);
        }
    }

    private static boolean tableExists(String table) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT to_regclass('public." + table + "') IS NOT NULL")) {
            assertThat(rs.next()).isTrue();
            return rs.getBoolean(1);
        }
    }
}
