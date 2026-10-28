package com.epam.aidial.analytics.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@code sync.payload.*} from the real {@code application.yml}, so the defaults and the rejected values are
 * the ones a deployment meets.
 */
class PayloadRetentionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindPayloadRetentionProperties.class);

    @Test
    void defaultsToNinetyDaysOnThePayloadTable() {
        contextRunner.run(context -> {
            final PayloadRetentionProperties props = context.getBean(PayloadRetentionProperties.class);
            assertThat(props.ttlDays()).isEqualTo(90);
            assertThat(props.table()).isEqualTo("dial_usage_log_payload");
            assertThat(props.noTtl()).isFalse();
        });
    }

    @Test
    void followsTheRenamedPayloadTable() {
        contextRunner.withSystemProperties("SYNC_PAYLOAD_TABLE=payload_copy")
                .run(context -> assertThat(context.getBean(PayloadRetentionProperties.class).table()).isEqualTo("payload_copy"));
    }

    @Test
    void acceptsOneDay() {
        contextRunner.withSystemProperties("SYNC_PAYLOAD_TTL_DAYS=1")
                .run(context -> assertThat(context.getBean(PayloadRetentionProperties.class).ttlDays()).isEqualTo(1));
    }

    @Test
    void minusOneMeansNoTtl() {
        contextRunner.withSystemProperties("SYNC_PAYLOAD_TTL_DAYS=-1")
                .run(context -> assertThat(context.getBean(PayloadRetentionProperties.class).noTtl()).isTrue());
    }

    @Test
    void zeroFailsStartupNamingTheProperty() {
        contextRunner.withSystemProperties("SYNC_PAYLOAD_TTL_DAYS=0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("sync.payload.ttl-days"));
    }

    @Test
    void belowMinusOneFailsStartup() {
        contextRunner.withSystemProperties("SYNC_PAYLOAD_TTL_DAYS=-2")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nonIntegerFailsStartup() {
        contextRunner.withSystemProperties("SYNC_PAYLOAD_TTL_DAYS=30d")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("sync.payload.ttl-days"));
    }

    @EnableConfigurationProperties(PayloadRetentionProperties.class)
    static class BindPayloadRetentionProperties {
    }
}
