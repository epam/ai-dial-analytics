package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.sql.SqlCompositeTranslation;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.translate.CompositeQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.security.Privilege;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Composite join operand pushdown and semi-join reduction against a real ClickHouse (the {@code structured-query}
 * spec's "A composite join's operand-only conjuncts restrict that operand" and "An equi-join operand is reduced to
 * the keys its restricted partner can produce"): every statement is rendered with and without the rewrites
 * ({@link CompositeQueryBuilder#withoutOperandPushdown()}) and run under {@code join_use_nulls = 1}, as the composite
 * surface runs it; the two results must be identical byte for byte.
 *
 * <p>The fixture is a span tree over two days, with the window on the second: a root whose children both fall in
 * the window (duplicate keys on the restricted side), a root inside the window with a NULL parent, a child whose
 * parent does not exist (a partnerless preserved row), and a whole trace outside the window.
 */
class CompositeOperandPushdownIntegrationTest extends AbstractClickHouseIntegrationTest {

    // Recent days: the payload table's 90-day TTL drops a historical row before the assertion runs.
    private static final String DAY_TWO = FIXTURE_DATE;
    private static final String DAY_ONE = java.time.LocalDate.parse(FIXTURE_DATE).minusDays(1).toString();
    private static final String WINDOW = "a.request_time >= TIMESTAMP '" + DAY_TWO + " 00:00:00'";

    @Autowired
    private CatalogQueryService catalog;

    @Autowired
    private SqlQueryFrontEnd frontEnd;

    @Autowired
    private CompositeQueryBuilder builder;

    @Autowired
    private DSLContext renderContext;

    @BeforeEach
    void seed() {
        truncate();
        span("T1", "r1", null, DAY_ONE, "01", "d1", "{\"email\":\"root1@x.io\"}");
        span("T1", "c1", "r1", DAY_TWO, "01", "d2", "{\"email\":\"child1@x.io\"}");
        span("T1", "c2", "r1", DAY_TWO, "02", "d2", null);
        span("T2", "r2", null, DAY_TWO, "03", "d1", "{\"email\":\"root2@x.io\"}");
        span("T2", "c3", "r2", DAY_TWO, "04", "d2", "{}");
        span("T3", "c4", "missing", DAY_TWO, "05", "d2", null);
        span("T4", "r4", null, DAY_ONE, "02", "d1", null);
        span("T4", "c5", "r4", DAY_ONE, "03", "d2", "{\"email\":\"old@x.io\"}");
    }

    private void span(String trace, String span, String parent, String day, String second, String deployment,
            String claims) {
        final String at = day + " 00:00:" + second + ".000";
        execute("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, deployment, trace_id,"
                + " core_span_id, core_parent_span_id) VALUES ('" + eventIdFor(trace + span) + "', '" + at
                + "', 'chat', 'p1', '" + deployment + "', '" + trace + "', '" + span + "', "
                + (parent == null ? "NULL" : "'" + parent + "'") + ")");
        if (claims != null) {
            execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_body,"
                    + " jwt_claims) VALUES ('" + eventIdFor(trace + span) + "', '" + at + "', '" + trace + "', '" + span
                    + "', '" + claims + "', '" + claims + "')");
        }
    }

    private String render(CompositeQueryBuilder with, String sql) {
        final SqlCompositeTranslation plan = (SqlCompositeTranslation) frontEnd.plan(sql);
        final Function<String, QueryEntityModel> models =
                name -> catalog.findModel(name, Privilege.TABLE_READ).orElseThrow();
        return with.build(renderContext, plan.query(), models, StructuredQueryBuilder.MAX_LIMIT).getSQL(ParamType.INLINED);
    }

    private String run(String rendered) {
        return queryText(rendered + " SETTINGS join_use_nulls = 1, do_not_merge_across_partitions_select_final = 1");
    }

    /**
     * Asserts the rewritten statement returns the reference's rows, and that it is a rewrite at all unless the join
     * admits none (a FULL join); returns the rows.
     */
    private String assertIdentical(String sql, int expectedRows) {
        final String rewritten = render(builder, sql);
        final String unrewritten = render(builder.withoutOperandPushdown(), sql);
        if (!sql.contains("FULL JOIN")) {
            assertThat(rewritten).as("the rewrite ran").isNotEqualTo(unrewritten);
        }
        final String reference = run(unrewritten);
        assertThat(run(rewritten)).as(sql).isEqualTo(reference);
        assertThat(reference.lines().count() - 2).as("rows of %s:%n%s", sql, reference).isEqualTo(expectedRows);
        return reference;
    }

    @Test
    void parentSpanSelfJoinReturnsTheUnreducedRows() {
        // j5's shape: only the child side is bounded; the parent side is reduced to the window's traces.
        // The parent side reads a payload column, so its enrichment join is keyed by the reduction.
        final String rows = assertIdentical("SELECT b.core_span_id AS parent,"
                + " max(b.\"dial_usage_log_payload.request_body\") AS body, count(*) AS c"
                + " FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id AND a.core_parent_span_id = b.core_span_id WHERE " + WINDOW
                + " GROUP BY b.core_span_id ORDER BY c DESC, parent", 2);
        // r1 (outside the window) is the parent of two in-window children; r2 of one.
        assertThat(rows).contains("r1\t{\"email\":\"root1@x.io\"}\t2").contains("r2\t{\"email\":\"root2@x.io\"}\t1");

        // j5 as written: an object path of the parent side, grouped.
        final String users = assertIdentical("SELECT b.deployment AS parent_deployment,"
                + " json_extract_string(b.\"dial_usage_log_payload.jwt_claims\", 'email') AS parent_user, count(*) AS c"
                + " FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id AND a.core_parent_span_id = b.core_span_id WHERE " + WINDOW
                + " GROUP BY b.deployment, json_extract_string(b.\"dial_usage_log_payload.jwt_claims\", 'email')"
                + " ORDER BY c DESC, parent_user", 2);
        assertThat(users).contains("d1\troot1@x.io\t2").contains("d1\troot2@x.io\t1");
    }

    @Test
    void leftJoinKeepsPartnerlessAndNullKeyedPreservedRows() {
        assertIdentical("SELECT a.core_span_id AS child, b.core_span_id AS parent, b.deployment AS d"
                + " FROM dial_usage_log a LEFT JOIN dial_usage_log b ON a.core_parent_span_id = b.core_span_id"
                + " WHERE " + WINDOW + " ORDER BY child, parent", 5);
    }

    @Test
    void rightJoinReducesItsNullSupplyingLeftSide() {
        assertIdentical("SELECT a.core_span_id AS child, b.core_span_id AS parent"
                + " FROM dial_usage_log a RIGHT JOIN dial_usage_log b ON a.core_parent_span_id = b.core_span_id"
                + " WHERE b.deployment = 'd1' ORDER BY parent, child", 4);
    }

    @Test
    void fullJoinIsUntouched() {
        assertIdentical("SELECT a.core_span_id AS child, b.core_span_id AS parent"
                + " FROM dial_usage_log a FULL JOIN dial_usage_log b ON a.core_parent_span_id = b.core_span_id"
                + " WHERE " + WINDOW + " ORDER BY child, parent", 5);
    }

    @Test
    void innerJoinRestrictedOnBothSidesReturnsTheUnreducedRows() {
        assertIdentical("SELECT a.core_span_id AS child, b.core_span_id AS parent"
                + " FROM dial_usage_log a JOIN dial_usage_log b ON a.trace_id = b.trace_id"
                + " WHERE " + WINDOW + " AND b.deployment = 'd1' ORDER BY child, parent", 4);
    }
}
