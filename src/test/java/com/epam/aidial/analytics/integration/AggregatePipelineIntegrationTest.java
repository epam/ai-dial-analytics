package com.epam.aidial.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the aggregate pipeline against a real ClickHouse: register → preview → enable →
 * refresh, then query the rollup.
 *
 * <p>Three of these tests exist to pin the correctness contract rather than the happy path, because the
 * failure modes they cover are all <em>silent</em> — the statement runs, rows appear, and only the numbers
 * are wrong:
 *
 * <ul>
 *   <li><b>Totals, not increments</b> — a windowed partial aggregate would overwrite a bucket's running
 *       total with the newest slice, since the target replaces by key.</li>
 *   <li><b>Idempotence</b> — a repeated refresh must not change the rollup, which is what makes retries
 *       and multi-instance scheduling safe.</li>
 *   <li><b>Late data self-heals</b> — a row arriving into an already-materialized bucket must correct it,
 *       not add a second row for the same key.</li>
 * </ul>
 *
 * <p>Requires a running Docker daemon.
 */
class AggregatePipelineIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final String ROLLUP = "usage_daily";
    private static final String PIPELINE = "usage_daily_rollup";
    /** A second target/pipeline pair, used only by the nullable-group-key case. */
    private static final String NULLABLE_ROLLUP = "parent_daily";
    private static final String NULLABLE_PIPELINE = "parent_daily_rollup";
    /** A third pair, used only by the array-measure case: one row per chat_id, no time bucket. */
    private static final String ARRAY_ROLLUP = "chat_deployments";
    private static final String ARRAY_PIPELINE = "chat_deployments_rollup";
    /** A fourth pair, used only by the scoped-measure case: the conversations cost shape. */
    private static final String SCOPED_ROLLUP = "chat_costs";
    private static final String SCOPED_PIPELINE = "chat_costs_rollup";
    private static final String DISTINCT_ROLLUP = "chat_turns";
    private static final String DISTINCT_PIPELINE = "chat_turns_rollup";

    // `tokens` is nullable because it sums `dial_usage_log.total_tokens`, which is: a sum over a nullable
    // column returns NULL for a bucket holding no value, and a non-nullable target would store that as 0 —
    // indistinguishable from a measured zero. `call_count` is a count and can never be null.
    private static final String TARGET_COLUMNS = """
            [{"source_name":"deployment","name":"deployment","type":"string"},
             {"source_name":"day","name":"day","type":"timestamp"},
             {"source_name":"call_count","name":"call_count","type":"long"},
             {"source_name":"tokens","name":"tokens","type":"long","nullable":true}]""";

    private static final String DECLARATION = """
            "target":"usage_daily",
            "inputs":["dial_usage_log"],
            "group_by":[{"column":"deployment"},
                        {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
            "measures":[{"name":"call_count","fn":"count"},
                        {"name":"tokens","fn":"sum","column":"total_tokens"}],
            "trigger":{"kind":"schedule","cron":"0 0 * * * *"}""";

    @BeforeEach
    void cleanSlate() throws Exception {
        truncate();
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
        IntegrationTables.upsertSource(mockMvc, ROLLUP, TARGET_COLUMNS, "[\"deployment\",\"day\"]");
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
        // Only one test creates these; a 404 for the rest is harmless (no status assertion).
        mockMvc.perform(delete(PIPELINES + "/" + NULLABLE_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + NULLABLE_ROLLUP));
        mockMvc.perform(delete(PIPELINES + "/" + ARRAY_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ARRAY_ROLLUP));
        mockMvc.perform(delete(PIPELINES + "/" + SCOPED_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + SCOPED_ROLLUP));
        mockMvc.perform(delete(PIPELINES + "/" + DISTINCT_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + DISTINCT_ROLLUP));
        // The two scoped-rejection cases register no pipeline (the declaration is refused), but they do
        // create a target table — and dropping it here rather than inline means an assertion that fails
        // mid-test cannot leak it into the next class. A leaked source table is not a local failure: it is
        // discoverable, so QuerySchemaDiscoveryIntegrationTest's exact-entity-count assertion breaks in a
        // file nobody touched.
        mockMvc.perform(delete("/v1/tables/scoped_strict"));
        mockMvc.perform(delete("/v1/tables/scoped_bad"));
    }

    /** Seeds one input row carrying a chat_id, for the array-measure case. */
    private void seedChatEvent(String chatId, String deployment, int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, chat_id, deployment, request_time,"
                + " trace_id, core_span_id, total_tokens, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', '" + chatId + "', '" + deployment + "',"
                + " '2026-01-01 10:0" + seq + ":00.000', 't" + seq + "', 's" + seq + "', 10,"
                + " '2026-01-01 10:0" + seq + ":01.000')");
    }

    /**
     * Seeds one hop of a chain. {@code parentDeployment} null marks the hop that starts the chain (and so
     * the row a top-down cost measure must select); {@code deploymentPrice} null is what an orchestration
     * hop carries, since it incurs no model cost of its own. Prices are passed as SQL text so a null is a
     * real NULL rather than a zero.
     */
    private void seedChainHop(String chatId, String deployment, String parentDeployment,
            String deploymentPrice, String totalPrice, int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, chat_id, deployment, parent_deployment,"
                + " request_time, trace_id, core_span_id, deployment_price, total_price, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', '" + chatId + "', '" + deployment + "',"
                + (parentDeployment == null ? " NULL," : " '" + parentDeployment + "',")
                + " '2026-01-01 10:0" + seq + ":00.000', 't" + seq + "', 's" + seq + "',"
                + (deploymentPrice == null ? " NULL," : " " + deploymentPrice + ",")
                + (totalPrice == null ? " NULL," : " " + totalPrice + ",")
                + " '2026-01-01 10:0" + seq + ":01.000')");
    }

    /**
     * Seeds one hop of a turn. {@code traceId} is what identifies the turn, so several hops sharing one is
     * the fan-out a distinct count has to collapse; {@code seq} varies both {@code request_time} and
     * {@code core_span_id}, which keeps each row distinct under the table's ReplacingMergeTree sort key and
     * so stops the fan-out from collapsing at the engine level before a measure ever reads it.
     */
    private void seedTurnHop(String chatId, String traceId, boolean success, int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, chat_id, deployment, request_time,"
                + " trace_id, core_span_id, success, response_status, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', '" + chatId + "', 'model-a',"
                + " '2026-01-01 10:0" + seq + ":00.000', '" + traceId + "', 's" + seq + "',"
                + (success ? " true, 200," : " false, 500,")
                + " '2026-01-01 10:0" + seq + ":01.000')");
    }

    /** Registers the pipeline (always lands disabled) and returns nothing; failures assert inline. */
    private void register() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\"," + DECLARATION + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.kind").value("aggregate"));
    }

    /**
     * Registers {@code body}, previews it, deletes it, and returns the preview's result.
     *
     * <p>An {@code aggregate} registration lands disabled, so it is a draft and is stored ungated; the
     * declaration gate runs at preview (and at the patch that arms it), with the same status and code the
     * registration used to answer. So a misdeclaration is now asserted where the author meets it. The
     * pipeline is removed again so the next case can reuse its name and target.
     */
    private ResultActions previewOf(String body) throws Exception {
        final String name = body.replaceAll("(?s).*?\"name\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));
        final ResultActions preview = mockMvc.perform(post(PIPELINES + "/" + name + "/preview"));
        mockMvc.perform(delete(PIPELINES + "/" + name)).andExpect(status().isNoContent());
        return preview;
    }

    private void enable() throws Exception {
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.state.next_run_at").value(notNullValue()));
    }

    /** Seeds one input row. {@code seq} keeps the identity (event_id) unique per row. */
    private void seedEvent(String deployment, String day, long tokens, int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, total_tokens, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', '" + deployment + "', '" + day + " 10:0" + seq + ":00.000',"
                + " 't" + seq + "', 's" + seq + "', " + tokens + ", '" + day + " 10:0" + seq + ":01.000')");
    }

    private long refresh() throws Exception {
        final String body = mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"rows_written\":(\\d+).*", "$1"));
    }

    /** Queries the rollup through the public query surface, the way a consumer would. */
    private ResultActions queryRollup() throws Exception {
        return mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"entity":"usage_daily","mode":"row",
                         "select":[{"expr":{"type":"field","name":"deployment"}},
                                   {"expr":{"type":"field","name":"call_count"}},
                                   {"expr":{"type":"field","name":"tokens"}}],
                         "sort":[{"field":"deployment","dir":"asc"}],
                         "page":{"type":"offset","offset":0,"limit":25,"include_total":true}}"""));
    }

    @Test
    void previewReturnsTheStatementAndSampleWithoutWriting() throws Exception {
        register();
        seedEvent("gpt-4", "2026-01-01", 100, 1);

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statement").value(notNullValue()))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(1));

        // The gate must not write: the rollup is still empty after a preview.
        queryRollup().andExpect(jsonPath("$.rows", hasSize(0)));
    }

    @Test
    void previewCarriesThePortableStatementAndNoRefreshStatement() throws Exception {
        register();
        seedEvent("gpt-4", "2026-01-01", 100, 1);

        final String body = mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isOk())
                // The shared shape: no refresh statement, no sibling member, no declaration echo.
                .andExpect(jsonPath("$.sample_statement").doesNotExist())
                .andExpect(jsonPath("$.group_by").doesNotExist())
                .andExpect(jsonPath("$.measures").doesNotExist())
                .andExpect(jsonPath("$.enrich").doesNotExist())
                .andExpect(jsonPath("$.total_rows").value(1))
                .andExpect(jsonPath("$.notes", hasSize(0)))
                // Each key and measure typed, with its target's type; the helper columns are not among them.
                .andExpect(jsonPath("$.columns[*].name", contains("deployment", "day", "call_count", "tokens")))
                .andExpect(jsonPath("$.columns[*].role", contains("key", "key", "measure", "measure")))
                .andExpect(jsonPath("$.columns[*].type", contains("string", "timestamp", "long", "long")))
                .andExpect(jsonPath("$.columns[*].target_type", contains("string", "timestamp", "long", "long")))
                .andExpect(jsonPath("$.rows[0].__latest_v").doesNotExist())
                .andExpect(jsonPath("$.rows[0].__latest_id").doesNotExist())
                .andExpect(jsonPath("$.rows[0].__total_groups").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // The declaration as the query API runs it: the catalog entity and the target columns, ordered by the keys,
        // with none of the sample's helpers, window or settings.
        assertThat(readStatement(body)).isEqualTo("SELECT \"deployment\", date_trunc('day', \"request_time\") AS \"day\","
                + " count(*) AS \"call_count\", sum(\"total_tokens\") AS \"tokens\" FROM \"dial_usage_log\" FINAL"
                + " GROUP BY \"deployment\", \"day\" ORDER BY \"deployment\" ASC, \"day\" ASC LIMIT 5");
    }

    @Test
    void drainedPipelineStillPreviewsItsWholeInput() throws Exception {
        // Once the cursor reaches the end of the input a refresh selects nothing, while the sample still
        // reports the declaration's totals over everything: preview answers "are these the right numbers",
        // and where the pipeline is belongs to the read's `state`.
        register();
        enable();
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        refresh();
        assertThat(refresh()).as("the pipeline is drained: a second refresh writes nothing").isZero();

        final String body = mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(1))
                .andExpect(jsonPath("$.rows[0].tokens").value(100))
                .andExpect(jsonPath("$.total_rows").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(readStatement(body)).as("the sample is unwindowed").doesNotContain("__cursor");
    }

    @Test
    void thePreviewStatementRunsThroughTheQueryApiAndAnswersTheSampledGroups() throws Exception {
        // Otherwise the statement is decorative: copied out of the response, it runs unchanged on execute-sql and,
        // with every group inside the sample, answers exactly the sampled groups.
        register();
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        seedEvent("gpt-4", "2026-01-01", 40, 2);

        final String body = mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].tokens").value(140))
                .andReturn().getResponse().getContentAsString();

        final JsonNode sampled = PreviewStatements.body(body).get("rows");
        assertThat(PreviewStatements.runStatement(mockMvc, body)).isEqualTo(sampled);
        assertThat(PreviewStatements.runQuery(mockMvc, body)).isEqualTo(sampled);
        assertThat(PreviewStatements.translateStatement(mockMvc, body)).isEqualTo(PreviewStatements.body(body).get("query"));
    }

    @Test
    void statementCarriesFilterAndMeasurePredicateAndOrdersByKeys() throws Exception {
        seedEvent("alpha", "2026-01-01", 10, 1);
        seedEvent("omega", "2026-01-03", 20, 2);
        seedEvent("alpha", "2026-01-01", 30, 3);
        seedEvent("excluded", "2026-01-02", 99, 4);

        final String body = previewOf("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\","
                + "\"filter\":\"deployment <> 'excluded'\"," + DECLARATION.replace(
                        "{\"name\":\"tokens\",\"fn\":\"sum\",\"column\":\"total_tokens\"}",
                        "{\"name\":\"tokens\",\"fn\":\"sum\",\"column\":\"total_tokens\",\"where\":\"total_tokens > 15\"}")
                + "}")
                .andExpect(status().isOk())
                // Newest first in the sample; by key in the statement.
                .andExpect(jsonPath("$.rows[*].deployment", contains("omega", "alpha")))
                .andReturn().getResponse().getContentAsString();

        assertThat(readStatement(body)).contains("WHERE \"deployment\" <> 'excluded'")
                .contains("sum(CASE WHEN \"total_tokens\" > 15 THEN \"total_tokens\" END) AS \"tokens\"");
        final JsonNode rows = PreviewStatements.runStatement(mockMvc, body);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("deployment").asText()).isEqualTo("alpha");
        assertThat(rows.get(0).get("call_count").asLong()).isEqualTo(2L);
        assertThat(rows.get(0).get("tokens").asLong()).isEqualTo(30L);
        assertThat(rows.get(1).get("deployment").asText()).isEqualTo("omega");
        assertThat(rows.get(1).get("tokens").asLong()).isEqualTo(20L);
    }

    @Test
    void bucketNamedLikeInputFieldPreviewsWithoutStatement() throws Exception {
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
        IntegrationTables.upsertSource(mockMvc, ROLLUP, TARGET_COLUMNS.replace(
                "{\"source_name\":\"day\",\"name\":\"day\"", "{\"source_name\":\"request_time\",\"name\":\"request_time\""),
                "[\"deployment\",\"request_time\"]");

        previewOf("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\"," + DECLARATION.replace(
                        "\"as\":\"day\"", "\"as\":\"request_time\"") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.total_rows").value(1))
                .andExpect(jsonPath("$.statement").doesNotExist())
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.notes[?(@.code == 'statement_not_portable')].subject", contains("request_time")));
    }

    @Test
    void theSampleShowsTheMostRecentlyActiveGroupsFirstAndCountsThemAll() throws Exception {
        register();
        // By key, `alpha` would sort first; by latest input row it is the oldest group.
        seedEvent("alpha", "2026-01-01", 1, 1);
        seedEvent("omega", "2026-01-03", 1, 2);
        seedEvent("mid", "2026-01-02", 1, 3);
        seedEvent("alpha", "2026-01-02", 1, 4);

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].deployment", contains("omega", "alpha", "mid", "alpha")))
                .andExpect(jsonPath("$.total_rows").value(4));
    }

    @Test
    void anEmptyInputIsExplainedWithZeroRows() throws Exception {
        register();

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.total_rows").value(0))
                // The header still renders: columns are present for an empty sample.
                .andExpect(jsonPath("$.columns", hasSize(4)))
                .andExpect(jsonPath("$.notes[0].code").value("no_matching_rows"))
                .andExpect(jsonPath("$.notes[0].severity").value("warning"));
    }

    /**
     * The two coercion notes against a target declaring narrower types than the measures yield: a {@code count}
     * into {@code integer} narrows without any sampled value out of range, a {@code sum} over a {@code long}
     * into {@code integer} narrows and, past 2^31, holds a value the insert would wrap — and a day bucket into a
     * {@code date} is lossless, so it is neither.
     */
    @Test
    void previewWarnsOfNarrowingBindingsAndValuesTheTargetCannotHold() throws Exception {
        IntegrationTables.upsertSource(mockMvc, "agg_narrow", """
                [{"source_name":"deployment","name":"deployment","type":"string"},
                 {"source_name":"day","name":"day","type":"date"},
                 {"source_name":"call_count","name":"call_count","type":"integer"},
                 {"source_name":"tokens","name":"tokens","type":"integer","nullable":true}]""",
                "[\"deployment\",\"day\"]");
        try {
            mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content("""
                            {"name":"agg-narrow","kind":"aggregate","target":"agg_narrow",
                             "inputs":["dial_usage_log"],
                             "group_by":[{"column":"deployment"},
                                         {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
                             "measures":[{"name":"call_count","fn":"count"},
                                         {"name":"tokens","fn":"sum","column":"total_tokens"}],
                             "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""))
                    .andExpect(status().isCreated());
            seedEvent("gpt-4", "2026-01-01", 2_000_000_000L, 1);
            seedEvent("gpt-4", "2026-01-01", 2_000_000_000L, 2);

            mockMvc.perform(post(PIPELINES + "/agg-narrow/preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns[?(@.name=='day')].type", contains("date")))
                    // The cell matches the type the column reports: a calendar date, not a midnight timestamp.
                    .andExpect(jsonPath("$.rows[0].day").value("2026-01-01"))
                    .andExpect(jsonPath("$.notes[?(@.code=='target_type_narrowing')].subject",
                            containsInAnyOrder("call_count", "tokens")))
                    .andExpect(jsonPath("$.notes[?(@.code=='value_out_of_target_range')].subject",
                            contains("tokens")))
                    .andExpect(jsonPath("$.notes[?(@.subject=='day')]", hasSize(0)));
        } finally {
            mockMvc.perform(delete(PIPELINES + "/agg-narrow"));
            mockMvc.perform(delete("/v1/tables/agg_narrow"));
        }
    }

    /** The `statement` member, unescaped, straight out of the response body. */
    private static String readStatement(String body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("statement").asText();
    }

    @Test
    void disabledPipelineCannotBeRefreshed() throws Exception {
        register();
        seedEvent("gpt-4", "2026-01-01", 100, 1);

        // `disabled` has to mean "cannot write", or the preview gate is decorative: an operator could skip
        // straight past it and fill the target from a declaration nobody has looked at.
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("pipeline_conflict"));
        queryRollup().andExpect(jsonPath("$.rows", hasSize(0)));
    }

    @Test
    void reDeclaringResetsTheCursorSoTheRollupIsRebuilt() throws Exception {
        register();
        enable();
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        refresh();
        queryRollup().andExpect(jsonPath("$.rows[0].tokens").value(100));

        // Disable, change the measure, re-enable. Keeping the cursor would confine the new declaration to
        // input that has not been read yet, leaving this bucket holding a value the old declaration produced
        // — forever, and with nothing in the target saying so.
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + DECLARATION.replace("\"fn\":\"sum\",\"column\":\"total_tokens\"",
                                "\"fn\":\"max\",\"column\":\"total_tokens\"") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.cursor_version").doesNotExist())
                .andExpect(jsonPath("$.state.has_more").value(false));

        seedEvent("gpt-4", "2026-01-01", 40, 2);
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        refresh();

        // max over both rows, not a sum and not only the row that arrived after the old cursor.
        queryRollup()
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(2))
                .andExpect(jsonPath("$.rows[0].tokens").value(100));
    }

    @Test
    void refreshMaterializesTheRollupAndItIsQueryable() throws Exception {
        register();
        enable();
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        seedEvent("gpt-4", "2026-01-01", 50, 2);
        seedEvent("claude", "2026-01-01", 7, 3);

        refresh();

        queryRollup()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].deployment").value("claude"))
                .andExpect(jsonPath("$.rows[0].call_count").value(1))
                .andExpect(jsonPath("$.rows[0].tokens").value(7))
                .andExpect(jsonPath("$.rows[1].deployment").value("gpt-4"))
                .andExpect(jsonPath("$.rows[1].call_count").value(2))
                .andExpect(jsonPath("$.rows[1].tokens").value(150));
    }

    @Test
    void repeatedRefreshLeavesTheRollupUnchanged() throws Exception {
        register();
        enable();
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        seedEvent("gpt-4", "2026-01-01", 50, 2);
        refresh();

        // A second refresh with no new input: the window is empty, so nothing is written and the rollup is
        // identical. This is what makes a retry — or two instances ticking at once — safe.
        assertThat(refresh()).isZero();
        queryRollup()
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(2))
                .andExpect(jsonPath("$.rows[0].tokens").value(150))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    void lateRowCorrectsItsBucketRatherThanDoubleCounting() throws Exception {
        register();
        enable();
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        refresh();
        queryRollup().andExpect(jsonPath("$.rows[0].call_count").value(1));

        // A row lands in the already-materialized bucket. The refresh must recompute the WHOLE bucket
        // (2 calls / 150 tokens), not add the increment to a second row for the same key.
        seedEvent("gpt-4", "2026-01-01", 50, 2);
        refresh();

        queryRollup()
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(2))
                .andExpect(jsonPath("$.rows[0].tokens").value(150));
    }

    @Test
    void refreshAdvancesTheCursorAndReportsState() throws Exception {
        register();
        enable();
        seedEvent("gpt-4", "2026-01-01", 100, 1);
        refresh();

        mockMvc.perform(get(PIPELINES + "/" + PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.cursor_version").value(notNullValue()))
                .andExpect(jsonPath("$.state.cursor_identity").value(notNullValue()))
                .andExpect(jsonPath("$.state.last_run_at").value(notNullValue()))
                .andExpect(jsonPath("$.state.lag_seconds").value(notNullValue()))
                .andExpect(jsonPath("$.state.last_error").doesNotExist())
                // A run that did not fill its window limit is caught up, so the backlog flag is false —
                // which is what distinguishes "lag is just the safety margin" from "still draining".
                .andExpect(jsonPath("$.state.has_more").value(false));
    }

    @Test
    void deletingTheTargetTableRemovesThePipelineWithIt() throws Exception {
        register();

        // A pipeline exists only to fill its target, so it goes with it (FK cascade) rather than surviving
        // as a process pointing at nothing.
        mockMvc.perform(delete("/v1/tables/" + ROLLUP))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(PIPELINES + "/" + PIPELINE)).andExpect(status().isNotFound());
    }

    // The mirror case — deleting a pipeline's INPUT is refused with 409 — is covered in
    // TableCatalogServiceTest rather than here. It is unreachable through the API today: a pipeline input
    // must be a scannable source, scan sources are configured only by migration, and every such table is
    // `system`-owned, so its delete is already refused earlier with `table_is_system`. The guard is kept
    // for the moment a non-system scan source exists.

    @Test
    void governanceRejectsMisdeclarations() throws Exception {
        // An append target would accumulate a row per refresh instead of replacing the key.
        IntegrationTables.source(mockMvc, "append_target",
                "[{\"source_name\":\"deployment\",\"name\":\"deployment\",\"type\":\"string\"},"
                        + "{\"source_name\":\"call_count\",\"name\":\"call_count\",\"type\":\"long\"}]",
                "[\"deployment\"]");
        previewOf("""
                                {"name":"bad","kind":"aggregate","target":"append_target",
                                 "inputs":["dial_usage_log"],
                                 "group_by":[{"column":"deployment"}],
                                 "measures":[{"name":"call_count","fn":"count"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}""")
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(delete("/v1/tables/append_target"));

        // group_by must equal the target's ordering_key, in order.
        previewOf("""
                                {"name":"bad","kind":"aggregate","target":"usage_daily",
                                 "inputs":["dial_usage_log"],
                                 "group_by":[{"column":"deployment"}],
                                 "measures":[{"name":"call_count","fn":"count"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}""")
                .andExpect(status().isUnprocessableContent());

        // `freshness` is no longer a field at all, so it is refused at the BINDING with a 400 naming it —
        // not this capability's 422. That is the deliberate difference: a member no kind declares cannot be
        // attributed to a kind, and accepting a spelling the read never echoes is the silent mis-answer
        // strict request parsing exists to prevent. Whatever mode it carries is beside the point.
        for (final String mode : new String[] {"periodic", "incremental"}) {
            mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"bad\",\"kind\":\"aggregate\"," + DECLARATION
                                    + ",\"freshness\":{\"mode\":\"" + mode + "\"}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"))
                    .andExpect(jsonPath("$.message").value(containsString("freshness")));
        }

        // The bucket unit no longer has a declared-but-unsupported member to exercise here: `month` is
        // implemented, and every other unit in the vocabulary always was. A unit outside the vocabulary
        // (`year`) is a binding failure by design, so it is a 400 rather than this capability's 422 — the
        // "declared but not implemented reports under our own code" rule stays covered by the `incremental`
        // freshness case above and the `enrich` kind case below.

        // Likewise a kind the registry models but does not run yet. `external` rather than `enrich`, which
        // became implemented when the two registries merged — an aggregate declaration sent under it is now
        // refused for carrying the other kind's fields, which the case below pins.
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"kind\":\"external\"," + DECLARATION + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("not implemented")));

        // An aggregate declaration sent under the enrich kind: refused for the fields, naming them, rather
        // than silently storing an enrich pipeline with the aggregate half discarded.
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"kind\":\"enrich\"," + DECLARATION + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("group_by")))
                .andExpect(jsonPath("$.message").value(containsString("measures")));

        // A `distinct` quantifier the function does not take. Grouped with the other misdeclarations because
        // it is the same class of failure and reports under the same code — the quantifier introduces no new
        // error code, so a client already handling these needs no change.
        previewOf("{\"name\":\"bad\",\"kind\":\"aggregate\"," + DECLARATION
                                .replace("{\"name\":\"tokens\",\"fn\":\"sum\",\"column\":\"total_tokens\"}",
                                        "{\"name\":\"tokens\",\"fn\":\"min\",\"column\":\"total_tokens\","
                                                + "\"distinct\":true}") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("does not accept it")));

        // And a distinct count with no column to deduplicate.
        previewOf("{\"name\":\"bad\",\"kind\":\"aggregate\"," + DECLARATION
                                .replace("{\"name\":\"call_count\",\"fn\":\"count\"}",
                                        "{\"name\":\"call_count\",\"fn\":\"count\",\"distinct\":true}") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("with no column")));
    }

    /**
     * A declaration whose predicate reads the clock, refused end to end through the real validator chain.
     *
     * <p>The query surfaces accept {@code now()} freely — a moving window is what makes a saved query
     * useful — so an aggregate declaration has to refuse it on its own terms. A scheduled refresh
     * recomputes every affected group key <b>in full</b>, so a filter moving with the clock would recompute
     * an already-published historical bucket over a different row set and silently rewrite a number
     * somebody has read and cited, with no error to point at afterwards. Time scoping belongs in the group
     * key and the refresh window, both of which are recorded.
     *
     * <p>Asserted here rather than in {@code AggregateSpecValidatorTest}, which stubs
     * {@code FilterSqlValidator} — where this rule actually lives — so a clock would pass the stub.
     */
    @Test
    void declarationWhosePredicateReadsTheClockIsRefused() throws Exception {
        for (final String predicate : List.of(
                "request_time >= now() - INTERVAL 7 DAY",
                "request_time >= date_sub('day', 7, now())")) {
            previewOf("{\"name\":\"bad\",\"kind\":\"aggregate\",\"filter\":\"" + predicate + "\","
                                    + DECLARATION + "}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                    .andExpect(jsonPath("$.message").value(containsString("non-deterministic function 'now'")));
        }

        // A measure's own row-membership predicate goes through the same gate.
        previewOf("{\"name\":\"bad\",\"kind\":\"aggregate\"," + DECLARATION
                                .replace("{\"name\":\"call_count\",\"fn\":\"count\"}",
                                        "{\"name\":\"call_count\",\"fn\":\"count\","
                                                + "\"where\":\"request_time >= now()\"}") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("non-deterministic function 'now'")));

        // Registration no longer stops such a declaration — an aggregate registration is a draft — so the
        // preview is where the author meets the refusal, and the patch that arms it refuses the same way.

        // It is the clock that is refused, not date arithmetic: the same functions over a column register.
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\","
                                + "\"filter\":\"date_sub('day', 1, request_time) < _ingested_at\","
                                + DECLARATION + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void nullableMeasureIntoNonNullableTargetIsRefusedAndNullSurvivesWhenDeclaredNullable() throws Exception {
        // The zeros case, end to end. `dial_usage_log.total_tokens` is Nullable(UInt32), so a bucket whose
        // rows all carry NULL sums to NULL — which ClickHouse converts to 0 on the way into a non-nullable
        // column. A stored 0 cannot be told from a measured one, so "no token data for this deployment-day"
        // would be read, believed, and summed into a report as "this deployment-day used zero tokens".
        IntegrationTables.upsertSource(mockMvc, "strict_daily",
                """
                        [{"source_name":"deployment","name":"deployment","type":"string"},
                         {"source_name":"day","name":"day","type":"timestamp"},
                         {"source_name":"tokens","name":"tokens","type":"long"}]""",
                "[\"deployment\",\"day\"]");
        previewOf("""
                                {"name":"strict","kind":"aggregate","target":"strict_daily",
                                 "inputs":["dial_usage_log"],
                                 "group_by":[{"column":"deployment"},
                                             {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
                                 "measures":[{"name":"tokens","fn":"sum","column":"total_tokens"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("nullable input column 'total_tokens'")));
        mockMvc.perform(delete("/v1/tables/strict_daily"));

        // With the target column declared nullable, the same bucket stores NULL — the honest answer, and
        // the one preview showed all along.
        register();
        enable();
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, total_tokens, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', 'no-tokens', '2026-01-01 10:00:00.000', 't9', 's9', NULL,"
                + " '2026-01-01 10:00:01.000')");
        refresh();

        queryRollup()
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].call_count").value(1))
                .andExpect(jsonPath("$.rows[0].tokens").value(nullValue()));
    }

    @Test
    void enabledPipelineRefusesDeclarationEdit() throws Exception {
        register();
        enable();

        // A state conflict, so 409 — the declaration itself may be fine, only the timing is not.
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + DECLARATION.replace("\"fn\":\"count\"", "\"fn\":\"min\"") + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("pipeline_conflict"));
    }

    @Test
    void nullableGroupKeyNeedsDeclaredFilter() throws Exception {
        // `parent_deployment` is one of the eleven dial_usage_log columns that are physically Nullable(...),
        // so a NULL is real input here. It cannot be represented in the rollup: the group keys ARE the
        // target's ordering_key, and ClickHouse refuses a nullable sorting key — so the engine would store ''
        // and the rollup would carry a parent deployment nobody ran. Refused unless the declaration says what
        // happens to the NULLs.
        IntegrationTables.upsertSource(mockMvc, NULLABLE_ROLLUP, """
                [{"source_name":"parent_deployment","name":"parent_deployment","type":"string"},
                 {"source_name":"day","name":"day","type":"timestamp"},
                 {"source_name":"call_count","name":"call_count","type":"long"}]""",
                "[\"parent_deployment\",\"day\"]");
        final String declaration = """
                "target":"%s",
                "inputs":["dial_usage_log"],
                "group_by":[{"column":"parent_deployment"},
                            {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
                "measures":[{"name":"call_count","fn":"count"}],
                "trigger":{"kind":"schedule","cron":"0 0 * * * *"}""".formatted(NULLABLE_ROLLUP);

        previewOf("{\"name\":\"" + NULLABLE_PIPELINE + "\",\"kind\":\"aggregate\","
                                + declaration + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("parent_deployment IS NOT NULL")));

        // The filter is the escape hatch, and the whole reason the rule is "no filter" rather than "no
        // nullable key": rejecting outright would leave the correct declaration inexpressible.
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + NULLABLE_PIPELINE + "\",\"kind\":\"aggregate\","
                                + "\"filter\":\"parent_deployment IS NOT NULL\"," + declaration + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filter").value("parent_deployment IS NOT NULL"));
    }

    @Test
    void arrayMeasureCollectsTheGroupsDistinctValuesSorted() throws Exception {
        // The conversations shape: one row per chat_id carrying every deployment that handled any hop of it.
        // Worth an end-to-end case because everything that could go wrong here is downstream of translation —
        // whether ClickHouse accepts the rendered aggregate, whether the array survives the INSERT into an
        // Array(String) column, and whether the query surface returns it as a JSON array.
        IntegrationTables.upsertSource(mockMvc, ARRAY_ROLLUP, """
                [{"source_name":"chat_id","name":"chat_id","type":"string"},
                 {"source_name":"turn_count","name":"turn_count","type":"long"},
                 {"source_name":"deployments","name":"deployments","type":"array","element_type":"string"}]""",
                "[\"chat_id\"]");
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","kind":"aggregate",
                                 "target":"%s",
                                 "inputs":["dial_usage_log"],
                                 "filter":"length(chat_id) > 0",
                                 "group_by":[{"column":"chat_id"}],
                                 "measures":[{"name":"turn_count","fn":"count"},
                                             {"name":"deployments","fn":"group_uniq_array","column":"deployment"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""
                                .formatted(ARRAY_PIPELINE, ARRAY_ROLLUP)))
                .andExpect(status().isCreated());
        mockMvc.perform(patch(PIPELINES + "/" + ARRAY_PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        // Two turns of one conversation, three hops, two of them on the same deployment: the set is the
        // point — 3 rows in, 2 distinct deployments out, ascending.
        seedChatEvent("c1", "zeta-model", 1);
        seedChatEvent("c1", "alpha-model", 2);
        seedChatEvent("c1", "alpha-model", 3);

        assertThat(mockMvc.perform(post(PIPELINES + "/" + ARRAY_PIPELINE + "/refresh"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"rows_written\":1");

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"chat_id"}},
                                           {"expr":{"type":"field","name":"turn_count"}},
                                           {"expr":{"type":"field","name":"deployments"}}],
                                 "page":{"type":"offset","offset":0,"limit":25}}""".formatted(ARRAY_ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].turn_count").value(3))
                .andExpect(jsonPath("$.rows[0].deployments", hasSize(2)))
                // Sorted, not insertion-ordered: this is what makes a recompute of unchanged rows write the
                // identical value, which the idempotence case below depends on.
                .andExpect(jsonPath("$.rows[0].deployments[0]").value("alpha-model"))
                .andExpect(jsonPath("$.rows[0].deployments[1]").value("zeta-model"));

        // A late hop on a new deployment corrects the set rather than adding a second row for the key.
        seedChatEvent("c1", "mid-model", 4);
        mockMvc.perform(post(PIPELINES + "/" + ARRAY_PIPELINE + "/refresh")).andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"deployments"}}],
                                 "page":{"type":"offset","offset":0,"limit":25}}""".formatted(ARRAY_ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].deployments", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].deployments[1]").value("mid-model"));
    }

    @Test
    void failedRefreshIsVisibleInTheStateView() throws Exception {
        register();
        enable();
        seedEvent("gpt-4", "2026-01-01", 100, 1);

        // Drop the target out from under the pipeline: the catalog row is gone, so the next refresh fails.
        // Whatever the cause, an operator has to be able to see it without reading logs — and the on-demand
        // route has to leave the same trace the scheduler does.
        execute("DROP TABLE IF EXISTS " + ROLLUP);
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get(PIPELINES + "/" + PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.last_error").value(notNullValue()))
                // The cursor stays put, so the failed window is simply re-run.
                .andExpect(jsonPath("$.state.cursor_version").doesNotExist());
    }

    @Test
    void scopedMeasureAggregatesOnlyItsMatchingRowsAndNullsAnEmptyScope() throws Exception {
        // The declaration that motivated per-measure predicates, end to end. One conversation is one chat_id
        // spanning a chain of hops: the hop that *starts* a chain carries the whole chain's cost in
        // total_price and no cost of its own, while each leaf carries only its own. So the two honest cost
        // figures read different rows of the same group, and before `where` there was no way to declare that
        // — the pipeline `filter` is one predicate over every measure.
        //
        // Worth an end-to-end case because everything that could go wrong is downstream of translation: the
        // unit tests assert the rendered SQL contains a CASE, but only ClickHouse can say whether it
        // executes, whether the aggregate really skips the non-matching rows, and — the load-bearing part —
        // whether an empty scope arrives as NULL rather than as the 0 a `sumIf` would have produced.
        IntegrationTables.upsertSource(mockMvc, SCOPED_ROLLUP, """
                [{"source_name":"chat_id","name":"chat_id","type":"string"},
                 {"source_name":"hops","name":"hops","type":"long"},
                 {"source_name":"root_turns","name":"root_turns","type":"long"},
                 {"source_name":"hop_price","name":"hop_price","type":"decimal","nullable":true},
                 {"source_name":"chain_price","name":"chain_price","type":"decimal","nullable":true}]""",
                "[\"chat_id\"]");
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","kind":"aggregate",
                                 "target":"%s",
                                 "inputs":["dial_usage_log"],
                                 "filter":"length(chat_id) > 0",
                                 "group_by":[{"column":"chat_id"}],
                                 "measures":[{"name":"hops","fn":"count"},
                                             {"name":"root_turns","fn":"count",
                                              "where":"parent_deployment IS NULL"},
                                             {"name":"hop_price","fn":"sum","column":"deployment_price"},
                                             {"name":"chain_price","fn":"sum","column":"total_price",
                                              "where":"parent_deployment IS NULL"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""
                                .formatted(SCOPED_PIPELINE, SCOPED_ROLLUP)))
                .andExpect(status().isCreated());

        // The predicate has to survive the round-trip through the stored spec, or a refresh would silently
        // widen the measure back to the whole group.
        mockMvc.perform(get(PIPELINES + "/" + SCOPED_PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measures[3].where").value("parent_deployment IS NULL"));

        // Chain c1: one root hop carrying the chain total (10.0) and no own cost, plus two leaves at 4.0
        // each. Bottom-up is 8.0 and top-down is 10.0 — deliberately different, which is the divergence the
        // two columns exist to expose. Chain c2: a leaf with no root hop at all, so its scope is empty.
        seedChainHop("c1", "orchestrator", null, null, "10.0", 1);
        seedChainHop("c1", "model-a", "orchestrator", "4.0", "4.0", 2);
        seedChainHop("c1", "model-b", "orchestrator", "4.0", "4.0", 3);
        seedChainHop("c2", "model-a", "orchestrator", "7.5", "7.5", 4);

        mockMvc.perform(patch(PIPELINES + "/" + SCOPED_PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        assertThat(mockMvc.perform(post(PIPELINES + "/" + SCOPED_PIPELINE + "/refresh"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"rows_written\":2");

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"chat_id"}},
                                           {"expr":{"type":"field","name":"hops"}},
                                           {"expr":{"type":"field","name":"root_turns"}},
                                           {"expr":{"type":"field","name":"hop_price"}},
                                           {"expr":{"type":"field","name":"chain_price"}}],
                                 "sort":[{"field":"chat_id","dir":"asc"}],
                                 "page":{"type":"offset","offset":0,"limit":25}}""".formatted(SCOPED_ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                // c1: the unscoped measures still see every hop of the group — which is the whole point of
                // putting the predicate on the measure rather than on the pipeline.
                .andExpect(jsonPath("$.rows[0].chat_id").value("c1"))
                .andExpect(jsonPath("$.rows[0].hops").value(3))
                // Scoped count: one of the three hops starts a chain. A `count` over a CASE counts the
                // non-null values, so this is the count of matching rows.
                .andExpect(jsonPath("$.rows[0].root_turns").value(1))
                // Bottom-up: the two leaves' own costs. The root's NULL own-cost is skipped, not zeroed.
                .andExpect(jsonPath("$.rows[0].hop_price").value(8.0))
                // Top-down: the root hop alone. Different from bottom-up by construction, and it could only
                // be this number if the CASE really excluded the two leaves — summing all three total_price
                // values would give 18.0, which is the double-count this column exists to be checked against.
                .andExpect(jsonPath("$.rows[0].chain_price").value(10.0))
                // c2 is the empty-scope case, and the assertion the nullable-target rule exists for: no row
                // of this group starts a chain, so there is nothing to sum. NULL is the honest answer;
                // `sumIf` would have reported 0.0 here, indistinguishable from a chain that really was free.
                .andExpect(jsonPath("$.rows[1].chat_id").value("c2"))
                .andExpect(jsonPath("$.rows[1].hops").value(1))
                // count, by contrast, is exempt: zero matching rows genuinely is a count of zero.
                .andExpect(jsonPath("$.rows[1].root_turns").value(0))
                .andExpect(jsonPath("$.rows[1].hop_price").value(7.5))
                .andExpect(jsonPath("$.rows[1].chain_price").value(nullValue()));

        // Idempotence survives scoping: the recompute-and-collapse write is only a no-op when a re-run over
        // unchanged rows produces the identical value, and a CASE is deterministic where an arbitrary
        // tie-break would not be.
        mockMvc.perform(post(PIPELINES + "/" + SCOPED_PIPELINE + "/refresh")).andExpect(status().isOk());
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"chain_price"}}],
                                 "sort":[{"field":"chat_id","dir":"asc"}],
                                 "page":{"type":"offset","offset":0,"limit":25}}""".formatted(SCOPED_ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].chain_price").value(10.0))
                .andExpect(jsonPath("$.rows[1].chain_price").value(nullValue()));
    }

    @Test
    void distinctMeasureCountsEntitiesExactlyAndScopesToZeroNotNull() throws Exception {
        // The declaration that motivated the `distinct` quantifier, end to end. The input is one row per hop
        // and one turn is one trace_id, so a turn count over it must count distinct values — the property no
        // `where` can express, because which hop of a trace represents it is a property of the group.
        //
        // Worth an end-to-end case for two reasons the unit tests structurally cannot cover. First, exactness:
        // ClickHouse rewrites count(DISTINCT x) per the `count_distinct_implementation` setting, whose default
        // uniqExact is exact but whose alternatives are approximate — so a materialized column could silently
        // become an estimate with no application-level symptom. Asserting the written value against a known
        // distinct count is what turns that into a build failure. Second, the scoped form's empty answer: the
        // target columns below are deliberately NOT nullable, so this only passes if a distinct count over an
        // all-NULL CASE really is 0 rather than NULL — the claim that keeps this a pipeline patch with no
        // schema patch on an existing rollup's target.
        IntegrationTables.upsertSource(mockMvc, DISTINCT_ROLLUP, """
                [{"source_name":"chat_id","name":"chat_id","type":"string"},
                 {"source_name":"hops","name":"hops","type":"long"},
                 {"source_name":"turns","name":"turns","type":"long"},
                 {"source_name":"ok_turns","name":"ok_turns","type":"long"}]""",
                "[\"chat_id\"]");
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","kind":"aggregate",
                                 "target":"%s",
                                 "inputs":["dial_usage_log"],
                                 "filter":"length(chat_id) > 0",
                                 "group_by":[{"column":"chat_id"}],
                                 "measures":[{"name":"hops","fn":"count"},
                                             {"name":"turns","fn":"count","column":"trace_id",
                                              "distinct":true},
                                             {"name":"ok_turns","fn":"count","column":"trace_id",
                                              "distinct":true,"where":"success = true"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""
                                .formatted(DISTINCT_PIPELINE, DISTINCT_ROLLUP)))
                .andExpect(status().isCreated());

        // The quantifier has to survive the round-trip through the stored spec, or a refresh would silently
        // widen the measure back to a row count — the failure this whole change exists to remove.
        mockMvc.perform(get(PIPELINES + "/" + DISTINCT_PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measures[1].distinct").value(true))
                .andExpect(jsonPath("$.measures[2].distinct").value(true))
                .andExpect(jsonPath("$.measures[2].where").value("success = true"))
                // An omitted quantifier is no longer ECHOED at all. It was `false` on 80 of the 87 live
                // measures, which made a repeated default the most voluminous member of the registry read.
                // The reading is unchanged in both directions — absent still means false inbound.
                .andExpect(jsonPath("$.measures[0].distinct").doesNotExist());

        // d1: two turns across five hops — trace u1 fans out to three hops and u2 to two. A row count would
        // say 5, so 2 is only reachable by deduplicating. d2: one turn of two hops, neither successful, which
        // is the empty scope for ok_turns.
        seedTurnHop("d1", "u1", true, 1);
        seedTurnHop("d1", "u1", true, 2);
        seedTurnHop("d1", "u1", true, 3);
        seedTurnHop("d1", "u2", true, 4);
        seedTurnHop("d1", "u2", true, 5);
        seedTurnHop("d2", "u3", false, 6);
        seedTurnHop("d2", "u3", false, 7);

        mockMvc.perform(patch(PIPELINES + "/" + DISTINCT_PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        assertThat(mockMvc.perform(post(PIPELINES + "/" + DISTINCT_PIPELINE + "/refresh"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"rows_written\":2");

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"chat_id"}},
                                           {"expr":{"type":"field","name":"hops"}},
                                           {"expr":{"type":"field","name":"turns"}},
                                           {"expr":{"type":"field","name":"ok_turns"}}],
                                 "sort":[{"field":"chat_id","dir":"asc"}],
                                 "page":{"type":"offset","offset":0,"limit":25}}""".formatted(DISTINCT_ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                // The unscoped count still sees every hop, so the two columns diverge exactly as the hop/turn
                // distinction says they must. 5 vs 2 is the whole defect this change fixes, in one row.
                .andExpect(jsonPath("$.rows[0].chat_id").value("d1"))
                .andExpect(jsonPath("$.rows[0].hops").value(5))
                .andExpect(jsonPath("$.rows[0].turns").value(2))
                .andExpect(jsonPath("$.rows[0].ok_turns").value(2))
                .andExpect(jsonPath("$.rows[1].chat_id").value("d2"))
                .andExpect(jsonPath("$.rows[1].hops").value(2))
                .andExpect(jsonPath("$.rows[1].turns").value(1))
                // The empty scope, into a non-nullable column: every row fails the predicate, so the CASE is
                // NULL throughout and the distinct count of nothing is 0. Were it NULL, ClickHouse would store
                // this same 0 as a type default — so the assertion that distinguishes them is the one below,
                // that the column could be declared non-nullable at all and the write still succeeded.
                .andExpect(jsonPath("$.rows[1].ok_turns").value(0));

        // Idempotence: a distinct aggregate is deterministic, so recomputing an unchanged group must write the
        // identical value and leave the collapse a no-op.
        mockMvc.perform(post(PIPELINES + "/" + DISTINCT_PIPELINE + "/refresh")).andExpect(status().isOk());
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"%s","mode":"row",
                                 "select":[{"expr":{"type":"field","name":"turns"}}],
                                 "sort":[{"field":"chat_id","dir":"asc"}],
                                 "page":{"type":"offset","offset":0,"limit":25}}""".formatted(DISTINCT_ROLLUP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].turns").value(2))
                .andExpect(jsonPath("$.rows[1].turns").value(1));
    }

    @Test
    void scopedMeasureIntoNonNullableTargetIsRefused() throws Exception {
        // The nullable rule reached through the scoping rather than the input column. total_price IS nullable
        // upstream, so to isolate the scoping this uses `success`, which is not — meaning the input rule
        // exempts it and only the `where` makes the result nullable. Keying the check on the input column
        // alone, as it was before, would have let exactly this declaration through.
        IntegrationTables.upsertSource(mockMvc, "scoped_strict", """
                [{"source_name":"chat_id","name":"chat_id","type":"string"},
                 {"source_name":"ok_count","name":"ok_count","type":"long"}]""",
                "[\"chat_id\"]");

        previewOf("""
                                {"name":"scoped_strict_rollup","kind":"aggregate","target":"scoped_strict",
                                 "inputs":["dial_usage_log"],
                                 "filter":"length(chat_id) > 0",
                                 "group_by":[{"column":"chat_id"}],
                                 "measures":[{"name":"ok_count","fn":"sum","column":"success",
                                              "where":"parent_deployment IS NULL"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("is scoped by a 'where'")))
                .andExpect(jsonPath("$.message").value(containsString("declared non-nullable")));
    }

    @Test
    void malformedMeasurePredicateIsRefusedAtDeclaration() throws Exception {
        // The predicate goes through the same bounded grammar as `filter`, so a broken one is a 422 at
        // declaration rather than a statement that fails inside every scheduled refresh.
        IntegrationTables.upsertSource(mockMvc, "scoped_bad", """
                [{"source_name":"chat_id","name":"chat_id","type":"string"},
                 {"source_name":"hops","name":"hops","type":"long"}]""",
                "[\"chat_id\"]");

        previewOf("""
                                {"name":"scoped_bad_rollup","kind":"aggregate","target":"scoped_bad",
                                 "inputs":["dial_usage_log"],
                                 "filter":"length(chat_id) > 0",
                                 "group_by":[{"column":"chat_id"}],
                                 "measures":[{"name":"hops","fn":"count",
                                              "where":"no_such_column IS NULL"}],
                                 "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("no_such_column")));
    }

    // ------------------------------------------------------------------ the shrunken declaration

    /**
     * {@code group_by} omitted, derived from the target's {@code ordering_key}, and <b>echoed expanded</b>.
     *
     * <p>The echo is the mitigation for the one failure derivation can have: it matches by name, so an
     * input column that happens to share the ordering key's name but is not what the author meant would
     * group the wrong thing silently. Name-matching cannot detect that, so the derived key is made
     * visible before the pipeline is armed rather than left implicit.
     */
    @Test
    void omittedGroupByIsDerivedFromTheTargetOrderingKeyAndEchoed() throws Exception {
        IntegrationTables.upsertSource(mockMvc, "agg_single",
                "[{\"source_name\":\"deployment\",\"name\":\"deployment\",\"type\":\"string\"},"
                        + "{\"source_name\":\"call_count\",\"name\":\"call_count\",\"type\":\"long\"}]",
                "[\"deployment\"]");
        try {
            mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"agg-derived","kind":"aggregate","target":"agg_single",
                                     "inputs":["dial_usage_log"],
                                     "measures":[{"name":"call_count","fn":"count"}],
                                     "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""))
                    .andExpect(status().isCreated())
                    // Expanded into the stored declaration at registration, once — not re-derived behind
                    // every consumer — so the echo is automatic rather than a feature.
                    .andExpect(jsonPath("$.group_by", hasSize(1)))
                    .andExpect(jsonPath("$.group_by[0].column").value("deployment"));

            // And a declaration patch may omit it too: it was optional at registration, so requiring it
            // on patch would refuse a pipeline that was allowed to be registered without it.
            mockMvc.perform(patch(PIPELINES + "/agg-derived").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"agg-derived","kind":"aggregate","target":"agg_single",
                                     "inputs":["dial_usage_log"],
                                     "measures":[{"name":"call_count","fn":"count"}],
                                     "trigger":{"kind":"schedule","cron":"0 30 * * * *"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.group_by[0].column").value("deployment"));

            // The read serves the derived key expanded before arming; the preview does not echo it, but its
            // sample is keyed on it and its statement groups by it, so a wrong derivation shows in the numbers.
            mockMvc.perform(get(PIPELINES + "/agg-derived"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.group_by[0].column").value("deployment"));
            mockMvc.perform(post(PIPELINES + "/agg-derived/preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.group_by").doesNotExist())
                    .andExpect(jsonPath("$.columns[0].name").value("deployment"))
                    .andExpect(jsonPath("$.columns[0].role").value("key"))
                    .andExpect(jsonPath("$.statement").value(containsString("GROUP BY \"deployment\"")));
        } finally {
            mockMvc.perform(delete(PIPELINES + "/agg-derived"));
            mockMvc.perform(delete("/v1/tables/agg_single"));
        }
    }

    /**
     * The measure {@code column} default is <b>function-aware</b>, and both halves matter.
     *
     * <p>A column-less {@code count} keeps meaning {@code count(*)}: 13 live measures are exactly that,
     * and their names ({@code hop_count}, {@code rate_event_count}) name no column at all. Every other
     * function defaults to the measure's own name. A flat "defaults to {@code name}" would break the
     * first group outright and silently change the numbers on the rest, because {@code count(<column>)}
     * skips NULLs where {@code count(*)} does not.
     *
     * <p>The resolved column is echoed expanded for the same reason the derived group key is: the default
     * cannot detect an input carrying two plausibly-named columns where the one the author meant is not
     * the one whose name matches.
     */
    @Test
    void measureColumnDefaultIsFunctionAwareAndEchoedExpanded() throws Exception {
        IntegrationTables.upsertSource(mockMvc, "agg_defaults",
                "[{\"source_name\":\"deployment\",\"name\":\"deployment\",\"type\":\"string\"},"
                        + "{\"source_name\":\"hop_count\",\"name\":\"hop_count\",\"type\":\"long\"},"
                        // Nullable, because `sum` over a nullable input column yields NULL for a group
                        // holding no value — a pre-existing rule this test has to satisfy like any other.
                        + "{\"source_name\":\"total_tokens\",\"name\":\"total_tokens\","
                        + "\"type\":\"long\",\"nullable\":true}]",
                "[\"deployment\"]");
        try {
            mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"agg-defaults","kind":"aggregate","target":"agg_defaults",
                                     "inputs":["dial_usage_log"],
                                     "measures":[{"name":"hop_count","fn":"count"},
                                                 {"name":"total_tokens","fn":"sum"}],
                                     "trigger":{"kind":"schedule","cron":"0 0 * * * *"}}"""))
                    .andExpect(status().isCreated())
                    // count keeps no column: it is a row count, which no column name can express.
                    .andExpect(jsonPath("$.measures[?(@.name=='hop_count')].column", hasSize(0)))
                    // sum resolves to the measure's own name, echoed so the resolution is visible.
                    .andExpect(jsonPath("$.measures[?(@.name=='total_tokens')].column",
                            contains("total_tokens")))
                    // And `distinct: false` is no longer echoed at all — it was repeated on 80 of 87
                    // live measures, which made a default the most voluminous member of the read.
                    .andExpect(jsonPath("$.measures[0].distinct").doesNotExist());

            // The count really compiled to count(*), not count(hop_count): the two differ on NULLs, so
            // this is a numbers question rather than a rendering one.
            // The preview echoes no measures — the read above serves the resolution — but its statement
            // shows the column each one resolved to.
            mockMvc.perform(post(PIPELINES + "/agg-defaults/preview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statement").value(containsString("count(*) AS \"hop_count\"")))
                    .andExpect(jsonPath("$.statement").value(
                            containsString("sum(\"total_tokens\") AS \"total_tokens\"")))
                    .andExpect(jsonPath("$.measures").doesNotExist());
        } finally {
            mockMvc.perform(delete(PIPELINES + "/agg-defaults"));
            mockMvc.perform(delete("/v1/tables/agg_defaults"));
        }
    }

    /**
     * The aggregate arm of preview takes <b>no body</b>, and refuses one rather than ignoring it.
     *
     * <p>Every member of that body positions an {@code enrich} sample — a group key, a cursor, a version
     * window — and an aggregate sample covers the whole input deliberately, so none of them means
     * anything here. A caller who sent one has a different pipeline in mind than the one they named, and
     * the same posture applies as to a declaration field belonging to the other kind: refuse it, because
     * a silently dropped member reads back absent and tells the caller their request was honoured.
     */
    @Test
    void anAggregatePreviewRefusesTheEnrichBody() throws Exception {
        register();

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group_key\":\"chat-7\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("takes no body")));

        // An empty body is not a body: a caller that always sends `{}` is not making a claim.
        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    /**
     * {@code enabled: true} at registration is refused naming the preview endpoint, instead of the silent
     * coercion to {@code false} it used to get. A field the caller set and the server discarded is the
     * class of surprise this change exists to remove — and it was the last one in the registration path.
     */
    @Test
    void enabledTrueAtRegistrationIsRefusedNamingThePreview() throws Exception {
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"agg-armed\",\"kind\":\"aggregate\"," + DECLARATION
                                + ",\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("pipeline_validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("/preview")));

        // `false` and an absent flag both stay legal — an honest statement is not a mistake.
        for (final String flag : new String[] {",\"enabled\":false", ""}) {
            mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\","
                                    + DECLARATION + flag + "}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.enabled").value(false));
            mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        }

        // And the PATCH path still arms it: that is the only way an aggregate is ever enabled, and it
        // re-validates first, so extending the refusal there would make the kind unarmable.
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\"," + DECLARATION + "}"))
                .andExpect(status().isCreated());
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }
}
