package com.epam.aidial.analytics.exception;

/**
 * An export selection names, or reaches, an object a bundle cannot carry: an OTLP landing table, a pipeline
 * reading one, or a PENDING or FAILED table. Mapped to HTTP 422 with the stable code {@code catalog_export_invalid}.
 */
public class CatalogExportInvalidException extends RuntimeException {
    public CatalogExportInvalidException(String message) {
        super(message);
    }
}
