package com.epam.aidial.analytics.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Diagnostics switches ({@code observability.*}): the runtime per-logger level file and the execution trace.
 * Values are env-driven via {@code application.yml}.
 *
 * @param logLevels      the mounted level file polled for per-logger levels; see {@link LogLevels}
 * @param executionTrace the {@code @LogExecution} trace advice; see {@link ExecutionTrace}
 */
@Validated
@ConfigurationProperties(prefix = "observability")
public record ObservabilityProperties(
        @NotNull @Valid LogLevels logLevels,
        @Valid ExecutionTrace executionTrace) {

    public ObservabilityProperties {
        executionTrace = executionTrace == null ? new ExecutionTrace(true) : executionTrace;
    }

    /**
     * The runtime level file.
     *
     * <p>{@code interval} is also read as a placeholder by the poller's {@code @Scheduled}; it is bound here so a
     * value below one second fails startup instead of polling the file in a tight loop. A bare number is
     * milliseconds, which is the usual way to end up there.
     *
     * @param path     the JSON level file, typically a mounted ConfigMap
     * @param interval how often the file is polled, at least one second
     */
    public record LogLevels(
            @NotNull Path path,
            @NotNull @DurationMin(seconds = 1) Duration interval) {
    }

    /**
     * The execution trace advice.
     *
     * @param enabled whether {@code @LogExecution} components are proxied for tracing at all
     */
    public record ExecutionTrace(boolean enabled) {
    }
}
