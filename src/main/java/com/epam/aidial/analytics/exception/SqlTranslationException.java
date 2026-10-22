package com.epam.aidial.analytics.exception;

/**
 * A SQL statement submitted to the Flight SQL endpoint could not be parsed, failed validation
 * against the query catalog, or uses a construct the structured-query DSL cannot express. Always a
 * client error; the Flight layer maps it to gRPC {@code INVALID_ARGUMENT}. Messages name the
 * offending construct and never echo backend internals.
 */
public class SqlTranslationException extends RuntimeException {

    public SqlTranslationException(String message) {
        super(message);
    }

    public SqlTranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
