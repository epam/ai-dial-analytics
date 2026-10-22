package com.epam.aidial.analytics.blob;

import java.time.Instant;

/**
 * A blob discovered by a listing: its full key and last-modified time, both read from the provider's
 * list response (no per-blob metadata fetch). Log blobs are write-once, so {@link #lastModified()}
 * is effectively the creation time and is the signal the near-real-time poller filters on.
 *
 * @param name         full blob key within the configured container
 * @param lastModified time the blob was last modified, as reported by the store
 */
public record BlobListing(String name, Instant lastModified) {
}
