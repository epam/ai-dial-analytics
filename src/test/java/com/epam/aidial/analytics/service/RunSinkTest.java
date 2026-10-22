package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flush order of a multi-target run sink, which is what keeps a data-sync-written enrichment
 * ({@code dial_usage_log_payload}) from being read as absent while its rows sit in a buffer. Every case
 * here is one where the previous per-sink flushing would have let a usage-log row become visible ahead of
 * its payload row — the shape an enrichment scan turns into a permanently wrong derived value.
 */
class RunSinkTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "dial_usage_log_payload";
    private static final String OTHER = "rate_analytics";

    /** A writer that records the ORDER of inserts, per table, and can be told to fail one table. */
    private static final class OrderRecordingWriter extends RecordingWriter {
        final List<String> insertOrder = new ArrayList<>();
        String failing;

        @Override
        public void insert(String table, List<Map<String, Object>> batch) {
            if (table.equals(failing)) {
                throw new IllegalStateException("writer down for " + table);
            }
            insertOrder.add(table + ":" + batch.size());
            super.insert(table, batch);
        }
    }

    private static Map<String, Object> row(int n) {
        return Map.of("n", n);
    }

    @Test
    void sourceSinkFillingFlushesItsEnrichmentFirst() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        // The source fills at 2 rows; the enrichment, holding the same rows, has room for many more.
        final RunSink sink = new RunSink(writer, List.of(SOURCE, ENRICHMENT), Map.of(ENRICHMENT, SOURCE), 2, Long.MAX_VALUE);

        sink.add(SOURCE, row(1));
        sink.add(ENRICHMENT, row(1));
        assertThat(writer.insertOrder).isEmpty();
        sink.add(ENRICHMENT, row(2));
        sink.add(SOURCE, row(2)); // the source reaches its threshold here

        // The buffered payload rows went first, then the source rows — never the source alone.
        assertThat(writer.insertOrder).containsExactly(ENRICHMENT + ":2", SOURCE + ":2");
    }

    @Test
    void enrichmentSinkFillingFlushesAlone() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        final RunSink sink = new RunSink(writer, List.of(SOURCE, ENRICHMENT), Map.of(ENRICHMENT, SOURCE), 2, Long.MAX_VALUE);

        sink.add(SOURCE, row(1));
        sink.add(ENRICHMENT, row(1));
        sink.add(ENRICHMENT, row(2)); // the enrichment reaches its threshold; the source has one buffered row

        // An enrichment row visible before its source row is harmless — nothing reads the enrichment except
        // through the source — so the source buffer is left alone rather than flushed half-full.
        assertThat(writer.insertOrder).containsExactly(ENRICHMENT + ":2");
        assertThat(writer.rowsFor(SOURCE)).isEmpty();
    }

    @Test
    void endOfRunFlushWritesEnrichmentsBeforeTheirSource() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        // Declared source-first, as sync.targets lists them; the order still comes out enrichment-first.
        final RunSink sink = new RunSink(writer, List.of(SOURCE, ENRICHMENT, OTHER), Map.of(ENRICHMENT, SOURCE), 100, Long.MAX_VALUE);
        sink.add(SOURCE, row(1));
        sink.add(ENRICHMENT, row(1));
        sink.add(OTHER, row(1));

        sink.flush();

        assertThat(writer.insertOrder).containsExactly(ENRICHMENT + ":1", SOURCE + ":1", OTHER + ":1");
        assertThat(sink.flushedBefore(SOURCE)).containsExactly(ENRICHMENT);
        assertThat(sink.flushedBefore(OTHER)).isEmpty();
    }

    /**
     * The failure direction is the one that matters: if the enrichment write fails, the source rows of the
     * same batch are never written, so the run faults with both tables equally behind (the next run repairs
     * it) rather than with usage-log rows whose payloads are gone for good.
     */
    @Test
    void enrichmentFlushFailureStopsTheSourceFromBeingWritten() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        writer.failing = ENRICHMENT;
        final RunSink sink = new RunSink(writer, List.of(SOURCE, ENRICHMENT), Map.of(ENRICHMENT, SOURCE), 100, Long.MAX_VALUE);
        sink.add(SOURCE, row(1));
        sink.add(ENRICHMENT, row(1));

        assertThatThrownBy(sink::flush).isInstanceOf(RowBatchSink.FlushException.class);

        assertThat(writer.rowsFor(SOURCE)).isEmpty();
        assertThat(writer.rowsFor(ENRICHMENT)).isEmpty();
    }

    @Test
    void independentTargetsKeepFlushingOnTheirOwnThresholds() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        final RunSink sink = new RunSink(writer, List.of(SOURCE, OTHER), 2, Long.MAX_VALUE);

        sink.add(SOURCE, row(1));
        sink.add(OTHER, row(1));
        sink.add(OTHER, row(2));

        // With no dependency declared this is the pre-payload behaviour: the full sink flushes, the other waits.
        assertThat(writer.insertOrder).containsExactly(OTHER + ":2");
        assertThat(sink.totalInserted()).isEqualTo(2);
    }

    // ---- which blobs are written ------------------------------------------------------------------------

    private static BlobListing blob(String name) {
        return new BlobListing(name, Instant.parse("2026-06-18T00:00:01.123Z"));
    }

    private static List<String> names(List<List<BlobListing>> batches) {
        return batches.stream().flatMap(List::stream).map(BlobListing::name).toList();
    }

    /** A sink with a source/enrichment pair and an independent table, reporting written blobs into {@code reported}. */
    private static RunSink trackingSink(RecordingWriter writer, int batchSize, List<List<BlobListing>> reported) {
        return new RunSink(writer, List.of(SOURCE, ENRICHMENT, OTHER), Map.of(ENRICHMENT, SOURCE), batchSize, Long.MAX_VALUE,
                reported::add);
    }

    /**
     * A blob is written only once every table holding its rows has flushed: here its OTHER row is written by
     * the OTHER sink filling, but its SOURCE row is still buffered, so it waits for the end-of-run flush.
     */
    @Test
    void blobIsReportedOnlyAfterEveryTableHoldingItsRowsFlushed() {
        final List<List<BlobListing>> reported = new ArrayList<>();
        final RunSink sink = trackingSink(new OrderRecordingWriter(), 2, reported);

        sink.startBlob(blob("a"));
        sink.add(SOURCE, row(1));
        sink.add(OTHER, row(1));
        sink.finishBlob();
        sink.startBlob(blob("b"));
        sink.add(OTHER, row(2)); // OTHER fills: a's OTHER row is written, its SOURCE row is not
        assertThat(reported).isEmpty();

        sink.finishBlob(); // b's only row went out with that flush, so b is written; a still waits on SOURCE
        assertThat(names(reported)).containsExactly("b");
        sink.flush();
        assertThat(reported).extracting(batch -> batch.stream().map(BlobListing::name).toList())
                .containsExactly(List.of("b"), List.of("a"));
    }

    /** Enrichment-first order still holds, and the blobs completed by one flush are reported as one batch. */
    @Test
    void blobsCompletedByOneFlushAreReportedTogetherAfterIt() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        final List<List<BlobListing>> reported = new ArrayList<>();
        final RunSink sink = trackingSink(writer, 2, reported);

        sink.startBlob(blob("a"));
        sink.add(ENRICHMENT, row(1));
        sink.add(SOURCE, row(1));
        sink.finishBlob();
        sink.startBlob(blob("b"));
        sink.add(ENRICHMENT, row(2)); // ENRICHMENT fills and flushes alone; a still waits on SOURCE
        assertThat(reported).isEmpty();
        sink.add(SOURCE, row(2));     // SOURCE fills: its (now empty) enrichment first, then SOURCE, and a is written
        assertThat(writer.insertOrder).containsExactly(ENRICHMENT + ":2", SOURCE + ":2");
        assertThat(names(reported)).containsExactly("a");

        sink.finishBlob(); // b's rows went out in the same flush, so it is written the moment its read ends
        assertThat(names(reported)).containsExactly("a", "b");
    }

    @Test
    void blobWithNoRowsIsReportedAtOnceEvenAsTheLastBlob() {
        final List<List<BlobListing>> reported = new ArrayList<>();
        final RunSink sink = trackingSink(new OrderRecordingWriter(), 100, reported);

        sink.startBlob(blob("rows"));
        sink.add(SOURCE, row(1));
        sink.finishBlob();
        sink.startBlob(blob("empty"));
        sink.finishBlob();

        assertThat(names(reported)).containsExactly("empty");
        sink.flush();
        assertThat(names(reported)).containsExactly("empty", "rows");
    }

    @Test
    void flushWithEveryBufferEmptyStillHandsOverWrittenBlobs() {
        final List<List<BlobListing>> reported = new ArrayList<>();
        final RecordingWriter writer = new OrderRecordingWriter();
        final RunSink sink = trackingSink(writer, 1, reported);

        sink.startBlob(blob("a"));
        sink.add(SOURCE, row(1)); // flushed at once (batch size 1) while a is still being read
        sink.finishBlob();        // so a is written when its read ends
        assertThat(names(reported)).containsExactly("a");

        sink.flush();
        assertThat(writer.insertCalls).isEqualTo(1);
        assertThat(names(reported)).containsExactly("a");
    }

    @Test
    void failedFlushReportsNothing() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        writer.failing = SOURCE;
        final List<List<BlobListing>> reported = new ArrayList<>();
        final RunSink sink = trackingSink(writer, 100, reported);
        sink.startBlob(blob("a"));
        sink.add(SOURCE, row(1));
        sink.finishBlob();
        sink.startBlob(blob("b"));
        sink.add(OTHER, row(1));
        sink.finishBlob();

        assertThatThrownBy(sink::flush).isInstanceOf(RowBatchSink.FlushException.class);

        assertThat(reported).isEmpty();
        assertThatThrownBy(sink::flush).isInstanceOf(IllegalStateException.class);
    }

    /** A blob whose read never finished — its ingest threw — is never reported, though its buffered rows are written. */
    @Test
    void abandonedBlobIsNeverReported() {
        final List<List<BlobListing>> reported = new ArrayList<>();
        final RecordingWriter writer = new OrderRecordingWriter();
        final RunSink sink = trackingSink(writer, 100, reported);
        sink.startBlob(blob("broken"));
        sink.add(SOURCE, row(1));
        sink.startBlob(blob("good"));
        sink.add(SOURCE, row(2));
        sink.finishBlob();

        sink.flush();

        assertThat(names(reported)).containsExactly("good");
        assertThat(writer.rowsFor(SOURCE)).hasSize(2);
    }

    /** The callback's failure is a run-level fault, never one of the blob being read when it happened. */
    @Test
    void callbackFailureSurfacesAsFlushFault() {
        final RunSink sink = new RunSink(new OrderRecordingWriter(), List.of(SOURCE), Map.of(), 1, Long.MAX_VALUE, written -> {
            throw new IllegalStateException("ledger down");
        });
        sink.startBlob(blob("a"));
        sink.add(SOURCE, row(1));

        assertThatThrownBy(sink::finishBlob)
                .isInstanceOf(RowBatchSink.FlushException.class)
                .hasRootCauseMessage("ledger down");
        assertThatThrownBy(() -> sink.add(SOURCE, row(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dependencyOnTableOutsideTheRunIsIgnored() {
        final OrderRecordingWriter writer = new OrderRecordingWriter();
        final RunSink sink = new RunSink(writer, List.of(ENRICHMENT), Map.of(ENRICHMENT, SOURCE), 1, Long.MAX_VALUE);

        sink.add(ENRICHMENT, row(1));

        assertThat(writer.insertOrder).containsExactly(ENRICHMENT + ":1");
    }
}
