package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlCompositeTranslation;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ClickHouse SQL a set operation renders to, end to end from SQL text.
 *
 * <p>These assertions are the guard on the one thing the semantics rest on: the {@code ALL}/{@code
 * DISTINCT} keyword is always spelled out, so no server-side default mode can reinterpret the statement,
 * and every branch is parenthesized, so a branch carrying its own {@code ORDER BY}/{@code LIMIT} parses.
 * Offline; the plan comes from the real {@link SqlQueryFrontEnd} and the SQL from the real
 * {@link CompositeQueryBuilder}.
 */
class SetOperationRenderTest {

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final Map<String, QueryEntityModel> models =
            Map.of(DialUsageLogTestModel.ENTITY, DialUsageLogTestModel.model());
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

    private String render(String sql) {
        final SqlCompositeTranslation plan = (SqlCompositeTranslation) frontEnd.plan(sql);
        return builder.build(ctx, plan.query(), models::get, StructuredQueryBuilder.MAX_LIMIT)
                .getSQL(ParamType.INLINED);
    }

    @Test
    void unionAllRendersParenthesizedBranchesUnderGovernedLimit() {
        final String sql = render("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log");
        assertThat(sql).isEqualTo("select \"t0\".\"event_kind\" \"event_kind\" from ("
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true)"
                + " UNION ALL "
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true)"
                + ") \"t0\" where true limit 100");
    }

    @Test
    void everySpellingRendersItsKeywordExplicitly() {
        // The keyword is never left to the server's *_default_mode settings, which is what keeps a
        // statement ADAS accepted from being reinterpreted by a reconfigured ClickHouse.
        assertThat(render(setOp("UNION ALL"))).containsIgnoringCase(" UNION ALL ");
        assertThat(render(setOp("UNION DISTINCT"))).containsIgnoringCase(" UNION DISTINCT ");
        assertThat(render(setOp("INTERSECT ALL"))).containsIgnoringCase(" INTERSECT ALL ");
        assertThat(render(setOp("INTERSECT DISTINCT"))).containsIgnoringCase(" INTERSECT DISTINCT ");
        assertThat(render(setOp("EXCEPT ALL"))).containsIgnoringCase(" EXCEPT ALL ");
        assertThat(render(setOp("EXCEPT DISTINCT"))).containsIgnoringCase(" EXCEPT DISTINCT ");
    }

    @Test
    void bareIntersectAndExceptRenderAll() {
        // ClickHouse's default modes, not Calcite's DISTINCT parse.
        assertThat(render(setOp("INTERSECT"))).containsIgnoringCase(" INTERSECT ALL ");
        assertThat(render(setOp("EXCEPT"))).containsIgnoringCase(" EXCEPT ALL ");
    }

    @Test
    void trailingOrderByAndLimitRenderInsideTheLastBranch() {
        final String sql = render("SELECT event_kind FROM dial_usage_log"
                + " UNION DISTINCT SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 7");
        // The branch carries the ordering and the seven-row bound; the governed 100 bounds the combined
        // result above it. Exactly the two-limit shape ClickHouse gives the same text.
        assertThat(sql).isEqualTo("select \"t0\".\"event_kind\" \"event_kind\" from ("
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true)"
                + " UNION DISTINCT "
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true"
                + " order by \"event_kind\" asc nulls last limit 7)"
                + ") \"t0\" where true limit 100");
    }

    @Test
    void trailingOrderByWithNoLimitRendersInsideTheLastBranch() {
        // The clause with nothing beside it, which is where the collation used to vanish before it could be
        // rendered: Calcite's converter drops the sort of a set-operation input that carries no LIMIT or
        // OFFSET unless removeSortInSubQuery is disabled, and the statement came out with no ORDER BY at
        // all. Measured on ClickHouse 26.3.24: `SELECT 5 AS x UNION ALL SELECT arrayJoin([3,1,2]) AS x
        // ORDER BY x` returns 5, 1, 2, 3.
        final String sql = render("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind");
        assertThat(sql).isEqualTo("select \"t0\".\"event_kind\" \"event_kind\" from ("
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true)"
                + " UNION ALL "
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true"
                + " order by \"event_kind\" asc nulls last)"
                + ") \"t0\" where true limit 100");
    }

    @Test
    void branchLimitIsNeitherDefaultedNorCapped() {
        final String sql = render("(SELECT event_kind FROM dial_usage_log LIMIT 5000)"
                + " UNION ALL (SELECT event_kind FROM dial_usage_log LIMIT 5000)");
        assertThat(sql).contains("limit 5000");
        assertThat(sql).endsWith("limit 100");
    }

    @Test
    void trailingClauseRendersInsideTheFinalSelectOfMixedChain() {
        // Calcite's last operand is the INTERSECT node, so the clause has to travel down the right spine
        // to reach the SELECT ClickHouse binds it to. The rendered shape is the one 26.3.24 executes for
        // the same text: the ordering and the bound inside the innermost right branch and nowhere else.
        final String sql = render("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log"
                + " INTERSECT ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 4");
        assertThat(sql).isEqualTo("select \"t0\".\"event_kind\" \"event_kind\" from ("
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true)"
                + " UNION ALL "
                + "(select \"t1\".\"event_kind\" \"event_kind\" from ("
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true)"
                + " INTERSECT ALL "
                + "(select \"dial_usage_log\".\"event_kind\" \"event_kind\" from \"dial_usage_log\" where true"
                + " order by \"event_kind\" asc nulls last limit 4)"
                + ") \"t1\" where true)"
                + ") \"t0\" where true limit 100");
    }

    @Test
    void qualifierIsResolvedInsideJoinConditionAndCase() {
        // Two positions the qualifier pass used to skip, so Calcite's DISTINCT parse of a bare INTERSECT
        // survived to the SQL — the opposite of what ClickHouse reads there.
        final String onPredicate = render("SELECT a.event_kind FROM dial_usage_log a"
                + " JOIN dial_usage_log b ON a.event_kind = b.event_kind AND a.event_kind IN"
                + " (SELECT event_kind FROM dial_usage_log"
                + " INTERSECT SELECT event_kind FROM dial_usage_log)");
        assertThat(onPredicate).containsIgnoringCase(" INTERSECT ALL ");
        assertThat(onPredicate).doesNotContainIgnoringCase("INTERSECT DISTINCT");
        final String caseExpression = render("SELECT CASE WHEN event_kind IN (SELECT event_kind"
                + " FROM dial_usage_log INTERSECT SELECT event_kind FROM dial_usage_log)"
                + " THEN 1 ELSE 0 END AS f FROM dial_usage_log");
        assertThat(caseExpression).containsIgnoringCase(" INTERSECT ALL ");
        assertThat(caseExpression).doesNotContainIgnoringCase("INTERSECT DISTINCT");
    }

    @Test
    void nestedOperationIsReferencedByTheNameItsBranchesExpose() {
        // A first branch whose item is an unnamed expression: Calcite's row type names it EXPR$0 while the
        // branch renders the DSL's base name, so taking the operation's column names from the row type
        // made the block above reference a column the subquery does not expose — ClickHouse answers Code
        // 47 UNKNOWN_IDENTIFIER for that text. Nested, because at the top level the unnamed item is
        // aliased before validation and the two names agree.
        final String sql = render("WITH c AS (SELECT max(event_kind) FROM dial_usage_log"
                + " UNION ALL SELECT max(event_kind) FROM dial_usage_log) SELECT * FROM c");
        assertThat(sql).isEqualTo("select \"t0\".\"max\" \"max\" from ("
                + "(select max(\"dial_usage_log\".\"event_kind\") \"max\" from \"dial_usage_log\" where true)"
                + " UNION ALL "
                + "(select max(\"dial_usage_log\".\"event_kind\") \"max\" from \"dial_usage_log\" where true)"
                + ") \"t0\" where true limit 100");
        // The same holds for the other two nested positions, a derived table and a subquery operand.
        assertThat(render("SELECT * FROM (SELECT max(event_kind) FROM dial_usage_log"
                + " UNION ALL SELECT max(event_kind) FROM dial_usage_log) t"))
                .doesNotContain("EXPR$");
        assertThat(render("SELECT event_id FROM dial_usage_log WHERE event_kind IN"
                + " (SELECT max(event_kind) FROM dial_usage_log"
                + " UNION ALL SELECT max(event_kind) FROM dial_usage_log)"))
                .doesNotContain("EXPR$");
    }

    @Test
    void multiBranchChainRendersOneOperatorPerJoin() {
        final String sql = render("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log");
        assertThat(sql.toUpperCase().split(" UNION ALL ", -1)).hasSize(3);
    }

    private static String setOp(String operator) {
        return "SELECT event_kind FROM dial_usage_log " + operator
                + " SELECT event_kind FROM dial_usage_log";
    }
}
