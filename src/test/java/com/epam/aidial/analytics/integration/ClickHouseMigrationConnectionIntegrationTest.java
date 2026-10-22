package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.ClickHouseMigrationRunner;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection Flyway migrates over reads with {@code final=1} — asserted against the live server, not
 * against the URL string.
 *
 * <p>Why this matters enough for its own class: every ingest table is a {@code ReplacingMergeTree}, so a
 * data-moving migration ({@code INSERT … SELECT}) that reads without
 * {@code FINAL} copies every unmerged version of a re-ingested row and lets the target's version column
 * pick a survivor arbitrarily. The runner passes the setting through the driver's
 * {@code custom_settings} URL parameter; a driver upgrade that renamed or dropped that parameter would
 * silently turn the setting off, and only the server can say whether it took. So the test opens the
 * exact URL the runner builds for this container and asks the server.
 *
 * <p>Requires a running Docker daemon.
 */
class ClickHouseMigrationConnectionIntegrationTest extends AbstractClickHouseIntegrationTest {

    @Autowired
    private ClickHouseProperties clickHouse;

    @Test
    void migrationConnectionRunsWithFinalEnabled() throws Exception {
        final String url = ClickHouseMigrationRunner.jdbcUrl(clickHouse.host(), clickHouse.port(), clickHouse.database());
        assertThat(url).endsWith("?custom_settings=" + ClickHouseMigrationRunner.MIGRATION_SETTINGS);

        try (Connection connection = DriverManager.getConnection(
                        url, ClickHouseContainerSupport.CLICKHOUSE.getUsername(), ClickHouseContainerSupport.CLICKHOUSE.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT getSetting('final') AS f")) {
            assertThat(result.next()).isTrue();
            // `final` is a Bool setting: the server renders it "true" over the JDBC path, "1" over others.
            assertThat(result.getString("f")).isIn("1", "true");
        }
    }

    /**
     * The setting is observable through a statement's behaviour too, not only through
     * {@code getSetting}: two versions of one payload row read as one on the migration connection, which is
     * what makes a plain {@code FROM dial_usage_log} in a migration see the merged table.
     */
    @Test
    void migrationConnectionCollapsesUnmergedVersionsWithoutSpellingFinal() throws Exception {
        truncate();
        // One literal request_time for both versions, not two now64(3) reads: the table is PARTITION BY
        // toYYYYMMDD(request_time) and a ReplacingMergeTree collapses only within a partition, so two
        // clock reads straddling UTC midnight would land the versions in different partitions and this
        // would count 2. Today's date keeps the part inside the table's 90-day TTL.
        final String requestTime = "'" + fixtureTime("00:00:00.000") + "'";
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_body)"
                + " VALUES (toUUID('77777777-7777-7777-7777-777777777777'), " + requestTime + ", 't', 's', 'first')");
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_body)"
                + " VALUES (toUUID('77777777-7777-7777-7777-777777777777'), " + requestTime + ", 't', 's', 'second')");

        final String url = ClickHouseMigrationRunner.jdbcUrl(clickHouse.host(), clickHouse.port(), clickHouse.database());
        try (Connection connection = DriverManager.getConnection(
                        url, ClickHouseContainerSupport.CLICKHOUSE.getUsername(), ClickHouseContainerSupport.CLICKHOUSE.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count() AS n FROM dial_usage_log_payload")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("n")).isEqualTo(1L);
        }
    }
}
