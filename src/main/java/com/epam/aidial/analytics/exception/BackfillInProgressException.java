package com.epam.aidial.analytics.exception;

/**
 * Raised when a backfill is triggered while another backfill run is still in progress. At most one
 * backfill runs at a time. Mapped by {@code DefaultExceptionHandler} to {@code 409 conflict}.
 */
public class BackfillInProgressException extends RuntimeException {

    public BackfillInProgressException(String message) {
        super(message);
    }
}
