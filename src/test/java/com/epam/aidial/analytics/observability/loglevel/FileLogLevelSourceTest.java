package com.epam.aidial.analytics.observability.loglevel;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.configuration.ObservabilityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FileLogLevelSourceTest {

    @TempDir
    Path dir;

    private FileLogLevelSource source(Path file) {
        return new FileLogLevelSource(
                new ObservabilityProperties(new ObservabilityProperties.LogLevels(file, Duration.ofSeconds(10)), null),
                new JsonMapperConfiguration().jsonMapper());
    }

    @Test
    void parsesTheSharedFormat() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), """
                {"loggerLevels": {
                  "com.epam.aidial.analytics.service.enrichment": {"defaultLevel": "INFO", "configuredLevel": "DEBUG", "validTill": 1790000000000},
                  "org.springframework": {"configuredLevel": "warn"}
                }}""");

        LogLevelSnapshot snapshot = source(file).read().orElseThrow();

        assertThat(snapshot.parsed()).isTrue();
        assertThat(snapshot.loggerLevels()).containsEntry("com.epam.aidial.analytics.service.enrichment", new LoggerLevelEntry("INFO", "DEBUG", 1790000000000L))
                .containsEntry("org.springframework", new LoggerLevelEntry(null, "warn", null));
    }

    @Test
    void sameBytesHashTheSameAndDifferentBytesDiffer() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), "{\"loggerLevels\": {}}");
        String first = source(file).read().orElseThrow().hash();
        String again = source(file).read().orElseThrow().hash();
        Files.writeString(file, "{\"loggerLevels\": {\"a\": {\"configuredLevel\": \"WARN\"}}}");
        String changed = source(file).read().orElseThrow().hash();

        assertThat(again).isEqualTo(first);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    void missingFileReadsAsEmpty() {
        assertThat(source(dir.resolve("absent.json")).read()).isEmpty();
    }

    @Test
    void unparsableContentStillHasItsHash() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), "{not json");

        LogLevelSnapshot snapshot = source(file).read().orElseThrow();

        assertThat(snapshot.parsed()).isFalse();
        assertThat(snapshot.hash()).isNotBlank();
        assertThat(snapshot.loggerLevels()).isNull();
    }

    @Test
    void documentWithoutLoggerLevelsIsEmpty() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), "{}");

        assertThat(source(file).read().orElseThrow().loggerLevels()).isEmpty();
    }
}
