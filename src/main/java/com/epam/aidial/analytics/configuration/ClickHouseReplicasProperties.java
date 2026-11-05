package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Replica discovery and synchronization ({@code clickhouse.replicas.*}); see
 * {@code openspec/specs/clickhouse-replication/spec.md}.
 *
 * @param discoveryInterval how often the replica set is re-read from {@code system.clusters}; at least 5 seconds
 * @param syncTimeout       the server-side bound of one {@code SYSTEM SYNC … REPLICA}
 */
@ConfigurationProperties(prefix = "clickhouse.replicas")
public record ClickHouseReplicasProperties(Duration discoveryInterval, Duration syncTimeout) {

    /** The smallest accepted discovery interval. */
    public static final Duration MIN_DISCOVERY_INTERVAL = Duration.ofSeconds(5);

    public ClickHouseReplicasProperties {
        discoveryInterval = DurationRules.positive("clickhouse.replicas.discovery-interval", discoveryInterval,
                Duration.ofSeconds(30));
        if (discoveryInterval.compareTo(MIN_DISCOVERY_INTERVAL) < 0) {
            throw new IllegalArgumentException("clickhouse.replicas.discovery-interval must be at least "
                    + MIN_DISCOVERY_INTERVAL.toSeconds() + "s, but was " + discoveryInterval);
        }
        syncTimeout = DurationRules.positive("clickhouse.replicas.sync-timeout", syncTimeout, Duration.ofSeconds(30));
    }

    /** The shipped defaults. */
    public static ClickHouseReplicasProperties defaults() {
        return new ClickHouseReplicasProperties(null, null);
    }
}
