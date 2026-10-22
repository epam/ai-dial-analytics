package com.epam.aidial.analytics.exception;

/**
 * A presented scan cursor is malformed, fails its HMAC, carries an unknown/retired key id, or does
 * not match the pipeline's source. Mapped to HTTP 400 with the stable code {@code invalid_cursor}.
 */
public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String message) {
        super(message);
    }
}
