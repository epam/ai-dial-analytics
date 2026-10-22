package com.epam.aidial.analytics.exception;

/**
 * Raised when a backfill is triggered while the feature is disabled ({@code backfill.enabled=false}).
 * Mapped by {@code DefaultExceptionHandler} to {@code 503 backfill_disabled}.
 */
public class BackfillDisabledException extends RuntimeException {

    public BackfillDisabledException(String message) {
        super(message);
    }
}
