package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.configuration.SyncRetryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** State derivation, listing, requeueing and the held-event queue of {@link IngestFailureService}, over in-memory ledgers. */
class IngestFailureServiceTest {

    private static final Instant T0 = Instant.parse("2026-05-26T10:00:00Z");
    private static final String KEY = "logs/2026/05/26/a.log.gz";

    private final InMemoryIngestFailureLedger failureLedger = new InMemoryIngestFailureLedger();
    private final InMemoryIngestLedger ingestLedger = new InMemoryIngestLedger();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(T0);
    private final IngestFailureService service = failureLedger.service(ingestLedger, registry, clock);

    {
        ingestLedger.clock = clock::instant;
    }

    @Test
    void transientFailuresBackOffExponentially() {
        BlobListing blob = new BlobListing(KEY, T0.minusSeconds(60));
        for (int i = 0; i < 3; i++) {
            service.recordBlobFailure(blob, IngestPath.POLL, new IOException("connection reset"));
            clock.advance(Duration.ofMinutes(1));
        }

        IngestFailureEntry entry = single();

        assertThat(entry.state()).isEqualTo(IngestFailureState.PENDING);
        assertThat(entry.failure().attempts()).isEqualTo(3);
        // The third failure was at T0+2m; 15m × 2² = 60m later.
        assertThat(entry.nextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(2 + 60)));
    }

    @Test
    void theBackoffIsCapped() {
        assertThat(InMemoryIngestFailureLedger.RETRY.backoffAfter(1)).isEqualTo(Duration.ofMinutes(15));
        assertThat(InMemoryIngestFailureLedger.RETRY.backoffAfter(8)).isEqualTo(Duration.ofHours(24));
        assertThat(InMemoryIngestFailureLedger.RETRY.backoffAfter(9)).isEqualTo(Duration.ofHours(24));
        assertThat(InMemoryIngestFailureLedger.RETRY.backoffAfter(1000)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void deterministicFailureParksAtOnce() {
        service.recordBlobFailure(new BlobListing(KEY, T0), IngestPath.RECONCILE, new ZipException("Not in GZIP format"));

        IngestFailureEntry entry = single();
        assertThat(entry.state()).isEqualTo(IngestFailureState.PARKED);
        assertThat(entry.nextAttemptAt()).isNull();
    }

    @Test
    void reachingTheAttemptLimitParks() {
        BlobListing blob = new BlobListing(KEY, T0);
        for (int i = 0; i < InMemoryIngestFailureLedger.RETRY.maxAttempts(); i++) {
            service.recordBlobFailure(blob, IngestPath.RETRY, new IOException("timeout"));
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(single().state()).isEqualTo(IngestFailureState.PARKED);
    }

    @Test
    void blobTheIngestLedgerRecordsSinceIsResolvedWithoutWrite() {
        BlobListing blob = new BlobListing(KEY, T0);
        service.recordBlobFailure(blob, IngestPath.POLL, new IOException("reset"));
        clock.advance(Duration.ofMinutes(1));

        ingestLedger.record(List.of(blob));

        assertThat(service.outstanding()).isEmpty();
        assertThat(failureLedger.events).hasSize(1);
    }

    /** A backfill including ingested blobs fails a blob the ledger recorded earlier: that entry says nothing of it. */
    @Test
    void earlierIngestDoesNotResolveLaterFailureOfTheSameVersion() {
        BlobListing blob = new BlobListing(KEY, T0);
        ingestLedger.record(List.of(blob));
        clock.advance(Duration.ofMinutes(1));

        service.recordBlobFailure(blob, IngestPath.BACKFILL, new ZipException("bad"));

        assertThat(single().state()).isEqualTo(IngestFailureState.PARKED);
    }

    @Test
    void resolutionsByTheLedgerAreCompactedIntoEvents() {
        BlobListing blob = new BlobListing(KEY, T0);
        service.recordBlobFailure(blob, IngestPath.POLL, new IOException("reset"));
        clock.advance(Duration.ofMinutes(1));
        ingestLedger.record(List.of(blob));

        IngestFailureService.Snapshot snapshot = service.snapshot();
        assertThat(snapshot.outstanding()).isEmpty();
        assertThat(snapshot.resolvedByLedger()).hasSize(1);

        assertThat(service.resolveIngested(snapshot)).isEqualTo(1);
        assertThat(failureLedger.open()).isEmpty();
        assertThat(service.snapshot().resolvedByLedger()).isEmpty();
    }

    @Test
    void blobFailuresAreWrittenInOneAppend() {
        List<IngestFailureEvent> events = List.of(
                service.blobFailure(new BlobListing("logs/a.log.gz", T0), IngestPath.RECONCILE, new ZipException("bad")),
                service.blobFailure(new BlobListing("logs/b.log.gz", T0), IngestPath.RECONCILE, new ZipException("bad")));

        service.recordBlobFailures(events);

        assertThat(failureLedger.appends).isEqualTo(1);
        assertThat(registry.counter("sync.failures.recorded", "kind", "blob", "path", "reconcile", "reason", "corrupt_gzip").count()).isEqualTo(2);
    }

    @Test
    void rewrittenBlobIngestedLaterResolvesItsEarlierFailure() {
        service.recordBlobFailure(new BlobListing(KEY, T0), IngestPath.POLL, new ZipException("bad"));
        clock.advance(Duration.ofMinutes(1));

        ingestLedger.record(List.of(new BlobListing(KEY, T0.plusSeconds(30))));

        assertThat(service.outstanding()).isEmpty();
    }

    @Test
    void olderIngestedVersionDoesNotResolveFailureOfNewerOne() {
        ingestLedger.record(List.of(new BlobListing(KEY, T0)));
        service.recordBlobFailure(new BlobListing(KEY, T0.plusSeconds(30)), IngestPath.POLL, new ZipException("bad"));

        assertThat(service.outstanding()).hasSize(1);
    }

    @Test
    void requeueResetsAttemptsAndIsDueAtOnce() {
        service.recordBlobFailure(new BlobListing(KEY, T0), IngestPath.POLL, new ZipException("bad"));
        clock.advance(Duration.ofHours(1));

        int requeued = service.requeue(new IngestFailureFilter(null, IngestFailureState.PARKED, null, null, null, null));

        assertThat(requeued).isEqualTo(1);
        IngestFailureEntry entry = single();
        assertThat(entry.state()).isEqualTo(IngestFailureState.PENDING);
        assertThat(entry.failure().attempts()).isZero();
        assertThat(entry.nextAttemptAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
        assertThat(service.due(10)).hasSize(1);
    }

    @Test
    void failureAfterRequeueCountsFromTheRequeue() {
        BlobListing blob = new BlobListing(KEY, T0);
        service.recordBlobFailure(blob, IngestPath.POLL, new ZipException("bad"));
        clock.advance(Duration.ofMinutes(1));
        service.requeue(IngestFailureFilter.ALL);
        clock.advance(Duration.ofMinutes(1));
        service.recordBlobFailure(blob, IngestPath.RETRY, new IOException("reset"));

        IngestFailureEntry entry = single();
        assertThat(entry.failure().attempts()).isEqualTo(1);
        assertThat(entry.failure().firstFailedAt()).isEqualTo(T0);
        assertThat(entry.state()).isEqualTo(IngestFailureState.PENDING);
    }

    @Test
    void resolvedSubjectIsNotRequeued() {
        LocalDate day = LocalDate.parse("2026-05-26");
        service.recordDayFailure(day, IngestPath.RECONCILE, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.LISTING_FAILED, new IllegalStateException("x"));
        clock.advance(Duration.ofMinutes(1));
        service.resolveDayIfOpen(day, IngestPath.RETRY, IngestLedger.Mode.SKIP_RECORDED);
        service.recordBlobFailure(new BlobListing(KEY, T0), IngestPath.POLL, new ZipException("bad"));

        int requeued = service.requeue(new IngestFailureFilter(null, null, null, null, null, Set.of(KEY, day.toString())));

        assertThat(requeued).isEqualTo(1);
    }

    @Test
    void listingIsNewestFirstFilteredAndTruncated() {
        for (int i = 0; i < 3; i++) {
            service.recordBlobFailure(new BlobListing("logs/2026/05/26/" + i + ".log.gz", T0), IngestPath.POLL, new ZipException("bad"));
            clock.advance(Duration.ofMinutes(1));
        }
        service.recordDayFailure(LocalDate.parse("2026-05-27"), IngestPath.BACKFILL, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.LISTING_FAILED,
                new IllegalStateException("x"));

        IngestFailureService.Page all = service.list(IngestFailureFilter.ALL, 2);
        IngestFailureService.Page blobs = service.list(new IngestFailureFilter(IngestFailureKind.BLOB, null,
                IngestFailureReason.CORRUPT_GZIP, null, null, null), 10);
        IngestFailureService.Page byDay = service.list(new IngestFailureFilter(null, null, null,
                LocalDate.parse("2026-05-27"), LocalDate.parse("2026-05-27"), null), 10);

        assertThat(all.entries()).extracting(entry -> entry.failure().subject()).containsExactly("2026-05-27", "logs/2026/05/26/2.log.gz");
        assertThat(all.truncated()).isTrue();
        assertThat(blobs.entries()).hasSize(3);
        assertThat(blobs.truncated()).isFalse();
        assertThat(byDay.entries()).singleElement().satisfies(entry -> assertThat(entry.failure().kind()).isEqualTo(IngestFailureKind.DAY));
    }

    @Test
    void dueSkipsParkedAndNotYetDue() {
        service.recordBlobFailure(new BlobListing("logs/p.log.gz", T0), IngestPath.POLL, new ZipException("bad"));
        service.recordBlobFailure(new BlobListing("logs/t.log.gz", T0), IngestPath.POLL, new IOException("reset"));

        assertThat(service.due(10)).isEmpty();
        clock.advance(Duration.ofMinutes(15));
        assertThat(service.due(10)).singleElement().satisfies(entry -> assertThat(entry.failure().subject()).isEqualTo("logs/t.log.gz"));
    }

    @Test
    void blobFailureThatCannotBeWrittenPropagates() {
        failureLedger.failAppends = true;

        assertThatThrownBy(() -> service.recordBlobFailure(new BlobListing(KEY, T0), IngestPath.POLL, new IOException("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dayFailureThatCannotBeWrittenIsHeldWithItsOriginalTime() {
        failureLedger.failAppends = true;
        service.recordDayFailure(LocalDate.parse("2026-05-26"), IngestPath.RECONCILE, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.WRITE_FAILED,
                new IllegalStateException("down"));
        assertThat(service.drainUnwritten()).isZero();
        assertThat(registry.get("sync.failures.unwritten").gauge().value()).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        failureLedger.failAppends = false;

        assertThat(service.drainUnwritten()).isEqualTo(1);
        assertThat(failureLedger.events).singleElement().satisfies(event -> assertThat(event.eventAt()).isEqualTo(T0));
        assertThat(registry.get("sync.failures.unwritten").gauge().value()).isZero();
    }

    @Test
    void theHeldQueueIsBounded() {
        failureLedger.failAppends = true;
        for (int i = 0; i <= IngestFailureService.MAX_UNWRITTEN; i++) {
            service.recordDayFailure(LocalDate.parse("2020-01-01").plusDays(i), IngestPath.RECONCILE, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.WRITE_FAILED,
                    new IllegalStateException("down"));
        }

        assertThat(service.unwrittenCount()).isEqualTo(IngestFailureService.MAX_UNWRITTEN);
    }

    @Test
    void gaugesCountOutstandingByKindAndState() {
        service.recordBlobFailure(new BlobListing("logs/a.log.gz", T0), IngestPath.POLL, new ZipException("bad"));
        service.recordBlobFailure(new BlobListing("logs/b.log.gz", T0), IngestPath.POLL, new ZipException("bad"));
        service.recordDayFailure(LocalDate.parse("2026-05-26"), IngestPath.RECONCILE, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.LISTING_FAILED,
                new IllegalStateException("x"));

        service.refreshGauges();

        assertThat(registry.get("sync.failures.outstanding").tag("kind", "blob").tag("state", "parked").gauge().value()).isEqualTo(2);
        assertThat(registry.get("sync.failures.outstanding").tag("kind", "day").tag("state", "pending").gauge().value()).isEqualTo(1);
        assertThat(registry.get("sync.failures.outstanding").tag("kind", "blob").tag("state", "pending").gauge().value()).isZero();
    }

    @Test
    void listCountsEveryMatchAndBothStatesBeyondTheLimit() {
        for (int i = 0; i < 3; i++) {
            service.recordBlobFailure(new BlobListing("logs/p" + i + ".log.gz", T0), IngestPath.POLL, new IOException("reset"));
            clock.advance(Duration.ofSeconds(1));
        }
        service.recordBlobFailure(new BlobListing("logs/k.log.gz", T0), IngestPath.POLL, new ZipException("Not in GZIP format"));

        IngestFailureService.Page parked = service.list(IngestFailureFilter.ALL.withState(IngestFailureState.PARKED), 100);
        assertThat(parked.entries()).hasSize(1);
        assertThat(parked.total()).isEqualTo(1);
        // The per-state counts disregard the state filter, so one read fills both tabs.
        assertThat(parked.byState()).containsEntry(IngestFailureState.PENDING, 3).containsEntry(IngestFailureState.PARKED, 1);

        IngestFailureService.Page limited = service.list(IngestFailureFilter.ALL, 2);
        assertThat(limited.entries()).hasSize(2);
        assertThat(limited.truncated()).isTrue();
        assertThat(limited.total()).isEqualTo(4);
    }

    @Test
    void listTotalEqualsWhatRequeueWithSameFilterRequeues() {
        for (int i = 0; i < 3; i++) {
            service.recordBlobFailure(new BlobListing("logs/m" + i + ".log.gz", T0), IngestPath.POLL, new ZipException("Not in GZIP format"));
        }
        service.recordBlobFailure(new BlobListing("logs/t.log.gz", T0), IngestPath.POLL, new IOException("reset"));
        IngestFailureFilter parked = IngestFailureFilter.ALL.withState(IngestFailureState.PARKED);

        int total = service.list(parked, 1).total();

        assertThat(total).isEqualTo(3);
        assertThat(service.requeue(parked)).isEqualTo(total);
    }

    @Test
    void targetSelectsOneKindOfSharedKey() {
        BlobListing blob = new BlobListing(KEY, T0);
        service.recordBlobFailure(blob, IngestPath.POLL, new ZipException("Not in GZIP format"));
        service.record(IngestFailureKind.RECORDS, blob, IngestPath.POLL, IngestFailureReason.SKIPPED_RECORDS, "malformed_line 1");
        IngestFailureFilter recordsOnly = new IngestFailureFilter(null, null, null, null, null, null, null,
                Set.of(new IngestFailureFilter.Target(IngestFailureKind.RECORDS, KEY)));

        assertThat(service.requeue(recordsOnly)).isEqualTo(1);
        assertThat(failureLedger.of(IngestFailureKind.RECORDS, KEY).getLast().type()).isEqualTo(IngestFailureEvent.Type.REQUEUED);
        assertThat(failureLedger.of(IngestFailureKind.BLOB, KEY).getLast().type()).isEqualTo(IngestFailureEvent.Type.FAILED);
    }

    @Test
    void failureIsAttributedToItsBackfillRunUntilResolved() {
        LocalDate day = LocalDate.parse("2026-05-20");
        BlobListing blob = new BlobListing(KEY, T0);
        service.recordDayFailure(day, IngestSource.backfill("run-a"), IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.LISTING_FAILED,
                new IllegalStateException("store down"));
        service.recordBlobFailures(List.of(service.blobFailure(blob, IngestSource.backfill("run-b"), new ZipException("bad"))));
        service.recordBlobFailure(new BlobListing("logs/other.log.gz", T0), IngestPath.POLL, new ZipException("bad"));

        IngestFailureFilter runA = new IngestFailureFilter(null, null, null, null, null, null, "run-a", null);
        assertThat(service.list(runA, 100).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.failure().subject()).isEqualTo(day.toString());
            assertThat(entry.failure().backfillRunIds()).containsExactly("run-a");
        });

        // Once resolved, a later failure by another path is no longer the run's.
        clock.advance(Duration.ofMinutes(1));
        service.resolveBlobs(List.of(blob), IngestPath.RETRY);
        clock.advance(Duration.ofMinutes(1));
        service.recordBlobFailure(blob, IngestPath.POLL, new ZipException("bad"));
        IngestFailureFilter runB = new IngestFailureFilter(null, null, null, null, null, null, "run-b", null);
        assertThat(service.list(runB, 100).entries()).isEmpty();
    }

    @Test
    void retrySweepStatusReportsTheNextRun() {
        IngestFailureService.RetrySweepStatus status = service.retrySweepStatus();
        assertThat(status.maxPerSweep()).isEqualTo(InMemoryIngestFailureLedger.RETRY.maxPerSweep());

        SyncRetryProperties everyQuarterHour = new SyncRetryProperties(true, "0 */15 * * * *", Duration.ofMinutes(15), Duration.ofHours(24), 10, 20);
        IngestFailureService.RetrySweepStatus scheduled =
                new IngestFailureService(failureLedger, ingestLedger, everyQuarterHour, new SimpleMeterRegistry(), clock).retrySweepStatus();
        assertThat(scheduled.enabled()).isTrue();
        assertThat(scheduled.nextSweepAt()).isAfter(T0).isBeforeOrEqualTo(T0.plus(Duration.ofMinutes(15)));

        SyncRetryProperties off = new SyncRetryProperties(false, "0 */15 * * * *", Duration.ofMinutes(15), Duration.ofHours(24), 10, 20);
        IngestFailureService.RetrySweepStatus disabled =
                new IngestFailureService(failureLedger, ingestLedger, off, new SimpleMeterRegistry(), clock).retrySweepStatus();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.nextSweepAt()).isNull();
    }

    @Test
    void unscheduledSweepIsReportedDisabled() {
        // The test settings schedule no sweep ("-"), so although retries are on nothing will attempt them.
        assertThat(service.retrySweepStatus().enabled()).isFalse();
        assertThat(service.retrySweepStatus().nextSweepAt()).isNull();
    }

    private IngestFailureEntry single() {
        List<IngestFailureEntry> outstanding = service.outstanding();
        assertThat(outstanding).hasSize(1);
        return outstanding.getFirst();
    }

    /** A clock a test moves by hand. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
