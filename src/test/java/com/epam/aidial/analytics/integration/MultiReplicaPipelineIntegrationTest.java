package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.replication.ReplicaAddressing;
import com.epam.aidial.analytics.clickhouse.replication.ReplicaRouter;
import com.epam.aidial.analytics.clickhouse.replication.SyncPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pipelines on a three-replica cluster ({@link MultiReplicaClickHouseSupport}): a Class-1 and an aggregate pipeline
 * kept correct under continuous ingest while their homes are disrupted, runner scan pages across a home change, a
 * home that missed a create, a sync timeout under a pipeline budget, and startup with a replica stopped. See
 * {@code openspec/specs/clickhouse-replication/spec.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "poll.enabled=false",
        "clickhouse.replicas.sync-timeout=5s",
        "clickhouse.replicas.discovery-interval=5s",
        "clickhouse.ddl.task-timeout=10s",
        "enrichment.scan.safety-lag=2s",
        "aggregate.safety-lag=2s",
        // Shorter than the sync bound, so a sync timeout under a run is visibly not a budget refusal.
        "pipeline.budget.timeout=3s",
        "spring.datasource.hikari.maximum-pool-size=3"
})
@AutoConfigureMockMvc
@Import(MultiReplicaPipelineIntegrationTest.Addressing.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiReplicaPipelineIntegrationTest {

    private static final MultiReplicaClickHouseSupport CLUSTER = MultiReplicaClickHouseSupport.get();
    private static final String PRIMARY = MultiReplicaClickHouseSupport.PRIMARY;

    private static final String MODEL = "mr_model";
    private static final String CLASS1 = "mr-class1";
    private static final String DAILY = "mr_daily";
    private static final String AGGREGATE = "mr-daily";
    private static final String TOX = "mr_tox";
    private static final String SCAN = "mr-scan";

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

    @BeforeEach
    void cleanSlate() throws Exception {
        class1Cursor = null;
        restore();
        for (final String table : List.of("dial_usage_log", "dial_usage_log_payload")) {
            CLUSTER.execute(PRIMARY, "TRUNCATE TABLE " + table);
        }
    }

    @AfterEach
    void restore() throws Exception {
        CLUSTER.startKeeper();
        for (final String replica : MultiReplicaClickHouseSupport.REPLICAS) {
            CLUSTER.start(replica);
            CLUSTER.execute(replica, "SYSTEM START FETCHES");
        }
        CLUSTER.awaitWritable();
        for (final String pipeline : List.of(CLASS1, AGGREGATE, SCAN)) {
            mockMvc.perform(delete("/v1/pipelines/" + pipeline));
        }
        for (final String table : List.of(MODEL, DAILY, TOX, "mr_late")) {
            mockMvc.perform(delete("/v1/tables/" + table));
        }
    }

    /** The replica {@code pipeline}'s runs currently land on. */
    private String home(String pipeline) {
        try (ReplicaRouter.PipelineRun run = router.openRun(pipeline, SyncPlan.of(List.of("dial_usage_log"), List.of(), null))) {
            return run.replica();
        }
    }

    private static String eventId(int n) {
        return String.format("00000000-0000-4000-8000-%012d", n);
    }

    /**
     * Ingest's order: each payload part before the source part it belongs to, both on the primary. A payload row carries
     * its parent's {@code request_time}, as real ingest writes both from one record: the grain join bounds the payload
     * side by the parent's value.
     */
    private static void ingest(Client primary, int from, int count) {
        final String requestTime = "toDateTime64('" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneOffset.UTC).format(Instant.now()) + "', 3, 'UTC')";
        final StringBuilder payload = new StringBuilder();
        final StringBuilder source = new StringBuilder();
        for (int n = from; n < from + count; n++) {
            final String id = eventId(n);
            payload.append(payload.isEmpty() ? "" : ",").append("(toUUID('").append(id).append("'), ").append(requestTime)
                    .append(", 't").append(n)
                    .append("', 's', '{\"model\":\"m-").append(n).append("\"}')");
            source.append(source.isEmpty() ? "" : ",").append("(toUUID('").append(id).append("'), ").append(requestTime)
                    .append(", 'p', 'd")
                    .append(n % 3).append("', 't").append(n).append("', 's', 1)");
        }
        primary.queryAll("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_body) VALUES "
                + payload);
        primary.queryAll("INSERT INTO dial_usage_log (event_id, request_time, project_id, deployment, trace_id, core_span_id, "
                + "total_tokens) VALUES " + source);
    }

    private void createClass1() throws Exception {
        IntegrationTables.enrichment(mockMvc, MODEL, "dial_usage_log", "event_id",
                "[{\"source_name\":\"body_model\",\"name\":\"body_model\",\"type\":\"string\"},"
                        + "{\"source_name\":\"pipeline_generation\",\"name\":\"pipeline_generation\",\"type\":\"long\"},"
                        + "{\"source_name\":\"enriched_at\",\"name\":\"enriched_at\",\"type\":\"timestamp\"}]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + CLASS1 + "\"," + Transforms.sql().output("body_model",
                                "json_extract_string(\\\"dial_usage_log_payload.request_body\\\", 'model')").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + MODEL + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated());
    }

    private void createAggregate() throws Exception {
        IntegrationTables.upsertSource(mockMvc, DAILY, "[{\"source_name\":\"deployment\",\"name\":\"deployment\",\"type\":\"string\"},"
                + "{\"source_name\":\"day\",\"name\":\"day\",\"type\":\"timestamp\"},"
                + "{\"source_name\":\"call_count\",\"name\":\"call_count\",\"type\":\"long\"}]", "[\"deployment\",\"day\"]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + AGGREGATE + "\",\"kind\":\"aggregate\",\"target\":\"" + DAILY + "\","
                                + "\"inputs\":[\"dial_usage_log\"],\"group_by\":[{\"column\":\"deployment\"},"
                                + "{\"trunc\":{\"column\":\"request_time\",\"unit\":\"day\"},\"as\":\"day\"}],"
                                + "\"measures\":[{\"name\":\"call_count\",\"fn\":\"count\"}],"
                                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 0 1 1 *\"}}"))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/pipelines/" + AGGREGATE).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    /** The runner's cursor over the Class-1 pipeline: advanced on a success, kept on a refusal, as a runner keeps it. */
    private String class1Cursor;

    private MockHttpServletResponse materialize() throws Exception {
        final MockHttpServletResponse response = mockMvc.perform(runnerPost("/v1/pipelines/" + CLASS1 + "/materialize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(class1Cursor == null ? "{\"limit\":1000}" : "{\"cursor\":\"" + class1Cursor + "\",\"limit\":1000}"))
                .andReturn().getResponse();
        if (response.getStatus() == 200) {
            // An empty window echoes the caller's cursor, and none when the caller sent none.
            final Map<String, Object> page = JsonPath.read(response.getContentAsString(), "$");
            if (page.get("next_cursor") != null) {
                class1Cursor = (String) page.get("next_cursor");
            }
        }
        return response;
    }

    private MockHttpServletResponse refresh() throws Exception {
        return mockMvc.perform(post("/v1/pipelines/" + AGGREGATE + "/refresh")).andReturn().getResponse();
    }

    /** The aggregate's cursor version; null before its first refresh, when the state carries none. */
    private Object aggregateCursor() throws Exception {
        final Map<String, Object> state = JsonPath.read(
                mockMvc.perform(get("/v1/pipelines/" + AGGREGATE)).andReturn().getResponse().getContentAsString(), "$.state");
        return state.get("cursor_version");
    }

    private static long scalar(Client client, String sql) {
        return client.queryAll(sql).getFirst().getLong("n");
    }

    /**
     * Ingest keeps writing on the primary while the Class-1 pipeline's home stops fetching, another replica restarts
     * and the home is killed, forcing a home change. Runs refused meanwhile move nothing; afterwards every source row
     * is enriched from its own payload and the rollup equals a recomputation.
     */
    @Test
    void pipelinesStayCorrectUnderIngestWhileTheirHomesAreDisrupted() throws Exception {
        createClass1();
        createAggregate();
        final String home = home(CLASS1);
        final String other = home.equals("chi-1") ? "chi-2" : "chi-1";

        final AtomicReference<Throwable> ingestFailure = new AtomicReference<>();
        final AtomicInteger ingested = new AtomicInteger();
        // The disruptions run on the ingest timeline, so every one of them lands while ingest is still writing, however
        // long the refused runs on the main thread take.
        final Thread ingest = new Thread(() -> {
            try (Client primary = CLUSTER.client(PRIMARY)) {
                for (int batch = 0; batch < 60; batch++) {
                    switch (batch) {
                        case 8 -> CLUSTER.execute(home, "SYSTEM STOP FETCHES");
                        case 20 -> CLUSTER.execute(home, "SYSTEM START FETCHES");
                        case 28 -> {
                            CLUSTER.stop(other);
                            CLUSTER.start(other);
                        }
                        case 40 -> CLUSTER.stop(home);
                        default -> { }
                    }
                    ingest(primary, batch * 25, 25);
                    ingested.addAndGet(25);
                    Thread.sleep(250);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Throwable ex) {
                ingestFailure.set(ex);
            }
        }, "mr-ingest");
        ingest.start();
        try {
            disruptAndCheck(ingest, ingestFailure, ingested, home, other);
        } finally {
            ingest.interrupt();
            ingest.join(Duration.ofSeconds(30));
        }
    }

    private void disruptAndCheck(Thread ingest, AtomicReference<Throwable> ingestFailure, AtomicInteger ingested,
            String home, String other) throws Exception {
        int refused = 0;
        while (ingest.isAlive()) {
            if (materialize().getStatus() == 503) {
                refused++;
            }
            final Object before = aggregateCursor();
            if (refresh().getStatus() == 503) {
                refused++;
                assertThat(aggregateCursor()).as("a refused refresh moves no cursor").isEqualTo(before);
            }
        }
        assertThat(ingestFailure.get()).isNull();

        // Drain: past the safety lag, until both pipelines have nothing left.
        final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        int quiet = 0;
        String last = "";
        while (quiet < 2 && System.nanoTime() < deadline) {
            MultiReplicaClickHouseSupport.sleep(1_000);
            final MockHttpServletResponse written = materialize();
            final MockHttpServletResponse rolled = refresh();
            last = "materialize " + written.getStatus() + " " + written.getContentAsString() + " | refresh " + rolled.getStatus()
                    + " " + rolled.getContentAsString();
            final boolean nothingLeft = written.getStatus() == 200 && written.getContentAsString().contains("\"rows_written\":0")
                    && rolled.getStatus() == 200 && rolled.getContentAsString().contains("\"rows_written\":0");
            quiet = nothingLeft ? quiet + 1 : 0;
        }
        assertThat(quiet).as("both pipelines drained; last: %s", last).isEqualTo(2);

        try (Client primary = CLUSTER.client(PRIMARY)) {
            primary.queryAll("SYSTEM SYNC REPLICA " + MODEL);
            primary.queryAll("SYSTEM SYNC REPLICA " + DAILY);
            assertThat(scalar(primary, "SELECT count() AS n FROM dial_usage_log FINAL")).isEqualTo(ingested.get());
            assertThat(scalar(primary, "SELECT count() AS n FROM dial_usage_log WHERE event_id NOT IN (SELECT event_id FROM "
                    + MODEL + ")")).as("no source row missing from the enrichment").isZero();
            assertThat(scalar(primary, "SELECT count() AS n FROM " + MODEL + " FINAL WHERE body_model IS NULL OR body_model "
                    + "!= concat('m-', toString(toUInt64(substring(toString(event_id), 25))))"))
                    .as("no row enriched without its own payload").isZero();
            assertThat(scalar(primary, "SELECT toInt64(sum(call_count)) AS n FROM " + DAILY + " FINAL"))
                    .as("the rollup equals a recomputation").isEqualTo(ingested.get());
            for (int deployment = 0; deployment < 3; deployment++) {
                assertThat(scalar(primary, "SELECT toInt64(sum(call_count)) AS n FROM " + DAILY + " FINAL WHERE deployment = 'd"
                        + deployment + "'")).isEqualTo(scalar(primary, "SELECT count() AS n FROM dial_usage_log FINAL "
                        + "WHERE deployment = 'd" + deployment + "'"));
            }
        }
        assertThat(home(CLASS1)).as("the killed home was replaced").isEqualTo(other);
        assertThat(refused).as("the disruptions refused some runs").isPositive();
    }

    /** A runner paging the scan keeps going from its cursor, without a gap, when the pipeline's home changes. */
    @Test
    void scanPagesStayGapFreeAcrossHomeChange() throws Exception {
        IntegrationTables.enrichment(mockMvc, TOX, "dial_usage_log", "event_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + SCAN + "\"," + Transforms.llm().proseOutputs("tox_score").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + TOX + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"filter\":\"total_tokens > 0\",\"enabled\":true}"))
                .andExpect(status().isCreated());
        try (Client primary = CLUSTER.client(PRIMARY)) {
            ingest(primary, 0, 6);
        }
        MultiReplicaClickHouseSupport.sleep(2_500);
        final String home = home(SCAN);

        final List<String> seen = new ArrayList<>();
        String cursor = null;
        boolean more = true;
        boolean moved = false;
        final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (more && System.nanoTime() < deadline) {
            final MockHttpServletResponse page = mockMvc.perform(runnerPost("/v1/pipelines/" + SCAN + "/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(cursor == null ? "{\"limit\":2}" : "{\"cursor\":\"" + cursor + "\",\"limit\":2}"))
                    .andReturn().getResponse();
            if (page.getStatus() == 503) {
                continue;
            }
            assertThat(page.getStatus()).isEqualTo(200);
            final String body = page.getContentAsString();
            seen.addAll(JsonPath.read(body, "$.rows[*].event_id"));
            cursor = JsonPath.read(body, "$.next_cursor");
            more = JsonPath.read(body, "$.has_more");
            if (!moved) {
                // After the first page: the home goes away, and two more rows arrive.
                CLUSTER.stop(home);
                try (Client primary = CLUSTER.client(PRIMARY)) {
                    ingest(primary, 6, 2);
                }
                MultiReplicaClickHouseSupport.sleep(2_500);
                moved = true;
                more = true;
            }
        }
        assertThat(home(SCAN)).isNotEqualTo(home);
        assertThat(seen).doesNotHaveDuplicates().hasSize(8);
        assertThat(new HashSet<>(seen)).isEqualTo(Set.of(eventId(0), eventId(1), eventId(2), eventId(3), eventId(4),
                eventId(5), eventId(6), eventId(7)));
    }

    /** A home that has not applied a create applies it in its database sync before the run reads the table. */
    @Test
    void homeThatMissedCreateSyncsTheDatabaseFirst() throws Exception {
        final String pipeline = "mr-late-reader";
        final String home = home(pipeline);
        CLUSTER.stop(home);
        IntegrationTables.source(mockMvc, "mr_late", "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}]", "[\"k\"]");
        CLUSTER.start(home);

        final long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        String landed = null;
        while (!home.equals(landed) && System.nanoTime() < deadline) {
            try (ReplicaRouter.PipelineRun run = router.openRun(pipeline, SyncPlan.of(List.of("mr_late"), List.of(), null))) {
                landed = run.replica();
                if (home.equals(landed)) {
                    assertThat(clickHouse.query("SELECT count() AS n FROM mr_late").getFirst().getLong("n")).isZero();
                }
            } catch (RuntimeException notYet) {
                MultiReplicaClickHouseSupport.sleep(1_000);
            }
        }
        assertThat(landed).isEqualTo(home);
    }

    /**
     * A refresh whose home cannot catch up within the sync bound, longer than the pipeline budget, answers
     * {@code 503}, not the budget's {@code 422}, records the failure and leaves its cursor where it was.
     */
    @Test
    void syncTimeoutUnderPipelineBudgetIsUnavailableNotBudgetRefusal() throws Exception {
        createAggregate();
        try (Client primary = CLUSTER.client(PRIMARY)) {
            ingest(primary, 0, 5);
        }
        MultiReplicaClickHouseSupport.sleep(2_500);
        assertThat(refresh().getStatus()).isEqualTo(200);
        final Object cursor = aggregateCursor();

        final String home = home(AGGREGATE);
        CLUSTER.execute(home, "SYSTEM STOP FETCHES");
        try (Client primary = CLUSTER.client(PRIMARY)) {
            ingest(primary, 5, 5);
        }
        final MockHttpServletResponse refused = refresh();
        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(refused.getContentAsString()).contains("clickhouse_unavailable");
        final String state = mockMvc.perform(get("/v1/pipelines/" + AGGREGATE)).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(state, "$.state.cursor_version")).isEqualTo(cursor);
        assertThat((Object) JsonPath.read(state, "$.state.last_error")).isNotNull();
    }

    /** Startup with every migration pending and a replica stopped is not held by the stopped replica. */
    @Test
    void startupWithPendingMigrationsAndStoppedReplicaCompletesAndTheReplicaCatchesUp() throws Exception {
        final String database = "adas_fresh";
        for (final String replica : MultiReplicaClickHouseSupport.REPLICAS) {
            CLUSTER.execute(replica, "CREATE DATABASE IF NOT EXISTS " + database + " ENGINE = Replicated('/clickhouse/databases/"
                    + database + "', '{shard}', '{replica}')");
        }
        try {
            CLUSTER.stop("chi-2");
            awaitInactive(database, "chi-2");
            final StandaloneStartup startup = new StandaloneStartup("mr_fresh");
            CLUSTER.registerStandalone(startup, database);
            final long started = System.nanoTime();
            try (ConfigurableApplicationContext context = startup.start()) {
                assertThat(context.isActive()).isTrue();
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMinutes(3));

            CLUSTER.start("chi-2");
            CLUSTER.awaitWritable();
            if (ClickHouseContainerSupport.VERSION.startsWith("25.")) {
                // 25.3 replays the payload table's original CREATE against its newer metadata and stalls; the
                // documented recovery re-joins the replica (docs/clickhouse-replication.md).
                MultiReplicaClickHouseSupport.sleep(15_000);
                CLUSTER.execute("chi-2", "DROP DATABASE " + database + " SYNC");
                CLUSTER.execute("chi-2", "CREATE DATABASE " + database + " ENGINE = Replicated('/clickhouse/databases/"
                        + database + "', '{shard}', '{replica}')");
            }
            final long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
            long tables = 0;
            while (System.nanoTime() < deadline) {
                try (Client chi2 = CLUSTER.client("chi-2")) {
                    tables = scalar(chi2, "SELECT count() AS n FROM system.tables WHERE database = '" + database
                            + "' AND name IN ('dial_usage_log', 'dial_usage_log_payload', 'flyway_schema_history')");
                }
                if (tables == 3) {
                    break;
                }
                MultiReplicaClickHouseSupport.sleep(1_000);
            }
            assertThat(tables).as("chi-2 applied the migrations from the database log").isEqualTo(3);
        } finally {
            CLUSTER.start("chi-2");
            CLUSTER.awaitWritable();
            for (final String replica : MultiReplicaClickHouseSupport.REPLICAS) {
                CLUSTER.execute(replica, "DROP DATABASE IF EXISTS " + database + " SYNC");
            }
        }
    }

    private static void awaitInactive(String database, String replica) {
        final long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (System.nanoTime() < deadline) {
            try (Client primary = CLUSTER.client(PRIMARY)) {
                if (Objects.equals(0L, scalar(primary, "SELECT toInt64(any(is_active)) AS n FROM system.clusters WHERE cluster = '"
                        + database + "' AND database_replica_name = '" + replica + "'"))) {
                    return;
                }
            } catch (Exception notYet) {
                // Retried.
            }
            MultiReplicaClickHouseSupport.sleep(500);
        }
        throw new IllegalStateException(replica + " did not turn inactive");
    }
}
