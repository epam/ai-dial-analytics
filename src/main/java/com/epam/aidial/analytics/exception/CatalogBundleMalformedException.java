package com.epam.aidial.analytics.exception;

/**
 * An uploaded catalog bundle or {@code options} part that does not parse: unreadable JSON, a wrong
 * {@code format}, an unknown field, an unknown enum value or a constraint violation. Mapped to HTTP 400 with the stable
 * code {@code bad_request}; the message names the offending field where there is one, never input fragments.
 */
public class CatalogBundleMalformedException extends RuntimeException {
    public CatalogBundleMalformedException(String message) {
        super(message);
    }
}
