package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for {@code POST /v1/queries/execute-sql} against a real ClickHouse: SQL is
 * translated through the shared front end and executed through the same pipeline as
 * {@code /v1/queries/execute}, returning the same JSON envelope. The context runs with the default
 * {@code flight.enabled=false}, proving the SQL translator does not depend on the Flight gRPC
 * listener.
 */
class SqlQueryExecutionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String EXECUTE = "/v1/queries/execute";

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    /** Seeds one plain usage-log row whose event_id {@link #seedPayload} can key its payload row on. */
    private void seedWithTrace(String traceId, String projectId) {
        seedWithTrace(traceId, projectId, "{}");
    }

    /**
     * As above, with the row's claim document. That column moved to {@code dial_usage_log_payload}, so it
     * needs a second insert keyed on the same {@code event_id} — and at the same {@code request_time}, which
     * is why both rows take {@link #fixtureTime}. See {@link #seedPayload}.
     */
    private void seedWithTrace(String traceId, String projectId, String jwtClaims) {
        execute("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, trace_id)"
                + " VALUES (toUUID('" + eventIdFor(traceId) + "'), '" + fixtureTime("00:00:00.000") + "',"
                + " 'chat', '" + projectId + "', '" + traceId + "')");
        seedPayload(traceId, "jwt_claims", "'" + jwtClaims + "'");
    }

    private ResultActions executeSql(String sql) throws Exception {
        final String body = "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void sameRowsAsDslEndpoint() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 300)
                .row("embedding", "p1", 50)
                .insert();

        final String sqlResponse = executeSql(
                        "SELECT project_id, total_tokens FROM dial_usage_log"
                                + " WHERE event_kind = 'chat' ORDER BY total_tokens ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.totalCount").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        final String dslResponse = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"dial_usage_log","mode":"row",
                                 "filter":{"op":"eq","args":[
                                   {"type":"field","name":"event_kind"},
                                   {"type":"value","value_type":"string","value":"chat"}]},
                                 "select":[
                                   {"expr":{"type":"field","name":"project_id"}},
                                   {"expr":{"type":"field","name":"total_tokens"}}],
                                 "sort":[{"field":"total_tokens","dir":"asc"}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sqlResponse).isEqualTo(dslResponse);
    }

    /**
     * ClickHouse reads {@code \'} inside a string literal as an escaped quote, so a value whose backslash
     * reached the SQL unescaped would close its literal early and let the rest of the value run as SQL.
     */
    @Test
    void backslashQuoteInSqlStringLiteralCannotEscapeIt() throws Exception {
        fixture().row("chat", "p1", 100).row("chat", "p2", 200).insert();

        executeSql("SELECT project_id FROM dial_usage_log WHERE project_id = 'x\\'' OR 1=1 --'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)));
    }

    @Test
    void backslashQuoteInDslFilterValueCannotEscapeTheLiteral() throws Exception {
        fixture().row("chat", "p1", 100).row("chat", "p2", 200).insert();

        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"dial_usage_log","mode":"row",
                                 "filter":{"op":"eq","args":[
                                   {"type":"field","name":"project_id"},
                                   {"type":"value","value_type":"string","value":"x\\\\' OR 1=1 --"}]},
                                 "select":[{"expr":{"type":"field","name":"project_id"}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)));
    }

    /** A backslash in a value is matched literally — {@code a\nb} is four characters, not a newline. */
    @Test
    void backslashInStringLiteralMatchesLiterally() throws Exception {
        fixture().row("chat", "a\\nb", 100).row("chat", "p2", 200).insert();

        executeSql("SELECT project_id FROM dial_usage_log WHERE project_id = 'a\\nb'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("a\\nb"));
    }

    /** Rows whose project ids differ from their neighbour only where a LIKE metacharacter would match. */
    private void seedLikeMetacharacterRows() {
        fixture()
                .row("chat", "100%", 1)
                .row("chat", "1000", 2)
                .row("chat", "a_b", 3)
                .row("chat", "axb", 4)
                .row("chat", "a\\b", 5)
                .row("chat", "ab", 6)
                .insert();
    }

    /** Runs a DSL row query filtered by one contains-family comparison, sorted by project id. */
    private ResultActions executeContains(String op, String value) throws Exception {
        final String json = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entity\":\"dial_usage_log\",\"mode\":\"row\","
                        + "\"filter\":{\"op\":\"" + op + "\",\"args\":["
                        + "{\"type\":\"field\",\"name\":\"project_id\"},"
                        + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"" + json + "\"}]},"
                        + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"project_id\"}}],"
                        + "\"sort\":[{\"field\":\"project_id\",\"dir\":\"asc\"}]}"));
    }

    /** {@code %}, {@code _} and {@code \} in a contains value are matched literally, never as LIKE wildcards. */
    @Test
    void containsFamilyMatchesLikeMetacharactersLiterally() throws Exception {
        seedLikeMetacharacterRows();

        executeContains("co", "100%")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("100%")));
        executeContains("co", "a_b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("a_b")));
        executeContains("co", "a\\b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("a\\b")));
        executeContains("nc", "100%")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("1000", "a\\b", "a_b", "ab", "axb")));
        executeContains("ico", "A_B")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("a_b")));
        executeContains("ico", "%")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("100%")));
        executeContains("inc", "A\\B")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("100%", "1000", "a_b", "ab", "axb")));
    }

    /** A backslash-quote inside a contains value stays inside the LIKE pattern's literal. */
    @Test
    void backslashQuoteInContainsValueCannotEscapeThePattern() throws Exception {
        seedLikeMetacharacterRows();

        executeContains("co", "x\\' OR 1=1 --")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)));
        executeContains("nc", "x\\' OR 1=1 --")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(6)));
    }

    /**
     * SQL {@code LIKE '%text%'} reaches the same contains operators; a standard-SQL literal carries its
     * backslash verbatim, so the pattern matches that one backslash and nothing else.
     */
    @Test
    void sqlLikeMatchesBackslashLiterally() throws Exception {
        seedLikeMetacharacterRows();

        executeSql("SELECT project_id FROM dial_usage_log WHERE project_id LIKE '%a\\b%'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("a\\b")));
        executeSql("SELECT project_id FROM dial_usage_log WHERE project_id ILIKE '%A\\B%'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("a\\b")));
        executeSql("SELECT project_id FROM dial_usage_log WHERE project_id NOT LIKE '%a\\b%' ORDER BY project_id ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].project_id", contains("100%", "1000", "a_b", "ab", "axb")));
    }

    @Test
    void aggregateQueryRoundTrips() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 200)
                .row("embedding", "p1", 50)
                .insert();

        executeSql("SELECT event_kind, count(*) AS request_count, sum(total_tokens) AS token_sum"
                        + " FROM dial_usage_log GROUP BY event_kind ORDER BY event_kind ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].event_kind").value("chat"))
                .andExpect(jsonPath("$.rows[0].request_count").value(2))
                .andExpect(jsonPath("$.rows[0].token_sum").value(300))
                .andExpect(jsonPath("$.rows[1].event_kind").value("embedding"))
                .andExpect(jsonPath("$.rows[1].request_count").value(1))
                .andExpect(jsonPath("$.rows[1].token_sum").value(50))
                .andExpect(jsonPath("$.totalCount").doesNotExist());
    }

    @Test
    void unnamedExpressionsAreKeyedByTheirPostgresNamesOnBothSurfaces() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 200)
                .row("embedding", "p1", 50)
                .insert();

        // No AS anywhere: the columns are named `count`/`sum`, never Calcite's EXPR$n...
        final String sqlResponse = executeSql("SELECT event_kind, count(*), sum(total_tokens)"
                        + " FROM dial_usage_log GROUP BY event_kind ORDER BY event_kind ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].event_kind").value("chat"))
                .andExpect(jsonPath("$.rows[0].count").value(2))
                .andExpect(jsonPath("$.rows[0].sum").value(300))
                .andExpect(jsonPath("$.rows[0].['EXPR$1']").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // ... and the alias-free DSL form of the same query returns a byte-identical body.
        final String dslResponse = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"dial_usage_log","mode":"aggregate",
                                 "group_by":["event_kind"],
                                 "select":[
                                   {"expr":{"type":"field","name":"event_kind"}},
                                   {"expr":{"type":"fn","name":"count","args":[]}},
                                   {"expr":{"type":"fn","name":"sum","args":[{"type":"field","name":"total_tokens"}]}}],
                                 "sort":[{"field":"event_kind","dir":"asc"}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sqlResponse).isEqualTo(dslResponse);
    }

    @Test
    void projectedLiteralIsKeyedByThePostgresPlaceholder() throws Exception {
        // `?column?` is PostgreSQL's own placeholder name; it must survive as a quoted ClickHouse alias
        // and as a JSON object key.
        fixture().row("chat", "p1", 100).insert();

        executeSql("SELECT 1 FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].['?column?']").value(1));
    }

    @Test
    void derivedNameIsOrderableWithoutAnAlias() throws Exception {
        fixture()
                .row("chat", "p1", 100)
                .row("chat", "p2", 200)
                .row("embedding", "p1", 50)
                .insert();

        // Valid PostgreSQL: ORDER BY resolves the bare name against the output columns.
        executeSql("SELECT event_kind, count(*) FROM dial_usage_log GROUP BY event_kind ORDER BY count DESC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].event_kind").value("chat"))
                .andExpect(jsonPath("$.rows[0].count").value(2))
                .andExpect(jsonPath("$.rows[1].count").value(1));
    }

    @Test
    void arrayColumnProjectsAsJsonArrayMatchingTheDslEndpoint() throws Exception {
        // Array columns are SQL-visible (projection-only): the SQL path must return native JSON
        // arrays — ["core","assistant"], empty array as [] — byte-identical to the DSL endpoint.
        execute("INSERT INTO dial_usage_log (request_time, event_kind, project_id, trace_id, execution_path) VALUES "
                + "('2026-01-01 00:00:00.000', 'chat', 'p1', 'trace-arr-1', ['core','assistant']), "
                + "('2026-01-01 00:00:00.000', 'chat', 'p2', 'trace-arr-2', [])");

        final String sqlResponse = executeSql(
                        "SELECT project_id, execution_path FROM dial_usage_log ORDER BY project_id ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].execution_path", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].execution_path[0]").value("core"))
                .andExpect(jsonPath("$.rows[0].execution_path[1]").value("assistant"))
                .andExpect(jsonPath("$.rows[1].execution_path", hasSize(0)))
                .andReturn().getResponse().getContentAsString();

        final String dslResponse = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"dial_usage_log","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"project_id"}},
                                   {"expr":{"type":"field","name":"execution_path"}}],
                                 "sort":[{"field":"project_id","dir":"asc"}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sqlResponse).isEqualTo(dslResponse);
    }

    @Test
    void jsonExtractOverObjectColumnProjectsAndFiltersOverRestSql() throws Exception {
        // The carve-out end-to-end on the REST SQL path: an object column as the JSON argument of
        // JSON_EXTRACT_* is accepted by the front end, rendered to ClickHouse, and executed.
        seedWithTrace("trace-x-1", "p1", "{\"sub\":\"user-1\",\"n\":42}");
        seedWithTrace("trace-x-2", "p2", "{\"sub\":\"user-2\",\"n\":7}");

        executeSql("SELECT project_id, JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') AS sub,"
                + " JSON_EXTRACT_LONG(\"dial_usage_log_payload.jwt_claims\", 'n') AS n FROM dial_usage_log ORDER BY project_id ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].sub").value("user-1"))
                .andExpect(jsonPath("$.rows[0].n").value(42))
                .andExpect(jsonPath("$.rows[1].sub").value("user-2"))
                .andExpect(jsonPath("$.rows[1].n").value(7));

        executeSql("SELECT project_id FROM dial_usage_log"
                + " WHERE JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') = 'user-1'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"));
    }

    @Test
    void degeneratePathKeyRejectedOnRestSql() throws Exception {
        // The spec states the empty/backslash path-key rule holds "whichever form the JSON argument
        // takes". Both surfaces funnel through ExprTranslator.jsonExtract, which validates every key
        // before the text-vs-object fork — pinned here so the SQL surface cannot drift away from it.
        executeSql("SELECT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", '') FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("must not be empty")));

        executeSql("SELECT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'a\\\\b') FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("backslash")));
    }

    @Test
    void bareObjectColumnStillRejectedOnRestSqlAlongsideAnExtraction() throws Exception {
        // The carve-out is positional: extracting from dial_usage_log_payload.jwt_claims does not license ordering by the
        // bare column in the same statement.
        executeSql("SELECT JSON_EXTRACT_STRING(\"dial_usage_log_payload.jwt_claims\", 'sub') AS sub FROM dial_usage_log"
                + " ORDER BY \"dial_usage_log_payload.jwt_claims\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("dial_usage_log_payload.jwt_claims")))
                .andExpect(jsonPath("$.message").value(containsString("can only be projected")));
    }

    @Test
    void objectColumnProjectsAsJsonObjectMatchingTheDslEndpoint() throws Exception {
        // Object columns (ClickHouse JSON) are SQL-visible (projection-only): the REST SQL path must
        // return native JSON values — never an escaped string, never Java map notation — byte-identical
        // to the DSL endpoint. (Arrow, which has no JSON type, carries the same value as text.)
        seedWithTrace("trace-obj-1", "p1", "{\"sub\":\"user-1\",\"roles\":[\"admin\"]}");
        seedWithTrace("trace-obj-2", "p2", "{}");

        final String sqlResponse = executeSql(
                        "SELECT project_id, \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log ORDER BY project_id ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].sub").value("user-1"))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims'].roles[0]").value("admin"))
                // An empty JSON cell is the empty object, not null and not an escaped string.
                .andExpect(jsonPath("$.rows[1]['dial_usage_log_payload.jwt_claims']").isMap())
                .andExpect(jsonPath("$.rows[1]['dial_usage_log_payload.jwt_claims']").isEmpty())
                .andReturn().getResponse().getContentAsString();

        final String dslResponse = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"dial_usage_log","mode":"row",
                                 "select":[
                                   {"expr":{"type":"field","name":"project_id"}},
                                   {"expr":{"type":"field","name":"dial_usage_log_payload.jwt_claims"}}],
                                 "sort":[{"field":"project_id","dir":"asc"}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sqlResponse).isEqualTo(dslResponse);
    }

    @Test
    void selectStarSucceedsOnEntityWithObjectColumns() throws Exception {
        // The bare projection over dial_usage_log used to be untranslatable because of its object
        // columns; it now expands cleanly, and expands over SOURCE columns only. Every payload column
        // is left out — bodies and claim/header documents alike — not because of their type or their
        // heaviness, but because they belong to the payload enrichment and a star never fans out into
        // a grain join. Naming one still returns it (see the projection cases above).
        fixture().row("chat", "p1", 100).insert();

        executeSql("SELECT * FROM dial_usage_log LIMIT 1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].event_kind").value("chat"))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.jwt_claims']").doesNotExist())
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_tags']").doesNotExist())
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").doesNotExist());
    }

    @Test
    void selectStarOmitsTheSeededHeavyPayloadColumns() throws Exception {
        // V18 seeds request_body/response_body heavy and V29 the merged assembled_response: the canonical
        // external-client query drags none of the three payloads along, while the small *_bytes columns
        // describing two of them stay.
        fixture().row("chat", "p1", 100).insert();

        executeSql("SELECT * FROM dial_usage_log LIMIT 1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.request_body']").doesNotExist())
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.response_body']").doesNotExist())
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.assembled_response']").doesNotExist())
                .andExpect(jsonPath("$.rows[0].request_body_bytes").exists())
                .andExpect(jsonPath("$.rows[0].response_body_bytes").exists());
    }

    @Test
    void explicitlyNamedHeavyColumnIsStillReturned() throws Exception {
        // Heaviness governs only the wildcard: naming the column is the way to get the payload, and the
        // value comes back verbatim (heaviness never truncates or reshapes it).
        seedWithTrace("trace-heavy-1", "p1");
        seedPayload("trace-heavy-1", "response_body", "'{\"choices\":[{\"text\":\"hello\"}]}'");

        executeSql("SELECT project_id, \"dial_usage_log_payload.response_body\" FROM dial_usage_log LIMIT 1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.response_body']")
                        .value("{\"choices\":[{\"text\":\"hello\"}]}"));
    }

    @Test
    void namedAssembledResponseComesBackInFullAndReadsNullWhenNeverIngested() throws Exception {
        // The merged response the UI needs: unlike response_body on a streamed call, it is readable as an
        // answer without reassembling anything, and the stored value is returned byte for byte. A row
        // written before the column existed reads NULL rather than an empty string — which is why the
        // column is Nullable.
        seedWithTrace("trace-merged-1", "p-merged");
        seedPayload("trace-merged-1", "assembled_response",
                "'{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}'");
        execute("INSERT INTO dial_usage_log (request_time, event_kind, project_id, trace_id) VALUES"
                + " ('2026-01-01 00:00:00.000', 'chat', 'p-legacy', 'trace-merged-2')");

        executeSql("SELECT \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE project_id = 'p-merged' LIMIT 1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.assembled_response']")
                        .value("{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        executeSql("SELECT \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE project_id = 'p-legacy' LIMIT 1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                // Present as an explicit null, not absent: the projected column is always keyed.
                .andExpect(jsonPath("$.rows[0]['dial_usage_log_payload.assembled_response']").value(nullValue()));
    }

    @Test
    void objectColumnMisuseIsRejectedWithTheProjectionOnlyError() throws Exception {
        executeSql("SELECT project_id FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" IS NULL")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("dial_usage_log_payload.jwt_claims")))
                .andExpect(jsonPath("$.message").value(containsString("can only be projected")));
    }

    @Test
    void objectColumnFilteredWithIlikeExecutes() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"model\":\"GPT-4o\"}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"model\":\"claude\"}"))
                .insert();

        executeSql("SELECT project_id FROM dial_usage_log WHERE \"dial_usage_log_payload.request_tags\" ILIKE '%gpt-4%'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"));
    }

    @Test
    void objectColumnDocumentEqualityIgnoresKeyOrder() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"b\":2,\"a\":1}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"a\":9}"))
                .insert();

        // The literal writes the keys in the opposite order to the stored document.
        executeSql("SELECT project_id FROM dial_usage_log WHERE \"dial_usage_log_payload.request_tags\" = '{\"a\":1,\"b\":2}'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"));
    }

    @Test
    void objectColumnEqualityAgainstNonJsonLiteralIsRejected() throws Exception {
        executeSql("SELECT project_id FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" = 'x'")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("dial_usage_log_payload.jwt_claims")))
                .andExpect(jsonPath("$.message").value(containsString("JSON document")));
    }

    @Test
    void objectColumnOrderingComparisonIsRejected() throws Exception {
        executeSql("SELECT project_id FROM dial_usage_log WHERE \"dial_usage_log_payload.jwt_claims\" > '{\"a\":1}'")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("can only be projected")));
    }

    @Test
    void objectDocumentFilterMatchesTheDslEndpointByteForByte() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").requestTags("{\"model\":\"GPT-4o\"}"))
                .add(r -> r.eventKind("chat").projectId("p2").requestTags("{\"model\":\"claude\"}"))
                .insert();

        final String sqlResponse =
                executeSql("SELECT project_id FROM dial_usage_log WHERE \"dial_usage_log_payload.request_tags\" ILIKE '%gpt-4%'")
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        final String dslResponse = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entity":"dial_usage_log","mode":"row",
                                 "filter":{"op":"ico","args":[
                                   {"type":"field","name":"dial_usage_log_payload.request_tags"},
                                   {"type":"value","value_type":"string","value":"gpt-4"}]},
                                 "select":[{"expr":{"type":"field","name":"project_id"}}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sqlResponse).isEqualTo(dslResponse);
    }

    @Test
    void arrayColumnMisuseIsRejectedWithTheProjectionOnlyError() throws Exception {
        executeSql("SELECT project_id FROM dial_usage_log WHERE execution_path IS NULL")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("execution_path")))
                .andExpect(jsonPath("$.message").value(containsString("can only be projected")));
    }

    @Test
    void queryWithoutLimitGetsTheDefaultLimit() throws Exception {
        final var fixture = fixture();
        for (int i = 0; i < 105; i++) {
            fixture.row("chat", "p" + i, i);
        }
        fixture.insert();

        executeSql("SELECT project_id FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(100)));
    }

    @Test
    void limitAboveTheMaximumIsRejected() throws Exception {
        executeSql("SELECT project_id FROM dial_usage_log LIMIT 50000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("50000")))
                .andExpect(jsonPath("$.message").value(containsString("1000")));
    }

    @Test
    void unsupportedConstructIsRejectedBeforeExecution() throws Exception {
        // A recursive CTE validates against the catalog but is outside the accepted subset -> rejected at
        // translation, before any ClickHouse contact. (Joins and set operations execute now; see
        // SqlCompositeExecutionIntegrationTest and SetOperationExecutionIntegrationTest.)
        executeSql("WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 3)"
                + " SELECT n FROM r")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("recursive")));
    }

    @Test
    void unknownColumnIsRejected() throws Exception {
        executeSql("SELECT no_such_column FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("no_such_column")));
    }

    @Test
    void enrichmentColumnJoinMissIsPreservedAsNullCell() throws Exception {
        // Source + enrichment via table management; only w1 has an enrichment row, so w2's LEFT-JOINed
        // label is a join miss that must still appear in the row as a JSON null.
        IntegrationTables.source(mockMvc, "widget_metrics",
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]",
                "[\"widget_id\"]");
        try {
            IntegrationTables.enrichment(mockMvc, "widget_labels", "widget_metrics", "widget_id",
                    "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
            mockMvc.perform(post("/v1/tables/widget_metrics/rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10},{\"widget_id\":\"w2\",\"score\":20}]}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/v1/tables/widget_labels/rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"alpha\"}]}"))
                    .andExpect(status().isOk());

            executeSql("SELECT widget_id, \"widget_labels.label\" FROM widget_metrics ORDER BY widget_id ASC")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(2)))
                    .andExpect(jsonPath("$.rows[0].widget_id").value("w1"))
                    .andExpect(jsonPath("$.rows[0]['widget_labels.label']").value("alpha"))
                    .andExpect(jsonPath("$.rows[1].widget_id").value("w2"))
                    .andExpect(jsonPath("$.rows[1]['widget_labels.label']").value(nullValue()));
        } finally {
            mockMvc.perform(delete("/v1/tables/widget_labels"));
            mockMvc.perform(delete("/v1/tables/widget_metrics"));
        }
    }

    @Test
    void starOnAnEnrichedSourceMatchesTheEmptyDslSelect() throws Exception {
        // One wildcard rule on both surfaces: neither a star nor an omitted DSL `select` reaches into an
        // attached enrichment (so neither emits its LEFT JOIN), and a heavy column is left out of both.
        // The enrichment column stays available under its quoted qualified name.
        IntegrationTables.source(mockMvc, "widget_metrics",
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"},"
                        + "{\"source_name\":\"payload\",\"name\":\"payload\",\"type\":\"string\",\"heavy\":true}]",
                "[\"widget_id\"]");
        try {
            IntegrationTables.enrichment(mockMvc, "widget_labels", "widget_metrics", "widget_id",
                    "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]");
            mockMvc.perform(post("/v1/tables/widget_metrics/rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"widget_id\":\"w1\",\"score\":10,\"payload\":\"big\"}]}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/v1/tables/widget_labels/rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"widget_id\":\"w1\",\"label\":\"alpha\"}]}"))
                    .andExpect(status().isOk());

            final String starResponse = executeSql("SELECT * FROM widget_metrics")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0].widget_id").value("w1"))
                    .andExpect(jsonPath("$.rows[0].score").value(10))
                    .andExpect(jsonPath("$.rows[0].payload").doesNotExist())
                    .andExpect(jsonPath("$.rows[0]['widget_labels.label']").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            final String dslResponse = mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entity\":\"widget_metrics\",\"mode\":\"row\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(starResponse).isEqualTo(dslResponse);

            executeSql("SELECT widget_id, payload, \"widget_labels.label\" FROM widget_metrics")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].payload").value("big"))
                    .andExpect(jsonPath("$.rows[0]['widget_labels.label']").value("alpha"));
        } finally {
            mockMvc.perform(delete("/v1/tables/widget_labels"));
            mockMvc.perform(delete("/v1/tables/widget_metrics"));
        }
    }

    @Test
    void heavyFlagIsPatchableAndTakesEffectImmediately() throws Exception {
        IntegrationTables.source(mockMvc, "widget_metrics",
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"payload\",\"name\":\"payload\",\"type\":\"string\"}]",
                "[\"widget_id\"]");
        try {
            mockMvc.perform(post("/v1/tables/widget_metrics/rows").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rows\":[{\"widget_id\":\"w1\",\"payload\":\"big\"}]}"))
                    .andExpect(status().isOk());

            executeSql("SELECT * FROM widget_metrics")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].payload").value("big"));

            mockMvc.perform(patch("/v1/tables/widget_metrics/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"update\":[{\"name\":\"payload\",\"heavy\":true}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns[?(@.name == 'payload')].heavy").value(true));

            // No restart, no cached schema: the very next star reflects the new flag.
            executeSql("SELECT * FROM widget_metrics")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].widget_id").value("w1"))
                    .andExpect(jsonPath("$.rows[0].payload").doesNotExist());
        } finally {
            mockMvc.perform(delete("/v1/tables/widget_metrics"));
        }
    }

    @Test
    void starOverAnAllHeavyEntityIsRejectedNamingTheEntity() throws Exception {
        IntegrationTables.source(mockMvc, "dumps",
                "[{\"source_name\":\"blob\",\"name\":\"blob\",\"type\":\"string\",\"heavy\":true}]",
                "[\"blob\"]");
        try {
            executeSql("SELECT * FROM dumps")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"))
                    .andExpect(jsonPath("$.message").value(containsString("dumps")))
                    .andExpect(jsonPath("$.message").value(containsString("heavy")));

            executeSql("SELECT blob FROM dumps").andExpect(status().isOk());
        } finally {
            mockMvc.perform(delete("/v1/tables/dumps"));
        }
    }

    @Test
    void catalogStatementReturnsRowsOverRest() throws Exception {
        // The column query the Dremio connector sends, trailing separator and all. No rows are inserted:
        // the answer comes from ADAS's catalog, not from ClickHouse.
        executeSql("SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'adas' AND TABLE_NAME = 'dial_usage_log';")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].COLUMN_NAME").exists())
                .andExpect(jsonPath("$.rows[0].IS_NULLABLE").value("YES"))
                .andExpect(jsonPath("$.totalCount").doesNotExist());

        executeSql("SELECT DISTINCT TABLE_SCHEMA FROM INFORMATION_SCHEMA.\"TABLES\" "
                        + "WHERE TABLE_TYPE <> 'SYSTEM_TABLE' ORDER BY TABLE_SCHEMA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].TABLE_SCHEMA").value("adas"));
    }

    @Test
    void translateSqlRejectsCatalogStatement() throws Exception {
        final String body = "{\"sql\":\"SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\\\"TABLES\\\"\"}";

        mockMvc.perform(post("/v1/queries/translate-sql")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("no structured-query equivalent")));
    }

    @Test
    void schemaQualifiedNameReturnsTheSameRowsAsTheBareName() throws Exception {
        fixture().row("chat", "p1", 100).row("embedding", "p1", 50).insert();

        final String qualified = executeSql(
                        "SELECT event_kind FROM \"adas\".\"dial_usage_log\" ORDER BY event_kind")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final String bare = executeSql("SELECT event_kind FROM dial_usage_log ORDER BY event_kind")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(qualified).isEqualTo(bare);
    }
}
