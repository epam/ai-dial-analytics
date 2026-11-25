package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.CallerValues;
import com.epam.aidial.analytics.service.query.ColumnMask;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.MaskRendering;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
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
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pruning form of a filter over a masked field returns the rows its masked form returns, on ClickHouse (see
 * {@code openspec/specs/structured-query/spec.md}, "A masked field reads as absent at every reference"): every operator
 * the rewrite takes, and the shapes it leaves alone, over stored nulls, absent cells and a row whose mask is unknown, under
 * {@code transform_null_in = 0} and {@code 1}. The masked form is the same comparison under an {@code or} with a branch no
 * row satisfies, which the rewrite never reaches. Statements are built by the query builder over a hand-built model and
 * run on the test container; no service context is started.
 */
class MaskedFilterRewriteIntegrationTest {

    private static final String TABLE = "mask_rewrite_probe";

    private final DSLContext dsl = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expressions, new FilterTranslator(expressions));

    @BeforeAll
    static void createProbe() {
        ClickHouseProbe.execute("CREATE TABLE " + TABLE + " (k String, p Nullable(String), n Nullable(Int64), s Nullable(String),"
                + " ts Nullable(DateTime64(3, 'UTC'))) ENGINE = ReplicatedMergeTree ORDER BY k");
        // The mask admits the rows of P1; P2's cells are absent, and so are those of k7, whose mask is unknown.
        ClickHouseProbe.execute("INSERT INTO " + TABLE + " VALUES"
                + " ('k1', 'P1', 1, 'a', now64(3) - INTERVAL 2 HOUR), ('k2', 'P1', NULL, NULL, NULL),"
                + " ('k3', 'P2', 1, 'a', now64(3) - INTERVAL 2 HOUR), ('k4', 'P2', NULL, NULL, NULL),"
                + " ('k5', 'P1', 5, 'Ab', now64(3) - INTERVAL 3 DAY), ('k6', 'P2', 5, 'Ab', now64(3) - INTERVAL 3 DAY),"
                + " ('k7', NULL, 1, 'a', now64(3) - INTERVAL 2 HOUR)");
    }

    @AfterAll
    static void dropProbe() {
        ClickHouseProbe.execute("DROP TABLE IF EXISTS " + TABLE);
    }

    @Test
    void everyShapeReturnsTheRowsOfItsMaskedForm() {
        final Expr dayAgo = new FnExpr("date_sub", false, List.of(str("day"), new ValueExpr(ValueType.INTEGER, "1"),
                new FnExpr("now", false, List.of())));
        final List<FilterNode> shapes = List.of(
                cmp(ComparisonOp.EQ, "n", number("1")), cmp(ComparisonOp.NE, "n", number("1")), cmp(ComparisonOp.LT, "n", number("5")),
                cmp(ComparisonOp.LE, "n", number("1")), cmp(ComparisonOp.GT, "n", number("1")), cmp(ComparisonOp.GE, "n", number("5")),
                cmp(ComparisonOp.IN, "n", new ArrayExpr(List.of(number("1"), number("5")))),
                cmp(ComparisonOp.EQ, "s", str("a")), cmp(ComparisonOp.NE, "s", str("a")), cmp(ComparisonOp.CO, "s", str("b")),
                cmp(ComparisonOp.NC, "s", str("b")), cmp(ComparisonOp.ICO, "s", str("AB")), cmp(ComparisonOp.INC, "s", str("AB")),
                cmp(ComparisonOp.NE, "s", nullValue()),
                cmp(ComparisonOp.GE, "ts", dayAgo), cmp(ComparisonOp.LT, "ts", dayAgo),
                // Left in the masked form: an absent cell satisfies `is null`, and under transform_null_in = 1 a null item matches it.
                cmp(ComparisonOp.EQ, "s", nullValue()), cmp(ComparisonOp.IN, "n", new ArrayExpr(List.of(number("1"), nullValue()))));
        for (final FilterNode shape : shapes) {
            for (final int transformNullIn : List.of(0, 1)) {
                assertThat(keys(shape, transformNullIn)).as(shape + " under transform_null_in = " + transformNullIn)
                        .isEqualTo(keys(masked(shape), transformNullIn));
            }
        }
        // The rows an absent cell cannot satisfy are gone, the present ones stay.
        assertThat(keys(cmp(ComparisonOp.EQ, "n", number("1")), 0)).containsExactly("k1");
        assertThat(keys(cmp(ComparisonOp.NE, "s", nullValue()), 0)).containsExactly("k1", "k5");
        assertThat(keys(cmp(ComparisonOp.EQ, "s", nullValue()), 0)).containsExactly("k2", "k3", "k4", "k6", "k7");
        assertThat(sql(cmp(ComparisonOp.GT, "n", number("1")))).as("the pruning form compares the stored column")
                .contains("\"" + TABLE + "\".\"n\" > 1");
    }

    @Test
    void callerValueOperandReturnsTheRowsOfItsMaskedFormKnownOrNot() {
        final FilterNode shape = new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("s"), new ParamExpr(CallerValues.PROJECT)));
        for (final String value : new String[] {"a", null}) {
            final List<String> rewritten = CallerValues.resolving(name -> value, () -> keys(shape, 0));
            assertThat(rewritten).as("caller value " + value).isEqualTo(CallerValues.resolving(name -> value, () -> keys(masked(shape), 0)));
            assertThat(rewritten).isEqualTo(value == null ? List.of() : List.of("k1"));
        }
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** {@code shape} under an {@code or} with a branch no row satisfies: not a top-level conjunct, so never rewritten. */
    private static FilterNode masked(FilterNode shape) {
        return new LogicalNode(LogicalOp.OR, List.of(shape, new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("k"), str("none")))));
    }

    private List<String> keys(FilterNode filter, int transformNullIn) {
        final String sql = sql(filter) + " SETTINGS transform_null_in = " + transformNullIn;
        try (Client client = client()) {
            return client.queryAll(sql).stream().map((GenericRecord row) -> row.getString("k")).toList();
        }
    }

    private String sql(FilterNode filter) {
        final StructuredQuery query = new StructuredQuery(TABLE, filter, QueryMode.ROW, false, List.of(new OutputColumn(new FieldExpr("k"), null)),
                null, null, List.of(new SortItem("k", SortDir.ASC, null)), new OffsetPage(0, 100, false));
        final Supplier<String> render = () -> builder.build(dsl, model(), query).getSQL(ParamType.INLINED);
        return MaskRendering.render(render);
    }

    /** {@code n}, {@code s} and {@code ts} masked to the rows of {@code P1}. */
    private static QueryEntityModel model() {
        final ColumnMask mask = ColumnMask.of(DSL.field(DSL.name(TABLE, "p"), String.class).eq("P1"));
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "k", QueryFieldType.STRING, null);
        bind(bindings, "p", QueryFieldType.STRING, null);
        bind(bindings, "n", QueryFieldType.LONG, mask);
        bind(bindings, "s", QueryFieldType.STRING, mask);
        bind(bindings, "ts", QueryFieldType.TIMESTAMP, mask);
        return new QueryEntityModel(TABLE, DSL.table(DSL.name(TABLE)), bindings, List.of(), List.of());
    }

    private static void bind(Map<String, QueryFieldBinding> bindings, String name, QueryFieldType type, ColumnMask mask) {
        final QueryFieldBinding binding = new QueryFieldBinding(name, DSL.field(DSL.name(TABLE, name), JooqTypes.of(type)), type);
        bindings.put(name, mask == null ? binding : binding.masked(mask));
    }

    private static FilterNode cmp(ComparisonOp op, String field, Expr value) {
        return new ComparisonNode(op, List.of(new FieldExpr(field), value));
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static ValueExpr number(String value) {
        return new ValueExpr(ValueType.LONG, value);
    }

    private static ValueExpr nullValue() {
        return new ValueExpr(ValueType.NULL, null);
    }

    private static Client client() {
        final var container = ClickHouseContainerSupport.CLICKHOUSE;
        return new Client.Builder()
                .addEndpoint("http://" + container.getHost() + ":" + container.getMappedPort(ClickHouseContainerSupport.HTTP_PORT) + "/")
                .setUsername(container.getUsername())
                .setPassword(container.getPassword())
                .setDefaultDatabase(ClickHouseContainerSupport.DATABASE)
                .build();
    }
}
