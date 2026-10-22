package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the grain-based enrichment join against a real PostgreSQL + ClickHouse. Creates
 * a source ({@code widget_metrics}) and an enrichment ({@code widget_labels}) — the enrichment is created
 * as a grain shell keyed on the source's ordering-key column {@code widget_id}, then its {@code label}
 * column is added via {@code PATCH .../schema}. Writes rows to both via {@code POST /v1/tables/{name}/rows},
 * then runs a structured query that selects an enrichment column and asserts the {@code LEFT JOIN} on
 * {@code source.widget_id = enrichment.widget_id} resolves. Also covers the write endpoint's validation.
 *
 * <p>Requires a running Docker daemon.
 */
class EnrichmentJoinIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "widget_metrics";
    private static final String ENRICHMENT = "widget_labels";
    private static final String ENRICHMENT2 = "widget_tags";

    @BeforeEach
    void createSourceAndEnrichment() throws Exception {
        // Source: widget_id (the grain) + score, ordered by widget_id — created, drafted, materialized.
        IntegrationTables.source(mockMvc, SOURCE,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]",
                "[\"widget_id\"]");
        // Enrichment keyed on the source's ordering-key column widget_id, with its `label` column drafted
        // in and materialized in one shot.
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "widget_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
    }

    @AfterEach
    void cleanUp() throws Exception {
        // Enrichments first — deleting a source with dependents is blocked (409). Deleting a table that a
        // given test never created is a harmless 404 (no status assertion here).
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT2));
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
    }

    @Test
    void enrichmentColumnResolvesViaGrainJoin() throws Exception {
        // Two source rows; only w1 has a label, so the join must enrich w1 and leave w2 unmatched.
        mockMvc.perform(post("/v1/tables/" + SOURCE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10},{\"widget_id\":\"w2\",\"score\":20}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(2));
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"alpha\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));

        // Selecting the enrichment column (qualified `widget_labels.label`) forces the LEFT JOIN on the
        // shared grain key.
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"widget_metrics","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"widget_id"}},
                                   {"expr":{"type":"field","name":"score"}},
                                   {"expr":{"type":"field","name":"widget_labels.label"}}],
                                 "sort":[{"field":"widget_id","dir":"asc"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].widget_id").value("w1"))
                .andExpect(jsonPath("$.rows[0].score").value(10))
                .andExpect(jsonPath("$.rows[0]['widget_labels.label']").value("alpha"))
                .andExpect(jsonPath("$.rows[1].widget_id").value("w2"))
                .andExpect(jsonPath("$.rows[1].score").value(20));
        // w2 has no enrichment row; its nullable `widget_labels.label` is the join miss (null), not "alpha".
    }

    @Test
    void nullEnrichmentColumnIsPresentInRowAsNull() throws Exception {
        // Reproduces the reporter's query: select an enrichment column and filter it `eq null`. w2 has no
        // enrichment row, so its LEFT-JOINed widget_labels.label is null and matches the IS NULL filter.
        // The projected column MUST still appear in the row as a null cell (not be dropped from the JSON).
        mockMvc.perform(post("/v1/tables/" + SOURCE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10},{\"widget_id\":\"w2\",\"score\":20}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"alpha\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"widget_metrics","mode":"row",
                                 "filter":{"op":"and","args":[
                                   {"op":"eq","args":[
                                     {"type":"field","name":"widget_labels.label"},
                                     {"type":"value","value_type":"null","value":null}]}]},
                                 "select":[
                                   {"expr":{"type":"field","name":"widget_id"}},
                                   {"expr":{"type":"field","name":"widget_labels.label"}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].widget_id").value("w2"))
                // The key must be present with a null value; before the fix it was dropped entirely
                // (a missing path here would fail rather than match nullValue()).
                .andExpect(jsonPath("$.rows[0]['widget_labels.label']").value(nullValue()));
    }

    @Test
    void omittedProjectionReturnsSourceColumnsOnly() throws Exception {
        // A row query with no `select` returns the source's own columns; enrichment columns are opt-in
        // (must be named), so no join is emitted, `label` is absent, and the query no longer errors.
        mockMvc.perform(post("/v1/tables/" + SOURCE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10},{\"widget_id\":\"w2\",\"score\":20}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"alpha\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"widget_metrics","mode":"row",
                                 "page":{"type":"offset","offset":0,"limit":25,"include_total":false}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].widget_id").exists())
                .andExpect(jsonPath("$.rows[0].score").exists())
                .andExpect(jsonPath("$.rows[0]['widget_labels.label']").doesNotExist());
    }

    @Test
    void filterOnEnrichmentColumnSelectsMatchedSourceRow() throws Exception {
        mockMvc.perform(post("/v1/tables/" + SOURCE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10},{\"widget_id\":\"w2\",\"score\":20}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"alpha\"}]}"))
                .andExpect(status().isOk());

        // Filtering on the enrichment column proves the join feeds the WHERE clause: only w1 matches.
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"widget_metrics","mode":"row",
                                 "filter":{"op":"eq","args":[
                                   {"type":"field","name":"widget_labels.label"},
                                   {"type":"value","value_type":"string","value":"alpha"}]},
                                 "select":[{"expr":{"type":"field","name":"widget_id"}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].widget_id").value("w1"));
    }

    @Test
    void twoEnrichmentsExposingTheSameColumnNameAreBothQueryable() throws Exception {
        // A second enrichment on the same source also exposing a `label` column. Before table-name
        // qualification this was rejected as a cross-family exposed-name collision; now both are accepted
        // and surfaced as distinct qualified flat fields (widget_labels.label, widget_tags.label).
        IntegrationTables.enrichment(mockMvc, ENRICHMENT2, SOURCE, "widget_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");

        mockMvc.perform(post("/v1/tables/" + SOURCE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"alpha\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT2 + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"red\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"widget_metrics","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"widget_id"}},
                                   {"expr":{"type":"field","name":"widget_labels.label"}},
                                   {"expr":{"type":"field","name":"widget_tags.label"}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].widget_id").value("w1"))
                .andExpect(jsonPath("$.rows[0]['widget_labels.label']").value("alpha"))
                .andExpect(jsonPath("$.rows[0]['widget_tags.label']").value("red"));
    }

    @Test
    void sourceColumnAndEnrichmentColumnOfTheSameNameAreDistinctFields() throws Exception {
        // The source also gets a `label` column. The source's stays bare (`label`); the enrichment's is
        // qualified (`widget_labels.label`); a query can project both as distinct fields.
        mockMvc.perform(patch("/v1/tables/" + SOURCE + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\",\"nullable\":true}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/tables/" + SOURCE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10,\"label\":\"src-label\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/tables/" + ENRICHMENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"enr-label\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"widget_metrics","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"widget_id"}},
                                   {"expr":{"type":"field","name":"label"}},
                                   {"expr":{"type":"field","name":"widget_labels.label"}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].label").value("src-label"))
                .andExpect(jsonPath("$.rows[0]['widget_labels.label']").value("enr-label"));
    }

    @Test
    void writeWithUnknownColumnIsRejected() throws Exception {
        mockMvc.perform(post("/v1/tables/" + SOURCE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"bogus\":1}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void writeToUnknownTableIsRejected() throws Exception {
        mockMvc.perform(post("/v1/tables/widget_ghost/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\"}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void droppingTheGrainKeyIsRejected() throws Exception {
        // The grain key is hidden plumbing and cannot be evolved away.
        mockMvc.perform(patch("/v1/tables/" + ENRICHMENT + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"widget_id\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }
}
