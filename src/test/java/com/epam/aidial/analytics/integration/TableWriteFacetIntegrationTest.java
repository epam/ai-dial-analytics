package com.epam.aidial.analytics.integration;

import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the ADR-0006 write-discipline facet against a real ClickHouse: an
 * {@code upsert_by_key} source materializes as {@code ReplacingMergeTree(_updated_at)} keyed on its
 * {@code ordering_key}, so re-writing a key <em>replaces</em> its row (the property a rollup target
 * rests on) and reads collapse to the latest version through the pipeline's standing {@code final = 1}
 * setting. Also pins the facet's immutability and the removal of the inert {@code aggregation} kind.
 *
 * <p>The collapse test seeds its two versions through separate raw {@code INSERT}s with explicit
 * {@code _updated_at} values — distinct unmerged parts and a deterministic winner, the same technique
 * {@code FinalDedupIntegrationTest} uses for enrichments. The row-write API is exercised separately, to
 * assert that the version column is <em>not</em> caller-settable.
 *
 * <p>Requires a running Docker daemon.
 */
class TableWriteFacetIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";
    private static final String ROLLUP = "usage_daily";
    private static final String COLUMNS = """
            [{"source_name":"deployment","name":"deployment","type":"string"},
             {"source_name":"day","name":"day","type":"date"},
             {"source_name":"call_count","name":"call_count","type":"long"}]""";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    @AfterEach
    void dropUserTables() throws Exception {
        // A table a given test never created is a harmless 404 (no status assertion). The enrichment goes
        // first: deleting a source that still has one is a 409.
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
        mockMvc.perform(delete("/v1/tables/append_labels"));
        mockMvc.perform(delete("/v1/tables/append_rollup"));
    }

    private void createRollup() throws Exception {
        IntegrationTables.upsertSource(mockMvc, ROLLUP, COLUMNS, "[\"deployment\",\"day\"]");
    }

    @Test
    void upsertByKeySourceMaterializesAsReplacingMergeTreeOverItsOrderingKey() throws Exception {
        createRollup();

        assertThatShowCreate()
                .contains("ReplacingMergeTree(_updated_at)")
                .contains("ORDER BY (deployment, day)");
    }

    @Test
    void rewrittenKeyReadsAsTheLatestValueRatherThanTwoRows() throws Exception {
        createRollup();

        // Two versions of the (deployment, day) key as separate (unmerged) inserts — a rollup bucket
        // recomputed with a new value. Without the collapsing engine this reads as two rows and the
        // measure would be double-counted; with it, the later write replaces the earlier one.
        execute("INSERT INTO usage_daily (deployment, day, call_count, _updated_at)"
                + " VALUES ('gpt-4','2026-01-01', 10, '2026-01-01 00:00:01.000')");
        execute("INSERT INTO usage_daily (deployment, day, call_count, _updated_at)"
                + " VALUES ('gpt-4','2026-01-01', 42, '2026-01-01 00:00:02.000')");

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"usage_daily","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"deployment"}},
                                   {"expr":{"type":"field","name":"call_count"}}],
                                 "page":{"type":"offset","offset":0,"limit":25,"include_total":true}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].deployment").value("gpt-4"))
                .andExpect(jsonPath("$.rows[0].call_count").value(42))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    void rollupIsQueryableLikeAnyOtherSource() throws Exception {
        createRollup();

        // A rollup is a source, so it needs no special case anywhere: it is listed for discovery and its
        // flat schema resolves — the whole argument for dropping the `aggregation` table kind.
        mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(ROLLUP)));
        mockMvc.perform(get("/v1/queries/entities/schema/" + ROLLUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[*].name", hasItem("call_count")));
    }

    @Test
    void versionColumnIsHiddenAndNotCallerSettable() throws Exception {
        createRollup();

        // Physically present (the engine dedups on it) but never exposed, never counted, never settable.
        assertThatShowCreate().contains("_updated_at");
        mockMvc.perform(get("/v1/tables/" + ROLLUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.write").value("upsert_by_key"))
                .andExpect(jsonPath("$.columns", hasSize(3)))
                .andExpect(jsonPath("$.columns[*].source_name", not(hasItem("_updated_at"))));
        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + ROLLUP + "')].column_count", hasItem(3)));

        mockMvc.perform(post("/v1/tables/" + ROLLUP + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rows":[{"deployment":"gpt-4","day":"2026-01-01","call_count":1,
                                          "_updated_at":"2026-01-01 00:00:00.000"}]}"""))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void writeDisciplineIsImmutable() throws Exception {
        createRollup();

        // The facet selects the engine, which cannot be ALTERed — so neither mutating surface accepts it.
        mockMvc.perform(patch("/v1/tables/" + ROLLUP + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"write\":\"append\"}"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(put("/v1/tables/" + ROLLUP).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"write\":\"append\"}"))
                .andExpect(status().isUnprocessableContent());
        // Neither attempt changed anything.
        mockMvc.perform(get("/v1/tables/" + ROLLUP))
                .andExpect(jsonPath("$.write").value("upsert_by_key"));
    }

    @Test
    void defineSchemaDoesNotAcceptTheFacet() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"append_rollup\",\"type\":\"source\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.write").value("append"));

        mockMvc.perform(post("/v1/tables/append_rollup/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":" + COLUMNS + ",\"ordering_key\":[\"deployment\"],"
                                + "\"write\":\"upsert_by_key\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void appendSourceStillMaterializesAsPlainMergeTree() throws Exception {
        IntegrationTables.source(mockMvc, "append_rollup", COLUMNS, "[\"deployment\"]");

        // The default is unchanged behaviour: no version column, no collapsing engine.
        Assertions.assertThat(showCreateTable("append_rollup"))
                .contains("MergeTree")
                .doesNotContain("ReplacingMergeTree")
                .doesNotContain("_updated_at");
    }

    @Test
    void seededSourcesDeclareTheEngineTheyWereBuiltWith() throws Exception {
        // Both seeded sources are hand-written ReplacingMergeTree(_ingested_at) DDL, so their sort tuple is
        // the ingest path's re-ingest dedup key. They were nonetheless seeded `append`, because write_mode
        // arrived with an append default and these rows predate it — and nothing caught it, since the read
        // path resolves FINAL against the engine rather than against the catalog. The facet is what an
        // operator reasons from, so a difference between a raw count and an ADAS read has to be explicable
        // from it.
        for (final String table : new String[] {"dial_usage_log", "rate_analytics"}) {
            Assertions.assertThat(showCreateTable(table))
                    .as("%s engine", table)
                    .contains("ReplacingMergeTree");
            mockMvc.perform(get("/v1/tables/" + table))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.write").value("upsert_by_key"));
        }
    }

    @Test
    void writeDisciplineIsRejectedForAnEnrichment() throws Exception {
        IntegrationTables.source(mockMvc, "append_rollup", COLUMNS, "[\"deployment\"]");

        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"append_labels","type":"enrichment","source_table":"append_rollup",
                                 "write":"upsert_by_key"}"""))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void enrichmentReportsTheDisciplineItsKindImplies() throws Exception {
        IntegrationTables.source(mockMvc, "append_rollup", COLUMNS, "[\"deployment\"]");
        IntegrationTables.enrichment(mockMvc, "append_labels", "append_rollup", "deployment", null);

        // The facet is not settable on an enrichment, so the stored column keeps its APPEND default — but the
        // engine collapses on the grain key, and the facet's contract is to describe the engine. Reporting the
        // default would make the one kind whose discipline nobody can declare the one the catalog gets wrong.
        Assertions.assertThat(showCreateTable("append_labels")).contains("ReplacingMergeTree");
        mockMvc.perform(get("/v1/tables/append_labels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.write").value("upsert_by_key"));
        mockMvc.perform(get("/v1/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == 'append_labels')].write", hasItem("upsert_by_key")));
    }

    @Test
    void aggregationTableTypeNoLongerExists() throws Exception {
        // Aggregation is a pipeline kind, not a table kind. The value is gone from the enum, so
        // the request fails to deserialize rather than creating an inert definition.
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"agg\",\"type\":\"aggregation\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/tables/agg")).andExpect(status().isNotFound());
    }

    private AbstractStringAssert<?> assertThatShowCreate() {
        return Assertions.assertThat(showCreateTable(ROLLUP));
    }
}
