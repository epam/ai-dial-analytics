package com.epam.aidial.analytics.integration;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test helper for the create → define-and-materialize lifecycle. Integration tests that just need a
 * materialized source/enrichment to query or write use these one-liners instead of spelling out the two
 * calls ({@code POST /v1/tables} identity, {@code POST /{name}/schema} define + materialize) each time.
 * Tests that assert the lifecycle contract itself drive the endpoints directly rather than through this
 * helper.
 */
final class IntegrationTables {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private IntegrationTables() {
    }

    /**
     * Creates and materializes a source declaring no scan metadata — not scannable, which is what a fixture
     * that never runs a rule over the table should be.
     *
     * <p>Deliberately no default pair: the identity half is a claim that a column is unique per row, and a
     * helper cannot know that. Guessing it (the first ordering-key column, say) is how a suite ends up
     * asserting a total order that does not hold — under keyset paging that silently skips rows rather than
     * failing. A test that wants the pair passes it through the overload below and means it.
     */
    static void source(MockMvc mvc, String name, String columnsJson, String orderingKeyJson) throws Exception {
        source(mvc, name, columnsJson, orderingKeyJson, null, null);
    }

    /**
     * As above, declaring an explicit {@code identity_column}/{@code version_column} pair — pass {@code null}
     * for both to declare none. The version must be a non-nullable {@code timestamp} column of this table
     * (or {@code _updated_at} on an {@code upsert_by_key} table) and the identity must be genuinely unique
     * per row; both are checked as far as the API can see them.
     */
    static void source(MockMvc mvc, String name, String columnsJson, String orderingKeyJson,
                       String identityColumn, String versionColumn) throws Exception {
        mvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));
        mvc.perform(post("/v1/tables/" + name + "/schema").contentType(JSON)
                        .content("{\"columns\":" + columnsJson + ",\"ordering_key\":" + orderingKeyJson
                                + scanMetadata(identityColumn, versionColumn) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    /** The two schema members, or nothing at all when the fixture declares no scan metadata. */
    private static String scanMetadata(String identityColumn, String versionColumn) {
        if (identityColumn == null && versionColumn == null) {
            return "";
        }
        return ",\"identity_column\":\"" + identityColumn + "\",\"version_column\":\"" + versionColumn + "\"";
    }

    /**
     * Creates and materializes an {@code upsert_by_key} source — a {@code ReplacingMergeTree(_updated_at)}
     * whose {@code ordering_key} is its unique key, the shape a rollup target uses.
     *
     * <p>Declares no scan metadata. A rollup's row identity is its whole {@code ordering_key}, so a
     * multi-column-grain rollup has no single column that identifies a row: naming one member of the key
     * would be a false total order, and every refresh stamps one {@code _updated_at} across all the rows it
     * writes, so rows sharing that member would be skipped by the keyset seek. A single-column-grain rollup
     * meant to be scanned declares the pair explicitly, through the source overload above.
     */
    static void upsertSource(MockMvc mvc, String name, String columnsJson, String orderingKeyJson)
            throws Exception {
        mvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"source\",\"write\":\"upsert_by_key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.write").value("upsert_by_key"));
        mvc.perform(post("/v1/tables/" + name + "/schema").contentType(JSON)
                        .content("{\"columns\":" + columnsJson + ",\"ordering_key\":" + orderingKeyJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    /**
     * Creates and materializes an enrichment on {@code sourceTable} keyed by {@code grainKey}.
     * {@code columnsJson} is a raw JSON array of user columns (may be {@code null} for a bare grain shell).
     */
    static void enrichment(MockMvc mvc, String name, String sourceTable, String grainKey, String columnsJson)
            throws Exception {
        mvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"enrichment\",\"source_table\":\""
                                + sourceTable + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));
        final String cols = columnsJson == null ? "[]" : columnsJson;
        mvc.perform(post("/v1/tables/" + name + "/schema").contentType(JSON)
                        .content("{\"grain_key\":\"" + grainKey + "\",\"columns\":" + cols + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }
}
