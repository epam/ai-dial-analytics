package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlCompositeTranslation;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ClickHouse SQL a composite plan renders to, end to end from SQL text: joins as aliased derived
 * tables, inner blocks without a default limit, subqueries inline, windows and CASE through the shared
 * expression translator. Offline; the plan comes from the real {@link SqlQueryFrontEnd} and the SQL from
 * the real {@link CompositeQueryBuilder} over the same models the front end validated against.
 */
class CompositeQueryRenderTest {

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final Map<String, QueryEntityModel> models = Map.of(
            DialUsageLogTestModel.ENTITY, DialUsageLogTestModel.model(),
            "events", eventsModel());
    private final SqlQueryFrontEnd frontEnd = newFrontEnd(models);
    private final CompositeQueryBuilder builder = newBuilder();

    private static CompositeQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new CompositeQueryBuilder(new StructuredQueryBuilder(expr, new FilterTranslator(expr)));
    }

    private static SqlQueryFrontEnd newFrontEnd(Map<String, QueryEntityModel> models) {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.copyOf(models.keySet()));
        models.forEach((name, model) -> when(catalog.findModel(eq(name), any())).thenReturn(Optional.of(model)));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    private static QueryEntityModel eventsModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> schema = new ArrayList<>();
        for (final var column : List.of(
                Map.entry("user_id", QueryFieldType.STRING),
                Map.entry("amount", QueryFieldType.LONG),
                Map.entry("rate", QueryFieldType.DECIMAL))) {
            bindings.put(column.getKey(), new QueryFieldBinding(column.getKey(),
                    DSL.field(DSL.name("events", column.getKey()), JooqTypes.of(column.getValue())), column.getValue()));
            schema.add(new QuerySchemaFieldDto(
                    column.getKey(), column.getValue(), null, null, column.getKey(), null, null, null, false, false));
        }
        bindings.put("users.region", new QueryFieldBinding(
                "users.region", DSL.field(DSL.name("users", "region"), JooqTypes.of(QueryFieldType.STRING)),
                QueryFieldType.STRING));
        schema.add(new QuerySchemaFieldDto(
                "users.region", QueryFieldType.STRING, null, null, "region", null, null, null, false, false));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name("users")),
                DSL.field(DSL.name("events", "user_id")).eq(DSL.field(DSL.name("users", "user_id"))), null, null, null, null,
                Set.of("users.region"), null);
        return new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings, List.of(join), schema);
    }

    private String render(String sql) {
        final SqlCompositeTranslation plan = (SqlCompositeTranslation) frontEnd.plan(sql);
        return builder.build(ctx, plan.query(), models::get, StructuredQueryBuilder.MAX_LIMIT).getSQL(ParamType.INLINED);
    }

    @Test
    void innerJoinRendersAliasedDerivedTablesAndTheOuterDefaultLimit() {
        final String sql = render("SELECT a.event_kind, b.amount FROM dial_usage_log a JOIN events b"
                + " ON a.user_hash = b.user_id WHERE a.event_kind = 'chat'");
        assertThat(sql)
                .containsIgnoringCase("from (select")
                .containsIgnoringCase(") \"t0\" join (select")
                .containsIgnoringCase(") \"t1\" on")
                .contains("\"t0\".\"user_hash\" = \"t1\".\"user_id\"")
                // A conjunct over one operand alone is evaluated inside it, and the other side is reduced to the
                // keys it can produce (see CompositeOperandPushdownRenderTest).
                .containsIgnoringCase("from \"dial_usage_log\" where \"dial_usage_log\".\"event_kind\" = 'chat') \"t0\"")
                .containsIgnoringCase("\"events\".\"user_id\" in (select \"dial_usage_log\".\"user_hash\" from")
                .endsWith("limit 100");
        // The join operands project only what the statement reads — never a heavy column.
        assertThat(sql).doesNotContain("dial_usage_log_payload.request_body").doesNotContain("dial_usage_log_payload.assembled_response");
        // No inner block carries a default limit.
        assertThat(sql.toLowerCase()).containsOnlyOnce("limit");
    }

    @Test
    void leftJoinAndEnrichmentColumnOnAnOperand() {
        final String sql = render("SELECT a.event_id, b.\"users.region\" FROM dial_usage_log a"
                + " LEFT JOIN events b ON a.user_hash = b.user_id");
        assertThat(sql)
                .containsIgnoringCase("left outer join (select")
                // The enrichment join happens inside the operand's derived table, where the entity is named.
                .containsIgnoringCase("left outer join \"users\" on \"events\".\"user_id\" = \"users\".\"user_id\"")
                .contains("\"users\".\"region\" \"users.region\"");
    }

    @Test
    void crossJoinWithNoReferencedColumnsStillProjectsAnAnchor() {
        final String sql = render("SELECT count(*) FROM dial_usage_log a CROSS JOIN events b");
        assertThat(sql).containsIgnoringCase("cross join (select");
        assertThat(sql).doesNotContain("dial_usage_log_payload.request_body");
    }

    @Test
    void threeWayJoinRendersLeftDeep() {
        final String sql = render("SELECT a.event_id FROM dial_usage_log a"
                + " JOIN events b ON a.user_hash = b.user_id JOIN events c ON b.user_id = c.user_id");
        assertThat(sql).containsIgnoringCase(") \"t1\" on \"t0\".\"user_hash\" = \"t1\".\"user_id\" join (select");
        assertThat(sql).containsIgnoringCase(") \"t2\" on \"t1\".\"user_id\" = \"t2\".\"user_id\"");
    }

    @Test
    void innerLimitRendersAsWrittenAndUncapped() {
        final String sql = render("SELECT count(*) FROM (SELECT event_id FROM dial_usage_log LIMIT 5000) t");
        assertThat(sql).containsIgnoringCase("limit 5000").endsWith("limit 100");
    }

    @Test
    void innerBlockWithoutLimitIsNotDefaulted() {
        // DISTINCT under an aggregate forces the seal; the inner block states no limit and gets none.
        final String sql = render("SELECT count(*) FROM (SELECT DISTINCT project_id FROM dial_usage_log) t");
        assertThat(sql.toLowerCase()).containsOnlyOnce("limit").endsWith("limit 100");
        assertThat(sql).containsIgnoringCase("from (select distinct");
    }

    @Test
    void subqueryPredicatesRenderInline() {
        assertThat(render("SELECT event_id FROM dial_usage_log WHERE user_hash IN (SELECT user_id FROM events WHERE rate > 4)"))
                .containsIgnoringCase("\"user_hash\" in (select \"events\".\"user_id\"");
        assertThat(render("SELECT event_id FROM dial_usage_log WHERE user_hash NOT IN (SELECT user_id FROM events)"))
                .containsIgnoringCase("\"user_hash\" not in (select");
        assertThat(render("SELECT count(*) FROM dial_usage_log WHERE EXISTS (SELECT 1 FROM events WHERE rate > 4)"))
                .containsIgnoringCase("where exists (select");
        assertThat(render("SELECT count(*) FROM dial_usage_log WHERE NOT EXISTS (SELECT 1 FROM events)"))
                .containsIgnoringCase("where not exists (select");
    }

    @Test
    void scalarSubqueryRendersAsTheField() {
        final String sql = render("SELECT project_id, (SELECT count(*) FROM events) AS rates FROM dial_usage_log"
                + " WHERE total_tokens > (SELECT avg(total_tokens) FROM dial_usage_log)");
        assertThat(sql).containsIgnoringCase("(select count(*) \"count\" from \"events\" where true) \"rates\"");
        assertThat(sql).containsIgnoringCase("\"total_tokens\" > (select avg(");
    }

    @Test
    void cteAggregateRendersAsTheDerivedTable() {
        final String sql = render("WITH chats AS (SELECT project_id, total_tokens FROM dial_usage_log"
                + " WHERE event_kind = 'chat' LIMIT 500) SELECT project_id, sum(total_tokens) AS t FROM chats GROUP BY project_id");
        assertThat(sql).containsIgnoringCase("from (select").containsIgnoringCase("limit 500) \"t0\" where true group by \"t0\".\"project_id\"");
        assertThat(sql).containsIgnoringCase("sum(\"t0\".\"total_tokens\") \"t\"");
    }

    @Test
    void windowOverTheDerivedTableRendersTheWindowInsideAndTheFilterOutside() {
        final String sql = render("SELECT project_id, rn FROM (SELECT project_id,"
                + " row_number() OVER (PARTITION BY project_id ORDER BY request_time) AS rn FROM dial_usage_log) t WHERE rn <= 10");
        assertThat(sql).containsIgnoringCase(
                "row_number() over (partition by \"dial_usage_log\".\"project_id\" order by \"dial_usage_log\".\"request_time\" asc)");
        assertThat(sql).containsIgnoringCase(") \"t0\" where \"t0\".\"rn\" <= 10");
    }

    /**
     * A statement the translator nests because its projection drops the computed sort key renders the
     * ordering on <b>both</b> blocks: inside, where the {@code LIMIT} selects the top rows with it, and
     * again outside, over the column the derived table exposes. The outer one is what makes the rows
     * arrive ordered — row order out of a subquery is not contractual, and ClickHouse's default-on
     * {@code query_plan_remove_redundant_sorting} strips an inner {@code ORDER BY} whose enclosing block
     * has none.
     */
    @Test
    void sealedComputedSortKeyRendersTheOrderingOnTheOuterBlockToo() {
        assertThat(render("SELECT project_id FROM dial_usage_log GROUP BY project_id"
                + " ORDER BY count(*) DESC LIMIT 5"))
                .containsIgnoringCase("count(*) \"count\" from \"dial_usage_log\"")
                .containsIgnoringCase("order by \"count\" desc nulls first limit 5) \"t0\"")
                .containsIgnoringCase("order by \"t0\".\"count\" desc nulls first limit 5");
        assertThat(render("SELECT event_id FROM dial_usage_log ORDER BY length(event_kind) DESC LIMIT 5"))
                .containsIgnoringCase("length(\"dial_usage_log\".\"event_kind\") \"length\"")
                .containsIgnoringCase("order by \"t0\".\"length\" desc nulls first limit 5");
        // The no-LIMIT form: the inner ORDER BY has no bound to select with, so the outer one is the only
        // thing that orders the answer at all.
        assertThat(render("SELECT project_id FROM dial_usage_log GROUP BY project_id"
                + " HAVING count(*) > 1 ORDER BY count(*) DESC"))
                .containsIgnoringCase("order by \"t0\".\"count\" desc nulls first limit 100");
    }

    @Test
    void carriedLimitAboveTheMaximumIsRefused() {
        // The other half of carrying a sealed block's bound outward. SqlCompositeTranslationTest asserts
        // that the outer block carries LIMIT 1500; only rendering reaches governance, and the point of
        // carrying the bound at all is that it lands somewhere governance can see — so the refusal has to
        // be asserted, not inferred from the page. A statement whose bound stayed inside would render
        // happily here and silently return the default 100 rows instead.
        final SqlCompositeTranslation plan = (SqlCompositeTranslation) frontEnd.plan(
                "SELECT project_id FROM dial_usage_log GROUP BY project_id ORDER BY count(*) DESC LIMIT 1500");
        assertThatThrownBy(() -> builder.build(ctx, plan.query(), models::get, StructuredQueryBuilder.MAX_LIMIT))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("page limit 1500 exceeds the maximum of 1000 rows");
    }

    /**
     * And a bound below the maximum is honoured at both levels rather than defaulted at either: the inner
     * block runs the 500 as written (any nested LIMIT does), and the outer block carries the same 500 —
     * which is the whole point of carrying it, since an unbounded outer block would take the 100 default
     * and answer a 500-row question with 100 rows. The outer {@code ORDER BY} is asserted here too: a
     * projection over an ordered subquery is not guaranteed to preserve its order, so the ordering has to
     * be in the statement that returns the rows.
     */
    @Test
    void sealedBoundIsHonouredAtBothLevelsWithTheOrderingCarried() {
        assertThat(render("SELECT project_id FROM dial_usage_log GROUP BY project_id"
                + " ORDER BY count(*) DESC LIMIT 500"))
                .containsIgnoringCase("order by \"count\" desc nulls first limit 500)")
                .containsIgnoringCase("order by \"t0\".\"count\" desc nulls first limit 500")
                .doesNotContain("limit 100");
    }

    /**
     * A grouped block whose projection drops its computed key renders with the grouping on the key the
     * statement wrote — the point being that the key survives into the inner block's own select list, since
     * {@code GROUP BY} in the rendered SQL names that column. Grouping on anything else here would answer a
     * different question, which is why this is asserted on the SQL and not only on the plan.
     */
    @Test
    void groupedBlockKeepsItsComputedKeyInTheInnerSelectList() {
        assertThat(render("SELECT avg(c) AS a FROM (SELECT lower(event_kind) AS k, sum(total_price) AS c"
                + " FROM dial_usage_log GROUP BY k) t"))
                .containsIgnoringCase("lower(\"dial_usage_log\".\"event_kind\") \"k\"")
                .containsIgnoringCase("group by \"k\") \"t0\"")
                .containsIgnoringCase("select avg(\"t0\".\"c\") \"a\"");
        // GROUP BY an expression the statement never projects: named after the expression, as an unnamed
        // select item is, and the projection over the derived table returns only what was asked for.
        assertThat(render("SELECT count(*) AS n FROM dial_usage_log GROUP BY lower(event_kind)"))
                .containsIgnoringCase("lower(\"dial_usage_log\".\"event_kind\") \"lower\"")
                .containsIgnoringCase("group by \"lower\") \"t0\"")
                .containsIgnoringCase("select \"t0\".\"n\" \"n\"");
        // And the ordered, bounded form keeps the bound at both levels, like the dropped-sort-key path.
        assertThat(render("SELECT c FROM (SELECT lower(event_kind) AS k, sum(total_price) AS c"
                + " FROM dial_usage_log GROUP BY k ORDER BY c DESC LIMIT 5) t"))
                .containsIgnoringCase("group by \"k\" order by \"c\" desc nulls first limit 5) \"t0\"")
                .containsIgnoringCase("\"t0\" where true order by \"c\" desc nulls first limit 5")
                .doesNotContain("limit 100");
    }

    /**
     * The rendered SQL for the two deduplication shapes. Asserted here because the defect they fix is wrong
     * <em>rows</em>, not an error: {@code select distinct 1 from "dial_usage_log"} renders happily and
     * returns one row where the statement asks for one per distinct key.
     */
    @Test
    void droppedDeduplicationKeyRendersInsideTheDerivedTable() {
        assertThat(render("SELECT 1 AS one FROM dial_usage_log GROUP BY lower(event_kind)"))
                .containsIgnoringCase("select distinct lower(\"dial_usage_log\".\"event_kind\") \"lower\"")
                .containsIgnoringCase("select 1 \"one\" from (select distinct")
                .doesNotContainIgnoringCase("select distinct 1");
        assertThat(render("SELECT project_id FROM (SELECT DISTINCT project_id, event_kind FROM dial_usage_log) x"))
                .containsIgnoringCase("select distinct \"dial_usage_log\".\"project_id\" \"project_id\","
                        + " \"dial_usage_log\".\"event_kind\" \"event_kind\"")
                .containsIgnoringCase("select \"t0\".\"project_id\" \"project_id\" from (select distinct");
        // An inner ORDER BY with no row bound is kept and rendered on the inner block, because ClickHouse
        // honours it: measured on 26.3.24, `SELECT c FROM (SELECT x % 3 AS k, sum(x) AS c FROM (SELECT
        // arrayJoin([1,2,3,4,5,6,7]) AS x) GROUP BY k ORDER BY c DESC) t` returns 12, 9, 7 in that order.
        // Pinned so the spec's claim is measured, not assumed.
        assertThat(render("SELECT c FROM (SELECT lower(event_kind) AS k, sum(total_price) AS c"
                + " FROM dial_usage_log GROUP BY k ORDER BY c DESC) t"))
                .containsIgnoringCase("order by \"c\" desc");
    }

    /**
     * The rendered SQL for a window and an expansion above a deduplication. Both are wrong <em>rows</em>
     * rather than an error when folded into one block, so the assertion that matters is that no single
     * block carries {@code distinct} together with the window or the expansion.
     */
    @Test
    void windowAndExpansionRenderOverTheDeduplicatedRows() {
        final String windowed = render("SELECT project_id, event_kind, row_number() OVER () AS rn"
                + " FROM (SELECT DISTINCT project_id, event_kind FROM dial_usage_log) x");
        assertThat(windowed)
                .containsIgnoringCase("from (select distinct \"dial_usage_log\".\"project_id\"")
                .containsIgnoringCase("row_number() over ()");
        // The one thing that must not happen: the numbering inside the deduplicating block, where it would
        // make every row unique before the deduplication ever sees them.
        assertThat(deduplicatingBlockOf(windowed)).doesNotContainIgnoringCase("row_number");
        final String expanded = render("SELECT project_id, ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS part"
                + " FROM (SELECT DISTINCT project_id, event_kind FROM dial_usage_log) x");
        assertThat(expanded).containsIgnoringCase("from (select distinct");
        assertThat(deduplicatingBlockOf(expanded)).doesNotContainIgnoringCase("arrayjoin");
    }

    /** The rendered SQL from the deduplicating derived table onwards, for asserting what it does NOT compute. */
    private static String deduplicatingBlockOf(String sql) {
        final int start = sql.toLowerCase().indexOf("from (select distinct");
        assertThat(start).as("no deduplicating derived table in: %s", sql).isNotNegative();
        return sql.substring(start);
    }

    @Test
    void unknownEntityInTheJoinFailsLikeTheDslPath() {
        final SqlCompositeTranslation plan = (SqlCompositeTranslation) frontEnd.plan(
                "SELECT a.event_id FROM dial_usage_log a JOIN events b ON a.user_hash = b.user_id");
        assertThatThrownBy(() -> builder.build(ctx, plan.query(), name -> {
            throw new QueryValidationException("entity '" + name + "' is not queryable");
        }, StructuredQueryBuilder.MAX_LIMIT))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("not queryable");
    }
}
