package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.sync.inline.BodySide;
import com.epam.aidial.analytics.sync.inline.InlineFilesSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers mapping-file load-time validation: the shipped default loads, derived columns parse, and
 * unknown synthetic source tokens / unknown transform names / unknown derivers / malformed derived
 * entries / missing files are rejected as configuration errors.
 */
class MappingFileLoaderTest {

    private final MappingFileLoader loader = new MappingFileLoader(
            new DefaultResourceLoader(), new TransformRegistry(), new DeriverRegistry(JsonMapper.builder().build()));

    @Test
    void loadsShippedDefaultMapping() {
        MappingConfig config = loader.load("classpath:mapping/dial-usage-log.yaml");

        // Every user column of dial_usage_log; only _ingested_at (a table-side default) is unmapped.
        // 33 since the mapping split moved the five verbatim-content columns to dial_usage_log_payload.
        assertThat(config.columns()).hasSize(33);
        ColumnMapping first = config.columns().getFirst();
        assertThat(first.column()).isEqualTo("event_id");
        assertThat(first.isDerived()).isTrue();
        assertThat(config.columns()).anyMatch(ColumnMapping::isSourceFile);
        // Every verbatim-content column is gone from this mapping — declaring one would now fail
        // startup schema validation, since the physical column no longer exists.
        assertThat(config.columns().stream().map(ColumnMapping::column))
                .doesNotContain("request_body", "response_body", "assembled_response",
                        "jwt_claims", "request_tags");
        // ...but the columns that SIZE a payload stay: 4 bytes each, and they must outlive it.
        assertThat(config.columns().getLast().column()).isEqualTo("response_body_bytes");
    }

    @Test
    void loadsShippedPayloadMapping() {
        MappingConfig config = loader.load("classpath:mapping/dial-usage-log-payload.yaml");

        // The five verbatim-content columns plus the four the table needs to key, partition, expire
        // and pass the dedup-key drop rule, then V15's request_scope (the request-delta chain scope). Unmapped:
        // _updated_at (a table-side default) and the request-delta columns the payload sink fills at flush.
        // The order matches the physical column order.
        assertThat(config.columns().stream().map(ColumnMapping::column)).containsExactly(
                "event_id", "request_time", "trace_id", "core_span_id",
                "jwt_claims", "request_tags",
                "request_body", "response_body", "assembled_response", "request_scope");
        // Match-all, exactly like dial-usage-log.yaml: the payload table shadows the usage log
        // one-for-one, so it must emit for every record the parent emits for.
        assertThat(config.matcher().conditions()).isEmpty();
    }

    @Test
    void payloadMappingDerivesTheSameEventIdAsTheUsageLogMapping() {
        MappingConfig payload = loader.load("classpath:mapping/dial-usage-log-payload.yaml");
        MappingConfig usageLog = loader.load("classpath:mapping/dial-usage-log.yaml");

        // The two rows a record produces must correspond one-to-one, which holds only while both
        // mappings derive event_id the same way. A later change joins the tables on this column.
        ColumnMapping payloadEventId = column(payload, "event_id");
        ColumnMapping usageLogEventId = column(usageLog, "event_id");
        assertThat(payloadEventId.derive()).isEqualTo(usageLogEventId.derive());
        assertThat(payloadEventId.sources()).isEqualTo(usageLogEventId.sources());

        // request_time carries no default in either mapping: a record without one is dropped at ingest
        // rather than landing in the epoch partition (and, here, never expiring on a request_time TTL).
        assertThat(column(payload, "request_time").defaultValue())
                .isEqualTo(column(usageLog, "request_time").defaultValue());
    }

    private static ColumnMapping column(MappingConfig config, String name) {
        return config.columns().stream()
                .filter(c -> c.column().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column " + name));
    }

    @Test
    void loadsDerivedColumn(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                columns:
                  - column: response_id
                    derive: json-extract-string
                    sources: [assembled_response, response.body]
                    args: { path: id }
                    kind: string
                """);

        MappingConfig config = loader.load("file:" + file);

        ColumnMapping column = config.columns().getFirst();
        assertThat(column.isDerived()).isTrue();
        assertThat(column.derive()).isEqualTo("json-extract-string");
        assertThat(column.sources()).containsExactly("assembled_response", "response.body");
        assertThat(column.args()).containsEntry("path", "id");
    }

    @Test
    void rejectsUnknownSyntheticToken(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                columns:
                  - column: x
                    source: $bogus
                    kind: string
                """);

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("$bogus")
                .hasMessageContaining("$sourceFile");
    }

    @Test
    void rejectsUnknownTransform(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                columns:
                  - column: x
                    source: a.b
                    kind: string
                    transform: no-such-transform
                """);

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-transform");
    }

    @Test
    void rejectsUnknownDeriver(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                columns:
                  - column: x
                    derive: no-such-deriver
                    sources: [a.b]
                    kind: string
                """);

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-deriver");
    }

    @Test
    void rejectsDerivedColumnWithoutSources(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                columns:
                  - column: x
                    derive: byte-length
                    kind: long
                """);

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sources");
    }

    @Test
    void rejectsColumnDeclaringBothSourceAndDerive(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                columns:
                  - column: x
                    source: a.b
                    derive: byte-length
                    sources: [a.b]
                    kind: long
                """);

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both");
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> loader.load("classpath:mapping/does-not-exist.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void shippedPayloadMappingDeclaresTheThreeBodiesAsInlineFileBodies() {
        MappingConfig payload = loader.load("classpath:mapping/dial-usage-log-payload.yaml");

        assertThat(column(payload, "request_body").inlineFiles()).isEqualTo(new InlineFilesSpec(BodySide.REQUEST, "request.uri"));
        assertThat(column(payload, "response_body").inlineFiles()).isEqualTo(new InlineFilesSpec(BodySide.RESPONSE, "request.uri"));
        assertThat(column(payload, "assembled_response").inlineFiles()).isEqualTo(new InlineFilesSpec(BodySide.RESPONSE, "request.uri"));
        assertThat(payload.columns().stream().filter(c -> c.inlineFiles() != null)).hasSize(3);
        // No other mapping declares one: the attribute is what makes a column a body, and only the payload table holds bodies.
        assertThat(loader.load("classpath:mapping/dial-usage-log.yaml").columns()).allMatch(c -> c.inlineFiles() == null);
    }

    @Test
    void loadsInlineFilesAttribute(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                columns:
                  - column: response_body
                    source: response.body
                    kind: string
                    nullable: true
                    inline-files:
                      body: Response
                      uri: request.uri
                """);

        ColumnMapping column = loader.load("file:" + file).columns().getFirst();

        assertThat(column.inlineFiles()).isEqualTo(new InlineFilesSpec(BodySide.RESPONSE, "request.uri"));
        assertThat(column.isDerived()).isFalse();
        assertThat(column.nullable()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            derived column            | derive: byte-length\\n    sources: [request.body]\\n    kind: long\\n    inline-files: { body: request, uri: request.uri } | is derived
            non-string column         | source: request.body\\n    kind: long\\n    inline-files: { body: request, uri: request.uri }                    | non-string
            source-file column        | source: $sourceFile\\n    kind: string\\n    inline-files: { body: request, uri: request.uri }                 | $sourceFile
            column with a transform   | source: request.body\\n    kind: string\\n    transform: string-array\\n    inline-files: { body: request, uri: request.uri } | transform
            missing body              | source: request.body\\n    kind: string\\n    inline-files: { uri: request.uri }                                 | body' missing
            unknown body              | source: request.body\\n    kind: string\\n    inline-files: { body: both, uri: request.uri }                     | 'both'
            missing uri               | source: request.body\\n    kind: string\\n    inline-files: { body: request }                                    | uri' missing
            synthetic uri             | source: request.body\\n    kind: string\\n    inline-files: { body: request, uri: $sourceFile }                  | '$sourceFile'
            non-mapping attribute     | source: request.body\\n    kind: string\\n    inline-files: request                                              | non-mapping
            """)
    void rejectsMisplacedOrMalformedInlineFiles(String name, String body, String message, @TempDir Path dir) throws Exception {
        Path file = write(dir, "columns:\n  - column: x\n    " + body.replace("\\n", "\n") + "\n");

        assertThatThrownBy(() -> loader.load("file:" + file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inline-files")
                .hasMessageContaining(message);
    }

    private static Path write(Path dir, String content) throws Exception {
        Path file = dir.resolve("mapping.yaml");
        Files.writeString(file, content);
        return file;
    }
}
