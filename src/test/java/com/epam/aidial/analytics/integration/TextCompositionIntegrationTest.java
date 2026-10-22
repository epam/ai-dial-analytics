package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code concat}, {@code to_string}, {@code array_to_string} and {@code array_slice} against a <b>real
 * ClickHouse</b>, on both query surfaces, plus the sort-key shapes this change unblocked.
 *
 * <p><b>Why this class exists rather than more render tests.</b> Every one of these functions is a claim
 * about what the engine prints. A render test asserts the SQL text we emit and cannot see that
 * {@code toString} answers {@code '1'} rather than {@code 'true'} over an {@code Array(UInt8)}, that an
 * {@code if(<null>, 'true', 'false')} answers the non-nullable {@code 'false'}, or that a
 * {@code Decimal(38, 12)} prints {@code 1.5} rather than the twelve decimal places its scale would
 * suggest. The spec states one rendering per type; this is where that table is
 * measured, so a ClickHouse upgrade that changes any of it fails here instead of in a dashboard.
 *
 * <p>The columns are chosen for the same reason: {@code success} is {@code Bool},
 * {@code total_price} is {@code Nullable(Decimal(38, 12))}, {@code total_tokens} is
 * {@code Nullable(UInt32)}, {@code parent_deployment} is {@code LowCardinality(Nullable(String))} and
 * {@code execution_path} is {@code Array(LowCardinality(String))} — the nullable and low-cardinality
 * wrappers being where a rendering that works over a plain {@code String} tends to fail.
 *
 * <p>Requires a running Docker daemon.
 */
class TextCompositionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String EXECUTE = "/v1/queries/execute";
    private static final String EXECUTE_SQL = "/v1/queries/execute-sql";

    /** The one managed table this class creates — {@code dial_usage_log} has no nullable boolean column. */
    private static final String BOOL_PROBE = "bool_probe";

    @AfterEach
    void dropProbeTable() throws Exception {
        // Unconditional, so a failed assertion does not leave the table behind for the next class.
        mockMvc.perform(delete("/v1/tables/" + BOOL_PROBE));
    }

    @BeforeEach
    void cleanSlate() {
        truncate();
        // p1 is fully populated; p2 leaves every nullable column null and its array empty, which is where
        // the null and empty-array halves of each contract are measured.
        execute("INSERT INTO dial_usage_log (request_time, event_kind, project_id, trace_id, deployment,"
                + " parent_deployment, execution_path, total_tokens, total_price, success, event_id) VALUES "
                + "('2026-01-01 00:00:00.000', 'chat', 'p1', 'trace-tc-1', 'gpt-4', 'router',"
                + " ['core','assistant','tool'], 42, 1.5, true,"
                + " '11111111-1111-1111-1111-111111111111'), "
                + "('2026-01-01 00:00:01.000', 'chat', 'p2', 'trace-tc-2', 'gpt-4', NULL,"
                + " [], NULL, NULL, false, '22222222-2222-2222-2222-222222222222')");
    }

    private ResultActions executeSql(String sql) throws Exception {
        final String body = "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return mockMvc.perform(post(EXECUTE_SQL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions executeDsl(String dsl) throws Exception {
        return mockMvc.perform(post(EXECUTE).contentType(MediaType.APPLICATION_JSON).content(dsl));
    }

    // ------------------------------------------------------------------ to_string per type

    @Test
    void toStringRendersEveryScalarFamilyAsTheSpecStates() throws Exception {
        // The spec's rendering table, measured. Each of these is a separate claim about the engine, and
        // the boolean one is the reason to_string does not simply call toString: over a Bool column that
        // would print 1/0.
        executeSql("SELECT TO_STRING(total_tokens) AS tokens, TO_STRING(total_price) AS price,"
                + " TO_STRING(success) AS ok, TO_STRING(request_time) AS at, TO_STRING(event_id) AS id,"
                + " TO_STRING(deployment) AS dep FROM dial_usage_log WHERE project_id = 'p1'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].tokens").value("42"))
                // Measured, and NOT what the first draft of the spec claimed: ClickHouse's toString over a
                // Decimal(38, 12) TRIMS the trailing zeros rather than padding to the column's scale.
                .andExpect(jsonPath("$.rows[0].price").value("1.5"))
                .andExpect(jsonPath("$.rows[0].ok").value("true"))
                .andExpect(jsonPath("$.rows[0].at").value("2026-01-01 00:00:00.000"))
                .andExpect(jsonPath("$.rows[0].id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.rows[0].dep").value("gpt-4"));
    }

    @Test
    void toStringPreservesNullRatherThanNormalizingIt() throws Exception {
        // The cast semantics: an absent value stays absent. concat's own contract is the opposite, and the
        // next test measures the pair together so the difference cannot drift.
        executeSql("SELECT TO_STRING(total_tokens) AS tokens, TO_STRING(parent_deployment) AS parent"
                + " FROM dial_usage_log WHERE project_id = 'p2'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].tokens").value((Object) null))
                .andExpect(jsonPath("$.rows[0].parent").value((Object) null));
    }

    @Test
    void booleanRendersAsTrueOrFalseOnBothSurfaces() throws Exception {
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"fn","name":"to_string",
                            "args":[{"type":"field","name":"success"}]},"as":"ok"}],
                 "sort":[{"field":"ok","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].ok").value("false"))
                .andExpect(jsonPath("$.rows[1].ok").value("true"));
    }

    // ------------------------------------------------------------------ concat

    @Test
    void concatJoinsMixedTypesAndAbsorbsNullOperand() throws Exception {
        // p1 has every operand; p2's parent_deployment is null and contributes the empty string rather
        // than collapsing the label — the whole reason concat guards its operands and to_string does not.
        executeSql("SELECT CONCAT(parent_deployment, ' -> ', deployment) AS edge FROM dial_usage_log"
                + " ORDER BY project_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].edge").value("router -> gpt-4"))
                .andExpect(jsonPath("$.rows[1].edge").value(" -> gpt-4"));
    }

    @Test
    void concatPutsNumberIntoLabel() throws Exception {
        executeSql("SELECT CONCAT('tokens: ', total_tokens) AS label FROM dial_usage_log"
                + " WHERE project_id = 'p1'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].label").value("tokens: 42"));
    }

    @Test
    void theConcatenationOperatorAndCastExecuteAsTheSameFunctions() throws Exception {
        // The two inbound spellings a BI client emits, end to end: || over a mixed pair, and a cast used
        // as a label. Both are the DSL's own functions by the time anything runs.
        executeSql("SELECT 'depth ' || total_tokens AS label, CAST(total_tokens AS VARCHAR) AS t"
                + " FROM dial_usage_log WHERE project_id = 'p1'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].label").value("depth 42"))
                .andExpect(jsonPath("$.rows[0].t").value("42"));
    }

    @Test
    void castOverTextColumnKeepsItsNulls() throws Exception {
        // The identity case, end to end: a cast of a text column is unwrapped to the bare column (the
        // pre-validation rewrite emits a marker and the translator decides once the operand has a type),
        // so the cells — nulls included — are the column's own. Measured, not assumed: this is the
        // spelling a BI client puts on every label over a nullable text column.
        executeSql("SELECT CAST(parent_deployment AS VARCHAR) AS parent FROM dial_usage_log"
                + " ORDER BY project_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].parent").value("router"))
                .andExpect(jsonPath("$.rows[1].parent").value((Object) null));
    }

    @Test
    void renderedLabelIsGroupKey() throws Exception {
        executeSql("SELECT CONCAT(event_kind, '/', deployment) AS label, count(*) AS n"
                + " FROM dial_usage_log GROUP BY label")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].label").value("chat/gpt-4"))
                .andExpect(jsonPath("$.rows[0].n").value(2));
    }

    // ------------------------------------------------------------------ array_to_string

    @Test
    void arrayToStringRendersLowCardinalityArrayAndAnEmptyOne() throws Exception {
        // Array(LowCardinality(String)) is the element type most likely to behave differently from a plain
        // Array(String), and the empty array must render as '' rather than null.
        executeSql("SELECT ARRAY_TO_STRING(execution_path, ' > ') AS path FROM dial_usage_log"
                + " ORDER BY project_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].path").value("core > assistant > tool"))
                .andExpect(jsonPath("$.rows[1].path").value(""));
    }

    @Test
    void arrayToStringRendersNumericGroupsValues() throws Exception {
        // A numeric group's values through the whole path. NOT a test of the per-element toString: 26.3's
        // arrayStringConcat renders a numeric element by itself, so this passes with or without the
        // arrayMap — arrayToStringRendersBooleanElementsAsTheSpecStates is the one that pins it.
        executeSql("SELECT ARRAY_TO_STRING(GROUP_UNIQ_ARRAY(total_tokens), ',') AS tokens"
                + " FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].tokens").value("42"));
    }

    @Test
    void arrayToStringAcceptsAnEmptyDelimiter() throws Exception {
        executeSql("SELECT ARRAY_TO_STRING(execution_path, '') AS path FROM dial_usage_log"
                + " WHERE project_id = 'p1'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].path").value("coreassistanttool"));
    }

    @Test
    void renderedArrayIsGroupKey() throws Exception {
        executeDsl("""
                {"entity":"dial_usage_log","mode":"aggregate",
                 "select":[
                   {"expr":{"type":"fn","name":"array_to_string","args":[
                      {"type":"field","name":"execution_path"},
                      {"type":"value","value_type":"string","value":">"}]},"as":"path"},
                   {"expr":{"type":"fn","name":"count","args":[]},"as":"n"}],
                 "group_by":["path"],
                 "sort":[{"field":"path","dir":"asc"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].path").value(""))
                .andExpect(jsonPath("$.rows[1].path").value("core>assistant>tool"));
    }

    // ------------------------------------------------------------------ array_slice

    @Test
    void arraySliceCapsAnArrayAndToleratesBothEdges() throws Exception {
        // The bounds behaviour the spec states: a slice reaching past the end returns what exists, and an
        // offset past the end returns an empty array instead of raising.
        executeSql("SELECT ARRAY_TO_STRING(ARRAY_SLICE(execution_path, 1, 2), ',') AS head,"
                + " ARRAY_TO_STRING(ARRAY_SLICE(execution_path, 2, 99), ',') AS rest,"
                + " ARRAY_TO_STRING(ARRAY_SLICE(execution_path, 9, 2), ',') AS beyond,"
                + " ARRAY_TO_STRING(ARRAY_SLICE(execution_path, -2, 2), ',') AS tail"
                + " FROM dial_usage_log WHERE project_id = 'p1'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].head").value("core,assistant"))
                .andExpect(jsonPath("$.rows[0].rest").value("assistant,tool"))
                .andExpect(jsonPath("$.rows[0].beyond").value(""))
                .andExpect(jsonPath("$.rows[0].tail").value("assistant,tool"));
    }

    @Test
    void projectedSliceComesBackAsAnArray() throws Exception {
        executeDsl("""
                {"entity":"dial_usage_log","mode":"row",
                 "filter":{"op":"eq","args":[
                   {"type":"field","name":"project_id"},
                   {"type":"value","value_type":"string","value":"p1"}]},
                 "select":[{"expr":{"type":"fn","name":"array_slice","args":[
                      {"type":"field","name":"execution_path"},
                      {"type":"value","value_type":"integer","value":"1"},
                      {"type":"value","value_type":"integer","value":"2"}]},"as":"head"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].head", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].head[0]").value("core"));
    }

    @Test
    void theDeepestTurnsCellExecutesEndToEnd() throws Exception {
        // One of the two dashboard cells this change exists for: a group's distinct values, capped and
        // rendered as one text cell.
        executeSql("SELECT project_id,"
                + " ARRAY_TO_STRING(ARRAY_SLICE(GROUP_UNIQ_ARRAY(deployment), 1, 6), ', ') AS models"
                + " FROM dial_usage_log GROUP BY project_id ORDER BY project_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].models").value("gpt-4"));
    }

    @Test
    void anExpansionOfSliceReturnsScalars() throws Exception {
        executeSql("SELECT ARRAY_JOIN(ARRAY_SLICE(execution_path, 1, 2)) AS step FROM dial_usage_log"
                + " ORDER BY step")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].step").value("assistant"))
                .andExpect(jsonPath("$.rows[1].step").value("core"));
    }

    // ------------------------------------------------- the sort-key shapes this change unblocked

    @Test
    void topRowsByAnAggregateTheProjectionDoesNotCarryExecutes() throws Exception {
        // Refused with a 400 about the ORDER BY expression before this change; the most common BI shape
        // there is, and the one the dashboards' top-N series selection is written as.
        executeSql("SELECT deployment FROM dial_usage_log GROUP BY deployment"
                + " ORDER BY count(*) DESC LIMIT 5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].deployment").value("gpt-4"));
    }

    @Test
    void theTopRowsIdiomInsideAnInSubqueryExecutes() throws Exception {
        executeSql("SELECT project_id, count(*) AS n FROM dial_usage_log WHERE project_id IN"
                + " (SELECT project_id FROM dial_usage_log GROUP BY project_id"
                + " ORDER BY count(*) DESC LIMIT 8) GROUP BY project_id ORDER BY project_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"));
    }

    @Test
    void computedRowModeSortKeyTheProjectionDoesNotCarryExecutes() throws Exception {
        executeSql("SELECT project_id FROM dial_usage_log ORDER BY length(trace_id) DESC, project_id LIMIT 2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].project_id").value("p1"));
    }

    @Test
    void theStatedLimitStillBoundsTheRowsReturned() throws Exception {
        // The bound has to travel out of the sealed block, or governance applies its own default over an
        // unbounded outer block and answers a 1-row question with everything.
        executeSql("SELECT deployment FROM dial_usage_log GROUP BY deployment"
                + " ORDER BY count(*) DESC LIMIT 1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)));
    }

    /**
     * The <b>ordering</b> of a sealed statement's answer, measured over rows a wrong order would separate.
     * The two assertions above cannot see it: one returns a single row and the other's sort expression ties.
     * Row order out of a ClickHouse subquery is not contractual — {@code query_plan_remove_redundant_sorting}
     * is on by default and strips an inner {@code ORDER BY} whose enclosing block has none — so this fails
     * unless the collation is re-expressed on the outer block.
     */
    @Test
    void theSealedOrderingIsInTheAnswerNotOnlyInTheSubquery() throws Exception {
        threeMoreDeployments();
        // Three distinct counts (gpt-35: 3, gpt-4: 2, claude: 1) — a total order, so any other sequence is
        // a real failure rather than a tie broken differently.
        executeSql("SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY count(*) DESC LIMIT 3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[0].deployment").value("gpt-35"))
                .andExpect(jsonPath("$.rows[1].deployment").value("gpt-4"))
                .andExpect(jsonPath("$.rows[2].deployment").value("claude"));
        // The no-LIMIT HAVING form is the sharp case: the sealed block's ORDER BY has no bound to select
        // with, so the outer ORDER BY is the only thing ordering the answer at all.
        executeSql("SELECT deployment FROM dial_usage_log GROUP BY deployment"
                + " HAVING count(*) > 1 ORDER BY count(*) DESC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].deployment").value("gpt-35"))
                .andExpect(jsonPath("$.rows[1].deployment").value("gpt-4"));
        // And the row-mode form, over four distinct trace-id lengths (16, 13, 10, 8).
        executeSql("SELECT trace_id FROM dial_usage_log WHERE deployment <> 'gpt-4'"
                + " ORDER BY length(trace_id) DESC LIMIT 4")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(4)))
                .andExpect(jsonPath("$.rows[0].trace_id").value("trace-tc-longest"))
                .andExpect(jsonPath("$.rows[1].trace_id").value("trace-tc-long"))
                .andExpect(jsonPath("$.rows[2].trace_id").value("trace-tc-x"))
                .andExpect(jsonPath("$.rows[3].trace_id").value("trace-tc"));
    }

    /** Two more deployments at distinct row counts, and three distinct trace-id lengths with them. */
    private void threeMoreDeployments() {
        execute("INSERT INTO dial_usage_log (request_time, event_kind, project_id, trace_id, deployment,"
                + " execution_path, success, event_id) VALUES "
                + "('2026-01-01 00:00:02.000', 'chat', 'p1', 'trace-tc-longest', 'gpt-35', [], true,"
                + " '33333333-3333-3333-3333-333333333333'), "
                + "('2026-01-01 00:00:03.000', 'chat', 'p1', 'trace-tc-long', 'gpt-35', [], true,"
                + " '44444444-4444-4444-4444-444444444444'), "
                + "('2026-01-01 00:00:04.000', 'chat', 'p1', 'trace-tc', 'gpt-35', [], false,"
                + " '55555555-5555-5555-5555-555555555555'), "
                + "('2026-01-01 00:00:05.000', 'chat', 'p1', 'trace-tc-x', 'claude', [], true,"
                + " '66666666-6666-6666-6666-666666666666')");
    }

    // ------------------------------------------------- boolean elements inside array_to_string

    /**
     * A {@code boolean} element renders as {@code true}/{@code false}, exactly as {@code to_string} renders
     * the same scalar — the one element type where a bare {@code toString} would answer differently. Both
     * physical shapes a DSL boolean can sit on are measured: {@code success} is a real {@code Bool} column,
     * while {@code not_empty(…)} is {@code UInt8}, and it is the second one where {@code toString} prints
     * {@code 1}/{@code 0}.
     */
    @Test
    void arrayToStringRendersBooleanElementsAsTheSpecStates() throws Exception {
        // A third row whose parent_deployment is the EMPTY string rather than null, so not_empty answers
        // false for it: a null would be dropped by group_uniq_array and the false half would be unmeasured.
        execute("INSERT INTO dial_usage_log (request_time, event_kind, project_id, trace_id, deployment,"
                + " parent_deployment, execution_path, success, event_id) VALUES "
                + "('2026-01-01 00:00:09.000', 'chat', 'p3', 'trace-tc-3', 'gpt-4', '', [], true,"
                + " '99999999-9999-9999-9999-999999999999')");
        executeSql("SELECT ARRAY_TO_STRING(GROUP_UNIQ_ARRAY(success), ',') AS flags,"
                + " ARRAY_TO_STRING(GROUP_UNIQ_ARRAY(NOT_EMPTY(parent_deployment)), ',') AS has_parent"
                + " FROM dial_usage_log")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                // group_uniq_array sorts ascending, so false comes first in both. `flags` is an Array(Bool)
                // and `has_parent` an Array(UInt8) — the second is the one a bare toString prints as 1,0.
                .andExpect(jsonPath("$.rows[0].flags").value("false,true"))
                .andExpect(jsonPath("$.rows[0].has_parent").value("false,true"));
    }

    // ------------------------------------------------- a NULLABLE boolean, on its own table

    /**
     * The null half of the boolean rendering, which {@code dial_usage_log} cannot measure: {@code success}
     * is a non-nullable {@code Bool}, and every boolean-valued <em>call</em> in the catalog
     * ({@code not_empty}, {@code map_contains}, the array predicates) is null-guarded by construction. So
     * this is the only reachable shape — and it is the shape where the obvious rendering is wrong.
     *
     * <p>An {@code if(flag, 'true', 'false')} answers the <b>non-nullable</b> {@code 'false'} for a NULL
     * condition on 26.3 (the same engine behaviour {@code ExprTranslator.conditional} documents for the
     * {@code if} function), so a cell holding nothing would read {@code false} — {@code to_string}'s null
     * contract broken for one type, and a dashboard label asserting the opposite of the truth. Rendering
     * through {@code toString(toBool(…))} keeps the nullability while still normalizing
     * {@code Bool}/{@code UInt8}, which is what this measures.
     *
     * <p>Reachable at runtime, not only here: a managed table may declare {@code boolean} with
     * {@code nullable}, and an enrichment column defaults to nullable.
     */
    @Test
    void toStringPreservesNullOverNullableBooleanColumn() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + BOOL_PROBE + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/" + BOOL_PROBE + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"source_name\":\"widget_id\",\"name\":\"widget_id\","
                                + "\"type\":\"string\"},{\"source_name\":\"flag\",\"name\":\"flag\","
                                + "\"type\":\"boolean\",\"nullable\":true}],"
                                + "\"ordering_key\":[\"widget_id\"]}"))
                .andExpect(status().isOk());
        // The premise of the test, asserted rather than assumed: without Nullable(Bool) in the physical
        // DDL there is no null for the rendering to preserve or lose.
        assertThat(showCreateTable(BOOL_PROBE)).contains("Nullable(Bool)");
        mockMvc.perform(post("/v1/tables/" + BOOL_PROBE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"widget_id\":\"w1\",\"flag\":true},"
                                + "{\"widget_id\":\"w2\",\"flag\":null}]}"))
                .andExpect(status().isOk());

        executeSql("SELECT TO_STRING(flag) AS rendered, CONCAT(widget_id, ':', flag) AS label"
                + " FROM " + BOOL_PROBE + " ORDER BY widget_id")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].rendered").value("true"))
                .andExpect(jsonPath("$.rows[0].label").value("w1:true"))
                // The same split the pair keeps over a nullable string — and neither half is the 'false'
                // an if()-based rendering would have produced for both.
                .andExpect(jsonPath("$.rows[1].rendered").value((Object) null))
                .andExpect(jsonPath("$.rows[1].label").value("w2:"));

        // And on the DSL surface, where the same rendering is reached without a CAST or a || in sight.
        executeDsl("""
                {"entity":"%s","mode":"row",
                 "select":[{"expr":{"type":"field","name":"widget_id"}},
                           {"expr":{"type":"fn","name":"to_string",
                            "args":[{"type":"field","name":"flag"}]},"as":"rendered"}],
                 "sort":[{"field":"widget_id","dir":"asc"}]}""".formatted(BOOL_PROBE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0].rendered").value("true"))
                .andExpect(jsonPath("$.rows[1].rendered").value((Object) null));
    }
}
