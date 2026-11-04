package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.ClickHouseDataRejectedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A batch ClickHouse refuses for its data is split by blob (see {@code openspec/specs/data-sync/spec.md}, "A batch
 * ClickHouse refuses for its data is split by blob"): the blobs whose rows are accepted are written, a blob refused on
 * its own is named and left out, and its rows still buffered elsewhere in the run are dropped with it.
 */
class BlobSplitTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "dial_usage_log_payload";

    /** Refuses, with {@code CANNOT_PARSE_DATETIME}, any insert into {@link #refusing} that carries a poison row. */
    private static class PoisonWriter extends RecordingWriter {
        final List<Integer> attemptSizes = new ArrayList<>();
        String refusing = SOURCE;
        RuntimeException other;

        @Override
        public void insert(String table, List<Map<String, Object>> batch) {
            attemptSizes.add(batch.size());
            if (other != null) {
                throw other;
            }
            if (table.equals(refusing) && batch.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("poison")))) {
                throw new ClickHouseDataRejectedException(table, 41, new RuntimeException("cannot parse"));
            }
            super.insert(table, batch);
        }
    }

    private static BlobListing blob(String name) {
        return new BlobListing(name, Instant.parse("2026-01-10T00:00:01.123Z"));
    }

    private static Map<String, Object> row(String blob, int n) {
        return Map.of("blob", blob, "n", n);
    }

    private static Map<String, Object> poison(String blob) {
        return Map.of("blob", blob, "poison", true);
    }

    /** The stage is told each row's blob before the first sub-insert, so it can undo links between blobs. */
    @Test
    void theStageIsIsolatedByBlobBeforeAnySubInsert() {
        PoisonWriter writer = new PoisonWriter();
        List<String> events = new ArrayList<>();
        Map<String, Object> first = row("b0", 1);
        Map<String, Object> second = poison("b1");
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE, rows -> new FlushStage.Written() {
            @Override
            public void written(Set<Map<String, Object>> notStored) {
                events.add("written " + notStored.size());
            }

            @Override
            public void isolate(Function<Map<String, Object>, ?> partOf) {
                events.add("isolate after " + writer.attemptSizes.size() + " insert(s): "
                        + ((BlobListing) partOf.apply(first)).name() + "," + ((BlobListing) partOf.apply(second)).name());
            }
        });
        sink.offer(first, blob("b0"));
        sink.offer(second, blob("b1"));

        sink.flush();

        assertThat(events).containsExactly("isolate after 1 insert(s): b0,b1", "written 1");
        assertThat(writer.attemptSizes).containsExactly(2, 1, 1);
    }

    @Test
    void oneRefusedBlobAmongManyLeavesTheOthersWritten() {
        PoisonWriter writer = new PoisonWriter();
        List<Set<Map<String, Object>>> notStoredSeen = new ArrayList<>();
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE, rows -> notStoredSeen::add);
        for (int b = 0; b < 8; b++) {
            BlobListing listing = blob("b" + b);
            sink.offer(row(listing.name(), 1), listing);
            sink.offer(b == 5 ? poison(listing.name()) : row(listing.name(), 2), listing);
        }

        List<RefusedBlob> refused = sink.flush();

        assertThat(refused).singleElement().satisfies(blob -> {
            assertThat(blob.listing().name()).isEqualTo("b5");
            assertThat(blob.table()).isEqualTo(SOURCE);
            assertThat(blob.code()).isEqualTo(41);
            assertThat(blob.name()).isEqualTo("CANNOT_PARSE_DATETIME");
            assertThat(blob.describe()).contains(SOURCE, "41 CANNOT_PARSE_DATETIME").doesNotContain("poison");
        });
        assertThat(writer.rowsFor(SOURCE)).hasSize(14).noneMatch(stored -> "b5".equals(stored.get("blob")));
        assertThat(sink.totalInserted()).isEqualTo(14);
        // The stage learns once, of the two rows not stored.
        assertThat(notStoredSeen).singleElement().satisfies(notStored ->
                assertThat(notStored).hasSize(2).allMatch(stored -> "b5".equals(stored.get("blob"))));
        // 8 blobs: the whole batch, then halves of 4, 2 and 1 around b5, plus the accepted siblings at each level.
        assertThat(writer.attemptSizes).hasSizeLessThanOrEqualTo(1 + 2 * 3);
    }

    @Test
    void refusedBlobsInBothHalvesAreEachIsolated() {
        PoisonWriter writer = new PoisonWriter();
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE);
        for (int b = 0; b < 6; b++) {
            BlobListing listing = blob("b" + b);
            sink.offer(b == 1 || b == 4 ? poison(listing.name()) : row(listing.name(), 1), listing);
        }

        assertThat(sink.flush()).extracting(blob -> blob.listing().name()).containsExactly("b1", "b4");
        assertThat(writer.rowsFor(SOURCE)).extracting(stored -> stored.get("blob")).containsExactlyInAnyOrder("b0", "b2", "b3", "b5");
    }

    @Test
    void everyBlobRefusedIsEveryBlobIsolated() {
        PoisonWriter writer = new PoisonWriter();
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE);
        for (int b = 0; b < 4; b++) {
            BlobListing listing = blob("b" + b);
            sink.offer(poison(listing.name()), listing);
        }

        assertThat(sink.flush()).hasSize(4);
        assertThat(writer.rowsFor(SOURCE)).isEmpty();
        assertThat(sink.totalInserted()).isZero();
    }

    @Test
    void failureOutsideTheDataClassIsNotSplit() {
        PoisonWriter writer = new PoisonWriter();
        writer.other = new IllegalStateException("connection refused");
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE);
        sink.offer(row("a", 1), blob("a"));
        sink.offer(row("b", 1), blob("b"));

        assertThatThrownBy(sink::flush).isInstanceOf(RowBatchSink.FlushException.class).hasRootCauseMessage("connection refused");
        assertThat(writer.attemptSizes).containsExactly(2);
    }

    @Test
    void splitInsertFailingOutsideTheDataClassFailsTheFlush() {
        PoisonWriter writer = new PoisonWriter() {
            @Override
            public void insert(String table, List<Map<String, Object>> batch) {
                if (attemptSizes.size() == 1) {
                    other = new IllegalStateException("server went away");
                }
                super.insert(table, batch);
            }
        };
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE);
        sink.offer(poison("a"), blob("a"));
        sink.offer(row("b", 1), blob("b"));

        assertThatThrownBy(sink::flush).isInstanceOf(RowBatchSink.FlushException.class).hasRootCauseMessage("server went away");
    }

    @Test
    void stageRowsAreNotPreparedAgainWhenSplit() {
        PoisonWriter writer = new PoisonWriter();
        int[] prepared = {0};
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE, rows -> {
            prepared[0]++;
            return FlushStage.Written.NONE;
        });
        sink.offer(poison("a"), blob("a"));
        sink.offer(row("b", 1), blob("b"));

        sink.flush();

        assertThat(prepared[0]).isEqualTo(1);
    }

    @Test
    void rowNoBlobOwnsCannotBeSplit() {
        PoisonWriter writer = new PoisonWriter();
        RowBatchSink sink = new RowBatchSink(writer, SOURCE, 1000, Long.MAX_VALUE);
        sink.offer(poison("a"));
        sink.offer(row("b", 1), blob("b"));

        assertThatThrownBy(sink::flush).isInstanceOf(RowBatchSink.FlushException.class);
    }

    @Test
    void refusedEnrichmentRowWithholdsItsBlobsBufferedSourceRows() {
        PoisonWriter writer = new PoisonWriter();
        writer.refusing = ENRICHMENT;
        List<RunSink.HandOver> handOvers = new ArrayList<>();
        RunSink sink = new RunSink(writer, List.of(SOURCE, ENRICHMENT), Map.of(ENRICHMENT, SOURCE), Map.of(), 1000, Long.MAX_VALUE,
                handOvers::add);

        sink.startBlob(blob("good"));
        sink.add(SOURCE, row("good", 1));
        sink.add(ENRICHMENT, row("good", 1));
        sink.finishBlob(null);
        sink.startBlob(blob("bad"));
        sink.add(SOURCE, row("bad", 1));
        sink.add(ENRICHMENT, poison("bad"));
        sink.finishBlob(null);

        sink.flush();

        assertThat(writer.rowsFor(ENRICHMENT)).extracting(stored -> stored.get("blob")).containsExactly("good");
        assertThat(writer.rowsFor(SOURCE)).extracting(stored -> stored.get("blob")).containsExactly("good");
        assertThat(handOvers).singleElement().satisfies(handOver -> {
            assertThat(handOver.written()).extracting(BlobListing::name).containsExactly("good");
            assertThat(handOver.refused()).extracting(refused -> refused.listing().name()).containsExactly("bad");
            assertThat(handOver.refused().getFirst().table()).isEqualTo(ENRICHMENT);
        });
    }

    @Test
    void blobRefusedWhileItIsReadDropsItsLaterRowsAndIsNeverHandedOver() {
        PoisonWriter writer = new PoisonWriter();
        List<RunSink.HandOver> handOvers = new ArrayList<>();
        // A batch of two fills on the bad blob's second row, while it is still being read.
        RunSink sink = new RunSink(writer, List.of(SOURCE), Map.of(), Map.of(), 2, Long.MAX_VALUE, handOvers::add);

        sink.startBlob(blob("bad"));
        sink.add(SOURCE, poison("bad"));
        sink.add(SOURCE, row("bad", 2));
        sink.add(SOURCE, row("bad", 3));
        sink.finishBlob(null);
        sink.flush();

        assertThat(writer.rowsFor(SOURCE)).isEmpty();
        assertThat(handOvers).flatExtracting(RunSink.HandOver::written).isEmpty();
        assertThat(handOvers).flatExtracting(RunSink.HandOver::refused).extracting(refused -> refused.listing().name()).containsExactly("bad");
    }

    /** A blob whose read failed never reached {@code finishBlob}: it is not handed over, so its skips are recorded nowhere. */
    @Test
    void blobWhoseReadFailedHandsOverNothing() {
        List<RunSink.HandOver> handOvers = new ArrayList<>();
        RunSink sink = new RunSink(new RecordingWriter(), List.of(SOURCE), Map.of(), Map.of(), 1000, Long.MAX_VALUE, handOvers::add);

        sink.startBlob(blob("broken"));
        sink.add(SOURCE, row("broken", 1));
        // The read threw here, before finishBlob; the next blob is read as usual.
        sink.startBlob(blob("next"));
        sink.add(SOURCE, row("next", 1));
        sink.finishBlob(new SkippedRecords());
        sink.flush();

        assertThat(handOvers).flatExtracting(RunSink.HandOver::written).extracting(BlobListing::name).containsExactly("next");
        assertThat(handOvers).allSatisfy(handOver -> assertThat(handOver.skipped()).isEmpty());
    }

    @Test
    void handOverCarriesWhatTheWrittenBlobsSkipped() {
        List<RunSink.HandOver> handOvers = new ArrayList<>();
        RunSink sink = new RunSink(new RecordingWriter(), List.of(SOURCE), Map.of(), Map.of(), 1000, Long.MAX_VALUE, handOvers::add);
        SkippedRecords skips = new SkippedRecords();
        skips.add(SkippedRecords.Cause.MALFORMED_LINE, 3);

        sink.startBlob(blob("a"));
        sink.add(SOURCE, row("a", 1));
        sink.finishBlob(skips);
        sink.startBlob(blob("b"));
        sink.add(SOURCE, row("b", 1));
        sink.finishBlob(new SkippedRecords());
        sink.flush();

        assertThat(handOvers).singleElement().satisfies(handOver -> {
            assertThat(handOver.written()).extracting(BlobListing::name).containsExactly("a", "b");
            assertThat(handOver.skipped()).containsOnlyKeys(blob("a"));
        });
    }
}
