package com.epam.aidial.analytics.exception;

/**
 * Raised when a backfill is triggered with an invalid date range (missing/unparseable dates,
 * {@code from} after {@code to}, {@code to} in the future, or a span exceeding the configured
 * maximum). Mapped by {@code DefaultExceptionHandler} to {@code 400 bad_request}.
 */
public class BackfillRangeException extends RuntimeException {

    public BackfillRangeException(String message) {
        super(message);
    }
}
