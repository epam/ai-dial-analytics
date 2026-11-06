package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.sync.inline.InlineFilesPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's handling of a value that fails to coerce: it takes the column's absent/blank fallback rather than an
 * invented value, and is counted per table and column (see the {@code data-sync} spec, "A value that fails to coerce
 * to its column's kind is counted and falls back like an absent one").
 */
class MappingEngineCoercionTest {

    private static final String TABLE = "dial_usage_log";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TransformRegistry transforms = new TransformRegistry();
    private final DeriverRegistry derivers = new DeriverRegistry(jsonMapper);
    private final MappingEngine engine = new MappingEngine(
            new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers).load("classpath:mapping/dial-usage-log.yaml"),
            transforms, derivers, InlineFilesPolicy.keep(), TABLE, registry);

    private Map<String, Object> row(String fields) {
        return engine.toRow(jsonMapper.readTree("{\"trace\":{\"trace_id\":\"t\",\"core_span_id\":\"s\"},"
                + "\"request\":{\"time\":\"2026-05-26T00:00:00.000\"}," + fields + "}"), "logs/2026/05/26/a.log.gz");
    }

    private double failures(String column, String kind) {
        var counter = registry.find("sync.values.coercion.failures").tags("table", TABLE, "column", column, "kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void unreadableTextInNullableLongColumnIsStoredAsNull() {
        Map<String, Object> row = row("\"token_usage\":{\"prompt_tokens\":\"abc\",\"total_tokens\":8}");

        assertThat(row).containsEntry("prompt_tokens", null).containsEntry("total_tokens", 8L);
        assertThat(failures("prompt_tokens", "long")).isEqualTo(1);
        assertThat(failures("total_tokens", "long")).isZero();
    }

    @Test
    void fractionalNumberInNonNullableLongColumnFallsBackToZeroAndIsCounted() {
        Map<String, Object> row = row("\"operation_duration_ms\":12.7");

        assertThat(row).containsEntry("operation_duration_ms", 0L);
        assertThat(failures("operation_duration_ms", "long")).isEqualTo(1);
    }

    @Test
    void numericTextIsReadAsTheNumber() {
        assertThat(row("\"token_usage\":{\"total_tokens\":\" 42 \"}")).containsEntry("total_tokens", 42L);
        assertThat(failures("total_tokens", "long")).isZero();
    }

    @Test
    void blankTextIsAbsentAndNotCounted() {
        assertThat(row("\"token_usage\":{\"total_tokens\":\"\"}")).containsEntry("total_tokens", null);
        assertThat(failures("total_tokens", "long")).isZero();
    }

    @Test
    void decimalTextKeepsItsPrecision() {
        assertThat(row("\"token_usage\":{\"price\":\"0.000123\"}")).containsEntry("total_price", new BigDecimal("0.000123"));
        assertThat(failures("total_price", "double")).isZero();
    }

    @Test
    void unreadableDecimalIsCounted() {
        assertThat(row("\"token_usage\":{\"price\":true}")).containsEntry("total_price", null);
        assertThat(failures("total_price", "double")).isEqualTo(1);
    }

    @Test
    void structuredArrayElementsKeepTheirContentAndScalarIsCounted() {
        assertThat(row("\"execution_path\":[\"a\",{\"k\":1}]")).containsEntry("execution_path", List.of("a", "{\"k\":1}"));
        assertThat(row("\"execution_path\":\"a\"")).containsEntry("execution_path", List.of());
        assertThat(failures("execution_path", "array<string>")).isEqualTo(1);
    }

    @Test
    void unreadableNumberInDeriverIsCountedAgainstTheDerivedColumn() {
        Map<String, Object> row = row("\"assembled_response\":\"{\\\"usage\\\":{\\\"completion_tokens_details\\\":{\\\"reasoning_tokens\\\":\\\"n/a\\\"}}}\"");

        assertThat(row).containsEntry("reasoning_tokens", 0L);
        assertThat(failures("reasoning_tokens", "long")).isEqualTo(1);
    }

    @Test
    void unreadableStatusIsCountedOnBothColumnsThatReadIt() {
        Map<String, Object> row = row("\"response\":{\"status\":\"OK\"}");

        assertThat(row).containsEntry("response_status", 0L).containsEntry("success", false);
        assertThat(failures("response_status", "long")).isEqualTo(1);
        assertThat(failures("success", "bool")).isEqualTo(1);
    }

    @Test
    void absentDeriverInputIsNotFailure() {
        Map<String, Object> row = row("\"project\":{\"id\":\"p\"}");

        assertThat(row).containsEntry("success", false);
        assertThat(failures("success", "bool")).isZero();
        assertThat(failures("reasoning_tokens", "long")).isZero();
    }

    /** Only kinds that can fail register a counter, so string and object columns add no idle series. */
    @Test
    void onlyColumnsThatCanFailRegisterCounter() {
        assertThat(registry.find("sync.values.coercion.failures").tags("column", "prompt_tokens").counter()).isNotNull();
        assertThat(registry.find("sync.values.coercion.failures").tags("column", "deployment").counter()).isNull();
        assertThat(registry.find("sync.values.coercion.failures").tags("column", "_source_file").counter()).isNull();
    }

    /** One record with an unreadable field still produces its row; it never fails the record or the blob. */
    @Test
    void nonFiniteNumberFailsOnlyItsField() {
        Map<String, Object> row = row("\"token_usage\":{\"prompt_tokens\":1e400,\"total_tokens\":8},\"operation_duration_ms\":1e400");

        assertThat(row).containsEntry("prompt_tokens", null).containsEntry("total_tokens", 8L).containsEntry("operation_duration_ms", 0L);
        assertThat(failures("prompt_tokens", "long")).isEqualTo(1);
    }

    @Test
    void defaultWinsOverFailure(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mapping.yaml");
        Files.writeString(file, """
                columns:
                  - column: attempts
                    source: attempts
                    kind: long
                    default: 7
                """);
        MappingEngine withDefault = new MappingEngine(new MappingFileLoader(new DefaultResourceLoader(), transforms, derivers)
                .load("file:" + file), transforms, derivers, InlineFilesPolicy.keep(), "t", registry);

        assertThat(withDefault.toRow(jsonMapper.readTree("{\"attempts\":\"many\"}"), "f").get("attempts")).isEqualTo(7);
        assertThat(registry.find("sync.values.coercion.failures").tags("table", "t", "column", "attempts").counter().count()).isEqualTo(1);
    }
}
