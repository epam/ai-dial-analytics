package com.epam.aidial.analytics.service;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.clickhouse.TestClickHouseSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.PollProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.exception.ClickHouseStorageFullException;
import com.epam.aidial.analytics.observability.TestClientSpanTracer;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link BlobPollingService} against the jclouds <em>filesystem</em> provider, controlling
 * each blob's last-modified time to drive the watermark logic: a blob newer than the watermark is
 * ingested, an older one is not, the watermark advances, a blob already ingested this window is not
 * re-read, and a failed cycle leaves the watermark unchanged.
 */
class BlobPollingServiceTest {

    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "logs";
    /** The default file-name head: the shape DIAL's producer writes by default, and what band listing assumes. */
    private static final String DEFAULT_HEAD = "'date='yyyy-MM-dd";
    private static final String NDJSON = """
            {"apiType":"DialOpenAI","project":{"id":"p1"},"request":{"time":"2026-06-18T00:00:01"},"token_usage":{"total_tokens":1},"trace":{"trace_id":"t1","core_span_id":"s1"}}
            {"apiType":"DialOpenAI","project":{"id":"p2"},"request":{"time":"2026-06-18T00:00:02"},"token_usage":{"total_tokens":2},"trace":{"trace_id":"t2","core_span_id":"s2"}}
            {"apiType":"DialOpenAI","project":{"id":"p3"},"request":{"time":"2026-06-18T00:00:03"},"token_usage":{"total_tokens":3},"trace":{"trace_id":"t3","core_span_id":"s3"}}
            """;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    /** Shared by every service a test builds, so a second instance stands for a restarted process. */
    private final InMemoryIngestLedger ledger = new InMemoryIngestLedger();
    private Path containerDir;
    private String datePath;
    // The date-directory layout the fixtures and the service under test share; see useDateLayout.
    private DateTimeFormatter datePathFormat;
    private BlobProperties blobProps;
    private BlobClient blobClient;
    private List<SyncTarget> targets;
    private SyncProperties syncProps;

    @BeforeEach
    void setUp(@TempDir Path baseDir) {
        containerDir = baseDir.resolve(BUCKET);
        useDateLayout("yyyy/MM/dd");

        blobProps = new BlobProperties(
                "filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString()));
        TransformRegistry registry = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig mappingConfig = new MappingFileLoader(new DefaultResourceLoader(), registry, derivers)
                .load("classpath:mapping/dial-usage-log.yaml");
        MappingEngine mappingEngine = new MappingEngine(mappingConfig, registry, derivers);
        targets = List.of(new SyncTarget("dial_usage_log", mappingConfig, mappingEngine));
        blobClient = new BlobClient(blobProps, TestBlobSpans.noop());
    }

    @Test
    void ingestsBlobsNewerThanWatermarkAndSkipsOlderOnes() throws Exception {
        Instant now = Instant.now();
        writeBlob("fresh.log.gz", now);
        writeBlob("old.log.gz", now.minus(Duration.ofMinutes(30)));

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = service(writer);
        Instant initialWatermark = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.discovered()).isEqualTo(1);
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(summary.rowsInserted()).isEqualTo(3);
        // Only the fresh blob's rows were ingested (the old blob is below the watermark less overlap).
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1", "p2", "p3");
        // Watermark advanced to the fresh blob's last-modified time.
        assertThat(service.watermark()).isAfter(initialWatermark);
    }

    @Test
    void doesNotReIngestAlreadyIngestedBlobButPicksUpNewerOnes() throws Exception {
        Instant now = Instant.now();
        writeBlob("fresh.log.gz", now);

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = service(writer);
        service.runOnce();
        int callsAfterFirst = writer.insertCalls;
        int rowsAfterFirst = writer.rows.size();

        // A second cycle with no new blobs ingests nothing (the fresh blob is in the seen-set).
        BlobPollingService.PollSummary repeat = service.runOnce();
        assertThat(repeat.ingested()).isEqualTo(0);
        // Skipped by the seen-set before the ledger is consulted, so not counted as a ledger skip.
        assertThat(repeat.skipped()).isZero();
        assertThat(writer.insertCalls).isEqualTo(callsAfterFirst);

        // A blob newer than the watermark is ingested on the next cycle.
        writeBlob("newest.log.gz", now.plus(Duration.ofMinutes(1)));
        BlobPollingService.PollSummary third = service.runOnce();
        assertThat(third.ingested()).isEqualTo(1);
        assertThat(writer.rows).hasSize(rowsAfterFirst + 3);
    }

    @Test
    void badBlobIsSkippedWithoutBlockingOthersOrWedgingWatermark() throws Exception {
        Instant now = Instant.now();
        // "corrupt.log.gz" sorts before "good.log.gz", so the filesystem provider lists it first; its
        // non-gzip bytes make ingest throw. The good blob must still be ingested and the watermark
        // must still advance, proving one bad blob does not wedge the cycle.
        writeRawBlob("corrupt.log.gz", "this is not gzip", now);
        writeBlob("good.log.gz", now);

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = service(writer);
        Instant initialWatermark = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.discovered()).isEqualTo(2);
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1", "p2", "p3");
        assertThat(service.watermark()).isAfter(initialWatermark);
        // The corrupt blob is not recorded, so it stays eligible for a retry and for reconciliation.
        assertThat(ledger.recordedKeys()).containsExactly(PATH_PREFIX + "/" + datePath + "/good.log.gz");
    }

    @Test
    void restartDoesNotReplayTheLookbackWindow() throws Exception {
        writeBlob("fresh.log.gz", Instant.now());
        RecordingWriter writer = new RecordingWriter();
        service(writer).runOnce();
        int callsAfterFirst = writer.insertCalls;

        // A new instance has an empty seen-set and a watermark reset to now - lookback; only the ledger remembers.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BlobPollingService restarted = service(blobClient, writer, IngestFlushOrder.NONE, registry);
        BlobPollingService.PollSummary summary = restarted.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.discovered()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(writer.insertCalls).isEqualTo(callsAfterFirst);
        assertThat(registry.counter("poll.blobs.skipped", "reason", "ledger").count()).isEqualTo(1);
        assertThat(registry.counter("poll.blobs.discovered").count()).isZero();
        // Remembered in the seen-set, so the next cycle does not consult the ledger for it again.
        int reads = ledger.reads;
        restarted.runOnce();
        assertThat(ledger.reads).isEqualTo(reads);
    }

    /**
     * Batch size 2 over three one-row blobs: b0 is written by the flush b1's row fills, b1 is written by the
     * time its read ends, and b2's row is in the failed final flush — so exactly b0 and b1 are recorded.
     */
    @Test
    void failedFinalFlushRecordsOnlyTheBlobsAlreadyWritten() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            writeSingleRecordBlob("b" + i + ".log.gz", now, "p" + i, "t" + i, "s" + i);
        }
        RecordingWriter writer = new RecordingWriter();
        writer.failOnInsertCall = 2;
        BlobPollingService service = service(writer);
        Instant before = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        String dir = PATH_PREFIX + "/" + datePath + "/";
        assertThat(summary.success()).isFalse();
        assertThat(service.watermark()).isEqualTo(before);
        assertThat(ledger.recordedKeys()).containsExactly(dir + "b0.log.gz", dir + "b1.log.gz");

        // The next cycle ingests only the unrecorded blob.
        writer.failOnInsertCall = 0;
        writer.rows.clear();
        BlobPollingService.PollSummary retry = service.runOnce();
        assertThat(retry.ingested()).isEqualTo(1);
        assertThat(retry.skipped()).isEqualTo(2);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p2");
    }

    @Test
    void ledgerReadFailureFailsTheCycleAndHoldsTheWatermark() throws Exception {
        writeBlob("fresh.log.gz", Instant.now());
        ledger.failReads = true;
        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = service(writer);
        Instant before = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(service.watermark()).isEqualTo(before);
        assertThat(writer.rows).isEmpty();
    }

    /** A ledger write failure is the cycle's fault, never the blob's: not counted as a failed blob. */
    @Test
    void ledgerWriteFailureFailsTheCycleAndTheNextCycleIngestsAgain() throws Exception {
        writeBlob("fresh.log.gz", Instant.now());
        ledger.failWrites = true;
        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = service(writer);
        Instant before = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(summary.failed()).isZero();
        assertThat(service.watermark()).isEqualTo(before);

        ledger.failWrites = false;
        BlobPollingService.PollSummary retry = service.runOnce();
        assertThat(retry.success()).isTrue();
        assertThat(retry.ingested()).isEqualTo(1);
        assertThat(ledger.recordedKeys()).containsExactly(PATH_PREFIX + "/" + datePath + "/fresh.log.gz");
    }

    @Test
    void listingFailureHoldsWatermark() {
        BlobClient failingClient = new BlobClient(blobProps, TestBlobSpans.noop()) {
            @Override
            public List<BlobListing> listWithMetadata(String prefix) {
                throw new IllegalStateException("blob store unreachable");
            }
        };
        BlobPollingService service = service(failingClient, new RecordingWriter());
        Instant before = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(service.watermark()).isEqualTo(before);
    }

    @Test
    void listWithMetadataSurfacesNamesAndLastModified() throws Exception {
        Instant mtime = Instant.now().minus(Duration.ofMinutes(2));
        writeBlob("fresh.log.gz", mtime);

        List<BlobListing> listings = blobClient.listWithMetadata(PATH_PREFIX + "/" + datePath + "/");

        assertThat(listings).hasSize(1);
        BlobListing listing = listings.getFirst();
        assertThat(listing.name()).isEqualTo(PATH_PREFIX + "/" + datePath + "/fresh.log.gz");
        assertThat(listing.lastModified()).isAfter(Instant.EPOCH);
        // Filesystem mtime resolution can truncate sub-second; compare at second granularity.
        assertThat(listing.lastModified()).isCloseTo(mtime, within(Duration.ofSeconds(2)));
    }

    @Test
    void bandModeListsBlobFinalizedWithinFinalizationLag() throws Exception {
        Instant now = Instant.now();
        // Start 16m ago but finalized (mtime) now: a 16m start→finalization lag, within the 30m budget.
        long startEpoch = now.minus(Duration.ofMinutes(16)).getEpochSecond();
        writeEpochBlob(startEpoch, now.getEpochSecond(), now);

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = bandService(writer, Duration.ofMinutes(30));

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.discovered()).isEqualTo(1);
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(writer.rows).hasSize(3);
    }

    @Test
    void bandModeListsUnderTheConfiguredDateDirectoryLayout() throws Exception {
        // A flat date-directory layout: one `yyyy-MM-dd` directory. The band prefix must place the date directory in
        // that form while leaving the producer's `date=<ISO><epochs>` file name — which is what makes the
        // server-side time filter work — exactly as it was.
        useDateLayout("yyyy-MM-dd");
        Instant now = Instant.now();
        long startEpoch = now.minus(Duration.ofMinutes(16)).getEpochSecond();
        String key = writeEpochBlob(startEpoch, now.getEpochSecond(), now);
        LocalDate day = Instant.ofEpochSecond(startEpoch).atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(key).startsWith(PATH_PREFIX + "/" + DateTimeFormatter.ISO_LOCAL_DATE.format(day) + "/date=");

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = bandService(writer, Duration.ofMinutes(30));

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.discovered()).isEqualTo(1);
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(writer.rows).hasSize(3);
    }

    @Test
    void bandModeDiscoversAnEpochLeadingKey() throws Exception {
        // The whole flat, epoch-leading shape end to end: a single `yyyy-MM-dd` directory AND a file name that starts with
        // the epoch. This is the case that proves the two layouts compose — the date directory reaches the
        // right folder, and the blank head puts the band digits where this producer actually writes them.
        useKeyLayout("yyyy-MM-dd", "");
        Instant now = Instant.now();
        long epoch = now.minus(Duration.ofMinutes(16)).getEpochSecond();
        String key = writeEpochLeadingBlob(epoch, now);
        LocalDate day = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(key).startsWith(PATH_PREFIX + "/" + DateTimeFormatter.ISO_LOCAL_DATE.format(day) + "/" + epoch);

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService.PollSummary summary = bandService(writer, Duration.ofMinutes(30)).runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.discovered()).isEqualTo(1);
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(writer.rows).hasSize(3);
    }

    @Test
    void bandModeMissesAnEpochLeadingKeyUnderTheDefaultHead() throws Exception {
        // The defect this change fixes, pinned: with the head left at the default `date=<ISO>`, every band prefix
        // for a producer that writes `<epoch>-<uuid>` matches nothing, and the cycle still reports success.
        useKeyLayout("yyyy-MM-dd", DEFAULT_HEAD);
        Instant now = Instant.now();
        long epoch = now.minus(Duration.ofMinutes(16)).getEpochSecond();
        writeEpochLeadingBlob(epoch, now);

        RecordingWriter bandWriter = new RecordingWriter();
        BlobPollingService.PollSummary bandSummary = bandService(bandWriter, Duration.ofMinutes(30)).runOnce();

        assertThat(bandSummary.success()).isTrue();
        assertThat(bandSummary.discovered()).isZero();
        assertThat(bandWriter.rows).isEmpty();

        // The full-day fallback never reads the file name, so it is the documented workaround: same
        // misconfigured head, same blob, ingested.
        RecordingWriter dayWriter = new RecordingWriter();
        BlobPollingService.PollSummary daySummary = serviceSpanningYesterday(dayWriter).runOnce();

        assertThat(daySummary.ingested()).isEqualTo(1);
        assertThat(dayWriter.rows).hasSize(3);
    }

    @Test
    void dayModeListsUnderTheConfiguredDateDirectoryLayout() throws Exception {
        useDateLayout("yyyy-MM-dd");
        Instant now = Instant.now();
        writeBlob("fresh.log.gz", now);

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService.PollSummary summary = service(writer).runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(writer.rows).hasSize(3);
    }

    @Test
    void bandModeMissesBlobFinalizedBeyondLagButFullListingFindsIt() throws Exception {
        Instant now = Instant.now();
        // Start 60m ago, finalized now: a 60m lag, far beyond the 5m budget -> band slice excludes it.
        long startEpoch = now.minus(Duration.ofMinutes(60)).getEpochSecond();
        String key = writeEpochBlob(startEpoch, now.getEpochSecond(), now);

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = bandService(writer, Duration.ofMinutes(5));

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.discovered()).isEqualTo(0);
        assertThat(summary.ingested()).isEqualTo(0);

        // The full-day listing the reconciliation run uses still sees it, so it is recoverable.
        LocalDate day = Instant.ofEpochSecond(startEpoch).atZone(ZoneOffset.UTC).toLocalDate();
        String dayPrefix = PATH_PREFIX + "/" + datePathFormat.format(day) + "/";
        assertThat(blobClient.listWithMetadata(dayPrefix)).extracting(BlobListing::name).contains(key);
    }

    @Test
    void dayModeListsWholeDayRegardlessOfStartEpoch() throws Exception {
        Instant now = Instant.now();
        // A real-format key whose start is old enough that band mode would skip it; day mode lists it.
        long startEpoch = now.minus(Duration.ofMinutes(20)).getEpochSecond();
        writeEpochBlob(startEpoch, now.getEpochSecond(), now);

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = serviceSpanningYesterday(writer); // day mode

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(writer.rows).hasSize(3);
    }

    @Test
    void cycleOfManySmallBlobsBatchesAcrossBlobs() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            writeSingleRecordBlob("b" + i + ".log.gz", now, "p" + i, "t" + i, "s" + i);
        }
        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = service(writer); // day mode, batch size 2

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.ingested()).isEqualTo(5);
        assertThat(summary.rowsInserted()).isEqualTo(5);
        assertThat(writer.rows).hasSize(5);
        // 5 rows at batch size 2 -> ceil(5/2) = 3 inserts, NOT one insert per blob (which would be 5).
        assertThat(writer.insertCalls).isEqualTo(3);
    }

    @Test
    void failedBlobAfterGoodOneRetainsBufferedRows() throws Exception {
        Instant now = Instant.now();
        writeSingleRecordBlob("a-good.log.gz", now, "pg", "tg", "sg"); // sorts first, 1 row buffered
        writeRawBlob("b-corrupt.log.gz", "this is not gzip", now);     // sorts after, fails to parse

        RecordingWriter writer = new RecordingWriter();
        BlobPollingService service = service(writer);

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.ingested()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        // The good blob's row, buffered before the corrupt blob failed, is still flushed at cycle end.
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("pg");
    }

    @Test
    void flushFailureHoldsWatermark() throws Exception {
        Instant now = Instant.now();
        writeBlob("fresh.log.gz", now);

        UsageLogWriter failingWriter = new UsageLogWriter(null, null, null, TestClickHouseSpans.noop(), new SimpleMeterRegistry()) {
            @Override
            public void insert(String table, List<Map<String, Object>> batch) {
                throw new IllegalStateException("ClickHouse unavailable");
            }
        };
        BlobPollingService service = service(blobClient, failingWriter);
        Instant before = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(service.watermark()).isEqualTo(before);
    }

    /**
     * A disk-full refusal is handled exactly like any other flush failure — the watermark holds and the blobs
     * stay uncommitted, which is what lets ingest resume unattended once space returns. Asserted separately
     * because the classification added a branch here, and the branch must not change the outcome.
     */
    @Test
    void storageFullFlushFailureAlsoHoldsWatermark() throws Exception {
        Instant now = Instant.now();
        writeBlob("fresh.log.gz", now);

        UsageLogWriter fullDiskWriter = new UsageLogWriter(
                null, null, null, TestClickHouseSpans.noop(), new SimpleMeterRegistry()) {
            @Override
            public void insert(String table, List<Map<String, Object>> batch) {
                throw new ClickHouseStorageFullException(
                        "ClickHouse refused the insert into " + table + ": the disk is full",
                        new ServerException(243, "not enough space", 500, "query-1"));
            }
        };
        BlobPollingService service = service(blobClient, fullDiskWriter);
        Instant before = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(summary.rowsInserted()).isZero();
        assertThat(service.watermark()).isEqualTo(before);
    }

    /**
     * A failed catalog read behind the cycle's sink is a failed cycle like any other: it does not escape, the
     * watermark holds, and the cycle's outcome and duration are still recorded.
     */
    @Test
    void catalogReadFailureIsRecordedAsFailedCycle() throws Exception {
        writeBlob("fresh.log.gz", Instant.now());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestFlushOrder failing = tables -> {
            throw new DataAccessResourceFailureException("catalog unreachable");
        };
        BlobPollingService service = service(blobClient, new RecordingWriter(), failing, registry);
        Instant initialWatermark = service.watermark();

        BlobPollingService.PollSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(summary.rowsInserted()).isZero();
        assertThat(service.watermark()).isEqualTo(initialWatermark);
        assertThat(registry.counter("poll.cycles", "outcome", "failure").count()).isEqualTo(1);
        assertThat(registry.timer("poll.duration").count()).isEqualTo(1);
    }

    private BlobPollingService service(UsageLogWriter writer) {
        return service(blobClient, writer);
    }

    private BlobPollingService service(BlobClient client, UsageLogWriter writer) {
        return service(client, writer, IngestFlushOrder.NONE, new SimpleMeterRegistry());
    }

    private BlobPollingService service(BlobClient client, UsageLogWriter writer, IngestFlushOrder flushOrder, MeterRegistry registry) {
        // overlap 1m; restart-lookback 10m so the watermark starts 10m before "now"; previous-day grace
        // 0 so only the current-day prefix is scanned (deterministic regardless of wall-clock time);
        // day listing mode (the band-mode cases construct their own properties).
        PollProperties pollProps = new PollProperties(
                true, "-", Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ZERO,
                Duration.ofMinutes(10), "day");
        BlobIngestor ingestor = new BlobIngestor(client, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, flushOrder);
        return new BlobPollingService(client, ingestor, writer, syncProps, pollProps, new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()),
                registry, TestClientSpanTracer.noop(), ledger);
    }

    /**
     * Day mode that also lists the previous day. For a fixture keyed by a start epoch in the past: that
     * epoch picks the key's date directory, so in the first minutes after UTC midnight the blob lands under
     * yesterday, and a grace of 0 — which lists only today — would miss it depending on the wall clock.
     */
    private BlobPollingService serviceSpanningYesterday(UsageLogWriter writer) {
        PollProperties pollProps = new PollProperties(
                true, "-", Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofDays(1),
                Duration.ofMinutes(10), "day");
        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        return new BlobPollingService(blobClient, ingestor, writer, syncProps, pollProps, new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger);
    }

    private BlobPollingService bandService(UsageLogWriter writer, Duration finalizationLag) {
        // band listing mode; overlap 1m, restart-lookback 10m (initial watermark = now - 10m).
        PollProperties pollProps = new PollProperties(
                true, "-", Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ZERO,
                finalizationLag, "band");
        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        return new BlobPollingService(blobClient, ingestor, writer, syncProps, pollProps, new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger);
    }

    /**
     * Points both the fixtures and the service under test at one date-directory layout, so a test that
     * varies it cannot end up writing blobs where the resolver will not look.
     */
    private void useDateLayout(String pattern) {
        useKeyLayout(pattern, DEFAULT_HEAD);
    }

    /**
     * As {@link #useDateLayout(String)}, but also varies the file-name head bounded listing filters on —
     * the two layouts have to move together for a band prefix to reach a fixture at all.
     */
    private void useKeyLayout(String datePattern, String fileNamePrefixPattern) {
        datePathFormat = DateTimeFormatter.ofPattern(datePattern);
        datePath = datePathFormat.format(LocalDate.now(ZoneOffset.UTC));
        syncProps = new SyncProperties(
                "-", 0, 2, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, datePattern, fileNamePrefixPattern, false,
                List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")), null);
    }

    private void writeBlob(String fileName, Instant lastModified) throws Exception {
        Path blobFile = blobPath(fileName);
        try (OutputStream out = Files.newOutputStream(blobFile);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(NDJSON.getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(blobFile, FileTime.from(lastModified));
    }

    private void writeRawBlob(String fileName, String content, Instant lastModified) throws Exception {
        Path blobFile = blobPath(fileName);
        Files.writeString(blobFile, content);
        Files.setLastModifiedTime(blobFile, FileTime.from(lastModified));
    }

    private Path blobPath(String fileName) throws Exception {
        Path blobFile = containerDir.resolve(PATH_PREFIX).resolve(datePath).resolve(fileName);
        Files.createDirectories(blobFile.getParent());
        return blobFile;
    }

    /**
     * Writes a blob using the epoch-leading key format {@code <epoch>-<uuid>.log.gz} — the same fixed-width epoch
     * seconds bounded listing filters on, but with no {@code date=} head in front of it — under the
     * directory derived from that epoch's UTC date, and returns its full key.
     */
    private String writeEpochLeadingBlob(long epoch, Instant lastModified) throws Exception {
        LocalDate day = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate();
        String dir = datePathFormat.format(day);
        String name = epoch + "-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.log.gz";
        Path blobFile = containerDir.resolve(PATH_PREFIX).resolve(dir).resolve(name);
        Files.createDirectories(blobFile.getParent());
        try (OutputStream out = Files.newOutputStream(blobFile);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(NDJSON.getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(blobFile, FileTime.from(lastModified));
        return PATH_PREFIX + "/" + dir + "/" + name;
    }

    /**
     * Writes a blob using the real DIAL key format {@code date=<YYYY-MM-DD><startEpoch><endEpoch>-<uuid>}
     * under the directory derived from the start epoch's UTC date, and returns its full key.
     */
    private String writeEpochBlob(long startEpoch, long endEpoch, Instant lastModified) throws Exception {
        LocalDate day = Instant.ofEpochSecond(startEpoch).atZone(ZoneOffset.UTC).toLocalDate();
        String dir = datePathFormat.format(day);
        String dateField = DateTimeFormatter.ISO_LOCAL_DATE.format(day);
        String name = "date=" + dateField + startEpoch + endEpoch
                + "-bbbbbbbb-cccc-dddd-eeee-ffffffffffff.log.gz";
        Path blobFile = containerDir.resolve(PATH_PREFIX).resolve(dir).resolve(name);
        Files.createDirectories(blobFile.getParent());
        try (OutputStream out = Files.newOutputStream(blobFile);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(NDJSON.getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(blobFile, FileTime.from(lastModified));
        return PATH_PREFIX + "/" + dir + "/" + name;
    }

    private void writeSingleRecordBlob(
            String fileName, Instant lastModified, String project, String trace, String span)
            throws Exception {
        String json = "{\"apiType\":\"DialOpenAI\",\"project\":{\"id\":\"" + project
                + "\"},\"request\":{\"time\":\"2026-06-18T00:00:01.000\"}"
                + ",\"token_usage\":{\"total_tokens\":1},\"trace\":{\"trace_id\":\"" + trace
                + "\",\"core_span_id\":\"" + span + "\"}}\n";
        Path blobFile = blobPath(fileName);
        try (OutputStream out = Files.newOutputStream(blobFile);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(blobFile, FileTime.from(lastModified));
    }

    private static org.assertj.core.data.TemporalUnitOffset within(Duration duration) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                duration.toSeconds(), java.time.temporal.ChronoUnit.SECONDS);
    }
}
