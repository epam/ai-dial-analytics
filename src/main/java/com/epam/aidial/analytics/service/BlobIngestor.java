package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parses a single blob into a caller-supplied {@link RunSink}: streams → parses → maps → appends its
 * rows. Shared by both ingestion paths — the scheduled reconciliation run ({@link DataSyncService})
 * and the near-real-time poller ({@link BlobPollingService}) — so the parse/map pipeline behaves
 * identically regardless of how a blob is discovered. The sink (owned by the caller for the whole run)
 * batches inserts across blobs, so this component no longer flushes per blob.
 *
 * <p>Multi-target: each parsed record is offered to every configured {@link SyncTarget}. A target
 * emits a row only when its mapping's {@link SyncTarget#matcher() match predicate} accepts the record,
 * so one record MAY produce rows in several tables (e.g. a {@code /rate} record lands in both
 * {@code dial_usage_log} and {@code rate_analytics}). The default configuration has a single match-all
 * target, preserving the original single-table behavior.
 *
 * <p>Deduplication is NOT performed here: the ingest paths skip blobs the {@link IngestLedger} records before
 * calling in, and whatever reaches this component is parsed and appended in full; each target table's
 * {@code ReplacingMergeTree} engine collapses duplicates on merge by its {@code ORDER BY} sort key within a daily partition. The two seeded sources key on the full five-column
 * tuple ({@code project_id, deployment, request_time, trace_id, core_span_id}); the payload enrichment
 * ({@code dial_usage_log_payload}) keys on {@code event_id} alone. For either collapse to be sound every
 * column in the key must be deterministic for a given span, so a record missing any of the columns this
 * ingestor can verify ({@code trace_id}, {@code core_span_id}, {@code request_time}) is dropped for
 * that target and counted. That rule covers the single-key target too, indirectly: {@code event_id} is
 * the deterministic hash of {@code (trace_id, core_span_id)}, both verified non-blank here, so it is as
 * stable per span as the five-column key — and without the check a blank {@code trace_id} would hash
 * every such record to ONE constant {@code event_id} and collapse them all into a single payload row.
 * {@code request_time} matters specifically because it also drives
 * {@code PARTITION BY toYYYYMMDD(request_time)}: an absent timestamp would otherwise land in the
 * {@code 1970-01-01} partition and could never collapse with the real row.
 *
 * <p>Targets that enrich other targets flush in dependency order — see {@link RunSink} — so a source row
 * never becomes visible before the enrichment row written beside it. Which targets those are is the
 * catalog's answer ({@link IngestFlushOrder}), asked once per run.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BlobIngestor {

    /**
     * ClickHouse columns from the {@code ReplacingMergeTree} sort/partition key that this ingestor can
     * verify per record; a record missing any of them is dropped, since a blank value would break the
     * determinism the dedup collapse relies on (see class Javadoc).
     */
    private static final String TRACE_ID_COLUMN = "trace_id";
    private static final String CORE_SPAN_ID_COLUMN = "core_span_id";
    private static final String REQUEST_TIME_COLUMN = "request_time";

    private final BlobClient blobClient;
    private final LogParser logParser;
    private final List<SyncTarget> targets;
    private final IngestFlushOrder flushOrder;

    /**
     * Creates a run-scoped {@link RunSink} covering every configured target table, sharing the given
     * writer. Callers create one per run so rows batch across blobs. The sink learns here which of the
     * targets are enrichments of which, so it can flush them ahead of their source.
     *
     * @param writer        the shared ClickHouse writer
     * @param batchSize     rows per batch before a per-table sink flushes
     * @param maxBatchBytes accumulated serialized bytes before a per-table sink flushes
     * @return a run sink keyed by the configured target tables
     */
    public RunSink newRunSink(UsageLogWriter writer, int batchSize, long maxBatchBytes) {
        return newRunSink(writer, batchSize, maxBatchBytes, ignored -> { });
    }

    /**
     * As {@link #newRunSink(UsageLogWriter, int, long)}, handing each blob ingested through
     * {@link #ingest(BlobListing, RunSink)} to {@code onBlobsWritten} once all its rows are written (see
     * {@link RunSink}) — how the ingest paths record blobs in the ingest ledger.
     *
     * @param onBlobsWritten receives the blobs whose rows are all written, one batch per hand-over
     */
    public RunSink newRunSink(UsageLogWriter writer, int batchSize, long maxBatchBytes, Consumer<List<BlobListing>> onBlobsWritten) {
        final List<String> tables = targets.stream().map(SyncTarget::table).toList();
        return new RunSink(writer, tables, flushOrder.sourceOf(tables), batchSize, maxBatchBytes, onBlobsWritten);
    }

    /**
     * As {@link #ingest(String, RunSink)}, telling the sink which blob the rows belong to and when its read
     * ended, so the sink can report the blob once its rows are all written. A blob whose read throws is never
     * reported.
     *
     * @param listing the listed blob: its key and last-modified time
     * @param sink    the run-scoped fan-out sink to append rows to
     * @return the ingest outcome
     * @throws IOException                 if the blob cannot be read or decompressed
     * @throws RowBatchSink.FlushException if a batch flush, or recording the blobs it wrote, fails
     */
    public IngestResult ingest(BlobListing listing, RunSink sink) throws IOException {
        sink.startBlob(listing);
        IngestResult result = ingest(listing.name(), sink);
        sink.finishBlob();
        return result;
    }

    /**
     * Parses the named blob and, for each parsed record, appends a row to every target whose match
     * predicate accepts it (and whose row carries the verifiable key columns). Records without
     * {@code trace_id}, {@code core_span_id}, or {@code request_time} are dropped for that target. Rows
     * are written when a per-table sink fills or when the caller flushes at end of run, not per blob.
     *
     * @param blobName full blob key within the configured container
     * @param sink     the run-scoped fan-out sink to append rows to
     * @return the ingest outcome (rows accepted into the sink, malformed lines skipped, records dropped)
     * @throws IOException                  if the blob cannot be read or decompressed
     * @throws RowBatchSink.FlushException  if a batch flush triggered by appending a row fails
     */
    public IngestResult ingest(String blobName, RunSink sink) throws IOException {
        Counters counters = new Counters();
        long malformed;
        try (InputStream stream = blobClient.openStream(blobName)) {
            malformed = logParser.parse(stream, blobName, node -> {
                for (SyncTarget target : targets) {
                    if (!target.matcher().matches(node)) {
                        continue;
                    }
                    Map<String, Object> row = target.engine().toRow(node, blobName);
                    if (missingDedupKey(row)) {
                        counters.dropped++;
                        log.warn("Dropping record without a complete dedup key "
                                + "(trace_id/core_span_id/request_time) for target '{}' in {}",
                                target.table(), blobName);
                        continue;
                    }
                    sink.add(target.table(), row);
                    counters.accepted++;
                }
            });
        }
        log.info("Ingested {}: {} rows accepted, {} malformed lines skipped, {} records dropped",
                blobName, counters.accepted, malformed, counters.dropped);
        return new IngestResult(counters.accepted, malformed, counters.dropped);
    }

    /** Per-blob tallies mutated from the parse callback; single-threaded, so no atomics are needed. */
    private static final class Counters {
        private long accepted;
        private long dropped;
    }

    private static boolean missingDedupKey(Map<String, Object> row) {
        return isBlank(row.get(TRACE_ID_COLUMN))
                || isBlank(row.get(CORE_SPAN_ID_COLUMN))
                || isBlank(row.get(REQUEST_TIME_COLUMN));
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    /**
     * Outcome of ingesting one blob.
     *
     * @param rowsInserted rows from the blob accepted into the sink across all targets (written by the sink's batching)
     * @param rowsSkipped  malformed lines skipped
     * @param rowsDropped  target rows dropped for lacking a deduplication key
     */
    public record IngestResult(long rowsInserted, long rowsSkipped, long rowsDropped) {
    }
}
