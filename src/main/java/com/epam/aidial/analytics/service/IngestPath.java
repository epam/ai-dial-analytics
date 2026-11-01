package com.epam.aidial.analytics.service;

import java.util.Locale;

/** The ingest path that made an attempt, as recorded in the failure ledger and its metrics. */
public enum IngestPath {
    POLL,
    RECONCILE,
    BACKFILL,
    RETRY;

    /** The lower-case spelling stored and reported. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
