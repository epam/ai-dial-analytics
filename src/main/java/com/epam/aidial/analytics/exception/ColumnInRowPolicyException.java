package com.epam.aidial.analytics.exception;

/**
 * A schema change would drop or rename a column that a row policy on the table references. Refused before
 * any DDL, because a policy over a column that no longer exists fails every read of the table once it is
 * enforced. Mapped to HTTP 409 with the stable code {@code column_in_row_policy}; distinct from the generic
 * {@code conflict} so the operator knows the dependency to remove is a row policy, not a pipeline.
 */
public class ColumnInRowPolicyException extends RuntimeException {
    public ColumnInRowPolicyException(String message) {
        super(message);
    }
}
