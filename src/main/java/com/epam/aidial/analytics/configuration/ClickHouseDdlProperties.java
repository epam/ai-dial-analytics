package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long a DDL waits for the other replicas of the {@code Replicated} database ({@code clickhouse.ddl.*}); see
 * {@code openspec/specs/clickhouse-replication/spec.md} "DDL completes while a replica is down".
 *
 * @param taskTimeout {@code distributed_ddl_task_timeout} for every DDL the service issues
 */
@ConfigurationProperties(prefix = "clickhouse.ddl")
public record ClickHouseDdlProperties(Duration taskTimeout) {

    public ClickHouseDdlProperties {
        taskTimeout = DurationRules.positive("clickhouse.ddl.task-timeout", taskTimeout, Duration.ofSeconds(30));
        if (taskTimeout.toSeconds() < 1) {
            throw new IllegalArgumentException("clickhouse.ddl.task-timeout must be at least 1s, but was " + taskTimeout);
        }
    }

    /** The shipped default. */
    public static ClickHouseDdlProperties defaults() {
        return new ClickHouseDdlProperties(null);
    }
}
