package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ClickHouse SQL the DSL's text-composition and array-text functions render to, and the operand rules
 * the translator enforces on them — the structured-query delta's "Text composition and value rendering" and
 * "Array text rendering and slicing" requirements, offline over {@link DialUsageLogTestModel}.
 */
class TextCompositionRenderTest {

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    private final StructuredQueryBuilder builder = newBuilder();

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    private String render(StructuredQuery query) {
        return builder.build(ctx, model, query).getSQL(ParamType.INLINED);
    }

    private static StructuredQuery rowQuery(Expr expr, String alias) {
        return new StructuredQuery(DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(expr, alias)), null, null, null, null);
    }

    private static FnExpr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    private static ValueExpr text(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static ValueExpr integer(int value) {
        return new ValueExpr(ValueType.INTEGER, String.valueOf(value));
    }

    // ------------------------------------------------------------------ to_string

    @Test
    void toStringRendersAnyScalarAsTextAndPreservesNull() {
        // One rendering for every scalar family, and NO null guard: to_string is the CAST this surface
        // stands in for, and a cast of nothing is nothing. The never-null contract belongs to the two
        // composing functions, which guard their operands themselves.
        assertThat(render(rowQuery(fn("to_string", new FieldExpr("total_tokens")), "t")))
                .contains("toString(\"dial_usage_log\".\"total_tokens\")")
                .doesNotContain("ifNull(toString");
        assertThat(render(rowQuery(fn("to_string", new FieldExpr("total_price")), "t")))
                .contains("toString(\"dial_usage_log\".\"total_price\")");
        assertThat(render(rowQuery(fn("to_string", new FieldExpr("request_time")), "t")))
                .contains("toString(\"dial_usage_log\".\"request_time\")");
        assertThat(render(rowQuery(fn("to_string", new FieldExpr("event_id")), "t")))
                .contains("toString(\"dial_usage_log\".\"event_id\")");
        assertThat(render(rowQuery(fn("to_string", new FieldExpr("deployment")), "t")))
                .contains("toString(\"dial_usage_log\".\"deployment\")");
    }

    @Test
    void toStringRendersBooleanThroughToBoolRatherThanBare() {
        // A DSL boolean may sit on Bool or UInt8, where a bare toString answers 'true' or '1' for the same
        // advertised type. The toBool normalizes the physical type while keeping nullability, so the
        // rendering is independent of the physical type AND still answers NULL for a null operand — which
        // an if(col, 'true', 'false') would not, ClickHouse taking the else branch for a NULL condition.
        assertThat(render(rowQuery(fn("to_string", new FieldExpr("success")), "t")))
                .contains("toString(toBool(\"dial_usage_log\".\"success\"))")
                .doesNotContain("toString(\"dial_usage_log\".\"success\")")
                .doesNotContain("'true', 'false'");
    }

    @Test
    void toStringRejectsNonScalarOperandAndNullLiteral() {
        assertThatThrownBy(() -> render(rowQuery(fn("to_string", new FieldExpr("execution_path")), "t")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("array");
        assertThatThrownBy(() -> render(rowQuery(fn("to_string", new FieldExpr(DialUsageLogTestModel.payload("jwt_claims"))), "t")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object");
        assertThatThrownBy(() -> render(rowQuery(fn("to_string", new ValueExpr(ValueType.NULL, null)), "t")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("null literal");
    }

    /**
     * A null <b>literal</b> is refused by {@code concat} too, which is not in tension with its
     * never-null contract: that contract is about an operand whose <em>value</em> is null (a nullable
     * column), which contributes the empty string. A literal has no type, this surface has no null in
     * expression position — {@code requireComparableType} says so for {@code equals}, and the numeric and
     * text checks say it for theirs — and {@code concat} is not the one function that takes one anyway.
     */
    @Test
    void concatRejectsNullLiteralWhileAbsorbingNullValue() {
        assertThatThrownBy(() -> render(rowQuery(
                fn("concat", new FieldExpr("deployment"), new ValueExpr(ValueType.NULL, null)), "t")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("null literal");
        // The nullable column, for contrast: accepted, and null-guarded so the label survives.
        assertThat(render(rowQuery(
                fn("concat", new FieldExpr("deployment"), new FieldExpr("parent_deployment")), "t")))
                .contains("ifNull(toString(\"dial_usage_log\".\"parent_deployment\"), '')");
    }

    @Test
    void toStringRejectsComputedArray() {
        assertThatThrownBy(() -> render(rowQuery(
                fn("to_string", fn("split_string", new FieldExpr("deployment"), text(","))), "t")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("returns an array");
    }

    @Test
    void toStringTakesAnEnumOperandTheInspectingTextFunctionsRefuse() {
        // Rendering an enum is defined where reading it as text is not, so this is also the route to the
        // inspecting functions over an enum domain: starts_with(to_string(status), 'run').
        final Map<String, QueryFieldBinding> bindings = Map.of("status", new QueryFieldBinding(
                "status", DSL.field(DSL.name("t", "status"), JooqTypes.of(QueryFieldType.ENUM)),
                QueryFieldType.ENUM));
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        assertThat(ctx.renderInlined(expr.toField(fn("to_string", new FieldExpr("status")), bindings)))
                .contains("toString(\"t\".\"status\")");
        assertThat(ctx.renderInlined(expr.toField(
                fn("concat", text("status: "), new FieldExpr("status")), bindings)))
                .contains("concat(");
        assertThat(ctx.renderInlined(expr.toField(
                fn("starts_with", fn("to_string", new FieldExpr("status")), text("run")), bindings)))
                .contains("startsWith(");
        assertThatThrownBy(() -> expr.toField(fn("length", new FieldExpr("status")), bindings))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("enum");
    }

    // ------------------------------------------------------------------ concat

    @Test
    void concatJoinsEveryOperandThroughTheSameTextRendering() {
        final String sql = render(rowQuery(
                fn("concat", new FieldExpr("deployment"), text(" -> "), new FieldExpr("event_kind")), "edge"));
        // Every operand rendered by the same rule, and each one null-guarded HERE: one absent operand must
        // not lose the whole label, which is the difference between this function's contract and to_string's.
        assertThat(sql).contains("concat("
                + "ifNull(toString(\"dial_usage_log\".\"deployment\"), ''), "
                + "ifNull(toString(' -> '), ''), "
                + "ifNull(toString(\"dial_usage_log\".\"event_kind\"), ''))");
    }

    @Test
    void concatTakesNonTextOperandWithoutAnExplicitRender() {
        assertThat(render(rowQuery(fn("concat", text("depth "), new FieldExpr("total_tokens")), "label")))
                .contains("ifNull(toString(\"dial_usage_log\".\"total_tokens\"), '')");
    }

    @Test
    void concatRejectsSingleOperandAndNamesTheSingleValueForm() {
        assertThatThrownBy(() -> render(rowQuery(fn("concat", new FieldExpr("deployment")), "label")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("at least two operands")
                .hasMessageContaining("to_string");
    }

    @Test
    void concatRejectsNonScalarOperandNamingItsPosition() {
        assertThatThrownBy(() -> render(rowQuery(
                fn("concat", new FieldExpr("deployment"), new FieldExpr("execution_path")), "label")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("operand 2");
        assertThatThrownBy(() -> render(rowQuery(
                fn("concat", fn("map_keys", new FieldExpr(DialUsageLogTestModel.payload("jwt_claims"))), text("x")), "label")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("returns an array");
    }

    @Test
    void renderedLabelIsGroupableSortableAndFilterable() {
        final FnExpr label = fn("concat", new FieldExpr("deployment"), text(" -> "), new FieldExpr("event_kind"));
        final StructuredQuery aggregate = new StructuredQuery(DialUsageLogTestModel.ENTITY, null,
                QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(label, "edge"), new OutputColumn(fn("count"), "n")),
                List.of("edge"), null, List.of(new SortItem("n", SortDir.DESC, null)), null);
        assertThatCode(() -> render(aggregate)).doesNotThrowAnyException();
        assertThat(render(aggregate)).contains("group by").contains("concat(");
    }

    // ------------------------------------------------------------------ array_to_string

    @Test
    void arrayToStringRendersEveryElementThroughTheSameTextRendering() {
        final String sql = render(rowQuery(
                fn("array_to_string", new FieldExpr("execution_path"), text(">")), "path"));
        assertThat(sql).contains("ifNull(arrayStringConcat(arrayMap(x -> ifNull(toString(x), ''),"
                + " \"dial_usage_log\".\"execution_path\"), '>'), '')");
    }

    @Test
    void arrayToStringRendersOverGroupsDistinctValues() {
        final StructuredQuery query = new StructuredQuery(DialUsageLogTestModel.ENTITY, null, QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(fn("array_to_string",
                                fn("group_uniq_array", new FieldExpr("deployment")), text(", ")), "models")),
                List.of("chat_id"), null, null, null);
        assertThat(render(query))
                .contains("arrayStringConcat(arrayMap(x -> ifNull(toString(x), ''),"
                        + " arraySort(groupUniqArray(\"dial_usage_log\".\"deployment\")))");
    }

    /**
     * A <b>boolean</b> element goes through {@code to_string}'s own boolean branch, not a bare
     * {@code toString}. The spec says each element renders exactly as {@code to_string} renders a scalar of
     * that type, and this is the one type where the two differ: a DSL boolean may sit on a ClickHouse
     * {@code UInt8} — which is what every {@code group_uniq_array} over {@code not_empty(…)} or a
     * comparison produces — where {@code toString} prints {@code 1}/{@code 0}.
     */
    @Test
    void arrayToStringRendersBooleanElementsAsTrueAndFalse() {
        final StructuredQuery query = new StructuredQuery(DialUsageLogTestModel.ENTITY, null, QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FieldExpr("chat_id"), null),
                        new OutputColumn(fn("array_to_string",
                                fn("group_uniq_array", new FieldExpr("success")), text(",")), "flags")),
                List.of("chat_id"), null, null, null);
        assertThat(render(query))
                .contains("arrayMap(x -> ifNull(toString(toBool(x)), ''),"
                        + " arraySort(groupUniqArray(\"dial_usage_log\".\"success\")))")
                // The scalar spelling of the same value, for comparison: one rendering, two callers.
                .doesNotContain("ifNull(toString(x)");
        // Through a slice, whose element type is its own operand's.
        assertThat(render(rowQuery(fn("array_to_string",
                fn("array_slice", fn("group_uniq_array", new FieldExpr("success")), integer(1), integer(2)),
                text(",")), "flags")))
                .contains("arrayMap(x -> ifNull(toString(toBool(x)), ''), arraySlice(");
    }

    @Test
    void arrayToStringAcceptsAnEmptyDelimiterAndRejectsComputedOne() {
        assertThatCode(() -> render(rowQuery(
                fn("array_to_string", new FieldExpr("execution_path"), text("")), "path")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> render(rowQuery(
                fn("array_to_string", new FieldExpr("execution_path"), new FieldExpr("deployment")), "path")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("string literal delimiter");
    }

    @Test
    void arrayToStringRejectsNonArrayOperand() {
        assertThatThrownBy(() -> render(rowQuery(
                fn("array_to_string", new FieldExpr("deployment"), text(",")), "path")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires an array operand");
    }

    // ------------------------------------------------------------------ array_slice

    @Test
    void arraySliceRendersBareWithItsLiteralBounds() {
        assertThat(render(rowQuery(
                fn("array_slice", new FieldExpr("execution_path"), integer(1), integer(3)), "head")))
                .contains("arraySlice(\"dial_usage_log\".\"execution_path\", 1, 3)");
    }

    @Test
    void arraySliceAcceptsNegativeOffsetAndRejectsZero() {
        assertThat(render(rowQuery(
                fn("array_slice", new FieldExpr("execution_path"), integer(-3), integer(3)), "tail")))
                .contains("arraySlice(\"dial_usage_log\".\"execution_path\", -3, 3)");
        assertThatThrownBy(() -> render(rowQuery(
                fn("array_slice", new FieldExpr("execution_path"), integer(0), integer(3)), "head")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("non-zero integer literal offset");
    }

    @Test
    void arraySliceRejectsNonPositiveOrComputedLength() {
        assertThatThrownBy(() -> render(rowQuery(
                fn("array_slice", new FieldExpr("execution_path"), integer(1), integer(0)), "head")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("positive integer literal length");
        assertThatThrownBy(() -> render(rowQuery(
                fn("array_slice", new FieldExpr("execution_path"), integer(1), new FieldExpr("total_tokens")), "h")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("integer literal length");
    }

    @Test
    void sliceComposesUnderTheArrayOperandFunctions() {
        assertThat(render(rowQuery(fn("array_to_string",
                fn("array_slice", new FieldExpr("execution_path"), integer(1), integer(6)), text(", ")), "head")))
                .contains("arrayStringConcat(arrayMap(x -> ifNull(toString(x), ''),"
                        + " arraySlice(\"dial_usage_log\".\"execution_path\", 1, 6))");
        assertThat(render(rowQuery(
                fn("array_length", fn("array_slice", new FieldExpr("execution_path"), integer(1), integer(6))), "n")))
                .contains("length(arraySlice(");
        assertThat(render(rowQuery(
                fn("array_join", fn("array_slice", new FieldExpr("execution_path"), integer(1), integer(6))), "s")))
                .contains("arrayJoin(arraySlice(");
    }

    @Test
    void sliceIsProjectionOnlyOutsideThatCarveOut() {
        assertThatThrownBy(() -> render(rowQuery(
                fn("length", fn("array_slice", new FieldExpr("execution_path"), integer(1), integer(3))), "n")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("returns an array");
    }
}
