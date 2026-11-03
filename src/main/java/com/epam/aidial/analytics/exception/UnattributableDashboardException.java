package com.epam.aidial.analytics.exception;

/**
 * A {@code private} dashboard cannot be created because the caller's principal did not resolve while
 * security is enabled, so the dashboard would have no owner and no caller could ever reach it again. The
 * token authenticated but carries no value for the configured principal claim, a misconfiguration the
 * caller cannot fix — mapped to HTTP 500 with the stable code {@code principal_unavailable}, exactly as
 * {@link UnattributableSavedQueryException} is.
 */
public class UnattributableDashboardException extends RuntimeException {
    public UnattributableDashboardException(String message) {
        super(message);
    }
}
