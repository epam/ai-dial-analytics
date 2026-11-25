package com.epam.aidial.analytics.exception;

/**
 * An export selection the bundle cannot honour: it names, or reaches, an object a bundle cannot carry (an OTLP landing
 * table, a pipeline reading one, a PENDING or FAILED table, a pipeline whose declaration no longer resolves), names a
 * system table without {@code include_access}, sets {@code access_only} or {@code roles} without {@code include_access},
 * or selects one object two different ways. Mapped to HTTP 422 with the stable code {@code catalog_export_invalid}.
 */
public class CatalogExportInvalidException extends RuntimeException {
    public CatalogExportInvalidException(String message) {
        super(message);
    }
}
