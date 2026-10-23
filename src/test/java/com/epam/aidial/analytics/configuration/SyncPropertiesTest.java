package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.inline.InlineFilesPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code sync.max-batch-bytes} and {@code sync.max-line-bytes} bounds enforced at startup via {@link jakarta.validation}:
 * a value below 1 MiB (which would flush every row) or above {@code Integer.MAX_VALUE / 6} (which could
 * let the real serialized payload exceed the JVM array-length ceiling) is rejected, so the context fails
 * fast rather than the ingest degrading at runtime.
 *
 * <p>Also binds the real {@code application.yml} for the two key-layout patterns, which no test
 * constructing {@link SyncProperties} directly can cover: their defaults have to survive YAML parsing and
 * placeholder resolution ({@code 'date='yyyy-MM-dd} carries quotes a plain scalar could mangle), and a
 * deployment that needs no file-name head configures it as the <em>empty string</em> — so an empty value
 * has to win over the default rather than fall back to it.
 */
class SyncPropertiesTest {

    private static final long MIN = 1024L * 1024L;
    private static final long MAX = Integer.MAX_VALUE / 6;

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindSyncProperties.class);

    @Test
    void keyLayoutDefaultsBindFromApplicationYaml() {
        contextRunner.run(context -> {
            SyncProperties props = context.getBean(SyncProperties.class);
            assertThat(props.pathPrefix()).isEqualTo("logs");
            assertThat(props.datePathPattern()).isEqualTo("yyyy/MM/dd");
            // The quotes are the pattern's own (a DateTimeFormatter literal), and must reach the binder intact.
            assertThat(props.fileNamePrefixPattern()).isEqualTo("'date='yyyy-MM-dd");
        });
    }

    @Test
    void emptyFileNameHeadVariableWinsOverTheDefault() {
        // How a producer writing `<epoch>-<uuid>.log.gz` is configured: the variable is set and empty. If an
        // empty value fell back to the default instead, that deployment could not be expressed at all.
        //
        // Set directly rather than through withSystemProperties, which discards a pair whose value is empty
        // (TestPropertyValues.Pair.of returns null for one) and so would test the harness, not the resolution.
        System.setProperty("SYNC_FILE_NAME_PREFIX_PATTERN", "");
        try {
            contextRunner.run(context ->
                    assertThat(context.getBean(SyncProperties.class).fileNamePrefixPattern()).isEmpty());
        } finally {
            System.clearProperty("SYNC_FILE_NAME_PREFIX_PATTERN");
        }
    }

    @Test
    void fileNameHeadVariableIsHonouredWhenSet() {
        contextRunner.withSystemProperties("SYNC_FILE_NAME_PREFIX_PATTERN='usage-'")
                .run(context -> assertThat(context.getBean(SyncProperties.class).fileNamePrefixPattern())
                        .isEqualTo("'usage-'"));
    }

    @Test
    void inlineFilesSettingsDefaultToTheRepairWithNoLimit() {
        contextRunner.run(context ->
                assertThat(context.getBean(SyncProperties.class).inlineFilesPolicy()).isEqualTo(InlineFilesPolicy.defaults()));
    }

    @Test
    void inlineFilesRepairCanBeTurnedOff() {
        contextRunner.withSystemProperties("SYNC_INLINE_FILES_DROP_TRUNCATED=false")
                .run(context ->
                        assertThat(context.getBean(SyncProperties.class).inlineFilesPolicy()).isEqualTo(InlineFilesPolicy.keep()));
    }

    @Test
    void inlineFilesMaxSizeBindsDataSizeWithBinaryUnits() {
        contextRunner.withSystemProperties("SYNC_INLINE_FILES_MAX_SIZE=256KB")
                .run(context -> assertThat(context.getBean(SyncProperties.class).inlineFilesPolicy())
                        .isEqualTo(new InlineFilesPolicy(true, 256 * 1024)));
    }

    @Test
    void inlineFilesMaxSizeReadsBareNumberAsBytes() {
        contextRunner.withSystemProperties("SYNC_INLINE_FILES_MAX_SIZE=2048")
                .run(context -> assertThat(context.getBean(SyncProperties.class).inlineFilesPolicy().maxFileSize()).isEqualTo(2048));
    }

    @Test
    void inlineFilesMaxSizeOfZeroRemovesEveryFile() {
        contextRunner.withSystemProperties("SYNC_INLINE_FILES_MAX_SIZE=0")
                .run(context ->
                        assertThat(context.getBean(SyncProperties.class).inlineFilesPolicy()).isEqualTo(InlineFilesPolicy.dropAll()));
    }

    @Test
    void negativeInlineFilesMaxSizeFailsStartup() {
        contextRunner.withSystemProperties("SYNC_INLINE_FILES_MAX_SIZE=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void inlineFilesMaxSizeThatIsNotSizeFailsStartup() {
        contextRunner.withSystemProperties("SYNC_INLINE_FILES_MAX_SIZE=big")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void removedInlineFilesModeVariableHasNoEffect() {
        // The old three-valued setting is gone: a value left in the environment binds to nothing.
        contextRunner.withSystemProperties("SYNC_INLINE_FILES_MODE=drop")
                .run(context ->
                        assertThat(context.getBean(SyncProperties.class).inlineFilesPolicy()).isEqualTo(InlineFilesPolicy.defaults()));
    }

    @Test
    void unsetInlineFilesSettingsAreTheDefaults() {
        assertThat(properties(10000, 134_217_728L).inlineFilesPolicy()).isEqualTo(InlineFilesPolicy.defaults());
    }

    @Test
    void requestBodyModeDefaultsToWhole() {
        contextRunner.run(context ->
                assertThat(context.getBean(SyncProperties.class).requestBodyMode()).isEqualTo(RequestBodyMode.WHOLE));
        assertThat(properties(10000, 134_217_728L).requestBodyMode()).isEqualTo(RequestBodyMode.WHOLE);
    }

    @Test
    void requestBodyModeBindsDeltaFromTheEnvironment() {
        contextRunner.withSystemProperties("SYNC_REQUEST_BODY_MODE=delta")
                .run(context ->
                        assertThat(context.getBean(SyncProperties.class).requestBodyMode()).isEqualTo(RequestBodyMode.DELTA));
    }

    @Test
    void unknownRequestBodyModeFailsStartupNamingTheSetting() {
        // A typo must not silently mean whole (or delta): the context refuses to start.
        contextRunner.withSystemProperties("SYNC_REQUEST_BODY_MODE=compact")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("sync.request-body-mode").hasStackTraceContaining("compact"));
    }

    @EnableConfigurationProperties(SyncProperties.class)
    static class BindSyncProperties {
    }

    private static SyncProperties withMaxBatchBytes(long maxBatchBytes) {
        return properties(10000, maxBatchBytes);
    }

    private static SyncProperties properties(int batchSize, long maxBatchBytes) {
        return properties(batchSize, maxBatchBytes, SyncProperties.DEFAULT_MAX_LINE_BYTES);
    }

    private static SyncProperties properties(int batchSize, long maxBatchBytes, int maxLineBytes) {
        return new SyncProperties(
                "0 0 1 * * *", 1, batchSize, maxBatchBytes, maxLineBytes, "logs", "yyyy/MM/dd", "'date='yyyy-MM-dd", true,
                List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")), null, null, null);
    }

    private static Set<ConstraintViolation<SyncProperties>> violationsFor(long maxBatchBytes) {
        return validator.validate(withMaxBatchBytes(maxBatchBytes));
    }

    @Test
    void acceptsTheDefaultAndBothBounds() {
        assertThat(violationsFor(134_217_728L)).isEmpty(); // the configured default (128 MiB)
        assertThat(violationsFor(MIN)).isEmpty();
        assertThat(violationsFor(MAX)).isEmpty();
    }

    @Test
    void rejectsValuesBelowTheMinimum() {
        assertThat(violationsFor(0L)).isNotEmpty();      // would flush every row -> one part per blob
        assertThat(violationsFor(MIN - 1)).isNotEmpty();
    }

    @Test
    void rejectsValuesAboveTheMaximum() {
        assertThat(violationsFor(MAX + 1)).isNotEmpty();
        assertThat(violationsFor(Long.MAX_VALUE)).isNotEmpty(); // unbounded -> could overflow the array ceiling
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThat(validator.validate(properties(1, 268_435_456L))).isEmpty();
        assertThat(validator.validate(properties(0, 268_435_456L))).isNotEmpty();  // never accumulates a batch
        assertThat(validator.validate(properties(-1, 268_435_456L))).isNotEmpty();
    }

    @Test
    void maxLineBytesDefaultBindsFromApplicationYaml() {
        contextRunner.run(context ->
                assertThat(context.getBean(SyncProperties.class).maxLineBytes()).isEqualTo(SyncProperties.DEFAULT_MAX_LINE_BYTES));
    }

    @Test
    void acceptsMaxLineBytesAtBothBounds() {
        assertThat(validator.validate(properties(10000, 268_435_456L, 1024))).isEmpty();
        assertThat(validator.validate(properties(10000, 268_435_456L, SyncProperties.MAX_LINE_BYTES_LIMIT))).isEmpty();
    }

    @Test
    void rejectsMaxLineBytesOutsideTheBounds() {
        assertThat(validator.validate(properties(10000, 268_435_456L, 1023))).isNotEmpty();
        assertThat(validator.validate(properties(10000, 268_435_456L, 0))).isNotEmpty();
        assertThat(validator.validate(properties(10000, 268_435_456L, SyncProperties.MAX_LINE_BYTES_LIMIT + 1))).isNotEmpty();
        assertThat(validator.validate(properties(10000, 268_435_456L, Integer.MAX_VALUE))).isNotEmpty();
    }

    @Test
    void rejectsEmptyOrBlankTargets() {
        SyncProperties noTargets = new SyncProperties(
                "0 0 1 * * *", 1, 10000, 268_435_456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, "logs", "yyyy/MM/dd", "'date='yyyy-MM-dd", true, List.of(), null, null, null);
        assertThat(validator.validate(noTargets)).isNotEmpty();  // at least one target is required

        SyncProperties blankTable = new SyncProperties(
                "0 0 1 * * *", 1, 10000, 268_435_456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, "logs", "yyyy/MM/dd", "'date='yyyy-MM-dd", true,
                List.of(new SyncProperties.Target("", "classpath:mapping/dial-usage-log.yaml")), null, null, null);
        assertThat(validator.validate(blankTable)).isNotEmpty();  // a target's table must be non-blank
    }
}
