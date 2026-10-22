package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ClickHouse SQL the DSL's {@code case} and {@code window} expressions render to, and the position
 * and shape rules the builder enforces on them — the structured-query delta's "Conditional case
 * expression" and "Window expression" requirements, offline over {@link DialUsageLogTestModel}.
 */
class CaseAndWindowRenderTest {

    private static final FieldExpr TOTAL_TOKENS = new FieldExpr("total_tokens");
    private static final FieldExpr PROJECT_ID = new FieldExpr("project_id");
    private static final FieldExpr REQUEST_TIME = new FieldExpr("request_time");
    private static final FieldExpr SUCCESS = new FieldExpr("success");

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

    private static StructuredQuery row(FilterNode filter, OutputColumn... select) {
        return new StructuredQuery(DialUsageLogTestModel.ENTITY, filter, QueryMode.ROW, false, List.of(select),
                null, null, null, null);
    }

    private static StructuredQuery aggregate(List<String> groupBy, OutputColumn... select) {
        return new StructuredQuery(DialUsageLogTestModel.ENTITY, null, QueryMode.AGGREGATE, false, List.of(select),
                groupBy, null, null, null);
    }

    private static ValueExpr text(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static ValueExpr integer(String value) {
        return new ValueExpr(ValueType.INTEGER, value);
    }

    private static ComparisonNode gt(Expr left, Expr right) {
        return new ComparisonNode(ComparisonOp.GT, List.of(left, right));
    }

    private static FnExpr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    // ------------------------------------------------------------------ case

    @Test
    void caseRendersAsSearchedCase() {
        final CaseExpr size = new CaseExpr(List.of(
                new CaseBranch(gt(TOTAL_TOKENS, integer("1000")), text("large")),
                new CaseBranch(gt(TOTAL_TOKENS, integer("100")), text("medium"))), text("small"));
        final String sql = render(row(null, new OutputColumn(size, "size")));
        assertThat(sql).containsIgnoringCase(
                "case when \"dial_usage_log\".\"total_tokens\" > 1000 then 'large' when \"dial_usage_log\".\"total_tokens\" > 100 then 'medium' else 'small' end \"size\"");
    }

    @Test
    void caseWithoutElseRendersNoElse() {
        final CaseExpr conditional = new CaseExpr(List.of(
                new CaseBranch(new ComparisonNode(ComparisonOp.EQ, List.of(SUCCESS, new ValueExpr(ValueType.BOOLEAN, "true"))),
                        TOTAL_TOKENS)), null);
        final String sql = render(row(null, new OutputColumn(conditional, "ok_tokens")));
        assertThat(sql).containsIgnoringCase("then \"dial_usage_log\".\"total_tokens\" end \"ok_tokens\"");
        assertThat(sql).doesNotContainIgnoringCase("else");
    }

    @Test
    void nullBranchRendersAsNullAndTakesTheOtherBranchesType() {
        final CaseExpr nullif = new CaseExpr(List.of(
                new CaseBranch(new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("chat_id"), text(""))),
                        new ValueExpr(ValueType.NULL, null))), new FieldExpr("chat_id"));
        assertThat(render(row(null, new OutputColumn(nullif, "chat"))))
                .containsIgnoringCase("then null else \"dial_usage_log\".\"chat_id\" end \"chat\"");
    }

    @Test
    void caseIsAcceptedInTheFilterAndAsAnAggregateArgument() {
        final CaseExpr flag = new CaseExpr(List.of(new CaseBranch(gt(TOTAL_TOKENS, integer("1000")), integer("1"))), integer("0"));
        assertThat(render(aggregate(List.of("project_id"),
                new OutputColumn(PROJECT_ID, null), new OutputColumn(fn("sum", flag), "large_calls"))))
                .containsIgnoringCase("sum(case when");
        assertThat(render(row(new ComparisonNode(ComparisonOp.EQ, List.of(flag, integer("1"))), new OutputColumn(PROJECT_ID, null))))
                .containsIgnoringCase("where case when");
    }

    @Test
    void emptyCaseIsRejected() {
        assertThatThrownBy(() -> render(row(null, new OutputColumn(new CaseExpr(List.of(), text("x")), "v"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void mismatchedBranchesAreRejected() {
        final CaseExpr mixed = new CaseExpr(List.of(new CaseBranch(gt(TOTAL_TOKENS, integer("1")), text("yes"))), integer("1"));
        assertThatThrownBy(() -> render(row(null, new OutputColumn(mixed, "v"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("same type");
    }

    @Test
    void allNullBranchesAreRejected() {
        final CaseExpr nothing = new CaseExpr(List.of(new CaseBranch(gt(TOTAL_TOKENS, integer("1")), new ValueExpr(ValueType.NULL, null))), null);
        assertThatThrownBy(() -> render(row(null, new OutputColumn(nothing, "v"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("null literal");
    }

    // ------------------------------------------------------------------ window

    @Test
    void rowNumberRendersPartitionAndOrder() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), List.of(PROJECT_ID),
                List.of(new WindowOrder(REQUEST_TIME, SortDir.ASC, null)), null);
        assertThat(render(row(null, new OutputColumn(PROJECT_ID, null), new OutputColumn(rn, "rn"))))
                .containsIgnoringCase("row_number() over (partition by \"dial_usage_log\".\"project_id\" order by \"dial_usage_log\".\"request_time\" asc) \"rn\"");
    }

    @Test
    void runningSumRendersItsFrame() {
        final WindowExpr running = new WindowExpr(fn("sum", TOTAL_TOKENS), null,
                List.of(new WindowOrder(REQUEST_TIME, SortDir.DESC, null)),
                new WindowFrame(FrameUnit.ROWS, new FrameBound(FrameBoundKind.UNBOUNDED_PRECEDING, null),
                        new FrameBound(FrameBoundKind.CURRENT_ROW, null)));
        assertThat(render(row(null, new OutputColumn(running, "running"))))
                .containsIgnoringCase("sum(\"dial_usage_log\".\"total_tokens\") over (order by \"dial_usage_log\".\"request_time\" desc"
                        + " rows between unbounded preceding and current row) \"running\"");
    }

    @Test
    void offsetBoundsRenderTheirOffsets() {
        final WindowExpr smoothed = new WindowExpr(fn("avg", TOTAL_TOKENS), null,
                List.of(new WindowOrder(REQUEST_TIME, null, null)),
                new WindowFrame(FrameUnit.RANGE, new FrameBound(FrameBoundKind.PRECEDING, 2L),
                        new FrameBound(FrameBoundKind.FOLLOWING, 1L)));
        assertThat(render(row(null, new OutputColumn(smoothed, "smoothed"))))
                .containsIgnoringCase("range between 2 preceding and 1 following");
    }

    @Test
    void emptyWindowRendersOverParentheses() {
        assertThat(render(row(null, new OutputColumn(new WindowExpr(fn("count"), null, null, null), "n"))))
                .containsIgnoringCase("count(*) over () \"n\"");
    }

    @Test
    void windowOverAnAggregateInAggregateMode() {
        final WindowExpr rank = new WindowExpr(fn("rank"), null,
                List.of(new WindowOrder(fn("sum", TOTAL_TOKENS), SortDir.DESC, null)), null);
        assertThat(render(aggregate(List.of("project_id"),
                new OutputColumn(PROJECT_ID, null), new OutputColumn(fn("sum", TOTAL_TOKENS), "t"), new OutputColumn(rank, "r"))))
                .containsIgnoringCase("rank() over (order by sum(\"dial_usage_log\".\"total_tokens\") desc) \"r\"");
    }

    @Test
    void nonKeyFieldInsideTheWindowInAggregateModeIsRejected() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), List.of(new FieldExpr("event_kind")), null, null);
        assertThatThrownBy(() -> render(aggregate(List.of("project_id"),
                new OutputColumn(PROJECT_ID, null), new OutputColumn(rn, "rn"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("event_kind");
    }

    @Test
    void windowInTheFilterIsRejected() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), null, null, null);
        assertThatThrownBy(() -> render(row(new ComparisonNode(ComparisonOp.LT, List.of(rn, integer("10"))),
                new OutputColumn(PROJECT_ID, null))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'select' entries only");
    }

    @Test
    void windowInCaseBranchConditionOfSelectEntryRenders() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), null,
                List.of(new WindowOrder(REQUEST_TIME, SortDir.ASC, null)), null);
        final CaseExpr phase = new CaseExpr(
                List.of(new CaseBranch(gt(rn, integer("5")), text("late"))), text("early"));
        assertThat(render(row(null, new OutputColumn(phase, "phase")))).containsIgnoringCase(
                "case when row_number() over (order by \"dial_usage_log\".\"request_time\" asc) > 5"
                        + " then 'late' else 'early' end \"phase\"");
    }

    @Test
    void windowInCaseBranchConditionInsideFilterIsRejected() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), null, null, null);
        final CaseExpr phase = new CaseExpr(
                List.of(new CaseBranch(gt(rn, integer("5")), integer("1"))), integer("0"));
        assertThatThrownBy(() -> render(row(new ComparisonNode(ComparisonOp.EQ, List.of(phase, integer("1"))),
                new OutputColumn(PROJECT_ID, null))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("'select' entries only");
    }

    @Test
    void nonKeyFieldInsideWindowNestedInCaseInAggregateModeIsRejected() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), List.of(new FieldExpr("event_kind")), null, null);
        final CaseExpr phase = new CaseExpr(
                List.of(new CaseBranch(gt(rn, integer("1")), text("late"))), text("early"));
        assertThatThrownBy(() -> render(aggregate(List.of("project_id"),
                new OutputColumn(PROJECT_ID, null), new OutputColumn(phase, "phase"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("event_kind");
    }

    @Test
    void windowInsideAnAggregateIsRejected() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), null, null, null);
        assertThatThrownBy(() -> render(aggregate(null, new OutputColumn(fn("sum", rn), "s"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("aggregate function's arguments");
    }

    @Test
    void nestedWindowIsRejected() {
        final WindowExpr rn = new WindowExpr(fn("row_number"), null, null, null);
        final WindowExpr outer = new WindowExpr(fn("sum", rn), null, null, null);
        assertThatThrownBy(() -> render(row(null, new OutputColumn(outer, "s"))))
                .isInstanceOf(QueryValidationException.class);
    }

    @Test
    void rankingFunctionOutsideTheWindowIsRejected() {
        assertThatThrownBy(() -> render(row(null, new OutputColumn(fn("row_number"), "rn"))))
                .isInstanceOf(QueryValidationException.class)
                // The catalog advertises row_number, so the message must point at the OVER clause rather
                // than call it unsupported and contradict the discovery surface.
                .hasMessageContaining("row_number")
                .hasMessageContaining("OVER")
                .hasMessageNotContaining("unsupported function");
    }

    @Test
    void unsupportedWindowFunctionAndDistinctAreRejected() {
        assertThatThrownBy(() -> render(row(null, new OutputColumn(new WindowExpr(fn("lag", TOTAL_TOKENS), null, null, null), "l"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("lag");
        assertThatThrownBy(() -> render(row(null, new OutputColumn(
                new WindowExpr(new FnExpr("count", true, List.of(PROJECT_ID)), null, null, null), "c"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("distinct");
    }

    @Test
    void malformedFrameBoundsAreRejected() {
        assertThatThrownBy(() -> render(row(null, new OutputColumn(new WindowExpr(fn("count"), null, null,
                new WindowFrame(FrameUnit.ROWS, new FrameBound(FrameBoundKind.PRECEDING, null),
                        new FrameBound(FrameBoundKind.CURRENT_ROW, null))), "c"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> render(row(null, new OutputColumn(new WindowExpr(fn("count"), null, null,
                new WindowFrame(FrameUnit.ROWS, new FrameBound(FrameBoundKind.CURRENT_ROW, 3L),
                        new FrameBound(FrameBoundKind.CURRENT_ROW, null))), "c"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("no 'offset'");
    }
}
