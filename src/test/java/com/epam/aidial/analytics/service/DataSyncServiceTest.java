package com.epam.aidial.analytics.service;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.clickhouse.TestClickHouseSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.BlobProperties;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link DataSyncService} end-to-end against the jclouds <em>filesystem</em> provider and an
 * in-memory writer stub: prefix listing, malformed-line skipping, batching, and dedup on re-run.
 */
class DataSyncServiceTest {

    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "logs";
    private static final String DEFAULT_HEAD = "'date='yyyy-MM-dd";
    private static final String MISMATCH_METER = "sync.blobs.band.shape.mismatch";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RecordingWriter writer;
    private final InMemoryIngestLedger ledger = new InMemoryIngestLedger();
    private DataSyncService service;
    private String blobKey;
    // Retained so a test can rebuild the service around a different writer (see serviceWritingWith).
    private BlobClient blobClient;
    private BlobIngestor ingestor;
    private List<SyncTarget> targets;
    private SyncProperties syncProps;
    // Retained so a test can write a second fixture under a different date-directory layout.
    private Path baseDir;

    @BeforeEach
    void setUp(@TempDir Path baseDir) throws Exception {
        this.baseDir = baseDir;
        // Today (UTC) so that day-offset 0 resolves to the directory we create below.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String datePath = DateTimeFormatter.ofPattern("yyyy/MM/dd").format(today);
        blobKey = PATH_PREFIX + "/" + datePath + "/data.log.gz";

        // filesystem provider: <baseDir>/<container>/<key>
        Path blobFile = baseDir.resolve(BUCKET).resolve(blobKey);
        Files.createDirectories(blobFile.getParent());
        writeGzip(blobFile, """
                {"project":{"id":"p1"},"request":{"uri":"/chat/completions","time":"2026-06-18T00:00:01"},"response":{"status":200},"trace":{"trace_id":"t1","core_span_id":"s1"}}
                {"project":{"id":"p2"},"request":{"uri":"/embeddings","time":"2026-06-18T00:00:02"},"response":{"status":500},"trace":{"trace_id":"t2","core_span_id":"s2"}}
                not-json
                {"project":{"id":"p3"},"request":{"time":"2026-06-18T00:00:03"},"token_usage":{"total_tokens":3},"trace":{"trace_id":"t3","core_span_id":"s3"}}
                {"project":{"id":"p4"},"token_usage":{"total_tokens":4}}
                {"project":{"id":"p5"},"request":{"uri":"/chat/completions"},"response":{"status":200},"trace":{"trace_id":"t5","core_span_id":"s5"}}
                """);

        BlobProperties blobProps = new BlobProperties(
                "filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString()));
        syncProps = new SyncProperties(
                "-", 0, 2, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, "yyyy/MM/dd", "'date='yyyy-MM-dd", false,
                List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")), null);

        TransformRegistry registry = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig mappingConfig = new MappingFileLoader(new DefaultResourceLoader(), registry, derivers)
                .load("classpath:mapping/dial-usage-log.yaml");
        MappingEngine mappingEngine = new MappingEngine(mappingConfig, registry, derivers);

        blobClient = new BlobClient(blobProps, TestBlobSpans.noop());
        writer = new RecordingWriter();
        targets = List.of(new SyncTarget("dial_usage_log", mappingConfig, mappingEngine));
        ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        service = new DataSyncService(blobClient, ingestor, writer, syncProps, new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger);
    }

    @Test
    void ingestsKeyedRowsSkipsMalformedDropsKeylessAndBatches() {
        DataSyncService.RunSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.filesProcessed()).isEqualTo(1);
        assertThat(summary.rowsInserted()).isEqualTo(3);
        assertThat(summary.rowsSkipped()).isEqualTo(1);   // malformed line
        // p4 lacks trace_id/core_span_id; p5 lacks request.time — both are incomplete-key drops.
        assertThat(summary.rowsDropped()).isEqualTo(2);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1", "p2", "p3");
        assertThat(writer.rows).allMatch(row -> blobKey.equals(row.get("_source_file")));
        // Calculated columns are produced at ingest from the raw record.
        assertThat(writer.rows).allMatch(row -> row.get("event_id") instanceof String id && !id.isBlank());
        assertThat(writer.rows.get(0)).containsEntry("event_kind", "llm_call").containsEntry("success", true);
        assertThat(writer.rows.get(1)).containsEntry("event_kind", "embedding").containsEntry("success", false);
        // batch-size 2 over 3 keyed rows -> two insert calls (2 + 1)
        assertThat(writer.insertCalls).isEqualTo(2);
    }

    @Test
    void secondRunOverTheSameDayInsertsNothingAndCountsTheSkip() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DataSyncService counted = new DataSyncService(blobClient, ingestor, writer, syncProps,
                new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()), registry, TestClientSpanTracer.noop(), ledger);
        counted.runOnce();
        int callsAfterFirst = writer.insertCalls;

        DataSyncService.RunSummary second = counted.runOnce();

        assertThat(second.success()).isTrue();
        assertThat(second.filesProcessed()).isZero();
        assertThat(second.blobsSkipped()).isEqualTo(1);
        assertThat(second.rowsInserted()).isZero();
        assertThat(writer.insertCalls).isEqualTo(callsAfterFirst);
        assertThat(registry.counter("sync.blobs.skipped", "reason", "ledger").count()).isEqualTo(1);
    }

    /** Rows batch across blobs, so the blob is recorded by the flush that writes its last row — once, after it. */
    @Test
    void blobIsRecordedOnceItsLastRowIsWritten() {
        DataSyncService.RunSummary summary = service.runOnce();

        assertThat(summary.success()).isTrue();
        // batch-size 2 over 3 rows: the first flush leaves a row buffered, so only the final flush records the blob.
        assertThat(ledger.recordBatches).containsExactly(List.of(blobKey));
        assertThat(ledger.entries).containsKey(blobKey);
    }

    /**
     * A day that fails after some writes keeps what it finished: the first blob's last row went out with the
     * second blob's first, so it is recorded; the second blob's last row was in the failed batch, so it is not.
     */
    @Test
    void failedRunRecordsExactlyTheBlobsWhoseRowsWereAllWritten() throws Exception {
        String secondKey = writeDayFixture("data2.log.gz", "q1", "q2");
        writer.failOnInsertCall = 3;

        DataSyncService.RunSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(ledger.recordedKeys()).containsExactly(blobKey);

        // A backfill of the day afterwards ingests only the unrecorded blob.
        writer.failOnInsertCall = 0;
        writer.rows.clear();
        DataSyncService.RunSummary retry = service.runForDate(LocalDate.now(ZoneOffset.UTC));
        assertThat(retry.filesProcessed()).isEqualTo(1);
        assertThat(retry.blobsSkipped()).isEqualTo(1);
        assertThat(writer.rows).allMatch(row -> secondKey.equals(row.get("_source_file")));
        assertThat(ledger.recordedKeys()).containsExactly(blobKey, secondKey);
    }

    @Test
    void rewrittenBlobIsIngestedAgainAndItsEntrySuperseded() throws Exception {
        service.runOnce();
        Instant rewritten = Instant.parse("2030-01-01T00:00:00.123Z");
        Files.setLastModifiedTime(baseDir.resolve(BUCKET).resolve(blobKey), FileTime.from(rewritten));

        DataSyncService.RunSummary second = service.runOnce();

        assertThat(second.filesProcessed()).isEqualTo(1);
        assertThat(second.blobsSkipped()).isZero();
        assertThat(ledger.entries.get(blobKey)).isEqualTo(blobClient.listWithMetadata(blobKey).getFirst().lastModified()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void ingestAllReingestsRecordedBlobsAndRefreshesTheirEntries() {
        service.runOnce();
        int rowsAfterFirst = writer.rows.size();

        DataSyncService.RunSummary all = service.runForDate(
                LocalDate.now(ZoneOffset.UTC), IngestLedger.Mode.INGEST_ALL, DataSyncService.IngestProgressListener.NO_OP);

        assertThat(all.filesProcessed()).isEqualTo(1);
        assertThat(all.blobsSkipped()).isZero();
        assertThat(writer.rows).hasSize(rowsAfterFirst * 2);
        assertThat(ledger.recordBatches).containsExactly(List.of(blobKey), List.of(blobKey));
    }

    /** The ledger does not track configuration: a new deployment, with whatever mapping, skips recorded blobs. */
    @Test
    void newDeploymentDoesNotReingestRecordedBlobs() {
        service.runOnce();
        BlobIngestor redeployed = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        DataSyncService next = new DataSyncService(blobClient, redeployed, writer, syncProps,
                new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()), new SimpleMeterRegistry(), TestClientSpanTracer.noop(),
                ledger);

        assertThat(next.runOnce().filesProcessed()).isZero();
    }

    @Test
    void ledgerReadFailureFailsTheDayWithoutWriting() {
        ledger.failReads = true;

        DataSyncService.RunSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(summary.storageFull()).isFalse();
        assertThat(writer.rows).isEmpty();
    }

    @Test
    void ledgerWriteFailureFailsTheDay() {
        ledger.failWrites = true;

        DataSyncService.RunSummary summary = service.runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(ledger.entries).isEmpty();
    }

    /** Writes a blob of one keyed record per project under today's default-layout directory; returns its key. */
    private String writeDayFixture(String fileName, String... projects) throws Exception {
        String key = blobKey.substring(0, blobKey.lastIndexOf('/') + 1) + fileName;
        Path file = baseDir.resolve(BUCKET).resolve(key);
        StringBuilder content = new StringBuilder();
        for (String project : projects) {
            content.append("{\"project\":{\"id\":\"").append(project).append("\"},\"request\":{\"time\":\"2026-06-18T00:00:01\"},")
                    .append("\"trace\":{\"trace_id\":\"").append(project).append("\",\"core_span_id\":\"s1\"}}\n");
        }
        writeGzip(file, content.toString());
        return key;
    }

    /**
     * A run whose flush is refused for lack of disk space reports it as such, so the day-iterating caller (the
     * backfill) can stop instead of attempting every remaining day against a store that cannot accept writes.
     * An ordinary flush failure must stay unflagged, or the backfill would abandon a range over one bad day.
     */
    @Test
    void reportsStorageExhaustionSeparatelyFromAnOrdinaryFlushFailure() {
        DataSyncService diskFull = serviceWritingWith(new IllegalStateException("wrapped",
                new ClickHouseStorageFullException("out of space",
                        new ServerException(243, "not enough space", 500, "q"))));
        DataSyncService otherFailure = serviceWritingWith(new IllegalStateException("ClickHouse insert failed"));

        DataSyncService.RunSummary full = diskFull.runOnce();
        DataSyncService.RunSummary other = otherFailure.runOnce();

        assertThat(full.success()).isFalse();
        assertThat(full.storageFull()).isTrue();
        assertThat(other.success()).isFalse();
        assertThat(other.storageFull()).isFalse();
    }

    /**
     * A failure preparing the day — here the catalog read behind the run sink — fails that day like any other
     * rather than escaping: the backfill iterating a range depends on the never-throws contract to isolate it.
     */
    @Test
    void catalogReadFailureFailsTheDayWithoutThrowing() {
        DataSyncService.RunSummary summary = serviceWithFailingCatalog(new SimpleMeterRegistry())
                .runForDate(LocalDate.now(ZoneOffset.UTC));

        assertThat(summary.success()).isFalse();
        assertThat(summary.storageFull()).isFalse();
        assertThat(summary.rowsInserted()).isZero();
        assertThat(summary.rowsInsertedByTable()).isEmpty();
        assertThat(writer.rows).isEmpty();
    }

    /** The scheduled run records its failed outcome when the day fails while preparing. */
    @Test
    void catalogReadFailureRecordsScheduledRunFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        DataSyncService.RunSummary summary = serviceWithFailingCatalog(registry).runOnce();

        assertThat(summary.success()).isFalse();
        assertThat(registry.counter("sync.runs", "outcome", "failure").count()).isEqualTo(1);
    }

    /** A service whose ingestor's catalog read (the flush order behind every run sink) fails. */
    private DataSyncService serviceWithFailingCatalog(SimpleMeterRegistry registry) {
        IngestFlushOrder failing = tables -> {
            throw new DataAccessResourceFailureException("catalog unreachable");
        };
        BlobIngestor failingIngestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, failing);
        return new DataSyncService(blobClient, failingIngestor, writer, syncProps,
                new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()), registry, TestClientSpanTracer.noop(), ledger);
    }

    @Test
    void ingestsUnderSingleSegmentDateDirectory() throws Exception {
        // A flat date-directory layout: one `yyyy-MM-dd` directory where the default writes three segments. The blob
        // written by setUp stays where it is, so this also asserts the flat prefix does not list it.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String flatKey = PATH_PREFIX + "/" + DateTimeFormatter.ISO_LOCAL_DATE.format(today) + "/data.log.gz";
        Path flatFile = baseDir.resolve(BUCKET).resolve(flatKey);
        Files.createDirectories(flatFile.getParent());
        writeGzip(flatFile, """
                {"project":{"id":"fl1"},"request":{"uri":"/chat/completions","time":"2026-06-18T00:00:01"},"response":{"status":200},"trace":{"trace_id":"u1","core_span_id":"s1"}}
                """);

        SyncProperties flatProps = new SyncProperties(
                "-", 0, 2, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, "yyyy-MM-dd", "'date='yyyy-MM-dd", false, syncProps.targets(), null);
        DataSyncService flatService = new DataSyncService(
                blobClient, ingestor, writer, flatProps, new BlobPrefixResolver(flatProps, new SimpleMeterRegistry()),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger);

        DataSyncService.RunSummary summary = flatService.runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.filesProcessed()).isEqualTo(1);
        assertThat(summary.rowsInserted()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("_source_file")).containsExactly(flatKey);
    }

    @Test
    void reportsFileNamesBoundedListingCannotMatch() throws Exception {
        // This path lists whole days, so it is the only place a head that band listing cannot match shows
        // up at all: band mode's own listing would simply be empty. Reporting only — both blobs still
        // ingest, because a mismatch narrows discovery rather than corrupting anything.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String iso = DateTimeFormatter.ISO_LOCAL_DATE.format(today);
        // A single-segment layout, so the listing contains only the two fixtures written here.
        writeFlatFixture(iso, "date=" + iso + "17881694461788170046-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.log.gz", "m1");
        writeFlatFixture(iso, "1788169446-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.log.gz", "m2");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DataSyncService.RunSummary summary = flatServiceReporting(DEFAULT_HEAD, registry).runOnce();

        assertThat(summary.success()).isTrue();
        assertThat(summary.filesProcessed()).isEqualTo(2);
        assertThat(summary.rowsInserted()).isEqualTo(2);
        assertThat(registry.counter(MISMATCH_METER).count()).isEqualTo(1);
    }

    @Test
    void reportsNothingWhenEveryFileNameMatchesTheHead() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String iso = DateTimeFormatter.ISO_LOCAL_DATE.format(today);
        writeFlatFixture(iso, "1788169446-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.log.gz", "u1");
        writeFlatFixture(iso, "1788170046-1c1b8b4e-3c2a-4a9f-9a1e-1d2c3b4a5e6f.log.gz", "u2");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // The head the producer of those keys actually needs: blank, the epoch begins the file name.
        DataSyncService.RunSummary summary = flatServiceReporting("", registry).runOnce();

        assertThat(summary.filesProcessed()).isEqualTo(2);
        assertThat(registry.counter(MISMATCH_METER).count()).isZero();
    }

    /** Writes one single-record blob under the single-segment date directory {@code iso}. */
    private void writeFlatFixture(String iso, String fileName, String project) throws Exception {
        Path file = baseDir.resolve(BUCKET).resolve(PATH_PREFIX + "/" + iso + "/" + fileName);
        Files.createDirectories(file.getParent());
        writeGzip(file, "{\"project\":{\"id\":\"" + project + "\"},\"request\":{\"time\":\"2026-06-18T00:00:01\"},"
                + "\"trace\":{\"trace_id\":\"" + project + "\",\"core_span_id\":\"s1\"}}\n");
    }

    /**
     * A service on the single-segment date layout — so it lists only the fixtures a test writes there —
     * with the given file-name head, and a registry the test can read the mismatch counter from.
     */
    private DataSyncService flatServiceReporting(String head, SimpleMeterRegistry registry) {
        SyncProperties props = new SyncProperties(
                "-", 0, 2, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, "yyyy-MM-dd", head, false, syncProps.targets(), null);
        return new DataSyncService(blobClient, ingestor, writer, props, new BlobPrefixResolver(props, registry),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger);
    }

    /** A service whose writer always fails the flush with {@code failure}, reusing this test's blob fixture. */
    private DataSyncService serviceWritingWith(RuntimeException failure) {
        UsageLogWriter failing = new UsageLogWriter(
                null, null, null, TestClickHouseSpans.noop(), new SimpleMeterRegistry()) {
            @Override
            public void insert(String table, List<Map<String, Object>> batch) {
                throw failure;
            }
        };
        return new DataSyncService(
                blobClient, ingestor, failing, syncProps, new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger);
    }

    private static void writeGzip(Path file, String content) throws Exception {
        try (OutputStream out = Files.newOutputStream(file);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
