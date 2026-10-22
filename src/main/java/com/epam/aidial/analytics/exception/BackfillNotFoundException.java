package com.epam.aidial.analytics.exception;

/**
 * Raised when the status of a backfill {@code runId} the service does not know is requested (run state
 * is in-memory and not retained across a restart or beyond the bounded registry). Mapped by
 * {@code DefaultExceptionHandler} to {@code 404 not_found}.
 */
public class BackfillNotFoundException extends RuntimeException {

    public BackfillNotFoundException(String message) {
        super(message);
    }
}
