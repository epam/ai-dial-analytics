package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.PageSpec;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;

import java.util.List;

/**
 * The outcome of translating one single-block SQL statement: the equivalent {@link StructuredQuery}
 * (executed through the same pipeline as {@code POST /v1/queries/execute}) and the result column
 * descriptors used to derive the Arrow schema without executing the query. A statement that joins, nests
 * a derived table or carries a subquery becomes a {@link SqlCompositeTranslation} instead.
 *
 * <p>{@code explicitLimit} records whether the statement itself stated a {@code LIMIT} — the parser's
 * answer, not an inference from the resulting page. The two are not interchangeable: an omitted
 * {@code LIMIT} and {@code OFFSET}-without-{@code LIMIT} both reach the executor as a limit of 0 or an
 * absent page, which the shared governance treats as "apply the default", so the value cannot tell a
 * caller whether the bound was the client's intent. The Flight SQL transport needs that distinction to
 * decide between honoring a client's bound and probing for truncation against its own cap, and reading
 * it off the value would couple that contract to a sentinel documented as an internal convenience.
 */
public record SqlTranslation(StructuredQuery query, List<ResultColumn> columns, boolean explicitLimit)
        implements SqlExecutableStatement {

    @Override
    public PageSpec page() {
        return query.page();
    }

    @Override
    public SqlTranslation withPage(OffsetPage page) {
        return new SqlTranslation(query.withPage(page), columns, explicitLimit);
    }
}
