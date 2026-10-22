package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two <b>array-typed</b> JSON extractions over a text column read across the payload grain join, against
 * real ClickHouse. The join is a {@code LEFT} one, so {@code request_body} arrives {@code Nullable(String)} —
 * and ClickHouse refuses {@code JSONExtract(…, 'Array(String)')} and {@code JSONExtractKeysAndValues} over a
 * {@code Nullable} operand at analysis time ({@code Code 43}), where the scalar extractors are fine. Found by
 * executing the {@code GET /v1/queries/sql-functions} examples: both translated, neither ran.
 *
 * <p>Two rows, because the absent document is the half the fix has to get right: a usage-log row with no
 * payload row answers the documented empty array and empty string, not a null and not an error.
 *
 * <p>Requires a running Docker daemon.
 */
class PayloadJsonTextExtractionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String BODY = "\"dial_usage_log_payload.request_body\"";

    @BeforeEach
    void seed() {
        truncate();
        execute("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, chat_id, trace_id, deployment)"
                + " VALUES (toUUID('" + eventIdFor("trace-json-1") + "'), '" + fixtureTime("00:00:00.000")
                + "', 'chat', 'p1', 'c1', 'trace-json-1', 'gpt-4'), (toUUID('" + eventIdFor("trace-json-2") + "'), '"
                + fixtureTime("00:00:00.000") + "', 'chat', 'p1', 'c2', 'trace-json-2', 'gpt-4')");
        // Only the first row has a payload; the second is the LEFT JOIN's unmatched side.
        seedPayload("trace-json-1", "request_body", "'{\"Model\":\"gpt-4o\",\"tags\":[\"a\",\"b\"]}'");
    }

    private ResultActions executeSql(String sql) throws Exception {
        final String body = "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void arrayExtractionOverTheJoinedBodyRuns() throws Exception {
        executeSql("SELECT trace_id, json_extract_array(" + BODY + ", 'tags') AS tags FROM dial_usage_log ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].tags").value(contains("a", "b")))
                .andExpect(jsonPath("$.rows[1].tags", hasSize(0)));
    }

    @Test
    void caseInsensitiveExtractionOverTheJoinedBodyRuns() throws Exception {
        executeSql("SELECT trace_id, json_extract_string_ci(" + BODY + ", 'model') AS model FROM dial_usage_log ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].model").value("gpt-4o"))
                .andExpect(jsonPath("$.rows[1].model").value(""));
    }
}
