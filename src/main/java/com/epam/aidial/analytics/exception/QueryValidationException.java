package com.epam.aidial.analytics.exception;

/**
 * Raised when a structured query is malformed or references an unknown/unsupported entity, field,
 * function, or feature, or when ClickHouse rejects the rendered SQL as not type-checking against the
 * data. These are client errors. Mapped by {@code DefaultExceptionHandler} to {@code 400 bad_request}.
 * The message is safe to expose to clients; any underlying cause is retained for server-side logging
 * only.
 */
public class QueryValidationException extends RuntimeException {

    public QueryValidationException(String message) {
        super(message);
    }

    public QueryValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
