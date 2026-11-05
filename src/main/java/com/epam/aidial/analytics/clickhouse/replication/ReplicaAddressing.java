package com.epam.aidial.analytics.clickhouse.replication;

/**
 * Where a discovered replica is reached: by default the {@code host_name} it reports in {@code system.clusters}, on
 * {@code clickhouse.port}, which is why every replica must serve HTTP on one port under a resolvable name
 * ({@code docs/clickhouse-replication.md}). A bean of this type replaces the default where those names resolve only
 * inside another network, as in the multi-replica tests.
 */
@FunctionalInterface
public interface ReplicaAddressing {

    /** The host and HTTP port of the replica that reports {@code hostName}; {@code port} is {@code clickhouse.port}. */
    Address address(String hostName, int port);

    /** A host and HTTP port. */
    record Address(String host, int port) {}

    /** The default: the reported host name on {@code clickhouse.port}. */
    ReplicaAddressing REPORTED = Address::new;
}
