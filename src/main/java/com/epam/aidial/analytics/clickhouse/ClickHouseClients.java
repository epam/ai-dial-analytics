package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;

/**
 * The one place a ClickHouse {@link Client} is configured, so a setting that must hold for every connection
 * is written once instead of four times.
 *
 * <p>Four components open their own client — the read wrapper, the writer/DDL client, the usage-log batch
 * writer and the catalog row writer — because they authenticate as different credentials and carry
 * different per-statement settings. What they share is the endpoint, the credential handling, the default
 * database, and the transport policy, and that shared part had been copied verbatim four times.
 *
 * <p><b>Why this exists rather than four copies.</b> Twice now a cross-cutting setting has been added to
 * "the ClickHouse clients" and reached only some of them: {@code async_insert} is pinned on two of the
 * three writers, and the retry policy below was first applied to two of the four. A missed copy is silent —
 * the client still works, it just does not carry the setting — which is the failure mode this class
 * removes. Anything that must be true of every connection belongs here; anything specific to one
 * statement shape stays chained at its call site.
 *
 * @see ClickHouseFailures#transportFaults() the retry policy applied to every client
 */
public final class ClickHouseClients {

    private ClickHouseClients() {
    }

    /**
     * A builder pointed at the configured server as {@code user}, with the shared transport policy applied.
     * The caller chains its own {@code serverSetting}s and calls {@code build()}.
     *
     * <p>The credential is passed in rather than read from {@code connection} because the read and write
     * paths deliberately use different ones — the split is a security boundary, not a convenience.
     */
    public static Client.Builder builder(ClickHouseProperties connection, String user, String password) {
        return builder(connection, connection.host(), connection.port(), user, password);
    }

    /**
     * {@link #builder(ClickHouseProperties, String, String)} pointed at {@code host:port} instead of
     * {@code clickhouse.host}: how a discovered replica is reached. Everything else — credential handling,
     * default database, transport policy — is the primary's, so the shared policy holds for every replica.
     */
    public static Client.Builder builder(ClickHouseProperties connection, String host, int port, String user, String password) {
        return new Client.Builder()
                .addEndpoint("http://" + host + ":" + port + "/")
                // Null means "unset, fall back to ClickHouse's own default account"; an explicitly empty
                // value is a real (if odd) credential and is passed through as written.
                .setUsername(user == null ? "default" : user)
                .setPassword(password == null ? "" : password)
                .setDefaultDatabase(connection.sessionDatabase())
                .retryOnFailures(ClickHouseFailures.transportFaults());
    }
}
