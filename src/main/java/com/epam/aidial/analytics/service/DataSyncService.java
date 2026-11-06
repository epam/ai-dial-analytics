package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.exception.ClickHouseStorageFullException;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.Attributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the scheduled <em>reconciliation</em> ingestion of DIAL usage logs from blob storage into
 * ClickHouse. On each run it resolves a date-derived prefix, lists the blobs under it, and ingests
 * each via {@link BlobIngestor}. This once-a-day full listing is a safety net complementing the
 * near-real-time poller ({@link BlobPollingService}): it skips the blobs the {@link IngestLedger} records,
 * ingests the rest — what the poller missed — and records each as soon as its rows are written. A blob
 * written twice anyway (the window between its rows and its ledger entry) is collapsed by the target
 * table's {@code ReplacingMergeTree} engine. The run is unattended; Spring's
 * single-threaded scheduler guarantees runs do not overlap. Progress is exposed via logs and
 * Micrometer metrics; there is no HTTP surface.
 */
@Service
@Slf4j
public class DataSyncService {

    private final BlobClient blobClient;
    private final BlobIngestor blobIngestor;
    private final UsageLogWriter usageLogWriter;
    private final SyncProperties properties;
    private final BlobPrefixResolver prefixResolver;
    private final MeterRegistry meterRegistry;
    private final ClientSpanTracer clientSpanTracer;
    private final IngestLedger ledger;
    private final IngestFailureService failures;

    private final Timer durationTimer;
    private final AtomicLong lastSuccessTimestamp = new AtomicLong(0);

    public DataSyncService(
            BlobClient blobClient,
            BlobIngestor blobIngestor,
            UsageLogWriter usageLogWriter,
            SyncProperties properties,
            BlobPrefixResolver prefixResolver,
            MeterRegistry meterRegistry,
            ClientSpanTracer clientSpanTracer,
            IngestLedger ledger,
            IngestFailureService failures) {
        this.blobClient = blobClient;
        this.blobIngestor = blobIngestor;
        this.usageLogWriter = usageLogWriter;
        this.properties = properties;
        this.prefixResolver = prefixResolver;
        this.meterRegistry = meterRegistry;
        this.clientSpanTracer = clientSpanTracer;
        this.ledger = ledger;
        this.failures = failures;
        this.durationTimer = Timer.builder("sync.duration").register(meterRegistry);
        Gauge.builder("sync.last.success.timestamp", lastSuccessTimestamp, AtomicLong::doubleValue)
                .register(meterRegistry);
    }

    /** Scheduled entry point. Cron is env-configurable via {@code sync.cron}. */
    @Scheduled(cron = "${sync.cron}")
    public void syncDaily() {
        runOnce();
    }

    /**
     * Executes a single scheduled <em>reconciliation</em> run for {@code today − dayOffset} and records
     * the scheduled-path ({@code sync.*}) metrics. Exposed (package-visible to callers in the same
     * module) so it can be exercised directly in tests without the scheduler.
     *
     * @return a summary of the run
     */
    public RunSummary runOnce() {
        long startNanos = System.nanoTime();
        LocalDate targetDate = LocalDate.now(ZoneOffset.UTC).minusDays(properties.dayOffset());
        RunSummary summary;
        try {
            summary = runForDate(targetDate);
        } finally {
            durationTimer.record(Duration.ofNanos(System.nanoTime() - startNanos));
        }
        meterRegistry.counter("sync.runs", "outcome", summary.complete() ? "success" : "failure").increment();
        meterRegistry.counter("sync.files.processed").increment(summary.filesProcessed());
        meterRegistry.counter("sync.blobs.skipped", "reason", "ledger").increment(summary.blobsSkipped());
        summary.rowsInsertedByTable().forEach((table, count) ->
                meterRegistry.counter("sync.rows.inserted", "table", table).increment(count));
        meterRegistry.counter("sync.rows.skipped").increment(summary.rowsSkipped());
        meterRegistry.counter("sync.rows.dropped").increment(summary.rowsDropped());
        meterRegistry.counter("sync.blobs.failed").increment(summary.blobsFailed());
        if (summary.complete()) {
            lastSuccessTimestamp.set(Instant.now().getEpochSecond());
        }
        return summary;
    }

    /**
     * Ingests the blobs under the date-derived prefix for a single UTC {@code targetDate} that the
     * {@link IngestLedger} does not record, via the shared parse → map → batch-insert pipeline, recording each
     * as soon as its rows are written, and returns a summary. This is the reusable per-day primitive shared by
     * the scheduled path ({@link #runOnce()}) and the operator-triggered backfill.
     *
     * <p>All per-run state (the {@link RowBatchSink}) is local to this invocation and the only instance
     * state touched is thread-safe, so the method is re-entrant: a backfill thread may call it
     * concurrently with the scheduler thread without sharing batch state. A blob that fails to read or map is
     * recorded in the failure ledger and skipped, and the day goes on ({@link RunSummary#blobsFailed()}). It never
     * throws — a listing or
     * write failure, or a failure preparing the day (resolving its prefix, reading the catalog for its
     * write path), is caught, logged, and reflected as {@code success() == false} in the summary — so
     * callers iterating a range can isolate a failed day and continue.
     *
     * <p>This method does <em>not</em> record the scheduled-path {@code sync.*} metrics; those belong to
     * {@link #runOnce()}, and the backfill records its own {@code backfill.*} metrics from the summary.
     *
     * @param targetDate the UTC date whose prefix to ingest
     * @return a summary of the day's ingestion
     */
    public RunSummary runForDate(LocalDate targetDate) {
        return runForDate(targetDate, IngestProgressListener.NO_OP);
    }

    /**
     * As {@link #runForDate(LocalDate)}, but notifies {@code progressListener} after each blob is
     * ingested so a caller can surface intra-run progress (e.g. the backfill's per-blob status). The
     * listener is invoked on the calling thread between blobs; it MUST be cheap and non-blocking.
     *
     * @param targetDate      the UTC date whose prefix to ingest
     * @param progressListener callback invoked once per successfully-ingested blob
     * @return a summary of the day's ingestion
     */
    public RunSummary runForDate(LocalDate targetDate, IngestProgressListener progressListener) {
        return runForDate(targetDate, IngestLedger.Mode.SKIP_RECORDED, progressListener);
    }

    /**
     * As {@link #runForDate(LocalDate, IngestProgressListener)}, choosing whether blobs the ledger records are
     * skipped ({@link IngestLedger.Mode#SKIP_RECORDED}) or ingested again ({@link IngestLedger.Mode#INGEST_ALL},
     * a backfill with {@code includeIngested}). Either way each ingested blob is recorded once its rows are
     * written.
     *
     * @param targetDate       the UTC date whose prefix to ingest
     * @param mode             whether recorded blobs are skipped
     * @param progressListener callback invoked once per successfully-ingested blob
     * @return a summary of the day's ingestion
     */
    public RunSummary runForDate(LocalDate targetDate, IngestLedger.Mode mode, IngestProgressListener progressListener) {
        return runForDate(targetDate, mode, IngestSource.of(IngestPath.RECONCILE), progressListener);
    }

    /**
     * As {@link #runForDate(LocalDate, IngestLedger.Mode, IngestProgressListener)}, naming the ingest path the day's
     * failure-ledger events are recorded under. A day that fails as a whole is recorded as a day failure, which the
     * retry sweep retries; a day that completes without a day-level fault resolves an earlier failure of it.
     *
     * @param source the path running the day — the reconciliation run, a backfill or the retry sweep — and, for a backfill,
     *               the run its failures are attributed to
     */
    public RunSummary runForDate(LocalDate targetDate, IngestLedger.Mode mode, IngestSource source, IngestProgressListener progressListener) {
        // One INTERNAL parent span per day so the CLIENT spans this cycle triggers (blob list/get,
        // ClickHouse inserts) nest under a single trace instead of each becoming a disconnected root.
        Attributes attributes = Attributes.builder().put("sync.date", targetDate.toString()).build();
        return clientSpanTracer.internalSpan("data-sync run", attributes, () -> ingestDate(targetDate, mode, source, progressListener));
    }

    private RunSummary ingestDate(LocalDate targetDate, IngestLedger.Mode mode, IngestSource source, IngestProgressListener progressListener) {
        IngestPath path = source.path();
        long filesProcessed = 0;
        long blobsSkipped = 0;
        long blobsFailed = 0;
        // Where the day stands, so a day-level fault is recorded under the reason for the step that raised it.
        IngestFailureReason failingStep = IngestFailureReason.PREPARE_FAILED;
        Exception dayFault = null;
        // The day's blob failures, written in one append (one part however many blobs fail) before the final flush.
        List<IngestFailureEvent> blobFailures = new ArrayList<>();
        long rowsSkipped = 0;
        long rowsDropped = 0;
        boolean success = false;
        boolean storageFull = false;
        String prefix = null;
        RunSink sink = null;
        // Blobs ClickHouse refused once their batch was split by blob; recorded in the failure ledger by the hand-over.
        AtomicLong blobsRefused = new AtomicLong();
        try {
            // Preparing the day (prefix, and the catalog read behind the sink) sits inside the try so a failure
            // there fails this day like any other, instead of escaping and breaking the never-throws contract.
            prefix = prefixResolver.dayPrefix(targetDate);
            log.info("Data-sync run starting: prefix={}", prefix);
            // One sink for the whole run so rows batch across blobs instead of one insert per blob.
            // Each blob is recorded the moment its rows are all written, so a day that fails part-way keeps what it
            // finished and a backfill of it afterwards ingests only the rest.
            // The hand-over records what the written blobs skipped and the refused blobs in the failure ledger first, then
            // the written blobs in the ingest ledger.
            sink = blobIngestor.newHandOverSink(usageLogWriter, properties.batchSize(), properties.maxBatchBytes(), handOver -> {
                failures.recordHandOver(handOver, source);
                for (RefusedBlob ignored : handOver.refused()) {
                    blobsRefused.incrementAndGet();
                    progressListener.onBlobFailed();
                }
            });
            failingStep = IngestFailureReason.LISTING_FAILED;
            List<BlobListing> blobs = blobClient.listWithMetadata(prefix);
            failingStep = IngestFailureReason.WRITE_FAILED;
            log.info("Data-sync found {} blob(s) under prefix {}", blobs.size(), prefix);
            // This path lists whole days, so it holds the only evidence of whether the poller's bounded
            // (band) listing can match these file names at all — band mode's own empty listing is
            // indistinguishable from an idle environment. Reporting only; nothing below changes.
            prefixResolver.checkBandShape(targetDate, blobs.stream().map(BlobListing::name).toList());
            Map<String, Instant> recorded = mode == IngestLedger.Mode.SKIP_RECORDED ? ledger.recorded(prefix) : Map.of();
            for (BlobListing listing : blobs) {
                if (IngestLedger.isRecorded(recorded, listing)) {
                    blobsSkipped++;
                    continue;
                }
                BlobIngestor.IngestResult result;
                try {
                    result = blobIngestor.ingest(listing, sink);
                } catch (RowBatchSink.FlushException fe) {
                    // A batch write, or the ledger write recording what it wrote, failed: a fault of the day, not of
                    // this blob.
                    throw fe;
                } catch (Exception ex) {
                    // One bad blob must not stop its day: record it and go on with the rest. The failures are written
                    // together below; a failed write fails the day, like a failed ledger write.
                    blobFailures.add(failures.blobFailure(listing, source, ex));
                    blobsFailed++;
                    progressListener.onBlobFailed();
                    continue;
                }
                rowsSkipped += result.rowsSkipped();
                rowsDropped += result.rowsDropped();
                filesProcessed++;
                progressListener.onBlobIngested(result.rowsInserted(), result.rowsSkipped(), result.rowsDropped());
            }
            failures.recordBlobFailures(blobFailures);
            blobFailures.clear();
            sink.flush();
            success = true;
        } catch (Exception ex) {
            dayFault = ex;
            recordCollected(blobFailures);
            // A storage-full refusal is called out separately because it is not this day's problem: the store
            // cannot accept writes at all, so the caller is told rather than left to read it out of the log.
            storageFull = ClickHouseStorageFullException.isIn(ex);
            if (storageFull) {
                log.error("Data-sync run for prefix {} stopped: ClickHouse is out of disk space ({})",
                        prefix, ex.getMessage(), ex);
            } else if (prefix == null) {
                log.error("Data-sync run failed preparing {}: {}", targetDate, ex.getMessage(), ex);
            } else {
                log.error("Data-sync run failed for prefix {}: {}", prefix, ex.getMessage(), ex);
            }
        }

        if (success) {
            failures.resolveDayIfOpen(targetDate, path, mode);
        } else {
            failures.recordDayFailure(targetDate, source, mode, storageFull ? IngestFailureReason.STORAGE_FULL : failingStep, dayFault);
        }

        // A sink that was never built (the day failed while preparing) inserted nothing. A refused blob was read in full,
        // so it was counted as processed; it is a failed one.
        RunSummary summary = new RunSummary(
                filesProcessed - blobsRefused.get(), sink == null ? 0 : sink.totalInserted(), sink == null ? Map.of() : sink.insertedByTable(),
                rowsSkipped, rowsDropped, blobsSkipped, blobsFailed + blobsRefused.get(), success, storageFull);
        log.info("Data-sync run finished: {}", summary);
        return summary;
    }

    /**
     * Best-effort write of the blob failures a failed day collected before its fault: the day failure is recorded
     * either way and its retry meets these blobs again, so a failure here is only logged.
     */
    private void recordCollected(List<IngestFailureEvent> blobFailures) {
        if (blobFailures.isEmpty()) {
            return;
        }
        try {
            failures.recordBlobFailures(blobFailures);
        } catch (RuntimeException ex) {
            log.warn("Could not record {} blob failure(s) of a failed day; its retry meets them again: {}", blobFailures.size(), ex.getMessage());
        }
    }

    /**
     * Summary of a single sync run.
     *
     * @param filesProcessed      files ingested this run; blobs skipped because the ledger records them are not counted
     * @param rowsInserted        rows inserted across all files and all target tables
     * @param rowsInsertedByTable rows inserted this run broken down by target table (for per-table metrics)
     * @param rowsSkipped         malformed lines skipped across all files
     * @param rowsDropped         records dropped for lacking a deduplication key across all files
     * @param blobsSkipped        listed blobs not read because the ingest ledger records them
     * @param blobsFailed         blobs that failed to read or map, or whose rows ClickHouse refused, each recorded in the
     *                            failure ledger and not recorded in the ingest ledger
     * @param success             whether the day completed without a day-level fault; blob failures do not clear it,
     *                            see {@link #complete()}
     * @param storageFull         whether the run failed because ClickHouse refused a write for lack of disk space;
     *                            always false when {@code success}. Carried as a flag rather than an exception so
     *                            {@link #runForDate(LocalDate)} keeps its never-throws contract, while a caller
     *                            iterating days (the backfill) can tell "this day failed" from "the store is out
     *                            of space, so every remaining day will fail too" and stop
     */
    public record RunSummary(
            long filesProcessed,
            long rowsInserted,
            Map<String, Long> rowsInsertedByTable,
            long rowsSkipped,
            long rowsDropped,
            long blobsSkipped,
            long blobsFailed,
            boolean success,
            boolean storageFull) {

        public RunSummary {
            rowsInsertedByTable = rowsInsertedByTable == null ? Map.of() : Map.copyOf(rowsInsertedByTable);
        }

        /** Whether the day ingested every listed blob: no day-level fault and no failed blob. */
        public boolean complete() {
            return success && blobsFailed == 0;
        }
    }

    /**
     * Callback invoked after each blob is ingested within a {@link #runForDate(LocalDate,
     * IngestProgressListener)} run, letting a caller surface intra-run progress. Counts are the blob's
     * own contribution (its rows accepted into the sink, its malformed lines skipped, its records
     * dropped); accepted rows are flushed by the sink's batching, so a caller's running "inserted"
     * total leads the table by at most one un-flushed batch and converges at end of run. The scheduled
     * path uses {@link #NO_OP}.
     */
    @FunctionalInterface
    public interface IngestProgressListener {

        IngestProgressListener NO_OP = (rowsInserted, rowsSkipped, rowsDropped) -> { };

        void onBlobIngested(long rowsInserted, long rowsSkipped, long rowsDropped);

        /** Called once per blob that failed to read or map and was skipped. */
        default void onBlobFailed() {
        }
    }
}
