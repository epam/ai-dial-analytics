package com.epam.aidial.analytics.exception;

/**
 * A {@code personal} saved query cannot be written because the caller's principal did not resolve while
 * security is enabled.
 *
 * <p>Such a row would be <strong>permanently orphaned</strong>: visibility is decided by
 * {@code owner_id = :author}, which no caller can ever satisfy against a null owner, so nobody could
 * read, replace, or delete it. Refusing the write is the only outcome that does not silently create
 * unreachable data.
 *
 * <p>It also signals a misconfiguration rather than a bad request: the token authenticated, so the
 * service already accepted it, but it carries no value for the configured principal claim. The caller
 * cannot fix that, which is why this is a 5xx — mapped to HTTP 500 with the stable code
 * {@code principal_unavailable}.
 */
public class UnattributableSavedQueryException extends RuntimeException {
    public UnattributableSavedQueryException(String message) {
        super(message);
    }
}
