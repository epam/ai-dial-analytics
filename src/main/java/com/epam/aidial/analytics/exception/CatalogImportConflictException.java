package com.epam.aidial.analytics.exception;

/**
 * A catalog import under {@code FAIL_IF_EXISTS} whose bundle holds an object that already exists on the
 * target; nothing was written. Mapped to HTTP 409 with the stable code {@code catalog_import_conflict}.
 */
public class CatalogImportConflictException extends RuntimeException {
    public CatalogImportConflictException(String message) {
        super(message);
    }
}
