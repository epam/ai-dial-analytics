package com.epam.aidial.analytics.exception;

/**
 * A schema change or an enrichment delete would empty a role's column policy. Refused before any DDL, because an
 * empty allowlist is not a valid policy and a {@code null} one would show the role every column. Mapped to HTTP 409
 * with the stable code {@code column_in_column_policy}.
 */
public class ColumnInColumnPolicyException extends RuntimeException {
    public ColumnInColumnPolicyException(String message) {
        super(message);
    }
}
