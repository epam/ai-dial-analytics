package com.epam.aidial.analytics.exception;

/**
 * A write could not take a table definition's row lock within {@code tables.lock-timeout}, because another change to the
 * table held it. Nothing was changed, so the request may be retried. Mapped to HTTP 409 with the stable code
 * {@code table_busy}.
 */
public class TableBusyException extends RuntimeException {
    public TableBusyException(String message) {
        super(message);
    }
}
