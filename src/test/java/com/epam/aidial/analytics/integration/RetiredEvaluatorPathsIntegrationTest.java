package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The evaluator surface is gone (capability {@code enrichment-registry}, "No evaluator surface remains"):
 * every {@code /v1/evaluators} path is answered as any unmapped path is, and neither archive error code is
 * produced. An enrichment's definition is read from its pipeline's {@code transform}. Runs in the default
 * {@code none} security mode. Requires a running Docker daemon.
 */
class RetiredEvaluatorPathsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String VERSION_PATH = "/v1/evaluators/toxicity/versions/1";

    @Test
    void theEvaluatorListingIsAnUnmappedPath() throws Exception {
        mockMvc.perform(get("/v1/evaluators"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void readingAnEvaluatorOrOneVersionIsAnUnmappedPath() throws Exception {
        mockMvc.perform(get("/v1/evaluators/toxicity"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(get(VERSION_PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    /** A write verb is answered as on any unmapped path, not with either archive code. */
    @Test
    void writingOneVersionIsAnUnmappedPath() throws Exception {
        for (RequestBuilder request : new RequestBuilder[] {put(VERSION_PATH), delete(VERSION_PATH)}) {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not_found"));
        }
    }
}
