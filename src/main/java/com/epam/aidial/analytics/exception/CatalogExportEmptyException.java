package com.epam.aidial.analytics.exception;

/**
 * A table selected for its access alone ({@code access_only}) has no entry an export can carry, so the selection
 * would export nothing for it. Mapped to HTTP 400 with the stable code {@code catalog_export_empty}.
 */
public class CatalogExportEmptyException extends RuntimeException {
    public CatalogExportEmptyException(String message) {
        super(message);
    }
}
