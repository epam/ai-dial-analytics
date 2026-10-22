package com.epam.aidial.analytics.exception;

/** A table definition addressed by name does not exist in the catalog. Mapped to HTTP 404. */
public class TableNotFoundException extends RuntimeException {

    public TableNotFoundException(String message) {
        super(message);
    }
}
