package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The query execution budget, with the memory ceiling set low enough that any real query trips it.
 *
 * <p>What this pins is the <b>shape of the refusal</b>, which is the part that has to be right: a budget
 * refusal is deterministic, so it is 422 with its own stable code — never the 503 an outage produces, which
 * a client would correctly respond to by retrying, and which would therefore loop forever. And never the
 * empty transport failure the change exists to remove, which is what the caller saw when the server kept
 * executing past the client's own timeout.
 *
 * <p>Both query surfaces are asserted, because the SQL endpoint translates into the same structured query:
 * a ceiling that bound only the DSL surface would leave the identical statement unbounded when written as
 * SQL. And the write path is asserted <em>not</em> bounded — a refresh recomputes a whole window by design,
 * so a capacity ceiling meant for a caller's ad-hoc query must not silently become one for the refresh.
 *
 * <p>Two things are deliberately not asserted here. That a query inside the ceilings is unaffected is
 * asserted by every other integration class in this suite: they all run through this same budget at its
 * shipped defaults (20s / 2GB) and return their rows unchanged. And the wall-clock ceiling is covered by
 * {@code QueryBudgetTest} rather than end to end, because tripping it would cost the suite the wall-clock
 * time it is asserting; what the unit test pins is the mapping from the server's error code to the ceiling
 * the message names, which is the half that can be wrong.
 *
 * <p>Requires a running Docker daemon.
 */
@TestPropertySource(properties = {
        // Unreachable under the shipped 2GB default: no test-sized query can exceed it. The spill threshold
        // is 0 (disabled), so the refusal is the memory ceiling rather than an external-aggregation failure.
        "query.budget.max-memory=1KB",
        "query.budget.spill-threshold=0B"})
class QueryBudgetIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";
    private static final String PIPELINES = "/v1/pipelines";
    private static final String ROLLUP = "usage_daily_budget";
    private static final String PIPELINE = "usage_daily_budget_rollup";

    /**
     * An aggregate-mode query, because aggregation state is what a memory ceiling actually bounds. A
     * two-row projection allocates less than ClickHouse's {@code min_untracked_memory} (4 MiB by default)
     * and is therefore never measured against the ceiling at all, however low the ceiling is set — the same
     * reason {@code PreviewBudget} caps memory rather than rows read. The unbounded heavy-column read below
     * is the row-mode case, and it seeds enough payload to be tracked.
     */
    private static final String AGGREGATE_QUERY = """
            {"entity":"dial_usage_log","mode":"aggregate",
             "group_by":["project_id"],
             "select":[{"expr":{"type":"field","name":"project_id"}},
                       {"expr":{"type":"fn","name":"count","args":[]},"as":"calls"}]}""";

    private static final String TARGET_COLUMNS = """
            [{"source_name":"deployment","name":"deployment","type":"string"},
             {"source_name":"day","name":"day","type":"timestamp"},
             {"source_name":"call_count","name":"call_count","type":"long"},
             {"source_name":"tokens","name":"tokens","type":"long","nullable":true}]""";

    private static final String DECLARATION = """
            "target":"usage_daily_budget",
            "inputs":["dial_usage_log"],
            "group_by":[{"column":"deployment"},
                        {"trunc":{"column":"request_time","unit":"day"},"as":"day"}],
            "measures":[{"name":"call_count","fn":"count"},
                        {"name":"tokens","fn":"sum","column":"total_tokens"}],
            "trigger":{"kind":"schedule","cron":"0 0 * * * *"}""";

    @BeforeEach
    void cleanSlate() {
        truncate();
        seedEvent(1);
        seedEvent(2);
    }

    @AfterEach
    void dropAll() throws Exception {
        mockMvc.perform(delete(PIPELINES + "/" + PIPELINE));
        mockMvc.perform(delete("/v1/tables/" + ROLLUP));
    }

    private void seedEvent(int seq) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, total_tokens, _ingested_at) VALUES"
                + " (generateUUIDv4(), 'p', 'gpt-4', '2026-01-01 10:0" + seq + ":00.000',"
                + " 't" + seq + "', 's" + seq + "', 10, '2026-01-01 10:0" + seq + ":01.000')");
    }

    @Test
    void dslQueryExceedingItsBudgetIsRefusedWithItsOwnCode() throws Exception {
        mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(AGGREGATE_QUERY))
                .andExpect(status().isUnprocessableContent())
                // Its own code, distinct from a malformed query (400 bad_request) and from an outage
                // (503 clickhouse_unavailable) — the whole point is that a caller can tell "this query is
                // too expensive" from "ClickHouse is down".
                .andExpect(jsonPath("$.error").value("query_budget_exceeded"))
                .andExpect(jsonPath("$.status").value(422))
                // Names the ceiling that was exceeded, its configured value, and what to narrow, echoing no
                // driver text.
                .andExpect(jsonPath("$.message").value(containsString("memory ceiling")))
                .andExpect(jsonPath("$.message").value(containsString("query.budget.*")))
                .andExpect(jsonPath("$.message").value(containsString("narrow the time range")))
                // No partial or sampled answer: the budget bounds the cost, never the result.
                .andExpect(jsonPath("$.rows").doesNotExist());
    }

    @Test
    void sqlQueryInheritsTheSameRefusal() throws Exception {
        // The SQL endpoint translates into the structured query and runs it through the same pipeline, so a
        // ceiling that bound only the DSL surface would leave the identical statement unbounded here.
        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT project_id, count(*) AS calls FROM dial_usage_log"
                                + " GROUP BY project_id\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("query_budget_exceeded"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(containsString("memory ceiling")));
    }

    @Test
    void unboundedHeavyColumnReadIsRefusedRatherThanFailingTheConnection() throws Exception {
        // The reproduction the change exists for: a heavy column read with no partition-pruning predicate, in a
        // shape no returned page bounds — a search over the body, so every body is read to decide which rows
        // count (a body that is only projected is fetched for the returned page alone, see ReadShapingIntegrationTest).
        // What the caller must get is a statement about the query, not a connection that died
        // while ClickHouse kept reading. The payload is generated server-side so the fixture costs a
        // statement rather than megabytes of SQL text, and it is deliberately large enough to be measured:
        // a read under ClickHouse's min_untracked_memory never reaches the tracker at all.
        execute("INSERT INTO dial_usage_log (event_id, request_time, project_id, deployment, trace_id,"
                + " core_span_id) SELECT generateUUIDv4(), '" + fixtureTime("10:00:00.000") + "', 'heavy', 'gpt-4',"
                + " 'h' || toString(number), 's' || toString(number) FROM numbers(40)");
        // The bulk lives on the payload table now, selected straight out of the parent rows — request_time
        // included, so the two tables agree on it. They are partition-aligned in the catalog, so a keyed
        // grain join bounds the payload side by the parent's value.
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id,"
                + " assembled_response) SELECT event_id, request_time, trace_id, core_span_id,"
                + " repeat('x', 262144) FROM dial_usage_log WHERE project_id = 'heavy'");

        mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT count(*) AS n FROM dial_usage_log"
                                + " WHERE \\\"dial_usage_log_payload.assembled_response\\\" ILIKE '%y%'\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("query_budget_exceeded"))
                // Names the projection as one of the things to narrow, which is the actual remedy here.
                .andExpect(jsonPath("$.message").value(containsString("heavy columns")));
    }

    @Test
    void aggregateRefreshIsNotSubjectToTheQueryBudget() throws Exception {
        // The budget must not leak onto the write path. A refresh recomputes every dirty key by design, and
        // a ceiling there would make a legitimate large window fail as though it were malformed. It shares
        // the executor with the query surface but not the boundary the budget is attached at.
        IntegrationTables.upsertSource(mockMvc, ROLLUP, TARGET_COLUMNS, "[\"deployment\",\"day\"]");
        mockMvc.perform(post(PIPELINES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\"," + DECLARATION + "}"))
                .andExpect(status().isCreated());
        mockMvc.perform(patch(PIPELINES + "/" + PIPELINE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(PIPELINES + "/" + PIPELINE + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows_written").value(1));

        // Read back off the budgeted query path (which would refuse under this ceiling) to prove the refresh
        // actually wrote the aggregate, not merely that it reported a row count.
        assertThat(queryScalar("SELECT sum(tokens) AS tokens FROM " + ROLLUP, "tokens")).isEqualTo("20");
    }
}
