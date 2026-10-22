package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one "is this blob recorded" rule every ingest path applies. */
class IngestLedgerTest {

    private static final Instant LISTED = Instant.parse("2026-06-18T10:11:12.345678Z");

    @Test
    void sameKeyAndSameMillisecondIsRecorded() {
        assertThat(IngestLedger.isRecorded(Map.of("a", Instant.parse("2026-06-18T10:11:12.345Z")), new BlobListing("a", LISTED)))
                .isTrue();
    }

    @Test
    void differentLastModifiedIsNotRecorded() {
        assertThat(IngestLedger.isRecorded(Map.of("a", Instant.parse("2026-06-18T10:11:12.346Z")), new BlobListing("a", LISTED)))
                .isFalse();
    }

    @Test
    void unknownKeyIsNotRecorded() {
        assertThat(IngestLedger.isRecorded(Map.of("b", LISTED), new BlobListing("a", LISTED))).isFalse();
    }

    @Test
    void listingWithoutLastModifiedNeverMatches() {
        assertThat(IngestLedger.isRecorded(Map.of("a", Instant.EPOCH), new BlobListing("a", Instant.EPOCH))).isFalse();
    }
}
