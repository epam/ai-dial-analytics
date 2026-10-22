package com.epam.aidial.analytics.exception;

/**
 * A rule references a {@code sensitive}-flagged column (in its filter or a bound input binding)
 * without an explicit grant. Mapped to HTTP 403 with the stable code
 * {@code sensitive_column_not_entitled}.
 */
public class SensitiveColumnNotEntitledException extends RuntimeException {
    public SensitiveColumnNotEntitledException(String message) {
        super(message);
    }
}
