package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.service.enrichment.ScanService;
import com.jayway.jsonpath.JsonPath;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the governed incremental scan ({@code POST /v1/pipelines/{name}/scan}) against real
 * PostgreSQL + ClickHouse. Seeds {@code dial_usage_log} with controlled {@code event_id}/{@code
 * _ingested_at} tuples and verifies keyset paging returns each row exactly once, that the second seeded
 * source ({@code rate_analytics}) scans on its own catalog-declared order, an empty page echoes
 * the cursor with {@code has_more=false}, a tampered cursor is rejected (400), an unknown pipeline is 404,
 * and a disabled-but-existing pipeline still scans. Also covers the generalized scan plumbing: a bounded
 * {@code (from, to]} window returns only its range and adjacent windows do not overlap at their shared
 * boundary, an inverted window is rejected, and a source with no catalog scan metadata is rejected with
 * a catalog-driven message. Safety-lag is set to 0 so the past-dated rows are all visible; the property set
 * is the shared one every enrichment integration class declares (see
 * {@link AbstractClickHouseIntegrationTest#SCAN_SAFETY_LAG_OFF}), so they share one Spring context.
 * Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class EnrichmentScanIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "scan_tox";

    @Autowired
    private TransitionLog transitions;

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
    }

    /** Inserts a dial_usage_log row with a controlled event_id and _ingested_at (past-dated, below the lag). */
    private void seedRow(String eventId, String ingestedAt, long totalTokens, String spanId) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, _ingested_at, total_tokens) VALUES ("
                + "toUUID('" + eventId + "'),'p','d', now64(3),'t','" + spanId + "','" + ingestedAt + "',"
                + totalTokens + ")");
    }

    private String createPipeline(boolean enabled) throws Exception {
        String body = "{\"name\":\"scan-pipeline\","
                + Transforms.llm().proseOutputs("tox_score").member() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                + "\"filter\":\"total_tokens > 0\","
                + "\"enabled\":" + enabled + "}";
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.name");
    }

    /**
     * One outcome line per page of rows, with no row value and no cursor; and an empty page only when it follows
     * a page of rows, so a caught-up pipeline polling on a short cadence logs one line rather than one per poll.
     */
    @Test
    void scanLogsItsOutcomeAndRepeatedEmptyPagesOnce() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", 100, "s1");
        String id = createPipeline(true);
        // The state is per replica and outlives a test's truncate, which does not go through the delete path.
        transitions.forget(id);

        try (LogCapture log = LogCapture.of(ScanService.class)) {
            String page = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(page, "$.next_cursor");
            for (int poll = 0; poll < 3; poll++) {
                mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cursor\":\"" + cursor + "\",\"limit\":10}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.rows.length()").value(0));
            }

            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.startsWith("scan pipeline=")).satisfiesExactly(
                    line -> assertThat(line).contains("pipeline=" + id + " gen=1 rows=1 has_more=false")
                            .contains("query_id=adas-" + id + "-scan-")
                            .doesNotContain("11111111-1111-1111-1111-111111111111")
                            .doesNotContain(cursor),
                    line -> assertThat(line).contains("rows=0").contains("lag_s=-"));
        }
    }

    @Test
    void keysetPagingReturnsEachRowExactlyOnce() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", 100, "s1");
        seedRow("22222222-2222-2222-2222-222222222222", "2020-01-01 00:00:02.000", 200, "s2");
        seedRow("33333333-3333-3333-3333-333333333333", "2020-01-01 00:00:03.000", 300, "s3");
        String id = createPipeline(true);

        String page1 = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.next_cursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String cursor = JsonPath.read(page1, "$.next_cursor");
        String page2 = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":\"" + cursor + "\",\"limit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.has_more").value(false))
                .andReturn().getResponse().getContentAsString();

        List<String> idsPage1 = JsonPath.read(page1, "$.rows[*].event_id");
        List<String> idsPage2 = JsonPath.read(page2, "$.rows[*].event_id");
        Set<String> seen = new HashSet<>(idsPage1);
        seen.addAll(idsPage2);
        assertThat(seen).hasSize(3);

        // A further scan past the last row returns an empty page with the cursor unchanged.
        String lastCursor = JsonPath.read(page2, "$.next_cursor");
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":\"" + lastCursor + "\",\"limit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").value(lastCursor));
    }

    @Test
    void scanProjectsOnlySourceColumnsNoEnrichmentJoin() throws Exception {
        seedRow("44444444-4444-4444-4444-444444444444", "2020-01-01 00:00:01.000", 100, "s1");
        String id = createPipeline(true);
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].event_id").isNotEmpty())
                .andExpect(jsonPath("$.rows[0].total_tokens").value(100))
                // the enrichment's own column is never joined into a scan
                .andExpect(jsonPath("$.rows[0]['scan_tox.tox_score']").doesNotExist())
                .andExpect(jsonPath("$.rows[0].tox_score").doesNotExist());
    }

    @Test
    void disabledButExistingPipelineStillScans() throws Exception {
        seedRow("55555555-5555-5555-5555-555555555555", "2020-01-01 00:00:01.000", 100, "s1");
        String id = createPipeline(false);
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1));
    }

    @Test
    void tamperedCursorIsRejected() throws Exception {
        String id = createPipeline(true);
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":\"bogus.signature\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
    }

    @Test
    void unknownPipelineIsNotFound() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/no-such-pipeline/scan")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_pipeline"));
    }

    @Test
    void boundedWindowReturnsOnlyItsRange() throws Exception {
        seedRow("aaaaaaaa-0000-0000-0000-000000000001", "2020-01-01 00:00:01.000", 100, "s1");
        seedRow("aaaaaaaa-0000-0000-0000-000000000002", "2020-01-01 00:00:02.000", 200, "s2");
        seedRow("aaaaaaaa-0000-0000-0000-000000000003", "2020-01-01 00:00:03.000", 300, "s3");
        String id = createPipeline(true);

        // (00:00:01, 00:00:03] excludes the first row and includes the last two.
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2020-01-01T00:00:01Z\",\"to\":\"2020-01-01T00:00:03Z\","
                                + "\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(200))
                .andExpect(jsonPath("$.rows[1].total_tokens").value(300));
    }

    @Test
    void adjacentWindowsDoNotOverlapAtTheSharedBoundary() throws Exception {
        seedRow("bbbbbbbb-0000-0000-0000-000000000001", "2020-01-01 00:00:01.000", 100, "s1");
        seedRow("bbbbbbbb-0000-0000-0000-000000000002", "2020-01-01 00:00:02.000", 200, "s2");
        String id = createPipeline(true);

        // The row at exactly the shared boundary belongs to the first window only.
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"2020-01-01T00:00:01Z\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(100));
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2020-01-01T00:00:01Z\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(200));
    }

    @Test
    void rateAnalyticsIsScannableOnItsOwnCatalogDeclaredOrder() throws Exception {
        // The second seeded source: rate_analytics declares (_ingested_at, event_id) like dial_usage_log,
        // and its event_id is a table-side DEFAULT the ingest path never writes — so these rows omit it,
        // exactly as ingest does. Keying an enrichment on it also exercises the catalog resolution of the
        // grain key's type, which only works because event_id is a registered column of the source.
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics (request_time, _ingested_at, project_id, trace_id, "
                + "core_span_id, deployment, user_hash, chat_id, response_id, rate, comment) VALUES "
                + "('2026-01-01 00:00:00.000','2020-01-01 00:00:01.000','p','t1','s1','d','u','c','resp_1',1,NULL),"
                + "('2026-01-01 00:00:01.000','2020-01-01 00:00:02.000','p','t2','s2','d','u','c','resp_2',1,NULL),"
                + "('2026-01-01 00:00:02.000','2020-01-01 00:00:03.000','p','t3','s3','d','u','c','resp_3',-1,NULL)");
        IntegrationTables.enrichment(mockMvc, "rate_sent", "rate_analytics", "event_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        try {
            String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"rate-scan-pipeline\","
                                    + Transforms.llm().proseOutputs("tox_score").member() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"rate_sent\",\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"filter\":\"rate > 0\","
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(content, "$.name");

            // Keyset paging over (_ingested_at, event_id): the filter drops the -1 row, the remaining two
            // come back one per page in ingest order, each carrying the derived grain key.
            String page1 = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"limit\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(1))
                    .andExpect(jsonPath("$.rows[0].event_id").isNotEmpty())
                    .andExpect(jsonPath("$.has_more").value(true))
                    .andReturn().getResponse().getContentAsString();

            String page2 = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cursor\":\"" + JsonPath.read(page1, "$.next_cursor") + "\",\"limit\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(1))
                    .andExpect(jsonPath("$.has_more").value(false))
                    .andReturn().getResponse().getContentAsString();

            String firstId = JsonPath.read(page1, "$.rows[0].event_id");
            String secondId = JsonPath.read(page2, "$.rows[0].event_id");
            assertThat(firstId).isNotBlank().isNotEqualTo(secondId);
        } finally {
            mockMvc.perform(delete("/v1/tables/rate_sent"));
            execute("TRUNCATE TABLE rate_analytics");
        }
    }

    /**
     * A pipeline that declares its own read source scans THAT table, not the one its target enrichment hangs
     * off — the worked example of the decoupling: an enrichment attached to {@code dial_usage_log} at the
     * conversation grain, whose values are derived from {@code rate_analytics}. The cursor half matters
     * because a cursor is bound to the source entity it was issued for: repointing the pipeline's read source
     * must invalidate it loudly rather than let a keyset seek run against the wrong table.
     */
    @Test
    void pipelineScansTheSourceItDeclaresAndRepointingItInvalidatesTheCursor() throws Exception {
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics (request_time, _ingested_at, project_id, trace_id, "
                + "core_span_id, deployment, user_hash, chat_id, response_id, rate, comment) VALUES "
                + "('2026-01-01 00:00:00.000','2020-01-01 00:00:01.000','p','t1','s1','d','u','chat-a','r1',1,NULL),"
                + "('2026-01-01 00:00:01.000','2020-01-01 00:00:02.000','p','t2','s2','d','u','chat-b','r2',1,NULL)");
        // Keyed on chat_id, a column BOTH tables carry — which is what makes the two compatible.
        IntegrationTables.enrichment(mockMvc, "scan_conv", SOURCE, "chat_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
        try {
            String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"scan-declared-pipeline\","
                                    + Transforms.llm().proseOutputs("tox_score").member() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"scan_conv\",\"inputs\":[\"rate_analytics\"],"
                                    + "\"trigger\":{\"kind\":\"schedule\"},\"filter\":\"rate > 0\","
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.name");

            // The page carries rate_analytics rows — chat_id as the grain, rate as the filter column — and
            // none of dial_usage_log's, even though that is the table the enrichment hangs off.
            String page = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(1))
                    .andExpect(jsonPath("$.rows[0].chat_id").value("chat-a"))
                    .andExpect(jsonPath("$.rows[0].total_tokens").doesNotExist())
                    .andExpect(jsonPath("$.has_more").value(true))
                    .andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(page, "$.next_cursor");

            // Repoint the pipeline onto its target's own source by DECLARING it. Omitting `inputs` would
            // no longer do this: a declaration patch of a pipeline that declares a read source must restate
            // it, because a silent repoint changes what the pipeline reads without the patch saying so.
            // Stating it is how a caller asks for the repoint on purpose — which is what this test is about.
            // The filter has to move with it, since source-scoping follows the read source.
            mockMvc.perform(patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"scan-declared-pipeline\","
                                    + Transforms.llm().proseOutputs("tox_score").member() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"scan_conv\",\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"inputs\":[\"" + SOURCE + "\"],"
                                    + "\"filter\":\"total_tokens > 0\","
                                    + "\"enabled\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inputs[0]").value(SOURCE));

            // The in-flight cursor was issued against rate_analytics: rejected, not silently re-seeked.
            mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cursor\":\"" + cursor + "\",\"limit\":10}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_cursor"));
        } finally {
            mockMvc.perform(delete("/v1/tables/scan_conv"));
            execute("TRUNCATE TABLE rate_analytics");
        }
    }

    @Test
    void invertedWindowIsRejected() throws Exception {
        String id = createPipeline(true);
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2020-01-02T00:00:00Z\",\"to\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void sourceWithoutScanMetadataIsRejected() throws Exception {
        // A source created through the table API declares no scan metadata unless it asks to, so it is not
        // a scan source: the scan fails with a catalog-driven message instead of a hardcoded phase note.
        IntegrationTables.source(mockMvc, "scan_unconfigured",
                "[{\"source_name\":\"id\",\"name\":\"id\",\"type\":\"string\",\"nullable\":false},"
                        + "{\"source_name\":\"ts\",\"name\":\"ts\",\"type\":\"timestamp\",\"nullable\":false}]",
                "[\"id\"]");
        IntegrationTables.enrichment(mockMvc, "scan_unconfigured_enr", "scan_unconfigured", "id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"scan-unconfigured-pipeline\","
                                + Transforms.llm().proseOutputs("label").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"scan_unconfigured_enr\","
                                + "\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(content, "$.name");

        try {
            mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"))
                    .andExpect(jsonPath("$.message").value(containsString("has no scan metadata configured")));
        } finally {
            mockMvc.perform(delete("/v1/tables/scan_unconfigured_enr"));
            mockMvc.perform(delete("/v1/tables/scan_unconfigured"));
        }
    }

    /**
     * The counterpart: a source that <em>declares</em> its scan metadata at schema-define is scannable with
     * no migration and no deploy in between — the gap that previously left every caller-created source
     * permanently unscannable. Drives the endpoints directly, since the shared helper declares no pair:
     * declaring one is a claim about the data, so a fixture makes it explicitly or not at all.
     */
    @Test
    void sourceDeclaringScanMetadataIsScannable() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"scan_declared\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/scan_declared/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"source_name\":\"id\",\"name\":\"id\",\"type\":\"string\","
                                + "\"nullable\":false},{\"source_name\":\"ts\",\"name\":\"ts\","
                                + "\"type\":\"timestamp\",\"nullable\":false}],\"ordering_key\":[\"id\"],"
                                + "\"identity_column\":\"id\",\"version_column\":\"ts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity_column").value("id"))
                .andExpect(jsonPath("$.version_column").value("ts"));
        IntegrationTables.enrichment(mockMvc, "scan_declared_enr", "scan_declared", "id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"scan-declared-pipeline\","
                                + Transforms.llm().proseOutputs("label").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"scan_declared_enr\","
                                + "\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // The pipeline surfaces the source's declared version column, resolved from the catalog — on the
        // compiled projection, which is where every resolved value lives.
        mockMvc.perform(get("/v1/pipelines/" + JsonPath.<String>read(content, "$.name") + "?view=compiled"))
                .andExpect(jsonPath("$.version_column").value("ts"));

        try {
            mockMvc.perform(runnerPost("/v1/pipelines/" + JsonPath.<String>read(content, "$.name") + "/scan")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows").isArray())
                    .andExpect(jsonPath("$.has_more").value(false));
        } finally {
            mockMvc.perform(delete("/v1/tables/scan_declared_enr"));
            mockMvc.perform(delete("/v1/tables/scan_declared"));
        }
    }

    /**
     * A repoint of the version column mid-scan. The cursor carries the tuple's <em>values</em> plus the
     * source entity — never the column names — and {@code RENAME COLUMN} keeps the data, so a cursor issued
     * before the rename resumes at exactly the position it encodes: the remaining row is returned once, not
     * skipped and not repeated. What does change is the page's own shape, since the scan projects the version
     * column under its own name; the pipeline read publishes the new name, which is how a runner learns it.
     *
     * <p>The identity column is a different matter here: this source's enrichment is keyed on it, so renaming
     * it is refused — the join and the Class-1 grain both resolve by that shared name.
     */
    @Test
    void renamingTheVersionColumnMidScanKeepsAnIssuedCursorValid() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"scan_rename\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/scan_rename/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"source_name\":\"id\",\"name\":\"id\",\"type\":\"string\","
                                + "\"nullable\":false},{\"source_name\":\"ts\",\"name\":\"ts\","
                                + "\"type\":\"timestamp\",\"nullable\":false}],\"ordering_key\":[\"id\"],"
                                + "\"identity_column\":\"id\",\"version_column\":\"ts\"}"))
                .andExpect(status().isOk());
        IntegrationTables.enrichment(mockMvc, "scan_rename_enr", "scan_rename", "id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
        String pipeline = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"scan-rename-pipeline\","
                                + Transforms.llm().proseOutputs("label").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"scan_rename_enr\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.name");

        try {
            execute("INSERT INTO scan_rename (id, ts) VALUES "
                    + "('a','2020-01-01 00:00:01.000'),('b','2020-01-01 00:00:02.000'),"
                    + "('c','2020-01-01 00:00:03.000')");

            String page1 = mockMvc.perform(runnerPost("/v1/pipelines/" + pipeline + "/scan")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(2))
                    .andExpect(jsonPath("$.has_more").value(true))
                    // The version column travels under its own name — the pre-rename one, for now.
                    .andExpect(jsonPath("$.rows[0].ts").exists())
                    .andReturn().getResponse().getContentAsString();
            String cursor = JsonPath.read(page1, "$.next_cursor");

            // The identity column stays immutable: it is this source's ordering key *and* the enrichment's
            // grain key, either of which refuses the rename (the ordering-key guard runs first here; the
            // grain-key rule is covered on a fixture where the two are different columns).
            mockMvc.perform(patch("/v1/tables/scan_rename/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rename\":[{\"from\":\"id\",\"to\":\"row_id\"}]}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.message", containsString("'id' cannot be modified")));

            mockMvc.perform(patch("/v1/tables/scan_rename/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rename\":[{\"from\":\"ts\",\"to\":\"ingested_at\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version_column").value("ingested_at"));
            // The pipeline now publishes the new name, so a runner reading it per source stays correct.
            mockMvc.perform(runnerPost("/v1/pipelines/" + pipeline + "/scan")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].ingested_at").exists())
                    .andExpect(jsonPath("$.rows[0].ts").doesNotExist());

            // The pre-rename cursor still resumes exactly where it left off: one row left, exactly once.
            String page2 = mockMvc.perform(runnerPost("/v1/pipelines/" + pipeline + "/scan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cursor\":\"" + cursor + "\",\"limit\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(1))
                    .andExpect(jsonPath("$.has_more").value(false))
                    .andReturn().getResponse().getContentAsString();
            Set<String> seen = new HashSet<>(JsonPath.<List<String>>read(page1, "$.rows[*].id"));
            seen.addAll(JsonPath.<List<String>>read(page2, "$.rows[*].id"));
            assertThat(seen).containsExactlyInAnyOrder("a", "b", "c");
        } finally {
            mockMvc.perform(delete("/v1/tables/scan_rename_enr"));
            mockMvc.perform(delete("/v1/tables/scan_rename"));
        }
    }
}
