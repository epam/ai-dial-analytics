package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.clickhouse.ClickHouseClients;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.net.ConnectException;

/**
 * The two driver clients of one non-primary replica — the read credential's and the writer credential's — built on
 * first use through {@link ClickHouseClients}, so the transport policy every client shares holds here too. Closed
 * when the replica is no longer registered (or moves host); a closed holder answers unavailable.
 */
@Slf4j
final class ReplicaClients implements AutoCloseable {

    private final String host;
    private final int port;
    private final ClickHouseProperties connection;
    private final ClickHouseWriterProperties writer;
    private volatile Client read;
    private volatile Client write;
    private volatile boolean closed;

    ReplicaClients(ReplicaAddressing.Address address, ClickHouseProperties connection, ClickHouseWriterProperties writer) {
        this.host = address.host();
        this.port = address.port();
        this.connection = connection;
        this.writer = writer;
    }

    String host() {
        return host;
    }

    Client read() {
        requireOpen();
        Client local = read;
        if (local == null) {
            synchronized (this) {
                local = read;
                if (local == null) {
                    requireOpen();
                    local = ClickHouseClients.builder(connection, host, port, connection.user(), connection.password()).build();
                    read = local;
                }
            }
        }
        return local;
    }

    Client write() {
        requireOpen();
        Client local = write;
        if (local == null) {
            synchronized (this) {
                local = write;
                if (local == null) {
                    requireOpen();
                    local = ClickHouseClients.builder(connection, host, port, writer.user(), writer.password()).build();
                    write = local;
                }
            }
        }
        return local;
    }

    /**
     * Refuses a closed holder as unavailable (503), never as a statement error, and never rebuilds a client. The cause
     * is a {@link ConnectException}, so {@code ClickHouseFailures} classifies it as a connection that could not be made:
     * the statement wrappers keep the 503 when they re-wrap it, and a user query re-runs on the primary.
     */
    private void requireOpen() {
        if (closed) {
            final String message = "ClickHouse replica " + host + " left the routing set";
            throw new ClickHouseUnavailableException(message, new ConnectException(message));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (final Client client : new Client[] {read, write}) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ex) {
                    log.debug("Error closing a ClickHouse replica client for {}", host, ex);
                }
            }
        }
    }
}
