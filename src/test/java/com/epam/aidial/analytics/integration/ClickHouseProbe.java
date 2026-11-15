package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;

/**
 * Direct ClickHouse access for the {@code oidc}-mode integration tests, whose base class has no write client: seeding
 * the ingest tables no API inserts into, and reading a table's live DDL to prove a refused request issued no
 * {@code ALTER}.
 */
final class ClickHouseProbe {

    private ClickHouseProbe() {
    }

    /** The table's live DDL ({@code SHOW CREATE TABLE}). */
    static String showCreateTable(String table) {
        try (Client client = client()) {
            return client.queryAll("SHOW CREATE TABLE " + table).getFirst().getString(1);
        }
    }

    /** Runs one statement. */
    static void execute(String sql) {
        try (Client client = client()) {
            client.queryAll(sql);
        }
    }

    private static Client client() {
        final var container = ClickHouseContainerSupport.CLICKHOUSE;
        return new Client.Builder()
                .addEndpoint("http://" + container.getHost() + ":" + container.getMappedPort(ClickHouseContainerSupport.HTTP_PORT) + "/")
                .setUsername(container.getUsername())
                .setPassword(container.getPassword())
                .setDefaultDatabase(container.getDatabaseName())
                .build();
    }
}
