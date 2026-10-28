package com.epam.aidial.analytics.sync;

import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.configuration.PayloadRetentionProperties;
import com.epam.aidial.analytics.configuration.UsageLogRetentionProperties;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The payload bound fails startup from the real {@code application.yml} even with migrations disabled, where
 * nothing is reconciled: the reconciler is created, and the bound checked, whatever the migration gate says.
 */
class IngestTtlReconcilerStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindRetentionProperties.class, IngestTtlReconciler.class)
            .withBean(ClickHouseTableInspector.class, () -> mock(ClickHouseTableInspector.class))
            .withBean(DdlExecutor.class, () -> mock(DdlExecutor.class))
            .withBean(TableDdlRenderer.class, TableDdlRenderer::new)
            .withPropertyValues("clickhouse.migrations.enabled=false");

    @Test
    void defaultsStart() {
        contextRunner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(IngestTtlReconciler.class));
    }

    @Test
    void payloadLongerThanUsageLogFailsStartupWithMigrationsDisabled() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=60")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("sync.payload.ttl-days")
                        .hasMessageContaining("sync.usage-log.ttl-days"));
    }

    @Test
    void unlimitedPayloadAloneFailsStartup() {
        contextRunner.withSystemProperties("SYNC_PAYLOAD_TTL_DAYS=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties({UsageLogRetentionProperties.class, PayloadRetentionProperties.class})
    static class BindRetentionProperties {
    }
}
