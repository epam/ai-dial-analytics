package com.epam.aidial.analytics.service.query.saved;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryTranslationService;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.sql.SqlCompositeTranslation;
import com.epam.aidial.analytics.service.query.sql.SqlMetadataResult;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryRequestDto;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Catalog-dependent validation of a saved query's body, and derivation of its {@code source}.
 *
 * <p><strong>A saved query must be executable as stored.</strong> A {@code query}-kind body is
 * validated by the same translation {@code POST /v1/queries/translate} performs — full entity
 * resolution, field allowlist, function catalog, mode coherence, limit governance, without contacting
 * ClickHouse — and stays single-entity, because the structured-query DSL has no wire representation
 * for a join or a derived table. A {@code sql}-kind body is validated by the same parse-and-plan front
 * end {@code POST /v1/queries/execute-sql} runs, which additionally accepts a composite statement (a
 * join, a derived table, a CTE, or a subquery): saving a body never needed the stricter, DSL-only
 * translation {@code POST /v1/queries/translate-sql} performs, only proof the body executes and the
 * entities it reads from. A body that will not plan/translate is one no ADAS endpoint could execute, so
 * storing it would persist a query that can never run.
 *
 * <p>Translation alone is not quite the whole of what {@code POST /v1/queries/translate} checks: it
 * declares {@code @Valid @RequestBody StructuredQuery}, so the body's bean-validation constraints are
 * enforced at its controller boundary, before translation ever runs. Translation does not re-check them
 * — a null {@code mode}, for instance, translates happily as a row query — so {@code SavedQueryController}
 * declares the same {@code @Valid} on its request body. Without it a body the execute endpoint would
 * refuse could be stored here.
 *
 * <p>Translation/plan failures are <strong>passed through unchanged</strong>, so saving a body and
 * running it report the same error for the same mistake. That also avoids inspecting exception messages
 * to tell an unresolvable column apart from other translation failures — a distinction that would be
 * fragile precisely where it matters, since an unresolvable column is how a restricted column must
 * appear.
 *
 * <p>{@code source} is <em>derived</em> from the body, never accepted from the request: two places
 * holding the same fact can disagree. It is a non-empty list of every entity the body reads from — one
 * element for a {@code query}-kind body or a single-entity {@code sql}-kind one, more than one for a
 * {@code sql}-kind body that joins, or that subqueries against, another entity.
 */
@Component
@RequiredArgsConstructor
public class SavedQueryBodyResolver {

    private final CatalogQueryService catalog;
    private final QueryTranslationService queryTranslation;
    private final SqlQueryFrontEnd sqlQueryFrontEnd;

    /**
     * Validates the request's body against the caller's view of the catalog and returns the entities it
     * queries. Assumes envelope validation has already run, so exactly one body is present.
     */
    public List<String> resolveSource(SavedQueryRequestDto request) {
        return request.query() != null ? resolveStructured(request.query()) : resolveSql(request.sql());
    }

    /**
     * Resolves the entity the body names, then lets translation have the final say on whether the body is
     * executable. Translation is the only authority here: nothing inspects the body on its own account.
     */
    private List<String> resolveStructured(StructuredQuery query) {
        final QueryEntityModel model = requireModel(query.entity());
        // Validation pass only; the rendered SQL is deliberately discarded. This resolves the entity a
        // second time — a save is not a hot path, and keeping translation as the single authority on
        // what is executable is worth one extra catalog read.
        queryTranslation.toSql(query);
        return List.of(model.entity());
    }

    /**
     * For a SQL body, planning comes first and doubles as the validation pass: it is also what names the
     * entities {@code source} is derived from, which the SQL text itself is never parsed for. Unlike the
     * structured path this calls {@code plan}, not {@code translate}: a composite statement executes
     * through the same pipeline as a single-block one even though it has no DSL document, and a saved
     * body never needed that document.
     */
    private List<String> resolveSql(String sql) {
        return switch (sqlQueryFrontEnd.plan(sql)) {
            case SqlTranslation translation -> List.of(requireModel(translation.query().entity()).entity());
            case SqlCompositeTranslation composite -> resolveComposite(composite);
            // A statement against INFORMATION_SCHEMA has no entity and cannot be a saved query's body
            // any more than a query-kind one naming a non-queryable entity can.
            case SqlMetadataResult ignored -> throw new QueryValidationException(
                    "catalog metadata statements have no structured-query equivalent and cannot be saved");
        };
    }

    /**
     * Every entity a composite plan reads from — through a join, a derived table, or a subquery —
     * resolved and validated exactly as the single-entity path resolves its one entity. Sorted so that
     * two saved queries reading the same entities always report {@code source} in the same order.
     */
    private List<String> resolveComposite(SqlCompositeTranslation composite) {
        final Set<String> entities = sqlQueryFrontEnd.entitiesOf(composite);
        return entities.stream().map(entity -> requireModel(entity).entity()).sorted().toList();
    }

    private QueryEntityModel requireModel(String entity) {
        return catalog.findModel(entity, Privilege.TABLE_READ)
                .orElseThrow(() -> new QueryValidationException("entity '" + entity
                        + "' is not queryable; supported entities: " + catalog.queryableEntityNames(Privilege.TABLE_READ)));
    }
}
