package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.enrichment.MaterializePositionWriter;
import com.epam.aidial.analytics.service.enrichment.MaterializeService;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.jayway.jsonpath.JsonPath;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of Class-1 ({@code type=sql}) enrichment against real PostgreSQL + ClickHouse: a
 * a {@code sql} transform is declared without model/preset/template while {@code llm} still requires them;
 * expression governance rejects an aggregate, an unknown column, a non-catalog function, and a
 * type-incompatible expression; {@code POST /v1/pipelines/{name}/materialize} writes exactly the probed window
 * with a matching {@code rows_written}; re-running the identical call leaves the {@code final=1} result
 * unchanged; a {@code from}/{@code to} window materializes only that range; an empty window echoes the
 * cursor; and the two execution paths refuse each other's pipelines.
 *
 * <p>The worked example is the phase's motivating Class-1 workload — extracting a declared field out of
 * the {@code sensitive} {@code request_body} — created here as a test fixture, not seeded by a migration.
 * Safety-lag is 0 so the past-dated fixture rows are all visible; the property set is the shared one every
 * enrichment integration class declares (see {@link AbstractClickHouseIntegrationTest#SCAN_SAFETY_LAG_OFF}),
 * so they share one Spring context. Requires Docker.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class Class1MaterializeIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "c1_model";
    private static final String COARSE_ENRICHMENT = "c1_coarse";
    private static final String RENAMED_ENRICHMENT = "c1_renamed";

    @Autowired
    private PipelineRepository pipelineRepository;

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"body_model\",\"name\":\"body_model\",\"type\":\"string\"},"
                        // Declared, so injection leaves it alone: the materialize path stamps the pipeline
                        // GENERATION now, and `long` is the only honest declaration for an unbounded
                        // counter. `evaluator_version` is never written again.
                        + "{\"source_name\":\"pipeline_generation\","
                        + "\"name\":\"pipeline_generation\",\"type\":\"long\"},"
                        + "{\"source_name\":\"enriched_at\",\"name\":\"enriched_at\",\"type\":\"timestamp\"}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
    }

    /**
     * The Class-1 transform: one output extracting {@code model} from the request body.
     *
     * <p>Keyed by the TARGET COLUMN it writes, which is what removes the separate {@code output_bindings}
     * declaration — and what couples an output's name to a column's. {@code body_model}, not
     * {@code model}, for that reason.
     */
    private static String sqlTransform() {
        return sqlTransform("body_model",
                "json_extract_string(\\\"dial_usage_log_payload.request_body\\\", 'model')");
    }

    /** A {@code sql} transform with one output for {@code column} computing {@code expression}. */
    private static String sqlTransform(String column, String expression) {
        return Transforms.sql().output(column, expression).member();
    }

    private String createSqlPipeline() throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-pipeline\"," + sqlTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.name");
    }

    /** Declares a {@code sql} transform whose single output carries {@code expression} and expects a refusal. */
    private void expectPipelineRejected(String name, String expression, String code)
            throws Exception {
        expectPipelineRejected(name, expression, code, null);
    }

    /**
     * As above, additionally asserting a fragment of the rejection's {@code message}. Worth an overload for
     * the cases where the message is the point: a governance rejection an author cannot act on is only
     * half a rejection, and the fragment is what pins the actionable part to the surface a client sees
     * rather than to the class that composes it.
     *
     * <p>One call rather than two, which is the fold's visible effect here: the expression used to be
     * accepted by the evaluator registry and refused only when a pipeline bound it, so every one of these
     * cases needed a 201 followed by a 422. There is nothing left to defer — the read source is in scope
     * where the declaration is written.
     */
    private void expectPipelineRejected(
            String name, String expression, String code, String messageFragment)
            throws Exception {
        var result = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pipeline-" + name.replace('_', '-') + "\","
                                + sqlTransform("body_model", expression) + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(code));
        if (messageFragment != null) {
            result.andExpect(jsonPath("$.message").value(containsString(messageFragment)));
        }
    }

    private void seedRow(String eventId, String ingestedAt, String requestBody) {
        seedRow(eventId, ingestedAt, requestBody, "{}", "");
    }

    /**
     * As {@link #seedRow(String, String, String)} but also seeding the {@code object} column and chat id.
     *
     * <p>Two inserts, because both the body and the claim document live on
     * {@code dial_usage_log_payload} since the split. They share this row's {@code event_id}, which is
     * what the catalog's grain join matches on, and its {@code request_time}, because the two tables are
     * partition-aligned in the catalog and the keyed join bounds the payload side by the parent's value.
     * That shared value is today's date, which is also what keeps it inside the payload table's 90-day
     * TTL; {@code _ingested_at} is what these tests actually order and window by.
     */
    private void seedRow(String eventId, String ingestedAt, String requestBody, String jwtClaims, String chatId) {
        // One request_time for both rows: the payload table is partition-aligned with its parent, so the
        // grain join bounds it by the parent's value. Two now64(3) calls would drift and lose the row.
        final String requestTime = "'" + fixtureTime("00:00:00.000") + "'";
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, _ingested_at, chat_id) VALUES (toUUID('" + eventId
                + "'),'p','d', " + requestTime + ",'t','" + eventId.substring(0, 8) + "','" + ingestedAt + "','"
                + escape(chatId) + "')");
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " request_body, jwt_claims) VALUES (toUUID('" + eventId + "'), " + requestTime + ", 't','"
                + eventId.substring(0, 8) + "','" + escape(requestBody) + "','"
                + escape(jwtClaims) + "')");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Reads the enrichment column back through the governed query surface (grain-joined, final=1). */
    private void expectModels(Object... expected) throws Exception {
        var actions = mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"event_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"c1_model.body_model\"}}],"
                                + "\"sort\":[{\"field\":\"event_id\",\"dir\":\"asc\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(expected.length)));
        for (int i = 0; i < expected.length; i++) {
            actions.andExpect(jsonPath("$.rows[" + i + "]['c1_model.body_model']").value(expected[i]));
        }
    }

    // ------------------------------------------------------------ declaration shape

    @Test
    void sqlTransformIsDeclaredWithoutModelPresetOrTemplate() throws Exception {
        final String id = createSqlPipeline();

        // Read back on view=source, which is where the AUTHORED transform lives — and on view=compiled
        // too, because serving it on both is what lets a read-modify-write client patch it back unchanged.
        mockMvc.perform(get("/v1/pipelines/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transform.type").value("sql"))
                .andExpect(jsonPath("$.transform.model").doesNotExist())
                .andExpect(jsonPath("$.transform.preset").doesNotExist())
                .andExpect(jsonPath("$.transform.request_template").doesNotExist())
                // The legacy output shape is absent, not empty: a declaration authored now carries
                // `outputs`, and which shape it holds stays answerable by which member is present.
                .andExpect(jsonPath("$.transform.output_vars").doesNotExist())
                .andExpect(jsonPath("$.transform.response_schema").doesNotExist())
                .andExpect(jsonPath("$.transform.outputs.body_model")
                        .value("json_extract_string(\"dial_usage_log_payload.request_body\", 'model')"));

        // A sql transform constrains no model response, so the derived member is absent even on compiled.
        mockMvc.perform(get("/v1/pipelines/" + id + "?view=compiled"))
                .andExpect(jsonPath("$.transform.type").value("sql"))
                .andExpect(jsonPath("$.response_schema").doesNotExist());
    }

    @Test
    void sqlTransformCarryingLlmOnlyFieldsIsRejected() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-bad-model\","
                                + Transforms.sql().model("gpt-4o").output("body_model", "deployment").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("must not carry 'transform.model'")));
    }

    @Test
    void sqlTransformCarryingPresetParamsIsRejected() throws Exception {
        // params shape the preset's request; a sql transform has no preset, so carrying them would be a
        // silently ignored declaration rather than a configuration that does anything.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-bad-params\","
                                + Transforms.sql().params("{\"temperature\":0}")
                                        .output("body_model", "deployment").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("must not carry 'transform.params'")));
    }

    @Test
    void sqlTransformWithoutAnExpressionIsRejected() throws Exception {
        // A sql output's string is an expression, not prose, so it cannot be defaulted from anything — the
        // optional-prose relaxation is type-split and deliberately does not leak across.
        expectPipelineRejected("c1_no_expr", "  ", "pipeline_validation_failed",
                "requires a sql expression");
    }

    @Test
    void llmTransformStillRequiresItsModel() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-llm-bad\","
                                + Transforms.llm().model(null)
                                        .output("body_model", "What the model should say.").member()
                                + ",\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("requires a model")));
    }

    // ------------------------------------------------------------ expression governance

    @Test
    void aggregateExpressionIsRejected() throws Exception {
        expectPipelineRejected("c1_agg", "sum(total_tokens)", "pipeline_validation_failed");
    }

    @Test
    void unknownColumnInExpressionIsRejected() throws Exception {
        expectPipelineRejected("c1_unknown", "lower(no_such_column)", "pipeline_validation_failed");
    }

    @Test
    void nonCatalogFunctionInExpressionIsRejected() throws Exception {
        // now() is not in the closed function catalog, so non-determinism is rejected for free.
        expectPipelineRejected("c1_nondet", "now()", "pipeline_validation_failed");
    }

    @Test
    void expressionSubstitutingItsOwnSourceIsRejected() throws Exception {
        // The expression is concatenated into "SELECT <expr> AS __expr FROM <source>", so a trailing SQL
        // comment can supply a different source. That would read a table the pipeline never declared, and the
        // entitlement check — which looks referenced columns up in the *declared* source's catalog rows —
        // would find none of them and wave the read through. Rejected on the entity, before any of that.
        expectPipelineRejected("c1_from_swap", "comment FROM rate_analytics --",
                "pipeline_validation_failed");
    }

    /**
     * A Class-1 expression may read a <b>sibling</b> enrichment of its source (see
     * {@code EnrichReadsEntityIntegrationTest}) — but not the enrichment the pipeline itself writes. That closes
     * a length-zero dependency cycle: the pipeline's window would be clamped by its own target's position,
     * which only its own writes advance, so it would freeze permanently with nothing raised anywhere.
     */
    @Test
    void expressionReferencingItsOwnTargetEnrichmentIsRejected() throws Exception {
        expectPipelineRejected("c1_enrichment_ref", "lower(\\\"c1_model.body_model\\\")",
                "pipeline_validation_failed", "dependency cycle");
    }

    @Test
    void typeIncompatibleExpressionIsRejected() throws Exception {
        // Incompatible against the TARGET COLUMN, which is the only type comparison left: `length(...)`
        // infers integer and `body_model` is a string column. Distinct from the array case below — this is
        // the plain scalar mismatch, which no numeric widening rescues.
        expectPipelineRejected("c1_type", "length(deployment)", "pipeline_validation_failed");
    }

    /**
     * The declared per-output type is gone, and dropping it can only <b>loosen</b> the gate — never tighten
     * it. A declaration whose declared token disagreed with its own expression used to be refused at the
     * first of two hops; now only the expression's inferred type is compared, against the target column.
     *
     * <p>Worth a test of its own rather than a note, because it is the one visible behaviour change of the
     * collapse: this exact body was a 422 before and is a 201 now. Everything the two-hop check accepted it
     * still accepts (compatibility is transitive over its three rules), so no live declaration can begin to
     * fail — but a declaration that was refused for disagreeing with itself no longer has anything to
     * disagree with.
     */
    @Test
    void expressionDisagreeingWithTheDroppedTypeTokenIsNowAccepted() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-loosened\","
                                + sqlTransform("body_model", "lower(deployment)") + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\","
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isCreated());
    }

    @Test
    void sqlPipelineWhoseOutputsResolveToNoTargetColumnIsRejected() throws Exception {
        // The output mapping is derived, so "binds nothing" is no longer expressible on the wire. What is
        // still expressible — and still refused — is a transform whose outputs name no column of the
        // target, which leaves nothing to materialize.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-no-outputs\","
                                + sqlTransform("no_such_column", "lower(deployment)") + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("resolves to no column on")))
                .andExpect(jsonPath("$.message").value(containsString("target column of the same name")));
    }

    /**
     * A Class-1 derivation is per source row, so its target must be keyed on the source's identity column.
     * Against a coarser grain one window would insert many rows under one key and the target's
     * ReplacingMergeTree would keep an arbitrary survivor — a label that is neither wrong nor reproducible.
     */
    @Test
    void sqlPipelineTargetingCoarserGrainIsRejected() throws Exception {
        IntegrationTables.enrichment(mockMvc, COARSE_ENRICHMENT, SOURCE, "chat_id",
                "[{\"source_name\":\"body_model\",\"name\":\"body_model\",\"type\":\"string\"}]");
        try {
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-coarse\"," + sqlTransform() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + COARSE_ENRICHMENT + "\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message")
                            .value(containsString("must be keyed on the source's identity column 'event_id'")));
        } finally {
            mockMvc.perform(delete("/v1/tables/" + COARSE_ENRICHMENT));
        }
    }

    /**
     * A Class-1 pipeline may declare its own read source, and everything Class-1 governance says then resolves
     * against THAT table: the expression's columns, and the identity column the target must be keyed on.
     * Here the enrichment hangs off {@code dial_usage_log} while its value is derived, set-based, out of
     * {@code rate_analytics} — whose {@code identity_column} is the same {@code event_id} the target is
     * keyed on, which is exactly what makes the derivation per-row and therefore Class-1.
     *
     * <p>The enrichment hangs off an unpartitioned source rather than {@code dial_usage_log}: an event_id-keyed
     * enrichment of the usage log is aligned, and a pipeline reading another table cannot write an aligned target
     * (AlignedEnrichmentWritersIntegrationTest).
     */
    @Test
    void sqlPipelineMaterializesFromTheReadSourceItDeclares() throws Exception {
        execute("TRUNCATE TABLE rate_analytics");
        execute("INSERT INTO rate_analytics (request_time, _ingested_at, project_id, trace_id, "
                + "core_span_id, deployment, user_hash, chat_id, response_id, rate, comment) VALUES "
                + "('2026-01-01 00:00:00.000','2020-01-01 00:00:01.000','p','t1','s1','d','u','c','resp_1',1,NULL),"
                + "('2026-01-01 00:00:01.000','2020-01-01 00:00:02.000','p','t2','s2','d','u','c','resp_2',1,NULL)");
        IntegrationTables.source(mockMvc, "c1_events",
                "[{\"source_name\":\"event_id\",\"name\":\"event_id\",\"type\":\"uuid\"},"
                        + "{\"source_name\":\"ts\",\"name\":\"ts\",\"type\":\"timestamp\"}]",
                "[\"event_id\"]", "event_id", "ts");
        IntegrationTables.enrichment(mockMvc, "c1_rated", "c1_events", "event_id",
                "[{\"source_name\":\"rated_resp\",\"name\":\"rated_resp\",\"type\":\"string\"}]");
        try {
            String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-rated-pipeline\","
                                    + sqlTransform("rated_resp", "lower(response_id)") + ","
                                    + "\"kind\":\"enrich\",\"target\":\"c1_rated\",\"inputs\":[\"rate_analytics\"],"
                                    + "\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.inputs[0]").value("rate_analytics"))
                    .andReturn().getResponse().getContentAsString(), "$.name");

            // The INSERT … SELECT reads rate_analytics: two rated rows in, two enrichment rows written.
            mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows_written").value(2));
        } finally {
            mockMvc.perform(delete("/v1/tables/c1_rated"));
            mockMvc.perform(delete("/v1/tables/c1_events"));
            execute("TRUNCATE TABLE rate_analytics");
        }
    }

    /**
     * The row-grain check follows the declared read source too: {@code rate_analytics} identifies a row by
     * {@code event_id}, so a target keyed on {@code chat_id} is one value per conversation over many rate
     * rows — an aggregation, which this capability does not provide.
     */
    @Test
    void sqlPipelineCoarserThanItsDeclaredReadSourceIsRejected() throws Exception {
        IntegrationTables.enrichment(mockMvc, COARSE_ENRICHMENT, SOURCE, "chat_id",
                "[{\"source_name\":\"rated_resp\",\"name\":\"rated_resp\",\"type\":\"string\"}]");
        try {
            mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-rated-coarse\","
                                    + sqlTransform("rated_resp", "lower(response_id)") + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + COARSE_ENRICHMENT + "\","
                                    + "\"inputs\":[\"rate_analytics\"],\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message")
                            .value(containsString("must be keyed on the source's identity column 'event_id'")));
        } finally {
            mockMvc.perform(delete("/v1/tables/" + COARSE_ENRICHMENT));
        }
    }

    /**
     * A target column whose physical {@code source_name} differs from its logical {@code name}: the pipeline
     * binds the logical name, and the materialize statement must still write the physical column (the
     * registration gate normalizes the binding), rather than emitting a column ClickHouse does not have.
     */
    @Test
    void materializeWritesRenamedTargetColumn() throws Exception {
        seedRow("61111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"renamed\"}");
        IntegrationTables.enrichment(mockMvc, RENAMED_ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"model_physical\",\"name\":\"model_logical\",\"type\":\"string\"}]");
        try {
            // An output resolves to the target column of the same EXPOSED name, so this transform names
            // `model_logical` — and the derived binding still carries the physical `model_physical`.
            String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-renamed\","
                                    + sqlTransform("model_logical",
                                            "json_extract_string(\\\"dial_usage_log_payload.request_body\\\", 'model')")
                                    + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + RENAMED_ENRICHMENT + "\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            // The derived output carries the physical column, which is what both write paths need — and,
            // separately, the exposed name the output was declared under. This target exposes
            // `model_logical` over a `model_physical` column, so it is the live proof that the two halves
            // of an output are two facts: collapsing them would either write to a column that does not
            // exist or look up a response property that was never declared.
            mockMvc.perform(get("/v1/pipelines/c1-renamed?view=compiled"))
                    .andExpect(jsonPath("$.outputs[0].column").value("model_physical"))
                    .andExpect(jsonPath("$.outputs[0].name").value("model_logical"));

            mockMvc.perform(runnerPost("/v1/pipelines/" + JsonPath.read(content, "$.name") + "/materialize")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows_written").value(1));

            // Read back through the query surface, which addresses the column by its logical name.
            mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                    + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\""
                                    + RENAMED_ENRICHMENT + ".model_logical\"}}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0]['" + RENAMED_ENRICHMENT + ".model_logical']").value("renamed"));
        } finally {
            mockMvc.perform(delete("/v1/tables/" + RENAMED_ENRICHMENT));
        }
    }

    @Test
    void sqlPipelineWithInputBindingsIsRejected() throws Exception {
        // The member moved into the transform; the rule did not.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-bindings\","
                                + Transforms.sql().input("x", "deployment")
                                        .output("body_model", "lower(deployment)").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message")
                        .value(containsString("must not declare transform.inputs")));
    }

    // ------------------------------------------------------------ materialize

    @Test
    void materializeWritesTheProbedWindowAndIsIdempotent() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        seedRow("22222222-2222-2222-2222-222222222222", "2020-01-01 00:00:02.000", "{\"model\":\"gpt-4.1\"}");
        String id = createSqlPipeline();

        String first = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(2))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        expectModels("gpt-4o", "gpt-4.1");

        // Provenance is stamped by ADAS itself; `model` is skipped (no model call). The stamp is the
        // pipeline GENERATION — 1 for a freshly registered pipeline — and not an evaluator version: the
        // definition is the pipeline's own, and its audit trail at that generation is what says which
        // expression produced the row.
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":"
                                + "\"c1_model.pipeline_generation\"}}],"
                                + "\"sort\":[{\"field\":\"event_id\",\"dir\":\"asc\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0]['c1_model.pipeline_generation']").value(1));

        // Re-running the identical call re-executes the same window; the final=1 result is unchanged.
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(2));
        expectModels("gpt-4o", "gpt-4.1");

        // Paging past the window's end writes nothing and echoes the cursor.
        String cursor = JsonPath.read(first, "$.next_cursor");
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":\"" + cursor + "\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(0))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").value(cursor));
    }

    // --------------------------------------- the materialized position ADAS records

    /**
     * The position is what a dependent rollup clamps its window to, so every property below is one whose
     * absence would silently either stall such a rollup forever or let it read enrichment cells that are
     * not there yet. Asserted against the stored pipeline row rather than the response, because nothing about
     * this appears on the wire — that is the point of it being an observation rather than a report.
     */
    @Test
    void writtenWindowRecordsItsEndPositionAndNeverLowersIt() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        seedRow("22222222-2222-2222-2222-222222222222", "2020-01-01 00:00:02.000", "{\"model\":\"gpt-4.1\"}");
        final String id = createSqlPipeline();

        final String first = materialize(id, "{\"limit\":1}");
        assertThat(positionOf(id)).isEqualTo(Instant.parse("2020-01-01T00:00:01Z").toEpochMilli());
        assertThat(pipeline(id).getMaterializedThroughIdentity())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        // Stamped with the generation it was recorded under, which is what makes a later pipeline mutation
        // discard it rather than leave a tuple whose axis may have moved.
        assertThat(pipeline(id).getMaterializedGeneration()).isEqualTo(pipeline(id).getGeneration());

        materialize(id, "{\"cursor\":\"" + JsonPath.read(first, "$.next_cursor") + "\",\"limit\":1}");
        assertThat(positionOf(id)).isEqualTo(Instant.parse("2020-01-01T00:00:02Z").toEpochMilli());

        // A replay from the beginning re-probes a window ending at the FIRST row, below what is stored. It
        // must not drag the mark backwards: a rollup that had already read up to row 2 would then re-read
        // rows it has aggregated, and — worse — a rollup clamped to the lowered mark would stall.
        materialize(id, "{\"limit\":1}");
        assertThat(positionOf(id)).isEqualTo(Instant.parse("2020-01-01T00:00:02Z").toEpochMilli());
    }

    @Test
    void emptyWindowRecordsDrainedInsteadOfPosition() throws Exception {
        final String id = createSqlPipeline();

        // No rows at all, so the probe finds nothing and the response echoes the caller's cursor.
        materialize(id, "{\"limit\":10}");

        assertThat(pipeline(id).getDrainedAt()).isNotNull();
        // The assertion that is the whole point of the test: the echoed cursor is the CALLER's claim, not
        // something ADAS observed, so persisting it as a position would record the runner's assertion as if
        // ADAS had written through it. A rollup would then read past rows nothing has enriched.
        assertThat(pipeline(id).getMaterializedThroughVersion()).isNull();
        assertThat(pipeline(id).getMaterializedThroughIdentity()).isNull();
        assertThat(pipeline(id).getHistoryRederivedAt()).isNull();
    }

    @Test
    void boundedWindowRecordsTheReDerivationAndNeitherPosition() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        final String id = createSqlPipeline();

        // A from/to call is the backfill discriminator: it rewrites values consumers may already have read.
        materialize(id, "{\"from\":\"2020-01-01T00:00:00Z\",\"to\":\"2020-01-02T00:00:00Z\",\"limit\":10}");

        assertThat(pipeline(id).getHistoryRederivedAt()).isNotNull();
        // Its position is inside history rather than at the pipeline's tail, so recording it would drag the mark
        // backwards; and "nothing left in this backfill window" says nothing about the tail either.
        assertThat(pipeline(id).getMaterializedThroughVersion()).isNull();
        assertThat(pipeline(id).getDrainedAt()).isNull();
    }

    @Test
    void unwindowedReplayOfAlreadyWrittenRowsRecordsReDerivation() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        seedRow("22222222-2222-2222-2222-222222222222", "2020-01-01 00:00:02.000", "{\"model\":\"gpt-4.1\"}");
        final String id = createSqlPipeline();

        // Two forward windows: ordinary live-tick progress, and neither may look like a re-derivation.
        final String first = materialize(id, "{\"limit\":1}");
        assertThat(pipeline(id).getHistoryRederivedAt()).isNull();
        materialize(id, "{\"cursor\":\"" + JsonPath.read(first, "$.next_cursor") + "\",\"limit\":1}");
        assertThat(pipeline(id).getHistoryRederivedAt()).isNull();

        // A full replay: cursor back to the source start, and NO from/to — the default shape of the
        // runner's backfill, and the way an expression change is re-applied to history. Nothing in
        // the request distinguishes it from a live tick, so it is the write that has to be recognised: this
        // window ends at row 1, at or below what the pipeline was already complete through.
        materialize(id, "{\"limit\":1}");

        assertThat(pipeline(id).getHistoryRederivedAt()).isNotNull();
        // Still monotonic — the replay records the re-derivation as well as leaving the mark where it was,
        // rather than instead of it.
        assertThat(positionOf(id)).isEqualTo(Instant.parse("2020-01-01T00:00:02Z").toEpochMilli());
    }

    @Test
    void replayBelowDrainedHorizonRecordsReDerivationWithNoWrittenPosition() throws Exception {
        final String id = createSqlPipeline();

        // Drained with nothing ever written: the shape a pipeline settles into when its filter has matched
        // nothing since its last mutation. The horizon alone is what a dependent rollup read past, so a
        // later write below it is still a re-derivation even though no position was ever recorded.
        materialize(id, "{\"limit\":10}");
        assertThat(pipeline(id).getDrainedAt()).isNotNull();
        assertThat(pipeline(id).getMaterializedThroughVersion()).isNull();

        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        materialize(id, "{\"limit\":10}");

        assertThat(pipeline(id).getHistoryRederivedAt()).isNotNull();
    }

    @Test
    void pipelineMutationDiscardsTheRecordedPosition() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        final String id = createSqlPipeline();
        materialize(id, "{\"limit\":10}");
        assertThat(pipeline(id).getMaterializedThroughVersion()).isNotNull();

        // Any accepted mutation bumps `generation`, and the mark is read as absent once it has moved. Coarse
        // on purpose: enumerating which fields matter is the kind of list that goes stale silently, and a
        // version-column repoint proves it would have to include indirect causes too.
        mockMvc.perform(patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-pipeline\"," + sqlTransform() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"advanced\":{\"rows_per_scan\":1200},\"enabled\":true}"))
                .andExpect(status().isOk());

        final PipelineEntity mutated = pipeline(id);
        assertThat(mutated.getMaterializedGeneration()).isNotEqualTo(mutated.getGeneration());
    }

    /**
     * The discard line answers "why did the rollup freeze", so it must name both generations — and it must not
     * fire for a pipeline's first mark, which claims a generation where none was stored. Both facts come from
     * the one native statement that discards the mark, which is what this runs against real PostgreSQL for.
     */
    @Test
    void discardedPositionIsLoggedAndTheFirstMarkIsNot() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        final String id = createSqlPipeline();

        try (LogCapture log = LogCapture.of(MaterializePositionWriter.class)) {
            materialize(id, "{\"limit\":10}");
            materialize(id, "{\"limit\":10}");
            assertThat(log.messagesAt(Level.INFO)).noneMatch(line -> line.contains("position discarded"));

            final long before = pipeline(id).getGeneration();
            mockMvc.perform(patch("/v1/pipelines/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-pipeline\"," + sqlTransform() + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                    + "\"advanced\":{\"rows_per_scan\":1200},\"enabled\":true}"))
                    .andExpect(status().isOk());
            final long after = pipeline(id).getGeneration();
            materialize(id, "{\"limit\":10}");

            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.contains("position discarded"))
                    .containsExactly("pipeline=" + id + " position discarded: stored_gen=" + before + " -> gen=" + after);
            assertThat(pipeline(id).getMaterializedGeneration()).isEqualTo(after);
        }
    }

    /** The outcome line of a written window, carrying the write statement's own query id. */
    @Test
    void writtenWindowLogsItsOutcomeWithTheWritesQueryId() throws Exception {
        seedRow("11111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"gpt-4o\"}");
        final String id = createSqlPipeline();

        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            materialize(id, "{\"limit\":10}");

            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.startsWith("materialize pipeline="))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("pipeline=" + id + " gen=1 outcome=written rows=1 narrowed=false has_more=false")
                            .contains("query_id=adas-" + id + "-write-")
                            // No value from the row or the declaration's literals.
                            .doesNotContain("gpt-4o")
                            .doesNotContain("11111111-1111-1111-1111-111111111111"));
        }
    }

    private String materialize(String pipelineName, String body) throws Exception {
        return mockMvc.perform(runnerPost("/v1/pipelines/" + pipelineName + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private PipelineEntity pipeline(String pipelineName) {
        return pipelineRepository.findById(pipelineName).orElseThrow();
    }

    private Long positionOf(String pipelineName) {
        return pipeline(pipelineName).getMaterializedThroughVersion();
    }

    /**
     * Every distinct inferred-type pairing the collapsed type check has to settle, in <b>one</b> pipeline,
     * carried through <b>both</b> gates: the registration gate and the materialize call.
     *
     * <p>Both matter, and the second is the reason this is an integration test rather than more unit
     * cases. {@code MaterializeService#analyzeExpressions} re-validates every expression on <em>each</em>
     * materialize call — deliberately not trusting the registration gate, because a source's schema can
     * change under a stored pipeline — so the collapsed comparison runs on the hot path, per window,
     * forever. A pairing that passed registration and failed there would fail at runtime, not in CI.
     *
     * <p>Written as one output per <em>pairing</em> rather than one per live output variable. The
     * registry's own vars sample whichever pairings happen to exist today (mostly {@code string} on
     * {@code string}); the pairings are what the check actually decides between, and
     * {@code EnrichmentTypeMappingTest} proves the collapse safe over all of them exhaustively. Here they
     * are proved to survive the real SQL front end and the real {@code INSERT … SELECT}.
     */
    @Test
    void everyInferredTypePairingSurvivesRegistrationAndMaterialize() throws Exception {
        final String enrichment = "c1_types";
        IntegrationTables.enrichment(mockMvc, enrichment, SOURCE, "event_id",
                // string -> string, string -> ENUM (the one-way relation), integer -> LONG (numeric
                // widening), boolean -> boolean, timestamp -> timestamp, array -> array (shape only).
                "[{\"source_name\":\"plain\",\"name\":\"plain\",\"type\":\"string\"},"
                        + "{\"source_name\":\"domain\",\"name\":\"domain\",\"type\":\"enum\","
                        + "\"enum_values\":[\"d\",\"other\"]},"
                        + "{\"source_name\":\"widened\",\"name\":\"widened\",\"type\":\"long\"},"
                        + "{\"source_name\":\"flag\",\"name\":\"flag\",\"type\":\"boolean\"},"
                        + "{\"source_name\":\"stamp\",\"name\":\"stamp\",\"type\":\"timestamp\"},"
                        + "{\"source_name\":\"claims\",\"name\":\"claims\",\"type\":\"array\","
                        + "\"element_type\":\"string\"}]");
        try {
            seedRow("51111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000",
                    "{}", "{\"roles\":[\"admin\"]}", "chat-1");

            final String transform = Transforms.sql()
                    .output("plain", "lower(deployment)")
                    // A string result into an enum column: accepted, because an enum column holds strings
                    // and the domain is enforced by ClickHouse at insert. The relation is one-way, which
                    // the unit test pins separately.
                    .output("domain", "lower(deployment)")
                    // length() infers integer against a `long` column: numeric widening.
                    .output("widened", "length(deployment)")
                    // A boolean-returning FUNCTION, not a comparison: a comparison cannot appear in a
                    // Class-1 scalar expression, because the DSL keeps those to filters.
                    .output("flag", "not_empty(chat_id)")
                    .output("stamp", "date_trunc('day', request_time)")
                    .output("claims",
                            "json_extract_array(\\\"dial_usage_log_payload.jwt_claims\\\", 'roles')")
                    .member();

            // Gate one: registration. Six pairings, one 201.
            final String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-types-pipeline\"," + transform + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + enrichment + "\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.name");
            mockMvc.perform(get("/v1/pipelines/c1-types-pipeline?view=compiled"))
                    .andExpect(jsonPath("$.outputs.length()").value(6));

            // Gate two: the materialize call, which re-runs the same comparison per window.
            assertThat(JsonPath.read(materialize(id, "{}"), "$.rows_written").toString()).isEqualTo("1");

            // And the values actually landed in their columns, so the pairings are not merely accepted by
            // the gate but written by the engine — an element mismatch on the array, or a widening the
            // engine refused, would surface here rather than in a later production insert.
            mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entity\":\"" + SOURCE + "\",\"mode\":\"row\","
                                    + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\""
                                    + enrichment + ".domain\"}},"
                                    + "{\"expr\":{\"type\":\"field\",\"name\":\""
                                    + enrichment + ".widened\"}},"
                                    + "{\"expr\":{\"type\":\"field\",\"name\":\""
                                    + enrichment + ".flag\"}},"
                                    + "{\"expr\":{\"type\":\"field\",\"name\":\""
                                    + enrichment + ".claims\"}}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0]['" + enrichment + ".domain']").value("d"))
                    .andExpect(jsonPath("$.rows[0]['" + enrichment + ".widened']").value(1))
                    .andExpect(jsonPath("$.rows[0]['" + enrichment + ".flag']").value(true))
                    .andExpect(jsonPath("$.rows[0]['" + enrichment + ".claims']",
                            org.hamcrest.Matchers.contains("admin")));
        } finally {
            mockMvc.perform(delete("/v1/pipelines/c1-types-pipeline"));
            mockMvc.perform(delete("/v1/tables/" + enrichment));
        }
    }

    // ------------------------------------------- derivation expression functions

    /**
     * The workload this phase's expression functions were added for, end to end: one transform whose
     * output vars classify a row with a nested {@code if} and pull an array claim out of the
     * {@code object}-typed {@code dial_usage_log_payload.jwt_claims}, bound to a {@code string} and an {@code array<string>}
     * column, materialized and read back through the governed query surface.
     *
     * <p>The array binding is the part that could not be expressed at all before: {@code ExprSqlValidator}
     * rejected every array-kind result outright, so a Class-1 pipeline could not carry one whatever its target
     * column said.
     */
    @Test
    void materializeWritesConditionalAndArrayClaim() throws Exception {
        final String enrichment = "c1_derived";
        IntegrationTables.enrichment(mockMvc, enrichment, SOURCE, "event_id",
                "[{\"source_name\":\"kind\",\"name\":\"kind\",\"type\":\"string\"},"
                        + "{\"source_name\":\"roles\",\"name\":\"roles\",\"type\":\"array\","
                        + "\"element_type\":\"string\"}]");
        try {
            seedRow("41111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000",
                    "{}", "{\"roles\":[\"admin\",\"Reader\"]}", "chat-1");
            seedRow("42222222-2222-2222-2222-222222222222", "2020-01-01 00:00:02.000",
                    "{}", "{}", "");

            final String transform = Transforms.sql()
                    .output("kind", "if(not_empty(chat_id), 'chat', if(starts_with(deployment, 'd'),"
                            + " 'direct', 'other'))")
                    // `array` with no per-output type: the inferred type is checked directly against the
                    // column, which declares element_type itself.
                    .output("roles",
                            "json_extract_array(\\\"dial_usage_log_payload.jwt_claims\\\", 'roles')")
                    .member();

            String id = JsonPath.read(mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"c1-derive-pipeline\"," + transform + ","
                                    + "\"kind\":\"enrich\",\"target\":\"" + enrichment + "\","
                                    + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.name");

            mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows_written").value(2));

            mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                                    + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\""
                                    + enrichment + ".kind\"}},"
                                    + "{\"expr\":{\"type\":\"field\",\"name\":\""
                                    + enrichment + ".roles\"}}],"
                                    + "\"sort\":[{\"field\":\"event_id\",\"dir\":\"asc\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(2)))
                    // Row 1 has a chat id, so the outer branch wins; row 2 falls through to the nested if,
                    // whose deployment is 'd'.
                    .andExpect(jsonPath("$.rows[0]['" + enrichment + ".kind']").value("chat"))
                    .andExpect(jsonPath("$.rows[1]['" + enrichment + ".kind']").value("direct"))
                    // The array claim survives the object column, the INSERT … SELECT, and the read back —
                    // including its element case, which the extraction reads rather than folds.
                    .andExpect(jsonPath("$.rows[0]['" + enrichment + ".roles']")
                            .value(contains("admin", "Reader")))
                    .andExpect(jsonPath("$.rows[1]['" + enrichment + ".roles']", hasSize(0)));
        } finally {
            mockMvc.perform(delete("/v1/tables/" + enrichment));
        }
    }

    /**
     * An array-producing expression bound to a scalar column is refused at registration, not at insert.
     *
     * <p>This is now the <b>only</b> array refusal reachable from the wire. The companion test that sent a
     * parameterized {@code array<string>} type token was deleted with this change rather than rewritten:
     * an output declares no logical type at all any more, so there is no spelling of that token to refuse.
     * The refusal itself survives in {@code EnrichmentTypeMapping#resolve} and still guards a <em>stored</em>
     * old-shape row, which has no wire form to test through.
     */
    @Test
    void arrayExpressionBoundToScalarColumnIsRejected() throws Exception {
        expectPipelineRejected("c1_array_to_scalar",
                "json_extract_array(\\\"dial_usage_log_payload.jwt_claims\\\", 'roles')", "pipeline_validation_failed");
    }


    @Test
    void materializeHonoursTheLimitAndReportsMore() throws Exception {
        seedRow("31111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"a\"}");
        seedRow("32222222-2222-2222-2222-222222222222", "2020-01-01 00:00:02.000", "{\"model\":\"b\"}");
        String id = createSqlPipeline();

        String page = mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1))
                .andExpect(jsonPath("$.has_more").value(true))
                .andReturn().getResponse().getContentAsString();

        String cursor = JsonPath.read(page, "$.next_cursor");
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":\"" + cursor + "\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1))
                .andExpect(jsonPath("$.has_more").value(true));
        expectModels("a", "b");
    }

    @Test
    void materializeHonoursBoundedWindow() throws Exception {
        seedRow("41111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "{\"model\":\"early\"}");
        seedRow("42222222-2222-2222-2222-222222222222", "2020-01-01 00:00:05.000", "{\"model\":\"late\"}");
        String id = createSqlPipeline();

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2020-01-01T00:00:01Z\",\"to\":\"2020-01-01T00:00:05Z\","
                                + "\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1));
        // Only the row inside (00:00:01, 00:00:05] was derived; the earlier one has no enrichment row.
        expectModels(null, "late");
    }

    @Test
    void emptyWindowWritesNothingAndEchoesTheCursor() throws Exception {
        String id = createSqlPipeline();
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(0))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void unparseableBodyYieldsTheTypeDefault() throws Exception {
        seedRow("51111111-1111-1111-1111-111111111111", "2020-01-01 00:00:01.000", "not json");
        String id = createSqlPipeline();
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1));
        // Total by construction: the row is written with the expression's type default, not dead-lettered.
        expectModels("");
    }

    // ------------------------------------------------------------ sensitive posture (Track C)

    @Test
    void derivingFromSensitiveColumnNeitherFailsNorMarksTheTargetSensitive() throws Exception {
        // The pipeline's expression reads the sensitive request_body and writes a non-sensitive column. That
        // is accepted as registered: pipeline registration never mutates catalog metadata (a caller creating a
        // pipeline must not silently change who can read an existing column), and it is not rejected either.
        // Controlling the derived column's visibility is the table author's call, via the schema API.
        String id = createSqlPipeline();
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/tables/" + ENRICHMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name == 'body_model')].sensitive").value(hasItem(false)));
    }

    // ------------------------------------------------------------ path separation

    @Test
    void materializeOnAnLlmPipelineIsRejected() throws Exception {
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"c1-llm-pipeline\","
                                + Transforms.llm().output("body_model", "The model named in the body.")
                                        .member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(content, "$.name");

        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/materialize").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("materialize is the Class-1")));
    }

    @Test
    void scanOnSqlPipelineIsRejected() throws Exception {
        String id = createSqlPipeline();
        mockMvc.perform(runnerPost("/v1/pipelines/" + id + "/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("materialize")));
    }

    @Test
    void materializeOnAnUnknownPipelineIsNotFound() throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/no-such-pipeline/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_pipeline"));
    }
}
