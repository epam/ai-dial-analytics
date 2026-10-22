package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.PollProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.exception.ClickHouseStorageFullException;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.Attributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic near-real-time ingest path. On a short schedule it lists the active date-derived
 * prefix(es) — the current day, plus the previous day within a post-midnight grace window — via the
 * jclouds blob abstraction, and ingests every blob whose last-modified time is at or after a moving
 * watermark (less a configurable overlap) through {@link BlobIngestor}. Discovery uses only
 * {@link BlobClient#listWithMetadata(String)}; there is no dependency on any cloud-specific
 * notification service or externally provisioned infrastructure, so
 * the same path works on any jclouds-supported store.
 *
 * <p>Correctness does not depend on exactly-once discovery: the watermark is held in memory (reset to
 * {@code now − restart-lookback} on startup), the overlap re-considers recent blobs to absorb
 * out-of-order writes and clock skew, and any blob re-ingested as a result is collapsed by the target
 * table's {@code ReplacingMergeTree} engine on its full sort key ({@code project_id, deployment,
 * request_time, trace_id, core_span_id}) within a partition.
 * A short-lived seen-set avoids redundant re-reads within the overlap window, and the {@link IngestLedger}
 * — consulted for a candidate the seen-set does not hold, and written as each blob's rows are written —
 * keeps a restart's lookback, or a blob another path already ingested, from being written again. A failed
 * cycle leaves the watermark unchanged so the next cycle re-covers the same window.
 *
 * <p>To keep per-cycle listing cost from growing with the full day, {@code poll.listing-mode=band}
 * (the default) narrows each listing to a recent time slice ({@link #activePrefixes(Instant, Instant)}
 * / {@link BlobPrefixResolver}); the {@code day} fallback lists the whole active day. Within a cycle,
 * rows are batched across blobs through a single {@link RowBatchSink} rather than one insert per blob.
 *
 * <p>Enabled by default (no infrastructure prerequisite); when {@code poll.enabled=false} the bean is
 * absent and ingestion proceeds solely via {@link DataSyncService}. Spring's single-threaded scheduler
 * prevents overlapping cycles.
 */
@Service
@ConditionalOnProperty(prefix = "poll", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class BlobPollingService {

    private static final String BAND_LISTING_MODE = "band";

    private final BlobClient blobClient;
    private final BlobIngestor blobIngestor;
    private final UsageLogWriter usageLogWriter;
    private final SyncProperties syncProperties;
    private final PollProperties pollProperties;
    private final BlobPrefixResolver prefixResolver;
    private final MeterRegistry meterRegistry;
    private final ClientSpanTracer clientSpanTracer;
    private final IngestLedger ledger;

    private final Timer durationTimer;
    /** Greatest last-modified time ingested so far; blobs at/after this (less overlap) are candidates. */
    private volatile Instant watermark;
    /** Names already ingested within the current overlap window, to avoid redundant re-reads. */
    private final Map<String, Instant> recentlyIngested = new HashMap<>();

    public BlobPollingService(
            BlobClient blobClient,
            BlobIngestor blobIngestor,
            UsageLogWriter usageLogWriter,
            SyncProperties syncProperties,
            PollProperties pollProperties,
            BlobPrefixResolver prefixResolver,
            MeterRegistry meterRegistry,
            ClientSpanTracer clientSpanTracer,
            IngestLedger ledger) {
        this.blobClient = blobClient;
        this.blobIngestor = blobIngestor;
        this.usageLogWriter = usageLogWriter;
        this.syncProperties = syncProperties;
        this.pollProperties = pollProperties;
        this.prefixResolver = prefixResolver;
        this.meterRegistry = meterRegistry;
        this.clientSpanTracer = clientSpanTracer;
        this.ledger = ledger;
        this.watermark = Instant.now().minus(pollProperties.restartLookback());
        this.durationTimer = Timer.builder("poll.duration").register(meterRegistry);
        Gauge.builder("poll.watermark.lag.seconds", this, BlobPollingService::watermarkLagSeconds)
                .register(meterRegistry);
    }

    /** Scheduled entry point. Cadence is env-configurable via {@code poll.poll-cron}. */
    @Scheduled(cron = "${poll.poll-cron}")
    public void poll() {
        runOnce();
    }

    /**
     * Executes a single poll cycle: lists the active prefix(es), ingests blobs newer than the watermark
     * (less overlap) that have not already been ingested this window, then advances the watermark.
     *
     * <p>A failure to <em>list</em> (e.g. the store is unreachable) is fatal to the cycle: the watermark
     * is left unchanged so the next cycle re-covers the whole window. A failure to ingest a single blob
     * (e.g. a corrupt gzip) is <em>not</em> fatal: it is counted and skipped, the cycle continues with
     * the remaining blobs, and the watermark still advances — so one bad blob cannot wedge the poller.
     * The skipped blob stays eligible for retry within the overlap window and is otherwise recovered by
     * the daily reconciliation sweep.
     *
     * @return a summary of the cycle
     */
    PollSummary runOnce() {
        // One INTERNAL parent span per cycle so the CLIENT spans it triggers (blob list/get, ClickHouse
        // inserts) nest under a single trace instead of each becoming a disconnected root span.
        return clientSpanTracer.internalSpan("data-sync poll", Attributes.empty(), this::runCycle);
    }

    private PollSummary runCycle() {
        long startNanos = System.nanoTime();
        Instant now = Instant.now();
        Instant cutoff = watermark.minus(pollProperties.overlap());
        Instant maxSeen = watermark;
        long discovered = 0;
        long skippedByLedger = 0;
        long ingested = 0;
        long failed = 0;
        long rowsSkipped = 0;
        long rowsDropped = 0;
        boolean success = false;
        RunSink sink = null;
        // Blobs ingested this cycle are committed to the seen-set only after a successful final flush,
        // so a flush failure (writer down) holds the watermark AND leaves them re-ingestable next cycle.
        List<BlobListing> ingestedThisCycle = new ArrayList<>();
        try {
            // One sink per cycle so rows batch across blobs (a cycle of many tiny blobs → a few inserts). Built
            // inside the try so a failed catalog read behind it is a failed cycle, with its metrics recorded.
            // Each blob is recorded in the ledger as soon as its rows are all written (usually at the final flush).
            sink = blobIngestor.newRunSink(usageLogWriter, syncProperties.batchSize(), syncProperties.maxBatchBytes(), ledger::record);
            List<String> prefixes = activePrefixes(now, cutoff);
            log.info("Poll cycle starting: mode={}, watermark={}, prefixes={}",
                    pollProperties.listingMode(), watermark, prefixes);
            for (String prefix : prefixes) {
                // Read once per prefix, and only when a candidate gets past the seen-set: an idle cycle costs no read.
                Map<String, Instant> recorded = null;
                for (BlobListing listing : blobClient.listWithMetadata(prefix)) {
                    Instant lastModified = listing.lastModified();
                    if (lastModified.isBefore(cutoff)) {
                        continue;
                    }
                    if (lastModified.isAfter(maxSeen)) {
                        maxSeen = lastModified;
                    }
                    if (recentlyIngested.containsKey(listing.name())) {
                        continue;
                    }
                    if (recorded == null) {
                        recorded = ledger.recorded(prefix);
                    }
                    if (IngestLedger.isRecorded(recorded, listing)) {
                        // Ingested before — by an earlier process (a restart's lookback) or another path. Remember it
                        // so the rest of the overlap window does not consult the ledger for it again.
                        recentlyIngested.put(listing.name(), lastModified);
                        skippedByLedger++;
                        continue;
                    }
                    discovered++;
                    try {
                        BlobIngestor.IngestResult result = blobIngestor.ingest(listing, sink);
                        ingestedThisCycle.add(listing);
                        rowsSkipped += result.rowsSkipped();
                        rowsDropped += result.rowsDropped();
                        ingested++;
                    } catch (RowBatchSink.FlushException fe) {
                        // A batch write, or the ledger write recording what it wrote, failed: the writer
                        // (ClickHouse) is unavailable. This is a cycle-level fault, not a single-blob one —
                        // propagate so the watermark holds.
                        throw fe;
                    } catch (Exception ex) {
                        // A single bad blob must not abort the cycle or hold back the watermark. Count
                        // it and move on, leaving it out of the seen-set so it is retried while it stays
                        // within the overlap window; the daily sweep is the longer-term backstop.
                        failed++;
                        log.warn("Failed to ingest blob {}; skipping it this cycle: {}",
                                listing.name(), ex.getMessage(), ex);
                    }
                }
            }
            // Final flush before advancing: if it throws, the watermark and seen-set stay put.
            sink.flush();
            ingestedThisCycle.forEach(listing -> recentlyIngested.put(listing.name(), listing.lastModified()));
            watermark = maxSeen;
            pruneSeen();
            success = true;
        } catch (Exception ex) {
            // Same handling either way — the watermark holds and the cycle retries, which is what makes ingest
            // resume unattended once space returns — but a disk-full refusal is named so it is not read as a
            // transient blob/store hiccup.
            if (ClickHouseStorageFullException.isIn(ex)) {
                log.error("Poll cycle failed: ClickHouse is out of disk space; watermark held at {} ({})",
                        watermark, ex.getMessage(), ex);
            } else {
                log.error("Poll cycle failed; watermark held at {}: {}", watermark, ex.getMessage(), ex);
            }
        } finally {
            durationTimer.record(Duration.ofNanos(System.nanoTime() - startNanos));
            meterRegistry.counter("poll.cycles", "outcome", success ? "success" : "failure").increment();
            meterRegistry.counter("poll.blobs.discovered").increment(discovered);
            meterRegistry.counter("poll.blobs.skipped", "reason", "ledger").increment(skippedByLedger);
            meterRegistry.counter("poll.blobs.ingested").increment(ingested);
            meterRegistry.counter("poll.blobs.failed").increment(failed);
            // A sink that was never built (the cycle failed setting it up) inserted nothing.
            if (sink != null) {
                sink.insertedByTable().forEach((table, count) ->
                        meterRegistry.counter("poll.rows.inserted", "table", table).increment(count));
            }
            meterRegistry.counter("poll.rows.skipped").increment(rowsSkipped);
            meterRegistry.counter("poll.rows.dropped").increment(rowsDropped);
        }

        PollSummary summary = new PollSummary(
                discovered, skippedByLedger, ingested, failed, sink == null ? 0 : sink.totalInserted(), rowsSkipped, rowsDropped, success);
        log.info("Poll cycle finished: {} (watermark={})", summary, watermark);
        return summary;
    }

    /** Drops seen-set entries that have aged out of the overlap window so it cannot grow unbounded. */
    private void pruneSeen() {
        Instant keepFrom = watermark.minus(pollProperties.overlap());
        recentlyIngested.values().removeIf(lastModified -> lastModified.isBefore(keepFrom));
    }

    /**
     * The blob key prefixes to list this cycle.
     *
     * <p>In {@code band} mode (see {@link BlobPrefixResolver}) the poller lists only a recent time
     * slice: it asks the store for start epochs in {@code [cutoff − finalizationLag, now]}, so listing
     * cost does not grow with the full day. The slice reaches back by {@code finalizationLag} below the
     * cutoff because the key sorts by each blob's start time while selection uses its finalization
     * (last-modified) time — so this guarantees every blob with {@code lastModified ≥ cutoff} is still
     * listed; blobs lagging beyond it are left to the daily reconciliation run.
     *
     * <p>In the {@code day} fallback it lists the whole active day prefix, plus the previous day
     * within {@code previousDayGrace} of UTC midnight.
     */
    private List<String> activePrefixes(Instant now, Instant cutoff) {
        if (BAND_LISTING_MODE.equalsIgnoreCase(pollProperties.listingMode())) {
            Instant lowerBound = cutoff.minus(pollProperties.finalizationLag());
            // The width lives with the key layout it encodes, so the prefix example logged at startup and
            // the prefixes listed here cannot describe different slices.
            return prefixResolver.bandPrefixes(lowerBound, now, BlobPrefixResolver.BAND_WIDTH_SECONDS);
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<String> prefixes = new ArrayList<>();
        prefixes.add(prefixResolver.dayPrefix(today));
        long secondsSinceMidnight = now.atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay();
        if (secondsSinceMidnight < pollProperties.previousDayGrace().getSeconds()) {
            prefixes.add(prefixResolver.dayPrefix(today.minusDays(1)));
        }
        return prefixes;
    }

    private double watermarkLagSeconds() {
        return Math.max(0, Instant.now().getEpochSecond() - watermark.getEpochSecond());
    }

    /** The current watermark; package-visible for tests. */
    Instant watermark() {
        return watermark;
    }

    /**
     * Summary of a single poll cycle.
     *
     * @param discovered   new blobs found newer than the watermark (less overlap) this cycle and selected for
     *                     ingestion; blobs the ledger records are not counted
     * @param skipped      candidate blobs not read because the ingest ledger records them
     * @param ingested     blobs successfully ingested this cycle
     * @param failed       blobs that failed to ingest this cycle (counted and skipped, not fatal)
     * @param rowsInserted rows inserted across all ingested blobs
     * @param rowsSkipped  malformed lines skipped across all ingested blobs
     * @param rowsDropped  records dropped for lacking a deduplication key across all ingested blobs
     * @param success      whether the cycle completed without a listing-level failure
     */
    public record PollSummary(
            long discovered,
            long skipped,
            long ingested,
            long failed,
            long rowsInserted,
            long rowsSkipped,
            long rowsDropped,
            boolean success) {
    }
}
