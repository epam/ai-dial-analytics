package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
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
import com.epam.aidial.analytics.service.query.model.FrameBound;
import com.epam.aidial.analytics.service.query.model.FrameBoundKind;
import com.epam.aidial.analytics.service.query.model.FrameUnit;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.NullsOrder;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowFrame;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
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

    private final DSLContext dsl = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expressions, new FilterTranslator(expressions));

    @BeforeAll
    static void createProbes() {
        dropProbes();
        // The mask admits the rows of P1. kA and kB are absent and store different values; kC is present and stores null.
        ClickHouseProbe.execute("CREATE TABLE " + VALUES + " (k String, p String, n Nullable(Int64)) ENGINE = ReplicatedMergeTree ORDER BY k");
        ClickHouseProbe.execute("INSERT INTO " + VALUES + " VALUES ('k1', 'P1', 1), ('k2', 'P1', 2), ('kA', 'P2', 0), ('kB', 'P2', 5),"
                + " ('kC', 'P1', NULL)");
        ClickHouseProbe.execute("CREATE TABLE " + SOURCE + " (event_id String, request_time DateTime64(3, 'UTC'), deployment String)"
                + " ENGINE = ReplicatedReplacingMergeTree ORDER BY (event_id, request_time)");
        ClickHouseProbe.execute("CREATE TABLE " + PAYLOAD + " (event_id String, request_time DateTime64(3, 'UTC'), body String)"
                + " ENGINE = ReplicatedReplacingMergeTree ORDER BY (event_id, request_time)");
        ClickHouseProbe.execute("INSERT INTO " + SOURCE + " VALUES ('e1', '2026-11-01 00:00:00', 'd1'), ('e2', '2026-11-01 00:00:00', 'd2'),"
                + " ('e3', '2026-11-01 00:00:00', 'd1'), ('e4', '2026-11-01 00:00:00', 'd2')");
        ClickHouseProbe.execute("INSERT INTO " + PAYLOAD + " VALUES ('e1', '2026-11-01 00:00:00', 'has an error'),"
                + " ('e2', '2026-11-01 00:00:00', 'has an error'), ('e3', '2026-11-01 00:00:00', 'fine'),"
                + " ('e4', '2026-11-01 00:00:00', 'ERROR here')");
    }

    @AfterAll
    static void dropProbes() {
        for (final String table : List.of(VALUES, SOURCE, PAYLOAD)) {
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

    /** {@code n} masked to the rows of {@code P1}. */
    private static QueryEntityModel valuesModel() {
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name(VALUES, "p"), String.class).eq("P1"));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "k", VALUES, "k", QueryFieldType.STRING, false, null);
        bind(bindings, "p", VALUES, "p", QueryFieldType.STRING, false, null);
        bind(bindings, "n", VALUES, "n", QueryFieldType.LONG, false, mask);
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
