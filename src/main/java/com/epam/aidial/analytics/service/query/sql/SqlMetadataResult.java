package com.epam.aidial.analytics.service.query.sql;

import java.util.List;
import java.util.Map;

/**
 * An already-evaluated catalog statement: the result column descriptors and the rows themselves.
 *
 * <p>Both fields deliberately carry the shapes the rest of the system already speaks — the same
 * {@link ResultColumn} list {@code ArrowTypeMapper} turns into a schema, and the same
 * {@code List<Map<String, Object>>} row form a {@code QueryResultPage} carries — so streaming a
 * catalog result over Flight and returning one over REST reuse the existing writers instead of
 * growing parallel ones.
 */
public record SqlMetadataResult(List<ResultColumn> columns, List<Map<String, Object>> rows)
        implements SqlStatementPlan {}
