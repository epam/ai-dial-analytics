package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.PartitionPreselection;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
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
import org.jooq.Condition;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-only coverage of the partition pre-selection (the {@code structured-query} spec's "A lookup on an aligned
 * enrichment pre-selects the source's partitions"), over a hand-built model: {@code events} partitioned by {@code day},
 * two enrichments aligned with it on {@code day} ({@code ident} and {@code labels}) and one keyed on another column
 * with no partition pair ({@code users}).
 */
class PartitionPreselectionRenderTest {

    private static final String SESSION = "ident.session";
    private static final String SCORE = "ident.score";
    private static final String BODY = "ident.body";
    private static final String TAG = "labels.tag";
    private static final String REGION = "users.region";
    private static final String PRESELECTION = "SETTINGS final = 0";

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final ExprTranslator expressions = new ExprTranslator(new ValueExprToObjectMapper());
    private final FilterTranslator filters = new FilterTranslator(expressions);
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(expressions, filters);

    private static EnrichmentJoin aligned(String table, Set<String> exposed, PartitionGranularity granularity) {
        return EnrichmentJoin.keyed(DSL.table(DSL.name(table)), DSL.field(DSL.name("events", "event_id")),
                DSL.field(DSL.name(table, "event_id")), DSL.field(DSL.name("events", "day")), DSL.field(DSL.name(table, "day")),
                exposed, granularity);
    }

    private static QueryEntityModel model() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bind(bindings, "event_id", "events", "event_id", QueryFieldType.UUID, false);
        bind(bindings, "day", "events", "day", QueryFieldType.TIMESTAMP, false);
        bind(bindings, "user_id", "events", "user_id", QueryFieldType.STRING, false);
        bind(bindings, "deployment", "events", "deployment", QueryFieldType.STRING, false);
        bind(bindings, SESSION, "ident", "session", QueryFieldType.STRING, false);
        bind(bindings, SCORE, "ident", "score", QueryFieldType.LONG, false);
        bind(bindings, BODY, "ident", "body", QueryFieldType.STRING, true);
        bind(bindings, TAG, "labels", "tag", QueryFieldType.STRING, false);
        bind(bindings, REGION, "users", "region", QueryFieldType.STRING, false);
        final EnrichmentJoin users = EnrichmentJoin.keyed(DSL.table(DSL.name("users")), DSL.field(DSL.name("events", "user_id")),
                DSL.field(DSL.name("users", "user_id")), null, null, Set.of(REGION), null);
        return new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings,
                List.of(aligned("ident", Set.of(SESSION, SCORE, BODY), PartitionGranularity.DAY),
                        aligned("labels", Set.of(TAG), PartitionGranularity.DAY), users),
                List.of(), null, true, false);
    }

    private static void bind(Map<String, QueryFieldBinding> bindings, String flatName, String table, String col,
            QueryFieldType type, boolean heavy) {
        final Field<?> field = DSL.field(DSL.name(table, col), JooqTypes.of(type));
        bindings.put(flatName, new QueryFieldBinding(flatName, field, type, null, heavy));
    }

    private static StructuredQuery query(FilterNode filter, String... select) {
        return new StructuredQuery("events", filter, QueryMode.ROW, false,
                Arrays.stream(select).map(name -> new OutputColumn(new FieldExpr(name), null)).toList(),
                null, null, null, new OffsetPage(0, 10, false));
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

    private String render(StructuredQuery query) {
        return builder.build(ctx, model(), query, StructuredQueryBuilder.MAX_LIMIT).getSQL(ParamType.INLINED);
    }

    private static int occurrences(String sql, String text) {
        final Matcher matcher = Pattern.compile(Pattern.quote(text)).matcher(sql);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------- rendering

    @Test
    void preselectionNamesThePartitionsAtEachGranularity() {
        final Condition lookup = DSL.condition("{0} = 's1'", DSL.field(DSL.name("ident", "session")));
        final Map<PartitionGranularity, String> functions = Map.of(
                PartitionGranularity.DAY, "toYYYYMMDD", PartitionGranularity.MONTH, "toYYYYMM", PartitionGranularity.YEAR, "toYear");
        for (final Map.Entry<PartitionGranularity, String> entry : functions.entrySet()) {
            final String fn = entry.getValue();
            final Condition source = PartitionPreselection.of(aligned("ident", Set.of(SESSION), entry.getKey()), lookup, List.of())
                    .orElseThrow();
            assertThat(ctx.renderInlined(source)).as(entry.getKey().name())
                    .contains(fn + "(\"events\".\"day\") IN (SELECT DISTINCT " + fn + "(\"ident\".\"day\") FROM \"ident\" WHERE ")
                    .contains("\"ident\".\"session\" = 's1'");
        }
    }

    @Test
    void joinWithoutAlignedPairOrGranularityHasNoPreselection() {
        final Condition lookup = DSL.condition("{0} = 's1'", DSL.field(DSL.name("ident", "session")));
        assertThat(PartitionPreselection.of(aligned("ident", Set.of(SESSION), null), lookup, List.of())).isEmpty();
        assertThat(PartitionPreselection.of(model().ownerOf(REGION), lookup, List.of())).isEmpty();
    }

    @Test
    void lookupRestrictsTheSourceOnceAndLeavesTheDerivedTableItsOwnConjunct() {
        final String sql = render(query(cmp(ComparisonOp.EQ, SESSION, str("s1")), "event_id", SESSION));

        assertThat(sql).contains("toYYYYMMDD(\"events\".\"day\") IN (SELECT DISTINCT toYYYYMMDD(\"ident\".\"day\") FROM \"ident\" "
                + "WHERE \"ident\".\"session\" = 's1' SETTINGS final = 0)");
        // One evaluation: ClickHouse carries the source's restriction into the aligned join through day = day.
        assertThat(occurrences(sql, PRESELECTION)).isEqualTo(1);
    }

    @Test
    void preselectionKeysOnlyTheJoinsWithoutAnAlignedPair() {
        final String sql = render(query(cmp(ComparisonOp.EQ, SESSION, str("s1")), SESSION, TAG, REGION));

        assertThat(sql).contains("left outer join \"labels\" FINAL on")
                .contains("\"users\".\"user_id\" IN (select \"events\".\"user_id\" from \"events\" where (toYYYYMMDD(\"events\".\"day\") IN "
                        + "(SELECT DISTINCT");
        assertThat(occurrences(sql, PRESELECTION)).isEqualTo(2);
    }

    @Test
    void sourceBoundOnThePartitionColumnBoundsThePreselectionsOwnRead() {
        final String sql = render(query(and(cmp(ComparisonOp.GE, "day", str("2026-01-01 00:00:00")),
                cmp(ComparisonOp.EQ, "deployment", str("d")), cmp(ComparisonOp.EQ, SESSION, str("s1"))), SESSION));

        final String preselection = sql.substring(sql.indexOf("(SELECT DISTINCT"), sql.indexOf(PRESELECTION));
        assertThat(preselection).contains("\"ident\".\"session\" = 's1'").contains("\"ident\".\"day\" >=")
                .doesNotContain("deployment").doesNotContain("\"events\"");
    }

    @Test
    void inListQualifiesAndDefaultNegatedOrHeavyLookupsDoNot() {
        final FilterNode in = cmp(ComparisonOp.IN, SESSION, new ArrayExpr(List.of(str("s1"), str("s2"))));
        assertThat(render(query(in, SESSION))).contains(PRESELECTION);
        for (final FilterNode filter : List.of(cmp(ComparisonOp.EQ, SESSION, str("")), cmp(ComparisonOp.NE, SESSION, str("s1")),
                cmp(ComparisonOp.IN, SESSION, new ArrayExpr(List.of(str("s1"), str("")))),
                cmp(ComparisonOp.EQ, BODY, str("x")))) {
            assertThat(render(query(filter, SESSION))).as(filter.toString()).doesNotContain(PRESELECTION);
        }
    }

    @Test
    void numericLookupQualifiesOnlyOffTheDefault() {
        assertThat(render(query(cmp(ComparisonOp.EQ, SCORE, new ValueExpr(ValueType.LONG, "5")), SCORE))).contains(PRESELECTION);
        assertThat(render(query(cmp(ComparisonOp.EQ, SCORE, new ValueExpr(ValueType.LONG, "0")), SCORE)))
                .doesNotContain(PRESELECTION);
    }

    @Test
    void countTakesThePreselectionAndKeysTheUnalignedJoinOnIt() {
        final String sql = builder.buildCount(ctx, model(), query(and(cmp(ComparisonOp.EQ, SESSION, str("s1")),
                cmp(ComparisonOp.EQ, REGION, str("eu"))))).getSQL(ParamType.INLINED);

        assertThat(sql).contains("toYYYYMMDD(\"events\".\"day\") IN (SELECT DISTINCT")
                .contains("\"users\".\"user_id\" IN (select \"events\".\"user_id\" from \"events\" where (toYYYYMMDD(");
    }
}
