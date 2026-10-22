package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end execution tests for {@code POST /v1/queries/execute} against a real ClickHouse. Each test
 * drives the production stack (controller -> service -> repository -> executor -> ClickHouse client)
 * and asserts the actual rows/aggregates/paging ClickHouse computes — unlike
 * {@code DialUsageLogQueryRenderTest}, which only asserts the rendered SQL string.
 *
 * <p>Every test owns its data: {@link #cleanSlate()} truncates {@code dial_usage_log} before each test,
 * and each test then inserts only the rows it asserts on. There is no shared fixture, so a test's
 * expected values are self-contained and tests cannot interfere with one another.
 */
class StructuredQueryExecutionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    /** Raw ClickHouse DDL/DML; named apart from {@link #execute} so the DSL overload is unambiguous. */
    private void insertSql(String sql) {
        execute(sql);
    }

    /**
     * Seeds one {@code dial_usage_log} row together with its payload row, under a shared
     * {@code event_id} derived from the trace id.
     *
     * <p>The payload columns are physically on {@code dial_usage_log_payload} since the split, so a
     * seed that writes them has to write two tables — and the {@code event_id} has to be set
     * explicitly and distinctly, because it is the key the catalog's grain join matches on. Left to
     * the column's zero value every row would share the all-zero UUID and the join would pair every
     * payload row with every usage-log row.
     *
     * <p>Both rows carry the <em>same</em> {@code request_time}, which is what real ingest writes — both
     * mappings read one record's {@code request.time}. They have to agree: the two tables are
     * partition-aligned in the catalog, so a keyed grain join bounds the payload side by the parent's
     * value and a payload row stamped with its own clock is excluded by that bound, reading back as NULL.
     * That bound is what
     * {@link #payloadRowDisagreeingWithItsParentsPartitionIsExcludedByTheBound()} asserts directly.
     *
     * <p>The date is {@link #FIXTURE_DATE} — today's — rather than a historical one, because the payload
     * table's 90-day TTL with {@code ttl_only_drop_parts=1} drops a past-dated part almost as soon as it
     * lands, and the resulting missing body looks exactly like a broken join.
     *
     * @param timeOfDay      the row's {@code request_time} within {@link #FIXTURE_DATE}, e.g.
     *                       {@code "00:00:01.000"}; ordering between fixtures is expressed here
     * @param payloadColumns physical payload column names, comma-separated (e.g. {@code "jwt_claims"})
     * @param payloadValues  their SQL literals, in the same order and already quoted
     */
    private void insertWithPayload(String timeOfDay, String eventKind, String projectId, String traceId,
            String payloadColumns, String payloadValues) {
        // Callers name a time of day only, never a date: the date has to be today's (see FIXTURE_DATE),
        // because the payload table's 90-day TTL drops a past-dated part almost as soon as it lands. A
        // caller that spelled out a date would have it silently replaced, so it is not asked for one.
        final String at = fixtureTime(timeOfDay);
        final String eventId = "'" + UUID.nameUUIDFromBytes(traceId.getBytes(StandardCharsets.UTF_8)) + "'";
        // Always two inserts: every verbatim-content column — the bodies and the claim/header
        // documents alike — lives on dial_usage_log_payload since the split, so none of them can be
        // set on the parent row.
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (" + eventId + ", '" + at + "', '" + eventKind + "', '" + projectId
                + "', '" + traceId + "')");
        // The SAME request_time as the parent row, which is what ingest writes: both mappings read
        // request.time from one record. The columns are partition-aligned in the catalog, so a keyed grain
        // join bounds the payload side by the parent's value — a fixture that stamped its own clock here
        // would be excluded by that bound and read back as NULL.
        insertSql("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, "
                + payloadColumns + ") VALUES (" + eventId + ", '" + at + "', '" + traceId + "', '', "
                + payloadValues + ")");
    }

    /**
     * Seeds one row carrying the same document in an {@code object} column and in a text column — the
     * shape the object/text extraction-parity cases need. Since the split both columns live on
     * {@code dial_usage_log_payload}, so this is one payload insert beside the parent row, joined on
     * {@code event_id}.
     */
    private void insertClaimsAndBody(String traceId, String document) {
        final String eventId = "'" + UUID.nameUUIDFromBytes(traceId.getBytes(StandardCharsets.UTF_8)) + "'";
        final String at = fixtureTime("00:00:00.000");
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (" + eventId + ", '" + at + "', 'chat', 'p1', '" + traceId + "')");
        insertSql("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " jwt_claims, request_body) VALUES (" + eventId + ", '" + at + "', '"
                + traceId + "', '', '" + document + "', '" + document + "')");
    }

    private void execute(String body, ResultMatcher... matchers) throws Exception {
        var actions = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(body));
        for (final ResultMatcher matcher : matchers) {
            actions.andExpect(matcher);
        }
    }

    @Test
    void rowModeEqAndComparisonFilterReturnsMatchingRows() throws Exception {
        // Only the chat rows with total_tokens >= 200 match; the chat 100 and embedding rows must be excluded.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p1", 300)
                .row("chat", "p2", 200)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "and", "args": [
                      { "op": "eq", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "chat" } ] },
                      { "op": "ge", "args": [ { "type": "field", "name": "total_tokens" }, { "type": "value", "value_type": "long", "value": "200" } ] }
                  ]},
                  "select": [
                    { "expr": { "type": "field", "name": "project_id" } },
                    { "expr": { "type": "field", "name": "total_tokens" } }
                  ],
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].project_id").value("p2"),
                jsonPath("$.rows[0].total_tokens").value(200),
                jsonPath("$.rows[1].project_id").value("p1"),
                jsonPath("$.rows[1].total_tokens").value(300));
    }

    @Test
    void rowModeInPredicateReturnsMatchingRows() throws Exception {
        // event_kind IN ('embedding', 'completion') -> only the embedding and completion rows; chat is excluded.
        fixture()
                .row("chat", "p1", 100)
                .row("embedding", "p1", 50)
                .row("completion", "p2", 500)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "in", "args": [
                      { "type": "field", "name": "event_kind" },
                      { "type": "array", "items": [
                          { "type": "value", "value_type": "string", "value": "embedding" },
                          { "type": "value", "value_type": "string", "value": "completion" }
                      ]}
                  ]},
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "field", "name": "total_tokens" } }
                  ],
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].event_kind").value("embedding"),
                jsonPath("$.rows[0].total_tokens").value(50),
                jsonPath("$.rows[1].event_kind").value("completion"),
                jsonPath("$.rows[1].total_tokens").value(500));
    }

    @Test
    void omittedProjectionSelectsAllBoundColumns() throws Exception {
        // No `select` -> all bound columns projected explicitly. Filter to p2 rows, ascending: 200 (chat) then 500.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 200)
                .row("completion", "p2", 500)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [ { "type": "field", "name": "project_id" }, { "type": "value", "value_type": "string", "value": "p2" } ] },
                  "sort": [ { "field": "total_tokens", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].total_tokens").value(200),
                // every bound column is present, including the leading and trailing DDL columns.
                jsonPath("$.rows[0].request_time").exists(),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].execution_path").exists(),
                jsonPath("$.rows[0]._source_file").exists(),
                jsonPath("$.rows[0]._ingested_at").exists());
    }

    @Test
    void objectColumnSerializesAsJsonWithArraysUnwrappedAtEveryDepth() throws Exception {
        // Two things are pinned here. (1) The ClickHouse client wraps every Array(...) value —
        // including arrays nested inside a JSON cell — in its ArrayValue type, whose getters Jackson
        // would serialize as {"array":[...],"arrayOfObjects":[...]}; the executor's unwrap is
        // recursive, so both the top-level 'roles' array and the one under the nested 'ctx' object
        // come out as real JSON arrays. (2) ClickHouse's JSON type flattens nested objects into
        // dotted path keys, so 'ctx':{'tags':[...]} surfaces as the key "ctx.tags" — a storage-format
        // property of the JSON column, not something this service reshapes.
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-nested",
                "jwt_claims",
                "'{\"sub\":\"user-1\",\"roles\":[\"admin\",\"dev\"],\"ctx\":{\"tags\":[\"a\"]}}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "dial_usage_log_payload.jwt_claims" } } ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].sub").value("user-1"),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].roles", hasSize(2)),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].roles[0]").value("admin"),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].roles[1]").value("dev"),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims']['ctx.tags']", hasSize(1)),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims']['ctx.tags'][0]").value("a"));
    }

    /**
     * A source-only filter beside a payload column is the keyed-join shape: the payload side renders as a
     * derived table restricted to {@code event_id IN (SELECT event_id FROM dial_usage_log WHERE ...)}, aliased
     * back to {@code dial_usage_log_payload}. This runs that SQL against real ClickHouse — the render test
     * pins the text, this pins that ClickHouse accepts the alias shadowing the table name inside the derived
     * table, resolves the outer {@code "dial_usage_log_payload"."request_body"} reference against it, and
     * returns exactly the filtered row with its own body while the unfiltered row and its body stay out.
     */
    @Test
    void sourceOnlyFilterWithPayloadColumnReturnsTheMatchedRowsBody() throws Exception {
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-keyed-a", "request_body", "'body-a'");
        insertWithPayload("00:00:01.000", "chat", "p2", "trace-keyed-b", "request_body", "'body-b'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "trace_id" } },
                    { "expr": { "type": "field", "name": "dial_usage_log_payload.request_body" } }
                  ],
                  "filter": { "op": "eq", "args": [ { "type": "field", "name": "trace_id" }, { "type": "value", "value_type": "string", "value": "trace-keyed-b" } ] },
                  "page": { "type": "offset", "limit": 10, "offset": 0, "include_total": true }
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].trace_id").value("trace-keyed-b"),
                jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("body-b"),
                // The count statement is keyed by the same filter and must agree with the rows.
                jsonPath("$.totalCount").value(1));
    }

    /**
     * The partition alignment is live on the real catalog, not only in the render tests' hand-built model:
     * a payload row whose {@code request_time} disagrees with its parent's is excluded by the bound the
     * keyed join applies, so the column reads back as NULL.
     *
     * <p>This asserts the mechanism has teeth, and it is also the failure mode to recognise. The bound
     * comes from {@code V40} declaring {@code dial_usage_log_payload} partition-aligned with
     * {@code dial_usage_log}, which claims the two tables hold the same {@code request_time} for the same
     * {@code event_id} — true of every row real ingest writes, since both mappings read one record's
     * {@code request.time}. Nothing checks the claim at query time, and this is what a violation looks
     * like: not an error, just a body that has quietly gone missing.
     */
    @Test
    void payloadRowDisagreeingWithItsParentsPartitionIsExcludedByTheBound() throws Exception {
        final String traceId = "trace-misaligned";
        final String eventId = "'" + UUID.nameUUIDFromBytes(traceId.getBytes(StandardCharsets.UTF_8)) + "'";
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (" + eventId + ", '" + fixtureTime("00:00:00.000") + "', 'chat', 'p1', '"
                + traceId + "')");
        // One day later on the payload side — a different partition, and so outside the value set the
        // parent's predicate selects.
        insertSql("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " request_body) VALUES (" + eventId + ", '" + fixtureTime("00:00:00.000")
                + "' + INTERVAL 1 DAY, '" + traceId + "', '', 'orphaned-body')");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "trace_id" } },
                    { "expr": { "type": "field", "name": "dial_usage_log_payload.request_body" } }
                  ],
                  "filter": { "op": "eq", "args": [ { "type": "field", "name": "trace_id" }, { "type": "value", "value_type": "string", "value": "trace-misaligned" } ] },
                  "page": { "type": "offset", "limit": 10, "offset": 0 }
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].trace_id").value("trace-misaligned"),
                jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value(nullValue()));
    }

    /**
     * Seeds the mixed-filter fixture: three rows inside the window opening at {@code 00:00:05} — one with a
     * body, one with no payload row at all, one whose payload row holds no body — and two before it, one
     * with no payload row and one with a body-less payload row, so an out-of-window row would satisfy the
     * enrichment conjunct if the window were not applied.
     */
    private void insertMixedFilterFixture() {
        insertWithPayload("00:00:10.000", "chat", "p1", "trace-mix-a", "request_body", "'body-a'");
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id) VALUES ('"
                + eventIdFor("trace-mix-b") + "', '" + fixtureTime("00:00:11.000") + "', 'chat', 'p1', 'trace-mix-b')");
        insertWithPayload("00:00:12.000", "chat", "p1", "trace-mix-c", "response_body", "'resp-c'");
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id) VALUES ('"
                + eventIdFor("trace-mix-d") + "', '" + fixtureTime("00:00:01.000") + "', 'chat', 'p1', 'trace-mix-d')");
        insertWithPayload("00:00:02.000", "chat", "p1", "trace-mix-e", "response_body", "'resp-e'");
    }

    /** {@code fixtureTime(timeOfDay)} as the epoch-millisecond string a DSL {@code timestamp} literal carries. */
    private static String epochMillis(String timeOfDay) {
        return String.valueOf(LocalDateTime.parse(fixtureTime(timeOfDay).replace(' ', 'T'))
                .toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    /**
     * The same statement with the payload joined <em>unkeyed</em> — a bare {@code LEFT JOIN ... FINAL} under the
     * read path's {@code join_use_nulls = 1} — returning the matched trace ids comma-joined in order. The
     * reference result the keyed statement ADAS renders must reproduce.
     */
    private String unkeyedTraceIds(String payloadPredicate) {
        return queryScalar("SELECT arrayStringConcat(groupArray(trace_id), ',') AS ids FROM (SELECT l.trace_id AS trace_id"
                + " FROM dial_usage_log AS l LEFT JOIN dial_usage_log_payload AS p FINAL ON l.event_id = p.event_id"
                + " WHERE l.request_time >= '" + fixtureTime("00:00:05.000") + "' AND " + payloadPredicate
                + " ORDER BY l.trace_id) SETTINGS join_use_nulls = 1", "ids");
    }

    /**
     * How many statements ClickHouse finished since {@code since} (a {@code now64(6)} reading) whose text joins
     * the payload bounded by the window — a derived table restricted by the window transferred onto the payload's
     * own {@code request_time}, the form a window-only filter takes on an aligned join, with no key set — and also
     * contains {@code marker}. Proves the statement that produced a result was the bounded one, not merely that
     * some statement produced it.
     */
    private long boundedPayloadStatementsSince(String since, String marker) {
        execute("SYSTEM FLUSH LOGS");
        return Long.parseLong(queryScalar("SELECT count() AS n FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND event_time_microseconds >= toDateTime64('" + since + "', 6)"
                + " AND positionCaseInsensitive(query, '\"dial_usage_log_payload\".\"request_time\" >=') > 0"
                + " AND positionCaseInsensitive(query, '\"dial_usage_log_payload\".\"event_id\" in (select') = 0"
                + " AND position(query, '" + marker.replace("'", "\\'") + "') > 0", "n"));
    }

    /**
     * RC1 of the 2026-10-14 cost sweep, at runtime: a time window beside an enrichment {@code IS NULL}
     * conjunct. The window keys the payload join now, and that changes no row: an in-window source row with no
     * payload row and one whose payload holds no body both still come back, the in-window row with a body does
     * not, and neither out-of-window row does — exactly the rows the unkeyed statement returns. {@code IS NULL}
     * is the shape where moving the enrichment predicate would be wrong; keying moves nothing, so it is right.
     */
    @Test
    void enrichmentIsNullConjunctBesideTheWindowIsKeyedAndReturnsTheUnkeyedRows() throws Exception {
        insertMixedFilterFixture();
        final String since = queryScalar("SELECT toString(now64(6)) AS t", "t");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "trace_id" } } ],
                  "filter": { "op": "and", "args": [
                      { "op": "ge", "args": [ { "type": "field", "name": "request_time" }, { "type": "value", "value_type": "timestamp", "value": "%s" } ] },
                      { "op": "eq", "args": [ { "type": "field", "name": "dial_usage_log_payload.request_body" }, { "type": "value", "value_type": "null", "value": null } ] }
                  ]},
                  "sort": [ { "field": "trace_id", "dir": "asc" } ],
                  "page": { "type": "offset", "limit": 10, "offset": 0, "include_total": true }
                }
                """.formatted(epochMillis("00:00:05.000")),
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].trace_id").value("trace-mix-b"),
                jsonPath("$.rows[1].trace_id").value("trace-mix-c"),
                jsonPath("$.totalCount").value(2));

        assertThat(unkeyedTraceIds("p.request_body IS NULL")).isEqualTo("trace-mix-b,trace-mix-c");
        // Both the row statement and the include_total count ran bounded by the transferred window.
        assertThat(boundedPayloadStatementsSince(since, "\"dial_usage_log_payload\".\"request_body\" is null")).isEqualTo(2);
    }

    /** The null-rejecting twin: the body conjunct beside the window, the body projected through the window-bounded join. */
    @Test
    void enrichmentEqualityConjunctBesideTheWindowIsKeyedAndReturnsTheUnkeyedRows() throws Exception {
        insertMixedFilterFixture();
        final String since = queryScalar("SELECT toString(now64(6)) AS t", "t");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "field", "name": "trace_id" } },
                    { "expr": { "type": "field", "name": "dial_usage_log_payload.request_body" } }
                  ],
                  "filter": { "op": "and", "args": [
                      { "op": "ge", "args": [ { "type": "field", "name": "request_time" }, { "type": "value", "value_type": "timestamp", "value": "%s" } ] },
                      { "op": "eq", "args": [ { "type": "field", "name": "dial_usage_log_payload.request_body" }, { "type": "value", "value_type": "string", "value": "body-a" } ] }
                  ]},
                  "page": { "type": "offset", "limit": 10, "offset": 0, "include_total": true }
                }
                """.formatted(epochMillis("00:00:05.000")),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].trace_id").value("trace-mix-a"),
                jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").value("body-a"),
                jsonPath("$.totalCount").value(1));

        assertThat(unkeyedTraceIds("p.request_body = 'body-a'")).isEqualTo("trace-mix-a");
        assertThat(boundedPayloadStatementsSince(since, "\"dial_usage_log_payload\".\"request_body\" = 'body-a'")).isEqualTo(2);
    }

    /** The unkeyed shape still answers: a filter on the payload column itself joins the payload table whole. */
    @Test
    void filterOnPayloadColumnJoinsUnkeyedAndStillMatches() throws Exception {
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-unkeyed-a", "request_body", "'needle here'");
        insertWithPayload("00:00:01.000", "chat", "p2", "trace-unkeyed-b", "request_body", "'hay'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "trace_id" } } ],
                  "filter": { "op": "co", "args": [
                      { "type": "field", "name": "dial_usage_log_payload.request_body" },
                      { "type": "value", "value_type": "string", "value": "needle" } ] }
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].trace_id").value("trace-unkeyed-a"));
    }

    @Test
    void objectColumnPreservesNullArrayElementsAndDropsNullMembers() throws Exception {
        // Nulls inside a JSON cell are an ordinary shape (an absent claim, a sparse array slot), and the
        // executor's recursive unwrap visits every nested value — a null array element passes straight
        // through as a JSON null rather than blowing up the request. A null *member* is a different
        // story and belongs to the storage format, not to this service: ClickHouse's JSON type does not
        // materialize a path whose value is null (JSONAllPathsWithTypes never lists it), so the key is
        // simply absent from the row object. Pinned here so the two cases cannot be confused.
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-nulls",
                "jwt_claims",
                "'{\"sub\":null,\"roles\":[\"a\",null]}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [ { "expr": { "type": "field", "name": "dial_usage_log_payload.jwt_claims" } } ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].roles", hasSize(2)),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].roles[0]").value("a"),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].roles[1]").value(nullValue()),
                jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].sub").doesNotExist());
    }

    // ------------------------------------------- json_extract over an object column (the overload)

    /**
     * The whole point of the overload: the four functions must behave the same over an {@code object}
     * column as over a {@code string} column holding the same document. Seeds one document into both
     * {@code dial_usage_log_payload.jwt_claims} (JSON) and {@code request_body} (String) and asserts value-for-value equality
     * across the four cases where the two renderings could diverge — a present value, an absent path,
     * a member stored as an explicit null, and a requested type the stored member does not have.
     *
     * <p>This is the test that fails if the renderings ever drift apart. Its cross-type case is why
     * the object side uses the untyped subcolumn plus {@code toString} rather than typed access
     * ({@code .:String}), which returns NULL where the text accessors coerce.
     */
    @Test
    void jsonExtractAgreesBetweenObjectAndTextColumns() throws Exception {
        final String document = "{\"s\":\"txt\",\"n\":42,\"f\":1.5,\"b\":true,\"nul\":null,\"strue\":\"true\","
                + "\"sfrac\":\"1.5\"}";
        insertClaimsAndBody("trace-parity", document);

        // Each pair extracts the same path from the object column and from the text column.
        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "s" } ] }, "as": "o_s" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "s" } ] }, "as": "t_s" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "o_n" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "t_n" },
                    { "expr": { "type": "fn", "name": "json_extract_double", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "f" } ] }, "as": "o_f" },
                    { "expr": { "type": "fn", "name": "json_extract_double", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "f" } ] }, "as": "t_f" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "b" } ] }, "as": "o_b" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "b" } ] }, "as": "t_b" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "o_b_num" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "t_b_num" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "strue" } ] }, "as": "o_b_str" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "strue" } ] }, "as": "t_b_str" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "f" } ] }, "as": "o_b_frac" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "f" } ] }, "as": "t_b_frac" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "f" } ] }, "as": "o_n_frac" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "f" } ] }, "as": "t_n_frac" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "sfrac" } ] }, "as": "o_n_sfrac" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "sfrac" } ] }, "as": "t_n_sfrac" },
                    { "expr": { "type": "fn", "name": "json_extract_double", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "o_f_int" },
                    { "expr": { "type": "fn", "name": "json_extract_double", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "t_f_int" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].o_s").value("txt"),
                jsonPath("$.rows[0].t_s").value("txt"),
                jsonPath("$.rows[0].o_n").value(42),
                jsonPath("$.rows[0].t_n").value(42),
                jsonPath("$.rows[0].o_f").value(1.5),
                jsonPath("$.rows[0].t_f").value(1.5),
                jsonPath("$.rows[0].o_b").value(true),
                jsonPath("$.rows[0].t_b").value(true),
                // json_extract_bool over members whose stored type is not boolean — the cases a
                // text comparison of the member would get backwards in both directions.
                // A non-zero integer is true to JSONExtractBool...
                jsonPath("$.rows[0].o_b_num").value(true),
                jsonPath("$.rows[0].t_b_num").value(true),
                // ...the string "true" is not (it parses no string)...
                jsonPath("$.rows[0].o_b_str").value(false),
                jsonPath("$.rows[0].t_b_str").value(false),
                // ...and neither is a fractional number, non-zero though it is.
                jsonPath("$.rows[0].o_b_frac").value(false),
                jsonPath("$.rows[0].t_b_frac").value(false),
                // json_extract_long over members that are not integers: JSONExtractInt converts a
                // number by truncating toward zero, and parses a numeric string the same way, so a
                // plain "is this integral text" read of the subcolumn would answer 0 to both.
                jsonPath("$.rows[0].o_n_frac").value(1),
                jsonPath("$.rows[0].t_n_frac").value(1),
                jsonPath("$.rows[0].o_n_sfrac").value(1),
                jsonPath("$.rows[0].t_n_sfrac").value(1),
                // ...and json_extract_double over an integer member, the mirror of the above.
                jsonPath("$.rows[0].o_f_int").value(42.0),
                jsonPath("$.rows[0].t_f_int").value(42.0));
    }

    /**
     * {@code json_extract_array} over an {@code object} column, pinned value-for-value against a real
     * ClickHouse. The rendering addresses the subcolumn and re-serializes <em>it</em>; the spelling that
     * stringifies the subcolumn instead ({@code JSONExtract(toString(<sub>), …)}) renders ClickHouse array
     * syntax into a JSON parser and yields the empty array for every array carrying a quoted string, a
     * null or a composite element, while still agreeing on arrays of bare integers, floats or booleans.
     * So the fixture carries both, and the string case is the one that catches the regression — an
     * integer-only fixture would pass either way, and so would a boolean-only or float-only one.
     *
     * <p><b>No text-column half, unlike {@link #jsonExtractAgreesBetweenObjectAndTextColumns()}.</b> The
     * payload's text columns are all {@code Nullable(String)}, which {@code JSONExtract(…, 'Array(String)')}
     * refuses outright ({@code Code 43}); the text form now runs only through the
     * {@code assumeNotNull(ifNull(…, ''))} guard ({@code ExprTranslator.nonNullJsonText}), and that half is
     * exercised on its own by {@code PayloadJsonTextExtractionIntegrationTest}. The equivalence of this
     * subcolumn rendering with the whole-document form was verified against a populated table and is
     * recorded on {@code ExprTranslator.subcolumnArray}.
     */
    @Test
    void jsonExtractArrayOverAnObjectColumnReadsTheAddressedSubcolumn() throws Exception {
        final String document = "{\"roles\":[\"a\",\"b\"],\"nums\":[1,2],\"mix\":[1,\"q\"],"
                + "\"s\":\"abc\",\"d\":{\"k\":1},\"realm\":{\"roles\":[\"x\"]}}";
        insertClaimsAndBody("trace-array-parity", document);

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_array", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "roles" } ] }, "as": "roles" },
                    { "expr": { "type": "fn", "name": "json_extract_array", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "nums" } ] }, "as": "nums" },
                    { "expr": { "type": "fn", "name": "json_extract_array", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "mix" } ] }, "as": "mix" },
                    { "expr": { "type": "fn", "name": "json_extract_array", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "s" } ] }, "as": "scalar" },
                    { "expr": { "type": "fn", "name": "json_extract_array", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "d" } ] }, "as": "doc" },
                    { "expr": { "type": "fn", "name": "json_extract_array", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "absent" } ] }, "as": "absent" },
                    { "expr": { "type": "fn", "name": "json_extract_array", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "realm" },
                        { "type": "value", "value_type": "string", "value": "roles" } ] }, "as": "deep" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                // An array of strings: the case the stringifying spelling empties.
                jsonPath("$.rows[0].roles", hasSize(2)),
                jsonPath("$.rows[0].roles[0]").value("a"),
                jsonPath("$.rows[0].roles[1]").value("b"),
                // An all-integer array, where ClickHouse's array syntax and JSON's coincide — which is
                // exactly why this case alone would prove nothing. Booleans and floats coincide too, so
                // a fixture built from either would be the same false safeguard.
                jsonPath("$.rows[0].nums", hasSize(2)),
                jsonPath("$.rows[0].nums[0]").value("1"),
                jsonPath("$.rows[0].nums[1]").value("2"),
                // Mixed element types are rendered to text element for element, as the text form does.
                jsonPath("$.rows[0].mix", hasSize(2)),
                jsonPath("$.rows[0].mix[0]").value("1"),
                jsonPath("$.rows[0].mix[1]").value("q"),
                // A member that is not an array, a member that is a document, and a path that is not
                // there: an empty array in every case, never null and never an error. The absent path is
                // the one the ifNull wrapper exists for — its subcolumn is a null Dynamic.
                jsonPath("$.rows[0].scalar", hasSize(0)),
                jsonPath("$.rows[0].doc", hasSize(0)),
                jsonPath("$.rows[0].absent", hasSize(0)),
                // Variadic, unlike json_extract_string_ci: a deeper path addresses a deeper subcolumn.
                jsonPath("$.rows[0].deep", hasSize(1)),
                jsonPath("$.rows[0].deep[0]").value("x"));
    }

    /**
     * A path key carrying a backslash is refused before it can be rendered. jOOQ doubles a quote inside
     * an identifier but leaves a backslash alone, and ClickHouse's identifier lexer honours it as an
     * escape — so this key, rendered, would have closed the subcolumn identifier and run the appended
     * {@code UNION ALL} as a second query. The 400 is the whole assertion: no row of the injected shape
     * can come back.
     */
    @Test
    void jsonExtractPathKeyWithBackslashIsRejected() throws Exception {
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-inject",
                "jwt_claims",
                "'{\"sub\":\"user-1\"}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string",
                          "value": "x\\\\\\") FROM dial_usage_log UNION ALL SELECT 'PWN' AS v --" } ] }, "as": "v" }
                  ]
                }
                """,
                status().isBadRequest(),
                jsonPath("$.error").value("bad_request"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("backslash")));
    }

    /**
     * An empty path key is refused for the same reason and on both storages. jOOQ drops an empty
     * identifier segment rather than rendering it, so this call would have addressed the column itself:
     * {@code toString("dial_usage_log_payload.jwt_claims")} hands back the whole serialized document where the text form
     * answers with the empty string, and {@code json_extract_bool} over the same key fails outright
     * ({@code dynamicType} rejects a {@code JSON} operand) where the text form answers {@code false}.
     */
    @Test
    void jsonExtractEmptyPathKeyIsRejected() throws Exception {
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-empty-key",
                "jwt_claims",
                "'{\"sub\":\"user-1\"}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "" } ] }, "as": "v" }
                  ]
                }
                """,
                status().isBadRequest(),
                jsonPath("$.error").value("bad_request"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must not be empty")));
    }

    /**
     * The one documented exception to object/text interchangeability, pinned here so it stays a
     * decision rather than a silent gap: a <em>composite</em> member — a nested document or an array —
     * is not addressable as text. {@code JSONExtractString} falls back to the member's raw
     * serialization and hands back the source spelling; a native {@code JSON} column keeps typed
     * values instead, and the two shapes lose that spelling differently. A sub-document is
     * materialized under deeper sub-paths, leaving the addressed path's own value empty, so the object
     * form yields the type default. An array is materialized as an array value, which {@code toString}
     * renders in ClickHouse's array syntax rather than JSON's — hence {@code ['p','q']} against
     * {@code ["p","q"]}. The two spellings coincide only for an all-integer, null-free array such as
     * {@code [1,2]}, so this test deliberately uses a <em>string</em> array: pinning {@code [1,2]}
     * would pin the one coincidence and let the real contract drift.
     *
     * <p>Serializing a composite member back would not close the gap either — ClickHouse rebuilds it
     * from its typed paths, so member order and value spelling differ from the source text. What the
     * exception costs stays narrow, and the bound is asserted: the sub-document's own members remain
     * addressable by naming them in the path, which is what the path arguments are for.
     */
    @Test
    void jsonExtractStringOverObjectColumnDoesNotSerializeCompositeMembers() throws Exception {
        final String document = "{\"obj\":{\"a\":1},\"arr\":[\"p\",\"q\"]}";
        insertClaimsAndBody("trace-subdoc", document);

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "obj" } ] }, "as": "o_obj" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "obj" } ] }, "as": "t_obj" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "arr" } ] }, "as": "o_arr" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "arr" } ] }, "as": "t_arr" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "obj" },
                        { "type": "value", "value_type": "string", "value": "a" } ] }, "as": "o_member" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "obj" },
                        { "type": "value", "value_type": "string", "value": "a" } ] }, "as": "t_member" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                // The exception, shape 1: the object form yields the type default where the text form serializes.
                jsonPath("$.rows[0].o_obj").value(""),
                jsonPath("$.rows[0].t_obj").value("{\"a\":1}"),
                // The exception, shape 2: an array renders in ClickHouse's array syntax, not JSON's.
                jsonPath("$.rows[0].o_arr").value("['p','q']"),
                jsonPath("$.rows[0].t_arr").value("[\"p\",\"q\"]"),
                // And the sub-document's members stay addressable through the path, on both sides.
                jsonPath("$.rows[0].o_member").value(1),
                jsonPath("$.rows[0].t_member").value(1));
    }

    @Test
    void jsonExtractOverObjectColumnIsTotalAndCoercesLikeTheTextForm() throws Exception {
        // The three divergence risks, all asserted to match the text form's documented defaults:
        // an absent path, a member stored as explicit null (which the JSON type drops entirely), and
        // a requested type the stored member does not have (a number read as a string).
        final String document = "{\"s\":\"txt\",\"n\":42,\"nul\":null}";
        insertClaimsAndBody("trace-total", document);

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "absent" } ] }, "as": "o_absent_s" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "absent" } ] }, "as": "t_absent_s" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "absent" } ] }, "as": "o_absent_n" },
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "absent" } ] }, "as": "t_absent_n" },
                    { "expr": { "type": "fn", "name": "json_extract_bool", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "absent" } ] }, "as": "o_absent_b" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "nul" } ] }, "as": "o_null" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "nul" } ] }, "as": "t_null" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "o_cross" },
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "n" } ] }, "as": "t_cross" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                // Absent path -> the extracting type's default on both sides, never null.
                jsonPath("$.rows[0].o_absent_s").value(""),
                jsonPath("$.rows[0].t_absent_s").value(""),
                jsonPath("$.rows[0].o_absent_n").value(0),
                jsonPath("$.rows[0].t_absent_n").value(0),
                jsonPath("$.rows[0].o_absent_b").value(false),
                // Explicit null member -> same as absent (the JSON type never materializes the path).
                jsonPath("$.rows[0].o_null").value(""),
                jsonPath("$.rows[0].t_null").value(""),
                // Cross-type -> coerced to text on both sides, not null.
                jsonPath("$.rows[0].o_cross").value("42"),
                jsonPath("$.rows[0].t_cross").value("42"));
    }

    @Test
    void jsonExtractOverObjectColumnReadsNestedPaths() throws Exception {
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-nested-extract",
                "jwt_claims",
                "'{\"usage\":{\"total_tokens\":42}}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_long", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "usage" },
                        { "type": "value", "value_type": "string", "value": "total_tokens" } ] }, "as": "tokens" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].tokens").value(42));
    }

    @Test
    void filterOnPathInsideObjectColumnNarrowsRows() throws Exception {
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-a",
                "jwt_claims",
                "'{\"sub\":\"user-1\"}'");
        insertWithPayload("00:00:00.000", "chat", "p2", "trace-b",
                "jwt_claims",
                "'{\"sub\":\"user-2\"}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [
                      { "type": "fn", "name": "json_extract_string", "args": [
                          { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                          { "type": "value", "value_type": "string", "value": "sub" } ] },
                      { "type": "value", "value_type": "string", "value": "user-1" } ] },
                  "select": [ { "expr": { "type": "field", "name": "project_id" } } ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].project_id").value("p1"));
    }

    @Test
    void aggregateGroupsByPathInsideObjectColumn() throws Exception {
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-g1",
                "jwt_claims",
                "'{\"tier\":\"gold\"}'");
        insertWithPayload("00:00:00.000", "chat", "p2", "trace-g2",
                "jwt_claims",
                "'{\"tier\":\"gold\"}'");
        insertWithPayload("00:00:00.000", "chat", "p3", "trace-g3",
                "jwt_claims",
                "'{\"tier\":\"silver\"}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.jwt_claims" },
                        { "type": "value", "value_type": "string", "value": "tier" } ] }, "as": "tier" },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "n" }
                  ],
                  "group_by": [ "tier" ],
                  "sort": [ { "field": "tier", "dir": "asc" } ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].tier").value("gold"),
                jsonPath("$.rows[0].n").value(2),
                jsonPath("$.rows[1].tier").value("silver"),
                jsonPath("$.rows[1].n").value(1));
    }

    @Test
    void jsonExtractOverHeavyColumnIsAllowed() throws Exception {
        // request_body is heavy: excluded from wildcard expansion, but nameable — and an extraction
        // returns a scalar rather than the payload, so heaviness does not block it.
        //
        // This pins the heavy *string* column; the catalog has no column that is both heavy and
        // object, so the heavy+object combination is not exercised here. It holds by construction:
        // heaviness is consulted only by WildcardProjection when expanding an absent select, and
        // neither the rendering choice nor the projection-only carve-out reads the flag.
        insertWithPayload("00:00:00.000", "chat", "p1", "trace-heavy",
                "request_body",
                "'{\"model\":\"gpt-4o\"}'");

        execute(
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "expr": { "type": "fn", "name": "json_extract_string", "args": [
                        { "type": "field", "name": "dial_usage_log_payload.request_body" },
                        { "type": "value", "value_type": "string", "value": "model" } ] }, "as": "model" }
                  ]
                }
                """,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].model").value("gpt-4o"));
    }

    @Test
    void aggregateModeGroupByCountAndSum() throws Exception {
        // GROUP BY event_kind: chat -> (3, 600), completion -> (1, 500), embedding -> (1, 50). Sorted by name.
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 200)
                .row("chat", "p1", 300)
                .row("completion", "p2", 500)
                .row("embedding", "p1", 50)
                .insert();

        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "request_count" },
                    { "expr": { "type": "fn", "name": "sum", "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "token_sum" }
                  ],
                  "sort": [ { "field": "event_kind", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].event_kind").value("chat"),
                jsonPath("$.rows[0].request_count").value(3),
                jsonPath("$.rows[0].token_sum").value(600),
                jsonPath("$.rows[1].event_kind").value("completion"),
                jsonPath("$.rows[1].request_count").value(1),
                jsonPath("$.rows[1].token_sum").value(500),
                jsonPath("$.rows[2].event_kind").value("embedding"),
                jsonPath("$.rows[2].request_count").value(1),
                jsonPath("$.rows[2].token_sum").value(50));
    }

    @Test
    void unknownFieldIsRejectedWithBadRequest() throws Exception {
        // Field validation happens in the builder before any SQL is rendered -> QueryValidationException -> 400.
        // No data needed: the empty (truncated) table is fine since validation precedes execution.
        String body =
                """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [ { "type": "field", "name": "nope" }, { "type": "value", "value_type": "string", "value": "x" } ] }
                }
                """;

        execute(
                body,
                status().isBadRequest(),
                jsonPath("$.status").value(400),
                jsonPath("$.error").value("bad_request"),
                // message carries the validation detail; the unknown field name is surfaced to the caller.
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("nope")));
    }

    @Test
    void unknownEntityIsRejectedWithBadRequest() throws Exception {
        // No repository serves this entity -> StructuredQueryService throws QueryValidationException -> 400.
        String body = "{ \"entity\": \"not_a_table\", \"mode\": \"row\" }";

        execute(
                body,
                status().isBadRequest(),
                jsonPath("$.status").value(400),
                jsonPath("$.error").value("bad_request"));
    }

    // --- Document comparisons on object columns ------------------------------------------------------

    /** A row-mode document filtering `dial_usage_log_payload.request_tags` with {@code op} against {@code literal}. */
    private static String tagFilter(String op, String literal) {
        return """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "%s", "args": [
                      { "type": "field", "name": "dial_usage_log_payload.request_tags" },
                      { "type": "value", "value_type": "string", "value": "%s" }
                  ]},
                  "select": [ { "expr": { "type": "field", "name": "project_id" } } ],
                  "sort": [ { "field": "project_id", "dir": "asc" } ]
                }
                """.formatted(op, literal.replace("\"", "\\\""));
    }

    @Test
    void documentEqualityIgnoresKeyOrderWhitespaceAndNumberFormat() throws Exception {
        // The whole point of canonicalizing both sides: three rows that differ only in how their
        // documents were written must all match one literal written a fourth way. Before this change
        // native equality on a JSON column matched none of them, silently.
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"b\":2,\"a\":1}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"a\":1,\"b\":2}"))
                .add(r -> r.eventKind("chat").projectId("p3").requestTags("{ \"a\" : 1 , \"b\" : 2.0 }"))
                .add(r -> r.eventKind("chat").projectId("p9").requestTags("{\"a\":1}"))
                .insert();

        execute(
                tagFilter("eq", "{\"b\":2.0,\"a\":1}"),
                status().isOk(),
                jsonPath("$.rows", hasSize(3)),
                jsonPath("$.rows[0].project_id").value("p1"),
                jsonPath("$.rows[1].project_id").value("p2"),
                jsonPath("$.rows[2].project_id").value("p3"));
    }

    @Test
    void documentInequalityIsTheComplement() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"a\":1}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"b\":2}"))
                .insert();

        execute(
                tagFilter("ne", "{\"a\":1}"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].project_id").value("p2"));
    }

    @Test
    void caseInsensitiveContainsSearchesTheWholeDocument() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"model\":\"GPT-4o\"}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"model\":\"claude\"}"))
                .insert();

        execute(
                tagFilter("ico", "gpt-4"),
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].project_id").value("p1"));
    }

    @Test
    void caseSensitiveContainsDistinguishesCase() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"model\":\"GPT-4o\"}"))
                .insert();

        execute(tagFilter("co", "gpt-4"), status().isOk(), jsonPath("$.rows", hasSize(0)));
        execute(tagFilter("co", "GPT-4"), status().isOk(), jsonPath("$.rows", hasSize(1)));
    }

    @Test
    void documentMembershipMatchesAnyListedDocument() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"a\":1}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"b\":2}"))
                .add(r -> r.eventKind("chat").projectId("p3").requestTags("{\"c\":3}"))
                .insert();

        String body = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "in", "args": [
                      { "type": "field", "name": "dial_usage_log_payload.request_tags" },
                      { "type": "array", "items": [
                          { "type": "value", "value_type": "string", "value": "{\\"a\\":1}" },
                          { "type": "value", "value_type": "string", "value": "{\\"c\\":3}" }
                      ]}
                  ]},
                  "select": [ { "expr": { "type": "field", "name": "project_id" } } ],
                  "sort": [ { "field": "project_id", "dir": "asc" } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(2)),
                jsonPath("$.rows[0].project_id").value("p1"),
                jsonPath("$.rows[1].project_id").value("p3"));
    }

    @Test
    void nonJsonLiteralIsRejectedBeforeClickHouseIsContacted() throws Exception {
        // No fixture at all: if this reached ClickHouse it would raise Code 117 rather than validate.
        execute(
                tagFilter("eq", "not-json"),
                status().isBadRequest(),
                jsonPath("$.status").value(400),
                jsonPath("$.error").value("bad_request"),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("JSON document")),
                jsonPath("$.message").value(org.hamcrest.Matchers.containsString("dial_usage_log_payload.request_tags")));
    }

    @Test
    void orderingComparisonOnAnObjectColumnIsRejected() throws Exception {
        execute(
                tagFilter("gt", "{\"a\":1}"),
                status().isBadRequest(),
                jsonPath("$.error").value("bad_request"),
                jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("can only be projected")));
    }

    @Test
    void nullTestOnAnObjectColumnIsRejected() throws Exception {
        String body = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "dial_usage_log_payload.request_tags" },
                      { "type": "value", "value_type": "null", "value": null }
                  ]},
                  "select": [ { "expr": { "type": "field", "name": "project_id" } } ]
                }
                """;

        execute(
                body,
                status().isBadRequest(),
                jsonPath("$.error").value("bad_request"),
                jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("can only be projected")));
    }

    @Test
    void documentComparisonInHavingIsRefusedForTheUngroupedReference() throws Exception {
        // The operand check treats `having` exactly like `filter` — FilterTranslator serves both — but a
        // bare object field in `having` is neither grouped nor aggregated, and grouping BY one is itself
        // outside the carve-out, so no aggregate query exists in which this can succeed. Pinned so the
        // spec scenario states what actually happens rather than "accepted like the filter case".
        fixture().add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"model\":\"gpt-4\"}")).insert();

        String body = """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "n" }
                  ],
                  "group_by": [ "event_kind" ],
                  "having": { "op": "ico", "args": [
                      { "type": "field", "name": "dial_usage_log_payload.request_tags" },
                      { "type": "value", "value_type": "string", "value": "gpt-4" }
                  ]}
                }
                """;

        execute(body, status().isBadRequest(), jsonPath("$.error").value("bad_request"));
    }

    @Test
    void documentComparisonInHavingOverItsOwnGroupKeySucceeds() throws Exception {
        // The complement of the test above. Grouping BY the object field makes the `having` reference
        // legal, and `group_by` on an object field is a non-comparison use the execution path does not
        // restrict — so this returns rows here while the SQL surfaces refuse the same document. Pinned
        // so that divergence is deliberate rather than discovered.
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"model\":\"gpt-4\"}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"model\":\"claude\"}"))
                .insert();

        String body = """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "field", "name": "dial_usage_log_payload.request_tags" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "n" }
                  ],
                  "group_by": [ "dial_usage_log_payload.request_tags" ],
                  "having": { "op": "ico", "args": [
                      { "type": "field", "name": "dial_usage_log_payload.request_tags" },
                      { "type": "value", "value_type": "string", "value": "gpt-4" }
                  ]}
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].n").value(1));
    }

    @Test
    void comparisonOnAnArrayColumnIsRejected() throws Exception {
        String body = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "co", "args": [
                      { "type": "field", "name": "execution_path" },
                      { "type": "value", "value_type": "string", "value": "x" }
                  ]},
                  "select": [ { "expr": { "type": "field", "name": "project_id" } } ]
                }
                """;

        execute(
                body,
                status().isBadRequest(),
                jsonPath("$.error").value("bad_request"),
                jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("can only be projected")));
    }

    /**
     * The claim the whole relative-time change rests on, executed against ClickHouse rather than
     * asserted as rendered text: a window written as {@code now() - INTERVAL n MINUTE} selects the rows
     * inside it and excludes the rows outside it.
     *
     * <p>Seeded relative to the <b>database's</b> clock, which is the clock {@code now()} reads. Using a
     * JVM-computed instant instead would make the test a clock-skew detector between two machines rather
     * than a test of the filter.
     */
    @Test
    void relativeWindowSelectsOnlyTheRowsInsideIt() throws Exception {
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (generateUUIDv4(), now64(3, 'UTC') - INTERVAL 10 MINUTE, 'chat', 'inside', 't-inside')");
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (generateUUIDv4(), now64(3, 'UTC') - INTERVAL 10 DAY, 'chat', 'outside', 't-outside')");

        final String body = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "ge", "args": [
                      { "type": "field", "name": "request_time" },
                      { "type": "fn", "name": "date_sub", "args": [
                          { "type": "value", "value_type": "string", "value": "hour" },
                          { "type": "value", "value_type": "integer", "value": "2" },
                          { "type": "fn", "name": "now", "args": [] } ] }
                  ]},
                  "select": [ { "expr": { "type": "field", "name": "project_id" } } ]
                }
                """;

        execute(
                body,
                status().isOk(),
                jsonPath("$.rows", hasSize(1)),
                jsonPath("$.rows[0].project_id").value("inside"));
    }

    /**
     * Each {@code now()} call site is one value across every row of the execution — what ClickHouse
     * guarantees for {@code now64}, and so what this service promises. Separate call sites are evaluated
     * separately and may straddle a millisecond tick, exactly as the same statement does in ClickHouse, so
     * {@code a} and {@code b} are deliberately not asserted equal (that assertion failed ~2% of runs).
     */
    @Test
    void eachNowCallSiteIsOneValueAcrossRows() throws Exception {
        fixture().row("chat", "p1", 1).row("chat", "p2", 2).row("chat", "p3", 3)
                .row("chat", "p4", 4).row("chat", "p5", 5).insert();

        final String body = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "select": [
                    { "as": "a", "expr": { "type": "fn", "name": "now", "args": [] } },
                    { "as": "b", "expr": { "type": "fn", "name": "now", "args": [] } }
                  ],
                  "sort": [ { "field": "a", "dir": "asc" } ]
                }
                """;

        final String response = mockMvc.perform(post(EXECUTE)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(5)))
                .andReturn().getResponse().getContentAsString();

        // The contract: each call site carries one value in every row.
        final java.util.List<Object> first = com.jayway.jsonpath.JsonPath.read(response, "$.rows[*].a");
        final java.util.List<Object> second = com.jayway.jsonpath.JsonPath.read(response, "$.rows[*].b");
        org.assertj.core.api.Assertions.assertThat(first).hasSize(5).doesNotContainNull();
        org.assertj.core.api.Assertions.assertThat(second).hasSize(5).doesNotContainNull();
        org.assertj.core.api.Assertions.assertThat(new java.util.HashSet<>(first)).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(new java.util.HashSet<>(second)).hasSize(1);

        // A tolerance, not a contract: ClickHouse promises no bound on the gap between two call sites, and a
        // loaded CI host can stretch it past a few milliseconds. One second never flakes, yet still catches a
        // gross defect such as one call rendered in another zone (hours off) or read from another clock.
        final java.time.Instant a = java.time.Instant.parse(first.getFirst().toString());
        final java.time.Instant b = java.time.Instant.parse(second.getFirst().toString());
        org.assertj.core.api.Assertions.assertThat(java.time.Duration.between(a, b).abs())
                .isLessThanOrEqualTo(java.time.Duration.ofSeconds(1));
    }

    /**
     * The SQL surface reaches the same execution, so the motivating statement is run as written rather
     * than only translated — including the engine-native unquoted {@code INTERVAL} spelling, which
     * parses differently from the standard one.
     */
    @Test
    void relativeWindowExecutesOverTheSqlSurface() throws Exception {
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (generateUUIDv4(), now64(3, 'UTC') - INTERVAL 5 MINUTE, 'chat', 'recent', 's-inside')");
        insertSql("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (generateUUIDv4(), now64(3, 'UTC') - INTERVAL 30 DAY, 'chat', 'old', 's-outside')");

        mockMvc.perform(post("/v1/queries/execute-sql").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT project_id FROM dial_usage_log"
                                + " WHERE request_time >= now() - INTERVAL 2 HOUR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("recent"));
    }

}
