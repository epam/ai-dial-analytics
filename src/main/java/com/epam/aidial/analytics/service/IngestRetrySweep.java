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

import java.time.Instant;
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
 * <p>A blob is listed by its key and ingested through the shared pipeline into a sweep-scoped sink, recorded in the
 * ingest ledger once its rows are written, and resolved; a blob the store no longer lists is parked as
 * {@code blob_missing}, and one the ingest ledger already records is resolved without being read. A day is re-run as
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
        try {
            if (retryProperties.enabled()) {
                summary = attemptDue(snapshot, written);
            }
        } catch (RuntimeException ex) {
            log.error("Retry sweep failed: {}", ex.getMessage(), ex);
        } finally {
            // The snapshot is still current when nothing was attempted, the usual case; otherwise read again.
            if (summary.attempted() == 0) {
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
        List<BlobListing> resolvedUnread = new ArrayList<>();
        List<BlobListing> ingested = new ArrayList<>();
        // Blobs share one sink so their rows batch; each is recorded in the ingest ledger once its rows are written,
        // and resolved after the final flush. A flush failure is a sweep-level fault: nothing it held is resolved.
        RunSink sink = blobIngestor.newRunSink(usageLogWriter, syncProperties.batchSize(), syncProperties.maxBatchBytes(), written -> {
            ledger.record(written);
            ingested.addAll(written);
        });
        for (IngestFailureEntry entry : due) {
            OpenIngestFailure failure = entry.failure();
            if (failure.kind() == IngestFailureKind.DAY) {
                boolean ok = retryDay(LocalDate.parse(failure.subject()));
                succeeded += ok ? 1 : 0;
                failed += ok ? 0 : 1;
                continue;
            }
            if (retryBlob(failure, sink, resolvedUnread) == BlobOutcome.FAILED) {
                failed++;
            } else {
                succeeded++;
            }
        }
        sink.flush();
        failures.resolveBlobs(resolvedUnread, IngestPath.RETRY);
        failures.resolveBlobs(ingested, IngestPath.RETRY);
        meterRegistry.counter("sync.retry.attempts", "kind", IngestFailureKind.BLOB.wire(), "outcome", "success").increment(ingested.size());
        return new SweepSummary(heldWritten, due.size(), succeeded, failed);
    }

    private boolean retryDay(LocalDate day) {
        DataSyncService.RunSummary summary = dataSyncService.runForDate(day, IngestLedger.Mode.SKIP_RECORDED, IngestPath.RETRY,
                DataSyncService.IngestProgressListener.NO_OP);
        meterRegistry.counter("sync.retry.attempts", "kind", IngestFailureKind.DAY.wire(), "outcome",
                summary.success() ? "success" : "failure").increment();
        return summary.success();
    }

    private BlobOutcome retryBlob(OpenIngestFailure failure, RunSink sink, List<BlobListing> resolvedUnread) {
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
            return BlobOutcome.FAILED;
        }
        Map<String, Instant> recorded = ledger.recorded(key);
        if (IngestLedger.isRecorded(recorded, listing)) {
            resolvedUnread.add(listing);
            attempt("resolved");
            return BlobOutcome.RESOLVED;
        }
        try {
            blobIngestor.ingest(listing, sink);
            return BlobOutcome.INGESTED;
        } catch (RowBatchSink.FlushException fe) {
            throw fe;
        } catch (Exception ex) {
            return failedBlob(listing, ex);
        }
    }

    private BlobOutcome failedBlob(BlobListing listing, Exception ex) {
        failures.recordBlobFailure(listing, IngestPath.RETRY, ex);
        attempt("failure");
        return BlobOutcome.FAILED;
    }

    private void attempt(String outcome) {
        meterRegistry.counter("sync.retry.attempts", "kind", IngestFailureKind.BLOB.wire(), "outcome", outcome).increment();
    }

    private enum BlobOutcome {
        INGESTED,
        RESOLVED,
        FAILED
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
