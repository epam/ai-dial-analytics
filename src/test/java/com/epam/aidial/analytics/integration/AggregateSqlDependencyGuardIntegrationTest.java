package com.epam.aidial.analytics.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog's dependency guards over an {@code aggregate} pipeline declared as SQL: a schema patch or a table delete
 * that would pull a column or an enrichment out from under the stored statement is refused with 409, found by a token
 * scan of the statement rather than by parsing it — including a bare source column, which the form path's qualified
 * scan never saw, and an enrichment referenced nowhere but inside the text.
 *
 * <p>Requires a running Docker daemon.
 */
class AggregateSqlDependencyGuardIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PIPELINES = "/v1/pipelines";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SOURCE = "gauge_events";
    private static final String ENRICHMENT = "gauge_owner";
    private static final String ROLLUP = "gauge_region_totals";
    private static final String PIPELINE = "gauge_region_totals_rollup";

    /**
     * Reads {@code region} and {@code score} bare, and the enrichment only through an aliased quoted name. The target's
     * {@code total} names an input column too, which the statement does not read.
     */
    private static final String SQL = "INSERT INTO " + ROLLUP + " (region, total, owners)"
            + " SELECT region, sum(score), count(e.\"" + ENRICHMENT + ".owner\") FROM " + SOURCE + " e GROUP BY 1";

    @BeforeEach
    void createTables() throws Exception {
        dropAll();
        IntegrationTables.source(mockMvc, SOURCE, """
                [{"source_name":"event_id","name":"event_id","type":"string"},
                 {"source_name":"region","name":"region","type":"string"},
                 {"source_name":"score","name":"score","type":"long"},
                 {"source_name":"total","name":"total","type":"long"},
                 {"source_name":"at","name":"at","type":"timestamp"}]""",
                "[\"event_id\"]", "event_id", "at");
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"owner\",\"name\":\"owner\",\"type\":\"string\"},"
                        + "{\"source_name\":\"tier\",\"name\":\"tier\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.upsertSource(mockMvc, ROLLUP, """
                [{"source_name":"region","name":"region","type":"string"},
                 {"source_name":"total","name":"total","type":"long","nullable":true},
                 {"source_name":"owners","name":"owners","type":"long"}]""",
                "[\"region\"]");
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", PIPELINE);
        body.put("kind", "aggregate");
        body.put("target", ROLLUP);
        body.put("sql", SQL);
        body.put("trigger", Map.of("kind", "schedule", "cron", "0 0 * * * *"));
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
        mockMvc.perform(delete("/v1/tables/" + SOURCE));
    }

    @Test
    void droppingOrRenamingSourceColumnReadOnlyBareInTheStatementIsRefused() throws Exception {
        patchSchema(SOURCE, "{\"drop\":[\"score\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)));

        patchSchema(SOURCE, "{\"rename\":[{\"from\":\"region\",\"to\":\"area\"}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)));
    }

    @Test
    void targetColumnListNameDoesNotPinTheInputColumnOfTheSameName() throws Exception {
        patchSchema(SOURCE, "{\"drop\":[\"total\"]}").andExpect(status().isOk());
    }

    @Test
    void droppingTheEnrichmentColumnTheStatementNamesBehindAnAliasIsRefused() throws Exception {
        patchSchema(ENRICHMENT, "{\"drop\":[\"owner\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)));

        patchSchema(ENRICHMENT, "{\"drop\":[\"tier\"]}").andExpect(status().isOk());
    }

    @Test
    void deletingAnEnrichmentOnlyTheStatementReferencesIsRefused() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(PIPELINE)));

        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT)).andExpect(status().isNoContent());
    }

    private ResultActions patchSchema(String table, String body) throws Exception {
        return mockMvc.perform(patch("/v1/tables/" + table + "/schema").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
