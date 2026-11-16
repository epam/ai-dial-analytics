package com.epam.aidial.analytics.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The routing and replica keys are validated at startup; see the clickhouse-replication spec. */
class ClickHouseReplicationPropertiesTest {

    @EnableConfigurationProperties({ClickHouseRoutingProperties.class, ClickHouseReplicasProperties.class,
            ClickHouseDdlProperties.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsRouteOverReplicasWithThirtySecondBounds() {
        runner.run(context -> {
            assertThat(context.getBean(ClickHouseRoutingProperties.class).pipelines())
                    .isEqualTo(ClickHouseRoutingProperties.Route.REPLICAS);
            assertThat(context.getBean(ClickHouseReplicasProperties.class).syncTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(context.getBean(ClickHouseDdlProperties.class).taskTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void invalidRoutingValueFailsStartupNamingThePropertyAndItsValues() {
        runner.withPropertyValues("clickhouse.routing.pipelines=nearest").run(context ->
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("clickhouse.routing.pipelines")
                        .hasMessageContaining("'replicas' or 'primary'"));
    }

    @Test
    void primaryRoutingIsAccepted() {
        runner.withPropertyValues("clickhouse.routing.queries=PRIMARY").run(context ->
                assertThat(context.getBean(ClickHouseRoutingProperties.class).queries())
                        .isEqualTo(ClickHouseRoutingProperties.Route.PRIMARY));
    }

    @Test
    void durationsMustBePositiveAndTheDiscoveryIntervalAtLeastFiveSeconds() {
        assertThatThrownBy(() -> new ClickHouseReplicasProperties(Duration.ofSeconds(1), null))
                .hasMessageContaining("clickhouse.replicas.discovery-interval");
        assertThatThrownBy(() -> new ClickHouseReplicasProperties(null, Duration.ZERO))
                .hasMessageContaining("clickhouse.replicas.sync-timeout");
        assertThatThrownBy(() -> new ClickHouseReplicasProperties(null, Duration.ofMillis(500)))
                .hasMessageContaining("clickhouse.replicas.sync-timeout")
                .hasMessageContaining("at least 1s");
        assertThatThrownBy(() -> new ClickHouseDdlProperties(Duration.ofSeconds(-1)))
                .hasMessageContaining("clickhouse.ddl.task-timeout");
        assertThatThrownBy(() -> new ClickHouseDdlProperties(Duration.ofMillis(500)))
                .hasMessageContaining("clickhouse.ddl.task-timeout")
                .hasMessageContaining("at least 1s");
    }
}
