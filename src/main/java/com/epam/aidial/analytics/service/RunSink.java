package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A run-scoped fan-out sink: one {@link RowBatchSink} per ingest target table, sharing a single
 * {@link UsageLogWriter}. {@link BlobIngestor} routes each mapped row to the sink for its target table
 * via {@link #add(String, Map)}; each per-table sink batches independently (so a target's rows are not
 * diluted by another's) and fills on its own count/byte thresholds. This replaces the previous
 * single {@link RowBatchSink} so a run can populate several tables (e.g. {@code dial_usage_log} and
 * {@code rate_analytics}) from the same parse pass.
 *
 * <p><b>Flush order is load-bearing when one target is an enrichment of another.</b>
 * {@code dial_usage_log_payload} is a system enrichment of {@code dial_usage_log}: the two rows a record
 * produces are read back through an outer grain join, and every consumer of that join — a query, an
 * enrichment scan, an aggregate refresh — treats an unmatched payload as <em>absent</em>. Absent is
 * final for them: a scan that reads a usage-log row while its payload row is still buffered derives its
 * value from a NULL body, records it, and never revisits the row, because the source row never moves
 * again. So a source row must not become visible before the enrichment rows written beside it. Flushing
 * each sink on its own threshold cannot promise that; this sink does, by flushing in dependency order:
 * <ul>
 *   <li>when a sink fills, every sink that holds an <em>enrichment of</em> its table is flushed first, then
 *       the full sink itself. An enrichment sink filling on its own flushes alone — an enrichment row
 *       visible before its source row is harmless, since nothing reads the enrichment except through the
 *       source;</li>
 *   <li>the end-of-run {@link #flush()} walks every sink under the same rule;</li>
 *   <li>a {@link RowBatchSink.FlushException} on an enrichment sink propagates before the source sink is
 *       touched, so the source rows of the failed batch are never written — the run faults with the
 *       usage log and its payload equally absent, which the next run repairs, rather than with usage-log
 *       rows whose payloads are gone for good.</li>
 * </ul>
 * The dependency map comes from the catalog ({@link IngestFlushOrder}) rather than from the mapping
 * files, so declaring a new data-sync-written enrichment needs no new mapping syntax. A target with no
 * enrichment among the run's tables flushes exactly as before.
 *
 * <p><b>Which blobs are written.</b> Batches span blobs, so a blob's rows are durable only once every table
 * holding some of them has flushed — not when its read ends. A caller that brackets a blob's read with
 * {@link #startBlob(BlobListing)} and {@link #finishBlob()} learns exactly when that is: the sink tracks,
 * per finished blob, the tables still holding its rows, and hands each blob to the {@code onBlobsWritten}
 * callback (the ingest ledger) once none does — right after the flush that writes its last buffered row, or
 * at once when it finished with nothing buffered (a blob that produced no rows included). Blobs written by
 * the same flush are handed over as one batch, and {@link #flush()} always hands over, so a trailing blob is
 * never left behind. A blob whose read never finished (its ingest threw) is never handed over, and neither is
 * any blob after a failed flush. Tracking only decides when to call back; it never forces a flush, so batching
 * is unchanged.
 *
 * <p>Not thread-safe: a run sink belongs to one run, driven by the single-threaded scheduler. A
 * {@link RowBatchSink.FlushException} from any per-table sink, or from the callback (wrapped as one so no
 * caller can mistake it for a fault of the blob being read), propagates as a run-level fault and leaves this
 * sink unusable.
 */
class RunSink {

    private final Map<String, RowBatchSink> sinks = new LinkedHashMap<>();

    /** Target table → the run's target tables that are enrichments of it, in declaration order. */
    private final Map<String, List<String>> enrichmentsOf = new LinkedHashMap<>();

    private final Consumer<List<BlobListing>> onBlobsWritten;

    /** The blob being read, between {@link #startBlob} and {@link #finishBlob}; {@code null} otherwise. */
    private BlobListing reading;
    /** The tables holding unflushed rows of {@link #reading}. */
    private Set<String> readingTables = new HashSet<>();
    /** Finished blobs whose rows are not all written yet → the tables still holding some of them. */
    private final Map<BlobListing, Set<String>> pending = new LinkedHashMap<>();
    /** Finished blobs whose rows are all written, awaiting the next hand-over. */
    private final List<BlobListing> written = new ArrayList<>();
    private boolean failed;

    RunSink(UsageLogWriter writer, Iterable<String> tables, int batchSize, long maxBatchBytes) {
        this(writer, tables, Map.of(), batchSize, maxBatchBytes);
    }

    RunSink(UsageLogWriter writer, Iterable<String> tables, Map<String, String> sourceOf, int batchSize, long maxBatchBytes) {
        this(writer, tables, sourceOf, batchSize, maxBatchBytes, ignored -> { });
    }

    /**
     * @param sourceOf       enrichment target table → the source target table it enriches, for the pairs where
     *     both are among {@code tables}; every other table is independent
     * @param onBlobsWritten receives, in one batch per hand-over, the finished blobs whose rows are all written
     */
    RunSink(UsageLogWriter writer, Iterable<String> tables, Map<String, String> sourceOf, int batchSize, long maxBatchBytes,
            Consumer<List<BlobListing>> onBlobsWritten) {
        this(writer, tables, sourceOf, Map.of(), batchSize, maxBatchBytes, onBlobsWritten);
    }

    /**
     * @param sourceOf       enrichment target table → the source target table it enriches, for the pairs where
     *     both are among {@code tables}; every other table is independent
     * @param stages         target table → the {@link FlushStage} its sink runs on each batch; absent means none
     * @param onBlobsWritten receives, in one batch per hand-over, the finished blobs whose rows are all written
     */
    RunSink(UsageLogWriter writer, Iterable<String> tables, Map<String, String> sourceOf, Map<String, FlushStage> stages,
            int batchSize, long maxBatchBytes, Consumer<List<BlobListing>> onBlobsWritten) {
        for (String table : tables) {
            sinks.putIfAbsent(table, new RowBatchSink(writer, table, batchSize, maxBatchBytes, stages.getOrDefault(table, FlushStage.NONE)));
        }
        for (String table : sinks.keySet()) {
            String source = sourceOf.get(table);
            if (source != null && sinks.containsKey(source) && !source.equals(table)) {
                enrichmentsOf.computeIfAbsent(source, ignored -> new ArrayList<>()).add(table);
            }
        }
        this.onBlobsWritten = onBlobsWritten;
    }

    /**
     * Starts reading {@code listing}: rows added until {@link #finishBlob()} belong to it. A blob started while
     * another's read never finished (its ingest threw) abandons the earlier one, which is never handed over.
     */
    void startBlob(BlobListing listing) {
        ensureUsable();
        reading = listing;
        readingTables = new HashSet<>();
    }

    /**
     * Ends the read of the blob {@link #startBlob} began. With none of its rows still buffered it is written
     * and handed over at once; otherwise it is handed over after the flush that writes its last row.
     */
    void finishBlob() {
        ensureUsable();
        if (reading == null) {
            return;
        }
        if (readingTables.isEmpty()) {
            written.add(reading);
        } else {
            pending.put(reading, readingTables);
        }
        reading = null;
        readingTables = new HashSet<>();
        handOver();
    }

    /**
     * Appends a row to the sink for {@code table}. When that sink reaches its threshold, the sinks holding
     * its enrichments are flushed first and then it is flushed itself (see the class note).
     */
    void add(String table, Map<String, Object> row) {
        ensureUsable();
        RowBatchSink sink = sinks.get(table);
        if (sink == null) {
            throw new IllegalStateException("No run sink configured for target table '" + table + "'");
        }
        if (reading != null) {
            readingTables.add(table);
        }
        if (sink.offer(row)) {
            flushInOrder(table, new LinkedHashSet<>());
            handOver();
        }
    }

    /**
     * Flushes every per-table sink, enrichments before their source, then hands over every blob that is now
     * written — even when no buffer held a row. Throws {@link RowBatchSink.FlushException} on the first write
     * error, handing over nothing.
     */
    void flush() {
        ensureUsable();
        Set<String> flushed = new LinkedHashSet<>();
        for (String table : sinks.keySet()) {
            flushInOrder(table, flushed);
        }
        handOver();
    }

    /** The tables {@link #flush()} writes before {@code table}, in order — exposed for tests and diagnostics. */
    List<String> flushedBefore(String table) {
        return List.copyOf(enrichmentsOf.getOrDefault(table, List.of()));
    }

    private void flushInOrder(String table, Set<String> flushed) {
        if (!flushed.add(table)) {
            return; // already written on this pass (or a dependency cycle, which the catalog does not permit)
        }
        for (String enrichment : enrichmentsOf.getOrDefault(table, List.of())) {
            flushInOrder(enrichment, flushed);
        }
        try {
            sinks.get(table).flush();
        } catch (RowBatchSink.FlushException ex) {
            failed = true;
            throw ex;
        }
        tableWritten(table);
    }

    /** Every row buffered for {@code table} is now written: no blob waits on it any more. */
    private void tableWritten(String table) {
        readingTables.remove(table);
        Iterator<Map.Entry<BlobListing, Set<String>>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlobListing, Set<String>> entry = entries.next();
            if (entry.getValue().remove(table) && entry.getValue().isEmpty()) {
                written.add(entry.getKey());
                entries.remove();
            }
        }
    }

    private void handOver() {
        if (written.isEmpty()) {
            return;
        }
        List<BlobListing> batch = List.copyOf(written);
        written.clear();
        try {
            onBlobsWritten.accept(batch);
        } catch (RuntimeException ex) {
            failed = true;
            throw new RowBatchSink.FlushException("Failed to record " + batch.size() + " written blob(s) in the ingest ledger", ex);
        }
    }

    private void ensureUsable() {
        if (failed) {
            throw new IllegalStateException("RunSink is unusable after a FlushException");
        }
    }

    /** Total rows written across all per-table sinks so far this run. */
    long totalInserted() {
        long total = 0;
        for (RowBatchSink sink : sinks.values()) {
            total += sink.totalInserted();
        }
        return total;
    }

    /**
     * Rows written so far this run, broken down by target table (insertion order preserved). Lets the
     * ingest paths emit their {@code rows.inserted} metric tagged by {@code table}, so a per-table count
     * (e.g. {@code dial_usage_log} vs {@code rate_analytics}) stays reconcilable rather than conflated.
     */
    Map<String, Long> insertedByTable() {
        Map<String, Long> byTable = new LinkedHashMap<>();
        for (Map.Entry<String, RowBatchSink> entry : sinks.entrySet()) {
            byTable.put(entry.getKey(), entry.getValue().totalInserted());
        }
        return byTable;
    }
}
