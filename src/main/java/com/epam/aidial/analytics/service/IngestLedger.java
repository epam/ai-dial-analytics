package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;

/**
 * The persisted record of the log blobs whose rows have all been written, consulted by every ingest path so
 * a blob is not written twice (see the data-sync spec, "Ingested blobs are recorded in a persisted ledger").
 *
 * <p>Read by the same prefixes the paths list, so a read returns exactly the keys its listing could return
 * and the statement size never depends on how many blobs a day holds. A failure of either operation is a
 * failure of the cycle or day that issued it, like a failed insert: there is no fail-open path.
 */
public interface IngestLedger {

    /**
     * The recorded blobs under {@code prefix}: key → the last-modified time recorded for it, the newest entry
     * winning when a blob was recorded more than once.
     *
     * @param prefix a prefix the caller listed
     * @return the recorded entries whose key starts with {@code prefix}
     */
    Map<String, Instant> recorded(String prefix);

    /**
     * Records blobs whose rows have all been written. A listing without a last-modified time
     * ({@link Instant#EPOCH}) is not recorded, since it could never match a later listing.
     *
     * @param written the blobs to record; a no-op when empty
     */
    void record(Collection<BlobListing> written);

    /**
     * Whether {@code listing} counts as recorded in {@code recorded}: same key and the same last-modified time
     * to the millisecond. A listing without a last-modified time never matches, so a blob the store lists
     * that way is always ingested.
     *
     * @param recorded the entries returned by {@link #recorded(String)}
     * @param listing  the listed blob
     * @return {@code true} when the blob may be skipped
     */
    static boolean isRecorded(Map<String, Instant> recorded, BlobListing listing) {
        if (Instant.EPOCH.equals(listing.lastModified())) {
            return false;
        }
        Instant entry = recorded.get(listing.name());
        return entry != null && entry.truncatedTo(ChronoUnit.MILLIS).equals(listing.lastModified().truncatedTo(ChronoUnit.MILLIS));
    }

    /** Which blobs a reconciliation run or backfill day ingests. */
    enum Mode {
        /** Skip the blobs the ledger records — the scheduled run, the poller and a default backfill. */
        SKIP_RECORDED,
        /** Ingest every listed blob, recorded or not — a backfill with {@code includeIngested: true}. */
        INGEST_ALL
    }
}
