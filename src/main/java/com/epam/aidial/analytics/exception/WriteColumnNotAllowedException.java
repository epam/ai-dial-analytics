package com.epam.aidial.analytics.exception;

/**
 * A pipeline-scoped write named a column outside {pipeline output columns} ∪ {grain key} ∪ {provenance}.
 * Mapped to HTTP 422 with the stable code {@code write_column_not_allowed}.
 */
public class WriteColumnNotAllowedException extends RuntimeException {
    public WriteColumnNotAllowedException(String message) {
        super(message);
    }
}
