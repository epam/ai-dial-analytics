package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CursorPage;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the translation layer's rejection paths — every {@link QueryValidationException}
 * the translators raise — plus a couple of render-only invariants (limit bounds / default limit).
 * These are pure logic (no ClickHouse), so they run without a container, complementing the end-to-end
 * execution coverage. Setup mirrors {@code DialUsageLogQueryRenderTest}: real translators wired against
 * the hand-built {@code DialUsageLogSchema} bindings on the connection-less CLICKHOUSE render context.
 */
class QueryTranslatorValidationTest {

    private static final FieldExpr EVENT_KIND = new FieldExpr("event_kind");

    private final ValueExprToObjectMapper mapper = new ValueExprToObjectMapper();
    private final ExprTranslator expr = new ExprTranslator(mapper);
    private final FilterTranslator filter = new FilterTranslator(expr);
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expr, filter);
    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    private final Map<String, QueryFieldBinding> bindings = model.bindings();

    private void condition(FilterNode node) {
        filter.toCondition(node, bindings);
    }

    private void toField(Expr node) {
        expr.toField(node, bindings);
    }

    private void build(StructuredQuery query) {
        builder.build(ctx, model, query);
    }

    private String render(StructuredQuery query) {
        final SelectQuery<Record> select = builder.build(ctx, model, query);
        return select.getSQL(ParamType.INLINED);
    }

    private static StructuredQuery row(FilterNode filter, List<OutputColumn> select, PageSpec page) {
        return new StructuredQuery("dial_usage_log", filter, QueryMode.ROW, false, select, null, null, null, page);
    }

    private static StructuredQuery aggregate(List<OutputColumn> select, List<String> groupBy) {
        return aggregate(select, groupBy, null);
    }

    private static StructuredQuery aggregate(List<OutputColumn> select, List<String> groupBy, PageSpec page) {
        return new StructuredQuery("dial_usage_log", null, QueryMode.AGGREGATE, false, select, groupBy, null, null, page);
    }

    private static ValueExpr value(ValueType type, String raw) {
        return new ValueExpr(type, raw);
    }

    // --- FilterTranslator ---------------------------------------------------------------------------

    @Test
    void comparisonRequiresTwoArguments() {
        assertThatThrownBy(() -> condition(new ComparisonNode(ComparisonOp.EQ, List.of(EVENT_KIND))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void notRequiresExactlyOneChild() {
        final FilterNode leaf = new ComparisonNode(ComparisonOp.EQ, List.of(EVENT_KIND, value(ValueType.STRING, "x")));
        assertThatThrownBy(() -> condition(new LogicalNode(LogicalOp.NOT, List.of(leaf, leaf))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void andRequiresAtLeastOneChild() {
        assertThatThrownBy(() -> condition(new LogicalNode(LogicalOp.AND, List.of())))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void orRequiresAtLeastOneChild() {
        assertThatThrownBy(() -> condition(new LogicalNode(LogicalOp.OR, List.of())))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void inRequiresArrayRightOperand() {
        assertThatThrownBy(() -> condition(
                        new ComparisonNode(ComparisonOp.IN, List.of(EVENT_KIND, value(ValueType.STRING, "chat")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void inRequiresNonEmptyArray() {
        assertThatThrownBy(() -> condition(
                        new ComparisonNode(ComparisonOp.IN, List.of(EVENT_KIND, new ArrayExpr(List.of())))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void inArrayItemsMustBeLiterals() {
        assertThatThrownBy(() -> condition(new ComparisonNode(
                        ComparisonOp.IN, List.of(EVENT_KIND, new ArrayExpr(List.of(new FieldExpr("project_id")))))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void nullLiteralIsOnlyValidWithEqOrNe() {
        assertThatThrownBy(() -> condition(
                        new ComparisonNode(ComparisonOp.LT, List.of(EVENT_KIND, value(ValueType.NULL, null)))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void containsRequiresStringLiteral() {
        assertThatThrownBy(() -> condition(
                        new ComparisonNode(ComparisonOp.CO, List.of(EVENT_KIND, value(ValueType.LONG, "5")))))
                .isInstanceOf(QueryValidationException.class);
    }

    // --- ExprTranslator -----------------------------------------------------------------------------

    @Test
    void unknownFieldIsRejected() {
        assertThatThrownBy(() -> toField(new FieldExpr("nope"))).isInstanceOf(QueryValidationException.class);
    }

    @Test
    void nullExpressionIsRejected() {
        assertThatThrownBy(() -> toField(null)).isInstanceOf(QueryValidationException.class);
    }

    @Test
    void paramExpressionIsRejected() {
        assertThatThrownBy(() -> toField(new ParamExpr("min_threshold")))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void bareArrayExpressionIsRejected() {
        assertThatThrownBy(() -> toField(new ArrayExpr(List.of(value(ValueType.STRING, "x")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void unsupportedFunctionIsRejected() {
        assertThatThrownBy(() -> toField(new FnExpr("nope", false, List.of(EVENT_KIND))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void scalarFunctionRequiresOneArgument() {
        assertThatThrownBy(() -> toField(new FnExpr("lower", false, List.of())))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void widthBucketRequiresFourArguments() {
        assertThatThrownBy(() -> toField(new FnExpr("width_bucket", false, List.of(new FieldExpr("total_tokens")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void argMaxRequiresExactlyTwoArguments() {
        assertThatThrownBy(() -> toField(new FnExpr("arg_max", false, List.of(new FieldExpr("total_tokens")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("arg_max")
                .hasMessageContaining("two arguments");
        assertThatThrownBy(() -> toField(new FnExpr("arg_max", false, List.of(
                        new FieldExpr("total_tokens"), new FieldExpr("request_time"), new FieldExpr("event_kind")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("arg_max")
                .hasMessageContaining("two arguments");
    }

    /**
     * Refused rather than ignored, unlike {@code min}/{@code max}: deduplicating the values would detach a
     * value from the order that selects it, so the quantifier is meaningless here rather than a harmless
     * no-op. The catalog advertises {@code distinct_supported: false} and the SQL front end refuses the
     * equivalent {@code DISTINCT}, so accepting it here would make the two surfaces disagree.
     */
    @Test
    void argMaxRejectsTheDistinctQuantifier() {
        assertThatThrownBy(() -> toField(new FnExpr(
                        "arg_max", true, List.of(new FieldExpr("total_tokens"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("arg_max")
                .hasMessageContaining("distinct");
    }

    @Test
    void argMaxRejectsAnArrayValuedAggregateAsEitherOperand() {
        // group_uniq_array returns an array and is projection-only, so nesting it inside arg_max is
        // rejected on both surfaces — the same guard every other function's operands get.
        FnExpr nested = new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment")));
        assertThatThrownBy(() -> toField(new FnExpr("arg_max", false, List.of(nested, new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("group_uniq_array");
        assertThatThrownBy(() -> toField(new FnExpr("arg_max", false, List.of(new FieldExpr("total_tokens"), nested))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("group_uniq_array");
    }

    @Test
    void dateBinRequiresThreeArguments() {
        assertThatThrownBy(() -> toField(new FnExpr(
                        "date_bin", false, List.of(value(ValueType.INTEGER, "5"), value(ValueType.STRING, "minute")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void dateBinRejectsUnknownUnit() {
        assertThatThrownBy(() -> toField(new FnExpr(
                        "date_bin",
                        false,
                        List.of(value(ValueType.INTEGER, "1"), value(ValueType.STRING, "month"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void dateBinRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> toField(new FnExpr(
                        "date_bin",
                        false,
                        List.of(value(ValueType.INTEGER, "0"), value(ValueType.STRING, "minute"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void dateBinRejectsNonIntegerAmount() {
        assertThatThrownBy(() -> toField(new FnExpr(
                        "date_bin",
                        false,
                        List.of(value(ValueType.STRING, "5"), value(ValueType.STRING, "minute"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class);
    }

    // --- the clock and the offset functions ---------------------------------------------------------

    @Test
    void nowTakesNoArguments() {
        // There is no zone parameter: UTC is the only alignment the surface offers, so an argument is an
        // arity error rather than a zone a caller might expect to be honoured.
        assertThatThrownBy(() -> toField(new FnExpr("now", false, List.of(value(ValueType.STRING, "UTC")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("takes no arguments");
        assertThatThrownBy(() -> toField(new FnExpr("now", false, List.of(value(ValueType.INTEGER, "0")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void dateOffsetRequiresThreeArguments() {
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "hour"), value(ValueType.INTEGER, "2")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("three arguments");
        assertThatThrownBy(() -> toField(new FnExpr("date_add", false,
                List.of(value(ValueType.STRING, "hour"), value(ValueType.INTEGER, "2"),
                        new FieldExpr("request_time"), value(ValueType.INTEGER, "1")))))
                .isInstanceOf(QueryValidationException.class);
    }

    /**
     * {@code week} and {@code quarter} are accepted by {@code date_trunc} and by the SQL surface's
     * {@code INTERVAL} form, so the refusal has to point at the exact equivalent rather than only listing
     * the accepted set — otherwise it reads as an oversight.
     */
    @Test
    void dateOffsetRejectsTheCalendarAlignmentUnitsAndSaysWhatToWriteInstead() {
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "week"), value(ValueType.INTEGER, "1"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("7 'day'");
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "quarter"), value(ValueType.INTEGER, "1"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("3 'month'");
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "fortnight"), value(ValueType.INTEGER, "1"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("unit must be one of");
    }

    @Test
    void dateOffsetRejectsNonPositiveOrNonIntegerAmount() {
        // Direction is carried by the function name, so a negative amount is refused rather than silently
        // inverting the call into its sibling — which would give one offset two documents.
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "hour"), value(ValueType.INTEGER, "-2"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("positive integer");
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "hour"), value(ValueType.INTEGER, "0"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class);
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "hour"), value(ValueType.DECIMAL, "1.5"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void dateOffsetRequiresLiteralUnitAndAmount() {
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(new FieldExpr("deployment"), value(ValueType.INTEGER, "2"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("string literal unit");
        assertThatThrownBy(() -> toField(new FnExpr("date_sub", false,
                List.of(value(ValueType.STRING, "hour"), new FieldExpr("total_tokens"), new FieldExpr("request_time")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("integer literal amount");
    }

    /**
     * The rendered ClickHouse is the point of the whole change, so it is asserted literally: {@code now()}
     * becomes {@code now64(3, 'UTC')} — millisecond precision to match the stored timestamps, and an
     * explicit zone so a server-zone change cannot re-align a window — and the offset becomes a native
     * {@code INTERVAL} term.
     */
    @Test
    void theClockAndOffsetsRenderNativeClickHouse() {
        final String sql = render(row(
                new ComparisonNode(ComparisonOp.GE, List.of(
                        new FieldExpr("request_time"),
                        new FnExpr("date_sub", false, List.of(
                                value(ValueType.STRING, "hour"),
                                value(ValueType.INTEGER, "2"),
                                new FnExpr("now", false, List.of()))))),
                List.of(new OutputColumn(new FieldExpr("event_id"), null)),
                null));
        assertThat(sql).contains("(now64(3, 'UTC') - INTERVAL 2 HOUR)");
    }

    /**
     * A two-bound window keeps both call sites: nothing pins one instant for the statement, so ClickHouse
     * evaluates each {@code now64} on its own, exactly as it would the same SQL sent to it directly.
     */
    @Test
    void twoBoundWindowRendersTwoIndependentClockCalls() {
        final FieldExpr requestTime = new FieldExpr("request_time");
        final String sql = render(row(
                new LogicalNode(LogicalOp.AND, List.of(
                        new ComparisonNode(ComparisonOp.GE, List.of(requestTime,
                                new FnExpr("date_sub", false, List.of(
                                        value(ValueType.STRING, "hour"),
                                        value(ValueType.INTEGER, "1"),
                                        new FnExpr("now", false, List.of()))))),
                        new ComparisonNode(ComparisonOp.LT, List.of(requestTime, new FnExpr("now", false, List.of()))))),
                List.of(new OutputColumn(new FieldExpr("event_id"), null)),
                null));
        assertThat(sql.split("now64\\(3, 'UTC'\\)", -1)).hasSize(3);
        assertThat(sql).contains("(now64(3, 'UTC') - INTERVAL 1 HOUR)").doesNotContainPattern("'\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void everyOffsetUnitRendersItsClickHouseKeyword() {
        for (final Map.Entry<String, String> unit : ExprTranslator.DATE_OFFSET_UNITS.entrySet()) {
            final String sql = render(row(
                    null,
                    List.of(new OutputColumn(new FnExpr("date_add", false, List.of(
                            value(ValueType.STRING, unit.getKey()),
                            value(ValueType.INTEGER, "3"),
                            new FieldExpr("request_time"))), "t")),
                    null));
            assertThat(sql).as("unit '%s'", unit.getKey()).contains("+ INTERVAL 3 " + unit.getValue() + ")");
        }
    }

    @Test
    void roundRejectsMalformedDigitsLiteral() {
        // All three passed the value_type check and then escaped Integer.parseInt as a 500, not a 400:
        // a null literal, an unparseable one, and a long that does not fit an int.
        final FieldExpr price = new FieldExpr("total_price");
        assertThatThrownBy(() -> toField(new FnExpr("round", false, List.of(price, value(ValueType.INTEGER, null)))))
                .isInstanceOf(QueryValidationException.class);
        assertThatThrownBy(() -> toField(new FnExpr("round", false, List.of(price, value(ValueType.INTEGER, "abc")))))
                .isInstanceOf(QueryValidationException.class);
        assertThatThrownBy(() -> toField(new FnExpr("round", false, List.of(price, value(ValueType.LONG, "3000000000")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void percentileRequiresTwoArguments() {
        assertThatThrownBy(() -> toField(new FnExpr("percentile_cont", false, List.of(value(ValueType.DECIMAL, "0.5")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void percentileFractionMustBeNumeric() {
        assertThatThrownBy(() -> toField(new FnExpr(
                        "percentile_cont", false, List.of(value(ValueType.STRING, "x"), new FieldExpr("total_tokens")))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void percentileFractionMustBeWithinUnitInterval() {
        assertThatThrownBy(() -> toField(new FnExpr(
                        "percentile_cont", false, List.of(value(ValueType.DECIMAL, "2"), new FieldExpr("total_tokens")))))
                .isInstanceOf(QueryValidationException.class);
    }

    // ----------------------------------------- lambdas on the structured surface

    @Test
    void higherOrderCallTranslatesWithItsParameterTypedAsTheElement() {
        // roles is array<string>, so the parameter is text and a text function over it resolves — the
        // same operand check every other branch makes, one level down.
        assertThatCode(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("starts_with", false, List.of(
                        new LambdaParamExpr("x"), value(ValueType.STRING, "coder-")))),
                new FieldExpr("execution_path")))))
                .doesNotThrowAnyException();
    }

    @Test
    void lambdaBodyCapturesTheEnclosingRowColumn() {
        // The statement's own bindings stay in scope inside a body, which is ClickHouse's scoping and
        // what makes this document mean there what it means here.
        assertThatCode(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("equals", false, List.of(
                        new LambdaParamExpr("x"), new FieldExpr("deployment")))),
                new FieldExpr("execution_path")))))
                .doesNotThrowAnyException();
    }

    @Test
    void unboundLambdaParameterIsRejectedNamingTheScope() {
        assertThatThrownBy(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("not_empty", false,
                        List.of(new LambdaParamExpr("y")))),
                new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("unknown lambda parameter 'y'")
                .hasMessageContaining("'x'");
    }

    @Test
    void lambdaParameterReferenceOutsideBodyIsRejected() {
        assertThatThrownBy(() -> toField(new FnExpr("not_empty", false, List.of(new LambdaParamExpr("x")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("no lambda parameter is in scope");
    }

    @Test
    void lambdaParameterNameIsValidatedBecauseItIsRenderedBare() {
        // ClickHouse takes a bare identifier in that position, so the name reaches the statement
        // unquoted — which is why anything outside the accepted spelling is refused before rendering.
        assertThatThrownBy(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x; drop"), new FnExpr("not_empty", false,
                        List.of(new LambdaParamExpr("x")))),
                new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("is not a valid name");
    }

    @Test
    void lambdaTakesExactlyOneParameter() {
        assertThatThrownBy(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x", "y"), new FnExpr("not_empty", false,
                        List.of(new LambdaParamExpr("x")))),
                new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("exactly one parameter");
    }

    @Test
    void higherOrderCallRequiresLambdaAndArray() {
        assertThatThrownBy(() -> toField(new FnExpr("arrayExists", false, List.of(
                new FnExpr("not_empty", false, List.of(new FieldExpr("deployment"))),
                new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires a lambda");
        assertThatThrownBy(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("not_empty", false,
                        List.of(new LambdaParamExpr("x")))),
                new FieldExpr("deployment")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires an array operand");
    }

    @Test
    void higherOrderPredicateRequiresBooleanBody() {
        assertThatThrownBy(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("upper", false,
                        List.of(new LambdaParamExpr("x")))),
                new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("returning a boolean");
    }

    @Test
    void aggregateInsideLambdaBodyIsRejectedNamingTheCall() {
        // A body is evaluated per element of one row's array, which is not a position an aggregate can
        // occupy. Without this the call translates like any other nested expression and ClickHouse
        // refuses the statement — a 503 where the contract promises a 400 naming the call. The SQL
        // surface refuses the same body with the same wording (SqlLambdaRewriter.rejectAggregate).
        assertThatThrownBy(() -> toField(new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("equals", false, List.of(
                        new LambdaParamExpr("x"),
                        new FnExpr("max", false, List.of(new FieldExpr("deployment")))))),
                new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'max' is an aggregate")
                .hasMessageContaining("inside the lambda of 'arrayExists'");
    }

    @Test
    void windowInsideLambdaBodyIsRejected() {
        // Not reached by the filter/having window guards, which never look into a select-position body.
        assertThatThrownBy(() -> toField(new FnExpr("arrayMap", false, List.of(
                new LambdaExpr(List.of("x"),
                        new WindowExpr(new FnExpr("row_number", false, List.of()), null, null, null)),
                new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("a window function cannot appear inside the lambda of 'arrayMap'");
    }

    @Test
    void lambdaIsRejectedOutsideHigherOrderCall() {
        assertThatThrownBy(() -> toField(new LambdaExpr(
                List.of("x"), new FnExpr("not_empty", false, List.of(new LambdaParamExpr("x"))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("only valid as the first argument");
    }

    @Test
    void flatteningAggregatesRefuseDistinctAndRequireAnArray() {
        assertThatThrownBy(() -> toField(new FnExpr("groupUniqArrayArray", true,
                List.of(new FieldExpr("execution_path")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("does not accept 'distinct'");
        assertThatThrownBy(() -> toField(new FnExpr("groupArrayArray", false,
                List.of(new FieldExpr("deployment")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires an array operand");
    }

    @Test
    void flatteningAggregateRefusesAnAggregateOperand() {
        // The array carve-out at argument 0 admits an array VALUE. An array-valued aggregate types as an
        // array too, so without the guard this rendered groupUniqArrayArray(arraySort(groupUniqArray(x)))
        // — a nested aggregate ClickHouse refuses, reaching the caller as a 503 where the SQL surface
        // answers 400 at Calcite validation.
        assertThatThrownBy(() -> toField(new FnExpr("groupUniqArrayArray", false, List.of(
                new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment")))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'group_uniq_array' is an aggregate")
                .hasMessageContaining("operand of 'groupUniqArrayArray'");
        // A scalar aggregate is the same mistake and gets the same message, rather than the type
        // mismatch that would send the author looking for the wrong fix.
        assertThatThrownBy(() -> toField(new FnExpr("groupArrayArray", false, List.of(
                new FnExpr("max", false, List.of(new FieldExpr("deployment")))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'max' is an aggregate");
    }

    @Test
    void flatteningAggregateAcceptsAnArrayValuedScalar() {
        // The carve-out's whole point: a scalar call producing an array stays a legal operand.
        assertThatCode(() -> toField(new FnExpr("groupUniqArrayArray", false, List.of(
                new FnExpr("arrayFilter", false, List.of(
                        new LambdaExpr(List.of("x"), new FnExpr("not_empty", false,
                                List.of(new LambdaParamExpr("x")))),
                        new FieldExpr("execution_path")))))))
                .doesNotThrowAnyException();
    }


    @Test
    void groupUniqArrayRefusesDistinct() {
        // The catalog advertises distinct_supported: false and the SQL front end rejects the equivalent
        // DISTINCT outright, so ignoring the flag here would make the two surfaces disagree on one input.
        assertThatThrownBy(() -> toField(new FnExpr("group_uniq_array", true, List.of(new FieldExpr("deployment")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("does not accept 'distinct'");
    }

    @Test
    void groupUniqArrayCannotBeNestedInAnotherFunction() {
        // ClickHouse would happily answer length(<array>) with the element count; the DSL has no array
        // operand, so the projectable-only result fails here instead of answering a question nobody asked.
        assertThatThrownBy(() -> toField(new FnExpr("length", false,
                        List.of(new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment")))))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("can only be projected");
    }

    @Test
    void groupUniqArrayCannotBeComparisonOperand() {
        assertThatThrownBy(() -> condition(new ComparisonNode(ComparisonOp.EQ, List.of(
                        new FnExpr("group_uniq_array", false, List.of(new FieldExpr("deployment"))),
                        value(ValueType.STRING, "x")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("can only be projected");
    }

    // --- StructuredQueryBuilder ---------------------------------------------------------------------

    @Test
    void aggregateRequiresAtLeastOneSelectEntry() {
        assertThatThrownBy(() -> build(aggregate(List.of(), List.of("event_kind"))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void aggregateOutputNeedsNoAlias() {
        final OutputColumn count = new OutputColumn(new FnExpr("count", false, List.of()), null);
        // The ClickHouse dialect renders an alias without the AS keyword.
        assertThat(render(aggregate(List.of(count), List.of()))).containsIgnoringCase("count(*) \"count\"");
    }

    @Test
    void unknownGroupByFieldIsRejected() {
        final OutputColumn count = new OutputColumn(new FnExpr("count", false, List.of()), "c");
        assertThatThrownBy(() -> build(aggregate(List.of(count), List.of("nope"))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void negativeOffsetIsRejected() {
        assertThatThrownBy(() -> build(row(null, null, new OffsetPage(-1L, 10, false))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void cursorPagingIsRejected() {
        assertThatThrownBy(() -> build(row(null, null, new CursorPage(null, 100))))
                .isInstanceOf(QueryValidationException.class);
    }

    // --- ValueExprToObjectMapper --------------------------------------------------------------------

    @Test
    void valueMustDeclareType() {
        assertThatThrownBy(() -> mapper.map(value(null, "x"))).isInstanceOf(QueryValidationException.class);
    }

    @Test
    void nonNullTypeRequiresValue() {
        assertThatThrownBy(() -> mapper.map(value(ValueType.STRING, null)))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void invalidIntegerLiteralIsRejected() {
        assertThatThrownBy(() -> mapper.map(value(ValueType.INTEGER, "x"))).isInstanceOf(QueryValidationException.class);
    }

    @Test
    void invalidLongLiteralIsRejected() {
        assertThatThrownBy(() -> mapper.map(value(ValueType.LONG, "x"))).isInstanceOf(QueryValidationException.class);
    }

    @Test
    void invalidDecimalLiteralIsRejected() {
        assertThatThrownBy(() -> mapper.map(value(ValueType.DECIMAL, "x"))).isInstanceOf(QueryValidationException.class);
    }

    @Test
    void invalidBooleanLiteralIsRejected() {
        assertThatThrownBy(() -> mapper.map(value(ValueType.BOOLEAN, "maybe")))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void invalidUuidLiteralIsRejected() {
        assertThatThrownBy(() -> mapper.map(value(ValueType.UUID, "not-a-uuid")))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void invalidDateLiteralIsRejected() {
        assertThatThrownBy(() -> mapper.map(value(ValueType.DATE, "2026-99-99")))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void parsesValidScalarLiterals() {
        // value_types with no dial_usage_log column to compare against are still parsed correctly here.
        assertThat(mapper.map(value(ValueType.BOOLEAN, "TRUE"))).isEqualTo(Boolean.TRUE);
        assertThat(mapper.map(value(ValueType.DATE, "2026-06-01"))).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(mapper.map(value(ValueType.DECIMAL, "1.5"))).isEqualTo(new BigDecimal("1.5"));
        assertThat(mapper.map(value(ValueType.NULL, null))).isNull();
    }

    // --- Render-only invariants ---------------------------------------------------------------------

    @Test
    void limitAboveMaximumIsRejected() {
        assertThatThrownBy(() -> render(row(null, null, new OffsetPage(0L, 5000, false))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("5000")
                .hasMessageContaining("1000");
    }

    @Test
    void negativeLimitIsRejected() {
        assertThatThrownBy(() -> render(row(null, null, new OffsetPage(0L, -1, false))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void zeroLimitFallsBackToDefault() {
        final String sql = render(row(null, null, new OffsetPage(0L, 0, false)));
        assertThat(sql).containsIgnoringCase("limit 100");
    }

    @Test
    void absentPageUsesDefaultLimit() {
        final String sql = render(row(null, null, null));
        assertThat(sql).containsIgnoringCase("limit 100");
    }

    // --- Document comparisons over object fields -----------------------------------------------------

    private static final FieldExpr TAGS = new FieldExpr("dial_usage_log_payload.request_tags");
    private static final FieldExpr PATH = new FieldExpr("execution_path");

    private static ComparisonNode compare(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    private String renderFilter(FilterNode node) {
        return render(row(node, null, null));
    }

    @Test
    void containsOnObjectFieldRendersOverCanonicalText() {
        // The column is wrapped in toJSONString on the execute path: LIKE over a JSON column is a
        // ClickHouse Code 43, and the canonical text is what the comparison is defined over.
        final String sql = renderFilter(compare(ComparisonOp.CO, TAGS, value(ValueType.STRING, "gpt-4")));
        assertThat(sql).contains("toJSONString").contains("\"dial_usage_log_payload\".\"request_tags\"").contains("gpt-4");
    }

    @Test
    void caseInsensitiveContainsOnObjectFieldRenders() {
        final String sql = renderFilter(compare(ComparisonOp.ICO, TAGS, value(ValueType.STRING, "gpt-4")));
        assertThat(sql).contains("toJSONString").containsIgnoringCase("ilike");
    }

    @Test
    void notContainsOnObjectFieldRenders() {
        assertThat(renderFilter(compare(ComparisonOp.NC, TAGS, value(ValueType.STRING, "x"))))
                .contains("toJSONString").containsIgnoringCase("not like");
        assertThat(renderFilter(compare(ComparisonOp.INC, TAGS, value(ValueType.STRING, "x"))))
                .contains("toJSONString").containsIgnoringCase("not ilike");
    }

    @Test
    void equalityOnObjectFieldCanonicalizesBothSides() {
        // Both sides go through the same ClickHouse function: canonicalizing only the column would make
        // the match depend on the key order the caller happened to write.
        final String sql =
                renderFilter(compare(ComparisonOp.EQ, TAGS, value(ValueType.STRING, "{\"a\":1}")));
        assertThat(sql).contains("toJSONString").contains("\"dial_usage_log_payload\".\"request_tags\"").contains("CAST(");
        // Two occurrences — the column and the literal — so the literal is canonicalized too. A split on
        // n occurrences yields n+1 parts, so "both sides" is a size of exactly 3.
        assertThat(sql.split("toJSONString", -1)).as("both operands canonicalized").hasSize(3);
    }

    @Test
    void inequalityOnObjectFieldCanonicalizesBothSides() {
        assertThat(renderFilter(compare(ComparisonOp.NE, TAGS, value(ValueType.STRING, "{\"a\":1}"))))
                .contains("toJSONString").contains("CAST(");
    }

    @Test
    void membershipOnObjectFieldCanonicalizesEveryItem() {
        final String sql = renderFilter(new ComparisonNode(ComparisonOp.IN, List.of(TAGS,
                new ArrayExpr(List.of(
                        value(ValueType.STRING, "{\"a\":1}"),
                        value(ValueType.STRING, "{\"b\":2}"))))));
        assertThat(sql.split("CAST\\(", -1)).as("one cast per list item").hasSize(3);
    }

    @Test
    void scalarComparisonIsNotWrappedInToJsonString() {
        // The wrap must be scoped to object operands: applying it to a string column would silently
        // change what every existing filter means.
        assertThat(renderFilter(compare(ComparisonOp.CO, EVENT_KIND, value(ValueType.STRING, "llm"))))
                .doesNotContain("toJSONString");
    }

    @Test
    void orderingComparisonOnObjectFieldIsRejected() {
        for (final ComparisonOp op : List.of(
                ComparisonOp.LT, ComparisonOp.GT, ComparisonOp.LE, ComparisonOp.GE)) {
            assertThatThrownBy(() -> condition(compare(op, TAGS, value(ValueType.STRING, "{\"a\":1}"))))
                    .as("ordering '%s' over a document", op.code())
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("object field 'dial_usage_log_payload.request_tags'")
                    .hasMessageContaining("can only be projected");
        }
    }

    @Test
    void nullTestOnObjectFieldIsRejected() {
        // A JSON column is never null, so this would be a constant predicate rather than a document test.
        for (final ComparisonOp op : List.of(ComparisonOp.EQ, ComparisonOp.NE)) {
            assertThatThrownBy(() -> condition(compare(op, TAGS, value(ValueType.NULL, null))))
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("object field 'dial_usage_log_payload.request_tags'")
                    .hasMessageContaining("never null");
        }
    }

    @Test
    void nonJsonLiteralIsRejectedBeforeExecution() {
        assertThatThrownBy(() -> condition(compare(ComparisonOp.EQ, TAGS, value(ValueType.STRING, "x"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires a JSON document")
                .hasMessageContaining("'x'");
    }

    @Test
    void jsonArrayLiteralIsRejectedAsDocument() {
        assertThatThrownBy(() -> condition(compare(ComparisonOp.EQ, TAGS, value(ValueType.STRING, "[1,2]"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires a JSON document");
    }

    @Test
    void containsLiteralNeedsNoJsonWellFormedness() {
        // The contains family searches text, so its pattern is an ordinary substring.
        assertThat(renderFilter(compare(ComparisonOp.CO, TAGS, value(ValueType.STRING, "\"role\":"))))
                .contains("toJSONString");
    }

    @Test
    void everyComparisonOnAnArrayFieldIsRejected() {
        for (final ComparisonOp op : ComparisonOp.values()) {
            final Expr right = op == ComparisonOp.IN
                    ? new ArrayExpr(List.of(value(ValueType.STRING, "x")))
                    : value(ValueType.STRING, "x");
            assertThatThrownBy(() -> condition(compare(op, PATH, right)))
                    .as("'%s' over an array field", op.code())
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("execution_path")
                    .hasMessageContaining("can only be projected");
        }
    }

    @Test
    void objectFieldAsRightOperandIsRejected() {
        // The carve-out is for the left operand; a document on the right has no rendering.
        assertThatThrownBy(() -> condition(compare(ComparisonOp.EQ, EVENT_KIND, TAGS)))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("object field 'dial_usage_log_payload.request_tags'")
                .hasMessageContaining("can only be projected");
    }

    // --- array_join ---------------------------------------------------------------------------------

    @Test
    void arrayExpansionRendersAsArrayJoin() {
        final String sql = render(row(null, List.of(new OutputColumn(
                new FnExpr("array_join", false, List.of(PATH)), "step")), null));
        // Bare, with no ifNull/empty wrapper: an empty array yielding no rows IS the contract, so a
        // wrapper here would quietly become the outer variant this deliberately does not have.
        assertThat(sql).contains("arrayJoin(").contains("execution_path");
        assertThat(sql).doesNotContain("emptyArrayToSingle").doesNotContain("ifNull(arrayJoin");
    }

    @Test
    void arrayExpansionAcceptsComputedArray() {
        final String sql = render(row(null, List.of(new OutputColumn(
                new FnExpr("array_join", false, List.of(
                        new FnExpr("split_string", false, List.of(
                                new FieldExpr("deployment"), value(ValueType.STRING, ","))))),
                "part")), null));
        assertThat(sql).contains("arrayJoin(").contains("splitByString");
    }

    @Test
    void arrayExpansionIsAcceptedInFilter() {
        // ClickHouse accepts arrayJoin in WHERE (measured on 26.3), so the DSL does not narrow it. This
        // pins the absence of a guard an earlier draft had, which is easier to delete than to notice.
        final String sql = renderFilter(compare(ComparisonOp.EQ,
                new FnExpr("array_join", false, List.of(PATH)), value(ValueType.STRING, "core")));
        assertThat(sql).contains("arrayJoin(");
    }

    @Test
    void arrayExpansionIsAcceptedInsideAnAggregate() {
        final String sql = render(aggregate(List.of(new OutputColumn(
                new FnExpr("count", false, List.of(new FnExpr("array_join", false, List.of(PATH)))), "n")),
                null));
        assertThat(sql).contains("arrayJoin(");
    }

    @Test
    void arrayExpansionOverAggregateArrayCarriesTheOperandType() {
        // group_uniq_array is the one array-returning call whose element type is not fixed at string: it
        // collects its own operand's values. A blanket string here typed this column as text, and since the
        // aggregate-mode alias binding takes its type from ExprFieldType, a numeric `having` over the alias
        // was then validated as a text comparison. Asserted through `having`, which is where it went wrong.
        final StructuredQuery query = new StructuredQuery(
                "dial_usage_log", null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(
                        new FnExpr("array_join", false, List.of(
                                new FnExpr("group_uniq_array", false, List.of(new FieldExpr("total_tokens"))))),
                        "token_value")),
                null,
                new ComparisonNode(ComparisonOp.GT, List.of(
                        new FieldExpr("token_value"), value(ValueType.LONG, "100"))),
                null, null);

        assertThat(render(query)).contains("arrayJoin(").contains("groupUniqArray");
    }

    @Test
    void arrayExpansionIsAcceptedInsideWindow() {
        // The third position the spec claims and the only one the other tests here do not reach. Asserted
        // rather than assumed: the window guards reject a nested WINDOW, and it would be easy to believe
        // they rejected this too.
        final WindowExpr ranked = new WindowExpr(
                new FnExpr("row_number", false, List.of()), null,
                List.of(new WindowOrder(new FnExpr("array_join", false, List.of(PATH)), SortDir.ASC, null)),
                null);
        final String sql = render(row(null, List.of(new OutputColumn(ranked, "rn")), null));
        assertThat(sql).containsIgnoringCase("row_number() over").contains("arrayJoin(");
    }

    @Test
    void arrayExpansionGroupedByItsAliasRenders() {
        final String sql = render(aggregate(List.of(
                new OutputColumn(new FnExpr("array_join", false, List.of(PATH)), "step"),
                new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("step")));
        assertThat(sql).contains("arrayJoin(").contains("group by").containsIgnoringCase("step");
    }

    @Test
    void arrayExpansionWithSecondArgumentIsRejected() {
        // There is no mode operand: the engine's own arrayJoin takes one argument, and a 'left' mode here
        // would be invented surface. The message has to say that, or the arity reads like an oversight.
        assertThatThrownBy(() -> render(row(null, List.of(new OutputColumn(
                new FnExpr("array_join", false, List.of(PATH, value(ValueType.STRING, "left"))), "step")), null)))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("array_join")
                .hasMessageContaining("exactly one argument")
                .hasMessageContaining("no mode");
    }

    @Test
    void arrayExpansionOverNonArrayIsRejected() {
        assertThatThrownBy(() -> render(row(null, List.of(new OutputColumn(
                new FnExpr("array_join", false, List.of(EVENT_KIND)), "step")), null)))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("array_join")
                .hasMessageContaining("requires an array operand");
    }

    @Test
    void includeTotalAlongsideAnExpansionIsRejected() {
        // buildCount counts source rows and cannot see the projection, so the total it produces is the
        // count before expansion — a real number that answers a different question than the page does.
        assertThatThrownBy(() -> render(row(null, List.of(new OutputColumn(
                new FnExpr("array_join", false, List.of(PATH)), "step")),
                new OffsetPage(0, 10, true))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("include_total")
                .hasMessageContaining("array_join");
    }

    @Test
    void includeTotalAlongsideAnAggregateExpansionIsAccepted() {
        // The refusal is row-mode only. In aggregate mode the executor returns a null total without ever
        // building a count, so there is no wrong number to ship — and refusing here would reject this
        // function's headline shape (expansion aliased, grouped by that alias) for a flag many clients set
        // uniformly on every page.
        final String sql = render(aggregate(List.of(
                new OutputColumn(new FnExpr("array_join", false, List.of(PATH)), "step"),
                new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("step"),
                new OffsetPage(0, 10, true)));
        assertThat(sql).contains("arrayJoin(").contains("group by");
    }

    @Test
    void includeTotalWithoutAnExpansionStillWorks() {
        // The guard is keyed on the expansion, not on include_total: the ordinary paged query is untouched.
        assertThat(render(row(null, List.of(new OutputColumn(EVENT_KIND, null)), new OffsetPage(0, 10, true))))
                .contains("event_kind");
    }

    @Test
    void documentComparisonWorksInsideBooleanTree() {
        final String sql = renderFilter(new LogicalNode(LogicalOp.AND, List.of(
                compare(ComparisonOp.ICO, TAGS, value(ValueType.STRING, "gpt-4")),
                compare(ComparisonOp.EQ, EVENT_KIND, value(ValueType.STRING, "llm_call")))));
        assertThat(sql).contains("toJSONString").contains("llm_call");
    }
}
