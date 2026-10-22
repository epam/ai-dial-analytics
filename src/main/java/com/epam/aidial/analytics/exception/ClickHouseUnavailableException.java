package com.epam.aidial.analytics.exception;

/**
 * Raised when ClickHouse cannot be reached or the connection times out. Mapped by
 * {@code DefaultExceptionHandler} to {@code 503 clickhouse_unavailable}. The cause is retained for
 * server-side logging only; it is never exposed to clients.
 */
public class ClickHouseUnavailableException extends RuntimeException {

    public ClickHouseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
