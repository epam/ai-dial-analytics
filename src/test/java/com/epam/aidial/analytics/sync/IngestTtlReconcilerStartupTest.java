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
 * The bound on two set retentions fails startup from the real {@code application.yml} even with migrations
 * disabled, where nothing is reconciled: the reconciler is created, and that bound checked, whatever the migration
 * gate says.
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
    void twoSetValuesWithTheLongerPayloadFailStartupWithMigrationsDisabled() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=60", "SYNC_PAYLOAD_TTL_DAYS=90")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("sync.payload.ttl-days")
                        .hasMessageContaining("sync.usage-log.ttl-days"));
    }

    @Test
    void unlimitedPayloadAgainstSetUsageLogFailsStartup() {
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=500", "SYNC_PAYLOAD_TTL_DAYS=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void oneSetValueIsLeftToTheReconciliation() {
        // Against an unset side the bound needs the live table, which only the reconciliation reads.
        contextRunner.withSystemProperties("SYNC_USAGE_LOG_TTL_DAYS=60")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @EnableConfigurationProperties({UsageLogRetentionProperties.class, PayloadRetentionProperties.class})
    static class BindRetentionProperties {
    }
}
