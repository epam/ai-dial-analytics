package com.epam.aidial.analytics.clickhouse.replication;

/**
 * One replica of the session database, as the {@code Replicated} database reports it in {@code system.clusters}.
 *
 * @param name    the database replica name ({@code database_replica_name}), unique within the database
 * @param host    the host name the replica is reached at, on {@code clickhouse.port}
 * @param primary whether this is the replica {@code clickhouse.host} reaches ({@code is_local})
 * @param active  whether Keeper reports the replica active
 * @param healthy whether its {@code /replicas_status} answered healthy at the latest refresh
 */
public record Replica(String name, String host, boolean primary, boolean active, boolean healthy) {
}
