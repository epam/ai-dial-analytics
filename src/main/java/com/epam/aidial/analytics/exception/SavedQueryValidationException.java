package com.epam.aidial.analytics.exception;

/**
 * A saved query failed envelope validation: a blank name, both or neither of {@code query}/{@code sql},
 * a server-assigned field supplied in the request, a runtime parameter expression (unsupported — a
 * parameterized body cannot be executed by the query translator), a malformed {@code time} intent, a
 * body over the configured size cap, or a body the query translator will not accept (a saved query must
 * be executable as stored). Mapped to HTTP 422 with the stable code {@code validation_error}.
 */
public class SavedQueryValidationException extends RuntimeException {
    public SavedQueryValidationException(String message) {
        super(message);
    }
}
