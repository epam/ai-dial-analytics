package com.epam.aidial.analytics.service;

import java.util.Locale;

/**
 * The stable reason codes of the failure ledger, each with the class it is recorded under. Only the three
 * {@linkplain #deterministic() deterministic} reasons park a subject on its first failure; see
 * {@code openspec/specs/data-sync-failures/spec.md} for why an early end of a gzip stream is not among them.
 */
public enum IngestFailureReason {
    CORRUPT_GZIP(true),
    MAPPING_ERROR(true),
    BLOB_MISSING(true),
    READ_ERROR(false),
    LISTING_FAILED(false),
    PREPARE_FAILED(false),
    WRITE_FAILED(false),
    STORAGE_FULL(false);

    /** The class spelling stored for a deterministic failure. */
    public static final String DETERMINISTIC = "deterministic";
    /** The class spelling stored for a transient failure. */
    public static final String TRANSIENT = "transient";

    private final boolean deterministic;

    IngestFailureReason(boolean deterministic) {
        this.deterministic = deterministic;
    }

    /** Whether repeating the attempt cannot succeed with the same blob and configuration. */
    public boolean deterministic() {
        return deterministic;
    }

    /** The class spelling stored with this reason. */
    public String failureClass() {
        return deterministic ? DETERMINISTIC : TRANSIENT;
    }

    /** The lower-case spelling stored and reported. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The reason spelled {@code wire}, matched case-insensitively; {@code null} when none is. */
    public static IngestFailureReason fromWire(String wire) {
        for (IngestFailureReason reason : values()) {
            if (reason.wire().equalsIgnoreCase(wire)) {
                return reason;
            }
        }
        return null;
    }
}
