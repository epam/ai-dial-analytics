package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The composite half of {@code POST /v1/queries/execute-sql} against a live ClickHouse: joins (with standard
 * outer-join NULLs), CTEs, derived tables, subqueries, CASE and window functions execute through the shared
 * pipeline; the outer statement keeps the shared row governance while inner blocks run as written; composite
 * statements have no DSL document. A second entity {@code usage_rates(trace_id, rate)} is provisioned through
 * the table API per test so a join has something to join.
 */
class SqlCompositeExecutionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String TRANSLATE_SQL = "/v1/queries/translate-sql";
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

    private ResultActions executeSql(String sql) throws Exception {
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body(sql)));
    }

    private ResultActions translateSql(String sql) throws Exception {
        return mockMvc.perform(post(TRANSLATE_SQL).contentType(MediaType.APPLICATION_JSON).content(body(sql)));
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

    private void threeTracedRows() {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(100).traceId("t1").requestTime("2026-01-01 00:00:01.000"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(300).traceId("t2").requestTime("2026-01-01 00:00:02.000"))
                .add(r -> r.eventKind("embedding").projectId("p2").totalTokens(50).traceId("t3").requestTime("2026-01-01 00:00:03.000"))
                .insert();
    }

    // ------------------------------------------------------------------ joins

    @Test
    void innerJoinReturnsMatchedRows() throws Exception {
        threeTracedRows();
        rates("[{\"trace_id\":\"t1\",\"rate\":5},{\"trace_id\":\"t3\",\"rate\":2}]");

        final JsonNode rows = rows(executeSql("SELECT l.project_id, r.rate FROM dial_usage_log l"
                + " JOIN usage_rates r ON l.trace_id = r.trace_id ORDER BY r.rate DESC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.totalCount").doesNotExist()));
        assertThat(rows.get(0).get("project_id").asString()).isEqualTo("p1");
        assertThat(rows.get(0).get("rate").asLong()).isEqualTo(5);
        assertThat(rows.get(1).get("project_id").asString()).isEqualTo("p2");
        assertThat(rows.get(1).get("rate").asLong()).isEqualTo(2);
    }

    @Test
    void leftJoinYieldsNullForAnUnmatchedNonNullableColumn() throws Exception {
        // `rate` is a non-nullable column: without join_use_nulls the unmatched row would read 0.
        threeTracedRows();
        rates("[{\"trace_id\":\"t1\",\"rate\":5}]");

        executeSql("SELECT l.trace_id, r.rate FROM dial_usage_log l LEFT JOIN usage_rates r ON l.trace_id = r.trace_id"
                + " ORDER BY l.trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].rate").value(5))
                .andExpect(jsonPath("$.rows[1].rate").value(nullValue()))
                .andExpect(jsonPath("$.rows[2].rate").value(nullValue()));
    }

    @Test
    void selfJoinUnderAliases() throws Exception {
        fixture()
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(1).traceId("t1").chatId("c1"))
                .add(r -> r.eventKind("chat").projectId("p1").totalTokens(2).traceId("t2").chatId("c1"))
                .insert();

        executeSql("SELECT a.trace_id AS left_trace, b.trace_id AS right_trace FROM dial_usage_log a"
                + " JOIN dial_usage_log b ON a.chat_id = b.chat_id AND a.trace_id < b.trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].left_trace").value("t1"))
                .andExpect(jsonPath("$.rows[0].right_trace").value("t2"));
    }

    @Test
    void joinedAggregateIsBoundedByTheDefaultLimit() throws Exception {
        final Fixture fixture = fixture();
        final StringBuilder rateRows = new StringBuilder("[");
        for (int i = 0; i < 150; i++) {
            final String trace = "t" + i;
            fixture.add(r -> r.eventKind("chat").projectId("p" + trace).totalTokens(1).traceId(trace));
            rateRows.append(i == 0 ? "" : ",").append("{\"trace_id\":\"").append(trace).append("\",\"rate\":1}");
        }
        fixture.insert();
        rates(rateRows.append(']').toString());

        executeSql("SELECT l.project_id, count(*) AS n FROM dial_usage_log l JOIN usage_rates r ON l.trace_id = r.trace_id"
                + " GROUP BY l.project_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(100)));
    }

    @Test
    void starOverTheJoinOmitsHeavyColumns() throws Exception {
        threeTracedRows();
        rates("[{\"trace_id\":\"t1\",\"rate\":5}]");

        final JsonNode rows = rows(executeSql(
                "SELECT * FROM dial_usage_log l JOIN usage_rates r ON l.trace_id = r.trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1))));
        assertThat(rows.get(0).has("dial_usage_log_payload.request_body")).isFalse();
        assertThat(rows.get(0).has("rate")).isTrue();
        assertThat(rows.get(0).has("project_id")).isTrue();
    }

    @Test
    void joinWithoutAnEqualityIsRejectedBeforeExecution() throws Exception {
        executeSql("SELECT l.trace_id FROM dial_usage_log l JOIN usage_rates r ON l.total_tokens > r.rate")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("equality")));
    }

    // ------------------------------------------------------------------ CTEs, derived tables, subqueries

    @Test
    void cteFeedsAnAggregate() throws Exception {
        threeTracedRows();

        executeSql("WITH chats AS (SELECT project_id, total_tokens FROM dial_usage_log WHERE event_kind = 'chat' LIMIT 500)"
                + " SELECT project_id, sum(total_tokens) AS t FROM chats GROUP BY project_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"))
                .andExpect(jsonPath("$.rows[0].t").value(400));
    }

    @Test
    void filterOverTheLimitedDerivedTable() throws Exception {
        threeTracedRows();

        executeSql("SELECT project_id, total_tokens FROM (SELECT project_id, total_tokens FROM dial_usage_log"
                + " ORDER BY total_tokens DESC LIMIT 2) t WHERE total_tokens > 100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].total_tokens").value(300));
    }

    @Test
    void aggregateOverAnAggregate() throws Exception {
        threeTracedRows();

        executeSql("SELECT avg(n) AS mean FROM (SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id) t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].mean").value(1.5));
    }

    @Test
    void innerLimitAboveTheSharedMaximumRunsAsWritten() throws Exception {
        threeTracedRows();

        executeSql("SELECT count(*) AS n FROM (SELECT event_id FROM dial_usage_log LIMIT 5000) t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(3));
        executeSql("SELECT event_id FROM dial_usage_log LIMIT 5000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("5000")));
    }

    @Test
    void innerBlockWithoutTheLimitIsNotDefaulted() throws Exception {
        final Fixture fixture = fixture();
        for (int i = 0; i < 250; i++) {
            final String trace = "trace-" + i;
            fixture.add(r -> r.eventKind("chat").projectId("p1").totalTokens(1).traceId(trace));
        }
        fixture.insert();

        executeSql("SELECT count(*) AS n FROM (SELECT DISTINCT trace_id FROM dial_usage_log) t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(250));
    }

    @Test
    void subqueryPredicatesFilterRows() throws Exception {
        threeTracedRows();
        rates("[{\"trace_id\":\"t1\",\"rate\":5},{\"trace_id\":\"t3\",\"rate\":2}]");

        executeSql("SELECT trace_id FROM dial_usage_log WHERE trace_id IN (SELECT trace_id FROM usage_rates WHERE rate > 4)")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].trace_id").value("t1"));
        executeSql("SELECT trace_id FROM dial_usage_log WHERE trace_id NOT IN (SELECT trace_id FROM usage_rates) ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].trace_id").value("t2"));
        executeSql("SELECT count(*) AS n FROM dial_usage_log WHERE NOT EXISTS (SELECT 1 FROM usage_rates WHERE rate > 100)")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(3));
        executeSql("SELECT count(*) AS n FROM dial_usage_log WHERE EXISTS (SELECT 1 FROM usage_rates WHERE rate > 100)")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].n").value(0));
    }

    @Test
    void scalarSubqueries() throws Exception {
        threeTracedRows();
        rates("[{\"trace_id\":\"t1\",\"rate\":5},{\"trace_id\":\"t3\",\"rate\":2}]");

        executeSql("SELECT trace_id FROM dial_usage_log WHERE total_tokens > (SELECT avg(total_tokens) FROM dial_usage_log)")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].trace_id").value("t2"));
        executeSql("SELECT project_id, (SELECT count(*) FROM usage_rates) AS rates FROM dial_usage_log ORDER BY project_id LIMIT 1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].rates").value(2));
    }

    @Test
    void correlatedSubqueryIsRejectedBeforeExecution() throws Exception {
        executeSql("SELECT trace_id FROM dial_usage_log l WHERE EXISTS (SELECT 1 FROM usage_rates r WHERE r.trace_id = l.trace_id)")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("correlated")));
    }

    @Test
    void bareUnionIsRejectedBeforeExecution() throws Exception {
        // Set operations execute (see SetOperationExecutionIntegrationTest); what is refused is a spelling
        // ClickHouse itself refuses, because its union_default_mode has no default.
        executeSql("SELECT trace_id FROM dial_usage_log UNION SELECT trace_id FROM usage_rates")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("UNION requires ALL or DISTINCT")));
    }

    // ------------------------------------------------------------------ CASE and windows

    @Test
    void caseClassifiesRowsAndGroupsByTheAlias() throws Exception {
        fixture().row("chat", "p1", 5000).row("chat", "p1", 500).row("chat", "p1", 50).row("chat", "p1", 40).insert();

        executeSql("SELECT CASE WHEN total_tokens > 1000 THEN 'large' WHEN total_tokens > 100 THEN 'medium' ELSE 'small' END AS size,"
                + " count(*) AS n FROM dial_usage_log GROUP BY size ORDER BY n DESC, size")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].size").value("small"))
                .andExpect(jsonPath("$.rows[0].n").value(2))
                .andExpect(jsonPath("$.rows[1].size").value("large"))
                .andExpect(jsonPath("$.rows[2].size").value("medium"));
    }

    @Test
    void caseWithoutElseYieldsNull() throws Exception {
        fixture().row("chat", "p1", 100).row("embedding", "p1", 50).insert();

        executeSql("SELECT event_kind, CASE WHEN event_kind = 'chat' THEN total_tokens END AS chat_tokens FROM dial_usage_log"
                + " ORDER BY event_kind")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].chat_tokens").value(100))
                .andExpect(jsonPath("$.rows[1].chat_tokens").value(nullValue()));
    }

    @Test
    void coalesceAndNullifExecuteThroughCase() throws Exception {
        fixture().add(r -> r.eventKind("chat").projectId("p1").totalTokens(1).chatId("").traceId("t1")).insert();

        executeSql("SELECT coalesce(nullif(chat_id, ''), 'none') AS chat FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].chat").value("none"));
    }

    @Test
    void rowNumberPartitionsAndOrders() throws Exception {
        threeTracedRows();

        executeSql("SELECT trace_id, row_number() OVER (PARTITION BY project_id ORDER BY request_time) AS rn"
                + " FROM dial_usage_log ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].rn").value(1))
                .andExpect(jsonPath("$.rows[1].rn").value(2))
                .andExpect(jsonPath("$.rows[2].rn").value(1));
    }

    @Test
    void runningSumWithTheFrame() throws Exception {
        threeTracedRows();

        executeSql("SELECT trace_id, sum(total_tokens) OVER (ORDER BY request_time ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"
                + " AS running FROM dial_usage_log ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].running").value(100))
                .andExpect(jsonPath("$.rows[1].running").value(400))
                .andExpect(jsonPath("$.rows[2].running").value(450));
    }

    @Test
    void averageWindowAndRankOverAnAggregate() throws Exception {
        threeTracedRows();

        executeSql("SELECT trace_id, avg(total_tokens) OVER (PARTITION BY project_id) AS mean FROM dial_usage_log ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].mean").value(200.0))
                .andExpect(jsonPath("$.rows[2].mean").value(50.0));
        executeSql("SELECT project_id, sum(total_tokens) AS t, rank() OVER (ORDER BY sum(total_tokens) DESC) AS r"
                + " FROM dial_usage_log GROUP BY project_id ORDER BY r")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"))
                .andExpect(jsonPath("$.rows[0].r").value(1))
                .andExpect(jsonPath("$.rows[1].r").value(2));
    }

    @Test
    void windowFilteredThroughTheDerivedTable() throws Exception {
        threeTracedRows();

        executeSql("SELECT trace_id FROM (SELECT trace_id, row_number() OVER (PARTITION BY project_id ORDER BY request_time) AS rn"
                + " FROM dial_usage_log) t WHERE rn = 1 ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].trace_id").value("t1"))
                .andExpect(jsonPath("$.rows[1].trace_id").value("t3"));
        executeSql("SELECT trace_id FROM dial_usage_log WHERE row_number() OVER (ORDER BY request_time) = 1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    /**
     * A window inside a select-list {@code CASE} is a window in {@code select}: it executes, and the document
     * {@code translate-sql} hands back for it executes unchanged through {@code /v1/queries/execute} — the
     * equivalence the translation endpoint promises.
     */
    @Test
    void windowInsideSelectListCaseExecutesAndSoDoesItsDocument() throws Exception {
        threeTracedRows();
        final String sql = "SELECT trace_id, CASE WHEN row_number() OVER (ORDER BY request_time) > 1"
                + " THEN 'late' ELSE 'first' END AS phase FROM dial_usage_log ORDER BY trace_id";

        executeSql(sql)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].phase").value("first"))
                .andExpect(jsonPath("$.rows[1].phase").value("late"))
                .andExpect(jsonPath("$.rows[2].phase").value("late"));

        final JsonNode document = json.readTree(translateSql(sql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("query");
        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(document.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].phase").value("first"))
                .andExpect(jsonPath("$.rows[2].phase").value("late"));
    }

    // ------------------------------------------------------------------ translate-sql

    @Test
    void compositeStatementHasNoDslDocumentButCaseDoes() throws Exception {
        translateSql("SELECT l.trace_id FROM dial_usage_log l JOIN usage_rates r ON l.trace_id = r.trace_id")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("no structured-query equivalent")));
        // A statement the TRANSLATOR nests — its projection drops the computed sort key — gets the same
        // refusal over HTTP, message included, rather than the superseded ORDER BY message. The wording
        // names that nesting so the caller is not told about a derived table they never wrote.
        translateSql("SELECT project_id FROM dial_usage_log GROUP BY project_id ORDER BY count(*) DESC LIMIT 5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message")
                        .value(containsString("to carry an ORDER BY, GROUP BY or DISTINCT key")))
                .andExpect(jsonPath("$.message").value(containsString("no structured-query equivalent")));
        // And a statement nested for the other of the two keys the wording now names: this one has no
        // ORDER BY at all, so being told about an ORDER BY expression would be the same wrong answer the
        // enumeration exists to prevent.
        translateSql("SELECT count(*) AS n FROM dial_usage_log GROUP BY lower(event_kind)")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message")
                        .value(containsString("to carry an ORDER BY, GROUP BY or DISTINCT key")));
        translateSql("SELECT CASE WHEN success THEN 1 ELSE 0 END AS ok FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.select[0].expr.type").value("case"))
                .andExpect(jsonPath("$.query.select[0].as").value("ok"));
    }
}
