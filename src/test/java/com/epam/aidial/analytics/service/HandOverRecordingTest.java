package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A sink hand-over is recorded failure ledger first, ingest ledger second: a blob recorded as ingested always has its
 * skipped records on record, and a failure to write them leaves the blob unrecorded (see
 * {@code openspec/specs/data-sync-failures/spec.md}, "Failed ingests are recorded in a persisted failure ledger").
 */
class HandOverRecordingTest {

    private static final BlobListing SKIPPING = new BlobListing("logs/2026/01/10/a.log.gz", Instant.parse("2026-01-10T00:00:01.123Z"));
    private static final BlobListing CLEAN = new BlobListing("logs/2026/01/10/b.log.gz", Instant.parse("2026-01-10T00:00:02.123Z"));
    private static final BlobListing REFUSED = new BlobListing("logs/2026/01/10/c.log.gz", Instant.parse("2026-01-10T00:00:03.123Z"));

    private final InMemoryIngestFailureLedger failureLedger = new InMemoryIngestFailureLedger();
    private final InMemoryIngestLedger ledger = new InMemoryIngestLedger();
    private final IngestFailureService failures = failureLedger.service(ledger);

    private static RunSink.HandOver handOver() {
        SkippedRecords skips = new SkippedRecords();
        skips.add(SkippedRecords.Cause.MALFORMED_LINE, 3);
        return new RunSink.HandOver(List.of(SKIPPING, CLEAN), Map.of(SKIPPING, skips),
                List.of(new RefusedBlob(REFUSED, "dial_usage_log", 53, "TYPE_MISMATCH")));
    }

    @Test
    void failuresAreWrittenInOneAppendThenTheWrittenBlobsAreRecorded() {
        failures.recordHandOver(handOver(), IngestPath.POLL);

        assertThat(failureLedger.appends).isEqualTo(1);
        assertThat(failureLedger.of(IngestFailureKind.RECORDS, SKIPPING.name())).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo(IngestFailureReason.SKIPPED_RECORDS);
            assertThat(event.reason().failureClass()).isEqualTo(IngestFailureReason.DETERMINISTIC);
            assertThat(event.path()).isEqualTo("poll");
            assertThat(event.message()).isEqualTo("malformed_line: 1 (line 3)");
            assertThat(event.blobLastModified()).isEqualTo(SKIPPING.lastModified());
        });
        assertThat(failureLedger.of(IngestFailureKind.BLOB, REFUSED.name())).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo(IngestFailureReason.REJECTED_BY_SERVER);
            assertThat(event.message()).contains("dial_usage_log", "53 TYPE_MISMATCH");
        });
        assertThat(failureLedger.of(IngestFailureKind.RECORDS, CLEAN.name())).isEmpty();
        assertThat(ledger.recordedKeys()).containsExactly(SKIPPING.name(), CLEAN.name());
    }

    @Test
    void failedFailureWriteLeavesTheBlobsUnrecorded() {
        failureLedger.failAppends = true;

        assertThatThrownBy(() -> failures.recordHandOver(handOver(), IngestPath.RECONCILE)).isInstanceOf(IllegalStateException.class);

        assertThat(ledger.recordedKeys()).isEmpty();
    }

    @Test
    void recordsSubjectsAreParkedAndNotResolvedByTheIngestLedger() {
        failures.recordHandOver(handOver(), IngestPath.RECONCILE);

        assertThat(failures.outstanding()).filteredOn(entry -> entry.failure().kind() == IngestFailureKind.RECORDS)
                .singleElement()
                .satisfies(entry -> assertThat(entry.state()).isEqualTo(IngestFailureState.PARKED));
        // The ingest ledger records the blob after its records failure, which would resolve a blob subject.
        assertThat(failures.snapshot().resolvedByLedger()).isEmpty();
    }
}
