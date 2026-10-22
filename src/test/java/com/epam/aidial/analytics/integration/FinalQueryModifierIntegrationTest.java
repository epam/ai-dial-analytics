package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the {@code FINAL} modifier with the {@code query.force-final} migration flag
 * <b>off</b> — the configuration in which what a query says is what it reads. Against a real ClickHouse,
 * with not-yet-merged versions seeded as separate {@code INSERT}s: a query that reads an entity final sees
 * one row per key, one that reads it raw sees every version, exactly as the same statement does in
 * ClickHouse with and without {@code FINAL}. The flag-on behaviour — every read final, as before the modifier
 * existed — is what {@link FinalDedupIntegrationTest} covers under the shipped default.
 *
 * <p>One extra Spring context (the property set is part of the context-cache key); it is the only way to see
 * the flag off end to end.
 *
 * <p>Requires a running Docker daemon.
 */
@TestPropertySource(properties = "query.force-final=false")
class FinalQueryModifierIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String EXECUTE_SQL_CSV = "/v1/queries/execute-sql-csv";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    @AfterEach
    void dropUserTables() throws Exception {
        mockMvc.perform(delete("/v1/tables/fq_labels"));
        mockMvc.perform(delete("/v1/tables/fq_metrics"));
        mockMvc.perform(delete("/v1/tables/fq_plain"));
    }

    /** Two unmerged versions of one dial_usage_log key; the later one carries total_tokens 999. */
    private void seedUnmergedUsageLogVersions() {
        execute("INSERT INTO dial_usage_log (project_id, deployment, request_time, trace_id, core_span_id,"
                + " total_tokens, _ingested_at) VALUES"
                + " ('p','d','2026-01-01 00:00:00.000','t','s', 100, '2026-01-01 00:00:01.000')");
        execute("INSERT INTO dial_usage_log (project_id, deployment, request_time, trace_id, core_span_id,"
                + " total_tokens, _ingested_at) VALUES"
                + " ('p','d','2026-01-01 00:00:00.000','t','s', 999, '2026-01-01 00:00:02.000')");
    }

    private static String dsl(String finalMember) {
        return """
                {"entity":"dial_usage_log","mode":"row",%s
                 "select":[{"expr":{"type":"field","name":"total_tokens"}}],
                 "page":{"type":"offset","offset":0,"limit":25,"include_total":true}}""".formatted(finalMember);
    }

    private static String sql(String statement) {
        return "{\"sql\":\"" + statement.replace("\"", "\\\"") + "\"}";
    }

    @Test
    void structuredQueryReadsWhatItSays() throws Exception {
        seedUnmergedUsageLogVersions();

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(dsl("\"final\":false,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.totalCount").value(2));
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(dsl("\"final\":true,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(999))
                .andExpect(jsonPath("$.totalCount").value(1));
        // Absent over a collapsing source reads final.
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(dsl("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    void sqlReadsFinalOnlyWhereItSaysFinal() throws Exception {
        seedUnmergedUsageLogVersions();

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT total_tokens FROM dial_usage_log WHERE trace_id = 't'")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT total_tokens FROM dial_usage_log FINAL WHERE trace_id = 't'")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(999));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT l.total_tokens FROM dial_usage_log AS l final")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)));
    }

    @Test
    void finalMarksOneReferenceOfJoin() throws Exception {
        seedUnmergedUsageLogVersions();

        // The final side has one row for the key and the raw side two, so the join yields 1 x 2; unmarked it is 2 x 2.
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT l.total_tokens AS a, r.total_tokens AS b FROM dial_usage_log l FINAL"
                                + " JOIN dial_usage_log r ON l.trace_id = r.trace_id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT l.total_tokens AS a, r.total_tokens AS b FROM dial_usage_log l"
                                + " JOIN dial_usage_log r ON l.trace_id = r.trace_id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(4)));
    }

    @Test
    void finalMarksOneSetOperationBranchAndReachesIntoCte() throws Exception {
        seedUnmergedUsageLogVersions();

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT total_tokens FROM dial_usage_log FINAL"
                                + " UNION ALL SELECT total_tokens FROM dial_usage_log")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("WITH c AS (SELECT trace_id FROM dial_usage_log FINAL) SELECT count(*) AS n FROM c")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(1));
    }

    @Test
    void finalOnDerivedTableOrCteNameIsRefused() throws Exception {
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT * FROM (SELECT trace_id FROM dial_usage_log) FINAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("FINAL applies to an entity")));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("WITH c AS (SELECT trace_id FROM dial_usage_log) SELECT * FROM c FINAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("FINAL applies to an entity")));
    }

    @Test
    void finalOnAnAppendSourceWithNoEnrichmentIsIllegal() throws Exception {
        IntegrationTables.source(mockMvc, "fq_plain",
                "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}]", "[\"k\"]");

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"fq_plain\",\"mode\":\"row\",\"final\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("illegal_final"))
                .andExpect(jsonPath("$.message").value(containsString("'fq_plain'")));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT k FROM fq_plain FINAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("illegal_final"));
        mockMvc.perform(post("/v1/queries/translate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"fq_plain\",\"mode\":\"row\",\"final\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("illegal_final"));
        // Omitting final is fine: nothing to collapse, so the read is raw.
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"fq_plain\",\"mode\":\"row\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void anAppendSourceReadsFinalThroughItsEnrichments() throws Exception {
        // An append source (plain MergeTree) with a version-collapsing enrichment: FINAL is legal on the entity,
        // renders on the enrichment only, and keeps the grain join 0..1; read raw, the re-patched key fans out.
        IntegrationTables.source(mockMvc, "fq_metrics",
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"}]", "[\"widget_id\"]");
        IntegrationTables.enrichment(mockMvc, "fq_labels", "fq_metrics", "widget_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
        mockMvc.perform(post("/v1/tables/fq_metrics/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\"}]}"))
                .andExpect(status().isOk());
        execute("INSERT INTO fq_labels (widget_id, label, _updated_at) VALUES ('w1','old','2026-01-01 00:00:01.000')");
        execute("INSERT INTO fq_labels (widget_id, label, _updated_at) VALUES ('w1','new','2026-01-01 00:00:02.000')");

        final String query = """
                {"entity":"fq_metrics","mode":"row",%s
                 "select":[{"expr":{"type":"field","name":"widget_id"}},{"expr":{"type":"field","name":"fq_labels.label"}}]}""";
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(query.formatted("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['fq_labels.label']").value("new"));
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(query.formatted("\"final\":false,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)));
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT widget_id, \"fq_labels.label\" FROM fq_metrics FINAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)));
        mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'fq_metrics')].supports_final").value(true));
    }

    /** A translatable document: no {@code include_total}, which has no SQL spelling. */
    private static String translatable(String finalMember) {
        return """
                {"entity":"dial_usage_log","mode":"row",%s
                 "select":[{"expr":{"type":"field","name":"total_tokens"}}]}""".formatted(finalMember);
    }

    @Test
    void translationCarriesTheMarkBothWays() throws Exception {
        mockMvc.perform(post("/v1/queries/translate").contentType(MediaType.APPLICATION_JSON).content(translatable("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value(containsString("FROM \"dial_usage_log\" FINAL")));
        mockMvc.perform(post("/v1/queries/translate").contentType(MediaType.APPLICATION_JSON)
                        .content(translatable("\"final\":false,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sql").value(not(containsString("FINAL"))));
        mockMvc.perform(post("/v1/queries/translate-sql").contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT trace_id FROM dial_usage_log")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.final").value(false));
        mockMvc.perform(post("/v1/queries/translate-sql").contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT trace_id FROM dial_usage_log FINAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.final").value(true));
    }

    @Test
    void csvDownloadHonoursFinal() throws Exception {
        seedUnmergedUsageLogVersions();

        mockMvc.perform(post(EXECUTE_SQL_CSV).contentType(MediaType.APPLICATION_JSON)
                        .content(sql("SELECT total_tokens FROM dial_usage_log FINAL")))
                .andExpect(status().isOk())
                .andExpect(content().string("total_tokens\r\n999\r\n"));
    }

    @Test
    void discoveryReportsWhereFinalIsLegal() throws Exception {
        IntegrationTables.source(mockMvc, "fq_plain",
                "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}]", "[\"k\"]");

        mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'dial_usage_log')].supports_final").value(true))
                .andExpect(jsonPath("$[?(@.name == 'fq_plain')].supports_final").value(false));
    }
}
