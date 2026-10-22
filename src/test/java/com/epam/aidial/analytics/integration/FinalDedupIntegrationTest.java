package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the read deduplication guarantee against a real ClickHouse: reads apply
 * ClickHouse {@code FINAL} (via the {@code final = 1} query setting the {@code StructuredQueryExecutor}
 * carries) so {@code ReplacingMergeTree} sources and enrichments collapse not-yet-merged versions to the
 * latest per key, while plain {@code MergeTree} sources are unaffected.
 *
 * <p>Each test seeds <em>separate</em> {@code INSERT} statements so the versions land in distinct,
 * unmerged parts — the exact steady state the feature corrects. Because the guarantee lives in the
 * shared execution pipeline, it is exercised through both the structured DSL
 * ({@code POST /v1/queries/execute}) and the REST SQL surface ({@code POST /v1/queries/execute-sql});
 * the Flight SQL / ADBC surface delegates to the same {@code StructuredQueryExecutor} and so inherits it.
 *
 * <p>Requires a running Docker daemon.
 */
class FinalDedupIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    @AfterEach
    void dropUserTables() throws Exception {
        // Enrichments before their source (deleting a source with dependents is blocked); a table a given
        // test never created is a harmless 404 (no status assertion).
        mockMvc.perform(delete("/v1/tables/widget_labels"));
        mockMvc.perform(delete("/v1/tables/widget_metrics"));
        mockMvc.perform(delete("/v1/tables/plain_metrics"));
    }

    /**
     * Seeds two versions of one {@code dial_usage_log} row that share the full ORDER BY sort key
     * (project_id, deployment, request_time, trace_id, core_span_id) but differ in the {@code _ingested_at}
     * version column, as two separate (unmerged) inserts. The second insert is the newer version.
     */
    private void seedUnmergedUsageLogVersions() {
        execute("INSERT INTO dial_usage_log (project_id, deployment, request_time, trace_id, core_span_id,"
                + " total_tokens, _ingested_at) VALUES"
                + " ('p','d','2026-01-01 00:00:00.000','t','s', 100, '2026-01-01 00:00:01.000')");
        execute("INSERT INTO dial_usage_log (project_id, deployment, request_time, trace_id, core_span_id,"
                + " total_tokens, _ingested_at) VALUES"
                + " ('p','d','2026-01-01 00:00:00.000','t','s', 999, '2026-01-01 00:00:02.000')");
    }

    @Test
    void sourceWithUnmergedDuplicatesReturnsLatestRowAndDeduplicatedTotal() throws Exception {
        seedUnmergedUsageLogVersions();

        // Row mode with include_total: without FINAL this returns 2 rows and totalCount 2; with FINAL the
        // two unmerged versions collapse to the latest (_ingested_at 00:00:02 -> total_tokens 999).
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"dial_usage_log","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"project_id"}},
                                   {"expr":{"type":"field","name":"total_tokens"}}],
                                 "page":{"type":"offset","offset":0,"limit":25,"include_total":true}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p"))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(999))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    void restSqlSurfaceReturnsDeduplicatedRows() throws Exception {
        // The REST SQL endpoint delegates to the same executor, so the same dedup holds: one latest row.
        seedUnmergedUsageLogVersions();

        final String body = "{\"sql\":\"SELECT project_id, total_tokens FROM dial_usage_log\"}";
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(999));
    }

    @Test
    void rePatchedEnrichmentDoesNotFanOutSourceRows() throws Exception {
        // Source (plain MergeTree) + enrichment (ReplacingMergeTree(_updated_at)) via table management.
        IntegrationTables.source(mockMvc, "widget_metrics",
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]",
                "[\"widget_id\"]");
        IntegrationTables.enrichment(mockMvc, "widget_labels", "widget_metrics", "widget_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");

        mockMvc.perform(post("/v1/tables/widget_metrics/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10},{\"widget_id\":\"w2\",\"score\":20}]}"))
                .andExpect(status().isOk());

        // Two unmerged enrichment versions for grain key w1 (explicit _updated_at so latest is deterministic).
        // Without FINAL the LEFT JOIN fans w1 out to two rows (3 total); with FINAL w1 resolves to the latest
        // enrichment value and each source row appears exactly once.
        execute("INSERT INTO widget_labels (widget_id, label, _updated_at)"
                + " VALUES ('w1','old','2026-01-01 00:00:01.000')");
        execute("INSERT INTO widget_labels (widget_id, label, _updated_at)"
                + " VALUES ('w1','new','2026-01-01 00:00:02.000')");

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"widget_metrics","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"widget_id"}},
                                   {"expr":{"type":"field","name":"widget_labels.label"}}],
                                 "sort":[{"field":"widget_id","dir":"asc"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].widget_id").value("w1"))
                .andExpect(jsonPath("$.rows[0]['widget_labels.label']").value("new"))
                .andExpect(jsonPath("$.rows[1].widget_id").value("w2"))
                .andExpect(jsonPath("$.rows[1]['widget_labels.label']").value(nullValue()));
    }

    @Test
    void plainMergeTreeSourceIsUnaffectedBySetting() throws Exception {
        // A user-created source is a plain MergeTree (no version collapsing), so final = 1 is a no-op there:
        // two rows sharing the ordering key both survive, exactly as without the setting.
        IntegrationTables.source(mockMvc, "plain_metrics",
                "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                        + "{\"source_name\":\"v\",\"name\":\"v\",\"type\":\"long\"}]",
                "[\"k\"]");

        // Two separate inserts, same ordering key k='a': on a plain MergeTree both rows are retained.
        mockMvc.perform(post("/v1/tables/plain_metrics/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"k\":\"a\",\"v\":1}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/tables/plain_metrics/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"k\":\"a\",\"v\":2}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"plain_metrics","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"k"}},
                                   {"expr":{"type":"field","name":"v"}}],
                                 "sort":[{"field":"v","dir":"asc"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].v").value(1))
                .andExpect(jsonPath("$.rows[1].v").value(2));
    }
}
