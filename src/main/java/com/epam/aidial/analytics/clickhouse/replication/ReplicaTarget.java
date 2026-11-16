package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;

import java.util.Locale;

/**
 * The replica a unit of work executes on, with the two driver clients that reach it: the read credential's and the
 * writer credential's. Bound for the duration of the work through {@link ReplicaScope}, so every statement the work
 * issues, on either credential, executes there.
 */
public interface ReplicaTarget {

    /** The route that chose this replica, for the statement meters. */
    enum Route {
        /** The primary, by rule or by default. */
        PRIMARY,
        /** A pipeline run's home. */
        PIPELINE,
        /** A user query's replica. */
        QUERY;

        /** The meter tag value. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The replica's name; {@code primary} until discovery has named the primary. */
    String name();

    /** Whether this is the primary, {@code clickhouse.host}. */
    boolean primary();

    /** The route that chose it. */
    Route route();

    /** A client on the read credential. */
    Client readClient();

    /** A client on the writer credential. */
    Client writerClient();

    /** Reports that a statement could not connect to the replica, so this instance stops routing to it. */
    void unreachable();
}
