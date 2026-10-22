package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.PageSpec;

import java.util.List;

/**
 * The outcome of translating a <b>composite</b> SQL statement — one that joins, reads a derived table or
 * CTE, or carries a subquery: an executable plan of single-entity blocks ({@link CompositeQuery}) plus the
 * result column descriptors, exactly as {@link SqlTranslation} carries them for a single-block statement.
 * It executes through the same pipeline and governance; what it lacks is a {@code StructuredQuery}
 * document, which is why {@code POST /v1/queries/translate-sql} refuses it.
 */
public record SqlCompositeTranslation(CompositeQuery query, List<ResultColumn> columns, boolean explicitLimit)
        implements SqlExecutableStatement {

    @Override
    public PageSpec page() {
        return query.block().page();
    }

    @Override
    public SqlCompositeTranslation withPage(OffsetPage page) {
        return new SqlCompositeTranslation(query.withPage(page), columns, explicitLimit);
    }
}
