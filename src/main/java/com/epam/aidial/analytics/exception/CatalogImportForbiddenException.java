package com.epam.aidial.analytics.exception;

/**
 * A catalog import that would create something the caller could not create through the endpoint that
 * normally creates it. Import calls the create services in-process, past their endpoint gates, so it repeats those
 * gates itself. Mapped to HTTP 403 with the stable code {@code forbidden}.
 */
public class CatalogImportForbiddenException extends RuntimeException {
    public CatalogImportForbiddenException(String message) {
        super(message);
    }
}
