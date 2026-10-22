package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.blob.TestBlobSpans;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.sync.LogParser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through {@link BlobIngestor} under {@code sync.inline-files-max-size=0}: a gzip NDJSON blob written the
 * way DIAL Core writes the usage log (each body a JSON string inside the line) is parsed, fanned out to the usage-log
 * and payload targets, and the payload rows carry the bodies without their inline files — for chat, Anthropic, MCP,
 * embeddings and a body Core cut at 4 MiB — while the usage-log rows still describe the bodies as sent.
 */
class InlineFileIngestTest {

    private static final String BUCKET = "logs";
    private static final String BLOB_KEY = "logs/2026/10/13/inline.log.gz";
    private static final String FILE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private static final String CHAT = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"what is it?\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + FILE + "\"}}]}]}";
    private static final String ANTHROPIC = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":"
            + "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + FILE + "\"}}]}]}";
    private static final String MCP_RESPONSE = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
            + "{\"type\":\"image\",\"data\":\"" + FILE + "\",\"mimeType\":\"image/png\"},"
            + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///a.pdf\",\"mimeType\":\"application/pdf\",\"blob\":\"" + FILE + "\"}}]}}\n\n";
    private static final String EMBEDDING = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":\"" + FILE + "\"}]}";
    private static final String CUT_HEAD = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"";

    @Test
    void dropRemovesInlineFilesFromThePayloadRowsOnly(@TempDir Path baseDir) throws Exception {
        String truncated = CUT_HEAD + "data:image/png;base64," + "A".repeat(4 * 1024 * 1024 - CUT_HEAD.length() - 22) + ">>";
        List<String> lines = new ArrayList<>();
        lines.add(line("t1", "/openai/deployments/gpt-5/chat/completions?api-version=2024-10-21", CHAT, "{\"choices\":[]}"));
        lines.add(line("t2", "/anthropic/v1/messages?beta=true", ANTHROPIC, null));
        lines.add(line("t3", "/v1/toolset/toolsets/public/docs__1.0.0/mcp", "{\"method\":\"tools/call\"}", MCP_RESPONSE));
        lines.add(line("t4", "/openai/deployments/text-embedding-3-large/embeddings", "{\"input\":\"x\"}", EMBEDDING));
        lines.add(line("t5", "/openai/deployments/gpt-5/chat/completions", truncated, null));
        lines.add(line("t6", "/claude_code_router/v1/messages?beta=true", ANTHROPIC, null));
        Path blob = baseDir.resolve(BUCKET).resolve(BLOB_KEY);
        Files.createDirectories(blob.getParent());
        writeGzip(blob, String.join("\n", lines));

        RecordingWriter writer = new RecordingWriter();
        BlobIngestor ingestor = new BlobIngestor(blobClient(baseDir), new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets(InlineFilesPolicy.dropAll()),
                IngestFlushOrder.NONE);
        RunSink sink = ingestor.newRunSink(writer, 1000, 268_435_456L);
        ingestor.ingest(BLOB_KEY, sink);
        sink.flush();

        List<Map<String, Object>> payload = writer.rowsFor("dial_usage_log_payload");
        assertThat(payload).hasSize(6);
        assertThat(payload.get(0).get("request_body")).isEqualTo(CHAT.replace(FILE, ""));
        assertThat(payload.get(0).get("response_body")).isEqualTo("{\"choices\":[]}");
        assertThat(payload.get(1).get("request_body")).isEqualTo(ANTHROPIC.replace(FILE, ""));
        assertThat(payload.get(2).get("response_body")).isEqualTo(MCP_RESPONSE.replace(FILE, ""));
        assertThat(payload.get(3).get("response_body")).isEqualTo(EMBEDDING.replace(FILE, ""));
        assertThat(payload.get(4).get("request_body")).isEqualTo("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":>>");
        // A route is never scanned, even when its body is shaped exactly like an Anthropic request.
        assertThat(payload.get(5).get("request_body")).isEqualTo(ANTHROPIC);

        // The usage-log rows are derived from the record, not from the rewritten payload value.
        List<Map<String, Object>> usageLog = writer.rowsFor("dial_usage_log");
        assertThat(usageLog.get(0).get("request_body_bytes")).isEqualTo((long) CHAT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(usageLog.get(4).get("request_body_bytes")).isEqualTo((long) truncated.getBytes(StandardCharsets.UTF_8).length);
        assertThat(usageLog.get(2).get("response_body_bytes")).isEqualTo((long) MCP_RESPONSE.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void keepStoresEveryBodyAsSent(@TempDir Path baseDir) throws Exception {
        Path blob = baseDir.resolve(BUCKET).resolve(BLOB_KEY);
        Files.createDirectories(blob.getParent());
        writeGzip(blob, line("t1", "/openai/deployments/gpt-5/chat/completions", CHAT, null));

        RecordingWriter writer = new RecordingWriter();
        BlobIngestor ingestor = new BlobIngestor(blobClient(baseDir), new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets(InlineFilesPolicy.keep()),
                IngestFlushOrder.NONE);
        RunSink sink = ingestor.newRunSink(writer, 1000, 268_435_456L);
        ingestor.ingest(BLOB_KEY, sink);
        sink.flush();

        assertThat(writer.rowsFor("dial_usage_log_payload")).singleElement()
                .satisfies(row -> assertThat(row.get("request_body")).isEqualTo(CHAT));
    }

    /**
     * An {@code assembled_response} that is not JSON (an SSE transcript) is stored verbatim only under {@code keep}, or
     * when it holds no inline file; with a size limit its {@code data:} events are scanned and their files removed.
     */
    @Test
    void sseAssembledResponseIsStoredAsSentUnderKeepAndScannedUnderDrop(@TempDir Path baseDir) throws Exception {
        String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"id\":\"a1\",\"data\":\"" + FILE + "\"}}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: [DONE]\n\n";
        String plainSse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n";
        Path blob = baseDir.resolve(BUCKET).resolve(BLOB_KEY);
        Files.createDirectories(blob.getParent());
        writeGzip(blob, String.join("\n",
                line("t1", "/openai/deployments/gpt-5/chat/completions", "{\"messages\":[]}", null, sse),
                line("t2", "/openai/deployments/gpt-5/chat/completions", "{\"messages\":[]}", null, plainSse)));

        assertThat(assembledResponses(baseDir, InlineFilesPolicy.keep())).containsExactly(sse, plainSse);
        assertThat(assembledResponses(baseDir, InlineFilesPolicy.defaults())).containsExactly(sse, plainSse);
        assertThat(assembledResponses(baseDir, InlineFilesPolicy.dropAll())).containsExactly(sse.replace(FILE, ""), plainSse);
    }

    private List<Object> assembledResponses(Path baseDir, InlineFilesPolicy policy) throws Exception {
        RecordingWriter writer = new RecordingWriter();
        BlobIngestor ingestor = new BlobIngestor(blobClient(baseDir), new LogParser(jsonMapper, SyncProperties.DEFAULT_MAX_LINE_BYTES), targets(policy),
                IngestFlushOrder.NONE);
        RunSink sink = ingestor.newRunSink(writer, 1000, 268_435_456L);
        ingestor.ingest(BLOB_KEY, sink);
        sink.flush();
        return writer.rowsFor("dial_usage_log_payload").stream().map(row -> row.get("assembled_response")).toList();
    }

    /** One usage-log line as Core writes it: the bodies are JSON strings, so the line escapes them. */
    private String line(String traceId, String uri, String requestBody, String responseBody) {
        return line(traceId, uri, requestBody, responseBody, null);
    }

    private String line(String traceId, String uri, String requestBody, String responseBody, String assembledResponse) {
        ObjectNode record = jsonMapper.createObjectNode();
        if (assembledResponse != null) {
            record.put("assembled_response", assembledResponse);
        }
        record.put("deployment", "d").putObject("project").put("id", "p");
        record.putObject("trace").put("trace_id", traceId).put("core_span_id", "s-" + traceId);
        record.putObject("request").put("method", "POST").put("uri", uri).put("time", "2026-10-13T10:00:00.000").put("body", requestBody);
        ObjectNode response = record.putObject("response").put("status", "200");
        if (responseBody != null) {
            response.put("body", responseBody);
        }
        return jsonMapper.writeValueAsString(record);
    }

    private List<SyncTarget> targets(InlineFilesPolicy policy) {
        TransformRegistry transforms = new TransformRegistry();
        DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
        MappingFileLoader loader = new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers);
        MappingConfig dial = loader.load("classpath:mapping/dial-usage-log.yaml");
        MappingConfig payload = loader.load("classpath:mapping/dial-usage-log-payload.yaml");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return List.of(
                new SyncTarget("dial_usage_log", dial, new MappingEngine(dial, transforms, derivers, policy, "dial_usage_log", registry)),
                new SyncTarget("dial_usage_log_payload", payload,
                        new MappingEngine(payload, transforms, derivers, policy, "dial_usage_log_payload", registry)));
    }

    private static BlobClient blobClient(Path baseDir) {
        return new BlobClient(new BlobProperties("filesystem", null, null, BUCKET, null,
                Map.of("jclouds.filesystem.basedir", baseDir.toString())), TestBlobSpans.noop());
    }

    private static void writeGzip(Path file, String content) throws Exception {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
