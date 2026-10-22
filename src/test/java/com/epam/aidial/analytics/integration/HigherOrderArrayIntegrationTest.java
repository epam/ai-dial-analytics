package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ClickHouse's higher-order array functions and the two flattening aggregates against a <b>real
 * ClickHouse</b>.
 *
 * <p><b>Why a live test rather than more rendering assertions.</b> Every claim this change makes is
 * about what the engine computes, and a render test can only show what we emit. Two of them are
 * invisible to an offline test by construction:
 *
 * <ul>
 *   <li><b>the point of the change</b> — a role test applied <em>after</em> aggregation leaves the
 *       group's own measures computed over every row, where the row filter it replaces would have
 *       narrowed them. Only real rows show the two answers differ, and by how much;
 *   <li><b>the shape we render is the shape ClickHouse parses</b> — the lambda first, the parameter as
 *       a bare identifier, the marker gone. A quoted parameter or a swapped operand order renders
 *       perfectly and fails at the server.
 * </ul>
 *
 * <p>{@code execution_path} is the array under test, as in {@code ArrayExpansionIntegrationTest}: it is
 * {@code Array(LowCardinality(String))}, the element type most likely to behave differently from a
 * plain {@code Array(String)} under a lambda.
 *
 * <p>Requires a running Docker daemon.
 */
class HigherOrderArrayIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";

    @BeforeEach
    void cleanSlate() {
        truncate();
        // c1 has two rows: one carrying a coder-* step and one carrying none. That split is what makes
        // the row-filtered and post-aggregation readings differ — 5 tokens against 12.
        // c2 never carries one, and c3's array is empty, which is where the empty-array contract shows.
        execute("INSERT INTO dial_usage_log (request_time, event_kind, project_id, chat_id, trace_id,"
                + " deployment, total_tokens, execution_path) VALUES "
                + "('2026-01-01 00:00:00.000', 'chat', 'p1', 'c1', 'trace-ho-1', 'gpt-4', 5,"
                + " ['core','coder-a']), "
                + "('2026-01-01 00:01:00.000', 'chat', 'p1', 'c1', 'trace-ho-2', 'gpt-4', 7, ['core']), "
                + "('2026-01-01 00:02:00.000', 'chat', 'p2', 'c2', 'trace-ho-3', 'gpt-4', 3, ['core']), "
                + "('2026-01-01 00:03:00.000', 'chat', 'p3', 'c3', 'trace-ho-4', 'gpt-4', 2, [])");
    }

    private ResultActions executeSql(String sql) throws Exception {
        final String body = "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions executeDsl(String dsl) throws Exception {
        return mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(dsl));
    }

    @Test
    void prefixTestOverAnArrayFiltersRows() throws Exception {
        executeSql("SELECT trace_id FROM dial_usage_log"
                + " WHERE arrayExists(x -> starts_with(x, 'coder-'), execution_path)")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].trace_id").value("trace-ho-1"));
    }

    @Test
    void theEmptyArrayAnswersWithoutNulls() throws Exception {
        // ClickHouse's own answers for an empty array, which the catalog advertises as the contract:
        // false, true and 0 — not null, and not an error.
        executeSql("SELECT arrayExists(x -> not_empty(x), execution_path) AS any_step,"
                + " arrayAll(x -> not_empty(x), execution_path) AS all_steps,"
                + " arrayCount(x -> not_empty(x), execution_path) AS steps"
                + " FROM dial_usage_log WHERE trace_id = 'trace-ho-4'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].any_step").value(false))
                .andExpect(jsonPath("$.rows[0].all_steps").value(true))
                .andExpect(jsonPath("$.rows[0].steps").value(0));
    }

    @Test
    void lambdaBodyReadsTheEnclosingRowColumn() throws Exception {
        // The capture ClickHouse allows and Calcite's own lambda scope does not: the body compares each
        // element against this row's deployment. Nothing matches, so the count is what proves the
        // statement ran rather than failed — and the row with the empty array answers 0 too.
        executeSql("SELECT trace_id, arrayCount(x -> equals(x, deployment), execution_path) AS matches"
                + " FROM dial_usage_log ORDER BY trace_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(4)))
                .andExpect(jsonPath("$.rows[0].matches").value(0));
    }

    @Test
    void arrayReturningCallsProjectAsArrays() throws Exception {
        executeSql("SELECT arrayFilter(x -> starts_with(x, 'coder-'), execution_path) AS coder_steps,"
                + " array_to_string(arrayMap(x -> upper(x), execution_path), '>') AS path"
                + " FROM dial_usage_log WHERE trace_id = 'trace-ho-1'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].coder_steps", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].coder_steps[0]").value("coder-a"))
                .andExpect(jsonPath("$.rows[0].path").value("CORE>CODER-A"));
    }

    @Test
    void regexMatchesInsideTheLambda() throws Exception {
        executeSql("SELECT trace_id FROM dial_usage_log"
                + " WHERE arrayExists(x -> match(x, '^coder-(a|b)$'), execution_path)")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].trace_id").value("trace-ho-1"));
    }

    @Test
    void theFlatteningAggregateUnionsTheGroupsArrays() throws Exception {
        executeSql("SELECT chat_id, groupUniqArrayArray(execution_path) AS steps FROM dial_usage_log"
                + " WHERE chat_id = 'c1' GROUP BY chat_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                // Both rows' elements, deduplicated: 'core' appears in each row and once in the result.
                .andExpect(jsonPath("$.rows[0].steps", hasSize(2)));
    }

    @Test
    void thePostAggregationRoleTestKeepsTheWholeGroupsMeasure() throws Exception {
        // THE point of the change, and the one thing no offline test can show. c1 holds a coder-* step
        // on one of its two rows: filtering rows first counts 5 tokens, testing the union after
        // aggregation counts 12 — the whole group, which is the question that had no spelling before.
        executeSql("SELECT chat_id, sum(total_tokens) AS tokens FROM dial_usage_log GROUP BY chat_id"
                + " HAVING arrayExists(x -> starts_with(x, 'coder-'),"
                + " groupUniqArrayArray(execution_path))")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].chat_id").value("c1"))
                .andExpect(jsonPath("$.rows[0].tokens").value(12));

        // The row filter it replaces, for contrast: the same group, 5 tokens.
        executeSql("SELECT chat_id, sum(total_tokens) AS tokens FROM dial_usage_log"
                + " WHERE arrayExists(x -> starts_with(x, 'coder-'), execution_path) GROUP BY chat_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].tokens").value(5));
    }

    @Test
    void argMaxCarriesTheLatestArrayValue() throws Exception {
        // The current-state reading: c1's latest row carries ['core'] alone, not the earlier coder step.
        executeSql("SELECT chat_id, arg_max(execution_path, request_time) AS last_path"
                + " FROM dial_usage_log WHERE chat_id = 'c1' GROUP BY chat_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].last_path", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].last_path[0]").value("core"));
    }

    @Test
    void theStructuredSurfaceExecutesTheSameQuery() throws Exception {
        // The DSL document the SQL above translates to, posted directly: both surfaces reach the same
        // ClickHouse statement, which is what the round-trip tests assert offline and this confirms.
        executeDsl("""
                {"entity":"dial_usage_log","mode":"aggregate",
                 "select":[
                   {"expr":{"type":"field","name":"chat_id"}},
                   {"expr":{"type":"fn","name":"sum",
                            "args":[{"type":"field","name":"total_tokens"}]},"as":"tokens"}],
                 "group_by":["chat_id"],
                 "having":{"op":"eq","args":[
                   {"type":"fn","name":"arrayExists","args":[
                     {"type":"lambda","params":["x"],
                      "body":{"type":"fn","name":"starts_with","args":[
                        {"type":"lambda_param","name":"x"},
                        {"type":"value","value_type":"string","value":"coder-"}]}},
                     {"type":"fn","name":"groupUniqArrayArray",
                      "args":[{"type":"field","name":"execution_path"}]}]},
                   {"type":"value","value_type":"boolean","value":"true"}]}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].chat_id").value("c1"))
                .andExpect(jsonPath("$.rows[0].tokens").value(12));
    }
}
