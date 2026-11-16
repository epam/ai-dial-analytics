package com.epam.aidial.analytics.exception;

/**
 * An app-routes write that would drop a pair an enabled pipeline calls. Mapped to HTTP 409 with the stable code
 * {@code app_route_in_use}.
 */
public class AppRouteInUseException extends RuntimeException {
    public AppRouteInUseException(String message) {
        super(message);
    }
}
