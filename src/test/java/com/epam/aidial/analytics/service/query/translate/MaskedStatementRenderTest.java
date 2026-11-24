package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.MaskFlag;
import com.epam.aidial.analytics.service.query.MaskRendering;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
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
    void aRowProjectionDefinesTheMaskOncePerScopeAndProjectsItsFlag() {
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
    void anAggregateOutputIsFlaggedOverItsGroup() {
        final StructuredQuery query = new StructuredQuery("t", null, QueryMode.AGGREGATE, false,
                List.of(column(new FieldExpr("p")), new OutputColumn(new FnExpr("sum", false, List.of(new FieldExpr("n"))), "total")),
                List.of("p"), null, null, null);
        final String sql = render(build(query));

        assertThat(occurrences(sql, POLICY)).as(sql).isEqualTo(1);
        assertThat(sql).contains("max((NOT \"" + MaskRendering.ALIAS_PREFIX + "0\"))");
    }

    @Test
    void aScopeReadingTheMaskOnlyInsideALambdaDefinesItInTheBody() {
        final Expr exists = new FnExpr("arrayExists", false, List.of(
                new LambdaExpr(List.of("x"), new FnExpr("equals", false, List.of(new LambdaParamExpr("x"), new FieldExpr("secret")))),
                new FieldExpr("labels")));
        final String sql = render(build(row(List.of(column(new FieldExpr("p"))),
                new ComparisonNode(ComparisonOp.EQ, List.of(exists, new ValueExpr(ValueType.BOOLEAN, "true"))))));

        assertThat(occurrences(sql, POLICY)).as(sql).isEqualTo(1);
        assertThat(sql.indexOf(POLICY)).as("defined inside the lambda body").isGreaterThan(sql.indexOf("->"));
    }

    @Test
    void aMaskedFieldProjectedUnderItsOwnNameIsSortedByItsMaskedExpression() {
        final StructuredQuery query = new StructuredQuery("t", null, QueryMode.ROW, false, List.of(column(new FieldExpr("secret"))),
                null, null, List.of(new SortItem("secret", SortDir.ASC, null)), null);
        final String sql = render(build(query));
        final String order = sql.substring(sql.toLowerCase(Locale.ROOT).lastIndexOf("order by") + "order by".length()).trim();

        assertThat(order).as(sql).startsWith("if(").doesNotStartWith("\"secret\"");
    }

    @Test
    void aDistinctWithMarksGroupsByEveryOutputAndItsBareCompositeFlags() {
        final StructuredQuery query = new StructuredQuery("t", null, QueryMode.ROW, true,
                List.of(column(new FieldExpr("tags")), column(new FieldExpr("p"))), null, null, null, null);
        final SelectQuery<Record> select = build(query);
        final String sql = render(select).toLowerCase(Locale.ROOT);

        assertThat(sql).contains("group by").doesNotContain("select distinct");
        assertThat(MaskFlag.Marks.of(select.getSelect()).outputs()).containsExactly("tags", "p");
    }

    @Test
    void aMaskedFieldBringsItsRefusals() {
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
