package com.epam.aidial.analytics.exception;

/**
 * A table-management request is well-formed JSON but semantically invalid — e.g. an enrichment
 * without a valid source, an ordering/foreign key that does not reference a declared column, an
 * {@code ARRAY} column missing its element type, or an invalid table/column identifier. Mapped to
 * HTTP 422.
 */
public class TableValidationException extends RuntimeException {

    public TableValidationException(String message) {
        super(message);
    }
}
