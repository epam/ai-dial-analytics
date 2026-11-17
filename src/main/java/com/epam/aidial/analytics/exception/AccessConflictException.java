package com.epam.aidial.analytics.exception;

/**
 * An access {@code PUT} carried a {@code generation} that is no longer the table's: another write changed the
 * table's access configuration since the client read it. Mapped to HTTP 409 with the stable code
 * {@code access_conflict}; the message never names the current generation, so a client re-reads.
 */
public class AccessConflictException extends RuntimeException {
    public AccessConflictException(String message) {
        super(message);
    }
}
