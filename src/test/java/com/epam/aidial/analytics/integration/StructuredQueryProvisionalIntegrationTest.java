package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the behavior of the <em>provisional</em> jOOQ-CLICKHOUSE constructs flagged as risky in the
 * structured-query design. Verified against a real ClickHouse, all now <strong>execute</strong> and
 * return correct results: the contains operators ({@code co}/{@code nc} case-sensitive,
 * {@code ico}/{@code inc} case-insensitive), the ordered-set
 * aggregates {@code percentile_cont}/{@code percentile_disc}, and {@code width_bucket} (the last two
 * groups after the render fixes in {@code FilterTranslator}/{@code ExprTranslator}). These assertions
 * catch any future change in rendering.
 */
class StructuredQueryProvisionalIntegrationTest extends AbstractClickHouseIntegrationTest {

    @BeforeEach
    void cleanSlate() {
        truncate();
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("embedding", "p1", 50)
                .insert();
    }

    private void execute(String body, ResultMatcher... matchers) throws Exception {
        final ResultActions actions =
                mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON).content(body));
        for (final ResultMatcher matcher : matchers) {
            actions.andExpect(matcher);
        }
    }

    @Test
    void containsOperatorsMatchPerCaseSensitivity() throws Exception {
        // ico is a case-insensitive substring match: 'CHAT' matches both 'chat' rows, not 'embedding'.
        String insensitive =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "ico", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "CHAT" } ] }
                }
                """;
        execute(insensitive, status().isOk(), jsonPath("$.rows", hasSize(2)));

        // co is the case-sensitive form: 'CHAT' matches nothing, 'hat' matches both 'chat' rows.
        String caseSensitiveUpper =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "co", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "CHAT" } ] }
                }
                """;
        execute(caseSensitiveUpper, status().isOk(), jsonPath("$.rows", hasSize(0)));

        String caseSensitiveLower =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "co", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "hat" } ] }
                }
                """;
        execute(caseSensitiveLower, status().isOk(), jsonPath("$.rows", hasSize(2)));
    }

    @Test
    void caseInsensitiveContainsMatchesSubstring() throws Exception {
        // 'MBED' is a substring of 'embedding' (case-insensitive) -> only the embedding row.
        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "ico", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "MBED" } ] }
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].event_kind").value("embedding"),
                jsonPath("$.rows[0].total_tokens").value(50));
    }

    @Test
    void caseInsensitiveNotContainsExcludesMatches() throws Exception {
        // inc is the case-insensitive negation: everything NOT containing 'CHAT' -> the embedding row only.
        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "inc", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "CHAT" } ] }
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].event_kind").value("embedding"));
    }

    @Test
    void percentileContinuousExecutes() throws Exception {
        // jOOQ's CLICKHOUSE dialect renders an executable ordered-set aggregate; assert it runs and
        // returns a single p50 over total_tokens [50,100,300] (median 100).
        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "fn", "name": "percentile_cont",
                                "args": [ { "type": "value", "value_type": "decimal", "value": "0.5" },
                                          { "type": "field", "name": "total_tokens" } ] }, "as": "p50" }
                  ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].p50").value(100.0));
    }

    @Test
    void percentileDiscreteExecutes() throws Exception {
        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "fn", "name": "percentile_disc",
                                "args": [ { "type": "value", "value_type": "decimal", "value": "0.5" },
                                          { "type": "field", "name": "total_tokens" } ] }, "as": "p50" }
                  ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].p50").value(100));
    }

    @Test
    void widthBucketBucketsValues() throws Exception {
        // width_bucket(total_tokens, 0, 1000, 4): width-250 buckets -> 50 & 100 fall in bucket 1, 300 in bucket 2.
        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "total_tokens" } },
                    { "expr": { "type": "fn", "name": "width_bucket",
                                "args": [ { "type": "field", "name": "total_tokens" },
                                          { "type": "value", "value_type": "long", "value": "0" },
                                          { "type": "value", "value_type": "long", "value": "1000" },
                                          { "type": "value", "value_type": "integer", "value": "4" } ] }, "as": "bucket" }
                  ],
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].total_tokens").value(50),
                jsonPath("$.rows[0].bucket").value(1),
                jsonPath("$.rows[1].total_tokens").value(100),
                jsonPath("$.rows[1].bucket").value(1),
                jsonPath("$.rows[2].total_tokens").value(300),
                jsonPath("$.rows[2].bucket").value(2));
    }

    /** Aggregate query: count() grouped by date_bin(amount,unit,request_time) as bucket, ordered by bucket. */
    private static String bucketCountQuery(int amount, String unit) {
        return """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": [ "bucket" ],
                  "select": [
                    { "expr": { "type": "fn", "name": "date_bin",
                                "args": [ { "type": "value", "value_type": "integer", "value": "%d" },
                                          { "type": "value", "value_type": "string", "value": "%s" },
                                          { "type": "field", "name": "request_time" } ] }, "as": "bucket" },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "n" }
                  ],
                  "sort": [ { "field": "bucket", "dir": "asc" } ]
                }
                """.formatted(amount, unit);
    }

    @Test
    void dateBinGroupsByFiveMinuteClockAlignedBuckets() throws Exception {
        // Timestamps chosen so the grouping DISCRIMINATES correct clock (:00) alignment from a wrong
        // data-relative origin — not merely that some 2-way split happens:
        //   correct 5-min buckets anchored to the clock ->  [12:00,12:05)={12:01,12:03}=2, [12:05,12:10)={12:05,12:08}=2  => (2, 2)
        //   buggy origin = earliest row (12:01)          ->  [12:01,12:06)={12:01,12:03,12:05}=3, [12:06,12:11)={12:08}=1  => (3, 1)
        // A wrong width also fails: 1-min -> 4 groups, 10-min -> 1 group, 6-min -> (3,1). So (2,2) pins
        // both the 5-minute width AND the alignment to the wall clock (12:05 starts a fresh bucket).
        truncate();
        fixture()
                .add(r -> r.eventKind("chat").requestTime("2026-06-01 12:01:00.000"))
                .add(r -> r.eventKind("chat").requestTime("2026-06-01 12:03:00.000"))
                .add(r -> r.eventKind("chat").requestTime("2026-06-01 12:05:00.000"))
                .add(r -> r.eventKind("chat").requestTime("2026-06-01 12:08:00.000"))
                .insert();

        execute(
                bucketCountQuery(5, "minute"),
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].bucket").value("2026-06-01T12:00:00Z"),
                jsonPath("$.rows[0].n").value(2),
                jsonPath("$.rows[1].bucket").value("2026-06-01T12:05:00Z"),
                jsonPath("$.rows[1].n").value(2));
    }

    @Test
    void dateBinBucketBoundaryIsHalfOpenAtTheIntervalEdge() throws Exception {
        // Two rows 1 ms apart straddling a 5-minute clock edge must fall in different buckets: 12:04:59.999
        // in [12:00,12:05), 12:05:00.000 in [12:05,12:10). Proves the boundary sits exactly on the :05 clock
        // mark, is half-open [start, start+interval), and that the DateTime64(3) sub-second part is truncated.
        truncate();
        fixture()
                .add(r -> r.eventKind("chat").requestTime("2026-06-01 12:04:59.999"))
                .add(r -> r.eventKind("chat").requestTime("2026-06-01 12:05:00.000"))
                .insert();

        execute(
                bucketCountQuery(5, "minute"),
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].bucket").value("2026-06-01T12:00:00Z"),
                jsonPath("$.rows[0].n").value(1),
                jsonPath("$.rows[1].bucket").value("2026-06-01T12:05:00Z"),
                jsonPath("$.rows[1].n").value(1));
    }

    @Test
    void jsonExtractFunctionsRenderAndExecuteOnRealClickHouse() throws Exception {
        // Locks the JSONExtract* renderings: each DSL function must produce valid ClickHouse and the
        // documented type, over both a top-level and a nested literal path.
        truncate();
        seedBody("11111111-1111-1111-1111-111111111111",
                "{\"model\":\"gpt-4o\",\"temperature\":0.5,\"stream\":true,\"usage\":{\"total_tokens\":42}}");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "model" } ] }, "as": "model" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "usage" },
                        { "type": "value", "value_type": "string", "value": "total_tokens" } ] }, "as": "tokens" },
                    { "expr": { "type": "fn", "name": "json_extract_double", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "temperature" } ] }, "as": "temp" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "stream" } ] }, "as": "stream" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].model").value("gpt-4o"),
                jsonPath("$.rows[0].tokens").value(42),
                jsonPath("$.rows[0].temp").value(0.5),
                jsonPath("$.rows[0].stream").value(true));
    }

    @Test
    void jsonExtractOverMissingFieldYieldsTheTypeDefault() throws Exception {
        // ClickHouse's JSONExtract* are total: an absent path or unparseable JSON yields the type
        // default rather than an error. Class-1 enrichment relies on this (no per-row dead-letter).
        truncate();
        seedBody("22222222-2222-2222-2222-222222222222", "not json at all");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "absent_field" } ] }, "as": "n" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].n").value(0));
    }

    /** Seeds one row carrying a {@code request_body} the Fixture builder does not model. */
    private void seedBody(String eventId, String requestBody) {
        // One timestamp, held in a variable and written to both rows. Two now64(3) calls in two
        // statements are milliseconds apart, and the payload table is partition-aligned with its parent —
        // so the grain join bounds the payload by the parent's value and a drifting clock loses the row.
        final String requestTime = "'" + fixtureTime("00:00:00.000") + "'";
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id) VALUES (toUUID('" + eventId + "'),'p','d', " + requestTime + ",'t','s')");
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " request_body) VALUES (toUUID('" + eventId + "'), " + requestTime + ", 't','s','"
                + requestBody.replace("\\", "\\\\").replace("'", "\\'") + "')");
    }
}
