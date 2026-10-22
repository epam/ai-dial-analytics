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
 * Where a composite join's {@code WHERE} conjuncts land, and which operand is reduced to its partner's keys (the
 * {@code structured-query} spec's "A composite join's operand-only conjuncts restrict that operand" and "An
 * equi-join operand is reduced to the keys its restricted partner can produce"). End to end from SQL text, over
 * the {@code dial_usage_log} test model with its keyed identity enrichment.
 */
class CompositeOperandPushdownRenderTest {

    private static final String ENRICHED = "\"usage_client_identity.client_session_id\"";
    private static final String REDUCTION = "\"dial_usage_log\".\"trace_id\" in (select \"dial_usage_log\".\"trace_id\""
            + " from \"dial_usage_log\" where";

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final Map<String, QueryEntityModel> models =
            Map.of(DialUsageLogTestModel.ENTITY, DialUsageLogTestModel.modelWithKeyedEnrichment());
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
        return render(builder, sql);
    }

    private String render(CompositeQueryBuilder with, String sql) {
        final SqlCompositeTranslation plan = (SqlCompositeTranslation) frontEnd.plan(sql);
        return with.build(ctx, plan.query(), models::get, StructuredQueryBuilder.MAX_LIMIT)
                .getSQL(ParamType.INLINED).toLowerCase();
    }

    /** The derived table aliased {@code alias}: the parenthesised select that {@code ) "alias"} closes. */
    private static String operand(String sql, String alias) {
        final int close = sql.indexOf(") \"" + alias + "\"");
        assertThat(close).as("operand %s in %s", alias, sql).isPositive();
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            final char c = sql.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(' && --depth == 0) {
                return sql.substring(i + 1, close);
            }
        }
        throw new AssertionError("unbalanced operand " + alias);
    }

    /** Everything after the last operand: the join condition and the block's own clauses. */
    private static String outer(String sql) {
        return sql.substring(sql.lastIndexOf(") \"t1\"") + ") \"t1\"".length());
    }

    private static final String SELF_JOIN = "SELECT b.\"usage_client_identity.client_session_id\" AS s, count(*) AS c"
            + " FROM dial_usage_log a JOIN dial_usage_log b"
            + " ON a.trace_id = b.trace_id AND a.core_parent_span_id = b.core_span_id"
            + " WHERE a.request_time >= TIMESTAMP '2026-10-13 00:00:00' AND a.request_time < TIMESTAMP '2026-10-14 00:00:00'"
            + " GROUP BY b.\"usage_client_identity.client_session_id\" ORDER BY c DESC LIMIT 20";

    @Test
    void innerSelfJoinMovesTheWindowIntoItsSideAndReducesTheOther() {
        final String sql = render(SELF_JOIN);
        final String a = operand(sql, "t0");
        final String b = operand(sql, "t1");

        assertThat(a).contains("where (\"dial_usage_log\".\"request_time\" >=")
                .contains("\"dial_usage_log\".\"request_time\" <")
                .doesNotContain(" in (select");
        assertThat(outer(sql)).doesNotContain("request_time");
        // b is reduced to the trace ids a's window can produce, built from a's source conjuncts.
        assertThat(b).contains(REDUCTION + " (\"dial_usage_log\".\"request_time\" >=");
        // ... and the reduction keys b's identity join, which otherwise hashes the whole enrichment.
        final String keyed = "\"usage_client_identity\".\"event_id\" in (select \"dial_usage_log\".\"event_id\""
                + " from \"dial_usage_log\" where ";
        assertThat(b).contains(keyed);
        assertThat(b.substring(b.indexOf(keyed) + keyed.length())).startsWith("(" + REDUCTION);
        assertThat(b).contains(ENRICHED);
    }

    @Test
    void theReferenceBuilderRendersNeitherTheMoveNorTheReduction() {
        final String sql = render(builder.withoutOperandPushdown(), SELF_JOIN);

        assertThat(operand(sql, "t0")).endsWith("where true");
        assertThat(operand(sql, "t1")).doesNotContain(REDUCTION);
        assertThat(outer(sql)).contains("\"t0\".\"request_time\" >=");
    }

    @Test
    void leftJoinMovesIntoItsPreservedSideAndReducesTheNullSupplyingSide() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a LEFT JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.deployment = 'x' AND b.project_id = 'y'");

        assertThat(operand(sql, "t0")).contains("\"dial_usage_log\".\"deployment\" = 'x'").doesNotContain(" in (select");
        assertThat(operand(sql, "t1")).contains(REDUCTION + " \"dial_usage_log\".\"deployment\" = 'x')")
                .doesNotContain("\"project_id\" = 'y'");
        assertThat(outer(sql)).contains("\"t1\".\"project_id\" = 'y'").doesNotContain("deployment");
    }

    @Test
    void rightJoinMovesIntoItsPreservedSideAndReducesTheNullSupplyingSide() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a RIGHT JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.deployment = 'x' AND b.project_id = 'y'");

        assertThat(operand(sql, "t1")).contains("\"dial_usage_log\".\"project_id\" = 'y'").doesNotContain(" in (select");
        assertThat(operand(sql, "t0")).contains(REDUCTION + " \"dial_usage_log\".\"project_id\" = 'y')")
                .doesNotContain("\"deployment\" = 'x'");
        assertThat(outer(sql)).contains("\"t0\".\"deployment\" = 'x'").doesNotContain("project_id");
    }

    @Test
    void fullJoinMovesNothing() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a FULL JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.deployment = 'x' AND b.project_id = 'y'");

        assertThat(operand(sql, "t0")).endsWith("where true");
        assertThat(operand(sql, "t1")).endsWith("where true");
        assertThat(outer(sql)).contains("\"t0\".\"deployment\" = 'x'").contains("\"t1\".\"project_id\" = 'y'");
    }

    @Test
    void preservedSideOfAnOuterJoinIsNeverReduced() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a LEFT JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE b.project_id = 'y'");

        assertThat(operand(sql, "t0")).endsWith("where true");
        assertThat(operand(sql, "t1")).endsWith("where true");
        assertThat(outer(sql)).contains("\"t1\".\"project_id\" = 'y'");
    }

    @Test
    void innerJoinRestrictedOnBothSidesReducesEachByTheOthersOwnConjuncts() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.deployment = 'x' AND b.project_id = 'y'");
        final String a = operand(sql, "t0");
        final String b = operand(sql, "t1");

        assertThat(a).contains(REDUCTION + " \"dial_usage_log\".\"project_id\" = 'y')")
                .contains("\"dial_usage_log\".\"deployment\" = 'x'");
        assertThat(b).contains(REDUCTION + " \"dial_usage_log\".\"deployment\" = 'x')")
                .contains("\"dial_usage_log\".\"project_id\" = 'y'");
        // One hop: neither reduction is built from the other.
        assertThat(a).containsOnlyOnce(" in (select");
        assertThat(b).containsOnlyOnce(" in (select");
        assertThat(outer(sql)).endsWith("where true limit 100");
    }

    @Test
    void conjunctOverBothSidesStaysAboveTheJoin() {
        final String sql = render("SELECT a.event_id FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.deployment = b.parent_deployment");

        assertThat(operand(sql, "t0")).endsWith("where true");
        assertThat(operand(sql, "t1")).endsWith("where true");
        assertThat(outer(sql)).contains("\"t0\".\"deployment\" = \"t1\".\"parent_deployment\"");
    }

    @Test
    void enrichmentConjunctMovesButBuildsNoReduction() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.\"usage_client_identity.client_session_id\" = 'x'");

        assertThat(operand(sql, "t0")).contains("\"usage_client_identity\".\"client_session_id\" = 'x'");
        assertThat(operand(sql, "t1")).endsWith("where true");
    }

    @Test
    void keyOnAnEnrichmentColumnBuildsNoReduction() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.\"usage_client_identity.client_session_id\" = b.trace_id WHERE a.deployment = 'x'");

        assertThat(operand(sql, "t0")).contains("\"dial_usage_log\".\"deployment\" = 'x'");
        assertThat(operand(sql, "t1")).endsWith("where true");
    }

    @Test
    void crossJoinMovesIntoBothSidesAndReducesNeither() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a CROSS JOIN dial_usage_log b"
                + " WHERE a.deployment = 'x' AND b.project_id = 'y'");

        assertThat(operand(sql, "t0")).contains("\"dial_usage_log\".\"deployment\" = 'x'").doesNotContain(" in (select");
        assertThat(operand(sql, "t1")).contains("\"dial_usage_log\".\"project_id\" = 'y'").doesNotContain(" in (select");
    }

    @Test
    void subqueryConjunctStaysAboveTheJoin() {
        final String sql = render("SELECT a.event_id FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.chat_id IN (SELECT chat_id FROM dial_usage_log WHERE deployment = 'z')");

        assertThat(operand(sql, "t0")).endsWith("where true");
        assertThat(outer(sql)).contains("\"t0\".\"chat_id\" in (select");
    }

    @Test
    void lambdaConjunctStaysAboveTheJoin() {
        final String sql = render("SELECT a.event_id FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE arrayExists(x -> starts_with(x, 'coder-'), a.execution_path)");

        assertThat(operand(sql, "t0")).endsWith("where true");
        assertThat(operand(sql, "t1")).endsWith("where true");
        assertThat(outer(sql)).contains("arrayexists(x -> ");
    }

    @Test
    void conjunctOverNestedJoinOperandStaysAboveTheJoin() {
        final String sql = render("SELECT a.event_id FROM dial_usage_log a JOIN dial_usage_log b ON a.trace_id = b.trace_id"
                + " JOIN dial_usage_log c ON b.trace_id = c.trace_id WHERE a.deployment = 'x'");

        // a and b render flat as the left operand of the outer join; only its entity operands could take a move.
        assertThat(operand(sql, "t0")).endsWith("where true");
        assertThat(operand(sql, "t1")).endsWith("where true");
        assertThat(operand(sql, "t2")).endsWith("where true");
        assertThat(sql.substring(sql.lastIndexOf(") \"t2\""))).contains("\"t0\".\"deployment\" = 'x'");
    }

    @Test
    void movedColumnIsNoLongerProjectedByItsOperand() {
        final String sql = render("SELECT a.event_id, b.chat_id FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id WHERE a.deployment = 'x'");

        assertThat(operand(sql, "t0")).doesNotContain("\"deployment\" \"deployment\"");
    }
}
