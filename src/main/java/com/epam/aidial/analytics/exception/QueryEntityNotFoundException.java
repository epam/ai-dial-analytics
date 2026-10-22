package com.epam.aidial.analytics.exception;

/**
 * Raised when a schema-discovery request names an entity that is not registered as queryable. Mapped
 * by {@code DefaultExceptionHandler} to {@code 404 not_found}.
 */
public class QueryEntityNotFoundException extends RuntimeException {

    public QueryEntityNotFoundException(String message) {
        super(message);
    }
}
