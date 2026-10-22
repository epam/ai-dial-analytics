package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end filter coverage for {@code POST /v1/queries/execute}: the comparison/logical operators,
 * value types, and scalar functions not exercised by {@link StructuredQueryExecutionIntegrationTest},
 * each asserted against the rows real ClickHouse returns. Per-test data via {@link #cleanSlate()}.
 */
class StructuredQueryFilterIntegrationTest extends AbstractClickHouseIntegrationTest {

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    private void execute(String body, ResultMatcher... matchers) throws Exception {
        final ResultActions actions =
                mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content(body));
        for (final ResultMatcher matcher : matchers) {
            actions.andExpect(matcher);
        }
    }

    // --- comparison operators -----------------------------------------------------------------------

    @Test
    void notEqualFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("embedding", "p1", 50)
                .row("completion", "p2", 500)
                .insert();

        execute(
                comparison("ne", "event_kind", "string", "chat"),
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].event_kind").value("embedding"),
                jsonPath("$.rows[1].event_kind").value("completion"));
    }

    @Test
    void lessThanFilter() throws Exception {
        fixture()
                .row("chat", "p1", 50)
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .insert();

        execute(
                comparison("lt", "total_tokens", "long", "200"),
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].total_tokens").value(50),
                jsonPath("$.rows[1].total_tokens").value(100));
    }

    @Test
    void greaterThanFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("chat", "p1", 300)
                .insert();

        execute(
                comparison("gt", "total_tokens", "long", "200"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(300));
    }

    @Test
    void lessOrEqualFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .row("chat", "p1", 300)
                .insert();

        execute(
                comparison("le", "total_tokens", "long", "200"),
                status().isOk(),
                jsonPath("$.rows", hasSize(2)));
    }

    // --- logical operators --------------------------------------------------------------------------

    @Test
    void orFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("embedding", "p1", 50)
                .row("chat", "p1", 300)
                .row("completion", "p2", 500)
                .insert();

        // event_kind = 'embedding' OR total_tokens > 250  -> embedding(50), chat(300), completion(500)
        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "or", "args": [
                      { "op": "eq", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "embedding" } ] },
                      { "op": "gt", "args": [ { "type": "field", "name": "total_tokens" }, { "type": "value", "value_type": "long", "value": "250" } ] }
                  ]},
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].total_tokens").value(50),
                jsonPath("$.rows[1].total_tokens").value(300),
                jsonPath("$.rows[2].total_tokens").value(500));
    }

    @Test
    void notFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "not", "args": [
                      { "op": "eq", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "chat" } ] }
                  ]}
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].event_kind").value("embedding"));
    }

    // --- null checks on a non-nullable column -------------------------------------------------------

    @Test
    void isNullOnNonNullableColumnMatchesNothing() throws Exception {
        // chat_id is a non-Nullable String (default ''), so IS NULL never matches.
        fixture()
                .row("chat", "p1", 100)
                .row("embedding", "p1", 50)
                .insert();

        execute(
                comparisonNull("eq", "chat_id"),
                status().isOk(),
                jsonPath("$.rows", hasSize(0)));
    }

    @Test
    void isNotNullOnNonNullableColumnMatchesEverything() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("embedding", "p1", 50)
                .insert();

        execute(
                comparisonNull("ne", "chat_id"),
                status().isOk(),
                jsonPath("$.rows", hasSize(2)));
    }

    // --- value types --------------------------------------------------------------------------------

    @Test
    void integerValueType() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .insert();

        execute(
                comparison("eq", "total_tokens", "integer", "100"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(100));
    }

    @Test
    void decimalValueType() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .insert();

        execute(
                comparison("ge", "total_tokens", "decimal", "150.5"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(200));
    }

    @Test
    void uuidValueType() throws Exception {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(100).traceId(uuid))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(200).traceId("00000000-0000-0000-0000-000000000000"))
                .insert();

        execute(
                comparison("eq", "trace_id", "uuid", uuid),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(100));
    }

    @Test
    void timestampValueTypeFiltersByInstant() throws Exception {
        // request_time is DateTime64(3); timestamp literals are epoch-millis compared against it.
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(100).requestTime("2026-06-01 10:00:00.000"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(300).requestTime("2026-06-03 10:00:00.000"))
                .insert();

        long cutoff = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli();
        execute(
                comparison("ge", "request_time", "timestamp", Long.toString(cutoff)),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(300));
    }

    // --- scalar functions ---------------------------------------------------------------------------

    @Test
    void lowerFunctionInFilter() throws Exception {
        fixture()
                .row("CHAT", "p1", 100)
                .row("EMBEDDING", "p1", 50)
                .insert();

        execute(
                fnComparison("eq", "lower", "event_kind", "string", "chat"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(100));
    }

    @Test
    void upperFunctionInFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("embedding", "p1", 50)
                .insert();

        execute(
                fnComparison("eq", "upper", "event_kind", "string", "CHAT"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(100));
    }

    @Test
    void lengthFunctionInFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "proj", 200)
                .insert();

        execute(
                fnComparison("eq", "length", "project_id", "integer", "2"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(100));
    }

    @Test
    void trimFunctionInFilter() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId(" p1 ").totalTokens(100))
                .add(r -> r.eventKind("chat").projectId("p2").totalTokens(200))
                .insert();

        execute(
                fnComparison("eq", "trim", "project_id", "string", "p1"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(100));
    }

    @Test
    void absFunctionInFilter() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 200)
                .insert();

        execute(
                fnComparison("eq", "abs", "total_tokens", "long", "100"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].total_tokens").value(100));
    }

    // --- edge cases ---------------------------------------------------------------------------------

    @Test
    void emptyResultSet() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .insert();

        execute(
                comparison("eq", "event_kind", "string", "nonexistent"),
                status().isOk(),
                jsonPath("$.rows", hasSize(0)));
    }

    @Test
    void clickHouseRejectionMapsToBadRequest() throws Exception {
        // abs() over a String column renders valid jOOQ but ClickHouse rejects it -> ClickHouseQueryException -> 400.
        fixture()
                .row("chat", "p1", 100)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [
                      { "type": "fn", "name": "abs", "args": [ { "type": "field", "name": "project_id" } ] },
                      { "type": "value", "value_type": "long", "value": "1" }
                  ]}
                }
                """;

        execute(
                body,
                status().isBadRequest(),
                jsonPath("$.status").value(400),
                jsonPath("$.error").value("bad_request"));
    }

    // --- request-body helpers -----------------------------------------------------------------------

    private static String comparison(String op, String field, String valueType, String value) {
        return """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "%s", "args": [
                      { "type": "field", "name": "%s" },
                      { "type": "value", "value_type": "%s", "value": "%s" }
                  ]},
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ]
                }
                """.formatted(op, field, valueType, value);
    }

    private static String comparisonNull(String op, String field) {
        return """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "%s", "args": [
                      { "type": "field", "name": "%s" },
                      { "type": "value", "value_type": "null", "value": null }
                  ]}
                }
                """.formatted(op, field);
    }

    private static String fnComparison(String op, String fn, String field, String valueType, String value) {
        return """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "%s", "args": [
                      { "type": "fn", "name": "%s", "args": [ { "type": "field", "name": "%s" } ] },
                      { "type": "value", "value_type": "%s", "value": "%s" }
                  ]}
                }
                """.formatted(op, fn, field, valueType, value);
    }
}
