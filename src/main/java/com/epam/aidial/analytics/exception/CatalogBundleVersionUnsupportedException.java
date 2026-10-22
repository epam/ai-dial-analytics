package com.epam.aidial.analytics.exception;

/**
 * A bundle whose {@code format_version} is newer than this build reads. Mapped to HTTP 422 with the stable code
 * {@code catalog_bundle_version_unsupported}; the message names the bundle's version.
 */
public class CatalogBundleVersionUnsupportedException extends RuntimeException {
    public CatalogBundleVersionUnsupportedException(String message) {
        super(message);
    }
}
