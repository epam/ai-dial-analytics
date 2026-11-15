package com.epam.aidial.analytics.exception;

/**
 * A pipeline or export path references a column a column policy hides from the calling identity. Mapped to HTTP 403
 * with the stable code {@code column_not_entitled}; checked before {@code sensitive_column_not_entitled}.
 */
public class ColumnNotEntitledException extends RuntimeException {
    public ColumnNotEntitledException(String message) {
        super(message);
    }
}
