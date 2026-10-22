package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CompositeQueryEntities} — the leaf-entity collector a saved query's {@code source} is derived
 * from. The base case is exercised directly against a hand-built {@link CompositeQuery}; the join,
 * derived-table and subquery cases are exercised through real plans from {@link SqlQueryFrontEnd} over
 * three minimal single-column entities, matching how {@code SavedQueryBodyResolver} actually calls it.
 */
class CompositeQueryEntitiesTest {

    private final SqlQueryFrontEnd frontEnd = newFrontEnd();

    private static SqlQueryFrontEnd newFrontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY, "b_entity", "c_entity"));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        when(catalog.findModel(eq("b_entity"), any())).thenReturn(Optional.of(oneColumnModel("b_entity")));
        when(catalog.findModel(eq("c_entity"), any())).thenReturn(Optional.of(oneColumnModel("c_entity")));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    /** A minimal entity carrying only {@code project_id}, joinable with {@link DialUsageLogTestModel}. */
    private static QueryEntityModel oneColumnModel(String entity) {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("project_id", new QueryFieldBinding("project_id",
                DSL.field(DSL.name(entity, "project_id"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        final List<QuerySchemaFieldDto> schema = List.of(new QuerySchemaFieldDto(
                "project_id", QueryFieldType.STRING, null, null, "project_id", null, null, null, false, false));
        return new QueryEntityModel(entity, DSL.table(DSL.name(entity)), bindings, List.of(), schema);
    }

    private Set<String> entitiesOf(String sql) {
        final SqlStatementPlan plan = frontEnd.plan(sql);
        assertThat(plan).isInstanceOf(SqlCompositeTranslation.class);
        return frontEnd.entitiesOf((SqlCompositeTranslation) plan);
    }

    @Test
    void oneEntityFromPlainScanRelation() {
        final CompositeQuery direct = new CompositeQuery(
                new EntityRelation(DialUsageLogTestModel.ENTITY, List.of()),
                new StructuredQuery(null, null, QueryMode.ROW, false, List.of(), null, null, null, null),
                List.of());

        assertThat(CompositeQueryEntities.leafEntities(direct)).containsExactly(DialUsageLogTestModel.ENTITY);
    }

    @Test
    void oneEntityViaDerivedTableSealedOnTopOfItsOwnSort() {
        // The inner ORDER BY/LIMIT is a genuine second block: the outer ORDER BY seals it into a
        // DerivedRelation (sortCtx's "second ORDER BY/LIMIT level" rule), so this is truly composite
        // even though it reads only one entity — the shape of the motivating report.
        final String sql = "SELECT project_id FROM "
                + "(SELECT project_id FROM dial_usage_log ORDER BY project_id LIMIT 100) d "
                + "ORDER BY project_id";

        assertThat(entitiesOf(sql)).containsExactly(DialUsageLogTestModel.ENTITY);
    }

    @Test
    void twoEntitiesViaJoin() {
        final String sql = "SELECT a.project_id FROM dial_usage_log a "
                + "JOIN b_entity b ON a.project_id = b.project_id";

        assertThat(entitiesOf(sql)).containsExactlyInAnyOrder(DialUsageLogTestModel.ENTITY, "b_entity");
    }

    @Test
    void twoEntitiesViaAnInSubquery() {
        final String sql = "SELECT project_id FROM dial_usage_log "
                + "WHERE project_id IN (SELECT project_id FROM b_entity)";

        assertThat(entitiesOf(sql)).containsExactlyInAnyOrder(DialUsageLogTestModel.ENTITY, "b_entity");
    }

    @Test
    void threeEntitiesViaJoinOfDerivedTableWithScalarSubqueryAgainstThird() {
        final String sql = "SELECT d.project_id, (SELECT project_id FROM c_entity LIMIT 1) AS c_probe "
                + "FROM (SELECT project_id FROM dial_usage_log WHERE project_id IS NOT NULL) d "
                + "JOIN b_entity b ON d.project_id = b.project_id";

        assertThat(entitiesOf(sql)).containsExactlyInAnyOrder(
                DialUsageLogTestModel.ENTITY, "b_entity", "c_entity");
    }
}
