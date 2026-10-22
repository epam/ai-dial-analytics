package com.epam.aidial.analytics.service.query.model;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips representative wire payloads through the production {@link JsonMapper}. This is the real
 * proof that record binding, the {@code type} discriminator on {@link Expr}, the
 * {@link FilterNodeDeserializer} routing, and the snake_case / {@code @JsonValue} wiring all work
 * together — a clean compile does not prove Jackson binds these records.
 */
class StructuredQueryDeserializationTest {

    private final JsonMapper mapper = new JsonMapperConfiguration().jsonMapper();

    @Test
    void deserializesNestedBooleanFilterTree() {
        // §3 shape: (event_kind = 'chat' AND total_tokens > 100) OR (event_kind = 'embedding' AND total_tokens > 50)
        String json = """
                {
                  "entity": "dial_usage_log",
                  "filter": {
                    "op": "or",
                    "args": [
                      { "op": "and", "args": [
                          { "op": "eq", "args": [ { "type": "field", "name": "event_kind" },     { "type": "value", "value_type": "string",  "value": "chat" } ] },
                          { "op": "gt", "args": [ { "type": "field", "name": "total_tokens" }, { "type": "value", "value_type": "long",    "value": "100" } ] }
                      ]},
                      { "op": "and", "args": [
                          { "op": "eq", "args": [ { "type": "field", "name": "event_kind" },     { "type": "value", "value_type": "string",  "value": "embedding" } ] },
                          { "op": "gt", "args": [ { "type": "field", "name": "total_tokens" }, { "type": "value", "value_type": "long",    "value": "50" } ] }
                      ]}
                    ]
                  }
                }
                """;

        FilterNode node = mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node).isInstanceOf(LogicalNode.class);
        LogicalNode root = (LogicalNode) node;
        assertThat(root.op()).isEqualTo(LogicalOp.OR);
        assertThat(root.args()).hasSize(2).allSatisfy(child -> assertThat(child).isInstanceOf(LogicalNode.class));

        LogicalNode left = (LogicalNode) root.args().getFirst();
        assertThat(left.op()).isEqualTo(LogicalOp.AND);

        ComparisonNode statusEq = (ComparisonNode) left.args().getFirst();
        assertThat(statusEq.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(statusEq.args())
                .containsExactly(new FieldExpr("event_kind"), new ValueExpr(ValueType.STRING, "chat"));

        ComparisonNode tokensGt = (ComparisonNode) left.args().get(1);
        assertThat(tokensGt.op()).isEqualTo(ComparisonOp.GT);
        assertThat(tokensGt.args())
                .containsExactly(new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, "100"));
    }

    @Test
    void deserializesComparisonWithFunctionOnLeftSide() {
        // length(project_id) = 3 — neither side is a bare column; LHS is fn(field), RHS is a value.
        String json = """
                {
                  "entity": "dial_usage_log",
                  "filter": {
                    "op": "eq",
                    "args": [
                      { "type": "fn", "name": "length", "args": [ { "type": "field", "name": "project_id" } ] },
                      { "type": "value", "value_type": "integer", "value": "3" }
                    ]
                  }
                }
                """;

        ComparisonNode node =
                (ComparisonNode) mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(node.args())
                .containsExactly(
                        new FnExpr("length", false, List.of(new FieldExpr("project_id"))),
                        new ValueExpr(ValueType.INTEGER, "3"));
    }

    @Test
    void deserializesNullCheckPredicate() {
        // §4.2 / §8.3: chat_id IS NULL.
        String json = """
                {
                  "entity": "dial_usage_log",
                  "filter": { "op": "eq", "args": [ { "type": "field", "name": "chat_id" }, { "type": "value", "value_type": "null", "value": null } ] }
                }
                """;

        ComparisonNode node =
                (ComparisonNode) mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(node.args()).containsExactly(new FieldExpr("chat_id"), new ValueExpr(ValueType.NULL, null));
    }

    @Test
    void deserializesInPredicateWithArrayOperand() {
        // §8.5: event_kind IN ('chat', 'embedding') — in is an ordinary binary predicate whose right
        // operand is an `array` expression (§4.6).
        String json = """
                {
                  "entity": "dial_usage_log",
                  "filter": {
                    "op": "in",
                    "args": [
                      { "type": "field", "name": "event_kind" },
                      { "type": "array", "items": [
                          { "type": "value", "value_type": "string", "value": "chat" },
                          { "type": "value", "value_type": "string", "value": "embedding" }
                      ]}
                    ]
                  }
                }
                """;

        ComparisonNode node =
                (ComparisonNode) mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node.op()).isEqualTo(ComparisonOp.IN);
        assertThat(node.args())
                .containsExactly(
                        new FieldExpr("event_kind"),
                        new ArrayExpr(List.of(
                                new ValueExpr(ValueType.STRING, "chat"),
                                new ValueExpr(ValueType.STRING, "embedding"))));
    }

    @Test
    void deserializesNestedFunctionExpression() {
        // §4.1: round(div(sum(total_tokens), count()), 2)
        String json = """
                { "type": "fn", "name": "round", "args": [
                    { "type": "fn", "name": "div", "args": [
                        { "type": "fn", "name": "sum",   "args": [ { "type": "field", "name": "total_tokens" } ] },
                        { "type": "fn", "name": "count", "args": [] }
                    ]},
                    { "type": "value", "value_type": "integer", "value": "2" }
                ]}
                """;

        FnExpr round = (FnExpr) mapper.readValue(json, Expr.class);
        assertThat(round.name()).isEqualTo("round");
        assertThat(round.args()).hasSize(2);

        FnExpr div = (FnExpr) round.args().getFirst();
        assertThat(div.name()).isEqualTo("div");
        FnExpr sum = (FnExpr) div.args().getFirst();
        assertThat(sum.name()).isEqualTo("sum");
        assertThat(sum.args()).containsExactly(new FieldExpr("total_tokens"));
        FnExpr count = (FnExpr) div.args().get(1);
        assertThat(count.args()).isEmpty();

        assertThat(round.args().get(1)).isEqualTo(new ValueExpr(ValueType.INTEGER, "2"));
    }

    @Test
    void deserializesFullRowModeEnvelope() {
        // §8.1 row-mode query with offset page + include_total.
        String json = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": {
                    "op": "lt",
                    "args": [ { "type": "field", "name": "total_tokens" }, { "type": "value", "value_type": "long", "value": "500" } ]
                  },
                  "select": [
                    { "expr": { "type": "field", "name": "project_id" } },
                    { "expr": { "type": "field", "name": "total_tokens" } }
                  ],
                  "sort": [ { "field": "total_tokens", "dir": "asc", "nulls": "last" }, { "field": "project_id", "dir": "asc" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 25, "include_total": true }
                }
                """;

        StructuredQuery query = mapper.readValue(json, StructuredQuery.class);

        assertThat(query.entity()).isEqualTo("dial_usage_log");
        assertThat(query.mode()).isEqualTo(QueryMode.ROW);
        assertThat(query.filter()).isInstanceOf(ComparisonNode.class);
        assertThat(query.select())
                .containsExactly(
                        new OutputColumn(new FieldExpr("project_id"), null),
                        new OutputColumn(new FieldExpr("total_tokens"), null));
        assertThat(query.sort())
                .containsExactly(
                        new SortItem("total_tokens", SortDir.ASC, NullsOrder.LAST),
                        new SortItem("project_id", SortDir.ASC, null));
        assertThat(query.page()).isEqualTo(new OffsetPage(0L, 25, true));
    }

    @Test
    void deserializesFullAggregateModeEnvelope() {
        // §8.2 aggregate-mode query with group_by, fn expressions in select (alias `as`), having, cursor page.
        String json = """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "group_by": ["event_kind"],
                  "select": [
                    { "expr": { "type": "field", "name": "event_kind" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "total_requests" },
                    { "expr": { "type": "fn", "name": "avg", "args": [ { "type": "field", "name": "total_tokens" } ] }, "as": "avg_tokens" }
                  ],
                  "having": { "op": "ge", "args": [ { "type": "field", "name": "avg_tokens" }, { "type": "value", "value_type": "decimal", "value": "100" } ] },
                  "sort": [ { "field": "avg_tokens", "dir": "desc" } ],
                  "page": { "type": "cursor", "limit": 100 }
                }
                """;

        StructuredQuery query = mapper.readValue(json, StructuredQuery.class);

        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.groupBy()).containsExactly("event_kind");
        assertThat(query.select())
                .containsExactly(
                        new OutputColumn(new FieldExpr("event_kind"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "total_requests"),
                        new OutputColumn(
                                new FnExpr("avg", false, List.of(new FieldExpr("total_tokens"))), "avg_tokens"));
        assertThat(query.having()).isInstanceOf(ComparisonNode.class);
        assertThat(((ComparisonNode) query.having()).args().getFirst()).isEqualTo(new FieldExpr("avg_tokens"));
        assertThat(query.page()).isEqualTo(new CursorPage(null, 100));
    }

    @Test
    void absentPrimitiveFlagsDefaultRatherThanFail() {
        // FAIL_ON_NULL_FOR_PRIMITIVES is left strict globally; the optional primitives below opt out
        // via @JsonSetter(nulls = AS_EMPTY), so omitting them must default instead of throwing.
        String json = """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "select": [
                    { "expr": { "type": "fn", "name": "count", "args": [] } }
                  ],
                  "page": { "type": "offset" }
                }
                """;

        StructuredQuery query = mapper.readValue(json, StructuredQuery.class);

        assertThat(query.distinct()).isFalse();
        assertThat(((FnExpr) query.select().getFirst().expr()).distinct()).isFalse();
        assertThat(query.page()).isEqualTo(new OffsetPage(0L, 0, false));
    }

    @Test
    void absentCursorLimitDefaultsToZero() {
        String json = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "page": { "type": "cursor" }
                }
                """;

        StructuredQuery query = mapper.readValue(json, StructuredQuery.class);

        assertThat(query.page()).isEqualTo(new CursorPage(null, 0));
    }

    @Test
    void roundTripsEnvelopeThroughSerialization() {
        // deserialize -> serialize -> deserialize must be stable (discriminators + snake_case keys).
        String json = """
                {
                  "entity": "dial_usage_log",
                  "mode": "row",
                  "filter": { "op": "in", "args": [
                      { "type": "field", "name": "event_kind" },
                      { "type": "array", "items": [
                          { "type": "value", "value_type": "string", "value": "chat" },
                          { "type": "value", "value_type": "string", "value": "embedding" }
                      ]}
                  ]},
                  "page": { "type": "offset", "offset": 200, "limit": 100, "include_total": false }
                }
                """;

        StructuredQuery first = mapper.readValue(json, StructuredQuery.class);
        String reserialized = mapper.writeValueAsString(first);
        StructuredQuery second = mapper.readValue(reserialized, StructuredQuery.class);

        assertThat(second).isEqualTo(first);
        assertThat(reserialized)
                .contains("\"op\":\"in\"")
                .contains("\"type\":\"field\"")
                .contains("\"name\":\"event_kind\"")
                .contains("\"type\":\"array\"")
                .contains("\"items\":[");
    }

    @Test
    void roundTripsFullAggregateEnvelopeThroughSerialization() {
        // The /v1/queries/translate-sql response returns a StructuredQuery as JSON, so serialization
        // must produce exactly the wire shape /v1/queries/execute deserializes — across the whole
        // grammar: nested filters, all four contains operators, fn/alias select entries, sort, page.
        String json = """
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "filter": {
                    "op": "and",
                    "args": [
                      { "op": "co",  "args": [ { "type": "field", "name": "deployment" }, { "type": "value", "value_type": "string", "value": "GPT" } ] },
                      { "op": "ico", "args": [ { "type": "field", "name": "project_id" }, { "type": "value", "value_type": "string", "value": "proj" } ] },
                      { "op": "not", "args": [
                          { "op": "inc", "args": [ { "type": "field", "name": "event_kind" }, { "type": "value", "value_type": "string", "value": "embed" } ] }
                      ]},
                      { "op": "ge", "args": [ { "type": "field", "name": "request_time" }, { "type": "value", "value_type": "timestamp", "value": "1751328000000" } ] }
                    ]
                  },
                  "group_by": ["deployment"],
                  "select": [
                    { "expr": { "type": "field", "name": "deployment" }, "as": "deployment" },
                    { "expr": { "type": "fn", "name": "count", "distinct": true, "args": [ { "type": "field", "name": "project_id" } ] }, "as": "projects" }
                  ],
                  "having": { "op": "gt", "args": [ { "type": "fn", "name": "count", "args": [] }, { "type": "value", "value_type": "integer", "value": "10" } ] },
                  "sort": [ { "field": "projects", "dir": "desc", "nulls": "last" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 50, "include_total": false }
                }
                """;

        StructuredQuery first = mapper.readValue(json, StructuredQuery.class);
        String reserialized = mapper.writeValueAsString(first);
        StructuredQuery second = mapper.readValue(reserialized, StructuredQuery.class);

        assertThat(second).isEqualTo(first);
        assertThat(reserialized)
                .contains("\"op\":\"co\"")
                .contains("\"op\":\"ico\"")
                .contains("\"op\":\"inc\"")
                .contains("\"group_by\":[\"deployment\"]")
                .contains("\"value_type\":\"timestamp\"")
                .contains("\"as\":\"projects\"")
                .contains("\"nulls\":\"last\"")
                .contains("\"type\":\"offset\"");
    }

    @Test
    void deserializesCaseExpression() {
        String json = """
                { "entity": "dial_usage_log", "mode": "row",
                  "select": [ { "expr": { "type": "case",
                      "when": [ { "when": { "op": "gt", "args": [ { "type": "field", "name": "total_tokens" },
                                                                   { "type": "value", "value_type": "long", "value": "1000" } ] },
                                  "then": { "type": "value", "value_type": "string", "value": "large" } } ],
                      "else": { "type": "value", "value_type": "string", "value": "small" } }, "as": "size" } ] }
                """;

        Expr expr = mapper.readValue(json, StructuredQuery.class).select().getFirst().expr();

        assertThat(expr).isInstanceOf(CaseExpr.class);
        CaseExpr caseExpr = (CaseExpr) expr;
        assertThat(caseExpr.when()).singleElement().satisfies(branch -> {
            assertThat(branch.when()).isEqualTo(new ComparisonNode(ComparisonOp.GT,
                    List.of(new FieldExpr("total_tokens"), new ValueExpr(ValueType.LONG, "1000"))));
            assertThat(branch.then()).isEqualTo(new ValueExpr(ValueType.STRING, "large"));
        });
        assertThat(caseExpr.otherwise()).isEqualTo(new ValueExpr(ValueType.STRING, "small"));
    }

    @Test
    void deserializesWindowExpression() {
        String json = """
                { "entity": "dial_usage_log", "mode": "row",
                  "select": [ { "expr": { "type": "window",
                      "fn": { "type": "fn", "name": "row_number", "args": [] },
                      "partition_by": [ { "type": "field", "name": "project_id" } ],
                      "order_by": [ { "expr": { "type": "field", "name": "request_time" }, "dir": "desc" } ] }, "as": "rn" } ] }
                """;

        Expr expr = mapper.readValue(json, StructuredQuery.class).select().getFirst().expr();

        assertThat(expr).isEqualTo(new WindowExpr(new FnExpr("row_number", false, List.of()),
                List.of(new FieldExpr("project_id")),
                List.of(new WindowOrder(new FieldExpr("request_time"), SortDir.DESC, null)), null));
    }

    @Test
    void deserializesWindowFrame() {
        String json = """
                { "type": "window", "fn": { "type": "fn", "name": "sum", "args": [ { "type": "field", "name": "total_tokens" } ] },
                  "frame": { "unit": "rows", "start": { "kind": "preceding", "offset": 2 }, "end": { "kind": "current_row" } } }
                """;

        Expr expr = mapper.readValue(json, Expr.class);

        assertThat(((WindowExpr) expr).frame()).isEqualTo(new WindowFrame(FrameUnit.ROWS,
                new FrameBound(FrameBoundKind.PRECEDING, 2L), new FrameBound(FrameBoundKind.CURRENT_ROW, null)));
    }

    @Test
    void unknownExpressionTypeIsRejected() {
        // The SQL surface's internal scalar-subquery node has no wire spelling: neither a guessed type id nor the
        // record's own class name deserializes.
        assertThatThrownBy(() -> mapper.readValue("{ \"type\": \"subquery\" }", Expr.class))
                .isInstanceOf(tools.jackson.databind.DatabindException.class);
        assertThatThrownBy(() -> mapper.readValue("{ \"type\": \"ScalarSubqueryExpr\", \"subquery\": null }", Expr.class))
                .isInstanceOf(tools.jackson.databind.DatabindException.class);
    }

    @Test
    void roundTripsCaseAndWindowThroughSerialization() {
        CaseExpr caseExpr = new CaseExpr(List.of(new CaseBranch(
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("success"), new ValueExpr(ValueType.BOOLEAN, "true"))),
                new ValueExpr(ValueType.INTEGER, "1"))), null);
        WindowExpr window = new WindowExpr(new FnExpr("sum", false, List.of(new FieldExpr("total_tokens"))), List.of(),
                List.of(new WindowOrder(new FieldExpr("request_time"), SortDir.ASC, NullsOrder.LAST)),
                new WindowFrame(FrameUnit.RANGE, new FrameBound(FrameBoundKind.UNBOUNDED_PRECEDING, null),
                        new FrameBound(FrameBoundKind.FOLLOWING, 1L)));
        StructuredQuery query = new StructuredQuery("dial_usage_log", null, QueryMode.ROW, false,
                List.of(new OutputColumn(caseExpr, "flag"), new OutputColumn(window, "running")), null, null, null, null);

        String json = mapper.writeValueAsString(query);

        assertThat(json).contains("\"type\":\"case\"").doesNotContain("\"else\"").contains("\"type\":\"window\"")
                .contains("\"partition_by\"").contains("\"unbounded_preceding\"");
        assertThat(mapper.readValue(json, StructuredQuery.class)).isEqualTo(query);
    }

    @Test
    void finalBindsThreeWaysAndAnAbsentFinalStaysAbsent() {
        final String base = "{\"entity\":\"dial_usage_log\",\"mode\":\"row\"%s}";
        final StructuredQuery yes = mapper.readValue(base.formatted(",\"final\":true"), StructuredQuery.class);
        final StructuredQuery no = mapper.readValue(base.formatted(",\"final\":false"), StructuredQuery.class);
        final StructuredQuery absent = mapper.readValue(base.formatted(""), StructuredQuery.class);

        assertThat(yes.finalRead()).isTrue();
        assertThat(no.finalRead()).isFalse();
        assertThat(absent.finalRead()).isNull();
        assertThat(mapper.writeValueAsString(yes)).contains("\"final\":true");
        assertThat(mapper.writeValueAsString(no)).contains("\"final\":false");
        // Absent and false read an upsert_by_key source differently, so absent must not come back as false.
        assertThat(mapper.writeValueAsString(absent)).doesNotContain("\"final\"");
        assertThat(mapper.readValue(mapper.writeValueAsString(absent), StructuredQuery.class)).isEqualTo(absent);
    }
}
