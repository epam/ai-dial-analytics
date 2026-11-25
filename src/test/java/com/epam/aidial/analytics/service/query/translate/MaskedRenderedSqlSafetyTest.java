package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.MaskRendering;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.JoinKind;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.Select;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendered-SQL safety check of masking (see {@code openspec/specs/structured-query/spec.md}, "A masked field reads as
 * absent at every reference"): for every clause shape, single-entity and composite, each occurrence of a masked column's
 * physical name is inside its mask or a conjunct in the pruning form ({@link MaskedSqlAudit}). The enrichment shapes,
 * whose derived tables read stored values on purpose, are audited in {@code ReadShapingRenderTest}.
 */
class MaskedRenderedSqlSafetyTest {

    private static final String P1 = "\"t\".\"p\" = 'P1'";
    private static final String P2 = "\"t\".\"p\" = 'P2'";
    // Two masks, so a read behind another column's mask is told apart from one behind its own.
    private static final List<MaskedSqlAudit.Masked> MASKED = List.of(MaskedSqlAudit.Masked.of("\"t\".\"secret\"", P1, MaskedSqlAudit.Kind.SCALAR),
            MaskedSqlAudit.Masked.of("\"t\".\"n\"", P2, MaskedSqlAudit.Kind.SCALAR),
            MaskedSqlAudit.Masked.of("\"t\".\"tags\"", P1, MaskedSqlAudit.Kind.COMPOSITE),
            MaskedSqlAudit.Masked.of("\"t\".\"meta\"", P1, MaskedSqlAudit.Kind.OBJECT));
    private static final List<String> PHYSICAL = List.of("\"t\".\"secret\"", "\"t\".\"n\"", "\"t\".\"tags\"", "\"t\".\"meta\"");
    private static final List<RelationColumn> T = List.of(new RelationColumn("p", QueryFieldType.STRING, ResultColumnKind.STRING),
            new RelationColumn("secret", QueryFieldType.STRING, ResultColumnKind.STRING),
            new RelationColumn("n", QueryFieldType.LONG, ResultColumnKind.INT64),
            new RelationColumn("tags", QueryFieldType.ARRAY, QueryFieldType.STRING, ResultColumnKind.ARRAY, ResultColumnKind.STRING),
            new RelationColumn("labels", QueryFieldType.ARRAY, QueryFieldType.STRING, ResultColumnKind.ARRAY, ResultColumnKind.STRING));

    private final DSLContext dsl = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expressions, new FilterTranslator(expressions));
    private final CompositeQueryBuilder composite = new CompositeQueryBuilder(builder);
    private final QueryEntityModel model = model();

    private static QueryEntityModel model() {
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name("t", "p"), String.class).eq("P1"));
        final ColumnMask other = ColumnMask.of(DSL.field(DSL.name("t", "p"), String.class).eq("P2"));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("p", new QueryFieldBinding("p", DSL.field(DSL.name("t", "p"), String.class), QueryFieldType.STRING));
        bindings.put("secret", new QueryFieldBinding("secret", DSL.field(DSL.name("t", "secret"), String.class), QueryFieldType.STRING)
                .masked(mask));
        bindings.put("n", new QueryFieldBinding("n", DSL.field(DSL.name("t", "n"), Long.class), QueryFieldType.LONG).masked(other));
        bindings.put("tags", new QueryFieldBinding("tags", DSL.field(DSL.name("t", "tags")), QueryFieldType.ARRAY, QueryFieldType.STRING)
                .masked(mask));
        bindings.put("labels", new QueryFieldBinding("labels", DSL.field(DSL.name("t", "labels")), QueryFieldType.ARRAY,
                QueryFieldType.STRING));
        bindings.put("meta", new QueryFieldBinding("meta", DSL.field(DSL.name("t", "meta")), QueryFieldType.OBJECT).masked(mask));
        return new QueryEntityModel("t", DSL.table(DSL.name("t")), bindings, List.of(), List.of());
    }

    @Test
    void singleEntityShapesReadMaskedColumnsOnlyBehindTheirMask() {
        final Map<String, Supplier<String>> shapes = new LinkedHashMap<>();
        shapes.put("projection", () -> render(row(List.of(out(field("secret")), out(field("n")), out(field("tags")), out(field("p"))), null)));
        shapes.put("wildcard", () -> render(row(List.of(), null)));
        shapes.put("filter, rewritten", () -> render(row(List.of(out(field("p"))),
                and(cmp(ComparisonOp.GT, field("n"), number("0")), cmp(ComparisonOp.EQ, field("secret"), str("x"))))));
        shapes.put("filter, not rewritten", () -> render(row(List.of(out(field("p"))), new LogicalNode(LogicalOp.OR,
                List.of(cmp(ComparisonOp.EQ, field("n"), new ValueExpr(ValueType.NULL, null)), cmp(ComparisonOp.EQ, field("p"), str("P2")))))));
        shapes.put("group and having", () -> render(new StructuredQuery("t", null, QueryMode.AGGREGATE, false,
                List.of(out(field("secret")), new OutputColumn(sum(field("n")), "total")), List.of("secret"),
                and(cmp(ComparisonOp.GT, field("total"), number("0")), cmp(ComparisonOp.LT, new FnExpr("max", false, List.of(field("n"))),
                        number("9"))), null, null)));
        shapes.put("order", () -> render(new StructuredQuery("t", null, QueryMode.ROW, false, List.of(out(field("p"))), null, null,
                List.of(new SortItem("n", SortDir.DESC, null), new SortItem("secret", SortDir.ASC, null)), new OffsetPage(0, 10, false))));
        shapes.put("window", () -> render(row(List.of(out(field("p")), new OutputColumn(new WindowExpr(sum(field("n")), List.of(field("secret")),
                List.of(new WindowOrder(field("n"), SortDir.DESC, null)), null), "running")), null)));
        shapes.put("distinct", () -> render(new StructuredQuery("t", null, QueryMode.ROW, true, List.of(out(field("secret")), out(field("tags"))),
                null, null, null, null)));
        shapes.put("distinct with a window", () -> render(new StructuredQuery("t", null, QueryMode.ROW, true, List.of(out(field("p")),
                new OutputColumn(new WindowExpr(sum(field("n")), List.of(field("p")), null, null), "total")), null, null, null, null)));
        shapes.put("array expansion", () -> render(row(List.of(new OutputColumn(new FnExpr("array_join", false, List.of(field("tags"))), "tag")),
                null)));
        shapes.put("object", () -> render(row(List.of(out(field("meta")), new OutputColumn(new FnExpr("json_extract_string", false,
                List.of(field("meta"), str("email"))), "email")), cmp(ComparisonOp.EQ, new FnExpr("json_extract_string", false,
                List.of(field("meta"), str("team"))), str("x")))));
        shapes.put("lambda", () -> render(row(List.of(new OutputColumn(new FnExpr("arrayExists", false, List.of(new LambdaExpr(List.of("x"),
                new FnExpr("equals", false, List.of(new LambdaParamExpr("x"), field("secret")))), field("labels"))), "seen")), null)));
        shapes.put("count", () -> {
            final Select<?> count = builder.buildCount(dsl, model, row(List.of(out(field("p"))), cmp(ComparisonOp.GT, field("n"), number("0"))));
            return MaskRendering.render(() -> count.getSQL(ParamType.INLINED));
        });

        audit(shapes);
    }

    @Test
    void compositeShapesReadMaskedColumnsOnlyBehindTheirMask() {
        final RelationColumn v = new RelationColumn("v", QueryFieldType.STRING, ResultColumnKind.STRING);
        final Map<String, Supplier<String>> shapes = new LinkedHashMap<>();
        shapes.put("composite leaf with a rewritten filter", () -> render(new CompositeQuery(new DerivedRelation(leaf(out(field("secret"), "v"),
                cmp(ComparisonOp.EQ, field("secret"), str("x")))), block(List.of(out(field("v"))), null), List.of(v))));
        shapes.put("join", () -> {
            final RelationColumn left = new RelationColumn("lp", QueryFieldType.STRING, ResultColumnKind.STRING);
            final RelationColumn secret = new RelationColumn("s", QueryFieldType.STRING, ResultColumnKind.STRING);
            // A masked field on each side of the ON.
            final JoinRelation join = new JoinRelation(new EntityRelation("t", T), new EntityRelation("t", T), JoinKind.LEFT,
                    and(cmp(ComparisonOp.EQ, field("lp"), field("rp")), cmp(ComparisonOp.EQ, field("ls"), field("s"))),
                    List.of(new JoinRelation.JoinColumn(left, true, "p"), new JoinRelation.JoinColumn(
                            new RelationColumn("rp", QueryFieldType.STRING, ResultColumnKind.STRING), false, "p"),
                            new JoinRelation.JoinColumn(new RelationColumn("ls", QueryFieldType.STRING, ResultColumnKind.STRING), true, "secret"),
                            new JoinRelation.JoinColumn(secret, false, "secret")));
            return render(new CompositeQuery(join, block(List.of(out(field("lp")), out(field("s"))), null), List.of(left, secret)));
        });
        for (final boolean all : List.of(true, false)) {
            shapes.put(all ? "union all" : "union", () -> render(new CompositeQuery(new SetOpRelation(SetOpKind.UNION, all,
                    List.of(leaf(out(field("secret"), "v"), null), leaf(out(field("p"), "v"), null)), List.of(v)),
                    block(List.of(out(field("v"))), null), List.of(v))));
        }
        shapes.put("scalar subquery", () -> {
            final CompositeQuery total = new CompositeQuery(new EntityRelation("t", T), new StructuredQuery("t", null, QueryMode.AGGREGATE,
                    false, List.of(new OutputColumn(sum(field("n")), "total")), List.of(), null, null, null),
                    List.of(new RelationColumn("total", QueryFieldType.LONG, ResultColumnKind.INT64)));
            return render(new CompositeQuery(new EntityRelation("t", T), new StructuredQuery("t", null, QueryMode.ROW, false,
                    List.of(out(field("p")), new OutputColumn(new ScalarSubqueryExpr(total), "total")), null, null, null, null),
                    List.of(T.getFirst(), new RelationColumn("total", QueryFieldType.LONG, ResultColumnKind.INT64))));
        });

        audit(shapes);
    }

    /** Each shape reads a masked column, so its audit is not vacuous, and reads it only behind its mask. */
    private static void audit(Map<String, Supplier<String>> shapes) {
        shapes.forEach((shape, sql) -> {
            final String rendered = sql.get();
            assertThat(PHYSICAL.stream().anyMatch(rendered::contains)).as(shape + " reads a masked column: " + rendered).isTrue();
            assertThat(MaskedSqlAudit.unguarded(rendered, MASKED)).as(shape + ": " + rendered).isEmpty();
        });
    }

    @Test
    void auditFindsTheStoredValueReadOutsideItsOwnMask() {
        assertThat(MaskedSqlAudit.unguarded("select \"t\".\"secret\" from \"t\"", MASKED)).hasSize(1);
        assertThat(MaskedSqlAudit.unguarded(render(row(List.of(out(field("secret"))), null)) + " where \"t\".\"n\" > 0", MASKED))
                .singleElement().asString().contains("where \"t\".\"n\"");
        // A raw read before the definition a guarded conjunct opens is not folded into it.
        assertThat(MaskedSqlAudit.unguarded("select toBool(ifNull(has(\"t\".\"tags\", 'x'), false)) \"seen\" from \"t\" where ((ifNull(" + P2
                + ", 0) AS \"__adas_mcond_0\") and \"t\".\"n\" > 0)", MASKED)).singleElement().asString().contains("has(\"t\".\"tags\"");
        // Behind another column's mask.
        assertThat(MaskedSqlAudit.unguarded("select if((ifNull(" + P2 + ", 0) AS \"__adas_mcond_0\"), if(\"__adas_mcond_0\", \"t\".\"secret\","
                + " defaultValueOfArgumentType(assumeNotNull(\"t\".\"secret\"))), NULL) from \"t\"", MASKED)).hasSize(2);
        // Under a negated mask, which admits exactly the rows it should reject.
        assertThat(MaskedSqlAudit.unguarded("select 1 from \"t\" where (not (ifNull(" + P2 + ", 0) AS \"__adas_mcond_0\") and \"t\".\"n\" > 0)",
                MASKED)).hasSize(1);
        // A scalar behind a single if: assumeNotNull reads the stored value under the NULL.
        assertThat(MaskedSqlAudit.unguarded("select assumeNotNull(if((ifNull(" + P2 + ", 0) AS \"__adas_mcond_0\"), \"t\".\"n\", NULL)) from \"t\"",
                MASKED)).hasSize(1);
        // A conjunct that does not compare the stored column on its own.
        assertThat(MaskedSqlAudit.unguarded("select 1 from \"t\" where ((ifNull(" + P2 + ", 0) AS \"__adas_mcond_0\") and \"t\".\"n\" % 0 = 1)",
                MASKED)).hasSize(1);
    }

    @Test
    void rewrittenFilterTakesThePruningForm() {
        final String sql = render(row(List.of(out(field("p"))), cmp(ComparisonOp.GT, field("n"), number("0"))));
        assertThat(sql).as("the audit's <guarded> case is exercised").containsPattern("\"__adas_mcond_\\d+\"\\)? and \"t\"\\.\"n\" > 0");
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** Built once and rendered by MaskRendering's two passes, as the executor renders a statement. */
    private String render(StructuredQuery query) {
        final Select<?> select = builder.build(dsl, model, query);
        return MaskRendering.render(() -> select.getSQL(ParamType.INLINED));
    }

    private String render(CompositeQuery query) {
        final Select<?> select = composite.build(dsl, query, entity -> model, 1000);
        return MaskRendering.render(() -> select.getSQL(ParamType.INLINED));
    }

    private static CompositeQuery leaf(OutputColumn output, FilterNode filter) {
        return new CompositeQuery(new EntityRelation("t", T), block(List.of(output), filter),
                List.of(new RelationColumn("v", QueryFieldType.STRING, ResultColumnKind.STRING)));
    }

    private static StructuredQuery block(List<OutputColumn> select, FilterNode filter) {
        return new StructuredQuery("t", filter, QueryMode.ROW, false, select, null, null, null, null);
    }

    private static StructuredQuery row(List<OutputColumn> select, FilterNode filter) {
        return block(select, filter);
    }

    private static OutputColumn out(Expr expr) {
        return new OutputColumn(expr, null);
    }

    private static OutputColumn out(Expr expr, String as) {
        return new OutputColumn(expr, as);
    }

    private static FieldExpr field(String name) {
        return new FieldExpr(name);
    }

    private static FnExpr sum(Expr arg) {
        return new FnExpr("sum", false, List.of(arg));
    }

    private static FilterNode cmp(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    private static FilterNode and(FilterNode... args) {
        return new LogicalNode(LogicalOp.AND, List.of(args));
    }

    private static ValueExpr number(String value) {
        return new ValueExpr(ValueType.LONG, value);
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }
}
