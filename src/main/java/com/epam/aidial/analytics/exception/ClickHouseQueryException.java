package com.epam.aidial.analytics.exception;

/**
 * Raised when ClickHouse is reachable but rejects a rendered query (e.g. invalid SQL for the data, a
 * type mismatch, or an unknown column). Since the query is the only client-controlled input on this
 * path, it is treated as a client error and mapped by {@code DefaultExceptionHandler} to
 * {@code 400 bad_request}. The cause is retained for server-side logging only; raw ClickHouse
 * messages are never exposed to clients.
 */
public class ClickHouseQueryException extends RuntimeException {

    public ClickHouseQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
