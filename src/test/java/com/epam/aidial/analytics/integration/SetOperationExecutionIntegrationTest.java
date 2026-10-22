package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Set operations over {@code POST /v1/queries/execute-sql} against a live ClickHouse.
 *
 * <p>The point of these tests is that the rows ADAS returns are the rows ClickHouse returns for the same
 * text — including the three places that differs from what standard SQL would say. Each such case names
 * the standard's answer in a comment, so a regression that "fixes" the semantics toward the standard fails
 * here with the reason written down.
 *
 * <p>A second entity {@code usage_rates(trace_id, rate)} is provisioned through the table API per test so
 * the branches can read different entities.
 */
class SetOperationExecutionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String TRANSLATE_SQL = "/v1/queries/translate-sql";
    private static final String VALIDATE_SQL = "/v1/queries/validate-sql";
    private static final String RATES = "usage_rates";

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void cleanSlate() throws Exception {
        truncate();
        IntegrationTables.source(mockMvc, RATES,
                "[{\"source_name\":\"trace_id\",\"name\":\"trace_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"rate\",\"name\":\"rate\",\"type\":\"long\"}]",
                "[\"trace_id\"]");
    }

    @AfterEach
    void dropRates() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + RATES));
    }

    // ------------------------------------------------------------ multiplicity

    @Test
    void unionAllConcatenatesBothBranches() throws Exception {
        twoChatOneEmbedding();

        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log WHERE total_tokens = 100"
                + " UNION ALL SELECT event_kind FROM dial_usage_log WHERE total_tokens = 300", "event_kind");
        assertThat(kinds).containsExactlyInAnyOrder("chat", "chat");
    }

    @Test
    void unionDistinctDeduplicatesAcrossBranches() throws Exception {
        twoChatOneEmbedding();

        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log"
                + " UNION DISTINCT SELECT event_kind FROM dial_usage_log", "event_kind");
        assertThat(kinds).containsExactlyInAnyOrder("chat", "embedding");
    }

    @Test
    void bareIntersectKeepsDuplicatesBecauseClickHouseDefaultsToAll() throws Exception {
        // ClickHouse's intersect_default_mode is ALL. Standard SQL reads a bare INTERSECT as DISTINCT,
        // which would return ONE row here. Two is the database's answer, so it is ours.
        twoChatOneEmbedding();

        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log WHERE event_kind = 'chat'"
                + " INTERSECT SELECT event_kind FROM dial_usage_log WHERE event_kind = 'chat'", "event_kind");
        assertThat(kinds).containsExactly("chat", "chat");

        // Spelling DISTINCT explicitly gives the other reading, and is honoured.
        final List<String> distinct = strings("SELECT event_kind FROM dial_usage_log WHERE event_kind = 'chat'"
                + " INTERSECT DISTINCT SELECT event_kind FROM dial_usage_log WHERE event_kind = 'chat'",
                "event_kind");
        assertThat(distinct).containsExactly("chat");
    }

    @Test
    void bareExceptSubtractsMultiplicityBecauseClickHouseDefaultsToAll() throws Exception {
        // Three 'chat' rows on the left, one on the right: EXCEPT ALL leaves two. Standard SQL's DISTINCT
        // reading would leave NONE.
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(1).traceId("t1"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(2).traceId("t2"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(3).traceId("t3"))
                .insert();

        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log"
                + " EXCEPT SELECT event_kind FROM dial_usage_log WHERE total_tokens = 1", "event_kind");
        assertThat(kinds).containsExactly("chat", "chat");

        final List<String> distinct = strings("SELECT event_kind FROM dial_usage_log"
                + " EXCEPT DISTINCT SELECT event_kind FROM dial_usage_log WHERE total_tokens = 1",
                "event_kind");
        assertThat(distinct).isEmpty();
    }

    @Test
    void branchesMayReadDifferentEntities() throws Exception {
        twoChatOneEmbedding();
        rates("[{\"trace_id\":\"r1\",\"rate\":7}]");

        final List<String> traces = strings("SELECT trace_id FROM dial_usage_log"
                + " UNION ALL SELECT trace_id FROM usage_rates", "trace_id");
        assertThat(traces).containsExactlyInAnyOrder("t1", "t2", "t3", "r1");
    }

    // ------------------------------------------------- ORDER BY / LIMIT binding

    @Test
    void trailingOrderByAndLimitBindToTheLastBranchOnly() throws Exception {
        // ClickHouse's reading. Standard SQL would order and truncate the COMBINED result, returning
        // {embedding} for `ORDER BY event_kind LIMIT 1`; here the first branch is untouched and only the
        // second is sorted and cut, so the answer is the first branch's two rows plus one of the second's.
        twoChatOneEmbedding();

        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log WHERE event_kind = 'chat'"
                + " UNION ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 1", "event_kind");
        assertThat(kinds).containsExactly("chat", "chat", "chat");
    }

    @Test
    void trailingOrderByWithNoLimitStillSortsTheLastBranch() throws Exception {
        // The clause with no LIMIT beside it: the rows are the same either way, so what this pins is their
        // ORDER — which is the whole content of the clause, and which Calcite's converter used to discard
        // for want of a row bound (see SqlSetOperations.reassociate). Measured on ClickHouse 26.3.24:
        // `SELECT 5 AS x UNION ALL SELECT arrayJoin([3,1,2]) AS x ORDER BY x` returns 5, 1, 2, 3.
        // `DESC` is the discriminator, because ascending order happens to be the insertion order here and
        // would pass on a dropped sort too. The first branch's single row is taken out of the answer before
        // the order is asserted: ClickHouse runs the branches of a UNION ALL in parallel and does not
        // guarantee where one branch's rows land relative to another's — what it does guarantee, and what
        // the clause says, is the order WITHIN the last branch.
        twoChatOneEmbedding();
        rates("[{\"trace_id\":\"r1\",\"rate\":7}]");

        final List<String> traces = strings("SELECT trace_id FROM usage_rates"
                + " UNION ALL SELECT trace_id FROM dial_usage_log ORDER BY trace_id DESC", "trace_id");
        assertThat(traces).hasSize(4).contains("r1");
        traces.remove("r1");
        assertThat(traces).containsExactly("t3", "t2", "t1");
    }

    @Test
    void trailingClauseBindsToTheChainsFinalSelectOnly() throws Exception {
        // A union is a list of SELECTs to ClickHouse and the clause belongs to the final one, which in a
        // mixed-precedence chain is not the last operand of the parse tree: `A UNION ALL B INTERSECT ALL
        // C ORDER BY … LIMIT 2` orders and truncates C, then intersects, then concatenates. Here C's first
        // two rows in `event_kind` order are the two chats, B is the one embedding row, so the
        // intersection is empty and only A comes back. Binding the clause to `B INTERSECT ALL C` instead —
        // the last operand — would intersect first and return the embedding as well.
        // Measured on ClickHouse 26.3.24 with the same shape over literals: one row.
        twoChatOneEmbedding();

        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log WHERE total_tokens = 100"
                + " UNION ALL SELECT event_kind FROM dial_usage_log WHERE total_tokens = 50"
                + " INTERSECT ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 2",
                "event_kind");
        assertThat(kinds).containsExactly("chat");
    }

    @Test
    void trailingClauseUnderWithStillBindsToTheLastBranch() throws Exception {
        // Calcite parses a trailing clause as a wrapper around the WITH, not around the operation, so this
        // is the same rule reached through a different parse shape. ClickHouse answers the equivalent text
        // with three rows (26.3.24): the first branch's two untouched, plus the last branch's single
        // ordered row. Reading the clause as the combined result's would return one row.
        twoChatOneEmbedding();

        final List<String> kinds = strings("WITH c AS (SELECT event_kind FROM dial_usage_log)"
                + " SELECT event_kind FROM c WHERE event_kind = 'chat'"
                + " UNION ALL SELECT event_kind FROM c ORDER BY event_kind LIMIT 1", "event_kind");
        assertThat(kinds).containsExactly("chat", "chat", "chat");
    }

    @Test
    void parenthesizedBranchBeforeTheLastOneIsExecuted() throws Exception {
        // The clause still follows a bare final SELECT, which is the only thing ClickHouse's grammar asks
        // for: it executes `(SELECT 'chat' AS k) UNION ALL SELECT … ORDER BY k LIMIT 1` and returns both
        // branches. Refusing it would refuse a shape BI tools and hand-written ClickHouse both produce.
        twoChatOneEmbedding();

        final List<String> kinds = strings("(SELECT event_kind FROM dial_usage_log WHERE total_tokens = 100)"
                + " UNION ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 1",
                "event_kind");
        assertThat(kinds).containsExactly("chat", "chat");
    }

    @Test
    void wrappingTheOperationOrdersAndTruncatesTheCombinedResult() throws Exception {
        // The spelling that means the same thing on both, and the one a caller wanting the standard
        // behaviour is pointed at.
        twoChatOneEmbedding();

        final List<String> kinds = strings("SELECT event_kind FROM (SELECT event_kind FROM dial_usage_log"
                + " WHERE event_kind = 'chat' UNION ALL SELECT event_kind FROM dial_usage_log)"
                + " ORDER BY event_kind DESC LIMIT 1", "event_kind");
        assertThat(kinds).containsExactly("embedding");
    }

    @Test
    void branchKeepsItsOwnLimit() throws Exception {
        twoChatOneEmbedding();

        final List<String> kinds = strings("(SELECT event_kind FROM dial_usage_log LIMIT 1)"
                + " UNION ALL (SELECT event_kind FROM dial_usage_log LIMIT 2)", "event_kind");
        assertThat(kinds).hasSize(3);
    }

    @Test
    void trailingLimitIsNotGovernedAsOuterLimit() throws Exception {
        // The limit binds to a branch, so the statement states no outer bound — which means a value above
        // the 1000-row maximum is not the over-maximum rejection it would be on an outer statement.
        twoChatOneEmbedding();

        executeSql("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log LIMIT 5000")
                .andExpect(status().isOk());
    }

    @Test
    void theCombinedResultTakesTheDefaultLimit() throws Exception {
        final List<String> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add("t" + i);
        }
        final var fixture = fixture();
        for (final String trace : rows) {
            fixture.add(r -> r.eventKind("chat").projectId("p1").totalTokens(1).traceId(trace));
        }
        fixture.insert();

        // 120 rows across the two branches, bounded to the default 100 over the combined result.
        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log", "event_kind");
        assertThat(kinds).hasSize(100);
    }

    // --------------------------------------------------------- shape and nesting

    @Test
    void intersectBindsTighterThanUnion() throws Exception {
        // `A UNION ALL B INTERSECT ALL C` is `A UNION ALL (B INTERSECT ALL C)` on both engines. Here B and
        // C are disjoint, so the intersection is empty and only A's rows come back.
        twoChatOneEmbedding();

        final List<String> kinds = strings("SELECT event_kind FROM dial_usage_log WHERE total_tokens = 100"
                + " UNION ALL SELECT event_kind FROM dial_usage_log WHERE total_tokens = 300"
                + " INTERSECT ALL SELECT event_kind FROM dial_usage_log WHERE total_tokens = 50",
                "event_kind");
        assertThat(kinds).containsExactly("chat");
    }

    @Test
    void setOperationNestsAsDerivedTableAndCteBody() throws Exception {
        twoChatOneEmbedding();

        assertThat(rows(executeSql("SELECT count(*) AS n FROM (SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log) t")).get(0).get("n").asLong())
                .isEqualTo(6L);
        assertThat(rows(executeSql("WITH both AS (SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log)"
                + " SELECT count(*) AS n FROM both")).get(0).get("n").asLong()).isEqualTo(6L);
    }

    @Test
    void setOperationNestsAsInOperand() throws Exception {
        twoChatOneEmbedding();

        final List<String> traces = strings("SELECT trace_id FROM dial_usage_log WHERE event_kind IN"
                + " (SELECT event_kind FROM dial_usage_log WHERE total_tokens = 50"
                + " UNION DISTINCT SELECT event_kind FROM dial_usage_log WHERE total_tokens = 100)",
                "trace_id");
        assertThat(traces).containsExactlyInAnyOrder("t1", "t2", "t3");
    }

    @Test
    void nestedOperationWithAnUnnamedColumnExecutes() throws Exception {
        // The one shape where the operation's column names and its rendered branches' could disagree: an
        // unnamed expression in the first branch, nested so no alias injection covers for it. ClickHouse
        // answers Code 47 UNKNOWN_IDENTIFIER when the block above references a name the subquery does not
        // expose, so this only passes if the operation is named after what its branches render.
        twoChatOneEmbedding();

        final JsonNode derived = rows(executeSql("SELECT * FROM (SELECT count(*) FROM dial_usage_log"
                + " UNION ALL SELECT count(*) FROM dial_usage_log) t").andExpect(status().isOk()));
        assertThat(derived.size()).isEqualTo(2);
        assertThat(derived.get(0).propertyNames()).containsExactly("count");
        assertThat(derived.get(0).get("count").asLong()).isEqualTo(3L);

        assertThat(rows(executeSql("WITH c AS (SELECT count(*) FROM dial_usage_log"
                + " UNION ALL SELECT count(*) FROM dial_usage_log) SELECT * FROM c")
                .andExpect(status().isOk())).size()).isEqualTo(2);
        // And validate-sql agrees, as it must for a statement execute-sql does not reject.
        validateSql("SELECT * FROM (SELECT count(*) FROM dial_usage_log"
                + " UNION ALL SELECT count(*) FROM dial_usage_log) t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void outputNamesComeFromTheFirstBranch() throws Exception {
        twoChatOneEmbedding();

        final JsonNode rows = rows(executeSql("SELECT event_kind AS k FROM dial_usage_log"
                + " UNION ALL SELECT event_kind AS other FROM dial_usage_log"));
        assertThat(rows.get(0).propertyNames()).containsExactly("k");
    }

    @Test
    void branchStarOmitsHeavyColumns() throws Exception {
        twoChatOneEmbedding();

        final JsonNode rows = rows(executeSql(
                "SELECT * FROM dial_usage_log UNION ALL SELECT * FROM dial_usage_log"));
        assertThat(rows.get(0).propertyNames())
                .doesNotContain("request_body")
                .doesNotContain("assembled_response");
    }

    // ------------------------------------------------------------- the refusals

    @Test
    void bareUnionIsRejectedAsClickHouseRejectsIt() throws Exception {
        executeSql("SELECT event_kind FROM dial_usage_log UNION SELECT event_kind FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("UNION requires ALL or DISTINCT")));
    }

    @Test
    void minusIsRejected() throws Exception {
        executeSql("SELECT event_kind FROM dial_usage_log MINUS SELECT event_kind FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("MINUS is not supported")));
    }

    @Test
    void trailingClauseAfterParenthesizedBranchIsRejected() throws Exception {
        executeSql("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL (SELECT event_kind FROM dial_usage_log) ORDER BY event_kind")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("belongs to its last branch")));
    }

    @Test
    void bareUnionIsRejectedInsideJoinConditionAndCase() throws Exception {
        // Two nested positions ClickHouse refuses as readily as any other — it answers Code 558
        // EXPECTED_ALL_OR_DISTINCT for a bare UNION wherever it stands — so ADAS refuses them here rather
        // than executing Calcite's DISTINCT reading of a statement the database would not run.
        executeSql("SELECT a.event_kind FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.event_kind = b.event_kind AND a.event_kind IN (SELECT event_kind"
                + " FROM dial_usage_log UNION SELECT event_kind FROM dial_usage_log)")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("UNION requires ALL or DISTINCT")));
        executeSql("SELECT CASE WHEN event_kind IN (SELECT event_kind FROM dial_usage_log"
                + " UNION SELECT event_kind FROM dial_usage_log) THEN 1 ELSE 0 END AS f"
                + " FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("UNION requires ALL or DISTINCT")));
    }

    @Test
    void mismatchedBranchArityIsRejected() throws Exception {
        executeSql("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind, trace_id FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void branchColumnTypesThatDoNotUnifyAreRejected() throws Exception {
        // Text against a number: ClickHouse has no supertype for String and UInt64 either, and this
        // surface refuses it before contacting the database — naming the column and the two types rather
        // than a CAST the caller never wrote.
        executeSql("SELECT event_kind AS v FROM dial_usage_log"
                + " UNION ALL SELECT total_tokens AS v FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("column 'v'")))
                .andExpect(jsonPath("$.message").value(containsString("(string vs int64)")));
        validateSql("SELECT event_kind AS v FROM dial_usage_log"
                + " UNION ALL SELECT total_tokens AS v FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("branch_type_mismatch"));
    }

    @Test
    void setOperationOverCatalogMetadataIsRejected() throws Exception {
        executeSql("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                + " UNION ALL SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        executeSql("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                + " UNION ALL SELECT event_kind FROM dial_usage_log")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("INFORMATION_SCHEMA")));
    }

    // ------------------------------------------------- the other two surfaces

    @Test
    void setOperationHasNoDslDocumentButExecutes() throws Exception {
        twoChatOneEmbedding();
        final String sql = "SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log";

        executeSql(sql).andExpect(status().isOk()).andExpect(jsonPath("$.rows", hasSize(6)));
        translateSql(sql)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("set operations")));
    }

    @Test
    void validateSqlAgreesWithExecution() throws Exception {
        validateSql("SELECT event_kind FROM dial_usage_log UNION ALL SELECT event_kind FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.issues", hasSize(0)));
        validateSql("SELECT event_kind FROM dial_usage_log UNION SELECT event_kind FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("unsupported_construct"));
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions executeSql(String sql) throws Exception {
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body(sql)));
    }

    private ResultActions translateSql(String sql) throws Exception {
        return mockMvc.perform(post(TRANSLATE_SQL).contentType(MediaType.APPLICATION_JSON).content(body(sql)));
    }

    private ResultActions validateSql(String sql) throws Exception {
        return mockMvc.perform(post(VALIDATE_SQL).contentType(MediaType.APPLICATION_JSON).content(body(sql)));
    }

    private static String body(String sql) {
        return "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private void rates(String rowsJson) throws Exception {
        mockMvc.perform(post("/v1/tables/" + RATES + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":" + rowsJson + "}"))
                .andExpect(status().isOk());
    }

    /** A strict parse of the response body — jsonPath alone passes on malformed JSON. */
    private JsonNode rows(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString()).get("rows");
    }

    private List<String> strings(String sql, String column) throws Exception {
        final ResultActions actions = executeSql(sql).andExpect(status().isOk());
        final List<String> values = new ArrayList<>();
        rows(actions).forEach(row -> values.add(row.get(column).asString()));
        return values;
    }

    private void twoChatOneEmbedding() {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(100).traceId("t1"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(300).traceId("t2"))
                .add(r -> r.eventKind("embedding").projectId("p2").totalTokens(50).traceId("t3"))
                .insert();
    }
}
