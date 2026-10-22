package com.epam.aidial.analytics.service.query.sql;

import java.util.List;

/**
 * The verdict on one submitted statement: whether {@code POST /v1/queries/execute-sql} would accept it,
 * why not where it would not, and what it would cost where it would.
 *
 * <p>{@code valid} answers only the accept/reject question. {@code warnings} never bear on it: a warning
 * describes a statement that passes every check and can still be refused at execution by the query
 * budget, which is reactive and therefore unknowable without running the statement.
 *
 * <p>{@code effectiveLimit} is the outermost row ceiling execution would apply — the statement's own
 * {@code LIMIT}, or the default where it carries none. Null for an invalid statement (there is no
 * translated query to read it from) and for a catalog-metadata statement (which no row limit governs).
 */
public record SqlValidation(boolean valid, Integer effectiveLimit, List<SqlIssue> issues, List<SqlIssue> warnings) {

    /** A statement that passes every check, with its effective ceiling and any advisory notes. */
    static SqlValidation accepted(Integer effectiveLimit, List<SqlIssue> warnings) {
        return new SqlValidation(true, effectiveLimit, List.of(), List.copyOf(warnings));
    }

    /** A rejected statement. Warnings are not computed for one: there is no translated query to read. */
    static SqlValidation rejected(List<SqlIssue> issues) {
        return new SqlValidation(false, null, List.copyOf(issues), List.of());
    }
}
