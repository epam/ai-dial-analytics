package com.epam.aidial.analytics.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of an aggregate pipeline declared as one {@code INSERT … SELECT} statement, against a real
 * ClickHouse: create → preview → arm → refresh → append → refresh, with the target compared against a full recompute
 * of the same statement after every refresh. The comparison is the point: a recompute that saw only part of a dirty
 * key, or a grain join restricted so that rows of other keys leak into one, runs and writes plausible numbers.
 *
 * <p>Requires a running Docker daemon.
 */
class AggregateSqlDeclarationIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USAGE_ROLLUP = "usage_daily_sql";
    private static final String USAGE_PIPELINE = "usage_daily_sql_rollup";

    /** A day bucket written with no time zone, and a WHERE the probe must apply as the pipeline's filter. */
    private static final String USAGE_SQL = "INSERT INTO " + USAGE_ROLLUP + " (deployment, day, call_count, tokens)"
            + " SELECT deployment, date_trunc('day', request_time), count(*), sum(total_tokens) FROM dial_usage_log"
            + " WHERE deployment <> 'ignored' GROUP BY 1, 2";

    /** The same statement run directly over the whole input: the full recompute the target must equal. */
    private static final String USAGE_RECOMPUTE = "SELECT toString(groupArray((deployment, day, call_count, tokens))) AS r"
            + " FROM (SELECT deployment, formatDateTime(toStartOfInterval(request_time, INTERVAL 1 DAY, 'UTC'), '%F %T', 'UTC') AS day,"
            + " count(*) AS call_count, sum(total_tokens) AS tokens FROM dial_usage_log FINAL WHERE deployment <> 'ignored'"
            + " GROUP BY deployment, day ORDER BY deployment, day)";

    private static final String USAGE_TARGET = "SELECT toString(groupArray((deployment, day, call_count, tokens))) AS r"
            + " FROM (SELECT deployment, formatDateTime(day, '%F %T', 'UTC') AS day, call_count, tokens FROM "
            + USAGE_ROLLUP + " FINAL ORDER BY deployment, day)";

    private static final String PROMPT_ROLLUP = "usage_prompt_sql";
    private static final String PROMPT_PIPELINE = "usage_prompt_sql_rollup";

    private static final String RATIO_ROLLUP = "usage_ratio_sql";
    private static final String RATIO_PIPELINE = "usage_ratio_sql_rollup";

    /**
     * A ratio measure into a {@code decimal} column, over a group whose denominator is 0. The division must yield NULL
     * for that group: computing {@code count / 0} under the NULL mask made the INSERT's Decimal conversion fail.
     */
    private static final String RATIO_SQL = "INSERT INTO " + RATIO_ROLLUP + " (deployment, calls_per_token)"
            + " SELECT deployment, count(*) / sum(total_tokens) FROM dial_usage_log GROUP BY deployment";

    private static final String RATIO_TARGET = "SELECT toString(groupArray((deployment, calls_per_token))) AS r"
            + " FROM (SELECT deployment, calls_per_token FROM " + RATIO_ROLLUP + " FINAL ORDER BY deployment)";

    private static final String SOURCE = "gizmo_events";
    private static final String ENRICHMENT = "gizmo_owner";
    private static final String OWNER_ROLLUP = "gizmo_owner_totals";
    private static final String OWNER_PIPELINE = "gizmo_owner_totals_rollup";

    /**
     * The sessions-rollup shape: one key coalescing a nullable enrichment column, and no WHERE that would drop the
     * input rows the enrichment has not reached — so they form the {@code ''} group, the key's absence value.
     */
    private static final String OWNER_SQL = "INSERT INTO " + OWNER_ROLLUP + " (owner, total, events) SELECT coalesce(\""
            + ENRICHMENT + ".owner\", '') AS owner, sum(score), count(*) FROM " + SOURCE + " GROUP BY 1";

    private static final String OWNER_RECOMPUTE = "SELECT toString(groupArray((owner, total, events))) AS r FROM ("
            + "SELECT coalesce(" + ENRICHMENT + ".owner, '') AS owner, sum(score) AS total, count() AS events FROM " + SOURCE
            + " LEFT JOIN " + ENRICHMENT + " FINAL ON " + SOURCE + ".event_id = " + ENRICHMENT + ".event_id"
            + " GROUP BY owner ORDER BY owner)";

    private static final String OWNER_TARGET = "SELECT toString(groupArray((owner, total, events))) AS r FROM ("
            + "SELECT owner, total, events FROM " + OWNER_ROLLUP + " FINAL ORDER BY owner)";

    @BeforeEach
    void cleanSlate() throws Exception {
        truncate();
        dropAll();
        IntegrationTables.upsertSource(mockMvc, USAGE_ROLLUP, """
                [{"source_name":"deployment","name":"deployment","type":"string"},
                 {"source_name":"day","name":"day","type":"timestamp"},
                 {"source_name":"call_count","name":"call_count","type":"long"},
                 {"source_name":"tokens","name":"tokens","type":"long","nullable":true}]""",
                "[\"deployment\",\"day\"]");
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + USAGE_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + USAGE_ROLLUP));
        mockMvc.perform(delete(PIPELINES + "/" + PROMPT_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + PROMPT_ROLLUP));
        mockMvc.perform(delete(PIPELINES + "/" + RATIO_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + RATIO_ROLLUP));
        mockMvc.perform(delete(PIPELINES + "/" + OWNER_PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + OWNER_ROLLUP));
        // The enrich pipeline before its target enrichment: it is what makes the enrichment clampable.
        if (ownerPipelineName != null) {
            mockMvc.perform(delete(PIPELINES + "/" + ownerPipelineName));
            ownerPipelineName = null;
        }
        // Enrichments before their source: deleting a source with dependents is refused with 409.
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
    }

    private void create(String name, String target, String sql) throws Exception {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("kind", "aggregate");
        body.put("target", target);
        body.put("sql", sql);
        body.put("trigger", Map.of("kind", "schedule", "cron", "0 0 * * * *"));
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    private void arm(String name) throws Exception {
        mockMvc.perform(patch(PIPELINES + "/" + name).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private long refresh(String name) throws Exception {
        final String body = mockMvc.perform(post(PIPELINES + "/" + name + "/refresh"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("rows_written").asLong();
    }

    /**
     * Seeds one input row. Ingest time grows with {@code seq} whatever the event day, so a row seeded later lands
     * past the cursor even when its event belongs to an already-written bucket — a late row.
     */
    private void seedUsage(String deployment, String day, long tokens, int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, total_tokens, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', '" + deployment + "', '" + day + " 10:" + String.format("%02d", seq) + ":00.000',"
                + " 't" + seq + "', 's" + seq + "', " + tokens + ", '2026-02-01 00:" + String.format("%02d", seq) + ":00.000')");
    }

    @Test
    void appendedRowsRecomputeToTheFullResultAndTheNextTickChangesNothing() throws Exception {
        create(USAGE_PIPELINE, USAGE_ROLLUP, USAGE_SQL);
        seedUsage("gpt-4", "2026-01-01", 100, 1);
        seedUsage("gpt-4", "2026-01-01", 40, 2);
        seedUsage("claude", "2026-01-02", 7, 3);
        seedUsage("ignored", "2026-01-02", 1000, 4);

        // The preview samples the block: target-named key and measure columns, and the block as a statement the
        // query API runs — which, every group fitting the sample, answers the sampled groups in key order.
        final String preview = mockMvc.perform(post(PIPELINES + "/" + USAGE_PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statement", startsWith("SELECT \"deployment\", date_trunc('day', \"request_time\") AS \"day\"")))
                .andExpect(jsonPath("$.columns[*].name", contains("deployment", "day", "call_count", "tokens")))
                .andExpect(jsonPath("$.columns[*].role", contains("key", "key", "measure", "measure")))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.total_rows").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(PreviewStatements.runStatement(mockMvc, preview))
                .containsExactlyInAnyOrderElementsOf(PreviewStatements.body(preview).get("rows"));

        arm(USAGE_PIPELINE);
        assertThat(refresh(USAGE_PIPELINE)).isEqualTo(2);
        assertThat(queryScalar(USAGE_TARGET, "r")).isEqualTo(queryScalar(USAGE_RECOMPUTE, "r")).contains("140");

        // An already-written group grows, a new group appears, and an excluded row stays excluded.
        seedUsage("gpt-4", "2026-01-01", 5, 5);
        seedUsage("gpt-4", "2026-01-03", 9, 6);
        seedUsage("ignored", "2026-01-01", 1000, 7);
        assertThat(refresh(USAGE_PIPELINE)).isEqualTo(2);
        final String afterAppend = queryScalar(USAGE_TARGET, "r");
        assertThat(afterAppend).isEqualTo(queryScalar(USAGE_RECOMPUTE, "r")).contains("145").doesNotContain("ignored");

        assertThat(refresh(USAGE_PIPELINE)).as("the input is drained: a second tick writes nothing").isZero();
        assertThat(queryScalar(USAGE_TARGET, "r")).isEqualTo(afterAppend);
    }

    @Test
    void measureNamedLikeInputFieldKeepsItsTargetNameInStatement() throws Exception {
        // The sample aliases the item positionally so it cannot shadow the input's own prompt_tokens; the statement
        // a caller copies names it by its target column, and the query path runs it with the declaration's meaning.
        IntegrationTables.upsertSource(mockMvc, PROMPT_ROLLUP, """
                [{"source_name":"deployment","name":"deployment","type":"string"},
                 {"source_name":"prompt_tokens","name":"prompt_tokens","type":"long","nullable":true}]""",
                "[\"deployment\"]");
        create(PROMPT_PIPELINE, PROMPT_ROLLUP, "INSERT INTO " + PROMPT_ROLLUP + " (deployment, prompt_tokens)"
                + " SELECT deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1");
        seedUsage("gpt-4", "2026-01-01", 100, 1);
        seedUsage("gpt-4", "2026-01-01", 40, 2);

        final String preview = mockMvc.perform(post(PIPELINES + "/" + PROMPT_PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].prompt_tokens").value(140))
                .andExpect(jsonPath("$.statement", containsString("sum(\"total_tokens\") AS \"prompt_tokens\"")))
                .andExpect(jsonPath("$.statement", not(containsString("__c"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(PreviewStatements.runStatement(mockMvc, preview)).isEqualTo(PreviewStatements.body(preview).get("rows"));
    }

    @Test
    void ratioIntoDecimalColumnYieldsNullForZeroDenominator() throws Exception {
        IntegrationTables.upsertSource(mockMvc, RATIO_ROLLUP, """
                [{"source_name":"deployment","name":"deployment","type":"string"},
                 {"source_name":"calls_per_token","name":"calls_per_token","type":"decimal","nullable":true}]""",
                "[\"deployment\"]");
        create(RATIO_PIPELINE, RATIO_ROLLUP, RATIO_SQL);
        seedUsage("gpt-4", "2026-01-01", 30, 1);
        seedUsage("gpt-4", "2026-01-01", 10, 2);
        seedUsage("empty", "2026-01-01", 0, 3);

        arm(RATIO_PIPELINE);
        assertThat(refresh(RATIO_PIPELINE)).isEqualTo(2);
        assertThat(queryScalar(RATIO_TARGET, "r")).isEqualTo("[('empty',NULL),('gpt-4',0.05)]");
    }

    private void createOwnerFixture() throws Exception {
        IntegrationTables.source(mockMvc, SOURCE, """
                [{"source_name":"event_id","name":"event_id","type":"string"},
                 {"source_name":"score","name":"score","type":"long"},
                 {"source_name":"at","name":"at","type":"timestamp"}]""",
                "[\"event_id\"]", "event_id", "at");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"owner\",\"name\":\"owner\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.upsertSource(mockMvc, OWNER_ROLLUP, """
                [{"source_name":"owner","name":"owner","type":"string"},
                 {"source_name":"total","name":"total","type":"long","nullable":true},
                 {"source_name":"events","name":"events","type":"long"}]""",
                "[\"owner\"]");
    }

    private void seedEvent(String eventId, long score, int minute) {
        execute("INSERT INTO " + SOURCE + " (event_id, score, at) VALUES ('" + eventId + "', " + score
                + ", '2026-01-01 10:" + String.format("%02d", minute) + ":00.000')");
    }

    private void seedOwner(String eventId, String owner) {
        execute("INSERT INTO " + ENRICHMENT + " (event_id, owner) VALUES ('" + eventId + "', "
                + (owner == null ? "NULL" : "'" + owner + "'") + ")");
    }

    @Test
    void tickDirtyingTheAbsenceKeyDoesNotLeakOtherKeysIntoIt() throws Exception {
        createOwnerFixture();
        create(OWNER_PIPELINE, OWNER_ROLLUP, OWNER_SQL);
        arm(OWNER_PIPELINE);
        // History: two owned keys, a row the enrichment never reached and one whose owner is NULL — the last two
        // are the '' group.
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedEvent("e3", 5, 3);
        seedEvent("e4", 7, 4);
        seedEvent("e5", 9, 5);
        seedOwner("e1", "ada");
        seedOwner("e2", "ada");
        seedOwner("e3", "grace");
        seedOwner("e5", null);
        assertThat(refresh(OWNER_PIPELINE)).isEqualTo(3);
        assertThat(queryScalar(OWNER_TARGET, "r")).isEqualTo(queryScalar(OWNER_RECOMPUTE, "r"));

        // The next window dirties '' (an unenriched event) and grace, but not ada. A join restricted to the dirty
        // keys alone would drop ada's enrichment rows, read e1 and e2 as '' and count them there.
        seedEvent("e6", 100, 6);
        seedEvent("e7", 1, 7);
        seedOwner("e7", "grace");
        assertThat(refresh(OWNER_PIPELINE)).isEqualTo(2);
        assertThat(queryScalar(OWNER_TARGET, "r")).isEqualTo(queryScalar(OWNER_RECOMPUTE, "r"))
                .contains("('',116,3)").contains("('ada',30,2)").contains("('grace',6,2)");

        // A window that dirties only an owned key keeps the restricted join, and stays exact.
        seedEvent("e8", 2, 8);
        seedOwner("e8", "ada");
        assertThat(refresh(OWNER_PIPELINE)).isEqualTo(1);
        assertThat(queryScalar(OWNER_TARGET, "r")).isEqualTo(queryScalar(OWNER_RECOMPUTE, "r")).contains("('ada',32,3)");
        // The restricted statement names the dirty-key sub-select three times (the key and its absence image in the
        // grain join, and the outer WHERE). The analyzer keys prepared sets by query-tree hash, so it builds one set
        // that all three probe: a single CreatingSet in the plan.
        execute("SYSTEM FLUSH LOGS");
        final String statement = queryScalar("SELECT query AS q FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND query_id LIKE 'adas-" + OWNER_PIPELINE + "-refresh-write-%' ORDER BY event_time_microseconds DESC LIMIT 1", "q");
        final String select = statement.substring(statement.indexOf(") select ") + 2);
        assertThat(select.split("IN \\(select distinct ", -1)).hasSize(4);
        final String plan = queryScalar("SELECT arrayStringConcat(groupArray(explain), '\\n') AS e FROM (EXPLAIN PLAN " + select + ")", "e");
        assertThat(plan.split("Create set for subquery", -1)).hasSize(2);
    }

    // ------------------------------------------------- a draft saved before its input existed

    /**
     * A draft whose {@code FROM} table did not exist when it was saved stores no input; once the table exists the
     * gate reads it from the statement, and the preview samples that table rather than a null input.
     */
    @Test
    void previewOfDraftSavedBeforeItsInputExistedSamplesTheStatementsTable() throws Exception {
        create(USAGE_PIPELINE, USAGE_ROLLUP, "INSERT INTO " + USAGE_ROLLUP + " (deployment, day, call_count, tokens)"
                + " SELECT event_id, date_trunc('day', at), count(*), sum(score) FROM " + SOURCE + " GROUP BY 1, 2");
        mockMvc.perform(get(PIPELINES + "/" + USAGE_PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputs").doesNotExist());
        IntegrationTables.source(mockMvc, SOURCE, """
                [{"source_name":"event_id","name":"event_id","type":"string"},
                 {"source_name":"score","name":"score","type":"long"},
                 {"source_name":"at","name":"at","type":"timestamp"}]""",
                "[\"event_id\"]", "event_id", "at");
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);

        mockMvc.perform(post(PIPELINES + "/" + USAGE_PIPELINE + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", contains("deployment", "day", "call_count", "tokens")))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.total_rows").value(2));
    }

    // ------------------------------------------------- a CTE that only filters and computes

    /** The usage rollup with its filter and a computed column moved into a CTE: one block once flattened. */
    private static final String CTE_SQL = "INSERT INTO " + USAGE_ROLLUP + " (deployment, day, call_count, tokens)"
            + " WITH kept AS (SELECT deployment, request_time, total_tokens * 2 AS doubled FROM dial_usage_log"
            + " WHERE deployment <> 'ignored')"
            + " SELECT deployment, date_trunc('day', request_time), count(*), sum(doubled) FROM kept GROUP BY 1, 2";

    @Test
    void cteThatFlattensToOneBlockRefreshesIncrementally() throws Exception {
        final String recompute = USAGE_RECOMPUTE.replace("sum(total_tokens)", "sum(total_tokens * 2)");
        create(USAGE_PIPELINE, USAGE_ROLLUP, CTE_SQL);
        seedUsage("gpt-4", "2026-01-01", 100, 1);
        seedUsage("claude", "2026-01-02", 7, 2);
        seedUsage("ignored", "2026-01-02", 1000, 3);
        arm(USAGE_PIPELINE);
        assertThat(refresh(USAGE_PIPELINE)).isEqualTo(2);
        assertThat(queryScalar(USAGE_TARGET, "r")).isEqualTo(queryScalar(recompute, "r")).contains("200");

        // The CTE's WHERE is the pipeline's filter: the excluded row neither dirties nor joins a group.
        seedUsage("gpt-4", "2026-01-01", 5, 4);
        seedUsage("ignored", "2026-01-01", 1000, 5);
        assertThat(refresh(USAGE_PIPELINE)).isEqualTo(1);
        final String afterAppend = queryScalar(USAGE_TARGET, "r");
        assertThat(afterAppend).isEqualTo(queryScalar(recompute, "r")).contains("210").doesNotContain("ignored");

        assertThat(refresh(USAGE_PIPELINE)).isZero();
        assertThat(queryScalar(USAGE_TARGET, "r")).isEqualTo(afterAppend);
    }

    // ------------------------------------------------- patching `sql` resets the cursor

    @Test
    void patchingTheStatementResetsTheCursorSoEveryKeyIsRecomputed() throws Exception {
        create(USAGE_PIPELINE, USAGE_ROLLUP, USAGE_SQL);
        arm(USAGE_PIPELINE);
        seedUsage("gpt-4", "2026-01-01", 100, 1);
        assertThat(refresh(USAGE_PIPELINE)).isEqualTo(1);
        final long generation = JSON.readTree(mockMvc.perform(get(PIPELINES + "/" + USAGE_PIPELINE))
                .andExpect(jsonPath("$.state.cursor_version").exists())
                .andReturn().getResponse().getContentAsString()).get("generation").asLong();

        mockMvc.perform(patch(PIPELINES + "/" + USAGE_PIPELINE).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        final String maxSql = USAGE_SQL.replace("sum(total_tokens)", "max(total_tokens)");
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", USAGE_ROLLUP);
        body.put("sql", maxSql);
        body.put("trigger", Map.of("kind", "schedule", "cron", "0 0 * * * *"));
        final String patched = mockMvc.perform(patch(PIPELINES + "/" + USAGE_PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value(maxSql))
                .andExpect(jsonPath("$.state.cursor_version").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(patched).get("generation").asLong()).isGreaterThan(generation);

        // A row for the already-written key: kept, the cursor would confine the new statement to it alone.
        seedUsage("gpt-4", "2026-01-01", 40, 2);
        arm(USAGE_PIPELINE);
        assertThat(refresh(USAGE_PIPELINE)).isEqualTo(1);
        // max over both rows — the history was re-read under the new statement.
        assertThat(queryScalar(USAGE_TARGET, "r"))
                .isEqualTo(queryScalar(USAGE_RECOMPUTE.replace("sum(total_tokens)", "max(total_tokens)"), "r"))
                .contains(",2,100)");
    }

    // ------------------------------------------------- an enrichment key with the freshness clamp

    /** The sessions-rollup shape in full: the coalesced enrichment key AND the WHERE that drops unmatched rows. */
    private static final String OWNED_SQL = "INSERT INTO " + OWNER_ROLLUP + " (owner, total, events) SELECT coalesce(\""
            + ENRICHMENT + ".owner\", '') AS owner, sum(score), count(*) FROM " + SOURCE
            + " WHERE length(\"" + ENRICHMENT + ".owner\") > 0 GROUP BY 1";

    private static final String OWNED_RECOMPUTE = OWNER_RECOMPUTE.replace(" GROUP BY owner",
            " WHERE length(" + ENRICHMENT + ".owner) > 0 GROUP BY owner");

    /** The enrich pipeline created by {@link #createOwnerPipeline}. */
    private String ownerPipelineName;

    /** A Class-1 pipeline filling the enrichment's owner column, so the enrichment has a position to clamp to. */
    private void createOwnerPipeline() throws Exception {
        final String created = mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gizmo-owner-live\","
                                + Transforms.sql().output("owner", "lower('ADA')").member() + ","
                                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                                + "\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ownerPipelineName = JsonPath.read(created, "$.name");
    }

    private void materialize(String body) throws Exception {
        mockMvc.perform(runnerPost(PIPELINES + "/" + ownerPipelineName + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /**
     * The form path's clamp case ({@code AggregateEnrichmentIntegrationTest}) over the SQL declaration: coverage
     * is non-contiguous (e1 and e3 enriched, e2 not), so an unclamped window would end at e3 and carry the cursor
     * past e2 for good. Clamped, it stops where the enrichment's pipeline has materialized through.
     */
    @Test
    void enrichmentKeyWindowClampsToTheEnrichmentPipelinesPosition() throws Exception {
        createOwnerFixture();
        createOwnerPipeline();
        create(OWNER_PIPELINE, OWNER_ROLLUP, OWNED_SQL);
        arm(OWNER_PIPELINE);
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedEvent("e3", 30, 3);
        seedOwner("e1", "ada");
        seedOwner("e3", "ada");

        materialize("{\"limit\":1}");
        assertThat(refresh(OWNER_PIPELINE)).isEqualTo(1);
        // 40: the dirty key is recomputed in full, e3 included; the clamp decides only where the cursor stops.
        assertThat(queryScalar(OWNER_TARGET, "r")).isEqualTo(queryScalar(OWNED_RECOMPUTE, "r")).isEqualTo("[('ada',40,2)]");
        mockMvc.perform(get(PIPELINES + "/" + OWNER_PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.clamp.enrichment").value(ENRICHMENT))
                .andExpect(jsonPath("$.state.clamp.cursor_version").isNumber());

        // e2 was held ahead of the cursor, so its late value is still read.
        seedOwner("e2", "ada");
        materialize("{\"limit\":10}");
        refresh(OWNER_PIPELINE);
        assertThat(queryScalar(OWNER_TARGET, "r")).isEqualTo(queryScalar(OWNED_RECOMPUTE, "r")).isEqualTo("[('ada',60,3)]");
        mockMvc.perform(get(PIPELINES + "/" + OWNER_PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.clamp").doesNotExist());
    }

    /**
     * The coalesce key with the WHERE that drops unmatched rows, ticked across a window that touches one owner
     * only: the restricted join must not drop other owners' rows into {@code ''} (the WHERE removes them), and no
     * {@code ''} group is ever written.
     */
    @Test
    void coalescedKeyWithAnEnrichmentWhereStaysExactAcrossTicks() throws Exception {
        createOwnerFixture();
        create(OWNER_PIPELINE, OWNER_ROLLUP, OWNED_SQL);
        arm(OWNER_PIPELINE);
        seedEvent("e1", 10, 1);
        seedEvent("e2", 20, 2);
        seedEvent("e3", 5, 3);
        seedEvent("e4", 7, 4);
        seedOwner("e1", "ada");
        seedOwner("e2", "ada");
        seedOwner("e3", "grace");
        assertThat(refresh(OWNER_PIPELINE)).isEqualTo(2);
        assertThat(queryScalar(OWNER_TARGET, "r")).isEqualTo(queryScalar(OWNED_RECOMPUTE, "r"));

        seedEvent("e5", 1, 5);
        seedOwner("e5", "grace");
        seedEvent("e6", 100, 6);
        assertThat(refresh(OWNER_PIPELINE)).isEqualTo(1);
        assertThat(queryScalar(OWNER_TARGET, "r")).isEqualTo(queryScalar(OWNED_RECOMPUTE, "r"))
                .isEqualTo("[('ada',30,2),('grace',6,2)]");
    }
}
