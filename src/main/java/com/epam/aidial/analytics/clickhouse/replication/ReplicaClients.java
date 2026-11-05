package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.clickhouse.ClickHouseClients;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * The two driver clients of one non-primary replica — the read credential's and the writer credential's — built on
 * first use through {@link ClickHouseClients}, so the transport policy every client shares holds here too. Closed
 * when the replica leaves the discovered set.
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
        Client local = read;
        if (local == null) {
            synchronized (this) {
                local = read;
                if (local == null) {
                    local = ClickHouseClients.builder(connection, host, port, connection.user(), connection.password()).build();
                    read = local;
                }
            }
        }
        return local;
    }

    Client write() {
        Client local = write;
        if (local == null) {
            synchronized (this) {
                local = write;
                if (local == null) {
                    local = ClickHouseClients.builder(connection, host, port, writer.user(), writer.password()).build();
                    write = local;
                }
            }
        }
        return local;
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
