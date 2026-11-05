package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whole-application starts against the single-replica container, for what the startup storage phase does before
 * anything else runs: an {@code Atomic} database is refused with ClickHouse and the catalog untouched, a start with
 * migrations disabled over replicated tables succeeds and runs no scheduled job before the gate opens, and one over a
 * plain table issues no DDL and fails the table check. See {@code openspec/specs/clickhouse-replication/spec.md}.
 */
class StorageStartupIntegrationTest {

    private static final String PLAIN_DATABASE = "st_plain";

    private static Client client;

    @BeforeAll
    static void connect() {
        final var container = ClickHouseContainerSupport.CLICKHOUSE;
        client = new Client.Builder()
                .addEndpoint("http://" + container.getHost() + ":" + container.getMappedPort(ClickHouseContainerSupport.HTTP_PORT) + "/")
                .setUsername(container.getUsername())
                .setPassword(container.getPassword())
                .build();
    }

    @AfterAll
    static void disconnect() throws Exception {
        client.queryAll("DROP DATABASE IF EXISTS " + PLAIN_DATABASE + " SYNC");
        client.close();
    }

    private static StandaloneStartup startup(String catalog, String database, boolean migrations) {
        final var container = ClickHouseContainerSupport.CLICKHOUSE;
        return new StandaloneStartup(catalog)
                .property("clickhouse.host", container.getHost())
                .property("clickhouse.port", container.getMappedPort(ClickHouseContainerSupport.HTTP_PORT))
                .property("clickhouse.database", database)
                .property("clickhouse.user", container.getUsername())
                .property("clickhouse.password", container.getPassword())
                .property("clickhouse.writer.user", container.getUsername())
                .property("clickhouse.writer.password", container.getPassword())
                .property("clickhouse.migrations.enabled", migrations);
    }

    private static List<String> tables(String database) {
        return client.queryAll("SELECT name FROM system.tables WHERE database = '" + database + "' ORDER BY name").stream()
                .map(row -> row.getString("name")).toList();
    }

    /** Records, for every run of a fast scheduled job, whether the storage gate was open when it ran. */
    @TestConfiguration
    static class GateSpy {

        static final List<Boolean> OBSERVED = new CopyOnWriteArrayList<>();

        @Bean
        Spy gateSpy(StorageReadyGate gate) {
            return new Spy(gate);
        }

        record Spy(StorageReadyGate gate) {

            @Scheduled(fixedRate = 5)
            void run() {
                OBSERVED.add(gate.isOpen());
            }
        }
    }

    @Test
    void atomicDatabaseIsRefusedBeforeAnyDdlMigrationOrCatalogRow() {
        final List<String> before = tables("default");
        final StandaloneStartup startup = startup("st_atomic", "default", true);

        assertThatThrownBy(startup::start).rootCause()
                .hasMessageContaining("'default'").hasMessageContaining("Atomic")
                .hasMessageContaining("docs/clickhouse-replication.md");

        assertThat(tables("default")).as("no DDL").isEqualTo(before);
        assertThat(startup.catalogTables()).as("no PostgreSQL migration, so no catalog row").isEmpty();
    }

    @Test
    void migrationsDisabledOverReplicatedTablesStartsAndRunsNoScheduledJobBeforeTheGateOpens() {
        // Provisioned replicated by a start with migrations enabled, then started again with them disabled.
        try (ConfigurableApplicationContext provisioning = startup("st_enabled", ClickHouseContainerSupport.DATABASE, true)
                .start()) {
            assertThat(provisioning.getBean(StorageReadyGate.class).isOpen()).isTrue();
        }
        GateSpy.OBSERVED.clear();
        try (ConfigurableApplicationContext context = startup("st_disabled", ClickHouseContainerSupport.DATABASE, false)
                .source(GateSpy.class).start()) {
            assertThat(context.getBean(StorageReadyGate.class).isOpen()).isTrue();
            final long deadline = System.nanoTime() + 5_000_000_000L;
            while (GateSpy.OBSERVED.isEmpty() && System.nanoTime() < deadline) {
                MultiReplicaClickHouseSupport.sleep(10);
            }
        }
        assertThat(GateSpy.OBSERVED).as("the scheduled job ran, and only once the gate was open")
                .isNotEmpty().containsOnly(true);
    }

    @Test
    void migrationsDisabledOverPlainTableIssuesNoDdlAndFailsTheTableCheck() {
        client.queryAll("DROP DATABASE IF EXISTS " + PLAIN_DATABASE + " SYNC");
        client.queryAll("CREATE DATABASE " + PLAIN_DATABASE + " ENGINE = Replicated('/clickhouse/databases/"
                + PLAIN_DATABASE + "', '{shard}', '{replica}')");
        client.queryAll("CREATE TABLE " + PLAIN_DATABASE + ".dial_usage_log (event_id UUID) ENGINE = MergeTree ORDER BY event_id",
                new QuerySettings().serverSetting("database_replicated_allow_only_replicated_engine", "0"));

        final StandaloneStartup startup = startup("st_plain", PLAIN_DATABASE, false)
                .property("sync.validate-schema", false);
        assertThatThrownBy(startup::start)
                .hasStackTraceContaining("'dial_usage_log' uses MergeTree");

        assertThat(tables(PLAIN_DATABASE)).containsExactly("dial_usage_log");
        assertThat(client.queryAll("SELECT engine FROM system.tables WHERE database = '" + PLAIN_DATABASE
                + "' AND name = 'dial_usage_log'").getFirst().getString("engine")).isEqualTo("MergeTree");
    }
}
