package com.epam.aidial.analytics.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code query.request-body-rebuild.*} bounds bind from the real {@code application.yml} and refuse a value
 * below 1 at startup, naming the property, rather than letting a zero bound turn every rebuild into a partial one.
 */
class RequestBodyRebuildPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindRebuildProperties.class);

    @Test
    void defaultsBindFromApplicationYaml() {
        contextRunner.run(context -> {
            RequestBodyRebuildProperties props = context.getBean(RequestBodyRebuildProperties.class);
            assertThat(props.maxRounds()).isEqualTo(8);
            assertThat(props.maxAncestors()).isEqualTo(20000);
        });
    }

    @Test
    void environmentVariablesOverrideTheDefaults() {
        contextRunner.withSystemProperties("QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS=2", "QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS=50")
                .run(context -> {
                    RequestBodyRebuildProperties props = context.getBean(RequestBodyRebuildProperties.class);
                    assertThat(props.maxRounds()).isEqualTo(2);
                    assertThat(props.maxAncestors()).isEqualTo(50);
                });
    }

    @Test
    void zeroRoundsFailsStartupNamingTheProperty() {
        contextRunner.withPropertyValues("query.request-body-rebuild.max-rounds=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("query.request-body-rebuild")
                    .hasMessageContaining("maxRounds");
        });
    }

    @Test
    void zeroAncestorsFailsStartupNamingTheProperty() {
        contextRunner.withSystemProperties("QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("query.request-body-rebuild")
                    .hasMessageContaining("maxAncestors");
        });
    }

    @EnableConfigurationProperties(RequestBodyRebuildProperties.class)
    static class BindRebuildProperties {
    }
}
