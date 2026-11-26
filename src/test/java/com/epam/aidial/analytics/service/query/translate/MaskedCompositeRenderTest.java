package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.MaskFlag;
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
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.Relation;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a composite plan over a masked field carries marks (see {@code openspec/specs/structured-query/spec.md}, "Masked
 * inputs are reported per output cell"): each nested block projects a mark flag and a bare-absent flag per output, the
 * block reading it binds them, the root projects the statement's flags, and a plan over no masked field renders as before.
 */
class MaskedCompositeRenderTest {

    private static final RelationColumn P = new RelationColumn("p", QueryFieldType.STRING, ResultColumnKind.STRING);
    private static final RelationColumn SECRET = new RelationColumn("secret", QueryFieldType.STRING, ResultColumnKind.STRING);
    private static final RelationColumn N = new RelationColumn("n", QueryFieldType.LONG, ResultColumnKind.INT64);
    private static final RelationColumn TAGS = new RelationColumn("tags", QueryFieldType.ARRAY, QueryFieldType.STRING,
            ResultColumnKind.ARRAY, ResultColumnKind.STRING);
    private static final List<RelationColumn> T = List.of(P, SECRET, N, TAGS);

    private final DSLContext dsl = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final CompositeQueryBuilder composite =
            new CompositeQueryBuilder(new StructuredQueryBuilder(expressions, new FilterTranslator(expressions)));
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
        return new QueryEntityModel("t", DSL.table(DSL.name("t")), bindings, List.of(), List.of());
    }

    @Test
    void derivedTableCarriesItsOutputsMarksToTheRoot() {
        final CompositeQuery inner = leaf(List.of(new OutputColumn(new FieldExpr("secret"), "cost")), List.of(column("cost")));
        final SelectQuery<Record> select = build(over(new DerivedRelation(inner), "cost"));
        final String sql = render(select);

        assertThat(sql).contains("\"__adas_mf_0\"", "\"__adas_bf_0\"");
        final MaskFlag.Marks marks = MaskFlag.Marks.of(select.getSelect());
        assertThat(marks.outputs()).containsExactly("cost");
        assertThat(marks.flags()).isNotEmpty();
    }

    @Test
    void unionAllBranchesAlignTheirFlagsByPosition() {
        final CompositeQuery bare = leaf(List.of(new OutputColumn(new FieldExpr("tags"), "v")), List.of(TAGS_V));
        final CompositeQuery concat = leaf(List.of(new OutputColumn(new FnExpr("array_slice", false,
                List.of(new FieldExpr("tags"), new ValueExpr(ValueType.INTEGER, "1"), new ValueExpr(ValueType.INTEGER, "2"))), "v")),
                List.of(TAGS_V));
        final SelectQuery<Record> select = build(over(new SetOpRelation(SetOpKind.UNION, true, List.of(bare, concat), List.of(TAGS_V)),
                "v"));
        final String sql = render(select);

        assertThat(occurrences(sql, "\"__adas_mf_0\"")).as(sql).isGreaterThanOrEqualTo(2);
        assertThat(sql.toLowerCase(Locale.ROOT)).contains("union all");
    }

    @Test
    void unionDistinctOverMarksIsGroupedInFirstOccurrenceOrder() {
        final CompositeQuery left = leaf(List.of(new OutputColumn(new FieldExpr("secret"), "v")), List.of(column("v")));
        final CompositeQuery right = leaf(List.of(new OutputColumn(new FieldExpr("secret"), "v")), List.of(column("v")));
        final String sql = render(build(over(new SetOpRelation(SetOpKind.UNION, false, List.of(left, right), List.of(column("v"))),
                "v"))).toLowerCase(Locale.ROOT);

        assertThat(sql).contains("union all", "rownumberinallblocks()", "group by", "min((").doesNotContain("union distinct");
    }

    /**
     * In a plan that reads a masked field only in a filter, a union whose outputs cannot be marked keeps its plain form, and
     * an intersect's branches project no flags, which it would compare with the values.
     */
    @Test
    void setOperationsOverUnmarkableOutputsKeepTheirPlainForm() {
        final CompositeQuery filtered = new CompositeQuery(new EntityRelation("t", T), block(List.of(new OutputColumn(new FieldExpr("p"), "v")),
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("secret"), new ValueExpr(ValueType.STRING, "x")))),
                List.of(column("v")));
        final CompositeQuery plain = leaf(List.of(new OutputColumn(new FieldExpr("p"), "v")), List.of(column("v")));

        final String union = render(build(over(new SetOpRelation(SetOpKind.UNION, false, List.of(filtered, plain), List.of(column("v"))),
                "v"))).toLowerCase(Locale.ROOT);
        assertThat(union).as(union).contains("union distinct").doesNotContain("rownumberinallblocks()");

        final String intersect = render(build(over(new SetOpRelation(SetOpKind.INTERSECT, false, List.of(filtered, plain),
                List.of(column("v"))), "v"))).toLowerCase(Locale.ROOT);
        assertThat(intersect).as(intersect).contains("intersect distinct").doesNotContain("__adas_mf_", "__adas_bf_");
    }

    @Test
    void outerJoinOperandFlagsAreReadAsZeroWhereUnmatched() {
        final RelationColumn left = column("lp");
        final RelationColumn secret = column("s");
        final JoinRelation join = new JoinRelation(new EntityRelation("t", T), new EntityRelation("t", T), JoinKind.LEFT,
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("lp"), new FieldExpr("rp"))),
                List.of(new JoinRelation.JoinColumn(left, true, "p"), new JoinRelation.JoinColumn(column("rp"), false, "p"),
                        new JoinRelation.JoinColumn(secret, false, "secret")));
        final String sql = render(build(new CompositeQuery(join, block(List.of(new OutputColumn(new FieldExpr("lp"), null),
                new OutputColumn(new FieldExpr("s"), null)), null), List.of(left, secret))));

        assertThat(sql).contains("ifNull(\"t1\".\"__adas_mf_");
    }

    @Test
    void scalarSubqueryReturnsItsValueWithItsMark() {
        final CompositeQuery total = new CompositeQuery(new EntityRelation("t", T), new StructuredQuery("t", null, QueryMode.AGGREGATE,
                false, List.of(new OutputColumn(new FnExpr("sum", false, List.of(new FieldExpr("n"))), "total")), List.of(), null,
                null, null), List.of(new RelationColumn("total", QueryFieldType.LONG, ResultColumnKind.INT64)));
        final Expr share = new FnExpr("div", false, List.of(new FnExpr("sum", false, List.of(new FieldExpr("n"))),
                new ScalarSubqueryExpr(total)));
        final CompositeQuery root = new CompositeQuery(new EntityRelation("t", T), new StructuredQuery("t", null, QueryMode.AGGREGATE,
                false, List.of(new OutputColumn(new FieldExpr("p"), null), new OutputColumn(share, "share")), List.of("p"), null, null,
                null), List.of(P, column("share")));
        final SelectQuery<Record> select = build(root);
        final String sql = render(select);

        assertThat(sql).contains("tupleElement(", "tuple(");
        assertThat(MaskFlag.Marks.of(select.getSelect()).outputs()).containsExactly("p", "share");
    }

    /** Two equal scalar subqueries are rendered and evaluated apart, so each output is marked by its own one's flag. */
    @Test
    void equalScalarSubqueriesEachMarkFromTheirOwnFlag() {
        final List<OutputColumn> outputs = List.of(new OutputColumn(new ScalarSubqueryExpr(highest()), "a"),
                new OutputColumn(new ScalarSubqueryExpr(highest()), "b"));
        final SelectQuery<Record> select = build(new CompositeQuery(new EntityRelation("t", T), block(outputs, null),
                List.of(column("a"), column("b"))));

        assertThat(MaskFlag.Marks.of(select.getSelect()).flags()).as(render(select)).hasSize(2);
    }

    private static CompositeQuery highest() {
        return new CompositeQuery(new EntityRelation("t", T), new StructuredQuery("t", null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(new FnExpr("max", false, List.of(new FieldExpr("secret"))), "top")), List.of(), null, null, null),
                List.of(column("top")));
    }

    /** A declared name led by an operand alias and '.' could capture that operand's flag columns, so the alias avoids it. */
    @Test
    void operandAliasAvoidsDeclaredNameLedByIt() {
        final CompositeQuery inner = leaf(List.of(new OutputColumn(new FieldExpr("secret"), "cost")), List.of(column("cost")));
        final String plain = render(build(over(new DerivedRelation(inner), "cost")));
        assertThat(plain).as("the operand is t0 when nothing is in the way").contains("\"t0\".\"__adas_mf_0\"");

        final CompositeQuery root = new CompositeQuery(new DerivedRelation(inner), block(List.of(new OutputColumn(new FieldExpr("cost"), null),
                new OutputColumn(new ValueExpr(ValueType.INTEGER, "0"), "t0.__adas_mf_0")), null), List.of(column("cost"), column("t0.__adas_mf_0")));
        final String sql = render(build(root));
        assertThat(sql).as(sql).contains("\"__adas_mf_0\"").doesNotContain("\"t0\".");
    }

    /** A join on a masked key reduces neither side: the key set would hold the stored values of absent cells. */
    @Test
    void joinOnMaskedKeyIsNotReduced() {
        final List<JoinRelation.JoinColumn> columns = List.of(new JoinRelation.JoinColumn(column("lp"), true, "p"),
                new JoinRelation.JoinColumn(column("ls"), true, "secret"), new JoinRelation.JoinColumn(column("rp"), false, "p"),
                new JoinRelation.JoinColumn(column("rs"), false, "secret"));
        final FilterNode bounded = new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("rp"), new ValueExpr(ValueType.STRING, "x")));
        final List<OutputColumn> select = List.of(new OutputColumn(new FieldExpr("lp"), null));

        final String plain = render(build(new CompositeQuery(new JoinRelation(new EntityRelation("t", T), new EntityRelation("t", T),
                JoinKind.INNER, new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("lp"), new FieldExpr("rp"))), columns),
                block(select, bounded), List.of(column("lp"))))).toLowerCase(Locale.ROOT);
        assertThat(plain).as("a join on a visible key is reduced: " + plain).contains(" in (select ");

        final String masked = render(build(new CompositeQuery(new JoinRelation(new EntityRelation("t", T), new EntityRelation("t", T),
                JoinKind.INNER, new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("ls"), new FieldExpr("rs"))), columns),
                block(select, bounded), List.of(column("lp"))))).toLowerCase(Locale.ROOT);
        assertThat(masked).as(masked).doesNotContain(" in (select ");
    }

    @Test
    void planOverNoMaskedFieldProjectsNoFlag() {
        final CompositeQuery inner = leaf(List.of(new OutputColumn(new FieldExpr("p"), "v")), List.of(column("v")));
        final String sql = render(build(over(new DerivedRelation(inner), "v")));

        assertThat(sql).doesNotContain("__adas_");
    }

    private static final RelationColumn TAGS_V = new RelationColumn("v", QueryFieldType.ARRAY, QueryFieldType.STRING,
            ResultColumnKind.ARRAY, ResultColumnKind.STRING);

    private SelectQuery<Record> build(CompositeQuery root) {
        return composite.build(dsl, root, entity -> model, 1000);
    }

    private static String render(SelectQuery<Record> select) {
        return MaskRendering.render(() -> select.getSQL(ParamType.INLINED));
    }

    private static CompositeQuery leaf(List<OutputColumn> select, List<RelationColumn> columns) {
        return new CompositeQuery(new EntityRelation("t", T), block(select, null), columns);
    }

    private static CompositeQuery over(Relation source, String column) {
        return new CompositeQuery(source, block(List.of(new OutputColumn(new FieldExpr(column), null)), null),
                List.of(source.columns().getFirst()));
    }

    private static StructuredQuery block(List<OutputColumn> select, FilterNode filter) {
        return new StructuredQuery("t", filter, QueryMode.ROW, false, select, null, null, null, null);
    }

    private static RelationColumn column(String name) {
        return new RelationColumn(name, QueryFieldType.STRING, ResultColumnKind.STRING);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
