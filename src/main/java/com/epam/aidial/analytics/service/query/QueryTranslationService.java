package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Translates a {@link StructuredQuery} into the external SQL dialect without executing it. The query
 * is validated exactly as {@link StructuredQueryService} would for execution — same entity
 * resolution, and a full {@link StructuredQueryBuilder} pass (field allowlist, function catalog, mode
 * coherence, limit governance) whose result is discarded — so translation failures carry the same
 * messages the execute endpoint produces, and rendering only ever sees a query that would execute.
 * ClickHouse is never contacted on this path.
 *
 * <p>Authorization is {@link Privilege#TABLE_GET} on every table the query resolves, not
 * {@link Privilege#TABLE_READ}: this endpoint validates against the resolved catalog model and returns SQL,
 * so it discloses schema rather than data. Sensitive columns remain governed by
 * the {@code TABLE_SENSITIVE} clearance on their owning table, because the model this resolves against has them
 * filtered out — referencing one fails as an ordinary unknown field.
 */
@Component
@RequiredArgsConstructor
public class QueryTranslationService {

    private final CatalogQueryService catalog;
    private final PortableQueryRenderer renderer;

    /** Validates {@code query} against the catalog and renders it as external-dialect SQL. */
    public String toSql(StructuredQuery query) {
        if (query == null) {
            throw new QueryValidationException("query must not be null");
        }
        final QueryEntityModel model = catalog.findModel(query.entity(), Privilege.TABLE_GET)
                .orElseThrow(() -> new QueryValidationException("entity '" + query.entity()
                        + "' is not queryable; supported entities: "
                        + catalog.queryableEntityNames(Privilege.TABLE_GET)));
        return renderer.render(query, model);
    }
}
