package com.epam.aidial.analytics.service;

import java.util.Locale;

/** What a failure-ledger subject is: one log blob, or one UTC day of a day-scoped ingest path. */
public enum IngestFailureKind {
    BLOB,
    DAY;

    /** The lower-case spelling stored and reported. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind spelled {@code wire}, matched case-insensitively; {@code null} when none is. */
    public static IngestFailureKind fromWire(String wire) {
        for (IngestFailureKind kind : values()) {
            if (kind.wire().equalsIgnoreCase(wire)) {
                return kind;
            }
        }
        return null;
    }
}
