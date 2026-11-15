package com.epam.aidial.analytics.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@code V56__role_keyed_table_access.sql} against catalog rows as {@code V55} leaves them: the stored grant
 * lists and row policies survive untouched, every table starts at access generation 0 with no column policy, and the
 * audit mirror gains the column an audited entity now writes (a mirror lacking a column fails every write at commit).
 */
class RoleKeyedTableAccessMigrationTest {

    private static final String DATABASE = "role_keyed_access_probe";
    private static final String LOCATION = "classpath:db/migration/postgres";
    private static final String ACCESS = "{\"get\": [\"team-g\"], \"read\": [\"team-r\"], \"write\": [\"team-w\"], \"modify\": []}";
    private static final String POLICIES = "{\"team-r\": {\"filter\": {\"op\": \"eq\", \"args\": [{\"type\": \"field\", \"name\": \"k\"},"
            + " {\"type\": \"value\", \"value_type\": \"string\", \"value\": \"a\"}]}}}";

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        try (Connection admin = DriverManager.getConnection(PostgresContainerSupport.jdbcUrl(),
                        PostgresContainerSupport.username(), PostgresContainerSupport.password());
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        flyway("55").migrate();
        execute("INSERT INTO table_definition (name, type, status, access, row_policies) VALUES"
                + " ('rk_granted', 'SOURCE', 'ACTIVE', '" + ACCESS + "'::jsonb, '" + POLICIES + "'::jsonb),"
                + " ('rk_plain', 'SOURCE', 'ACTIVE', '{}'::jsonb, '{}'::jsonb)");
        execute("INSERT INTO revinfo (id, timestamp) VALUES (1, 0)");
        execute("INSERT INTO table_definition_aud (name, rev, revtype, access, row_policies) VALUES"
                + " ('rk_granted', 1, 0, '" + ACCESS + "'::jsonb, '" + POLICIES + "'::jsonb)");
        flyway("56").migrate();
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
    void existingGrantsAndRowPoliciesAreUntouched() throws SQLException {
        assertThat(scalar("SELECT access = '" + ACCESS + "'::jsonb FROM table_definition WHERE name = 'rk_granted'")).isEqualTo("t");
        assertThat(scalar("SELECT row_policies = '" + POLICIES + "'::jsonb FROM table_definition WHERE name = 'rk_granted'"))
                .isEqualTo("t");
    }

    @Test
    void everyTableStartsAtGenerationZeroWithNoColumnPolicy() throws SQLException {
        assertThat(scalar("SELECT count(*) FROM table_definition WHERE access_generation <> 0 OR column_policies <> '{}'::jsonb"))
                .isEqualTo("0");
        // The seeded system tables as well as the two rows inserted here.
        assertThat(Integer.parseInt(scalar("SELECT count(*) FROM table_definition"))).isGreaterThan(2);
    }

    @Test
    void theAuditMirrorCarriesColumnPoliciesAndAnEarlierRevisionReadsNull() throws SQLException {
        assertThat(scalar("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'table_definition_aud' AND column_name = 'column_policies'")).isEqualTo("YES");
        assertThat(scalar("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = 'table_definition_aud' AND column_name = 'access_generation'")).isEqualTo("0");
        assertThat(scalar("SELECT column_policies IS NULL FROM table_definition_aud WHERE name = 'rk_granted'")).isEqualTo("t");
        assertThat(scalar("SELECT access = '" + ACCESS + "'::jsonb FROM table_definition_aud WHERE name = 'rk_granted'"))
                .isEqualTo("t");
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

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(PostgresContainerSupport.jdbcUrlFor(DATABASE), PostgresContainerSupport.username(),
                        PostgresContainerSupport.password())
                .locations(LOCATION)
                .target(target)
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(PostgresContainerSupport.jdbcUrlFor(DATABASE),
                PostgresContainerSupport.username(), PostgresContainerSupport.password());
    }
}
