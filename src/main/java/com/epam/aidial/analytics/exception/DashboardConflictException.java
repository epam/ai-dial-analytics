package com.epam.aidial.analytics.exception;

/**
 * A dashboard write lost to another: a replace carried a {@code generation} the stored dashboard has moved
 * past, or a concurrent write changed or removed the row before this one flushed. Nothing was changed.
 * Mapped to HTTP 409 with the stable code {@code dashboard_conflict}; the client re-reads the dashboard.
 */
public class DashboardConflictException extends RuntimeException {
    public DashboardConflictException(String message) {
        super(message);
    }
}
