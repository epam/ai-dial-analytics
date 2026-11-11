package com.epam.aidial.analytics.exception;

/**
 * An instance setting body that does not validate against the catalog as the writing caller sees it.
 * Mapped to HTTP 422 with the stable code {@code validation_error}.
 */
public class SettingValidationException extends RuntimeException {
    public SettingValidationException(String message) {
        super(message);
    }
}
