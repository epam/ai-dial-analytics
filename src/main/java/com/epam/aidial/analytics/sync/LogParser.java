package com.epam.aidial.analytics.sync;

import com.epam.aidial.analytics.configuration.SyncProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Reads a gzip-compressed, newline-delimited JSON (NDJSON) blob stream and passes each successfully
 * parsed record to a consumer. Lines that cannot be parsed are skipped with a warning and counted;
 * a malformed line never aborts the file or the run. Parsing is streaming (line by line, split on {@code \n}
 * only) and a line longer than {@code sync.max-line-bytes} is discarded as it streams past, counted like a
 * malformed one and additionally on the {@value #OVERSIZED_LINES_METRIC} counter.
 *
 * <p>The cap bounds the raw line buffer, not the parse as a whole: a line just under it is held at once as its
 * bytes, the decoded {@link String} (up to twice the byte count for non-Latin-1 text), the JSON tree, the mapped
 * row and its share of the open batch. Memory per parsing thread is therefore a small multiple of the cap, and
 * the scheduled sync and a backfill can each be parsing at the same time.
 */
@Component
@Slf4j
public class LogParser {

    /** Counter of lines skipped for exceeding {@code sync.max-line-bytes}, across scheduled sync and backfill. */
    public static final String OVERSIZED_LINES_METRIC = "sync.lines.oversized";

    private static final int READ_CHUNK = 64 * 1024;
    private static final int INITIAL_LINE_BUFFER = 1024;
    /** A line buffer grown past this is dropped once its line is consumed, so one long line does not pin it for the blob. */
    private static final int RETAINED_LINE_BUFFER = 16 * READ_CHUNK;

    private final JsonMapper jsonMapper;
    private final int maxLineBytes;
    private final Counter oversizedLines;

    @Autowired
    public LogParser(JsonMapper jsonMapper, SyncProperties properties, MeterRegistry meterRegistry) {
        this(jsonMapper, properties.maxLineBytes(), meterRegistry);
    }

    public LogParser(JsonMapper jsonMapper, int maxLineBytes) {
        this(jsonMapper, maxLineBytes, new SimpleMeterRegistry());
    }

    public LogParser(JsonMapper jsonMapper, int maxLineBytes, MeterRegistry meterRegistry) {
        this.jsonMapper = jsonMapper;
        this.maxLineBytes = maxLineBytes;
        this.oversizedLines = meterRegistry.counter(OVERSIZED_LINES_METRIC);
    }

    /**
     * Parses an NDJSON gzip stream, invoking {@code recordConsumer} for each valid record.
     *
     * @param rawGzipStream  the raw (gzip-compressed) blob stream; closed by this method
     * @param sourceFile     blob name, used only for log context
     * @param recordConsumer receives each successfully parsed record
     * @return the number of malformed or oversized lines skipped
     * @throws IOException if the stream cannot be read or decompressed
     */
    public long parse(InputStream rawGzipStream, String sourceFile, Consumer<JsonNode> recordConsumer) throws IOException {
        long skipped = 0;
        long lineNumber = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(rawGzipStream)) {
            BoundedLineReader reader = new BoundedLineReader(gzip, maxLineBytes);
            while (reader.next()) {
                lineNumber++;
                if (reader.overflowBytes() > 0) {
                    skipped++;
                    oversizedLines.increment();
                    log.warn("Skipping oversized NDJSON line {} in {}: {} bytes exceeds sync.max-line-bytes ({})",
                            lineNumber, sourceFile, reader.overflowBytes(), maxLineBytes);
                    continue;
                }
                // Decoded leniently (malformed UTF-8 becomes U+FFFD) rather than handed to Jackson as bytes, which
                // would refuse the whole line over one bad byte.
                String line = new String(reader.buffer(), 0, reader.length(), StandardCharsets.UTF_8);
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = jsonMapper.readTree(line);
                } catch (JacksonException ex) {
                    // Only a genuine JSON parse failure is a "malformed line". Mapping/deriver faults from
                    // the consumer below must propagate, not be miscounted as benign parse skips.
                    skipped++;
                    log.warn("Skipping malformed NDJSON line {} in {}: {}", lineNumber, sourceFile, ex.getMessage());
                    continue;
                }
                recordConsumer.accept(node);
            }
        }
        return skipped;
    }

    /**
     * Splits a byte stream on {@code \n} only, dropping one {@code \r} that directly precedes it (CRLF); unlike
     * {@code BufferedReader.readLine}, a lone {@code \r} does not end a line. Holds at most {@code maxLineBytes} of
     * the current line plus that {@code \r}. A longer line is not buffered: its bytes are only counted until its
     * end, and it is reported through {@link #overflowBytes()}. A buffer grown past {@code RETAINED_LINE_BUFFER}
     * is released when the next line starts.
     */
    private static final class BoundedLineReader {

        private final InputStream in;
        private final int maxLineBytes;
        /** One more than the cap, so a line exactly at the cap still fits with its {@code \r}. */
        private final int bufferLimit;
        private final byte[] chunk = new byte[READ_CHUNK];
        private int chunkPos;
        private int chunkEnd;
        private byte[] line = new byte[INITIAL_LINE_BUFFER];
        private int length;
        private long overflowBytes;

        BoundedLineReader(InputStream in, int maxLineBytes) {
            this.in = in;
            this.maxLineBytes = maxLineBytes;
            this.bufferLimit = maxLineBytes + 1;
        }

        /** Advances to the next line; {@code false} at end of stream with no further line. */
        boolean next() throws IOException {
            if (line.length > RETAINED_LINE_BUFFER) {
                line = new byte[INITIAL_LINE_BUFFER];
            }
            length = 0;
            overflowBytes = 0;
            boolean sawAny = false;
            while (true) {
                if (chunkPos == chunkEnd) {
                    chunkEnd = in.read(chunk);
                    chunkPos = 0;
                    if (chunkEnd <= 0) {
                        chunkEnd = 0;
                        return sawAny && finish();
                    }
                }
                sawAny = true;
                int start = chunkPos;
                while (chunkPos < chunkEnd && chunk[chunkPos] != '\n') {
                    chunkPos++;
                }
                append(start, chunkPos - start);
                if (chunkPos < chunkEnd) {
                    chunkPos++;
                    return finish();
                }
            }
        }

        private boolean finish() {
            if (overflowBytes == 0 && length > 0 && line[length - 1] == '\r') {
                length--;
            }
            if (length > maxLineBytes) {
                overflowBytes = length;
                length = 0;
            }
            return true;
        }

        private void append(int from, int count) {
            if (overflowBytes > 0) {
                overflowBytes += count;
                return;
            }
            if ((long) length + count > bufferLimit) {
                overflowBytes = (long) length + count;
                length = 0;
                if (line.length > INITIAL_LINE_BUFFER) {
                    line = new byte[INITIAL_LINE_BUFFER];
                }
                return;
            }
            if (length + count > line.length) {
                line = Arrays.copyOf(line, (int) Math.min(bufferLimit, Math.max((long) line.length * 2, length + count)));
            }
            System.arraycopy(chunk, from, line, length, count);
            length += count;
        }

        byte[] buffer() {
            return line;
        }

        int length() {
            return length;
        }

        long overflowBytes() {
            return overflowBytes;
        }
    }
}
