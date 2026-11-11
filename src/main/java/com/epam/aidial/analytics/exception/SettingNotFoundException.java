package com.epam.aidial.analytics.exception;

/**
 * An instance setting that has never been written. Mapped to HTTP 404 with the stable code
 * {@code setting_not_found}, so a client can tell "not configured" apart from an unknown path.
 */
public class SettingNotFoundException extends RuntimeException {
    public SettingNotFoundException(String message) {
        super(message);
    }
}
