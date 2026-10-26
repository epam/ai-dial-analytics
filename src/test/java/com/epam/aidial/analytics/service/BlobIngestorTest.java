package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link BlobIngestor}'s multi-target fan-out against the jclouds <em>filesystem</em>
 * provider: every key-complete record lands in {@code dial_usage_log} <em>and</em> in
 * {@code dial_usage_log_payload}, and only {@code /rate} POST records additionally land in
 * {@code rate_analytics}. Also asserts {@code dial_usage_log} output is unchanged by the multi-target
 * refactor (a rate record still produces its dial row), and that the payload split moved the three
 * body columns to the payload target rather than duplicating them.
 */
class BlobIngestorTest {

    private static final String BUCKET = "logs";
    private static final String BLOB_KEY = "logs/2026/07/01/mixed.log.gz";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private BlobClient blobClient;
    private List<SyncTarget> targets;
    private RecordingWriter writer;
    private Path baseDir;

    @BeforeEach
    void setUp(@TempDir Path baseDir) throws Exception {
        this.baseDir = baseDir;
        Path blobFile = baseDir.resolve(BUCKET).resolve(BLOB_KEY);
        Files.createDirectories(blobFile.getParent());
        // A chat-completion (dial only), a /rate POST (dial + rate), and a /rate GET (dial only, not POST).
        String ndjson = String.join("\n",
                "{\"project\":{\"id\":\"p1\"},\"deployment\":\"gpt-4o\","
                        + "\"request\":{\"uri\":\"/v1/gpt-4o/chat/completions\",\"method\":\"POST\","
                        + "\"time\":\"2026-07-01T00:00:01\"},"
                        + "\"trace\":{\"trace_id\":\"t1\",\"core_span_id\":\"s1\"}}",
                "{\"project\":{\"id\":\"p2\"},\"deployment\":\"gpt-4o\",\"user\":{\"id\":\"u2\"},"
                        + "\"chat\":{\"id\":\"c2\"},\"request\":{\"uri\":\"/v1/gpt-4o/rate\",\"method\":\"POST\","
                        + "\"time\":\"2026-07-01T00:00:02\","
                        + "\"body\":\"{\\\"rate\\\":-1,\\\"responseId\\\":\\\"resp_2\\\",\\\"comment\\\":\\\"bad\\\"}\"},"
                        + "\"trace\":{\"trace_id\":\"t2\",\"core_span_id\":\"s2\"}}",
                "{\"project\":{\"id\":\"p3\"},"
                        + "\"request\":{\"uri\":\"/v1/gpt-4o/rate\",\"method\":\"GET\",\"time\":\"2026-07-01T00:00:03\"},"
                        + "\"trace\":{\"trace_id\":\"t3\",\"core_span_id\":\"s3\"}}");
        writeGzip(blobFile, ndjson);

        BlobProperties blobProps = new BlobProperties(
                "filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString()));
        blobClient = new BlobClient(blobProps, TestBlobSpans.noop());

        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingFileLoader loader = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers);
        MappingConfig dial = loader.load("classpath:mapping/dial-usage-log.yaml");
        MappingConfig payload = loader.load("classpath:mapping/dial-usage-log-payload.yaml");
        MappingConfig rate = loader.load("classpath:mapping/rate-analytics.yaml");
        targets = List.of(
                new SyncTarget("dial_usage_log", dial, new MappingEngine(dial, transforms, derivers)),
                new SyncTarget("dial_usage_log_payload", payload, new MappingEngine(payload, transforms, derivers)),
                new SyncTarget("rate_analytics", rate, new MappingEngine(rate, transforms, derivers)));
        writer = new RecordingWriter();
    }

    @Test
    void rateRecordsFanOutToRateAnalyticsOnlyWhileAllReachDialUsageLog() throws Exception {
        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        RunSink sink = ingestor.newRunSink(writer, 1000, 268_435_456L);

        BlobIngestor.IngestResult result = ingestor.ingest(BLOB_KEY, sink);
        sink.flush();

        // Every key-complete record reaches dial_usage_log (chat, rate-POST, rate-GET).
        assertThat(writer.rowsFor("dial_usage_log")).extracting(row -> row.get("project_id"))
                .containsExactly("p1", "p2", "p3");

        // Only the /rate POST record reaches rate_analytics.
        List<Map<String, Object>> rateRows = writer.rowsFor("rate_analytics");
        assertThat(rateRows).hasSize(1);
        assertThat(rateRows.getFirst())
                .containsEntry("response_id", "resp_2")
                .containsEntry("rate", -1L)
                .containsEntry("comment", "bad")
                .containsEntry("project_id", "p2");

        // Accepted total spans every target: 3 dial rows + 3 payload rows + 1 rate row.
        assertThat(result.rowsInserted()).isEqualTo(7);
        assertThat(result.rowsDropped()).isZero();
    }

    /**
     * The payload table shadows the usage log one-for-one: same records, same {@code event_id}. The
     * grain join matches on that key alone, so a divergence would not fail an insert — it would quietly
     * produce usage-log rows whose body columns read as null.
     */
    @Test
    void everyRecordAlsoProducesPayloadRowMirroringItsUsageLogRow() throws Exception {
        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        RunSink sink = ingestor.newRunSink(writer, 1000, 268_435_456L);

        ingestor.ingest(BLOB_KEY, sink);
        sink.flush();

        List<Map<String, Object>> dialRows = writer.rowsFor("dial_usage_log");
        List<Map<String, Object>> payloadRows = writer.rowsFor("dial_usage_log_payload");
        assertThat(payloadRows).hasSameSizeAs(dialRows);

        for (int i = 0; i < dialRows.size(); i++) {
            Map<String, Object> dial = dialRows.get(i);
            Map<String, Object> payload = payloadRows.get(i);
            // The join key a later change relies on must be identical on both sides.
            assertThat(payload.get("event_id")).isEqualTo(dial.get("event_id"));
            assertThat(payload.get("trace_id")).isEqualTo(dial.get("trace_id"));
            assertThat(payload.get("core_span_id")).isEqualTo(dial.get("core_span_id"));
            assertThat(payload.get("request_time")).isEqualTo(dial.get("request_time"));
            // Moved, not duplicated: every verbatim-content column — the bodies and the claim/header
            // documents alike — is written by exactly one of the two targets.
            for (String column : List.of("request_body", "response_body", "assembled_response",
                    "jwt_claims", "request_tags")) {
                assertThat(payload).as("payload." + column).containsKey(column);
                assertThat(dial).as("dial." + column).doesNotContainKey(column);
            }
        }
        // The payload row carries only what the payload table declares — no analytic columns leak in. The identity
        // columns it does declare carry the usage-log row's values.
        assertThat(payloadRows.getFirst()).doesNotContainKeys("chat_id", "total_tokens", "total_price");
        assertThat(payloadRows.getFirst().get("project_id")).isEqualTo(dialRows.getFirst().get("project_id"));
        assertThat(payloadRows.getFirst().get("deployment")).isEqualTo(dialRows.getFirst().get("deployment"));
    }

    /**
     * The dedup-key drop rule is applied per target against the row that target produced, so a record
     * missing {@code trace_id} must be dropped for the payload target on the same terms as for the usage
     * log — otherwise the two tables would disagree about which events exist. It matters beyond
     * symmetry: such a record hashes to a <em>constant</em> {@code event_id}, which on a table keyed by
     * {@code event_id} alone would collapse every one of them into a single row.
     */
    @Test
    void recordWithoutDedupKeyIsDroppedFromBothTables() throws Exception {
        String key = "logs/2026/07/01/keyless.log.gz";
        Path blobFile = baseDir.resolve(BUCKET).resolve(key);
        Files.createDirectories(blobFile.getParent());
        writeGzip(blobFile, "{\"project\":{\"id\":\"p9\"},\"deployment\":\"gpt-4o\","
                + "\"request\":{\"uri\":\"/v1/gpt-4o/chat/completions\",\"method\":\"POST\","
                + "\"time\":\"2026-07-01T00:00:09\",\"body\":\"{\\\"messages\\\":[]}\"},"
                + "\"trace\":{\"core_span_id\":\"s9\"}}");

        BlobIngestor ingestor = new BlobIngestor(blobClient, new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets, IngestFlushOrder.NONE);
        RunSink sink = ingestor.newRunSink(writer, 1000, 268_435_456L);

        BlobIngestor.IngestResult result = ingestor.ingest(key, sink);
        sink.flush();

        assertThat(writer.rowsFor("dial_usage_log")).isEmpty();
        assertThat(writer.rowsFor("dial_usage_log_payload")).isEmpty();
        assertThat(result.rowsInserted()).isZero();
        // Counted once per target that refused it.
        assertThat(result.rowsDropped()).isEqualTo(2);
    }

    private static void writeGzip(Path file, String content) throws Exception {
        try (OutputStream out = Files.newOutputStream(file);
                GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
