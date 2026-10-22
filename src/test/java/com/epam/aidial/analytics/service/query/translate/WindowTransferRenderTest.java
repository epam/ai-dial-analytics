package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-only coverage of the {@code structured-query} spec's "A partition-only key on an aligned enrichment is
 * transferred, not keyed", over a hand-built model: {@code events} partitioned by {@code day}, an enrichment aligned
 * with it on {@code day} ({@code ident}) and one keyed on another column with no partition pair ({@code users}).
 */
class WindowTransferRenderTest {

    private static final String SESSION = "ident.session";
    private static final String REGION = "users.region";
    private static final String WINDOW_START = "2026-01-01 00:00:00";
    private static final String GRAIN_SET = "\"ident\".\"event_id\" IN (select \"events\".\"event_id\" from \"events\"";
    private static final String PARTITION_SET = "\"ident\".\"day\" IN (select \"events\".\"day\" from \"events\"";
    private static final String TUPLE_JOIN = "on ((\"events\".\"event_id\" = \"ident\".\"event_id\") and (\"events\".\"day\" = \"ident\".\"day\"))";

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final FilterTranslator filters = new FilterTranslator(expressions);
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expressions, filters);

    private static QueryEntityModel model() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "event_id", "events", "event_id", QueryFieldType.UUID);
        bind(bindings, "day", "events", "day", QueryFieldType.TIMESTAMP);
        bind(bindings, "user_id", "events", "user_id", QueryFieldType.STRING);
        bind(bindings, "deployment", "events", "deployment", QueryFieldType.STRING);
        bind(bindings, SESSION, "ident", "session", QueryFieldType.STRING);
        bind(bindings, REGION, "users", "region", QueryFieldType.STRING);
        final EnrichmentJoin ident = EnrichmentJoin.keyed(DSL.table(DSL.name("ident")), DSL.field(DSL.name("events", "event_id")),
                DSL.field(DSL.name("ident", "event_id")), DSL.field(DSL.name("events", "day")), DSL.field(DSL.name("ident", "day")),
                Set.of(SESSION), PartitionGranularity.DAY);
        final EnrichmentJoin users = EnrichmentJoin.keyed(DSL.table(DSL.name("users")), DSL.field(DSL.name("events", "user_id")),
                DSL.field(DSL.name("users", "user_id")), null, null, Set.of(REGION), null);
        return new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings, List.of(ident, users),
                List.of(), null, true, false);
    }

    private static void bind(Map<String, QueryFieldBinding> bindings, String flatName, String table, String col, QueryFieldType type) {
        final Field<?> field = DSL.field(DSL.name(table, col), JooqTypes.of(type));
        bindings.put(flatName, new QueryFieldBinding(flatName, field, type, null, false));
    }

    private static StructuredQuery query(FilterNode filter, boolean includeTotal, String... select) {
        return new StructuredQuery("events", filter, QueryMode.ROW, false,
                Arrays.stream(select).map(name -> new OutputColumn(new FieldExpr(name), null)).toList(),
                null, null, null, new OffsetPage(0, 10, includeTotal));
    }

    private static StructuredQuery query(FilterNode filter, String... select) {
        return query(filter, false, select);
    }

    private static FilterNode cmp(ComparisonOp op, String field, Expr value) {
        return new ComparisonNode(op, List.of(new FieldExpr(field), value));
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static FilterNode and(FilterNode... args) {
        return new LogicalNode(LogicalOp.AND, List.of(args));
    }

    private static FilterNode or(FilterNode... args) {
        return new LogicalNode(LogicalOp.OR, List.of(args));
    }

    private static FilterNode window() {
        return cmp(ComparisonOp.GE, "day", str(WINDOW_START));
    }

    private String render(StructuredQuery query) {
        return builder.build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT).getSQL(ParamType.INLINED);
    }

    /** The {@code ident} derived table: from its {@code (select} to the alias that closes it. */
    private static String identDerived(String sql) {
        final int start = sql.indexOf("(select \"ident\".\"event_id\"");
        assertThat(start).as("ident is joined as a derived table: %s", sql).isGreaterThanOrEqualTo(0);
        return sql.substring(start, sql.indexOf(") \"ident\"", start));
    }

    @Test
    void windowOnlyReadTransfersItsWindowOntoTheAlignedEnrichment() {
        final String sql = render(query(window(), "event_id", SESSION));

        assertThat(identDerived(sql)).contains("\"ident\".\"day\" >=").doesNotContain(GRAIN_SET).doesNotContain(PARTITION_SET)
                .doesNotContain("\"events\"");
        assertThat(sql).contains(TUPLE_JOIN).contains("where \"events\".\"day\" >=");
    }

    @Test
    void pushedEnrichmentConjunctStaysBesideTheTransferredWindow() {
        final String sql = render(query(and(window(), cmp(ComparisonOp.EQ, SESSION, str("s1"))), "event_id"));

        assertThat(identDerived(sql)).contains("\"ident\".\"day\" >=").contains("\"ident\".\"session\" = 's1'")
                .doesNotContain(GRAIN_SET).doesNotContain(PARTITION_SET);
    }

    @Test
    void anotherSourceConjunctKeepsBothKeySets() {
        final String sql = render(query(and(window(), cmp(ComparisonOp.EQ, "deployment", str("d"))), "event_id", SESSION));

        assertThat(identDerived(sql)).contains(GRAIN_SET).contains(PARTITION_SET);
    }

    @Test
    void disjunctionOverThePartitionColumnKeepsBothKeySets() {
        final String sql = render(query(or(window(), cmp(ComparisonOp.LT, "day", str("2025-01-01 00:00:00"))), "event_id", SESSION));

        assertThat(identDerived(sql)).contains(GRAIN_SET).contains(PARTITION_SET);
    }

    @Test
    void joinWithoutAnAlignedPairIsKeyedAsBefore() {
        final String sql = render(query(window(), "event_id", REGION));

        assertThat(sql).contains("\"users\".\"user_id\" IN (select \"events\".\"user_id\" from \"events\" where \"events\".\"day\" >=");
    }

    @Test
    void countStatementTransfersAsItsPageDoes() {
        final StructuredQuery query = query(and(window(), cmp(ComparisonOp.EQ, SESSION, str("s1"))), true, "event_id");

        final String count = builder.buildCount(ctx, model(), query).getSQL(ParamType.INLINED);

        assertThat(identDerived(count)).contains("\"ident\".\"day\" >=").doesNotContain(GRAIN_SET).doesNotContain(PARTITION_SET);
    }

    @Test
    void compositeLeafWithoutReductionTransfers() {
        // A composite operand's moved window arrives as its leaf query's own filter, through the key-conjunct entry point.
        final String sql = builder.build(ctx, model(), query(window(), "event_id", SESSION), StructuredQueryBuilder.MAX_LIMIT,
                StructuredQueryBuilder.LimitPolicy.NESTED, List.of()).getSQL(ParamType.INLINED);

        assertThat(identDerived(sql)).contains("\"ident\".\"day\" >=").doesNotContain(GRAIN_SET).doesNotContain(PARTITION_SET);
    }

    @Test
    void handedInKeyConjunctKeepsBothKeySets() {
        // A composite reduction reaches the block as a key conjunct of its own: not a bound, so nothing transfers.
        final var reduction = DSL.condition("{0} IN (select 'u1')", DSL.field(DSL.name("events", "user_id")));

        final String sql = builder.build(ctx, model(), query(window(), "event_id", SESSION), StructuredQueryBuilder.MAX_LIMIT,
                StructuredQueryBuilder.LimitPolicy.NESTED, List.of(reduction)).getSQL(ParamType.INLINED);

        assertThat(identDerived(sql)).contains(GRAIN_SET).contains(PARTITION_SET);
    }
}
