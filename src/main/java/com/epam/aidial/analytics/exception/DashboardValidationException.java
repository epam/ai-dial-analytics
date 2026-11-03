package com.epam.aidial.analytics.exception;

/**
 * A dashboard create or replace body breaks an envelope rule of the dashboards capability. Mapped to HTTP
 * 422 with the stable code {@code validation_error}, the code a saved query's envelope rules use.
 */
public class DashboardValidationException extends RuntimeException {
    public DashboardValidationException(String message) {
        super(message);
    }
}
