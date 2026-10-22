package com.epam.aidial.analytics.sync;

import com.epam.aidial.analytics.configuration.SyncProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserTest {

    private final LogParser parser = new LogParser(JsonMapper.builder().build(), SyncProperties.DEFAULT_MAX_LINE_BYTES);

    @Test
    void parsesValidGzippedNdjsonRecords() throws Exception {
        byte[] gzip = gzip("""
                {"apiType":"DialOpenAI","project":{"id":"a"}}
                {"apiType":"DialOpenAI","project":{"id":"b"}}
                """);

        List<JsonNode> records = new ArrayList<>();
        long skipped = parser.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isZero();
        assertThat(records).hasSize(2);
        assertThat(records.getFirst().path("project").path("id").asString("")).isEqualTo("a");
    }

    @Test
    void skipsMalformedLinesAndCountsThemWithoutFailing() throws Exception {
        byte[] gzip = gzip("""
                {"apiType":"DialOpenAI"}
                this is not json
                {"apiType":"DialOpenAI"}
                """);

        List<JsonNode> records = new ArrayList<>();
        long skipped = parser.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isEqualTo(1);
        assertThat(records).hasSize(2);
    }

    @Test
    void ignoresBlankLines() throws Exception {
        byte[] gzip = gzip("{\"apiType\":\"DialOpenAI\"}\n\n\n");

        List<JsonNode> records = new ArrayList<>();
        long skipped = parser.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isZero();
        assertThat(records).hasSize(1);
    }

    @Test
    void skipsLineLongerThanTheCapAndKeepsParsingTheRest() throws Exception {
        LogParser capped = new LogParser(JsonMapper.builder().build(), 1024);
        String oversized = "{\"apiType\":\"" + "a".repeat(200_000) + "\"}";
        byte[] gzip = gzip("{\"project\":{\"id\":\"a\"}}\n" + oversized + "\n{\"project\":{\"id\":\"b\"}}\n");

        List<JsonNode> records = new ArrayList<>();
        long skipped = capped.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isEqualTo(1);
        assertThat(records).extracting(r -> r.path("project").path("id").asString("")).containsExactly("a", "b");
    }

    @Test
    void skipsOversizedLineWithNoTrailingNewline() throws Exception {
        LogParser capped = new LogParser(JsonMapper.builder().build(), 1024);
        // Valid JSON, so only the cap can reject it.
        byte[] gzip = gzip("{\"project\":{\"id\":\"a\"}}\n{\"project\":{\"id\":\"" + "x".repeat(300_000) + "\"}}");

        List<JsonNode> records = new ArrayList<>();
        long skipped = capped.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isEqualTo(1);
        assertThat(records).hasSize(1);
    }

    @Test
    void acceptsLineSpanningReadChunksUpToTheCap() throws Exception {
        String value = "é".repeat(100_000);
        String line = "{\"apiType\":\"" + value + "\"}";
        LogParser capped = new LogParser(JsonMapper.builder().build(), line.getBytes(StandardCharsets.UTF_8).length);
        byte[] gzip = gzip(line + "\r\n{\"apiType\":\"b\"}");

        List<JsonNode> records = new ArrayList<>();
        long skipped = capped.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isZero();
        assertThat(records).extracting(r -> r.path("apiType").asString("")).containsExactly(value, "b");
    }

    @Test
    void lineOneByteOverTheCapIsSkipped() throws Exception {
        String line = "{\"apiType\":\"" + "a".repeat(1000) + "\"}";
        LogParser capped = new LogParser(JsonMapper.builder().build(), line.length() - 1);

        List<JsonNode> records = new ArrayList<>();
        long skipped = capped.parse(new ByteArrayInputStream(gzip(line + "\r\n")), "test.log.gz", records::add);

        assertThat(skipped).isEqualTo(1);
        assertThat(records).isEmpty();
    }

    @Test
    void countsOversizedLinesSeparatelyFromMalformedOnes() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LogParser capped = new LogParser(JsonMapper.builder().build(), 1024, registry);
        String oversized = "{\"apiType\":\"" + "a".repeat(5_000) + "\"}";
        byte[] gzip = gzip(oversized + "\nthis is not json\n" + oversized + "\n{\"apiType\":\"b\"}\n");

        List<JsonNode> records = new ArrayList<>();
        long skipped = capped.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isEqualTo(3);
        assertThat(registry.counter(LogParser.OVERSIZED_LINES_METRIC).count()).isEqualTo(2.0);
        assertThat(records).hasSize(1);
    }

    @Test
    void parsesShortLinesAfterOneLineGrewTheBuffer() throws Exception {
        String big = "{\"apiType\":\"" + "a".repeat(3_000_000) + "\"}";
        LogParser capped = new LogParser(JsonMapper.builder().build(), big.length());
        byte[] gzip = gzip(big + "\n{\"apiType\":\"b\"}\n{\"apiType\":\"c\"}");

        List<JsonNode> records = new ArrayList<>();
        long skipped = capped.parse(new ByteArrayInputStream(gzip), "test.log.gz", records::add);

        assertThat(skipped).isZero();
        assertThat(records).extracting(r -> r.path("apiType").asString("").length()).containsExactly(3_000_000, 1, 1);
    }

    private static byte[] gzip(String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
