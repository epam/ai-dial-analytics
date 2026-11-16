package com.epam.aidial.analytics.clickhouse.replication;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * The two HTTP probes discovery needs that the driver client cannot make: a replica's {@code /replicas_status}, and a
 * query over a <em>fresh</em> connection. The driver pools keep-alive connections, so it cannot tell whether a host
 * name spreads new connections over several servers; each probe here opens its own connection and asks the server to
 * close it.
 */
class ReplicaHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** What a replica's {@code /replicas_status} answered. */
    enum Status {
        /** {@code 200}: every replicated table within the server's lag threshold. */
        HEALTHY,
        /** Any other answer: the server is up, but some replicated table lags. */
        LAGGING,
        /** No answer at all. */
        UNREACHABLE
    }

    /** What the server at {@code host:port} reports on {@code /replicas_status}. */
    Status status(String host, int port) {
        try {
            final HttpURLConnection connection = open(URI.create("http://" + host + ":" + port + "/replicas_status"));
            try {
                return connection.getResponseCode() == HttpURLConnection.HTTP_OK ? Status.HEALTHY : Status.LAGGING;
            } finally {
                drain(connection);
                connection.disconnect();
            }
        } catch (IOException | IllegalArgumentException ex) {
            return Status.UNREACHABLE;
        }
    }

    /**
     * The database replica name of the server that answers a fresh connection to {@code host:port}, for the
     * {@code Replicated} database {@code database}; empty when it could not be asked.
     */
    Optional<String> answeringReplica(String host, int port, String user, String password, String database) {
        final String sql = "SELECT database_replica_name FROM system.clusters WHERE cluster = {db:String} AND is_local "
                + "AND database_replica_name != '' LIMIT 1 FORMAT TabSeparated";
        try {
            final URI uri = URI.create("http://" + host + ":" + port + "/?param_db="
                    + URLEncoder.encode(database, StandardCharsets.UTF_8));
            final HttpURLConnection connection = open(uri);
            try {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("X-ClickHouse-User", user == null ? "default" : user);
                connection.setRequestProperty("X-ClickHouse-Key", password == null ? "" : password);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(sql.getBytes(StandardCharsets.UTF_8));
                }
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return Optional.empty();
                }
                try (InputStream in = connection.getInputStream()) {
                    final String name = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
                    return name.isEmpty() ? Optional.empty() : Optional.of(name);
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout((int) TIMEOUT.toMillis());
        connection.setReadTimeout((int) TIMEOUT.toMillis());
        connection.setUseCaches(false);
        connection.setRequestProperty("Connection", "close");
        return connection;
    }

    private static void drain(HttpURLConnection connection) {
        try (InputStream in = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            if (in != null) {
                in.readAllBytes();
            }
        } catch (IOException ignored) {
            // Nothing to drain from a failed probe.
        }
    }
}
