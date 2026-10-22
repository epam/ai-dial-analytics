package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.PageSpec;

import java.util.List;

/**
 * What the two executable outcomes of the SQL front end — a {@link SqlTranslation} and a
 * {@link SqlCompositeTranslation} — have in common for a transport: the result columns, whether the
 * statement stated a {@code LIMIT}, the page it stated, and a copy with a different page. The Flight SQL
 * transport's clamp-and-probe is written once over this rather than twice over the two records.
 */
public sealed interface SqlExecutableStatement extends SqlStatementPlan permits SqlTranslation, SqlCompositeTranslation {

    /** The result column descriptors, in output order. */
    List<ResultColumn> columns();

    /** Whether the statement itself stated a {@code LIMIT} (the parser's answer, never inferred from the page). */
    boolean explicitLimit();

    /** The outermost block's page as translated, {@code null} when it stated neither {@code LIMIT} nor {@code OFFSET}. */
    PageSpec page();

    /** The same statement with the outermost block's page replaced. */
    SqlExecutableStatement withPage(OffsetPage page);
}
