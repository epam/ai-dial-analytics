package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.CallerValues;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A caller value in a structured query: bound on execution, a placeholder on validation, refused elsewhere. */
class CallerValueQueryRenderTest {

    private static final ParamExpr USER_HASH = new ParamExpr(CallerValues.USER_HASH);
    private static final FieldExpr USER_HASH_FIELD = new FieldExpr("user_hash");

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    private final StructuredQueryBuilder builder = newBuilder();

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    private static StructuredQuery query(FilterNode filter, List<OutputColumn> select) {
        return new StructuredQuery(DialUsageLogTestModel.ENTITY, filter, QueryMode.ROW, false, select, null, null, null,
                new OffsetPage(0L, 10, false));
    }

    private static StructuredQuery query(FilterNode filter) {
        return query(filter, List.of(new OutputColumn(new FieldExpr("project_id"), null)));
    }

    private static ComparisonNode eq(Expr left, Expr right) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(left, right));
    }

    private String render(StructuredQuery query, UnaryOperator<String> values) {
        return CallerValues.resolving(values, () -> builder.build(ctx, model, query).getSQL(ParamType.INLINED));
    }

    @Test
    void knownValueIsInlinedAsStringLiteral() {
        final String sql = render(query(eq(USER_HASH_FIELD, USER_HASH)),
                Map.of(CallerValues.USER_HASH, "aaaa-01")::get);
        assertThat(sql).contains("'aaaa-01'").doesNotContain("Nullable(UInt8)");
    }

    @Test
    void computedTextOperandIsComparedLikeField() {
        final String sql = render(query(eq(new FnExpr("lower", false, List.of(USER_HASH_FIELD)), USER_HASH)),
                Map.of(CallerValues.USER_HASH, "aaaa-01")::get);
        assertThat(sql).contains("'aaaa-01'");
    }

    @Test
    void nameDifferingOnlyInCaseIsAnyOtherParam() {
        assertThatThrownBy(() -> render(query(eq(USER_HASH_FIELD, new ParamExpr("Caller.User_Hash"))), name -> "aaaa-01"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("param expressions are not supported");
    }

    @Test
    void eitherOperandOrderBindsTheSame() {
        final String sql = render(query(eq(USER_HASH, USER_HASH_FIELD)), Map.of(CallerValues.USER_HASH, "aaaa-01")::get);
        assertThat(sql).contains("'aaaa-01'");
    }

    @Test
    void anUnknownValueMakesTheWholeComparisonNull() {
        for (final String unknown : new String[] {null, "  ", "x".repeat(CallerValues.MAX_BYTES + 1)}) {
            final String sql = render(query(new LogicalNode(LogicalOp.NOT, List.of(eq(USER_HASH_FIELD, USER_HASH)))),
                    name -> unknown);
            assertThat(sql).contains("CAST(NULL, 'Nullable(UInt8)')").doesNotContain("user_hash = ''");
        }
    }

    @Test
    void withoutScopeCallerValueIsRefusedLikeAnyParam() {
        assertThatThrownBy(() -> builder.build(ctx, model, query(eq(USER_HASH_FIELD, USER_HASH))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("param expressions are not supported");
    }

    @Test
    void validationBuildsWithPlaceholderAndReadsNoIdentity() {
        CallerValues.validating(() -> builder.build(ctx, model, query(eq(USER_HASH_FIELD, USER_HASH))));
    }

    private static StructuredQuery grouped(FilterNode having) {
        return new StructuredQuery(DialUsageLogTestModel.ENTITY, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FieldExpr("project_id"), null),
                        new OutputColumn(new FnExpr("max", false, List.of(USER_HASH_FIELD)), "m"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("project_id"), having, null, new OffsetPage(0L, 10, false));
    }

    @Test
    void havingComparesGroupedFieldOrStringAggregateOutput() {
        assertThat(render(grouped(eq(new FieldExpr("project_id"), new ParamExpr(CallerValues.PROJECT))), name -> "P1"))
                .contains("'P1'");
        assertThat(render(grouped(eq(new FieldExpr("m"), USER_HASH)), name -> "aaaa-01")).contains("'aaaa-01'");
        assertThatThrownBy(() -> render(grouped(eq(new FieldExpr("n"), USER_HASH)), name -> "aaaa-01"))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("caller value 'caller.user_hash'");
    }

    @Test
    void unknownFieldIsReportedWhetherOrNotTheCallerHasValue() {
        for (final String value : new String[] {"aaaa-01", null}) {
            assertThatThrownBy(() -> render(query(eq(new FieldExpr("no_such_field"), USER_HASH)), name -> value))
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("no_such_field");
        }
    }

    @Test
    void everyOtherPositionIsRefusedNamingTheCallerValue() {
        final List<StructuredQuery> misplaced = List.of(
                query(null, List.of(new OutputColumn(USER_HASH, "h"))),
                query(eq(new FnExpr("lower", false, List.of(USER_HASH)), USER_HASH_FIELD)),
                query(new ComparisonNode(ComparisonOp.GT, List.of(USER_HASH_FIELD, USER_HASH))),
                query(new ComparisonNode(ComparisonOp.IN, List.of(USER_HASH_FIELD,
                        new ArrayExpr(List.of(new ValueExpr(ValueType.STRING, "a"), USER_HASH))))),
                query(eq(new FieldExpr("prompt_tokens"), USER_HASH)),
                query(eq(new FieldExpr("event_id"), USER_HASH)),
                query(eq(USER_HASH, new ValueExpr(ValueType.STRING, "x"))),
                query(eq(USER_HASH, new ParamExpr(CallerValues.PROJECT))),
                query(eq(new FnExpr("max", false, List.of(USER_HASH_FIELD)), USER_HASH)),
                query(eq(new FnExpr("lower", false, List.of(USER_HASH)), USER_HASH_FIELD)),
                query(eq(new CaseExpr(List.of(new CaseBranch(eq(USER_HASH_FIELD, USER_HASH), new ValueExpr(ValueType.STRING, "y"))),
                        new ValueExpr(ValueType.STRING, "n")), new ValueExpr(ValueType.STRING, "y"))));
        for (final StructuredQuery query : misplaced) {
            assertThatThrownBy(() -> render(query, name -> "aaaa-01"))
                    .as("%s", query)
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("caller value 'caller.");
        }
    }
}
