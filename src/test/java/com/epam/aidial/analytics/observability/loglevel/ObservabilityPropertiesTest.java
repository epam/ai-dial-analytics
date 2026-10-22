package com.epam.aidial.analytics.observability.loglevel;

import com.epam.aidial.analytics.configuration.ObservabilityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The poll interval is bound as a {@link Duration} with a one-second floor, so a bare number (milliseconds) or a
 * sub-second value fails startup, naming the property, instead of polling the file in a tight loop.
 */
class ObservabilityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("observability.log-levels.path=/app/log-config/logging.levels.json");

    @Test
    void bindsDurationStringsAndDefaultsTheTraceSwitchOn() {
        runner.withPropertyValues("observability.log-levels.interval=10s").run(context -> {
            ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
            assertThat(properties.logLevels().interval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.logLevels().path()).isEqualTo(Path.of("/app/log-config/logging.levels.json"));
            assertThat(properties.executionTrace().enabled()).isTrue();
        });
    }

    @Test
    void oneSecondIsTheMinimum() {
        runner.withPropertyValues("observability.log-levels.interval=1s").run(context ->
                assertThat(context.getBean(ObservabilityProperties.class).logLevels().interval()).isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    void intervalBelowOneSecondFailsStartup() {
        runner.withPropertyValues("observability.log-levels.interval=500ms").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("observability")
                        .hasMessageContaining("logLevels.interval").hasMessageContaining("1 second"));
    }

    @Test
    void bareNumberIsMillisecondsAndSoFailsStartup() {
        runner.withPropertyValues("observability.log-levels.interval=10").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("observability")
                        .hasMessageContaining("logLevels.interval").hasMessageContaining("1 second"));
    }

    @Test
    void traceSwitchBindsOff() {
        runner.withPropertyValues("observability.log-levels.interval=10s", "observability.execution-trace.enabled=false").run(context ->
                assertThat(context.getBean(ObservabilityProperties.class).executionTrace().enabled()).isFalse());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ObservabilityProperties.class)
    static class PropertiesConfiguration {
    }
}
