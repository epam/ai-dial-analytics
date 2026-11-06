package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Replica discovery and synchronization ({@code clickhouse.replicas.*}); see
 * {@code openspec/specs/clickhouse-replication/spec.md}.
 *
 * @param discoveryInterval how often the replica set is re-read from {@code system.clusters}; at least 5 seconds
 * @param syncTimeout       the server-side bound of one {@code SYSTEM SYNC … REPLICA}; at least 1 second
 */
@ConfigurationProperties(prefix = "clickhouse.replicas")
public record ClickHouseReplicasProperties(Duration discoveryInterval, Duration syncTimeout) {

    /** The smallest accepted discovery interval. */
    public static final Duration MIN_DISCOVERY_INTERVAL = Duration.ofSeconds(5);

    public ClickHouseReplicasProperties {
        discoveryInterval = DurationRules.atLeast("clickhouse.replicas.discovery-interval", discoveryInterval,
                Duration.ofSeconds(30), MIN_DISCOVERY_INTERVAL);
        // Applied as receive_timeout in whole seconds, so a sub-second bound is refused rather than rounded up.
        syncTimeout = DurationRules.atLeast("clickhouse.replicas.sync-timeout", syncTimeout, Duration.ofSeconds(30),
                Duration.ofSeconds(1));
    }

    /** The shipped defaults. */
    public static ClickHouseReplicasProperties defaults() {
        return new ClickHouseReplicasProperties(null, null);
    }
}
