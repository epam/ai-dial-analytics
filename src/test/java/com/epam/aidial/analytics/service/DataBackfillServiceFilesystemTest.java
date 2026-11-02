package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.configuration.BackfillProperties;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.exception.BackfillInProgressException;
import com.epam.aidial.analytics.observability.TestClientSpanTracer;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end backfill over the jclouds <em>filesystem</em> provider and an in-memory writer: a
 * two-day range is ingested day-by-day through the real {@link DataSyncService} pipeline, confirming
 * rows land for every day in the range and the run reports completion.
 */
class DataBackfillServiceFilesystemTest {

    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "logs";
    private static final String DEFAULT_DATE_PATTERN = "yyyy/MM/dd";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RecordingWriter writer;
    private final InMemoryIngestLedger ledger = new InMemoryIngestLedger();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private DataBackfillService backfillService;
    private LocalDate dayOne;
    private LocalDate dayTwo;
    // Retained so a test can write fixtures under a different date-directory layout and re-wire.
    private Path baseDir;

    @BeforeEach
    void setUp(@TempDir Path baseDir) throws Exception {
        this.baseDir = baseDir;
        // Two recent UTC dates so the range is valid (not in the future).
        dayTwo = LocalDate.now(ZoneOffset.UTC);
        dayOne = dayTwo.minusDays(1);

        writeDay(baseDir, DEFAULT_DATE_PATTERN, dayOne, "a1", "a2");
        writeDay(baseDir, DEFAULT_DATE_PATTERN, dayTwo, "b1", "b2");
        wire(DEFAULT_DATE_PATTERN);
    }

    /** Wires the backfill over the temp-dir blob store for one date-directory layout. */
    private void wire(String datePathPattern) {
        if (backfillService != null) {
            backfillService.shutdown();
        }
        BlobProperties blobProps = new BlobProperties(
                "filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString()));
        SyncProperties syncProps = new SyncProperties(
                "-", 0, 10, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, datePathPattern, "'date='yyyy-MM-dd", false,
                List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")), null, null, null);

        TransformRegistry registry = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig mappingConfig = new MappingFileLoader(new DefaultResourceLoader(), registry, derivers)
                .load("classpath:mapping/dial-usage-log.yaml");
        MappingEngine mappingEngine = new MappingEngine(mappingConfig, registry, derivers);

        BlobClient blobClient = new BlobClient(blobProps, TestBlobSpans.noop());
        writer = new RecordingWriter();
        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES),
                List.of(new SyncTarget("dial_usage_log", mappingConfig, mappingEngine)), IngestFlushOrder.NONE);
        DataSyncService syncService = new DataSyncService(blobClient, ingestor, writer, syncProps, new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger);
        backfillService = new DataBackfillService(syncService, new BackfillProperties(true, 366), metrics);
    }

    @AfterEach
    void tearDown() {
        if (backfillService != null) {
            backfillService.shutdown();
        }
    }

    @Test
    void backfillsEveryDayInRange() {
        BackfillRun run = backfillService.start(dayOne.toString(), dayTwo.toString());

        await().untilAsserted(() ->
                assertThat(backfillService.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));

        BackfillRun.Snapshot status = backfillService.status(run.runId());
        assertThat(status.daysTotal()).isEqualTo(2);
        assertThat(status.daysCompleted()).isEqualTo(2);
        assertThat(status.daysFailed()).isZero();
        assertThat(status.rowsInserted()).isEqualTo(4); // 2 days * 2 keyed records
        // Rows from both days landed in the writer.
        assertThat(writer.rows).extracting(row -> row.get("project_id"))
                .containsExactlyInAnyOrder("a1", "a2", "b1", "b2");
    }

    @Test
    void backfillsUnderTheConfiguredDateDirectoryLayout() throws Exception {
        // A flat date-directory layout: one `yyyy-MM-dd` directory. The backfill carries no layout setting of its own —
        // it discovers through DataSyncService.runForDate — so configuring the scheduled path configures
        // this one too, which is exactly what this asserts.
        writeDay(baseDir, "yyyy-MM-dd", dayOne, "u1", "u2");
        writeDay(baseDir, "yyyy-MM-dd", dayTwo, "u3", "u4");
        wire("yyyy-MM-dd");

        BackfillRun run = backfillService.start(dayOne.toString(), dayTwo.toString());

        await().untilAsserted(() ->
                assertThat(backfillService.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));

        BackfillRun.Snapshot status = backfillService.status(run.runId());
        assertThat(status.daysCompleted()).isEqualTo(2);
        assertThat(status.daysFailed()).isZero();
        assertThat(status.rowsInserted()).isEqualTo(4);
        // Only the flat-layout fixtures: the three-segment ones written by setUp are not under this prefix.
        assertThat(writer.rows).extracting(row -> row.get("project_id"))
                .containsExactlyInAnyOrder("u1", "u2", "u3", "u4");
    }

    @Test
    void defaultBackfillOverIngestedDaysSkipsTheirBlobs() {
        runToCompletion(false);
        writer.rows.clear();

        BackfillRun again = runToCompletion(false);

        BackfillRun.Snapshot status = backfillService.status(again.runId());
        assertThat(status.daysCompleted()).isEqualTo(2);
        assertThat(status.filesProcessed()).isZero();
        assertThat(status.rowsInserted()).isZero();
        assertThat(writer.rows).isEmpty();
        assertThat(metrics.counter("backfill.blobs.skipped", "reason", "ledger").count()).isEqualTo(2);
    }

    /** A re-trigger after a run that finished only part of the range writes only the rest. */
    @Test
    void retriggerIngestsOnlyWhatIsNotYetRecorded() {
        // Day one was ingested earlier (by the scheduled run, or a run that stopped before day two).
        ledger.record(List.of(new com.epam.aidial.analytics.blob.BlobListing(dayKey(dayOne),
                lastModified(dayKey(dayOne)))));

        BackfillRun run = runToCompletion(false);

        assertThat(backfillService.status(run.runId()).filesProcessed()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactlyInAnyOrder("b1", "b2");
    }

    @Test
    void includeIngestedReingestsTheWholeRange() {
        runToCompletion(false);
        writer.rows.clear();

        BackfillRun again = runToCompletion(true);

        assertThat(backfillService.status(again.runId()).filesProcessed()).isEqualTo(2);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactlyInAnyOrder("a1", "a2", "b1", "b2");
    }

    /**
     * Starts a backfill of both days and waits for it to complete. A run frees its slot just after reporting
     * completion, so a start right behind the previous run may still see it in progress; that is retried.
     */
    private BackfillRun runToCompletion(boolean includeIngested) {
        BackfillRun run = await().ignoreException(BackfillInProgressException.class)
                .until(() -> backfillService.start(dayOne.toString(), dayTwo.toString(), includeIngested), Objects::nonNull);
        await().untilAsserted(() ->
                assertThat(backfillService.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        return run;
    }

    private static String dayKey(LocalDate date) {
        return PATH_PREFIX + "/" + DateTimeFormatter.ofPattern(DEFAULT_DATE_PATTERN).format(date) + "/data.log.gz";
    }

    private java.time.Instant lastModified(String key) {
        try {
            return Files.getLastModifiedTime(baseDir.resolve(BUCKET).resolve(key)).toInstant();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static void writeDay(Path baseDir, String datePathPattern, LocalDate date, String p1, String p2) throws Exception {
        String key = PATH_PREFIX + "/" + DateTimeFormatter.ofPattern(datePathPattern).format(date) + "/data.log.gz";
        Path blobFile = baseDir.resolve(BUCKET).resolve(key);
        Files.createDirectories(blobFile.getParent());
        String content = record(p1, "t-" + p1) + "\n" + record(p2, "t-" + p2) + "\n";
        try (OutputStream out = Files.newOutputStream(blobFile);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String record(String projectId, String traceId) {
        return "{\"project\":{\"id\":\"" + projectId + "\"},"
                + "\"request\":{\"uri\":\"/chat/completions\",\"time\":\"2026-06-18T00:00:01\"},"
                + "\"response\":{\"status\":200},"
                + "\"trace\":{\"trace_id\":\"" + traceId + "\",\"core_span_id\":\"s-" + projectId + "\"}}";
    }
}
