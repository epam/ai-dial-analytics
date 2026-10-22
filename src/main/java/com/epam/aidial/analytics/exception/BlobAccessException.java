package com.epam.aidial.analytics.exception;

/**
 * Raised when the blob source cannot be listed or a blob cannot be read. Used by the
 * {@code data-sync} ingest path; the cause is retained for server-side logging only. There is no
 * HTTP surface for ingest, so this is handled by failing the current sync run (which retries on the
 * next schedule) rather than being mapped to an error response.
 */
public class BlobAccessException extends RuntimeException {

    public BlobAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
