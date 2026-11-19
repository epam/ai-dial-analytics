package com.epam.aidial.analytics.exception;

/**
 * A catalog transfer the caller lacks a privilege for: an import that would create something the caller could not
 * create through the endpoint that normally creates it (import calls the create services in-process, past their
 * endpoint gates, so it repeats those gates itself), or an export or import of access without {@code TABLE_GRANT}.
 * Mapped to HTTP 403 with the stable code {@code forbidden}.
 */
public class CatalogTransferForbiddenException extends RuntimeException {
    public CatalogTransferForbiddenException(String message) {
        super(message);
    }
}
