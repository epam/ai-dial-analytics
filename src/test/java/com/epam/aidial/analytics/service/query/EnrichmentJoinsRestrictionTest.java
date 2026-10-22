package com.epam.aidial.analytics.service.query;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A grain join restricted by the enrichment's own columns: the aggregate recompute's dirty-key shape. The
 * restriction replaces keying rather than accompanying it, so the assertions pin what the derived table's
 * {@code WHERE} holds — and, as importantly, what it does not.
 */
class EnrichmentJoinsRestrictionTest {

    private static final String ENRICHMENT = DialUsageLogTestModel.ENRICHMENT;
    private static final String SESSION = DialUsageLogTestModel.ENRICHMENT_COLUMN;
    private static final String KEYING = "\"usage_client_identity\".\"event_id\" IN (select";

    private final DSLContext render = DSL.using(SQLDialect.DEFAULT);
    private final QueryEntityModel keyed = DialUsageLogTestModel.modelWithKeyedEnrichment();
    private final Condition restriction = DSL.condition("\"usage_client_identity\".\"client_session_id\" IN ('s1')");
    private final List<Condition> sourcePredicates = List.of(DSL.condition("\"dial_usage_log\".\"success\" = true"));

    @Test
    void restrictionAloneEmitsTheDerivedTableWithOnlyThatConjunct() {
        final String sql = render(keyed, List.of(), Map.of(ENRICHMENT, restriction));

        assertThat(sql).contains("left outer join (select \"usage_client_identity\".\"event_id\"")
                .contains("from \"usage_client_identity\" where (\"usage_client_identity\".\"client_session_id\" IN ('s1'))")
                .doesNotContain(KEYING);
    }

    @Test
    void restrictionWithSourcePredicatesStillEmitsOnlyTheRestriction() {
        final String sql = render(keyed, sourcePredicates, Map.of(ENRICHMENT, restriction));

        // The source predicates' key set is not window-bounded; beside a restriction that already selects a
        // superset of the rows read, it would only add an unbounded set.
        assertThat(sql).contains("\"client_session_id\" IN ('s1')").doesNotContain(KEYING);
    }

    @Test
    void noRestrictionRendersExactlyWhatItRenderedBefore() {
        assertThat(render(keyed, sourcePredicates, Map.of())).isEqualTo(renderLegacy(keyed, sourcePredicates));
        assertThat(render(keyed, List.of(), Map.of())).isEqualTo(renderLegacy(keyed, List.of()));
        assertThat(render(keyed, sourcePredicates, Map.of())).contains(KEYING);
    }

    @Test
    void restrictionOnAnUnkeyableJoinIsRefusedRatherThanDropped() {
        final QueryEntityModel unkeyable = DialUsageLogTestModel.modelWithEnrichment();

        assertThatThrownBy(() -> render(unkeyable, List.of(), Map.of(ENRICHMENT, restriction)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ENRICHMENT);
    }

    private String render(
            QueryEntityModel model, List<Condition> predicates, Map<String, Condition> restrictions) {
        final SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        select.addSelect(model.bindings().get(SESSION).field());
        EnrichmentJoins.addReferenced(select, model, Set.of(SESSION), predicates, restrictions);
        return select.getSQL(ParamType.INLINED);
    }

    private String renderLegacy(QueryEntityModel model, List<Condition> predicates) {
        final SelectQuery<Record> select = render.selectQuery();
        select.addFrom(model.table());
        select.addSelect(model.bindings().get(SESSION).field());
        EnrichmentJoins.addReferenced(select, model, Set.of(SESSION), predicates);
        return select.getSQL(ParamType.INLINED);
    }
}
