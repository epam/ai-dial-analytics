package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.configuration.SyncRetryProperties;
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
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The retry sweep over a filesystem blob store and in-memory ledgers. */
class IngestRetrySweepTest {

    private static final String BUCKET = "logs";
    private static final String PATH_PREFIX = "sweep";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final InMemoryIngestLedger ledger = new InMemoryIngestLedger();
    private final InMemoryIngestFailureLedger failureLedger = new InMemoryIngestFailureLedger();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IngestFailureServiceTest.MutableClock clock =
            new IngestFailureServiceTest.MutableClock(Instant.now().minus(Duration.ofHours(1)));

    private Path baseDir;
    private String dayDir;
    private BlobClient blobClient;
    private BlobIngestor ingestor;
    private RecordingWriter writer;
    private SyncProperties syncProps;
    private IngestFailureService failures;
    private DataSyncService dataSyncService;

    @BeforeEach
    void setUp(@TempDir Path baseDir) {
        this.baseDir = baseDir;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        dayDir = PATH_PREFIX + "/" + DateTimeFormatter.ofPattern("yyyy/MM/dd").format(today) + "/";
        BlobProperties blobProps = new BlobProperties("filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString()));
        syncProps = new SyncProperties(
                "-", 0, 100, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, PATH_PREFIX, "yyyy/MM/dd", "'date='yyyy-MM-dd", false,
                List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")), null, null, null);
        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingConfig mapping = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers).load("classpath:mapping/dial-usage-log.yaml");
        List<SyncTarget> targets = List.of(new SyncTarget("dial_usage_log", mapping, new MappingEngine(mapping, transforms, derivers)));
        blobClient = new BlobClient(blobProps, TestBlobSpans.noop());
        ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        writer = new RecordingWriter();
        failures = failureLedger.service(ledger, registry, clock);
        dataSyncService = new DataSyncService(blobClient, ingestor, writer, syncProps, new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()),
                new SimpleMeterRegistry(), TestClientSpanTracer.noop(), ledger, failures);
    }

    @Test
    void transientBlobFailureIsIngestedAndResolvedOnceDue() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        failures.recordBlobFailure(blob, IngestPath.POLL, new IOException("connection reset"));
        clock.advance(Duration.ofMinutes(16));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1");
        assertThat(ledger.recordedKeys()).containsExactly(blob.name());
        assertThat(failureLedger.of(IngestFailureKind.BLOB, blob.name())).extracting(IngestFailureEvent::type)
                .containsExactly(IngestFailureEvent.Type.FAILED, IngestFailureEvent.Type.RESOLVED);
        assertThat(failures.outstanding()).isEmpty();
        assertThat(registry.counter("sync.retry.attempts", "kind", "blob", "outcome", "success").count()).isEqualTo(1);
    }

    /** An ingest-ledger entry written before the failure says nothing about the failed attempt: the blob is read again. */
    @Test
    void earlierIngestOfTheSameVersionDoesNotResolveWithoutReading() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        ledger.clock = clock::instant;
        ledger.record(List.of(blob));
        clock.advance(Duration.ofSeconds(1));
        failures.recordBlobFailure(blob, IngestPath.BACKFILL, new RecordMappingException("dial_usage_log", new IllegalStateException("x")));
        clock.advance(Duration.ofSeconds(1));
        assertThat(failures.outstanding()).singleElement().extracting(IngestFailureEntry::state).isEqualTo(IngestFailureState.PARKED);
        failures.requeue(IngestFailureFilter.ALL);
        clock.advance(Duration.ofMinutes(16));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1");
        assertThat(failures.outstanding()).isEmpty();
    }

    /**
     * A write failure is the failed attempt of the blob being written, each in its own sink, so it backs off and parks
     * rather than aborting the sweep unrecorded and failing every later sweep ahead of the other due subjects.
     */
    @Test
    void writeFailureIsRecordedAsEachBlobsAttempt() throws Exception {
        BlobListing older = writeBlob("a.log.gz", "p1");
        BlobListing newer = writeBlob("b.log.gz", "p2");
        failures.recordBlobFailure(older, IngestPath.POLL, new IOException("reset"));
        clock.advance(Duration.ofSeconds(1));
        failures.recordBlobFailure(newer, IngestPath.POLL, new IOException("reset"));
        clock.advance(Duration.ofMinutes(16));
        writer.failOnInsertCall = 1;

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.attempted()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(2);
        assertThat(ledger.recordedKeys()).isEmpty();
        assertThat(failures.outstanding()).hasSize(2).allSatisfy(entry -> assertThat(entry.failure().attempts()).isEqualTo(2));
        assertThat(registry.counter("sync.retry.attempts", "kind", "blob", "outcome", "failure").count()).isEqualTo(2);
    }

    @Test
    void blobNotYetDueIsLeftAlone() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        failures.recordBlobFailure(blob, IngestPath.POLL, new IOException("connection reset"));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.attempted()).isZero();
        assertThat(writer.rows).isEmpty();
    }

    @Test
    void blobTheStoreNoLongerListsIsParked() throws Exception {
        // Another blob keeps the day's directory, which the filesystem store needs to list it at all.
        writeBlob("other.log.gz", "p1");
        failures.recordBlobFailure(new BlobListing(dayDir + "gone.log.gz", Instant.now().minusSeconds(3600)), IngestPath.POLL,
                new IOException("reset"));
        clock.advance(Duration.ofMinutes(16));

        sweep(retry(true, 20)).runOnce();

        assertThat(failures.outstanding()).singleElement().satisfies(entry -> {
            assertThat(entry.state()).isEqualTo(IngestFailureState.PARKED);
            assertThat(entry.failure().reason()).isEqualTo("blob_missing");
        });
    }

    /** A sweep that cannot list the blob keeps the version and day that failed, rather than recording none. */
    @Test
    void listingFailureKeepsTheFailedVersion() {
        Instant failedVersion = Instant.parse("2026-05-26T09:00:00Z");
        String key = "sweep/2026/05/26/a.log.gz";
        failures.recordBlobFailure(new BlobListing(key, failedVersion), IngestPath.POLL, new IOException("reset"));
        clock.advance(Duration.ofMinutes(16));
        BlobClient unreachable = mock(BlobClient.class);
        when(unreachable.listWithMetadata(anyString())).thenThrow(new IllegalStateException("store unreachable"));
        IngestRetrySweep sweep = new IngestRetrySweep(failures, unreachable, ingestor, writer, syncProps, retry(true, 20), ledger,
                dataSyncService, registry, TestClientSpanTracer.noop());

        sweep.runOnce();

        assertThat(failureLedger.of(IngestFailureKind.BLOB, key)).hasSize(2).allSatisfy(event -> {
            assertThat(event.blobLastModified()).isEqualTo(failedVersion);
            assertThat(event.day()).isEqualTo(LocalDate.parse("2026-05-26"));
        });
    }

    @Test
    void failedDayIsReRunAndResolved() throws Exception {
        writeBlob("a.log.gz", "p1");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        failures.recordDayFailure(today, IngestPath.RECONCILE, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.LISTING_FAILED, new IllegalStateException("store down"));
        clock.advance(Duration.ofMinutes(16));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1");
        assertThat(failureLedger.of(IngestFailureKind.DAY, today.toString())).extracting(IngestFailureEvent::type)
                .containsExactly(IngestFailureEvent.Type.FAILED, IngestFailureEvent.Type.RESOLVED);
        assertThat(registry.counter("sync.retry.attempts", "kind", "day", "outcome", "success").count()).isEqualTo(1);
    }

    /**
     * A day that failed while a backfill ingested every blob is retried the same way: the blobs after its fault are
     * recorded from an earlier ingest, and a retry that skipped them would resolve the day with their old rows in place.
     */
    @Test
    void dayThatFailedIngestingEveryBlobIsRetriedTheSameWay() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        ledger.record(List.of(blob));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        failures.recordDayFailure(today, IngestPath.BACKFILL, IngestLedger.Mode.INGEST_ALL, IngestFailureReason.WRITE_FAILED,
                new IllegalStateException("insert failed"));
        clock.advance(Duration.ofMinutes(16));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1");
        assertThat(failureLedger.of(IngestFailureKind.DAY, today.toString())).extracting(IngestFailureEvent::type)
                .containsExactly(IngestFailureEvent.Type.FAILED, IngestFailureEvent.Type.RESOLVED);
    }

    /** A run that skips recorded blobs, such as the scheduled one, does not resolve such a day. */
    @Test
    void skippingRunLeavesOpenTheDayThatFailedIngestingEveryBlob() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        ledger.record(List.of(blob));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        failures.recordDayFailure(today, IngestPath.BACKFILL, IngestLedger.Mode.INGEST_ALL, IngestFailureReason.WRITE_FAILED,
                new IllegalStateException("insert failed"));

        DataSyncService.RunSummary run = dataSyncService.runForDate(today, IngestLedger.Mode.SKIP_RECORDED, IngestPath.RECONCILE,
                DataSyncService.IngestProgressListener.NO_OP);

        assertThat(run.success()).isTrue();
        assertThat(writer.rows).isEmpty();
        assertThat(failures.outstanding()).singleElement().satisfies(entry ->
                assertThat(entry.failure().ledgerMode()).isEqualTo(IngestLedger.Mode.INGEST_ALL));
    }

    /**
     * A later failure of a skipping run, such as a scheduled run of the day while ClickHouse is briefly unavailable, does
     * not lower the day's mode: neither that run nor a later skipping run resolves it, and the retry still ingests every blob.
     */
    @Test
    void laterSkippingFailureKeepsTheDayIngestingEveryBlob() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        ledger.record(List.of(blob));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        failures.recordDayFailure(today, IngestPath.BACKFILL, IngestLedger.Mode.INGEST_ALL, IngestFailureReason.WRITE_FAILED,
                new IllegalStateException("insert failed"));
        clock.advance(Duration.ofMinutes(1));
        failures.recordDayFailure(today, IngestPath.RECONCILE, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.WRITE_FAILED,
                new IllegalStateException("down"));

        DataSyncService.RunSummary run = dataSyncService.runForDate(today, IngestLedger.Mode.SKIP_RECORDED, IngestPath.RECONCILE,
                DataSyncService.IngestProgressListener.NO_OP);

        assertThat(run.success()).isTrue();
        assertThat(writer.rows).isEmpty();
        assertThat(failures.outstanding()).singleElement().satisfies(entry ->
                assertThat(entry.failure().ledgerMode()).isEqualTo(IngestLedger.Mode.INGEST_ALL));

        clock.advance(Duration.ofHours(1));
        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1");
        assertThat(failureLedger.of(IngestFailureKind.DAY, today.toString())).extracting(IngestFailureEvent::type)
                .containsExactly(IngestFailureEvent.Type.FAILED, IngestFailureEvent.Type.FAILED, IngestFailureEvent.Type.RESOLVED);
    }

    @Test
    void theSweepIsBounded() throws Exception {
        BlobListing older = writeBlob("a.log.gz", "p1");
        BlobListing newer = writeBlob("b.log.gz", "p2");
        failures.recordBlobFailure(older, IngestPath.POLL, new IOException("reset"));
        clock.advance(Duration.ofSeconds(1));
        failures.recordBlobFailure(newer, IngestPath.POLL, new IOException("reset"));
        clock.advance(Duration.ofMinutes(16));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 1)).runOnce();

        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(ledger.recordedKeys()).containsExactly(older.name());
    }

    @Test
    void disabledRetriesAttemptNothingButStillWriteHeldEventsAndRefreshGauges() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        failures.recordBlobFailure(blob, IngestPath.POLL, new IOException("reset"));
        failureLedger.failAppends = true;
        failures.recordDayFailure(LocalDate.parse("2026-05-26"), IngestPath.RECONCILE, IngestLedger.Mode.SKIP_RECORDED, IngestFailureReason.WRITE_FAILED,
                new IllegalStateException("down"));
        failureLedger.failAppends = false;
        clock.advance(Duration.ofMinutes(16));

        IngestRetrySweep.SweepSummary summary = sweep(retry(false, 20)).runOnce();

        assertThat(summary.heldWritten()).isEqualTo(1);
        assertThat(summary.attempted()).isZero();
        assertThat(writer.rows).isEmpty();
        assertThat(registry.get("sync.failures.outstanding").tag("kind", "blob").tag("state", "pending").gauge().value()).isEqualTo(1);
        assertThat(registry.get("sync.failures.outstanding").tag("kind", "day").tag("state", "pending").gauge().value()).isEqualTo(1);
    }

    private IngestRetrySweep sweep(SyncRetryProperties retry) {
        return new IngestRetrySweep(failures, blobClient, ingestor, writer, syncProps, retry, ledger, dataSyncService, registry,
                TestClientSpanTracer.noop());
    }

    private static SyncRetryProperties retry(boolean enabled, int maxPerSweep) {
        return new SyncRetryProperties(enabled, "-", Duration.ofMinutes(15), Duration.ofHours(24), 10, maxPerSweep);
    }

    /** A blob's skipped records, requeued once the producer rewrote it without its bad line, are re-read and resolve. */
    @Test
    void requeuedRecordsOfRewrittenBlobAreReadAgainAndResolve() throws Exception {
        BlobListing blob = writeBlobLines("a.log.gz", record("p1") + "not json\n");
        ledger.clock = clock::instant;
        recordSkippedRecords(blob);
        clock.advance(Duration.ofSeconds(1));
        assertThat(failures.outstanding()).singleElement().satisfies(entry -> {
            assertThat(entry.failure().kind()).isEqualTo(IngestFailureKind.RECORDS);
            assertThat(entry.state()).isEqualTo(IngestFailureState.PARKED);
        });
        writeBlobLines("a.log.gz", record("p1"));
        failures.requeue(IngestFailureFilter.ALL);
        clock.advance(Duration.ofMinutes(1));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.succeeded()).isEqualTo(1);
        // Read again although the ingest ledger records the blob.
        assertThat(writer.rows).extracting(row -> row.get("project_id")).containsExactly("p1");
        assertThat(failureLedger.of(IngestFailureKind.RECORDS, blob.name())).extracting(IngestFailureEvent::type)
                .containsExactly(IngestFailureEvent.Type.FAILED, IngestFailureEvent.Type.REQUEUED, IngestFailureEvent.Type.RESOLVED);
        assertThat(failures.outstanding()).isEmpty();
        assertThat(registry.counter("sync.retry.attempts", "kind", "records", "outcome", "success").count()).isEqualTo(1);
    }

    /** A requeued blob that still holds its bad line parks again, under a fresh failure from the retry. */
    @Test
    void requeuedRecordsOfAnUnchangedBlobParkAgain() throws Exception {
        BlobListing blob = writeBlobLines("a.log.gz", record("p1") + "not json\n");
        ledger.clock = clock::instant;
        recordSkippedRecords(blob);
        failures.requeue(IngestFailureFilter.ALL);
        clock.advance(Duration.ofMinutes(1));

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(failureLedger.of(IngestFailureKind.RECORDS, blob.name())).last().satisfies(event -> {
            assertThat(event.type()).isEqualTo(IngestFailureEvent.Type.FAILED);
            assertThat(event.reason()).isEqualTo(IngestFailureReason.SKIPPED_RECORDS);
            assertThat(event.path()).isEqualTo("retry");
            assertThat(event.message()).isEqualTo("malformed_line: 1 (line 2)");
        });
        assertThat(failures.outstanding()).singleElement().extracting(IngestFailureEntry::state).isEqualTo(IngestFailureState.PARKED);
    }

    /** A requeued blob ClickHouse refused is ingested once the cause is gone, like any requeued blob. */
    @Test
    void requeuedRefusedBlobIsIngestedOnceTheCauseIsGone() throws Exception {
        BlobListing blob = writeBlob("a.log.gz", "p1");
        failures.recordBlobFailures(List.of(failures.refusedBlobFailure(blob, IngestPath.POLL,
                new RefusedBlob(blob, "dial_usage_log", 53, "TYPE_MISMATCH"))));
        assertThat(failures.outstanding()).singleElement().extracting(IngestFailureEntry::state).isEqualTo(IngestFailureState.PARKED);
        failures.requeue(IngestFailureFilter.ALL);
        clock.advance(Duration.ofMinutes(1));

        assertThat(sweep(retry(true, 20)).runOnce().succeeded()).isEqualTo(1);

        assertThat(ledger.recordedKeys()).containsExactly(blob.name());
        assertThat(failures.outstanding()).isEmpty();
    }

    /**
     * A requeued records subject whose blob ClickHouse now refuses parks again: the refusal is recorded on the records
     * subject too, so it is not left pending with no attempts and re-read by every sweep.
     */
    @Test
    void requeuedRecordsOfRefusedBlobParkAgain() throws Exception {
        BlobListing blob = writeBlobLines("a.log.gz", record("p1") + "not json\n");
        ledger.clock = clock::instant;
        recordSkippedRecords(blob);
        failures.requeue(IngestFailureFilter.ALL);
        clock.advance(Duration.ofMinutes(1));
        writer.rejectRows = true;

        IngestRetrySweep.SweepSummary summary = sweep(retry(true, 20)).runOnce();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(failureLedger.of(IngestFailureKind.RECORDS, blob.name())).last().satisfies(event -> {
            assertThat(event.type()).isEqualTo(IngestFailureEvent.Type.FAILED);
            assertThat(event.reason()).isEqualTo(IngestFailureReason.REJECTED_BY_SERVER);
            assertThat(event.path()).isEqualTo("retry");
        });
        assertThat(failures.outstanding()).hasSize(2).allSatisfy(entry -> assertThat(entry.state()).isEqualTo(IngestFailureState.PARKED));

        clock.advance(Duration.ofMinutes(16));
        assertThat(sweep(retry(true, 20)).runOnce().attempted()).isZero();
    }

    /** As a reconciliation run would leave it: the blob recorded, its skipped records parked. */
    private void recordSkippedRecords(BlobListing blob) {
        SkippedRecords skips = new SkippedRecords();
        skips.add(SkippedRecords.Cause.MALFORMED_LINE, 2);
        failures.recordHandOver(new RunSink.HandOver(List.of(blob), Map.of(blob, skips), List.of()), IngestPath.RECONCILE);
    }

    private static String record(String project) {
        return "{\"project\":{\"id\":\"" + project + "\"},\"request\":{\"time\":\"2026-06-18T00:00:01\"},"
                + "\"trace\":{\"trace_id\":\"" + project + "\",\"core_span_id\":\"s1\"}}\n";
    }

    private BlobListing writeBlobLines(String fileName, String content) throws Exception {
        String key = dayDir + fileName;
        Path file = baseDir.resolve(BUCKET).resolve(key);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        Instant lastModified = blobClient.listWithMetadata(key).getFirst().lastModified();
        return new BlobListing(key, lastModified.truncatedTo(ChronoUnit.MILLIS));
    }

    /** Writes a blob holding one keyed record and returns it as the store lists it. */
    private BlobListing writeBlob(String fileName, String project) throws Exception {
        String key = dayDir + fileName;
        Path file = baseDir.resolve(BUCKET).resolve(key);
        Files.createDirectories(file.getParent());
        String record = "{\"project\":{\"id\":\"" + project + "\"},\"request\":{\"time\":\"2026-06-18T00:00:01\"},"
                + "\"trace\":{\"trace_id\":\"" + project + "\",\"core_span_id\":\"s1\"}}\n";
        try (OutputStream out = Files.newOutputStream(file); GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(record.getBytes(StandardCharsets.UTF_8));
        }
        Instant lastModified = blobClient.listWithMetadata(key).getFirst().lastModified();
        return new BlobListing(key, lastModified.truncatedTo(ChronoUnit.MILLIS));
    }
}
