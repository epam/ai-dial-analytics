package com.epam.aidial.analytics.service;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.sync.LogParser;
import com.epam.aidial.analytics.sync.delta.Rebuild;
import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.inline.InlineFilesPolicy;
import com.epam.aidial.analytics.sync.mapping.DeriverRegistry;
import com.epam.aidial.analytics.sync.mapping.MappingConfig;
import com.epam.aidial.analytics.sync.mapping.MappingEngine;
import com.epam.aidial.analytics.sync.mapping.MappingFileLoader;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import com.epam.aidial.analytics.sync.mapping.TransformRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through {@link BlobIngestor} under {@code sync.request-body-mode}: the payload target's sink stores a
 * chat request's follow-up turn as its new messages + parent link and leaves an MCP body whole, while the usage-log
 * rows still describe the bodies as sent. Under {@code whole} nothing changes.
 */
class RequestDeltaIngestTest {

    private static final String BUCKET = "logs";
    private static final String BLOB_KEY = "logs/2026/01/15/delta.log.gz";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private static final String TURN_1 = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"s\"},{\"role\":\"user\",\"content\":\"u1\"}]}";
    private static final String TURN_2 = "{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"s\"},{\"role\":\"user\",\"content\":\"u1\"},"
            + "{\"role\":\"assistant\",\"content\":\"a1\"},{\"role\":\"user\",\"content\":\"u2\"}]}";
    private static final String MCP = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1}";

    @Test
    void deltaStoresTheFollowUpCutAndLeavesOtherBodiesWhole(@TempDir Path baseDir) throws Exception {
        writeBlob(baseDir);

        List<Map<String, Object>> payload = ingest(baseDir, RequestBodyMode.DELTA).rowsFor("dial_usage_log_payload");

        assertThat(payload).hasSize(3);
        Map<String, Object> first = payload.get(0);
        Map<String, Object> second = payload.get(1);
        assertThat(Rebuild.canonical((String) first.get("request_body"))).isEqualTo(Rebuild.canonical(TURN_1));
        assertThat(second.get("request_parent_hash")).isEqualTo(first.get("request_hash"));
        assertThat(second.get("request_body"))
                .isEqualTo("{\"messages\":[{\"content\":\"a1\",\"role\":\"assistant\"},{\"content\":\"u2\",\"role\":\"user\"}],\"model\":\"m\"}");
        assertThat(payload.get(2).get("request_body")).isEqualTo(MCP);
        assertThat(payload.get(2)).doesNotContainKey("request_hash");
        // The identity columns are written on every row, whatever the mode or body; the session only when Core sent one.
        assertThat(payload).allSatisfy(row -> assertThat(row).containsEntry("project_id", "p").containsEntry("deployment", "d")
                .containsEntry("user_hash", "user-1"));
        assertThat(payload).extracting(row -> row.get("session_id")).containsExactly("s-1", "s-1", null);
    }

    @Test
    void deltaLeavesTheUsageLogRowsDescribingTheBodiesAsSent(@TempDir Path baseDir) throws Exception {
        writeBlob(baseDir);

        List<Map<String, Object>> usageLog = ingest(baseDir, RequestBodyMode.DELTA).rowsFor("dial_usage_log");

        assertThat(usageLog.get(1).get("request_body_bytes")).isEqualTo((long) TURN_2.getBytes(StandardCharsets.UTF_8).length);
        assertThat(usageLog.get(1).get("number_request_messages")).isEqualTo(4L);
    }

    @Test
    void wholeStoresEveryBodyAsBefore(@TempDir Path baseDir) throws Exception {
        writeBlob(baseDir);

        List<Map<String, Object>> payload = ingest(baseDir, RequestBodyMode.WHOLE).rowsFor("dial_usage_log_payload");

        assertThat(payload).extracting(row -> row.get("request_body")).containsExactly(TURN_1, TURN_2, MCP);
        assertThat(payload).allSatisfy(row -> assertThat(row).doesNotContainKeys("request_parent_hash", "request_ancestor_hashes"));
        // A chat row stored whole still carries its path, so it can be a parent once delta is switched on.
        assertThat(payload.get(1)).containsKey("request_hash");
        assertThat(payload.get(2)).doesNotContainKey("request_hash");
    }

    /** The mode applies after the inline-file rewrite (here: every file removed): a file dropped by it is not in the stored body. */
    @Test
    void deltaStoresTheBodyAsTheInlineFileRewriteLeftIt(@TempDir Path baseDir) throws Exception {
        String file = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        String withImage = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"what is it?\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + file + "\"}}]}]}";
        Path blob = baseDir.resolve(BUCKET).resolve(BLOB_KEY);
        Files.createDirectories(blob.getParent());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(blob))) {
            out.write(line("t1", "/openai/deployments/m/chat/completions", "2026-01-15T10:00:00.000", withImage).getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Object> row = ingest(baseDir, RequestBodyMode.DELTA, InlineFilesPolicy.dropAll()).rowsFor("dial_usage_log_payload").getFirst();

        assertThat((String) row.get("request_body")).contains("data:image/png;base64,").doesNotContain(file);
        assertThat(row.get("request_hash")).isNotNull();
    }

    private RecordingWriter ingest(Path baseDir, RequestBodyMode mode) throws Exception {
        return ingest(baseDir, mode, InlineFilesPolicy.keep());
    }

    private RecordingWriter ingest(Path baseDir, RequestBodyMode mode, InlineFilesPolicy inlineFiles) throws Exception {
        RecordingWriter writer = new RecordingWriter() {
            @Override
            public List<GenericRecord> query(String sql, Map<String, Object> params) {
                return List.of(); // the payload table holds nothing yet
            }
        };
        List<SyncTarget> targets = targets(inlineFiles);
        SyncProperties properties = new SyncProperties("-", 0, 1000, 268_435_456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, "logs", "yyyy/MM/dd",
                "'date='yyyy-MM-dd", false, List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")),
                inlineFiles.dropTruncated(), null, mode);
        BlobIngestor ingestor = new BlobIngestor(blobClient(baseDir), new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets,
                IngestFlushOrder.NONE, new RequestDeltaStages(properties, targets, writer, new SimpleMeterRegistry()));
        RunSink sink = ingestor.newRunSink(writer, 1000, 268_435_456L);
        ingestor.ingest(BLOB_KEY, sink);
        sink.flush();
        return writer;
    }

    private void writeBlob(Path baseDir) throws Exception {
        Path blob = baseDir.resolve(BUCKET).resolve(BLOB_KEY);
        Files.createDirectories(blob.getParent());
        String content = String.join("\n",
                line("t1", "/openai/deployments/m/chat/completions", "2026-01-15T10:00:00.000", TURN_1),
                line("t2", "/openai/deployments/m/chat/completions", "2026-01-15T10:01:00.000", TURN_2),
                line("t3", "/v1/toolset/toolsets/public/docs__1.0.0/mcp", "2026-01-15T10:01:30.000", MCP));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(blob))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String line(String traceId, String uri, String time, String requestBody) {
        ObjectNode record = jsonMapper.createObjectNode();
        record.put("deployment", "d").putObject("project").put("id", "p");
        record.putObject("user").put("id", "user-1");
        if (!uri.endsWith("/mcp")) {
            record.putObject("session").put("id", "s-1");
        }
        record.putObject("trace").put("trace_id", traceId).put("core_span_id", "s-" + traceId);
        record.putObject("request").put("method", "POST").put("uri", uri).put("time", time).put("body", requestBody);
        record.putObject("response").put("status", "200");
        return jsonMapper.writeValueAsString(record);
    }

    private List<SyncTarget> targets(InlineFilesPolicy inlineFiles) {
        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingFileLoader loader = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers);
        MappingConfig dial = loader.load("classpath:mapping/dial-usage-log.yaml");
        MappingConfig payload = loader.load("classpath:mapping/dial-usage-log-payload.yaml");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return List.of(
                new SyncTarget("dial_usage_log", dial, new MappingEngine(dial, transforms, derivers, inlineFiles, "dial_usage_log", registry)),
                new SyncTarget("dial_usage_log_payload", payload,
                        new MappingEngine(payload, transforms, derivers, inlineFiles, "dial_usage_log_payload", registry)));
    }

    private static BlobClient blobClient(Path baseDir) {
        return new BlobClient(new BlobProperties("filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString())), TestBlobSpans.noop());
    }
}
