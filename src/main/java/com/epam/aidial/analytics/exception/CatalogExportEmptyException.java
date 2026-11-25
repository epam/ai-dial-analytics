package com.epam.aidial.analytics.exception;

/**
 * A table selected for its access alone ({@code access_only}) has no entry an export can carry, so the selection
 * would export nothing for it. Mapped to HTTP 422 with the stable code {@code catalog_export_empty}: the request is
 * well formed, and only the stored entries leave it nothing to carry.
 */
public class CatalogExportEmptyException extends RuntimeException {
    public CatalogExportEmptyException(String message) {
        super(message);
    }
}
