package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.SqlCompositeTranslation;
import com.epam.aidial.analytics.service.query.sql.SqlMetadataResult;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Plans and runs one {@code execute-sql} statement, for both of its response forms — the JSON envelope
 * ({@link SqlQueryController}) and CSV ({@link QueryCsvController}) — so the two cannot disagree on which
 * rows a statement returns. Only the formatting differs between them.
 */
@Component
@RequiredArgsConstructor
class SqlStatementRunner {

    private final SqlQueryFrontEnd sqlQueryFrontEnd;
    private final StructuredQueryService queryService;

    QueryResultPage run(String sql) {
        return switch (sqlQueryFrontEnd.plan(sql)) {
            // A catalog statement arrives already evaluated — its rows come from ADAS's own catalog, so
            // there is nothing to run and no page limit to apply; its size is bounded by the catalog.
            case SqlMetadataResult metadata -> new QueryResultPage(
                    metadata.columns().stream().map(ResultColumn::name).toList(), metadata.rows(), null);
            case SqlTranslation translation -> queryService.execute(translation.query());
            // Joins, derived tables and subqueries: the same pipeline and governance, applied to a plan of
            // single-entity blocks rather than to one StructuredQuery.
            case SqlCompositeTranslation composite -> queryService.execute(composite.query());
        };
    }
}
