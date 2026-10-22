package com.epam.aidial.analytics.service.query.sql;

/**
 * One problem found in a submitted statement, or one advisory note about a statement that is valid.
 *
 * <p>{@code code} is the stable machine-readable classification a caller may branch on — the
 * {@code ErrorView} {@code error} codes are the precedent, and the same rule applies here: a code, once
 * published, does not change meaning. {@code message} is the underlying diagnosis, carried verbatim so a
 * caller is never worse off than it would be reading the equivalent rejection from
 * {@code POST /v1/queries/execute-sql}.
 *
 * <p>{@code line} and {@code column} are 1-based and null wherever the diagnosis carries no position —
 * the governance rejections (an over-maximum limit, an unsupported field use) are properties of the
 * translated query rather than of a token. {@code remedy} is null unless the classification implies a
 * concrete fix; an unrecognised diagnosis keeps its message and offers no advice rather than guessing.
 */
public record SqlIssue(String code, String message, Integer line, Integer column, String remedy) {

    /** An issue with no position — a governance or whole-statement diagnosis. */
    public static SqlIssue of(String code, String message, String remedy) {
        return new SqlIssue(code, message, null, null, remedy);
    }
}
