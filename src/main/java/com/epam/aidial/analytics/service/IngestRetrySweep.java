package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.configuration.SyncRetryProperties;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.Attributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Retries the pending subjects of the failure ledger (see the {@code data-sync-failures} spec, "Outstanding failures
 * are retried automatically"). Each sweep first writes the day failures held in memory while ClickHouse was
 * unavailable, then attempts the due pending subjects — oldest latest failure first, at most
 * {@code sync.retry.max-per-sweep} — and finally refreshes the outstanding-failure gauges.
 *
 * <p>A blob is listed by its key and ingested through the shared pipeline into a sink of its own, recorded in the
 * ingest ledger once its rows are written, and resolved; a blob the store no longer lists is parked as
 * {@code blob_missing}, and one the ingest ledger records at the listed version since it failed is resolved without being read. A day is re-run as
 * the reconciliation run would, skipping recorded blobs; the day path records and resolves on its own. Every attempt
 * is idempotent, so a sweep overlapping another path on the same blob or day costs only duplicate work.
 *
 * <p>Runs on the shared scheduler; Spring does not start a sweep while the previous one runs.
 */
@Service
@Slf4j
public class IngestRetrySweep {

    private final IngestFailureService failures;
    private final BlobClient blobClient;
    private final BlobIngestor blobIngestor;
    private final UsageLogWriter usageLogWriter;
    private final SyncProperties syncProperties;
    private final SyncRetryProperties retryProperties;
    private final IngestLedger ledger;
    private final DataSyncService dataSyncService;
    private final MeterRegistry meterRegistry;
    private final ClientSpanTracer clientSpanTracer;

    public IngestRetrySweep(
            IngestFailureService failures,
            BlobClient blobClient,
            BlobIngestor blobIngestor,
            UsageLogWriter usageLogWriter,
            SyncProperties syncProperties,
            SyncRetryProperties retryProperties,
            IngestLedger ledger,
            DataSyncService dataSyncService,
            MeterRegistry meterRegistry,
            ClientSpanTracer clientSpanTracer) {
        this.failures = failures;
        this.blobClient = blobClient;
        this.blobIngestor = blobIngestor;
        this.usageLogWriter = usageLogWriter;
        this.syncProperties = syncProperties;
        this.retryProperties = retryProperties;
        this.ledger = ledger;
        this.dataSyncService = dataSyncService;
        this.meterRegistry = meterRegistry;
        this.clientSpanTracer = clientSpanTracer;
    }

    /** Scheduled entry point. Cadence is env-configurable via {@code sync.retry.cron}. */
    @Scheduled(cron = "${sync.retry.cron}")
    public void sweep() {
        runOnce();
    }

    /** Fills the outstanding-failure gauges once at startup, rather than leaving them at zero until the first sweep. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshGaugesAtStartup() {
        failures.refreshGauges();
    }

    /**
     * Runs one sweep. Never throws.
     *
     * @return a summary of the sweep
     */
    public SweepSummary runOnce() {
        return clientSpanTracer.internalSpan("data-sync retry sweep", Attributes.empty(), this::sweepOnce);
    }

    private SweepSummary sweepOnce() {
        int written = failures.drainUnwritten();
        SweepSummary summary = new SweepSummary(written, 0, 0, 0);
        IngestFailureService.Snapshot snapshot;
        try {
            snapshot = failures.snapshot();
        } catch (RuntimeException ex) {
            log.error("Retry sweep could not read the failure ledger: {}", ex.getMessage(), ex);
            return summary;
        }
        failures.resolveIngested(snapshot);
        boolean threw = false;
        try {
            if (retryProperties.enabled()) {
                summary = attemptDue(snapshot, written);
            }
        } catch (RuntimeException ex) {
            threw = true;
            log.error("Retry sweep failed: {}", ex.getMessage(), ex);
        } finally {
            // The snapshot is still current when nothing was attempted, the usual case; otherwise read again.
            if (summary.attempted() == 0 && !threw) {
                failures.refreshGauges(snapshot);
            } else {
                failures.refreshGauges();
            }
        }
        log.info("Retry sweep finished: {}", summary);
        return summary;
    }

    private SweepSummary attemptDue(IngestFailureService.Snapshot snapshot, int heldWritten) {
        List<IngestFailureEntry> due = failures.due(snapshot, retryProperties.maxPerSweep());
        if (due.isEmpty()) {
            return new SweepSummary(heldWritten, 0, 0, 0);
        }
        log.info("Retry sweep attempting {} due failure(s)", due.size());
        int succeeded = 0;
        int failed = 0;
        for (IngestFailureEntry entry : due) {
            OpenIngestFailure failure = entry.failure();
            if (failure.kind() == IngestFailureKind.DAY) {
                boolean ok = retryDay(LocalDate.parse(failure.subject()));
                succeeded += ok ? 1 : 0;
                failed += ok ? 0 : 1;
                continue;
            }
            if (retryBlob(failure)) {
                succeeded++;
            } else {
                failed++;
            }
        }
        return new SweepSummary(heldWritten, due.size(), succeeded, failed);
    }

    private boolean retryDay(LocalDate day) {
        DataSyncService.RunSummary summary = dataSyncService.runForDate(day, IngestLedger.Mode.SKIP_RECORDED, IngestPath.RETRY,
                DataSyncService.IngestProgressListener.NO_OP);
        meterRegistry.counter("sync.retry.attempts", "kind", IngestFailureKind.DAY.wire(), "outcome",
                summary.success() ? "success" : "failure").increment();
        return summary.success();
    }

    /**
     * Retries one blob in a sink of its own, flushed before the next blob is read: a sweep holds at most
     * {@code max-per-sweep} blobs, so batching across them saves little, and a write failure then belongs to this blob
     * alone. It is recorded as the blob's failed attempt, so the blob backs off and eventually parks instead of failing
     * every sweep ahead of every other due subject.
     *
     * @return whether the blob is now resolved
     */
    private boolean retryBlob(OpenIngestFailure failure) {
        String key = failure.subject();
        // Without a fresh listing, a failure keeps the version that failed before: recording no version would let any
        // earlier ingest of the blob count as resolving it, and would move the subject to today's day.
        BlobListing failedVersion = new BlobListing(key, failure.blobLastModified());
        BlobListing listing;
        try {
            listing = blobClient.listWithMetadata(key).stream().filter(candidate -> candidate.name().equals(key)).findFirst().orElse(null);
        } catch (RuntimeException ex) {
            return failedBlob(failedVersion, ex);
        }
        if (listing == null) {
            failures.recordBlob(failedVersion, IngestPath.RETRY, IngestFailureReason.BLOB_MISSING,
                    "The store no longer lists the blob");
            attempt("failure");
            return false;
        }
        if (recordedSinceFailure(listing, failure)) {
            failures.resolveBlobs(List.of(listing), IngestPath.RETRY);
            attempt("resolved");
            return true;
        }
        List<BlobListing> ingested = new ArrayList<>(1);
        try {
            RunSink sink = blobIngestor.newRunSink(usageLogWriter, syncProperties.batchSize(), syncProperties.maxBatchBytes(), written -> {
                ledger.record(written);
                ingested.addAll(written);
            });
            blobIngestor.ingest(listing, sink);
            sink.flush();
        } catch (Exception ex) {
            return failedBlob(listing, ex);
        }
        failures.resolveBlobs(ingested, IngestPath.RETRY);
        attempt("success");
        return true;
    }

    /**
     * Whether the ingest ledger recorded {@code listing}, at this version, after the blob's latest failure. An entry
     * written before the failure — the blob ingested once, then failing in a backfill that includes ingested blobs —
     * says nothing about the failed attempt, so the blob is read again.
     */
    private boolean recordedSinceFailure(BlobListing listing, OpenIngestFailure failure) {
        IngestLedger.Recorded recorded = ledger.recordedAmong(List.of(listing.name())).get(listing.name());
        return recorded != null
                && IngestLedger.isRecorded(Map.of(listing.name(), recorded.lastModified()), listing)
                && recorded.ingestedAt().isAfter(failure.lastFailedAt());
    }

    private boolean failedBlob(BlobListing listing, Exception ex) {
        failures.recordBlobFailure(listing, IngestPath.RETRY, ex);
        attempt("failure");
        return false;
    }

    private void attempt(String outcome) {
        meterRegistry.counter("sync.retry.attempts", "kind", IngestFailureKind.BLOB.wire(), "outcome", outcome).increment();
    }

    /**
     * Summary of one sweep.
     *
     * @param heldWritten day-failure events held in memory that this sweep wrote
     * @param attempted   due subjects attempted
     * @param succeeded   attempts that ingested, re-ran or resolved their subject
     * @param failed      attempts that failed again
     */
    public record SweepSummary(int heldWritten, int attempted, int succeeded, int failed) {
    }
}
