package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.CallerValues;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.MaskFlag;
import com.epam.aidial.analytics.service.query.MaskRendering;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the builder renders a statement over masked fields (see {@code openspec/specs/structured-query/spec.md}, "A
 * masked field reads as absent at every reference" and "Masked inputs are reported per output cell"): the mask text
 * once per SELECT scope under an alias, one flag column per masked input context, the distinct collapse, the masked
 * expression in place of an exempt output's name, and the refusals a masked field brings.
 */
class MaskedStatementRenderTest {

    private static final String POLICY = "'P1'";

    private final DSLContext dsl = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expressions, new FilterTranslator(expressions));
    private final QueryEntityModel model = model();

    private static QueryEntityModel model() {
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name("t", "p"), String.class).eq("P1"));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("p", new QueryFieldBinding("p", DSL.field(DSL.name("t", "p"), String.class), QueryFieldType.STRING));
        bindings.put("secret", new QueryFieldBinding("secret", DSL.field(DSL.name("t", "secret"), String.class),
                QueryFieldType.STRING).masked(mask));
        bindings.put("n", new QueryFieldBinding("n", DSL.field(DSL.name("t", "n"), Long.class), QueryFieldType.LONG).masked(mask));
        bindings.put("tags", new QueryFieldBinding("tags", DSL.field(DSL.name("t", "tags")), QueryFieldType.ARRAY,
                QueryFieldType.STRING).masked(mask));
        bindings.put("labels", new QueryFieldBinding("labels", DSL.field(DSL.name("t", "labels")), QueryFieldType.ARRAY,
                QueryFieldType.STRING));
        return new QueryEntityModel("t", DSL.table(DSL.name("t")), bindings, List.of(), List.of());
    }

    @Test
    void rowProjectionDefinesTheMaskOncePerScopeAndProjectsItsFlag() {
        final SelectQuery<Record> select = build(row(List.of(column(new FieldExpr("secret")), column(new FieldExpr("p"))),
                new ComparisonNode(ComparisonOp.GT, List.of(new FieldExpr("n"), new ValueExpr(ValueType.INTEGER, "0")))));
        final String sql = render(select);

        assertThat(occurrences(sql, POLICY)).as(sql).isEqualTo(1);
        assertThat(sql).contains("AS \"" + MaskRendering.ALIAS_PREFIX + "0\")", "defaultValueOfArgumentType", "\"__adas_mask_0\"");
        final MaskFlag.Marks marks = MaskFlag.Marks.of(select.getSelect());
        assertThat(marks.outputs()).containsExactly("secret", "p");
        assertThat(marks.flags()).containsOnlyKeys("__adas_mask_0");
        assertThat(render(select)).as("a render is repeatable").isEqualTo(sql);
    }

    @Test
    void aggregateOutputIsFlaggedOverItsGroup() {
        final StructuredQuery query = new StructuredQuery("t", null, QueryMode.AGGREGATE, false,
                List.of(column(new FieldExpr("p")), new OutputColumn(new FnExpr("sum", false, List.of(new FieldExpr("n"))), "total")),
                List.of("p"), null, null, null);
        final String sql = render(build(query));

        assertThat(occurrences(sql, POLICY)).as(sql).isEqualTo(1);
        assertThat(sql).contains("max((NOT \"" + MaskRendering.ALIAS_PREFIX + "0\"))");
    }

    @Test
    void scopeReadingTheMaskOnlyInsideLambdaDefinesItInTheBody() {
        final Expr exists = new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("equals", false, List.of(new LambdaParamExpr("x"), new FieldExpr("secret")))),
                new FieldExpr("labels")));
        final String sql = render(build(row(List.of(column(new FieldExpr("p"))),
                new ComparisonNode(ComparisonOp.EQ, List.of(exists, new ValueExpr(ValueType.BOOLEAN, "true"))))));

        assertThat(occurrences(sql, POLICY)).as(sql).isEqualTo(1);
        assertThat(sql.indexOf(POLICY)).as("defined inside the lambda body").isGreaterThan(sql.indexOf("->"));
    }

    @Test
    void maskedFieldProjectedUnderItsOwnNameIsSortedByItsMaskedExpression() {
        final StructuredQuery query = new StructuredQuery("t", null, QueryMode.ROW, false, List.of(column(new FieldExpr("secret"))),
                null, null, List.of(new SortItem("secret", SortDir.ASC, null)), null);
        final String sql = render(build(query));
        final String order = sql.substring(sql.toLowerCase(Locale.ROOT).lastIndexOf("order by") + "order by".length()).trim();

        assertThat(order).as(sql).startsWith("if(").doesNotStartWith("\"secret\"");
    }

    @Test
    void distinctWithMarksGroupsByEveryOutputAndItsBareCompositeFlags() {
        final StructuredQuery query = new StructuredQuery("t", null, QueryMode.ROW, true,
                List.of(column(new FieldExpr("tags")), column(new FieldExpr("p"))), null, null, null, null);
        final SelectQuery<Record> select = build(query);
        final String sql = render(select).toLowerCase(Locale.ROOT);

        assertThat(sql).contains("group by").doesNotContain("select distinct");
        assertThat(MaskFlag.Marks.of(select.getSelect()).outputs()).containsExactly("tags", "p");
    }

    /** The window is computed in a derived table with its flags, and the distinct collapses above it, as SQL plans it. */
    @Test
    void distinctWithWindowOutputCollapsesAboveTheBlockComputingTheWindow() {
        final Expr total = new WindowExpr(new FnExpr("sum", false, List.of(new FieldExpr("n"))), List.of(new FieldExpr("p")), null, null);
        final StructuredQuery query = new StructuredQuery("t", null, QueryMode.ROW, true,
                List.of(column(new FieldExpr("p")), new OutputColumn(total, "total")), null, null,
                List.of(new SortItem("total", SortDir.DESC, null)), null);
        final SelectQuery<Record> select = build(query);
        final String sql = render(select).toLowerCase(Locale.ROOT);

        assertThat(sql).as(sql).contains("over (partition by", "\"__adas_mf_1\"", ") \"__adas_w\"", "group by \"__adas_w\".\"p\", \"__adas_w\".\"total\"",
                "max(\"__adas_w\".\"__adas_mf_1\")").doesNotContain("distinct");
        assertThat(sql.indexOf("over (")).as("the window is computed inside").isLessThan(sql.indexOf(") \"__adas_w\""));
        final MaskFlag.Marks marks = MaskFlag.Marks.of(select.getSelect());
        assertThat(marks.outputs()).containsExactly("p", "total");
        assertThat(marks.flags()).hasSize(1);
        assertThat(sql.substring(sql.lastIndexOf("order by"))).contains("desc").endsWith("limit 100");

        // An output named like the derived table would capture its column references in the scope that reads it.
        final StructuredQuery named = new StructuredQuery("t", null, QueryMode.ROW, true,
                List.of(new OutputColumn(new FieldExpr("p"), "__adas_w"), new OutputColumn(total, "total")), null, null, null, null);
        assertThat(render(build(named)).toLowerCase(Locale.ROOT)).contains(") \"__adas_w_\"", "\"__adas_w_\".\"__adas_w\"")
                .doesNotContain(") \"__adas_w\" ");
    }

    @Test
    void maskedFieldBringsItsRefusals() {
        assertThatThrownBy(() -> build(row(List.of(new OutputColumn(new FieldExpr("secret"), "t")), null)))
                .isInstanceOf(QueryValidationException.class).hasMessageContaining("'t'");
        assertThatThrownBy(() -> build(row(List.of(new OutputColumn(new FieldExpr("secret"), "t.x")), null)))
                .isInstanceOf(QueryValidationException.class);
        final Expr capturing = new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("t"), new FnExpr("equals", false, List.of(new LambdaParamExpr("t"), new FieldExpr("secret")))),
                new FieldExpr("labels")));
        assertThatThrownBy(() -> build(row(List.of(new OutputColumn(capturing, "e")), null)))
                .isInstanceOf(QueryValidationException.class).hasMessageContaining("lambda parameter 't'");
        assertThatThrownBy(() -> build(row(List.of(new OutputColumn(new FieldExpr("p"), MaskFlag.MEMBER)), null)))
                .as("reserved for every caller").isInstanceOf(QueryValidationException.class).hasMessageContaining("reserved");
        build(row(List.of(new OutputColumn(new FieldExpr("p"), "t")), null));
        build(row(List.of(new OutputColumn(new FieldExpr("p"), "$Masked")), null));
    }

    @Test
    void filterOnMaskedScalarComparesTheStoredColumnBehindItsMask() {
        final String greater = render(build(row(List.of(column(new FieldExpr("p"))),
                new ComparisonNode(ComparisonOp.GT, List.of(new FieldExpr("n"), new ValueExpr(ValueType.INTEGER, "0"))))));
        assertThat(greater).as(greater).contains("\"t\".\"n\" > 0");

        final String notNull = render(build(row(List.of(column(new FieldExpr("p"))),
                new ComparisonNode(ComparisonOp.NE, List.of(new FieldExpr("n"), new ValueExpr(ValueType.NULL, null))))));
        assertThat(notNull).as(notNull).contains("\"t\".\"n\" is not null");

        final String isNull = render(build(row(List.of(column(new FieldExpr("p"))),
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("n"), new ValueExpr(ValueType.NULL, null))))));
        assertThat(isNull).as("an absent cell satisfies is null, so it reads the masked value").doesNotContain("\"t\".\"n\" is null");

        final String orFilter = render(build(row(List.of(column(new FieldExpr("p"))), new LogicalNode(LogicalOp.OR, List.of(
                new ComparisonNode(ComparisonOp.GT, List.of(new FieldExpr("n"), new ValueExpr(ValueType.INTEGER, "0"))),
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("p"), new ValueExpr(ValueType.STRING, "P2"))))))));
        assertThat(orFilter).as("only a top-level conjunct takes the form").doesNotContain("\"t\".\"n\" > 0");

        final FilterNode callerProject = new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("secret"), new ParamExpr(CallerValues.PROJECT)));
        final String known = CallerValues.resolving(name -> "P9", () -> render(build(row(List.of(column(new FieldExpr("p"))), callerProject))));
        assertThat(known).as(known).contains("\"t\".\"secret\" = 'P9'");
        final String unknown = CallerValues.resolving(name -> null, () -> render(build(row(List.of(column(new FieldExpr("p"))), callerProject))));
        assertThat(unknown).as(unknown).contains("CAST(NULL, 'Nullable(UInt8)')").doesNotContain("\"t\".\"secret\" =");

        final FilterNode emptyAnd = new LogicalNode(LogicalOp.AND, List.of(
                new ComparisonNode(ComparisonOp.GT, List.of(new FieldExpr("n"), new ValueExpr(ValueType.INTEGER, "0"))),
                new LogicalNode(LogicalOp.AND, List.of())));
        assertThatThrownBy(() -> build(row(List.of(column(new FieldExpr("p"))), emptyAnd)))
                .as("an empty 'and' beside a rewritten conjunct is still refused").isInstanceOf(QueryValidationException.class);
    }

    /** Which conjuncts take the pruning form: none whose other operand holds a subquery, however deep. */
    @Test
    void pruningFormSkipsOperandsHoldingSubqueryOrNullItem() {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final Expr zero = new ValueExpr(ValueType.INTEGER, "0");
        final Expr nullItem = new ValueExpr(ValueType.NULL, null);
        for (final ComparisonOp op : List.of(ComparisonOp.EQ, ComparisonOp.NE, ComparisonOp.LT, ComparisonOp.LE, ComparisonOp.GT, ComparisonOp.GE)) {
            assertThat(FilterTranslator.prunable(compare(op, "n", zero), bindings)).as(op.code()).isNotNull();
        }
        for (final ComparisonOp op : List.of(ComparisonOp.CO, ComparisonOp.NC, ComparisonOp.ICO, ComparisonOp.INC)) {
            assertThat(FilterTranslator.prunable(compare(op, "secret", new ValueExpr(ValueType.STRING, "x")), bindings)).as(op.code()).isNotNull();
        }
        assertThat(FilterTranslator.prunable(compare(ComparisonOp.NE, "n", nullItem), bindings)).as("is not null").isNotNull();
        assertThat(FilterTranslator.prunable(compare(ComparisonOp.EQ, "n", nullItem), bindings)).as("is null").isNull();
        assertThat(FilterTranslator.prunable(compare(ComparisonOp.IN, "n", new ArrayExpr(List.of(zero, nullItem))), bindings)).isNull();
        assertThat(FilterTranslator.prunable(compare(ComparisonOp.IN, "n", new ArrayExpr(List.of(zero))), bindings)).isNotNull();
        assertThat(FilterTranslator.prunable(compare(ComparisonOp.GT, "p", zero), bindings)).as("not masked").isNull();
        assertThat(FilterTranslator.prunable(compare(ComparisonOp.EQ, "tags", zero), bindings)).as("not scalar").isNull();

        final Expr inLambda = new FnExpr("arrayCount", false, List.of(new LambdaExpr(List.of("x"), new ScalarSubqueryExpr(null)),
                new FieldExpr("labels")));
        final Expr inCase = new CaseExpr(List.of(new CaseBranch(new SubqueryNode(SubqueryNode.SubqueryKind.EXISTS, List.of(), null), zero)), zero);
        for (final Expr operand : List.of(new ScalarSubqueryExpr(null), inLambda, inCase)) {
            assertThat(FilterTranslator.prunable(compare(ComparisonOp.GT, "n", operand), bindings)).as(operand.toString()).isNull();
        }
        // The same shapes holding no subquery take the form: the subquery, not the shape, is what keeps them out.
        final Expr plainLambda = new FnExpr("arrayCount", false, List.of(new LambdaExpr(List.of("x"), new LambdaParamExpr("x")), new FieldExpr("labels")));
        final Expr plainCase = new CaseExpr(List.of(new CaseBranch(compare(ComparisonOp.EQ, "p", new ValueExpr(ValueType.STRING, "a")), zero)), zero);
        for (final Expr operand : List.of(plainLambda, plainCase)) {
            assertThat(FilterTranslator.prunable(compare(ComparisonOp.GT, "n", operand), bindings)).as(operand.toString()).isNotNull();
        }
    }

    private static FilterNode compare(ComparisonOp op, String field, Expr operand) {
        return new ComparisonNode(op, List.of(new FieldExpr(field), operand));
    }

    @Test
    void marksBecomeTheMaskedMemberAndLeaveTheRow() {
        final SelectQuery<Record> select = build(row(List.of(column(new FieldExpr("tags")), column(new FieldExpr("secret")),
                column(new FieldExpr("p"))), null));
        final MaskFlag.Marks marks = MaskFlag.Marks.of(select.getSelect());
        final String flag = marks.flags().keySet().iterator().next();
        final Map<String, Object> present = cells("tags", List.of("a"), "secret", "s", "p", "P1", flag, (short) 0);
        final Map<String, Object> absent = cells("tags", List.of(), "secret", null, "p", "P2", flag, (short) 1);
        final Map<String, Object> unknown = cells("tags", List.of(), "secret", null, "p", "P3", flag, null);

        marks.apply(new ArrayList<>(List.of(present, absent, unknown)));

        assertThat(present).containsOnlyKeys("tags", "secret", "p");
        assertThat(absent).containsEntry(MaskFlag.MEMBER, List.of("tags", "secret")).containsEntry("tags", null).doesNotContainKey(flag);
        assertThat(unknown).as("a null flag counts as absent").containsEntry(MaskFlag.MEMBER, List.of("tags", "secret"));
    }

    private SelectQuery<Record> build(StructuredQuery query) {
        return builder.build(dsl, model, query);
    }

    private static String render(SelectQuery<Record> select) {
        return MaskRendering.render(() -> select.getSQL(ParamType.INLINED));
    }

    private static StructuredQuery row(List<OutputColumn> select, FilterNode filter) {
        return new StructuredQuery("t", filter, QueryMode.ROW, false, select, null, null, null, null);
    }

    private static OutputColumn column(Expr expr) {
        return new OutputColumn(expr, null);
    }

    private static Map<String, Object> cells(Object... entries) {
        final Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            row.put((String) entries[i], entries[i + 1]);
        }
        return row;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
