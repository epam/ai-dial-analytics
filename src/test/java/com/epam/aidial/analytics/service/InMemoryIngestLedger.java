package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link IngestLedger} held in memory for unit tests: the newest record per key wins, as in ClickHouse,
 * and each {@link #record} call is kept as one batch so a test can assert when blobs were handed over.
 * {@link #failReads} / {@link #failWrites} make the next operations throw, standing in for ClickHouse
 * being unavailable.
 */
class InMemoryIngestLedger implements IngestLedger {

    final Map<String, Instant> entries = new LinkedHashMap<>();
    final List<List<String>> recordBatches = new ArrayList<>();
    int reads;
    boolean failReads;
    boolean failWrites;

    @Override
    public Map<String, Instant> recorded(String prefix) {
        reads++;
        if (failReads) {
            throw new IllegalStateException("ledger read failed");
        }
        Map<String, Instant> result = new HashMap<>();
        entries.forEach((key, lastModified) -> {
            if (key.startsWith(prefix)) {
                result.put(key, lastModified);
            }
        });
        return result;
    }

    @Override
    public void record(Collection<BlobListing> written) {
        if (failWrites) {
            throw new IllegalStateException("ledger write failed");
        }
        List<String> batch = new ArrayList<>();
        for (BlobListing listing : written) {
            if (Instant.EPOCH.equals(listing.lastModified())) {
                continue;
            }
            entries.put(listing.name(), listing.lastModified().truncatedTo(ChronoUnit.MILLIS));
            batch.add(listing.name());
        }
        recordBatches.add(batch);
    }

    /** Every key recorded so far, in recording order. */
    List<String> recordedKeys() {
        return List.copyOf(entries.keySet());
    }
}
