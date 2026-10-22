package com.epam.aidial.analytics.exception;

/**
 * A saved query addressed by id either does not exist or is not visible to the caller. The two cases
 * deliberately share one exception — and therefore one response — so that no error path confirms that
 * another caller's {@code personal} saved query exists. Mapped to HTTP 404 with the stable code
 * {@code not_found}.
 */
public class SavedQueryNotFoundException extends RuntimeException {
    public SavedQueryNotFoundException(String message) {
        super(message);
    }
}
