package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code array_join} against a <b>real ClickHouse</b>, on both query surfaces.
 *
 * <p><b>Why this class exists rather than more render tests.</b> A render test asserts the SQL text we
 * emit; it cannot see what the engine does with it. The preceding change measured a rendering that
 * returned an empty result for <em>every row</em> and raised nothing, and this function's whole contract
 * is about row counts — how many rows come back, and which rows disappear. A unit test that asserted
 * {@code arrayJoin("execution_path")} was emitted would have passed for a rendering that silently
 * returned nothing.
 *
 * <p>{@code execution_path} is deliberately the column under test: it is
 * {@code Array(LowCardinality(String))}, the element type most likely to behave differently from a plain
 * {@code Array(String)} under expansion. Its row with an <b>empty</b> array is asserted separately from
 * the populated one, because "the empty row vanishes" is the contract rather than an accident.
 *
 * <p>The two multi-expansion cases are here for a different reason: they assert behaviour this service
 * <em>inherits</em> rather than implements. The spec states that two different expansions multiply and
 * two identical ones do not; nothing in our code makes that true, so only a live query can keep the
 * statement honest across an engine upgrade.
 *
 * <p>Requires a running Docker daemon.
 */
class ArrayExpansionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";

    @BeforeEach
    void cleanSlate() {
        truncate();
        // p1 carries two steps, p2 none. deployment doubles as a second expandable expression via
        // split_string, so the cartesian case needs no second array column.
        execute("INSERT INTO dial_usage_log (request_time, event_kind, project_id, trace_id, deployment,"
                + " execution_path) VALUES "
                + "('2026-01-01 00:00:00.000', 'chat', 'p1', 'trace-aj-1', 'a,b', ['core','assistant']), "
                + "('2026-01-01 00:00:00.000', 'chat', 'p2', 'trace-aj-2', 'a,b', [])");
    }

    private ResultActions executeSql(String sql) throws Exception {
        final String body = "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions executeDsl(String dsl) throws Exception {
        return mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(dsl));
    }

    @Test
    void expandsAnArrayColumnIntoOneRowPerElement() throws Exception {
        // Two rows in, two rows out — both from p1. p2's empty array contributes nothing, which is the
        // whole of the empty-array contract and the half a "does it run" assertion would miss.
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[
                   {"expr":{"type":"field","name":"project_id"}},
                   {"expr":{"type":"fn","name":"array_join",
                            "args":[{"type":"field","name":"execution_path"}]},"as":"step"}],
                 "sort":[{"field":"step","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"))
                .andExpect(jsonPath("$.rows[0].step").value("assistant"))
                .andExpect(jsonPath("$.rows[1].step").value("core"));
    }

    @Test
    void expandedElementIsScalarNotArray() throws Exception {
        // The element of an Array(LowCardinality(String)) must come back as a bare JSON string. If the
        // expansion ever regressed to projecting the array, this would see ["core","assistant"] here.
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"fn","name":"array_join",
                            "args":[{"type":"field","name":"execution_path"}]},"as":"step"}],
                 "sort":[{"field":"step","dir":"asc"}],
                 "page":{"type":"offset","limit":1}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].step").value("assistant"));
    }

    @Test
    void groupsByTheExpandedElement() throws Exception {
        // The motivating query: a distribution over the individual elements of an open-vocabulary array,
        // which had no expression at all before this change.
        executeDsl("""
                {"entity":"dial_usage_log","mode":"aggregate",
                 "select":[
                   {"expr":{"type":"fn","name":"array_join",
                            "args":[{"type":"field","name":"execution_path"}]},"as":"step"},
                   {"expr":{"type":"fn","name":"count","args":[]},"as":"n"}],
                 "group_by":["step"],
                 "sort":[{"field":"step","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].step").value("assistant"))
                .andExpect(jsonPath("$.rows[0].n").value(1))
                .andExpect(jsonPath("$.rows[1].step").value("core"))
                .andExpect(jsonPath("$.rows[1].n").value(1));
    }

    @Test
    void expandsComputedArray() throws Exception {
        // split_string yields an array with no declared element type; the expansion must still produce
        // scalars. Both source rows carry 'a,b', so two rows expand to four.
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"fn","name":"array_join","args":[
                              {"type":"fn","name":"split_string","args":[
                                 {"type":"field","name":"deployment"},
                                 {"type":"value","value_type":"string","value":","}]}]},"as":"part"}],
                 "sort":[{"field":"part","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(4)))
                .andExpect(jsonPath("$.rows[0].part").value("a"))
                .andExpect(jsonPath("$.rows[3].part").value("b"));
    }

    @Test
    void twoDifferentExpansionsMultiply() throws Exception {
        // Inherited engine behaviour, asserted so the spec's claim is measured rather than recited.
        // p1: 2 steps x 2 parts = 4 rows. p2 has no steps, so it contributes none.
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[
                   {"expr":{"type":"fn","name":"array_join",
                            "args":[{"type":"field","name":"execution_path"}]},"as":"step"},
                   {"expr":{"type":"fn","name":"array_join","args":[
                              {"type":"fn","name":"split_string","args":[
                                 {"type":"field","name":"deployment"},
                                 {"type":"value","value_type":"string","value":","}]}]},"as":"part"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(4)));
    }

    @Test
    void twoIdenticalExpansionsDoNotMultiply() throws Exception {
        // The other half of the inherited rule: the same expression twice is one expansion, and the two
        // aliases advance together rather than forming a 2x2 product.
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[
                   {"expr":{"type":"fn","name":"array_join",
                            "args":[{"type":"field","name":"execution_path"}]},"as":"a"},
                   {"expr":{"type":"fn","name":"array_join",
                            "args":[{"type":"field","name":"execution_path"}]},"as":"b"}],
                 "sort":[{"field":"a","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].a").value("assistant"))
                .andExpect(jsonPath("$.rows[0].b").value("assistant"));
    }

    @Test
    void includeTotalIsRefusedAlongsideAnExpansion() throws Exception {
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"fn","name":"array_join",
                            "args":[{"type":"field","name":"execution_path"}]},"as":"step"}],
                 "page":{"type":"offset","limit":10,"include_total":true}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message", containsString("include_total")))
                .andExpect(jsonPath("$.message", containsString("array_join")));
    }

    @Test
    void sqlSurfaceExpandsIdenticallyToTheDsl() throws Exception {
        executeSql("SELECT project_id, ARRAY_JOIN(execution_path) AS step FROM dial_usage_log ORDER BY step ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].step").value("assistant"))
                .andExpect(jsonPath("$.rows[1].step").value("core"));
    }

    @Test
    void sqlSurfaceGroupsByTheExpandedElement() throws Exception {
        executeSql("SELECT ARRAY_JOIN(execution_path) AS step, count(*) AS n FROM dial_usage_log"
                + " GROUP BY step ORDER BY step ASC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].step").value("assistant"))
                .andExpect(jsonPath("$.rows[0].n").value(1));
    }
}
