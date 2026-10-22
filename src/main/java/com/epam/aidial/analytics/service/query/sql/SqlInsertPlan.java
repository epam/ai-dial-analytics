package com.epam.aidial.analytics.service.query.sql;

import java.util.List;

/**
 * One {@code INSERT INTO <target> (<columns>) <query>} statement, planned: the target and column list exactly as
 * written (identifier quotes removed, case kept), and the source query's single-block {@link SqlTranslation}. The
 * column list maps to {@code translation.query().select()} by position; nothing here checks the two agree in
 * length or match the target's columns — that is the aggregate SQL validator's job, which knows the target.
 *
 * @param target      the INSERT target as written, unquoted; a qualified name is joined with {@code .}
 * @param columns     the column-list names, in order, unquoted
 * @param translation the source query, planned by {@link SqlQueryFrontEnd#plan} at the caller's bar
 */
public record SqlInsertPlan(String target, List<String> columns, SqlTranslation translation) {

    public SqlInsertPlan {
        columns = List.copyOf(columns);
    }
}
