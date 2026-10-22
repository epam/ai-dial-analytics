package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of a pipeline resolving its declaration against its read source's <b>entity</b> — the
 * source plus its ACTIVE enrichments — rather than against the source's own columns alone.
 *
 * <p>The shape in miniature: a group pipeline reads {@code dial_usage_log} for the bodies
 * and groups by a session key that only an enrichment computes. {@code sess_key} stands in for
 * {@code usage_client_identity}, keyed on the source's identity column {@code event_id} and carrying a
 * nullable {@code session_id}; {@code sess_label} stands in for {@code session_insights}.
 *
 * <p>What is pinned here and nowhere else: the group key is declared qualified and comes back under
 * <b>both</b> spellings — the qualified one the declaration used, which is what the Enrichment Runner
 * addresses the key column by ({@code row.get(pipeline.triggerGroupBy())}, a miss there being a silent
 * {@code continue}), and the target's plain grain key, which is what the target is physically keyed on and
 * what the write-back names; a NULL or empty key is excluded by the read rather than by the author's
 * filter; and a pipeline naming no enrichment still renders and behaves exactly as before.
 * Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class EnrichReadsEntityIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String SPINE = "sess_spine";
    private static final String KEY_ENRICHMENT = "sess_key";
    private static final String TARGET = "sess_label";

    @Autowired
    private PipelineRepository pipelineRepository;

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        // The key-supplying enrichment: row-grain on the read source's identity column, nullable session_id.
        IntegrationTables.enrichment(mockMvc, KEY_ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"session_id\",\"name\":\"session_id\",\"type\":\"string\","
                        + "\"nullable\":true}]");
        // The spine the target hangs off: one row per session, so it declares the grain key the target is
        // keyed on. A session-label enrichment like `session_insights` attaches to `sessions` while its pipeline
        // reads `dial_usage_log` for the bodies, because an enrichment's grain key must be a column of its
        // OWN source_table even when the pipeline reads another table entirely.
        IntegrationTables.source(mockMvc, SPINE,
                "[{\"source_name\":\"session_id\",\"name\":\"session_id\",\"type\":\"string\"}]",
                "[\"session_id\"]");
        IntegrationTables.enrichment(mockMvc, TARGET, SPINE, "session_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
    }

    /**
     * A GROUP-grain {@code llm} transform: at group grain the render inputs are only the five
     * {@code members_*} built-ins, {{members}} is mandatory, and a declared input is a field of each member
     * object inside it rather than a placeholder.
     */
    private static String groupTransform() {
        return Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                .proseOutputs("title")
                .member();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + TARGET));
        mockMvc.perform(delete("/v1/tables/" + SPINE));
        mockMvc.perform(delete("/v1/tables/" + KEY_ENRICHMENT));
    }

    private String pipelineBody(String groupBy, String filter) {
        return "{\"name\":\"sess-pipeline\"," + groupTransform() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\",\"inputs\":[\"" + SOURCE + "\"],"
                + "\"trigger\":{\"kind\":\"group\","
                + "\"group_by\":\"" + groupBy + "\",\"ready_when\":{\"idle\":\"10m\"}},"
                + (filter == null ? "" : "\"filter\":\"" + filter + "\",")
                + "\"enabled\":true}";
    }

    private void seedRow(String eventId, String ingestedAt) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, chat_id, event_kind, _ingested_at) VALUES (toUUID('" + eventId
                + "'),'p','d', now64(3),'t','" + eventId.substring(0, 8) + "','c','chat','" + ingestedAt + "')");
    }

    /** Keys {@code eventId}'s source row, carrying its request_time as the aligned key enrichment's partition value. */
    private void seedKey(String eventId, String sessionId) {
        execute("INSERT INTO " + KEY_ENRICHMENT + " (event_id, request_time, session_id) SELECT event_id, request_time, "
                + (sessionId == null ? "NULL" : "'" + sessionId + "'") + " FROM dial_usage_log WHERE event_id = toUUID('"
                + eventId + "')");
    }

    // ------------------------------------------------------------ registration

    @Test
    void grainKeyReachedThroughAnEnrichmentIsAccepted() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody(KEY_ENRICHMENT + ".session_id", null)))
                .andExpect(status().isCreated())
                // The trigger is declaration and comes back on the authored answer; the resolved input
                // and the derived grain key are not, so they are read where derived values live.
                .andExpect(jsonPath("$.trigger.group_by").value(KEY_ENRICHMENT + ".session_id"));
        mockMvc.perform(get("/v1/pipelines/sess-pipeline?view=compiled"))
                .andExpect(jsonPath("$.inputs[0]").value(SOURCE))
                .andExpect(jsonPath("$.grain_key").value("session_id"));
    }

    /**
     * The bare spelling stays reserved for the source, which is what keeps the widening monotonic — so a
     * grain key only an enrichment reaches must be named qualified, and the refusal says which name to use.
     */
    @Test
    void theBareSpellingOfAnEnrichmentColumnIsRejectedAndNamesTheQualifiedOne() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("session_id", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString(KEY_ENRICHMENT + ".session_id")));
    }

    @Test
    void filterOverAnEnrichmentColumnIsAccepted() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody(KEY_ENRICHMENT + ".session_id",
                                "length(\\\"" + KEY_ENRICHMENT + ".session_id\\\") > 0")))
                .andExpect(status().isCreated());
    }

    @Test
    void anEnrichmentOfAnotherSourceIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody("not_an_enrichment.session_id", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"));
    }

    // ------------------------------------------------------------ scan

    /**
     * The load-bearing assertion of the whole change: an enrichment-sourced group key is on the page under
     * <b>both</b> names, and each has a consumer that breaks silently without it.
     *
     * <p>The <b>qualified</b> name is the spelling {@code group_by} carries on the wire, and the Enrichment
     * Runner reads the key column as {@code row.get(pipeline.groupBy())} — a miss there is a {@code continue},
     * so the pipeline marks no group, raises nothing, and still advances its cursor. Verified against the live
     * runner: with only the plain name on the page it scanned, advanced, and marked zero groups; with both,
     * it marked every group.
     *
     * <p>The <b>plain grain key</b> is what the target is physically keyed on and what the key round-trips
     * as through the dirty mark, the assembly request and the write-back.
     */
    @Test
    void anEnrichmentSourcedGroupKeyIsProjectedUnderBothNames() throws Exception {
        final String id = createPipeline(KEY_ENRICHMENT + ".session_id", null);
        seedRow("11111111-1111-1111-1111-111111111111", "2026-01-01 00:00:01");
        seedKey("11111111-1111-1111-1111-111111111111", "s-1");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].session_id").value("s-1"))
                .andExpect(jsonPath("$.rows[0]['" + KEY_ENRICHMENT + ".session_id']").value("s-1"));
    }

    /**
     * ADAS emits the exclusion itself. NULL is the unmatched join; the empty string is a derived "no key"
     * and, with {@code join_use_nulls=0}, also an unmatched non-nullable column. Neither addresses a row of
     * the target, and an empty key would otherwise collect every keyless row into one meaningless group.
     */
    @Test
    void nullOrEmptyGroupKeyIsExcludedByTheReadWithNoFilterDeclared() throws Exception {
        final String id = createPipeline(KEY_ENRICHMENT + ".session_id", null);
        seedRow("22222222-2222-2222-2222-222222222222", "2026-01-01 00:00:01");
        seedKey("22222222-2222-2222-2222-222222222222", "s-ok");
        // Enriched, but with no session id at all.
        seedRow("33333333-3333-3333-3333-333333333333", "2026-01-01 00:00:02");
        seedKey("33333333-3333-3333-3333-333333333333", "");
        // Enriched with an explicit NULL — the same shape an unmatched join produces.
        seedRow("44444444-4444-4444-4444-444444444444", "2026-01-01 00:00:03");
        seedKey("44444444-4444-4444-4444-444444444444", null);

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].session_id").value("s-ok"));
    }

    /** The group is assembled by the plain key the scan handed back, through the same grain join. */
    @Test
    void theGroupAssemblesByTheKeyTheScanReported() throws Exception {
        final String id = createPipeline(KEY_ENRICHMENT + ".session_id", null);
        seedRow("55555555-5555-5555-5555-555555555555", "2026-01-01 00:00:01");
        seedKey("55555555-5555-5555-5555-555555555555", "s-9");
        seedRow("66666666-6666-6666-6666-666666666666", "2026-01-01 00:00:02");
        seedKey("66666666-6666-6666-6666-666666666666", "s-9");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/group-rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"s-9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)));
    }

    /**
     * The write-back closes the loop the reads open. Both reads hand the caller the key under the plain grain
     * key, but the scan <em>also</em> hands it under the declared {@code group_by} — and a caller that keys
     * its row by the name the pipeline declares (the Enrichment Runner did) must not have its whole pipeline
     * dead-lettered for it. The qualified name is no column of the target, so it is renamed to the grain key
     * rather than accepted as one: the row lands under {@code session_id}, addressable by the same key the
     * scan reported.
     */
    @Test
    void writeBackAcceptsTheDeclaredGroupBySpellingAndKeysTheRowByGrain() throws Exception {
        final String id = createPipeline(KEY_ENRICHMENT + ".session_id", null);

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"" + KEY_ENRICHMENT + ".session_id\":\"s-w\","
                                + "\"title\":\"a label\"}]}"))
                .andExpect(status().isOk());

        execute("INSERT INTO " + SPINE + " (session_id) VALUES ('s-w')");
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"" + SPINE + "\",\"mode\":\"row\",\"select\":["
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"session_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"" + TARGET + ".title\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].session_id").value("s-w"))
                .andExpect(jsonPath("$.rows[0]['" + TARGET + ".title']").value("a label"));
    }

    /** Both spellings name the same grain, so a row disagreeing with itself about the key is refused. */
    @Test
    void writeBackRejectsTheGroupKeyNamedTwiceWithDifferentValues() throws Exception {
        final String id = createPipeline(KEY_ENRICHMENT + ".session_id", null);

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"" + KEY_ENRICHMENT + ".session_id\":\"s-a\","
                                + "\"session_id\":\"s-b\",\"title\":\"a label\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("names the group key twice")));
    }

    /** The rename is scoped to the group key: every other undeclared column is still refused. */
    @Test
    void writeBackStillRejectsAnUndeclaredColumn() throws Exception {
        final String id = createPipeline(KEY_ENRICHMENT + ".session_id", null);

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"" + KEY_ENRICHMENT + ".session_id\":\"s-w\","
                                + "\"" + KEY_ENRICHMENT + ".other\":\"x\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("write_column_not_allowed"));
    }

    /**
     * A pipeline naming no enrichment must be untouched by all of this — no join, no emitted predicate, and the
     * group key under its own name, exactly as before enrichment references were admitted.
     */
    @Test
    void pipelineNamingNoEnrichmentIsUnchanged() throws Exception {
        IntegrationTables.enrichment(mockMvc, "chat_label", SOURCE, "chat_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        try {
            final String created = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"chat-pipeline\"," + groupTransform() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"chat_label\","
                                    + "\"trigger\":{\"kind\":\"group\",\"group_by\":\"chat_id\","
                                    + "\"ready_when\":{\"idle\":\"10m\"}},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            final String id = JsonPath.read(created, "$.name");
            seedRow("77777777-7777-7777-7777-777777777777", "2026-01-01 00:00:01");

            mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"limit\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(1)))
                    // The source's own chat_id, under its own name, with no key exclusion applied.
                    .andExpect(jsonPath("$.rows[0].chat_id").value("c"));
        } finally {
            mockMvc.perform(delete("/v1/tables/chat_label"));
        }
    }

    // ------------------------------------------------------------ catalog safety

    @Test
    void enrichmentReadByPipelineCannotBeDropped() throws Exception {
        createPipeline(KEY_ENRICHMENT + ".session_id", null);

        mockMvc.perform(delete("/v1/tables/" + KEY_ENRICHMENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message").value(containsString("sess-pipeline")));
    }

    // ------------------------------------------------------------ absence must be representable

    /**
     * An enrichment column in a value-producing position has to be able to represent the absence of its
     * enrichment row. The grain join is outer, and with {@code join_use_nulls = 0} ClickHouse renders an
     * unmatched non-nullable column as its <em>type default</em> — here an empty string, which is a
     * perfectly valid group key naming a group that corresponds to nothing.
     *
     * <p>The fixture also pins the disambiguation rule: two enrichments of one source declare
     * {@code session_id}, and the declaration is answered against the one {@code group_by} names rather
     * than refused as ambiguous.
     */
    @Test
    void nonNullableEnrichmentColumnIsRefusedAsGroupKey() throws Exception {
        nonNullableKeyEnrichment();
        try {
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content(pipelineBody("nn_key.session_id", null)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(containsString("declared non-nullable")));
        } finally {
            mockMvc.perform(delete("/v1/tables/nn_key"));
        }
    }

    /** The same pipeline in the other value-producing position: a Class-1 output expression. */
    @Test
    void nonNullableEnrichmentColumnIsRefusedInClassOneExpression() throws Exception {
        nonNullableKeyEnrichment();
        try {
            IntegrationTables.enrichment(mockMvc, "nn_reader", SOURCE, "event_id",
                    "[{\"source_name\":\"sid\",\"name\":\"sid\",\"type\":\"string\",\"nullable\":true}]");
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"nn-reader-pipeline\","
                                    + sqlTransform("sid", "nn_key.session_id") + ","
                                    + "\"kind\":\"enrich\",\"target\":\"nn_reader\",\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(containsString("declared non-nullable")));
        } finally {
            mockMvc.perform(delete("/v1/tables/nn_reader"));
            mockMvc.perform(delete("/v1/tables/nn_key"));
        }
    }

    /** A second enrichment reaching the same grain key, this one declaring it explicitly non-nullable. */
    private void nonNullableKeyEnrichment() throws Exception {
        IntegrationTables.enrichment(mockMvc, "nn_key", SOURCE, "event_id",
                "[{\"source_name\":\"session_id\",\"name\":\"session_id\",\"type\":\"string\","
                        + "\"nullable\":false}]");
    }

    // ------------------------------------------------------------ a row-grain pipeline cannot key on an enrichment

    /**
     * Only a group pipeline may take its grain key from an enrichment: it declares the qualified field as
     * {@code group_by} and every read projects it under the plain grain key. A row-grain pipeline declares no
     * {@code group_by}, so its reads would name the bare grain key — a column the source does not carry —
     * and the statement would fail at execution instead of here. It is also unsound on its own terms: many
     * source rows share one enrichment value and would collapse onto a single target row.
     */
    @Test
    void rowGrainPipelineMayNotKeyItsRowsOnAnEnrichmentColumn() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sess-row-pipeline\"," + groupTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + TARGET + "\",\"inputs\":[\"" + SOURCE + "\"],"
                                + "\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("only a group pipeline")));
    }

    // ------------------------------------------------------------ a Class-1 pipeline reading a sibling

    /**
     * A {@code sql} pipeline's column references live in its <b>transform's</b> output expressions, not in
     * the pipeline row. Everything that walks a stored declaration — the catalog's drop and rename checks, the
     * dependency-cycle walk, the freshness diagnostics — has to look there too, or a Class-1 pipeline that
     * derives from a sibling enrichment reads as though it referenced nothing at all.
     */
    @Test
    void enrichmentReadOnlyByClassOneOutputExpressionCannotBeDropped() throws Exception {
        createClass1ReaderOf(KEY_ENRICHMENT + ".session_id", "c1_sid", "sid", "c1-sid-pipeline");
        try {
            mockMvc.perform(delete("/v1/tables/" + KEY_ENRICHMENT))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("conflict"))
                    .andExpect(jsonPath("$.message").value(containsString("c1-sid-pipeline")));
        } finally {
            mockMvc.perform(delete("/v1/tables/c1_sid"));
        }
    }

    /** The same reads have to be visible to the cycle walk, or two pipelines can clamp each other forever. */
    @Test
    void cycleClosedThroughClassOneOutputExpressionIsRejected() throws Exception {
        createClass1ReaderOf(KEY_ENRICHMENT + ".session_id", "c1_sid", "sid", "c1-sid-pipeline");
        try {
            // The return edge: a pipeline targeting sess_key whose expression reads c1_sid, which reads sess_key.
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-back-pipeline\","
                                    + sqlTransform("session_id", "c1_sid.sid") + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + KEY_ENRICHMENT + "\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(containsString("cycle")));
        } finally {
            mockMvc.perform(delete("/v1/tables/c1_sid"));
        }
    }

    // ------------------------------------------------------------ the clamp and the drained horizon

    /**
     * A window the freshness clamp emptied is not a drained pipeline. The drained horizon claims "nothing
     * matching below this instant", and every downstream clamp reads it as licence to advance past rows
     * this pipeline never enriched — so recording it here would hand a downstream reader exactly the unbounded
     * read the clamp just denied this one, one level further down and equally unrecoverable.
     */
    @Test
    void windowEmptiedByTheClampDoesNotRecordThePipelineAsDrained() throws Exception {
        IntegrationTables.enrichment(mockMvc, "clamp_up", SOURCE, "event_id",
                "[{\"source_name\":\"tag\",\"name\":\"tag\",\"type\":\"string\",\"nullable\":true}]");
        try {
            String upstream = createSqlPipeline("clamp-up-pipeline", "clamp_up", "tag", "deployment");
            seedRow("88888888-8888-8888-8888-888888888888", "2026-01-01 00:00:01");
            seedRow("99999999-9999-9999-9999-999999999999", "2026-01-01 00:00:02");
            // One row only, so the upstream's recorded position sits between the two source rows.
            mockMvc.perform(runnerPost("/v1/pipelines/" + upstream + "/materialize")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows_written").value(1));

            String downstream = createClass1ReaderOf("clamp_up.tag", "clamp_down", "t2", "clamp-down-pipeline");
            String cursor = JsonPath.read(mockMvc.perform(runnerPost("/v1/pipelines/" + downstream + "/materialize")
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.rows_written").value(1))
                            .andReturn().getResponse().getContentAsString(), "$.next_cursor");

            // The second row is above the upstream's position, so the clamp — not an absence of rows —
            // empties this window.
            mockMvc.perform(runnerPost("/v1/pipelines/" + downstream + "/materialize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cursor\":\"" + cursor + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows_written").value(0))
                    .andExpect(jsonPath("$.next_cursor").value(cursor));

            PipelineEntity stored = pipelineRepository.findById(downstream).orElseThrow();
            assertThat(stored.getDrainedAt())
                    .as("a clamped-empty window must not claim the pipeline is drained")
                    .isNull();
        } finally {
            mockMvc.perform(delete("/v1/tables/clamp_down"));
            mockMvc.perform(delete("/v1/tables/clamp_up"));
        }
    }

    /**
     * Registers a Class-1 enrichment {@code target} carrying one nullable string column and the pipeline that
     * fills it from {@code expression}, and returns the pipeline id. The expression is quoted because a
     * qualified flat name is one identifier containing a dot, not a two-part reference.
     */
    private String createClass1ReaderOf(String expression, String target, String column, String pipelineName)
            throws Exception {
        IntegrationTables.enrichment(mockMvc, target, SOURCE, "event_id",
                "[{\"source_name\":\"" + column + "\",\"name\":\"" + column
                        + "\",\"type\":\"string\",\"nullable\":true}]");
        return createSqlPipeline(pipelineName, target, column, expression);
    }

    /**
     * A {@code sql} transform with one output computing {@code expression}. The expression is quoted
     * because a qualified flat name is one identifier containing a dot, not a two-part reference.
     */
    private static String sqlTransform(String column, String expression) {
        return Transforms.sql().output(column, "\\\"" + expression + "\\\"").member();
    }

    private String createSqlPipeline(String name, String target, String column, String expression)
            throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"," + sqlTransform(column, expression) + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + target + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.name");
    }

    private String createPipeline(String groupBy, String filter) throws Exception {
        final String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content(pipelineBody(groupBy, filter)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // Read the top-level member rather than pattern-matching the body: the merged response carries
        // several `name` members (an output binding's var, a transform output's), so a greedy regex picks the
        // last one — which is not the pipeline's identity.
        return JsonPath.read(content, "$.name");
    }
}
