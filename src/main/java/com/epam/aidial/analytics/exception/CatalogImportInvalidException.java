package com.epam.aidial.analytics.exception;

/**
 * A catalog import whose validation pass found a problem; nothing was written. Mapped to HTTP 422 with the
 * stable code {@code catalog_import_invalid}. The message is a summary: {@code /import/preview} reports the detail.
 */
public class CatalogImportInvalidException extends RuntimeException {
    public CatalogImportInvalidException(String message) {
        super(message);
    }
}
