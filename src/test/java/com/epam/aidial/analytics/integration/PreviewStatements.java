package com.epam.aidial.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs a preview's portable {@code statement} and {@code query} through the query API, the way a caller who copied
 * them would: {@code statement} on {@code /v1/queries/execute-sql}, {@code query} on {@code /v1/queries/execute}, and
 * {@code statement} through {@code /v1/queries/translate-sql} to compare with {@code query}.
 */
final class PreviewStatements {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PreviewStatements() {
    }

    static JsonNode body(String previewBody) throws Exception {
        return JSON.readTree(previewBody);
    }

    /** The rows {@code execute-sql} answers for the preview's {@code statement}; fails unless it answers 200. */
    static JsonNode runStatement(MockMvc mockMvc, String previewBody) throws Exception {
        final ObjectNode request = JSON.createObjectNode().put("sql", body(previewBody).get("statement").asText());
        return rows(mockMvc, "/v1/queries/execute-sql", request);
    }

    /** The rows {@code execute} answers for the preview's {@code query}; fails unless it answers 200. */
    static JsonNode runQuery(MockMvc mockMvc, String previewBody) throws Exception {
        return rows(mockMvc, "/v1/queries/execute", body(previewBody).get("query"));
    }

    /** The document {@code translate-sql} makes of the preview's {@code statement}. */
    static JsonNode translateStatement(MockMvc mockMvc, String previewBody) throws Exception {
        final ObjectNode request = JSON.createObjectNode().put("sql", body(previewBody).get("statement").asText());
        final String response = mockMvc.perform(post("/v1/queries/translate-sql").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("query");
    }

    private static JsonNode rows(MockMvc mockMvc, String path, JsonNode request) throws Exception {
        final String response = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("rows");
    }
}
