package com.epam.aidial.analytics.exception;

/**
 * A dashboard addressed by id either does not exist or is not visible to the caller. The two cases share
 * one exception, and so one response, so that no error path confirms another caller's {@code private}
 * dashboard exists. Mapped to HTTP 404 with the stable code {@code not_found}.
 */
public class DashboardNotFoundException extends RuntimeException {
    public DashboardNotFoundException(String message) {
        super(message);
    }
}
