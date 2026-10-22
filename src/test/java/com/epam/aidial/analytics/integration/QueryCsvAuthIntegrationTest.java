package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CSV execution endpoints in {@code oidc} mode, over the full application and real ClickHouse: they
 * sit behind the same {@code /v1/**} application-role rule and the same sensitive-column chokepoint as
 * {@code /execute} and {@code /execute-sql}, with no rule of their own — and every refusal stays an
 * {@code ErrorView} even though the client asked only for CSV.
 */
class QueryCsvAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String EXECUTE_CSV = "/v1/queries/execute-csv";
    private static final String EXECUTE_SQL_CSV = "/v1/queries/execute-sql-csv";

    /** Matches no row, so the file is the header alone whatever other tests have written. */
    private static final String NO_ROWS_DSL = """
            {"entity":"dial_usage_log","mode":"row",
             "select":[{"expr":{"type":"field","name":"project_id"}},
                       {"expr":{"type":"field","name":"deployment"},"as":"model"}],
             "filter":{"op":"eq","args":[{"type":"field","name":"project_id"},
                                         {"type":"value","value_type":"string","value":"csv-auth-no-such-project"}]}}
            """;

    private static final String SENSITIVE_DSL = """
            {"entity":"dial_usage_log","mode":"row",
             "select":[{"expr":{"type":"field","name":"dial_usage_log_payload.request_body"}}]}
            """;

    private static String fullAdminToken() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    private static String readOnlyToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "readonly-user", "roles", List.of("viewer"), "email", "readonly@test"));
    }

    private static MockHttpServletRequestBuilder csvRequest(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).accept("text/csv").content(body);
    }

    @Test
    void unauthenticatedCallerGets401ErrorView() throws Exception {
        mockMvc.perform(csvRequest(EXECUTE_CSV, NO_ROWS_DSL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("unauthorized"));

        mockMvc.perform(csvRequest(EXECUTE_SQL_CSV, "{\"sql\":\"SELECT project_id FROM dial_usage_log\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void readOnlyCallerGetsHeaderOnlyFileForEmptyResult() throws Exception {
        mockMvc.perform(csvRequest(EXECUTE_CSV, NO_ROWS_DSL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().string("project_id,model\r\n"));

        mockMvc.perform(csvRequest(EXECUTE_SQL_CSV, """
                        {"sql":"SELECT project_id, count(*) FROM dial_usage_log \
                        WHERE project_id = 'csv-auth-no-such-project' GROUP BY project_id"}""")
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isOk())
                .andExpect(content().string("project_id,count\r\n"));
    }

    @Test
    void sensitiveColumnIsUnknownFieldForReadOnlyCaller() throws Exception {
        mockMvc.perform(csvRequest(EXECUTE_CSV, SENSITIVE_DSL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("bad_request"));

        mockMvc.perform(csvRequest(EXECUTE_SQL_CSV,
                        "{\"sql\":\"SELECT \\\"dial_usage_log_payload.request_body\\\" FROM dial_usage_log\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void fullAdminExecutesSqlToCsv() throws Exception {
        mockMvc.perform(csvRequest(EXECUTE_SQL_CSV, """
                        {"sql":"SELECT project_id FROM dial_usage_log WHERE project_id = 'csv-auth-no-such-project'"}""")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(content().string("project_id\r\n"));
    }
}
