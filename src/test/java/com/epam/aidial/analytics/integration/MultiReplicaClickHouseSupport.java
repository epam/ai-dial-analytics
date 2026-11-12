package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;
import com.epam.aidial.analytics.clickhouse.replication.ReplicaAddressing;
import com.github.dockerjava.api.exception.NotModifiedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A three-replica ClickHouse cluster for the multi-replica integration tests: one standalone Keeper and servers
 * {@code chi-0}, {@code chi-1} and {@code chi-2} on one Docker network, each joining the {@code Replicated} database
 * {@link #DATABASE}. Started once, on first use, and reused for the JVM lifetime.
 *
 * <p>The replicas report the host names {@code chi-N} in {@code system.clusters}, which resolve only inside the
 * Docker network, so {@link #addressing()} maps each to its fixed host port. A fixed port, rather than
 * Testcontainers' random mapping, keeps a replica's address across a {@link #stop}/{@link #start} cycle.
 *
 * <p>It can stop and start a replica, stop and start a replica's fetches, stop and start Keeper, and point the
 * service's primary ({@code clickhouse.host}) at {@link #PRIMARY}.
 */
final class MultiReplicaClickHouseSupport {

    static final String DATABASE = "adas";
    static final List<String> REPLICAS = List.of("chi-0", "chi-1", "chi-2");
    static final String PRIMARY = "chi-0";
    private static final String IMAGE = "clickhouse/clickhouse-server:" + ClickHouseContainerSupport.VERSION;

    private static MultiReplicaClickHouseSupport instance;

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> keeper;
    private final Map<String, ClickHouseContainer> servers = new LinkedHashMap<>();
    private final Map<String, Integer> ports = new LinkedHashMap<>();

    /** A ClickHouse container whose HTTP port is bound to a fixed host port. */
    private static final class FixedPortClickHouse extends ClickHouseContainer {

        FixedPortClickHouse(int hostPort) {
            super(DockerImageName.parse(IMAGE));
            addFixedExposedPort(hostPort, ClickHouseContainerSupport.HTTP_PORT);
        }
    }

    private MultiReplicaClickHouseSupport() {
        keeper = new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-keeper:" + ClickHouseContainerSupport.VERSION))
                .withNetwork(network)
                .withNetworkAliases("keeper")
                .withCreateContainerCmdModifier(cmd -> cmd.withHostName("keeper"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse/replicas/keeper.xml"),
                        "/etc/clickhouse-keeper/keeper_config.d/keeper.xml")
                .waitingFor(Wait.forSuccessfulCommand("echo ruok | nc -w1 127.0.0.1 9181 | grep -q imok "
                        + "&& echo mntr | nc -w1 127.0.0.1 9181 | grep -q zk_server_state")
                        .withStartupTimeout(Duration.ofMinutes(1)));
        for (final String name : REPLICAS) {
            final int port = freePort();
            ports.put(name, port);
            final ClickHouseContainer server = new FixedPortClickHouse(port)
                    .withNetwork(network)
                    .withNetworkAliases(name)
                    .withCreateContainerCmdModifier(cmd -> cmd.withHostName(name))
                    .withEnv("TZ", "UTC")
                    .withEnv("MACRO_REPLICA", name)
                    .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse/encryption.xml"),
                            "/etc/clickhouse-server/config.d/encryption.xml")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse/replicas/server.xml"),
                            "/etc/clickhouse-server/config.d/replication.xml")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse/replicated-only.xml"),
                            "/etc/clickhouse-server/users.d/replicated-only.xml")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse/init-replicated-database.sql"),
                            "/docker-entrypoint-initdb.d/init-replicated-database.sql");
            servers.put(name, server);
        }
    }

    /** The started cluster. */
    static synchronized MultiReplicaClickHouseSupport get() {
        if (instance == null) {
            final MultiReplicaClickHouseSupport cluster = new MultiReplicaClickHouseSupport();
            cluster.keeper.start();
            cluster.servers.values().parallelStream().forEach(GenericContainer::start);
            instance = cluster;
        }
        return instance;
    }

    /** Wires {@code clickhouse.*} at {@link #PRIMARY}, by its IP literal so the host resolves to one address. */
    void registerProperties(DynamicPropertyRegistry registry) {
        final ClickHouseContainer primary = servers.get(PRIMARY);
        registry.add("clickhouse.host", () -> "127.0.0.1");
        registry.add("clickhouse.port", () -> ports.get(PRIMARY));
        registry.add("clickhouse.database", () -> DATABASE);
        registry.add("clickhouse.user", primary::getUsername);
        registry.add("clickhouse.password", primary::getPassword);
        registry.add("clickhouse.writer.user", primary::getUsername);
        registry.add("clickhouse.writer.password", primary::getPassword);
        registry.add("clickhouse.migrations.enabled", () -> "true");
    }

    /** Points a standalone start at {@link #PRIMARY} in {@code database}, migrations enabled. */
    void registerStandalone(StandaloneStartup startup, String database) {
        final ClickHouseContainer primary = servers.get(PRIMARY);
        startup.property("clickhouse.host", "127.0.0.1")
                .property("clickhouse.port", ports.get(PRIMARY))
                .property("clickhouse.database", database)
                .property("clickhouse.user", primary.getUsername())
                .property("clickhouse.password", primary.getPassword())
                .property("clickhouse.writer.user", primary.getUsername())
                .property("clickhouse.writer.password", primary.getPassword())
                .property("clickhouse.migrations.enabled", true)
                .property("clickhouse.ddl.task-timeout", "10s");
    }

    /** Maps each replica's reported host name to its fixed host port. */
    ReplicaAddressing addressing() {
        return (hostName, port) -> ports.containsKey(hostName)
                ? new ReplicaAddressing.Address("127.0.0.1", ports.get(hostName))
                : new ReplicaAddressing.Address(hostName, port);
    }

    /** A client on replica {@code name}, as the container user, in {@link #DATABASE}; the caller closes it. */
    Client client(String name) {
        final ClickHouseContainer server = servers.get(name);
        return new Client.Builder()
                .addEndpoint("http://127.0.0.1:" + ports.get(name) + "/")
                .setUsername(server.getUsername())
                .setPassword(server.getPassword())
                .setDefaultDatabase(DATABASE)
                .build();
    }

    /** Runs {@code sql} on replica {@code name}. */
    void execute(String name, String sql) {
        try (Client client = client(name)) {
            client.queryAll(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("statement failed on " + name + ": " + sql, ex);
        }
    }

    /** Stops replica {@code name}, keeping its data. */
    void stop(String name) {
        final ClickHouseContainer server = servers.get(name);
        server.getDockerClient().stopContainerCmd(server.getContainerId()).exec();
    }

    /** Starts a replica {@link #stop stopped} earlier, if it is, and waits until it answers. */
    void start(String name) {
        final ClickHouseContainer server = servers.get(name);
        try {
            server.getDockerClient().startContainerCmd(server.getContainerId()).exec();
        } catch (NotModifiedException alreadyRunning) {
            // Running already.
        }
        final long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                execute(name, "SELECT 1");
                return;
            } catch (IllegalStateException notYet) {
                sleep(500);
            }
        }
        throw new IllegalStateException("replica " + name + " did not come back");
    }

    /** Stops Keeper; every replica loses its session. */
    void stopKeeper() {
        keeper.getDockerClient().stopContainerCmd(keeper.getContainerId()).exec();
    }

    /** Starts Keeper again, if it is stopped. */
    void startKeeper() {
        try {
            keeper.getDockerClient().startContainerCmd(keeper.getContainerId()).exec();
        } catch (NotModifiedException alreadyRunning) {
            // Running already.
        }
    }

    /** Waits until no replica has a read-only table, as after Keeper or a replica came back. */
    void awaitWritable() {
        final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        for (final String name : REPLICAS) {
            while (true) {
                try (Client client = client(name)) {
                    if (client.queryAll("SELECT count() AS n FROM system.replicas WHERE is_readonly OR is_session_expired")
                            .getFirst().getLong("n") == 0) {
                        break;
                    }
                } catch (Exception notYet) {
                    // Still starting.
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("replica " + name + " did not become writable again");
                }
                sleep(500);
            }
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
