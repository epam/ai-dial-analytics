package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.MaskFlag;
import com.epam.aidial.analytics.service.query.MaskRendering;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.NullsOrder;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.service.query.translate.CompositeQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Select;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Masked statements as ClickHouse executes them (see {@code openspec/specs/structured-query/spec.md}, "A masked field reads
 * as absent at every reference" and "Masked inputs are reported per output cell"), built by the query builder over
 * hand-built models of probe tables and run on the test container, with the marks applied as the executor applies them.
 * No service context is started.
 */
class MaskedExecutionIntegrationTest {

    private static final String VALUES = "mask_exec_probe";
    private static final String SOURCE = "mask_exec_src";
    private static final String PAYLOAD = "mask_exec_payload";
    private static final String BODY = PAYLOAD + ".body";
    private static final String ARRAYS = "mask_exec_arrays";
    private static final String WIDE = "mask_exec_wide";
    private static final int WIDE_COLUMNS = 16;

    private final DSLContext dsl = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expressions, new FilterTranslator(expressions));
    private final CompositeQueryBuilder composite = new CompositeQueryBuilder(builder);

    @BeforeAll
    static void createProbes() {
        dropProbes();
        // The mask admits the rows of P1. kA and kB are absent and store different values; kC is present and stores null.
        ClickHouseProbe.execute("CREATE TABLE " + VALUES + " (k String, p String, n Nullable(Int64), opts Array(Int64) DEFAULT [1, 2, 0, 5])"
                + " ENGINE = ReplicatedMergeTree ORDER BY k");
        ClickHouseProbe.execute("INSERT INTO " + VALUES + " (k, p, n) VALUES ('k1', 'P1', 1), ('k2', 'P1', 2), ('kA', 'P2', 0), ('kB', 'P2', 5),"
                + " ('kC', 'P1', NULL)");
        ClickHouseProbe.execute("CREATE TABLE " + SOURCE + " (event_id String, request_time DateTime64(3, 'UTC'), deployment String)"
                + " ENGINE = ReplicatedReplacingMergeTree ORDER BY (event_id, request_time)");
        ClickHouseProbe.execute("CREATE TABLE " + PAYLOAD + " (event_id String, request_time DateTime64(3, 'UTC'), body String)"
                + " ENGINE = ReplicatedReplacingMergeTree ORDER BY (event_id, request_time)");
        ClickHouseProbe.execute("INSERT INTO " + SOURCE + " VALUES ('e1', '2026-11-01 00:00:00', 'd1'), ('e2', '2026-11-01 00:00:00', 'd2'),"
                + " ('e3', '2026-11-01 00:00:00', 'd1'), ('e4', '2026-11-01 00:00:00', 'd2')");
        // e2 and e4 are absent and store different bodies; e4's sorts before every present one.
        ClickHouseProbe.execute("INSERT INTO " + PAYLOAD + " VALUES ('e1', '2026-11-01 00:00:00', 'has an error'),"
                + " ('e2', '2026-11-01 00:00:00', 'has an error'), ('e3', '2026-11-01 00:00:00', 'fine'),"
                + " ('e4', '2026-11-01 00:00:00', 'ERROR here')");
        // a3 and a4 are absent; a2 is present and stores the empty array.
        ClickHouseProbe.execute("CREATE TABLE " + ARRAYS + " (k String, p String, tags Array(String)) ENGINE = ReplicatedMergeTree ORDER BY k");
        ClickHouseProbe.execute("INSERT INTO " + ARRAYS + " VALUES ('a1', 'P1', ['a']), ('a2', 'P1', []), ('a3', 'P2', ['z']), ('a4', 'P2', [])");
        final StringBuilder wide = new StringBuilder();
        for (int i = 0; i < WIDE_COLUMNS; i++) {
            wide.append(", c").append(i).append(" Nullable(String)");
        }
        ClickHouseProbe.execute("CREATE TABLE " + WIDE + " (k String, p String, labels Array(String)" + wide + ") ENGINE = ReplicatedMergeTree ORDER BY k");
        ClickHouseProbe.execute("INSERT INTO " + WIDE + " SELECT 'w' || toString(number), if(number = 0, 'P0001', 'Q'), ['v'],"
                + " 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v', 'v' FROM numbers(2)");
    }

    @AfterAll
    static void dropProbes() {
        for (final String table : List.of(VALUES, SOURCE, PAYLOAD, ARRAYS, WIDE)) {
            ClickHouseProbe.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    /**
     * An absent scalar cell is a stored null of its type in every value and error computed from it: a function failing
     * for particular values ({@code widthBucket}'s count) and one reading a null's underlying value ({@code assumeNotNull})
     * give one outcome over the absent cells storing {@code 0} and {@code 5} and the present one storing null.
     */
    @Test
    void absentScalarCellComputesAsStoredNullWhateverItStores() {
        final Field<?> n = valuesModel().bindings().get("n").value();
        for (final Field<?> expression : List.<Field<?>>of(DSL.field("widthBucket(1, 0, 10, toUInt64({0}))", Long.class, n),
                DSL.field("assumeNotNull({0})", Long.class, n))) {
            final List<String> outcomes = new ArrayList<>();
            for (final String key : List.of("kA", "kB", "kC")) {
                final Select<?> select = dsl.select(expression.as("v")).from(DSL.table(DSL.name(VALUES)))
                        .where(DSL.field(DSL.name(VALUES, "k"), String.class).eq(key));
                outcomes.add(outcome(MaskRendering.render(() -> select.getSQL(ParamType.INLINED))));
            }
            assertThat(outcomes).as(expression.toString()).containsOnly(outcomes.getFirst());
        }
    }

    /**
     * "A ranking window with a narrow frame is marked by the rows ahead of it": {@code row_number()} over {@code n} nulls
     * first with a one-row frame ranks every present row behind the absent one, so each is marked; a {@code max} over
     * the same one-row frame reads its own row only, so a present row's is not.
     */
    @Test
    void rankingWindowWithOneRowFrameIsMarkedByTheAbsentRowAhead() {
        final WindowFrame current = new WindowFrame(FrameUnit.ROWS, new FrameBound(FrameBoundKind.CURRENT_ROW, null),
                new FrameBound(FrameBoundKind.CURRENT_ROW, null));
        final List<WindowOrder> byN = List.of(new WindowOrder(field("n"), SortDir.ASC, NullsOrder.FIRST));
        final StructuredQuery query = new StructuredQuery(VALUES, cmp(ComparisonOp.IN, field("k"), strings("k1", "k2", "kA")), QueryMode.ROW,
                false, List.of(new OutputColumn(field("k"), null),
                        new OutputColumn(new WindowExpr(new FnExpr("row_number", false, List.of()), null, byN, current), "rn"),
                        new OutputColumn(new WindowExpr(new FnExpr("max", false, List.of(field("n"))), null, byN, current), "top")),
                null, null, List.of(new SortItem("k", SortDir.ASC, null)), new OffsetPage(0, 10, false));

        final Map<String, Map<String, Object>> rows = byKey(run(builder.build(dsl, valuesModel(), query)));
        for (final String present : List.of("k1", "k2")) {
            assertThat(rows.get(present).get(MaskFlag.MEMBER)).as(present).isEqualTo(List.of("rn"));
        }
        assertThat(rows.get("kA").get(MaskFlag.MEMBER)).isEqualTo(List.of("rn", "top"));
        assertThat(((Number) rows.get("kA").get("rn")).longValue()).as("the absent row sorts first").isEqualTo(1L);
    }

    /**
     * "A distinct projection with a window output over a masked field runs" through the DSL: one row per project, the
     * {@code P1} sum present and unmarked, the {@code P2} sum null and marked.
     */
    @Test
    void distinctWithWindowOutputReturnsOneMarkedRowPerProject() {
        final StructuredQuery query = new StructuredQuery(VALUES, cmp(ComparisonOp.NE, field("k"), str("kC")), QueryMode.ROW, true,
                List.of(new OutputColumn(field("p"), null), new OutputColumn(new WindowExpr(new FnExpr("sum", false, List.of(field("n"))),
                        List.of(field("p")), null, null), "total")),
                null, null, List.of(new SortItem("p", SortDir.ASC, null)), new OffsetPage(0, 10, false));

        final List<Map<String, Object>> rows = run(builder.build(dsl, valuesModel(), query));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("p", "P1").doesNotContainKey(MaskFlag.MEMBER);
        assertThat(((Number) rows.get(0).get("total")).longValue()).isEqualTo(3L);
        assertThat(rows.get(1)).containsEntry("p", "P2").containsEntry("total", null).containsEntry(MaskFlag.MEMBER, List.of("total"));
    }

    /**
     * Read shaping keeps its result over a masked body: a body search counts the rows the unshaped statement counts, and a
     * page projecting the body and a call over it returns its rows and marks.
     */
    @Test
    void readShapedMaskedBodyReturnsWhatTheUnshapedStatementReturns() {
        final QueryEntityModel model = enrichedModel();
        final FilterNode window = cmp(ComparisonOp.GE, field("request_time"), new ValueExpr(ValueType.TIMESTAMP, "1791590400000"));
        final FilterNode search = new LogicalNode(LogicalOp.AND, List.of(window, cmp(ComparisonOp.ICO, field(BODY), str("error"))));
        final StructuredQuery counted = new StructuredQuery(SOURCE, search, QueryMode.ROW, false, List.of(new OutputColumn(field("event_id"), null)),
                null, null, null, new OffsetPage(0, 10, true));
        final StructuredQueryBuilder unshaped = builder.withoutReadShaping();

        final SelectQuery<?> shapedCount = builder.buildCount(dsl, model, counted);
        assertThat(MaskRendering.render(() -> shapedCount.getSQL(ParamType.INLINED))).as("the body search is computed in the derived table")
                .contains("__adas_c0");
        assertThat(count(shapedCount)).isEqualTo(count(unshaped.buildCount(dsl, model, counted))).isEqualTo(1L);

        final StructuredQuery page = new StructuredQuery(SOURCE, window, QueryMode.ROW, false,
                List.of(new OutputColumn(field("event_id"), null), new OutputColumn(field(BODY), null),
                        new OutputColumn(new FnExpr("length", false, List.of(field(BODY))), "size")),
                null, null, List.of(new SortItem("event_id", SortDir.ASC, null)), new OffsetPage(0, 10, false));
        final List<Map<String, Object>> shaped = run(builder.build(dsl, model, page));
        assertThat(shaped).isEqualTo(run(unshaped.build(dsl, model, page)));
        assertThat(byKey(shaped, "event_id").get("e2")).containsEntry(BODY, null).containsEntry("size", null)
                .containsEntry(MaskFlag.MEMBER, List.of(BODY, "size"));
        assertThat(byKey(shaped, "event_id").get("e1")).containsEntry(BODY, "has an error").doesNotContainKey(MaskFlag.MEMBER);
    }

    /**
     * An enrichment field projected without {@code as} names its output after the field, and every reference to that name
     * in its block reads the masked value, never the stored column the name also resolves to: sorted with a one-row page,
     * grouped, kept by a {@code having}, collapsed by a distinct. e2 and e4 are absent and store different bodies.
     */
    @Test
    void exemptEnrichmentOutputReadsTheMaskedValueWhereverItsNameIsReferenced() {
        final QueryEntityModel model = enrichedModel();
        final OutputColumn body = new OutputColumn(field(BODY), null);
        final StructuredQuery first = new StructuredQuery(SOURCE, null, QueryMode.ROW, false, List.of(new OutputColumn(field("event_id"), null), body),
                null, null, List.of(new SortItem(BODY, SortDir.ASC, null)), new OffsetPage(0, 1, false));
        assertThat(run(builder.build(dsl, model, first))).singleElement().satisfies(row -> assertThat(row).containsEntry(BODY, "fine"));

        final OutputColumn count = new OutputColumn(new FnExpr("count", false, List.of()), "rows");
        final List<Map<String, Object>> groups = run(builder.build(dsl, model, new StructuredQuery(SOURCE, null, QueryMode.AGGREGATE, false,
                List.of(body, count), List.of(BODY), null, List.of(new SortItem(BODY, SortDir.ASC, null)), new OffsetPage(0, 10, false))));
        assertThat(groups).extracting(row -> row.get(BODY)).containsExactly("fine", "has an error", null);
        assertThat(groups.get(2)).containsEntry(MaskFlag.MEMBER, List.of(BODY));
        assertThat(((Number) groups.get(2).get("rows")).longValue()).as("the absent rows in one null group").isEqualTo(2L);

        final List<Map<String, Object>> kept = run(builder.build(dsl, model, new StructuredQuery(SOURCE, null, QueryMode.AGGREGATE, false,
                List.of(body, count), List.of(BODY), cmp(ComparisonOp.GT, field("rows"), new ValueExpr(ValueType.LONG, "1")), null,
                new OffsetPage(0, 10, false))));
        assertThat(kept).singleElement().satisfies(row -> assertThat(row).containsEntry(BODY, null));

        final List<Map<String, Object>> distinct = run(builder.build(dsl, model, new StructuredQuery(SOURCE, null, QueryMode.ROW, true, List.of(body),
                null, null, List.of(new SortItem(BODY, SortDir.ASC, null)), new OffsetPage(0, 10, false))));
        assertThat(distinct).extracting(row -> row.get(BODY)).containsExactly("fine", "has an error", null);
        assertThat(distinct.get(2)).containsEntry(MaskFlag.MEMBER, List.of(BODY));

        // Grouped with a visible key and sorted by the exempt output: d2's two absent rows are one null group.
        final List<Map<String, Object>> keyed = run(builder.build(dsl, model, new StructuredQuery(SOURCE, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(field("deployment"), null), body, count), List.of("deployment", BODY), null,
                List.of(new SortItem(BODY, SortDir.ASC, null)), new OffsetPage(0, 10, false))));
        assertThat(keyed).extracting(row -> row.get("deployment") + "/" + row.get(BODY)).containsExactly("d1/fine", "d1/has an error", "d2/null");
        assertThat(((Number) keyed.get(2).get("rows")).longValue()).isEqualTo(2L);

        // A scalar subquery projecting the exempt output over an absent row returns null, marked.
        final List<RelationColumn> entity = List.of(new RelationColumn("event_id", QueryFieldType.STRING, ResultColumnKind.STRING),
                new RelationColumn("request_time", QueryFieldType.TIMESTAMP, ResultColumnKind.TIMESTAMP),
                new RelationColumn("deployment", QueryFieldType.STRING, ResultColumnKind.STRING),
                new RelationColumn(BODY, QueryFieldType.STRING, ResultColumnKind.STRING));
        final CompositeQuery absentBody = new CompositeQuery(new EntityRelation(SOURCE, entity), new StructuredQuery(SOURCE,
                cmp(ComparisonOp.EQ, field("event_id"), str("e2")), QueryMode.ROW, false, List.of(body), null, null, null, null),
                List.of(new RelationColumn(BODY, QueryFieldType.STRING, ResultColumnKind.STRING)));
        final List<OutputColumn> outputs = List.of(new OutputColumn(field("event_id"), null),
                new OutputColumn(new com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr(absentBody), "b"));
        final CompositeQuery root = new CompositeQuery(new EntityRelation(SOURCE, entity), new StructuredQuery(SOURCE,
                cmp(ComparisonOp.EQ, field("event_id"), str("e1")), QueryMode.ROW, false, outputs, null, null, null, null),
                List.of(entity.getFirst(), new RelationColumn("b", QueryFieldType.STRING, ResultColumnKind.STRING)));
        assertThat(run(composite.build(dsl, root, ignored -> model, 1000))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("b", null).containsEntry(MaskFlag.MEMBER, List.of("b")));
    }

    /** A filter whose only reference to a mask is inside a lambda defines the mask in the lambda body, and runs. */
    @Test
    void maskReferencedOnlyInsideFilterLambdaIsDefinedInTheBodyAndRuns() {
        final Expr matches = new FnExpr("arrayExists", false, List.of(new LambdaExpr(List.of("x"), new FnExpr("equals", false,
                List.of(new LambdaParamExpr("x"), field("n")))), field("opts")));
        final SelectQuery<?> select = builder.build(dsl, valuesModel(), new StructuredQuery(VALUES, cmp(ComparisonOp.EQ, matches,
                new ValueExpr(ValueType.BOOLEAN, "true")), QueryMode.ROW, false, List.of(new OutputColumn(field("k"), null)), null, null,
                List.of(new SortItem("k", SortDir.ASC, null)), new OffsetPage(0, 10, false)));
        final String sql = MaskRendering.render(() -> select.getSQL(ParamType.INLINED));

        assertThat(sql.indexOf(" AS \"" + MaskRendering.ALIAS_PREFIX)).as(sql).isGreaterThan(sql.indexOf("->"));
        // k1 and k2 find their values; kA and kB store values opts holds, but their cells are absent, and kC stores null.
        assertThat(run(select)).extracting(row -> row.get("k")).containsExactly("k1", "k2");
    }

    /** Beside a masked field, an output named after a table the query reads (or beginning with it and '.') is refused. */
    @Test
    void tableNamedOutputBesideMaskedFieldIsRefusedAsGroupKeyAndSortKey() {
        final OutputColumn named = new OutputColumn(field("deployment"), SOURCE + ".deployment");
        final FilterNode having = cmp(ComparisonOp.GT, new FnExpr("count", false, List.of(field(BODY))), new ValueExpr(ValueType.LONG, "0"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(dsl, enrichedModel(), new StructuredQuery(SOURCE, null,
                        QueryMode.AGGREGATE, false, List.of(named), List.of(SOURCE + ".deployment"), having, null, null)))
                .hasMessageContaining("names a table the query reads");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(dsl, enrichedModel(), new StructuredQuery(SOURCE, null, QueryMode.ROW,
                        false, List.of(named, new OutputColumn(field(BODY), null)), null, null, List.of(new SortItem(SOURCE + ".deployment", SortDir.ASC, null)),
                        null)))
                .hasMessageContaining("names a table the query reads");
    }

    /** A lambda parameter named like the mask's internal alias cannot capture it: the alias is chosen apart from it. */
    @Test
    void lambdaParameterNamedLikeInternalAliasReadsTheAbsentValue() {
        final String param = "__adas_mcond_0";
        // opts holds [1, 2, 0, 5]: the values kA and kB store among them.
        final Expr hit = new FnExpr("arrayExists", false, List.of(new LambdaExpr(List.of(param), new FnExpr("equals", false,
                List.of(new LambdaParamExpr(param), field("n")))), field("opts")));
        final StructuredQuery query = new StructuredQuery(VALUES, cmp(ComparisonOp.IN, field("k"), strings("kA", "kB")), QueryMode.ROW, false,
                List.of(new OutputColumn(field("k"), null), new OutputColumn(hit, "hit")), null, null, null, new OffsetPage(0, 10, false));

        final Map<String, Map<String, Object>> rows = byKey(run(builder.build(dsl, valuesModel(), query)));
        for (final String absent : List.of("kA", "kB")) {
            assertThat(String.valueOf(rows.get(absent).get("hit"))).as(absent + " stores a value in the list, but its cell is absent")
                    .isIn("0", "false");
            assertThat(rows.get(absent)).containsEntry(MaskFlag.MEMBER, List.of("hit"));
        }
    }

    /**
     * One policy near 16 KiB masking sixteen columns renders once per scope, so a wildcard over them, a lambda reading all
     * sixteen and sixteen lambdas each reading one run under ClickHouse's default {@code max_query_size} and
     * {@code max_ast_elements}.
     */
    @Test
    void largePolicyRendersOncePerScopeAndRunsUnderTheDefaultLimits() {
        final QueryEntityModel model = wideModel();
        final List<Expr> reads = new ArrayList<>();
        final List<OutputColumn> lambdas = new ArrayList<>();
        for (int i = 0; i < WIDE_COLUMNS; i++) {
            reads.add(new FnExpr("equals", false, List.of(new LambdaParamExpr("x"), field("c" + i))));
            lambdas.add(new OutputColumn(new FnExpr("arrayExists", false, List.of(new LambdaExpr(List.of("x"), new FnExpr("equals", false,
                    List.of(new LambdaParamExpr("x"), field("c" + i)))), field("labels"))), "l" + i));
        }
        final List<FilterNode> equalities = reads.stream().<FilterNode>map(read -> new ComparisonNode(ComparisonOp.EQ,
                ((FnExpr) read).args())).toList();
        final Expr all = new FnExpr("arrayExists", false, List.of(new LambdaExpr(List.of("x"), new CaseExpr(List.of(new CaseBranch(
                new LogicalNode(LogicalOp.OR, equalities), new ValueExpr(ValueType.BOOLEAN, "true"))), new ValueExpr(ValueType.BOOLEAN, "false"))),
                field("labels")));
        final List<OutputColumn> select = new ArrayList<>(List.of(new OutputColumn(field("k"), null)));
        select.addAll(lambdas);
        select.add(new OutputColumn(all, "any_read"));
        for (final StructuredQuery query : List.of(
                new StructuredQuery(WIDE, null, QueryMode.ROW, false, List.of(), null, null, null, new OffsetPage(0, 10, false)),
                new StructuredQuery(WIDE, null, QueryMode.ROW, false, select, null, null, null, new OffsetPage(0, 10, false)))) {
            final SelectQuery<?> statement = builder.build(dsl, model, query);
            final String sql = MaskRendering.render(() -> statement.getSQL(ParamType.INLINED));
            assertThat(sql.length()).as("the policy is near 16 KiB").isGreaterThan(16_000).isLessThan(262_144);
            assertThat(occurrences(sql, "'P1799'")).as("rendered once in the statement's one scope").isEqualTo(1);
            final List<Map<String, Object>> rows = run(statement);
            assertThat(rows).hasSize(2);
            assertThat(byKey(rows).get("w0")).doesNotContainKey(MaskFlag.MEMBER);
            assertThat(byKey(rows).get("w1")).containsKey(MaskFlag.MEMBER);
        }
    }

    /**
     * {@code union all} branches carry a mark flag and a bare-absent flag per position, so only the branch whose output is the
     * bare absent array nulls it; the other branch's expression over it reads the empty value, marked.
     */
    @Test
    void unionAllNullsOnlyTheBareAbsentCell() {
        final RelationColumn k = new RelationColumn("k", QueryFieldType.STRING, ResultColumnKind.STRING);
        final RelationColumn v = new RelationColumn("v", QueryFieldType.ARRAY, QueryFieldType.STRING, ResultColumnKind.ARRAY, ResultColumnKind.STRING);
        final Expr slice = new FnExpr("array_slice", false, List.of(field("tags"), new ValueExpr(ValueType.INTEGER, "1"),
                new ValueExpr(ValueType.INTEGER, "1")));
        final CompositeQuery bare = arraysLeaf(List.of(new OutputColumn(field("k"), null), new OutputColumn(field("tags"), "v")), List.of(k, v));
        final CompositeQuery sliced = arraysLeaf(List.of(new OutputColumn(field("k"), null), new OutputColumn(slice, "v")), List.of(k, v));
        final CompositeQuery root = new CompositeQuery(new SetOpRelation(SetOpKind.UNION, true, List.of(bare, sliced), List.of(k, v)),
                new StructuredQuery(ARRAYS, null, QueryMode.ROW, false, List.of(new OutputColumn(field("k"), null), new OutputColumn(field("v"), null)),
                        null, null, null, null), List.of(k, v));

        final List<Map<String, Object>> rows = run(composite.build(dsl, root, entity -> arraysModel(), 1000));
        final List<String> a3 = rows.stream().filter(row -> "a3".equals(row.get("k"))).map(row -> show(row.get("v")) + " " + row.get(MaskFlag.MEMBER))
                .sorted().toList();
        assertThat(a3).containsExactly("[] [v]", "null [v]");
        assertThat(rows.stream().filter(row -> "a2".equals(row.get("k"))).map(row -> show(row.get("v")) + " " + row.get(MaskFlag.MEMBER)).toList())
                .containsExactly("[] null", "[] null");
    }

    /** A grouping key over a bare masked array keeps its absent cells apart from a stored empty array. */
    @Test
    void groupingKeyOverBareMaskedArrayKeepsAbsentCellsApartFromStoredEmpty() {
        final List<Map<String, Object>> groups = run(builder.build(dsl, arraysModel(), new StructuredQuery(ARRAYS, null, QueryMode.AGGREGATE, false,
                List.of(new OutputColumn(field("tags"), null), new OutputColumn(new FnExpr("count", false, List.of()), "rows")), List.of("tags"),
                null, null, new OffsetPage(0, 10, false))));
        assertThat(groups).hasSize(3);
        final List<Map<String, Object>> absent = groups.stream().filter(row -> row.get("tags") == null).toList();
        assertThat(absent).singleElement().satisfies(row -> assertThat(row).containsEntry(MaskFlag.MEMBER, List.of("tags")));
        assertThat(((Number) absent.getFirst().get("rows")).longValue()).isEqualTo(2L);
    }

    /**
     * An aggregate-mode distinct collapses the groups whose outputs are equal, an absent and a stored null among them, and
     * marks the row when any group it collapses was marked; a row-mode distinct sorted by a key that is not an output runs.
     */
    @Test
    void distinctCollapsesMarkedAndUnmarkedRowsAndMarksTheResult() {
        final List<Map<String, Object>> tops = run(builder.build(dsl, valuesModel(), new StructuredQuery(VALUES, null, QueryMode.AGGREGATE, true,
                List.of(new OutputColumn(new FnExpr("max", false, List.of(field("n"))), "top")), List.of("k"), null, null, new OffsetPage(0, 10, false))));
        final List<Map<String, Object>> nulls = tops.stream().filter(row -> row.get("top") == null).toList();
        assertThat(tops).hasSize(3);
        assertThat(nulls).singleElement().satisfies(row -> assertThat(row).containsEntry(MaskFlag.MEMBER, List.of("top")));

        final List<Map<String, Object>> projects = run(builder.build(dsl, valuesModel(), new StructuredQuery(VALUES, null, QueryMode.ROW, true,
                List.of(new OutputColumn(field("p"), null), new OutputColumn(field("n"), null)), null, null,
                List.of(new SortItem("k", SortDir.ASC, null)), new OffsetPage(0, 10, false))));
        assertThat(projects).filteredOn(row -> "P2".equals(row.get("p"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("n", null).containsEntry(MaskFlag.MEMBER, List.of("n")));
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** The result of a statement whose only output is {@code v}: its value, or the error code ClickHouse refuses it with. */
    private static String outcome(String sql) {
        try {
            return "value " + ClickHouseProbe.queryAll(sql).getFirst().getValues().get("v");
        } catch (RuntimeException ex) {
            final Matcher code = Pattern.compile("Code: (\\d+)").matcher(String.valueOf(ex.getCause() == null ? ex.getMessage()
                    : ex.getCause().getMessage()));
            return "error " + (code.find() ? code.group(1) : ex.getMessage());
        }
    }

    /** Runs {@code select} as the executor does: rendered with every mask once per scope, its marks turned into {@code $masked}. */
    private static List<Map<String, Object>> run(SelectQuery<?> select) {
        final String sql = MaskRendering.render(() -> select.getSQL(ParamType.INLINED));
        final List<Map<String, Object>> rows = new ArrayList<>();
        // By the result's columns: GenericRecord#getValues leaves a null cell out, which the executor's rows keep.
        ClickHouseProbe.queryAll(sql).forEach(row -> {
            final Map<String, Object> cells = new LinkedHashMap<>();
            row.getSchema().getColumns().forEach(column -> cells.put(column.getColumnName(), row.getObject(column.getColumnName())));
            rows.add(cells);
        });
        MaskFlag.Marks.of(select.getSelect()).apply(rows);
        return rows;
    }

    private static long count(SelectQuery<?> select) {
        final String sql = MaskRendering.render(() -> select.getSQL(ParamType.INLINED));
        return ((Number) ClickHouseProbe.queryAll(sql).getFirst().getValues().values().iterator().next()).longValue();
    }

    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> rows) {
        return byKey(rows, "k");
    }

    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> rows, String key) {
        final Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        rows.forEach(row -> byKey.put(String.valueOf(row.get(key)), row));
        return byKey;
    }

    /** {@code tags} masked to the rows of {@code P1}. */
    private static QueryEntityModel arraysModel() {
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name(ARRAYS, "p"), String.class).eq("P1"));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "k", ARRAYS, "k", QueryFieldType.STRING, false, null);
        bind(bindings, "p", ARRAYS, "p", QueryFieldType.STRING, false, null);
        bindings.put("tags", new QueryFieldBinding("tags", DSL.field(DSL.name(ARRAYS, "tags")), QueryFieldType.ARRAY, QueryFieldType.STRING)
                .masked(mask));
        return new QueryEntityModel(ARRAYS, DSL.table(DSL.name(ARRAYS)), bindings, List.of(), List.of());
    }

    private static CompositeQuery arraysLeaf(List<OutputColumn> select, List<RelationColumn> columns) {
        final List<RelationColumn> entity = List.of(new RelationColumn("k", QueryFieldType.STRING, ResultColumnKind.STRING),
                new RelationColumn("p", QueryFieldType.STRING, ResultColumnKind.STRING),
                new RelationColumn("tags", QueryFieldType.ARRAY, QueryFieldType.STRING, ResultColumnKind.ARRAY, ResultColumnKind.STRING));
        return new CompositeQuery(new EntityRelation(ARRAYS, entity), new StructuredQuery(ARRAYS, null, QueryMode.ROW, false, select, null, null,
                null, null), columns);
    }

    /** Sixteen columns masked by one policy listing 1800 projects, near 16 KiB of text; {@code w0}'s project is among them. */
    private static QueryEntityModel wideModel() {
        final List<String> projects = new ArrayList<>();
        for (int i = 0; i < 1800; i++) {
            projects.add(String.format("P%04d", i));
        }
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name(WIDE, "p"), String.class).in(projects));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "k", WIDE, "k", QueryFieldType.STRING, false, null);
        bind(bindings, "p", WIDE, "p", QueryFieldType.STRING, false, null);
        bindings.put("labels", new QueryFieldBinding("labels", DSL.field(DSL.name(WIDE, "labels")), QueryFieldType.ARRAY, QueryFieldType.STRING));
        for (int i = 0; i < WIDE_COLUMNS; i++) {
            bind(bindings, "c" + i, WIDE, "c" + i, QueryFieldType.STRING, false, mask);
        }
        return new QueryEntityModel(WIDE, DSL.table(DSL.name(WIDE)), bindings, List.of(), List.of());
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    /** A cell as text, an array cell whatever the client returns it as. */
    private static String show(Object cell) {
        return switch (cell) {
            case com.clickhouse.client.api.data_formats.internal.BinaryStreamReader.ArrayValue array -> array.asList().toString();
            case Object[] array -> java.util.Arrays.toString(array);
            case null, default -> String.valueOf(cell);
        };
    }

    /** {@code n} masked to the rows of {@code P1}. */
    private static QueryEntityModel valuesModel() {
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name(VALUES, "p"), String.class).eq("P1"));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "k", VALUES, "k", QueryFieldType.STRING, false, null);
        bind(bindings, "p", VALUES, "p", QueryFieldType.STRING, false, null);
        bind(bindings, "n", VALUES, "n", QueryFieldType.LONG, false, mask);
        bindings.put("opts", new QueryFieldBinding("opts", DSL.field(DSL.name(VALUES, "opts")), QueryFieldType.ARRAY, QueryFieldType.LONG));
        return new QueryEntityModel(VALUES, DSL.table(DSL.name(VALUES)), bindings, List.of(), List.of());
    }

    /** The source and its aligned payload, the heavy body masked to the source rows of deployment {@code d1}. */
    private static QueryEntityModel enrichedModel() {
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name(SOURCE, "deployment"), String.class).eq("d1"));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "event_id", SOURCE, "event_id", QueryFieldType.STRING, false, null);
        bind(bindings, "request_time", SOURCE, "request_time", QueryFieldType.TIMESTAMP, false, null);
        bind(bindings, "deployment", SOURCE, "deployment", QueryFieldType.STRING, false, null);
        bind(bindings, BODY, PAYLOAD, "body", QueryFieldType.STRING, true, mask);
        final EnrichmentJoin payload = EnrichmentJoin.keyed(DSL.table(DSL.name(PAYLOAD)), DSL.field(DSL.name(SOURCE, "event_id")),
                DSL.field(DSL.name(PAYLOAD, "event_id")), DSL.field(DSL.name(SOURCE, "request_time")), DSL.field(DSL.name(PAYLOAD, "request_time")),
                Set.of(BODY), null);
        return new QueryEntityModel(SOURCE, DSL.table(DSL.name(SOURCE)), bindings, List.of(payload), List.of(), null, true, true);
    }

    private static void bind(Map<String, QueryFieldBinding> bindings, String name, String table, String column, QueryFieldType type,
            boolean heavy, ColumnMask mask) {
        final QueryFieldBinding binding = new QueryFieldBinding(name, DSL.field(DSL.name(table, column), JooqTypes.of(type)), type, null, heavy);
        bindings.put(name, mask == null ? binding : binding.masked(mask));
    }

    private static FieldExpr field(String name) {
        return new FieldExpr(name);
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static Expr strings(String... values) {
        return new com.epam.aidial.analytics.service.query.model.ArrayExpr(java.util.Arrays.stream(values).<Expr>map(value -> str(value)).toList());
    }

    private static FilterNode cmp(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }
}
