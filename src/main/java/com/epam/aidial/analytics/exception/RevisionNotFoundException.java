package com.epam.aidial.analytics.exception;

/**
 * There is no state to return for a resource at the requested revision — either the revision does not
 * exist at all, or the resource did not exist at it (before its creation, or at/after its deletion).
 * Mapped to HTTP 404.
 *
 * <p>Deliberately one code for both: "there is no such revision" and "the resource did not exist then"
 * are the same answer to the caller, namely that there is no point-in-time state to read.
 */
public class RevisionNotFoundException extends RuntimeException {

    public RevisionNotFoundException(String message) {
        super(message);
    }
}
