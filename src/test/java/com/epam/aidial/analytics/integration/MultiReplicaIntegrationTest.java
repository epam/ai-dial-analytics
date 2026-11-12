package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.replication.ReplicaAddressing;
import com.epam.aidial.analytics.clickhouse.replication.ReplicaRouter;
import com.epam.aidial.analytics.clickhouse.replication.ReplicatedTableConversion;
import com.epam.aidial.analytics.clickhouse.replication.SyncPlan;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service on a three-replica cluster ({@link MultiReplicaClickHouseSupport}): discovery, a pipeline run on its
 * home replica, user queries off the primary, DDL with a replica stopped, a sync that cannot complete, and Keeper
 * down. See {@code openspec/specs/clickhouse-replication/spec.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "poll.enabled=false",
        "clickhouse.replicas.sync-timeout=5s",
        "clickhouse.ddl.task-timeout=10s",
        "spring.datasource.hikari.maximum-pool-size=3"
})
@AutoConfigureMockMvc
@Import(MultiReplicaIntegrationTest.Addressing.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiReplicaIntegrationTest {

    private static final String TABLE = "mr_widgets";
    private static final String COLUMNS = "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
            + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]";

    private static final MultiReplicaClickHouseSupport CLUSTER = MultiReplicaClickHouseSupport.get();

    /** The replicas' reported host names resolve only inside the Docker network. */
    @TestConfiguration
    static class Addressing {

        @Bean
        ReplicaAddressing replicaAddressing() {
            return CLUSTER.addressing();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        CLUSTER.registerProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReplicaRouter router;

    @Autowired
    private ClickHouseClientWrapper clickHouse;

    @Autowired
    private ReplicatedTableConversion conversion;

    @AfterEach
    void restoreCluster() throws Exception {
        CLUSTER.startKeeper();
        for (final String replica : MultiReplicaClickHouseSupport.REPLICAS) {
            CLUSTER.start(replica);
            CLUSTER.execute(replica, "SYSTEM START FETCHES");
        }
        CLUSTER.awaitWritable();
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    @BeforeEach
    void cleanSlate() throws Exception {
        CLUSTER.awaitWritable();
        mockMvc.perform(delete("/v1/tables/" + TABLE));
    }

    private String hostName() {
        return clickHouse.query("SELECT hostName() AS h").getFirst().getString("h");
    }

    private static SyncPlan usageLog() {
        return SyncPlan.of(List.of("dial_usage_log"), List.of(), null);
    }

    private void createTable() throws Exception {
        IntegrationTables.source(mockMvc, TABLE, COLUMNS, "[\"widget_id\"]");
    }

    @Test
    void pipelineRunsExecuteOnTheirHomeOffThePrimaryAfterSyncing() {
        final Set<String> homes = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            try (ReplicaRouter.PipelineRun run = router.openRun("pipeline-" + i, usageLog())) {
                assertThat(hostName()).isEqualTo(run.replica());
                homes.add(run.replica());
            }
        }
        assertThat(homes).containsExactlyInAnyOrder("chi-1", "chi-2");
        try (ReplicaRouter.PipelineRun run = router.openRun("pipeline-0", usageLog())) {
            assertThat(run.replica()).isIn(homes);
        }
        // Unbound, a statement runs on the primary.
        assertThat(hostName()).isEqualTo(MultiReplicaClickHouseSupport.PRIMARY);
    }

    @Test
    void userQueriesAreSpreadOverTheNonPrimaryReplicas() {
        final Set<String> served = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            served.add(router.query(this::hostName));
        }
        assertThat(served).containsExactlyInAnyOrder("chi-1", "chi-2");
    }

    @Test
    void homeThatCannotCatchUpRefusesTheRunAsUnavailable() {
        final String home;
        try (ReplicaRouter.PipelineRun run = router.openRun("stalled-pipeline", usageLog())) {
            home = run.replica();
        }
        CLUSTER.execute(home, "SYSTEM STOP FETCHES");
        CLUSTER.execute(MultiReplicaClickHouseSupport.PRIMARY,
                "INSERT INTO dial_usage_log (event_id, request_time, trace_id, core_span_id) "
                        + "VALUES (generateUUIDv4(), now64(3), 'mr-trace', 'mr-span')");

        final long started = System.nanoTime();
        assertThatThrownBy(() -> router.openRun("stalled-pipeline", usageLog()).close())
                .isInstanceOf(ClickHouseUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));

        CLUSTER.execute(home, "SYSTEM START FETCHES");
        try (ReplicaRouter.PipelineRun run = router.openRun("stalled-pipeline", usageLog())) {
            assertThat(clickHouse.query("SELECT count() AS c FROM dial_usage_log WHERE trace_id = 'mr-trace'")
                    .getFirst().getLong("c")).isEqualTo(1L);
        }
        CLUSTER.execute(MultiReplicaClickHouseSupport.PRIMARY, "TRUNCATE TABLE dial_usage_log");
    }

    @Test
    void createAndPatchSucceedWithReplicaStoppedAndReachItOnRestart() throws Exception {
        CLUSTER.stop("chi-2");
        final long started = System.nanoTime();
        createTable();
        mockMvc.perform(patch("/v1/tables/" + TABLE + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"tier\",\"name\":\"tier\",\"type\":\"string\"}]}"))
                .andExpect(status().isOk());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(60));

        CLUSTER.start("chi-2");
        // The restarted replica applies the create and the patch from the database log once its Keeper session is back.
        CLUSTER.awaitWritable();
        final long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (true) {
            try {
                CLUSTER.execute("chi-2", "SYSTEM SYNC DATABASE REPLICA " + MultiReplicaClickHouseSupport.DATABASE);
                break;
            } catch (IllegalStateException notYet) {
                if (System.nanoTime() > deadline) {
                    throw notYet;
                }
                MultiReplicaClickHouseSupport.sleep(1_000);
            }
        }
        try (Client client = CLUSTER.client("chi-2")) {
            assertThat(client.queryAll("SELECT name FROM system.columns WHERE database = currentDatabase() AND table = '"
                    + TABLE + "' ORDER BY name").stream().map(row -> row.getString("name")).toList())
                    .contains("widget_id", "score", "tier");
        }
    }

    @Test
    void withKeeperDownQueriesAreServedAndWritesAnswerUnavailable() throws Exception {
        createTable();
        CLUSTER.stopKeeper();
        // Long enough for the replicas to notice the lost session.
        MultiReplicaClickHouseSupport.sleep(7_000);

        assertThat(router.query(() -> clickHouse.query("SELECT count() AS c FROM " + TABLE).getFirst().getLong("c")))
                .isZero();
        mockMvc.perform(post("/v1/tables/" + TABLE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":1}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("clickhouse_unavailable"));
        assertThatThrownBy(() -> router.openRun("keeper-down", usageLog()).close())
                .isInstanceOf(ClickHouseUnavailableException.class);
    }

    /** A plain table in a Replicated database keeps one copy per replica; copies that diverged are not converted. */
    @Test
    void plainTableWhoseCopiesDivergedIsNotConvertedWhileReplicasAreActiveOrStopped() {
        final String plain = "mr_plain_rates";
        try (Client primary = CLUSTER.client(MultiReplicaClickHouseSupport.PRIMARY)) {
            primary.queryAll("CREATE TABLE " + plain + " (id UInt32) ENGINE = MergeTree ORDER BY id",
                    new QuerySettings().serverSetting("database_replicated_allow_only_replicated_engine", "0"));
        }
        try {
            CLUSTER.execute("chi-1", "SYSTEM SYNC DATABASE REPLICA " + MultiReplicaClickHouseSupport.DATABASE);
            CLUSTER.execute("chi-1", "INSERT INTO " + plain + " VALUES (1), (2)");

            assertThatThrownBy(conversion::convert).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(plain).hasMessageContaining("chi-1").hasMessageContaining("No DDL was issued");

            CLUSTER.stop("chi-2");
            assertThatThrownBy(conversion::convert).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("chi-2").hasMessageContaining("cannot be inspected");
            try (Client primary = CLUSTER.client(MultiReplicaClickHouseSupport.PRIMARY)) {
                assertThat(primary.queryAll("SELECT engine FROM system.tables WHERE database = currentDatabase() AND name = '"
                        + plain + "'").getFirst().getString("engine")).isEqualTo("MergeTree");
                assertThat(primary.queryAll("SELECT count() AS n FROM system.tables WHERE database = currentDatabase() "
                        + "AND name LIKE '" + ReplicatedTableConversion.PREFIX + "%'").getFirst().getLong("n")).isZero();
            }
        } finally {
            CLUSTER.start("chi-2");
            CLUSTER.awaitWritable();
            CLUSTER.execute(MultiReplicaClickHouseSupport.PRIMARY, "DROP TABLE IF EXISTS " + plain + " SYNC");
        }
    }
}
