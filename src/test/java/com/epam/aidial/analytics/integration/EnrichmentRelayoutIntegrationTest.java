package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.table.relayout.EnrichmentRelayoutService;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStep;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The automatic relayout of an existing enrichment into the aligned layout (capability table-management, "Existing
 * eligible enrichments are relaid out automatically", and "The writers of a relaying-out enrichment are held
 * briefly"), against real ClickHouse and Postgres on the default Atomic database engine. A pre-alignment table is
 * built by recreating an API-created enrichment's ClickHouse table without the alignment, and clearing its catalog
 * partitioning, which is exactly the state an enrichment created by an earlier build is in.
 */
class EnrichmentRelayoutIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String UCI = "rl_ident";
    private static final String BAGGAGE = "rl_bag";
    private static final String TODAY = FIXTURE_DATE;
    private static final String YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();

    @Autowired
    private EnrichmentRelayoutService relayouts;

    @Autowired
    private RelayoutStateStore states;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private Duration settle;

    private long sliceBytes;

    @BeforeEach
    void setUp() {
        truncate();
        settle = (Duration) ReflectionTestUtils.getField(relayouts, "settle");
        sliceBytes = (long) ReflectionTestUtils.getField(relayouts, "sliceBytes");
        // The hold's settle is the pipeline budget's timeout; no write runs alongside these tests.
        ReflectionTestUtils.setField(relayouts, "settle", Duration.ZERO);
    }

    @AfterEach
    void tearDown() throws Exception {
        ReflectionTestUtils.setField(relayouts, "settle", settle);
        ReflectionTestUtils.setField(relayouts, "sliceBytes", sliceBytes);
        for (final String table : List.of(UCI, BAGGAGE)) {
            mockMvc.perform(delete("/v1/pipelines/" + table.replace('_', '-')));
            mockMvc.perform(delete("/v1/pipelines/" + table.replace('_', '-') + "-rollup"));
            mockMvc.perform(delete("/v1/tables/" + table + "_rollup"));
            mockMvc.perform(delete("/v1/tables/" + table));
            execute("DROP TABLE IF EXISTS " + copy(table));
        }
    }

    /** Creates {@code name} through the API, then rebuilds it as an earlier build laid it out: unaligned. */
    private void legacy(String name, String columnsJson, boolean adopted) throws Exception {
        IntegrationTables.enrichment(mockMvc, name, SOURCE, "event_id", columnsJson);
        String ddl = showCreateTable(name).replaceAll("\\s+", " ");
        if (!adopted) {
            ddl = ddl.replace("`request_time` DateTime64(3, 'UTC'), ", "").replaceAll(" TTL [^S]*", " ");
        }
        ddl = ddl.replace(" PARTITION BY toYYYYMMDD(request_time)", "")
                .replaceAll(" SETTINGS .*", " SETTINGS index_granularity = 8192");
        execute("DROP TABLE " + name);
        execute(ddl);
        jdbc.update("UPDATE table_definition SET partition_column = NULL, partition_granularity = NULL WHERE name = ?", name);
    }

    private String seedSource(String day, String time) {
        final String eventId = UUID.randomUUID().toString();
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id, core_span_id,"
                + " _ingested_at) VALUES (toUUID('" + eventId + "'), 'p', 'd', '" + day + " " + time + "', 't-" + day
                + "-" + time + "', 's', '2020-01-01 00:00:01.000')");
        return eventId;
    }

    /** Seeds four source rows over two days and a legacy UCI row for each, with distinct {@code _updated_at}s. */
    private void seedUci() throws Exception {
        legacy(UCI, "[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\",\"nullable\":true}]", false);
        int i = 0;
        for (final String day : List.of(TODAY, YESTERDAY)) {
            for (final String time : List.of("10:00:00.000", "11:00:00.000")) {
                final String eventId = seedSource(day, time);
                execute("INSERT INTO " + UCI + " (event_id, session, _updated_at) VALUES (toUUID('" + eventId + "'), "
                        + "'s" + (i % 2) + "', '2026-01-01 00:00:0" + i++ + ".000')");
            }
        }
    }

    private static String copy(String table) {
        return EnrichmentRelayoutService.copyName(table);
    }

    private boolean exists(String table) {
        return !"0".equals(queryScalar("SELECT toString(count()) AS c FROM system.tables WHERE database = currentDatabase() "
                + "AND name = '" + table + "'", "c"));
    }

    /**
     * Runs the relayout on a thread of its own and interrupts it while it holds the writers, as a shutdown does: the
     * state is left at {@link RelayoutStep#CAUGHT_UP}, the hold released and the copy kept.
     */
    private RelayoutStateStore.State interruptInsideTheHold() throws Exception {
        ReflectionTestUtils.setField(relayouts, "settle", Duration.ofSeconds(30));
        final Thread job = new Thread(relayouts::runAll, "relayout-under-test");
        job.start();
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!states.find(UCI).map(RelayoutStateStore.State::hold).orElse(false)) {
            assertThat(System.nanoTime()).as("the relayout takes the hold").isLessThan(deadline);
            Thread.sleep(50);
        }
        job.interrupt();
        job.join(Duration.ofSeconds(20).toMillis());
        assertThat(job.isAlive()).isFalse();
        ReflectionTestUtils.setField(relayouts, "settle", Duration.ZERO);
        final RelayoutStateStore.State state = states.find(UCI).orElseThrow();
        assertThat(state.step()).isEqualTo(RelayoutStep.CAUGHT_UP);
        assertThat(state.hold()).isFalse();
        assertThat(state.error()).isNull();
        assertThat(exists(copy(UCI))).isTrue();
        assertThat(showCreateTable(UCI)).doesNotContain("PARTITION BY");
        return state;
    }

    private String rows(String sql) {
        return queryText(sql).lines().skip(2).reduce("", (a, b) -> a + b + "\n");
    }

    private RelayoutStep step(String table) {
        return states.find(table).map(RelayoutStateStore.State::step).orElse(null);
    }

    @Test
    void existingEnrichmentIsCopiedIntoTheAlignedLayoutWithItsVersionsKept() throws Exception {
        seedUci();
        final String before = rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id");
        // A rollup grouping by the session: the aligned copy is created with its lookup index.
        IntegrationTables.upsertSource(mockMvc, UCI + "_rollup",
                "[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\"},"
                        + "{\"source_name\":\"events\",\"name\":\"events\",\"type\":\"long\"}]", "[\"session\"]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rl-ident-rollup\",\"kind\":\"aggregate\",\"target\":\"" + UCI + "_rollup\","
                                + "\"inputs\":[\"" + SOURCE + "\"],\"filter\":\"length(\\\"" + UCI + ".session\\\") > 0\","
                                + "\"group_by\":[{\"column\":\"" + UCI + ".session\",\"as\":\"session\"}],"
                                + "\"measures\":[{\"name\":\"events\",\"fn\":\"count\"}],"
                                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}"))
                .andExpect(status().isCreated());

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(showCreateTable(UCI)).contains("PARTITION BY toYYYYMMDD(request_time)")
                .contains("TTL toDateTime(request_time) + toIntervalYear(1)")
                .contains("INDEX idx_session session TYPE bloom_filter(0.001) GRANULARITY 1")
                .doesNotContain("materialize_ttl_recalculate_only");
        assertThat(rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id")).isEqualTo(before);
        assertThat(rows("SELECT count() FROM " + UCI + " AS e INNER JOIN dial_usage_log AS s ON e.event_id = s.event_id "
                + "AND e.request_time = s.request_time")).isEqualTo("4\n");
        assertThat(queryScalar("SELECT toString(count()) AS c FROM system.tables WHERE database = currentDatabase() "
                + "AND name = '" + copy(UCI) + "'", "c")).isEqualTo("0");
        mockMvc.perform(get("/v1/tables/" + UCI))
                .andExpect(jsonPath("$.layout").value("aligned"))
                .andExpect(jsonPath("$.partition_by.column").value("request_time"));
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"" + SOURCE + "\",\"mode\":\"row\",\"filter\":{\"op\":\"eq\",\"args\":["
                                + "{\"type\":\"field\",\"name\":\"" + UCI + ".session\"},{\"type\":\"value\",\"value_type\":"
                                + "\"string\",\"value\":\"s1\"}]},\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2));
    }

    /**
     * One grain key per slice: four keys are four bounded slices and an empty open one, and the copy is the same as one
     * statement would make it.
     */
    @Test
    void historyIsCopiedOneSliceOfKeysPerStatement() throws Exception {
        seedUci();
        final String before = rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id");
        ReflectionTestUtils.setField(relayouts, "sliceBytes", 1L);

        try (LogCapture log = LogCapture.of(EnrichmentRelayoutService.class)) {
            relayouts.runAll();
            assertThat(log.messagesAt(Level.INFO)).anyMatch(message -> message.contains("in 5 slice(s)"));
        }

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id")).isEqualTo(before);
        assertThat(rows("SELECT count() FROM " + UCI + " AS e INNER JOIN dial_usage_log AS s ON e.event_id = s.event_id "
                + "AND e.request_time = s.request_time")).isEqualTo("4\n");
    }

    /**
     * A restart during the history copy resumes after the last slice it completed: the keys up to it are left as they
     * are, and every key above it is copied.
     */
    @Test
    void historyCopyResumedMidwayCopiesTheKeysAboveTheLastCompletedSlice() throws Exception {
        seedUci();
        final String before = rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id");
        final RelayoutStateStore.State caughtUp = interruptInsideTheHold();
        final String second = queryScalar("SELECT toString(event_id) AS k FROM " + UCI + " ORDER BY event_id LIMIT 1 OFFSET 1", "k");
        // The copy as a run stopped after its second slice left it: the two lowest keys only.
        execute("ALTER TABLE " + copy(UCI) + " DELETE WHERE event_id > toUUID('" + second + "') SETTINGS mutations_sync = 2");
        assertThat(rows("SELECT count() FROM " + copy(UCI))).isEqualTo("2\n");
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.STARTED, caughtUp.copyTable(), second,
                caughtUp.copyMark(), null, false, null));
        ReflectionTestUtils.setField(relayouts, "sliceBytes", 1L);

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id")).isEqualTo(before);
    }

    /** {@code usage_request_baggage}'s shape: its own {@code request_time}, and a catalog TTL over it. */
    @Test
    void ownCopyOfThePartitionColumnIsAdoptedAndItsTtlKept() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + BAGGAGE + "\",\"type\":\"enrichment\",\"source_table\":\"" + SOURCE + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/" + BAGGAGE + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"event_id\",\"ttl\":{\"column\":\"request_time\",\"interval\":1,\"unit\":\"year\"},"
                                + "\"columns\":[{\"source_name\":\"baggage\",\"name\":\"baggage\",\"type\":\"string\",\"nullable\":true},"
                                + "{\"source_name\":\"request_time\",\"name\":\"request_time\",\"type\":\"timestamp\",\"nullable\":false,"
                                + "\"description\":\"drives the TTL\"}]}"))
                .andExpect(status().isOk());
        String ddl = showCreateTable(BAGGAGE).replaceAll("\\s+", " ").replace(" PARTITION BY toYYYYMMDD(request_time)", "");
        execute("DROP TABLE " + BAGGAGE);
        execute(ddl);
        jdbc.update("UPDATE table_definition SET partition_column = NULL, partition_granularity = NULL WHERE name = ?", BAGGAGE);
        final String eventId = seedSource(TODAY, "10:00:00.000");
        execute("INSERT INTO " + BAGGAGE + " (event_id, baggage, request_time) SELECT event_id, 'b=1', request_time FROM "
                + "dial_usage_log");

        relayouts.runAll();

        assertThat(step(BAGGAGE)).isEqualTo(RelayoutStep.DONE);
        final String aligned = showCreateTable(BAGGAGE);
        assertThat(aligned).contains("PARTITION BY toYYYYMMDD(request_time)").contains("TTL toDateTime(request_time) + toIntervalYear(1)");
        assertThat(aligned.split("`request_time`", -1)).hasSize(2);
        mockMvc.perform(get("/v1/tables/" + BAGGAGE))
                .andExpect(jsonPath("$.layout").value("aligned"))
                .andExpect(jsonPath("$.ttl.column").value("request_time"))
                .andExpect(jsonPath("$.columns[?(@.source_name=='request_time')].description").value(hasItem("drives the TTL")));
        assertThat(queryScalar("SELECT baggage AS v FROM " + BAGGAGE + " WHERE event_id = toUUID('" + eventId + "')", "v"))
                .isEqualTo("b=1");
    }

    @Test
    void tableWrittenByAnLlmPipelineIsLeftAloneAndReported() throws Exception {
        seedUci();
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rl-ident\"," + Transforms.llm().output("session", "A session.").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + UCI + "\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":false}"))
                .andExpect(status().isCreated());

        try (LogCapture log = LogCapture.of(EnrichmentRelayoutService.class)) {
            relayouts.runAll();
            assertThat(log.messagesAt(Level.WARN)).anyMatch(message -> message.contains(UCI) && message.contains("llm"));
        }

        assertThat(step(UCI)).isEqualTo(RelayoutStep.UNSUPPORTED);
        assertThat(showCreateTable(UCI)).doesNotContain("PARTITION BY");
        mockMvc.perform(get("/v1/tables/" + UCI)).andExpect(jsonPath("$.layout").value("unsupported"));
    }

    @Test
    void anotherInstanceHoldingTheLockIsLeftToIt() throws Exception {
        seedUci();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")) {
            lock.setString(1, "enrichment_relayout:" + UCI);
            lock.execute();

            relayouts.runAll();
            assertThat(step(UCI)).isNull();
            assertThat(showCreateTable(UCI)).doesNotContain("PARTITION BY");
            // A session lock outlives close(): the pool keeps the connection, and the lock with it.
            try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
                unlock.setString(1, "enrichment_relayout:" + UCI);
                unlock.execute();
            }
        }

        relayouts.runAll();
        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
    }

    @Test
    void heldTableRefusesWritesWith503AndStillServesReads() throws Exception {
        seedUci();
        states.start(UCI, RelayoutStep.CAUGHT_UP, copy(UCI), null);
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.CAUGHT_UP, copy(UCI), null, 0L, 0L, true, null));

        mockMvc.perform(post("/v1/tables/" + UCI + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + UUID.randomUUID() + "\",\"session\":\"x\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("table_relayout_in_progress"));
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"" + SOURCE + "\",\"mode\":\"row\",\"select\":["
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"" + UCI + ".session\"}}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/tables/" + UCI)).andExpect(jsonPath("$.layout").value("migrating"));
    }

    /**
     * A crash after the exchange but before the catalog update: the next start finds the aligned table already in
     * place, does not exchange it back, and finishes.
     */
    @Test
    void relayoutInterruptedAfterTheExchangeResumesWithoutUndoingIt() throws Exception {
        seedUci();
        relayouts.runAll();
        final String aligned = rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id");
        // Rewind: the catalog not yet updated, the previous table still there, the state at the exchange.
        jdbc.update("UPDATE table_definition SET partition_column = NULL, partition_granularity = NULL WHERE name = ?", UCI);
        execute("CREATE TABLE " + copy(UCI) + " AS " + UCI);
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.HELD, copy(UCI), null, 0L, 0L, true, null));

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(showCreateTable(UCI)).contains("PARTITION BY toYYYYMMDD(request_time)");
        assertThat(rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id")).isEqualTo(aligned);
        mockMvc.perform(get("/v1/tables/" + UCI)).andExpect(jsonPath("$.layout").value("aligned"));
    }

    /**
     * Resumed at the exchange after the earlier attempt released the hold: the writers are held again, and settled,
     * before the exchange and the catalog update — a write that read the catalog unaligned would otherwise land in
     * the aligned table with no alignment value after the last repair.
     */
    @Test
    void relayoutResumedAtTheExchangeHoldsTheWritersAgain() throws Exception {
        seedUci();
        relayouts.runAll();
        jdbc.update("UPDATE table_definition SET partition_column = NULL, partition_granularity = NULL WHERE name = ?", UCI);
        execute("CREATE TABLE " + copy(UCI) + " AS " + UCI);
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.HELD, copy(UCI), null, 0L, 0L, false, null));

        ReflectionTestUtils.setField(relayouts, "settle", Duration.ofSeconds(30));
        final Thread job = new Thread(relayouts::runAll, "relayout-under-test");
        job.start();
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!states.find(UCI).map(RelayoutStateStore.State::hold).orElse(false)) {
            assertThat(System.nanoTime()).as("the resumed relayout takes the hold").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(step(UCI)).isEqualTo(RelayoutStep.HELD);
        assertThat(mockMvc.perform(get("/v1/tables/" + UCI)).andReturn().getResponse().getContentAsString())
                .doesNotContain("\"layout\":\"aligned\"");
        job.interrupt();
        job.join(Duration.ofSeconds(20).toMillis());
        assertThat(job.isAlive()).isFalse();
        ReflectionTestUtils.setField(relayouts, "settle", Duration.ZERO);
        assertThat(states.find(UCI).orElseThrow().hold()).isFalse();

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        mockMvc.perform(get("/v1/tables/" + UCI)).andExpect(jsonPath("$.layout").value("aligned"));
    }

    /** A crash before the copy finished: the next start rebuilds the copy rather than trusting what it holds. */
    @Test
    void relayoutInterruptedDuringTheCopyStartsTheCopyAgain() throws Exception {
        seedUci();
        final String before = rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id");
        states.start(UCI, RelayoutStep.STARTED, copy(UCI), null);
        execute("CREATE TABLE " + copy(UCI) + " (junk String) ENGINE = MergeTree ORDER BY junk");

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id")).isEqualTo(before);
    }

    @Test
    void failureBeforeTheExchangeKeepsTheOldTableAndReleasesTheHold() throws Exception {
        seedUci();
        // The state says the copy is done and the writers held, but the copy is gone: the exchange cannot run.
        states.start(UCI, RelayoutStep.HELD, copy(UCI), null);
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.HELD, copy(UCI), null, 0L, 0L, true, null));

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.FAILED);
        assertThat(states.find(UCI).orElseThrow().hold()).isFalse();
        assertThat(states.find(UCI).orElseThrow().error()).isNotBlank();
        assertThat(showCreateTable(UCI)).doesNotContain("PARTITION BY");
        assertThat(rows("SELECT count() FROM " + UCI)).isEqualTo("4\n");

        // The next start relays it out again, from the beginning.
        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(showCreateTable(UCI)).contains("PARTITION BY toYYYYMMDD(request_time)");
        assertThat(rows("SELECT count() FROM " + UCI)).isEqualTo("4\n");
    }

    @Test
    void interruptionInsideTheHoldKeepsTheStateAndTheNextRunResumesIt() throws Exception {
        seedUci();
        final String before = rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id");

        interruptInsideTheHold();
        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(showCreateTable(UCI)).contains("PARTITION BY toYYYYMMDD(request_time)");
        assertThat(rows("SELECT event_id, session, _updated_at FROM " + UCI + " ORDER BY event_id")).isEqualTo(before);
        assertThat(exists(copy(UCI))).isFalse();
    }

    /**
     * A write stamped below the catch-up mark — it started before the write that set the mark and committed after the
     * mark was read — is copied too, and the rows already copied in that lookback are not copied twice.
     */
    @Test
    void catchUpCopiesWriteStampedBelowItsMark() throws Exception {
        seedUci();
        final RelayoutStateStore.State caughtUp = interruptInsideTheHold();
        final String eventId = seedSource(TODAY, "13:00:00.000");
        execute("INSERT INTO " + UCI + " (event_id, session, _updated_at) VALUES (toUUID('" + eventId + "'), 'slow', "
                + "fromUnixTimestamp64Milli(toInt64(" + (caughtUp.copyMark() - 1000) + ")))");
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.COPIED, caughtUp.copyTable(), caughtUp.copiedThrough(),
                caughtUp.copyMark(), null, false, null));

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(rows("SELECT count() FROM " + UCI)).isEqualTo("5\n");
        assertThat(queryScalar("SELECT session AS v FROM " + UCI + " WHERE event_id = toUUID('" + eventId + "')", "v"))
                .isEqualTo("slow");
    }

    /** Resumed after the history copy: what was written meanwhile, in the mark's own millisecond included, is copied. */
    @Test
    void relayoutResumedAfterTheHistoryCopyCopiesWhatWasWrittenMeanwhile() throws Exception {
        seedUci();
        final RelayoutStateStore.State caughtUp = interruptInsideTheHold();
        final String eventId = seedSource(TODAY, "13:00:00.000");
        execute("INSERT INTO " + UCI + " (event_id, session, _updated_at) VALUES (toUUID('" + eventId + "'), 'during', "
                + "fromUnixTimestamp64Milli(toInt64(" + caughtUp.copyMark() + ")))");
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.COPIED, caughtUp.copyTable(), caughtUp.copiedThrough(),
                caughtUp.copyMark(), null, false, null));

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(rows("SELECT count() FROM " + UCI)).isEqualTo("5\n");
        assertThat(queryScalar("SELECT session AS v FROM " + UCI + " WHERE event_id = toUUID('" + eventId + "')", "v"))
                .isEqualTo("during");
    }

    @Test
    void holdWhoseInstanceDiedNoLongerRefusesWrites() throws Exception {
        seedUci();
        states.start(UCI, RelayoutStep.CAUGHT_UP, copy(UCI), null);
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.CAUGHT_UP, copy(UCI), null, 0L, 0L, true, null));
        jdbc.update("UPDATE enrichment_relayout SET updated_at = now() - interval '11 minutes' WHERE table_name = ?", UCI);

        mockMvc.perform(post("/v1/tables/" + UCI + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + UUID.randomUUID() + "\",\"session\":\"x\"}]}"))
                .andExpect(status().is2xxSuccessful());
    }

    /** A row whose source row is gone is not carried over, by design: a WARN, not a loss. */
    @Test
    void rowWithNoSourceRowIsNotReportedAsLost() throws Exception {
        seedUci();
        execute("INSERT INTO " + UCI + " (event_id, session, _updated_at) VALUES (toUUID('" + UUID.randomUUID() + "'), "
                + "'orphan', '2026-01-01 00:00:09.000')");

        try (LogCapture log = LogCapture.of(EnrichmentRelayoutService.class)) {
            relayouts.runAll();
            assertThat(log.messagesAt(Level.ERROR)).isEmpty();
            assertThat(log.messagesAt(Level.WARN)).anyMatch(message -> message.contains("1 row(s)")
                    && message.contains("no source row"));
        }

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(exists(copy(UCI))).isFalse();
        assertThat(rows("SELECT count() FROM " + UCI)).isEqualTo("4\n");
    }

    /** A catch-up after a re-derivation spans every day it re-derived: more than an insert's default 100 partitions. */
    @Test
    void catchUpSpanningOverOneHundredPartitionsIsCopied() throws Exception {
        seedUci();
        relayouts.runAll();
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id, core_span_id, "
                + "_ingested_at) SELECT generateUUIDv4(), 'p', 'd', toStartOfDay(now64(3)) - toIntervalDay(number + 2), "
                + "concat('bulk-', toString(number)), 's', '2020-01-01 00:00:01.000' FROM numbers(101) "
                + "SETTINGS max_partitions_per_insert_block = 0");
        // Rewind to just after the catalog update, the previous table holding a re-derivation of all 101 days.
        execute("CREATE TABLE " + copy(UCI) + " AS " + UCI);
        execute("INSERT INTO " + copy(UCI) + " (event_id, request_time, session, _updated_at) SELECT event_id, "
                + "request_time, 'late', '2026-06-01 00:00:00.000' FROM dial_usage_log WHERE startsWith(trace_id, 'bulk-') "
                + "SETTINGS max_partitions_per_insert_block = 0");
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.RELEASED, copy(UCI), null, 0L, 1767225600000L,
                false, null));

        relayouts.runAll();

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(rows("SELECT count() FROM " + UCI + " WHERE session = 'late'")).isEqualTo("101\n");
        assertThat(exists(copy(UCI))).isFalse();
    }

    /**
     * Both deploy-skew losses: a row the previous build wrote into the old table after the last catch-up, and a row
     * it wrote into the new one with no alignment value. Both survive, each with a WARN.
     */
    @Test
    void writesFromThePreviousBuildDuringTheRolloutSurvive() throws Exception {
        seedUci();
        relayouts.runAll();
        final String late = seedSource(TODAY, "12:00:00.000");
        final String unstamped = seedSource(YESTERDAY, "12:00:00.000");
        // Rewind to just after the catalog update: the previous table still there holding one late row, and no TTL
        // in force yet on either table — the relayout applies it once the repairs have run.
        execute("ALTER TABLE " + UCI + " REMOVE TTL");
        execute("CREATE TABLE " + copy(UCI) + " AS " + UCI);
        execute("INSERT INTO " + copy(UCI) + " (event_id, request_time, session, _updated_at) VALUES (toUUID('" + late
                + "'), '1970-01-01 00:00:00.000', 'late', '2026-06-01 00:00:00.000')");
        execute("INSERT INTO " + UCI + " (event_id, session, _updated_at) VALUES (toUUID('" + unstamped + "'), "
                + "'unstamped', '2026-06-01 00:00:00.000')");
        states.save(new RelayoutStateStore.State(UCI, RelayoutStep.RELEASED, copy(UCI), null, 0L,
                1767225600000L, false, null));

        try (LogCapture log = LogCapture.of(EnrichmentRelayoutService.class)) {
            relayouts.runAll();
            assertThat(log.messagesAt(Level.WARN)).anyMatch(message -> message.contains("previous table"));
            assertThat(log.messagesAt(Level.WARN)).anyMatch(message -> message.contains("epoch partition"));
        }

        assertThat(step(UCI)).isEqualTo(RelayoutStep.DONE);
        assertThat(rows("SELECT e.session FROM " + UCI + " AS e INNER JOIN dial_usage_log AS s ON e.event_id = s.event_id "
                + "AND e.request_time = s.request_time WHERE e.session IN ('late', 'unstamped') ORDER BY e.session"))
                .isEqualTo("late\nunstamped\n");
        assertThat(queryScalar("SELECT toString(count()) AS c FROM system.parts WHERE active AND database = "
                + "currentDatabase() AND table = '" + UCI + "' AND partition_id = '19700101'", "c")).isEqualTo("0");
        assertThat(showCreateTable(UCI)).contains("TTL toDateTime(request_time) + toIntervalYear(1)")
                .contains("ttl_only_drop_parts = 1");
    }
}
