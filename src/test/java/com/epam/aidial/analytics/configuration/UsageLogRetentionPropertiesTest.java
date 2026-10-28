package com.epam.aidial.analytics.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@code sync.usage-log.*} from the real {@code application.yml}, so the defaults and the rejected values are
 * the ones a deployment meets.
 */
class UsageLogRetentionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindUsageLogRetentionProperties.class);

    @Test
    void unsetKeepsTheProvisionedYearOnTheUsageLog() {
        contextRunner.run(context -> {
            final UsageLogRetentionProperties props = context.getBean(UsageLogRetentionProperties.class);
            assertThat(props.ttlDays()).isNull();
            assertThat(props.provisionedYear()).isTrue();
            assertThat(props.noTtl()).isFalse();
            assertThat(props.table()).isEqualTo("dial_usage_log");
        });
    }

    @Test
    void followsTheRenamedUsageLogTable() {
        contextRunner.withSystemProperties("SYNC_TABLE=usage_copy")
                .run(context -> assertThat(context.getBean(UsageLogRetentionProperties.class).table()).isEqualTo("usage_copy"));
    }

    @Test
    void blankTableMeansTheDefault() {
        assertThat(new UsageLogRetentionProperties(null, "  ").table()).isEqualTo(UsageLogRetentionProperties.DEFAULT_TABLE);
    }

    @Test
    void acceptsDayCount() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=180")
                .run(context -> {
                    final UsageLogRetentionProperties props = context.getBean(UsageLogRetentionProperties.class);
                    assertThat(props.ttlDays()).isEqualTo(180);
                    assertThat(props.provisionedYear()).isFalse();
                });
    }

    @Test
    void minusOneMeansNoTtl() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=-1")
                .run(context -> assertThat(context.getBean(UsageLogRetentionProperties.class).noTtl()).isTrue());
    }

    @Test
    void zeroFailsStartupNamingTheProperty() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("sync.usage-log.ttl-days"));
    }

    @Test
    void belowMinusOneFailsStartup() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=-2")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nonIntegerFailsStartup() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=1y")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("sync.usage-log.ttl-days"));
    }

    @EnableConfigurationProperties(UsageLogRetentionProperties.class)
    static class BindUsageLogRetentionProperties {
    }
}
