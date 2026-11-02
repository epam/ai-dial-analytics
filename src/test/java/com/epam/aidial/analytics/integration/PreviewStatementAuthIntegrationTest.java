package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A preview's portable statement grants nothing: running it goes through the query API's own checks, whoever copied it
 * from whom. Preview itself reads the input as its caller, so only a caller who may read the input gets a statement at
 * all; the case that matters is the statement handed to someone who may not.
 *
 * <p>Requires a running Docker daemon.
 */
class PreviewStatementAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PIPELINES = "/v1/pipelines";
    private static final String ROLLUP = "authz_preview_rollup";
    private static final String PIPELINE = "authz-preview-rollup";

    private static final String DECLARATION = """
            "target":"authz_preview_rollup",
            "inputs":["dial_usage_log"],
            "group_by":[{"column":"deployment"}],
            "measures":[{"name":"call_count","fn":"count"}],
            "trigger":{"kind":"schedule","cron":"0 0 * * * *"}""";

    @BeforeEach
    void setUp() throws Exception {
        mockMvc.perform(as(admin(), post("/v1/tables"))
                        .content("{\"name\":\"" + ROLLUP + "\",\"type\":\"source\",\"write\":\"upsert_by_key\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(as(admin(), post("/v1/tables/" + ROLLUP + "/schema"))
                        .content("{\"columns\":[{\"source_name\":\"deployment\",\"name\":\"deployment\",\"type\":\"string\"},"
                                + "{\"source_name\":\"call_count\",\"name\":\"call_count\",\"type\":\"long\"}],"
                                + "\"ordering_key\":[\"deployment\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(admin(), post(PIPELINES))
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\"," + DECLARATION + "}"))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(as(admin(), delete(PIPELINES + "/" + PIPELINE)));
        mockMvc.perform(as(admin(), delete("/v1/tables/" + ROLLUP)));
    }

    @Test
    void readerRunsTheCopiedStatement() throws Exception {
        final String statement = previewStatement(role("registry"));

        mockMvc.perform(as(role("registry"), post("/v1/queries/execute-sql")).content(sql(statement)))
                .andExpect(status().isOk());
    }

    @Test
    void copiedStatementIsRefusedForCallerWithoutRead() throws Exception {
        // Previewed by a caller who may read the input, run by one who may see its schema but not its rows: refused
        // exactly as any query over a table the caller may not read — as an unknown relation, not as a 403 that
        // would confirm the table exists.
        final String statement = previewStatement(role("registry"));

        mockMvc.perform(as(role("drafter"), post("/v1/queries/execute-sql")).content(sql(statement)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void callerWithoutReadCannotPreview() throws Exception {
        // The sample reads the input as its caller, so the preview is refused before any statement is rendered.
        mockMvc.perform(as(role("drafter"), post(PIPELINES + "/" + PIPELINE + "/preview")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statement").doesNotExist());
    }

    private String previewStatement(String token) throws Exception {
        final String body = mockMvc.perform(as(token, post(PIPELINES + "/" + PIPELINE + "/preview")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statement", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("statement").asText();
    }

    private static String sql(String statement) throws Exception {
        return JSON.writeValueAsString(Map.of("sql", statement));
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON);
    }

    private static String admin() {
        return role("admin");
    }

    private static String role(String role) {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", role + "-user", "roles", List.of(role), "email", role + "@test"));
    }
}
