package com.epam.aidial.analytics.exception;

/**
 * A table-management operation conflicts with the catalog's current state — a duplicate name, a
 * row write to a table that is not {@code ACTIVE}, or deleting a source that still has enrichments.
 * Mapped to HTTP 409.
 */
public class TableConflictException extends RuntimeException {

    public TableConflictException(String message) {
        super(message);
    }
}
