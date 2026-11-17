package com.epam.aidial.analytics.exception;

/**
 * A read-side grant ({@code get}, {@code read}, {@code sensitive}) or a row policy was sent for an enrichment, whose
 * read-side access and rows are its source's. Mapped to HTTP 409 with the {@code enrichment_follows_source} code; the
 * message names the source to grant or write the policy on instead.
 */
public class EnrichmentFollowsSourceException extends RuntimeException {

    public EnrichmentFollowsSourceException(String message) {
        super(message);
    }
}
