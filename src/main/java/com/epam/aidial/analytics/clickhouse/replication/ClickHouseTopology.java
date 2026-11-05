package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.ClickHouseWriterClient;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseReplicasProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The replicas of the session database, discovered from ClickHouse rather than configured.
 *
 * <p>A {@code Replicated} database registers itself in {@code system.clusters} as a cluster named after the database;
 * its rows carry the {@code database_replica_name} and a non-null {@code is_active}. A {@code remote_servers} cluster
 * that happens to share the name carries neither, so it is ignored. The rows are read through the primary
 * ({@code clickhouse.host}) on the writer credential at startup and every {@code clickhouse.replicas.discovery-interval}.
 *
 * <ul>
 *   <li>A replica is a candidate while the row reports it active; it is reached at its {@code host_name} on
 *       {@code clickhouse.port}, with the primary's credentials.</li>
 *   <li>The primary is the row reported as local through {@code clickhouse.host}, and is always reached through
 *       {@code clickhouse.host} itself.</li>
 *   <li>A replica's user-read health is its own {@code /replicas_status}.</li>
 *   <li>A replica this instance could not connect to is excluded until the next refresh.</li>
 *   <li>A failed refresh keeps the last set; until a refresh succeeds every route resolves to the primary.</li>
 * </ul>
 *
 * <p>At startup, while the database has more than one active replica, {@code clickhouse.host} must reach exactly
 * one server: it may neither resolve to several addresses nor be answered by different replicas over fresh
 * connections. Later refreshes only WARN and count either condition, because a failover looks the same. See
 * {@code openspec/specs/clickhouse-replication/spec.md} "The replica set is discovered from the database's own cluster".
 */
@Slf4j
@Component
public class ClickHouseTopology {

    /** Fresh connections the startup identity check opens to {@code clickhouse.host}. */
    static final int IDENTITY_PROBES = 4;

    /** The cluster rows the {@code Replicated} database contributes for itself. */
    static final String CLUSTER_ROWS = "SELECT database_replica_name AS name, host_name AS host, is_local AS local, "
            + "is_active AS active FROM system.clusters WHERE cluster = {db:String} AND database_replica_name != '' "
            + "AND is_active IS NOT NULL ORDER BY name";

    /** One registered replica, active or not. */
    public record Registered(String name, String host, boolean local, boolean active) {}

    /** What the latest successful refresh found; {@code discovered} is false until one has. */
    record Snapshot(boolean discovered, String primaryName, List<Replica> replicas) {

        static Snapshot unknown() {
            return new Snapshot(false, "primary", List.of());
        }
    }

    private final ClickHouseProperties connection;
    private final ClickHouseWriterProperties writer;
    private final ClickHouseReplicasProperties replicasProperties;
    private final ClickHouseWriterClient writerClient;
    private final ClickHouseClientWrapper readClient;
    private final ReplicaMeters meters;
    private final ReplicaHttp http;
    private final ReplicaAddressing addressing;
    private final Function<String, List<String>> resolver;
    private final Set<String> excluded = ConcurrentHashMap.newKeySet();
    private final Map<String, ReplicaClients> clients = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = Snapshot.unknown();
    private volatile ScheduledExecutorService refresher;

    @Autowired
    public ClickHouseTopology(ClickHouseProperties connection, ClickHouseWriterProperties writer,
            ClickHouseReplicasProperties replicasProperties, ClickHouseWriterClient writerClient,
            ClickHouseClientWrapper readClient, ReplicaMeters meters, ObjectProvider<ReplicaAddressing> addressing) {
        this(connection, writer, replicasProperties, writerClient, readClient, meters, new ReplicaHttp(),
                addressing.getIfAvailable(() -> ReplicaAddressing.REPORTED), ClickHouseTopology::addresses);
    }

    ClickHouseTopology(ClickHouseProperties connection, ClickHouseWriterProperties writer,
            ClickHouseReplicasProperties replicasProperties, ClickHouseWriterClient writerClient,
            ClickHouseClientWrapper readClient, ReplicaMeters meters, ReplicaHttp http, ReplicaAddressing addressing,
            Function<String, List<String>> resolver) {
        this.connection = connection;
        this.writer = writer;
        this.replicasProperties = replicasProperties;
        this.writerClient = writerClient;
        this.readClient = readClient;
        this.meters = meters;
        this.http = http;
        this.addressing = addressing;
        this.resolver = resolver;
    }

    /** Every replica registered in the database, active or not, read now through the primary. */
    public List<Registered> registered() {
        final List<Registered> rows = new ArrayList<>();
        for (final GenericRecord row : writerClient.primaryClient().queryAll(CLUSTER_ROWS,
                Map.of("db", connection.sessionDatabase()))) {
            rows.add(new Registered(row.getString("name"), row.getString("host"), row.getBoolean("local"),
                    row.getBoolean("active")));
        }
        return rows;
    }

    /**
     * The startup refresh: discovers the replicas and, while more than one is active, fails when {@code clickhouse.host}
     * reaches more than one server. A discovery that cannot be made is not a failure: the service then routes every
     * statement to the primary.
     *
     * @throws IllegalStateException when {@code clickhouse.host} spreads over several replicas
     */
    public void startup() {
        refresh(true);
    }

    /** Starts the periodic refresh. */
    public synchronized void startRefreshing() {
        if (refresher != null) {
            return;
        }
        final long interval = replicasProperties.discoveryInterval().toMillis();
        refresher = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("clickhouse-topology").factory());
        refresher.scheduleWithFixedDelay(() -> refresh(false), interval, interval, TimeUnit.MILLISECONDS);
    }

    /** One refresh; {@code startup} turns a spreading primary endpoint into a failure. */
    void refresh(boolean startup) {
        final List<Registered> rows;
        try {
            rows = registered();
        } catch (RuntimeException ex) {
            meters.discovery(false);
            final Snapshot last = snapshot;
            log.warn("ClickHouse replica discovery failed ({}); {}. Check the writer credential's SELECT ON system.clusters "
                    + "grant and that {} is reachable", ex.getMessage(),
                    last.discovered() ? "keeping the last discovered replica set" : "routing every statement to the primary",
                    connection.host());
            return;
        }
        final List<Registered> active = rows.stream().filter(Registered::active).toList();
        final String primaryName = rows.stream().filter(Registered::local).map(Registered::name).findFirst()
                .orElse(snapshot.primaryName());
        checkPrimaryEndpoint(startup, active.size(), primaryName);
        final List<Replica> replicas = new ArrayList<>();
        for (final Registered row : active) {
            final ReplicaAddressing.Address address = addressing.address(row.host(), connection.port());
            final ReplicaHttp.Status status = row.local()
                    ? http.status(connection.host(), connection.port())
                    : http.status(address.host(), address.port());
            if (status == ReplicaHttp.Status.UNREACHABLE && !row.local()) {
                // Active in Keeper but not answering here: no route uses it until a refresh finds it answering.
                log.warn("ClickHouse replica '{}' at {}:{} does not answer; it is excluded until it does", row.name(),
                        address.host(), address.port());
                continue;
            }
            replicas.add(new Replica(row.name(), row.host(), row.local(), true, status == ReplicaHttp.Status.HEALTHY));
        }
        snapshot = new Snapshot(true, primaryName, List.copyOf(replicas));
        excluded.clear();
        final Set<String> present = new LinkedHashSet<>();
        replicas.forEach(replica -> present.add(replica.name()));
        clients.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
        meters.primaryName(primaryName);
        meters.active(replicas.size());
        meters.discovery(true);
        log.debug("ClickHouse replicas: {} (primary {})", replicas, primaryName);
    }

    private void checkPrimaryEndpoint(boolean startup, int active, String primaryName) {
        if (active <= 1) {
            return;
        }
        final List<String> addresses = resolver.apply(connection.host());
        if (addresses.size() > 1) {
            final String message = "clickhouse.host '" + connection.host() + "' resolves to several addresses " + addresses
                    + " while the database has " + active + " active replicas; it must reach only the primary";
            if (startup) {
                throw new IllegalStateException(message + ". Point it at an endpoint that selects only the primary "
                        + "(see docs/clickhouse-replication.md)");
            }
            meters.primarySpread();
            log.warn(message);
        }
        if (startup) {
            final Set<String> answered = new LinkedHashSet<>();
            for (int i = 0; i < IDENTITY_PROBES; i++) {
                http.answeringReplica(connection.host(), connection.port(), writer.user(), writer.password(),
                        connection.sessionDatabase()).ifPresent(answered::add);
            }
            if (answered.size() > 1) {
                throw new IllegalStateException("clickhouse.host '" + connection.host() + "' is answered by different "
                        + "replicas " + answered + " over fresh connections; it must reach only the primary "
                        + "(see docs/clickhouse-replication.md)");
            }
            return;
        }
        final String previous = snapshot.primaryName();
        final Optional<String> answering = http.answeringReplica(connection.host(), connection.port(), writer.user(),
                writer.password(), connection.sessionDatabase());
        if (snapshot.discovered() && answering.isPresent() && !answering.get().equals(previous)) {
            meters.primaryChange();
            log.warn("clickhouse.host '{}' is now answered by replica '{}' instead of '{}': a failover, or an endpoint "
                    + "that spreads over replicas", connection.host(), answering.get(), previous);
        } else if (!primaryName.equals(previous) && snapshot.discovered()) {
            meters.primaryChange();
            log.warn("clickhouse.host '{}' is now answered by replica '{}' instead of '{}'", connection.host(), primaryName,
                    previous);
        }
    }

    static List<String> addresses(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress).distinct().toList();
        } catch (UnknownHostException ex) {
            return List.of();
        }
    }

    /** Replaces the discovered set as a refresh would; for tests that route over a set no server reports. */
    void seed(String primaryName, List<Replica> replicas) {
        snapshot = new Snapshot(true, primaryName, List.copyOf(replicas));
        excluded.clear();
    }

    /** The active replicas of the latest refresh, less those this instance excluded. */
    List<Replica> candidates() {
        return snapshot.replicas().stream().filter(replica -> !excluded.contains(replica.name())).toList();
    }

    /** Whether a refresh has succeeded. */
    boolean discovered() {
        return snapshot.discovered();
    }

    /** The primary's name, {@code primary} until discovery has named it. */
    public String primaryName() {
        return snapshot.primaryName();
    }

    /** Stops routing to {@code name} on this instance until the next refresh. */
    void exclude(String name) {
        if (excluded.add(name)) {
            log.warn("ClickHouse replica '{}' could not be reached; it is excluded until the next discovery refresh", name);
        }
    }

    /** The primary as a target on {@code route}. */
    ReplicaTarget primary(ReplicaTarget.Route route) {
        final String name = snapshot.primaryName();
        return new Target(name, true, route, null);
    }

    /** {@code replica} as a target on {@code route}. */
    ReplicaTarget target(Replica replica, ReplicaTarget.Route route) {
        if (replica.primary()) {
            return primary(route);
        }
        final ReplicaClients replicaClients = clients.computeIfAbsent(replica.name(),
                name -> new ReplicaClients(addressing.address(replica.host(), connection.port()), connection, writer));
        return new Target(replica.name(), false, route, replicaClients);
    }

    /** A short-lived pair of clients on {@code host}, for a one-off inspection; the caller closes it. */
    ReplicaClients oneOff(String host) {
        return new ReplicaClients(addressing.address(host, connection.port()), connection, writer);
    }

    @PreDestroy
    void shutdown() {
        final ScheduledExecutorService local = refresher;
        if (local != null) {
            local.shutdownNow();
        }
        clients.values().forEach(ReplicaClients::close);
        clients.clear();
    }

    private final class Target implements ReplicaTarget {

        private final String name;
        private final boolean primary;
        private final Route route;
        private final ReplicaClients replicaClients;

        Target(String name, boolean primary, Route route, ReplicaClients replicaClients) {
            this.name = name;
            this.primary = primary;
            this.route = route;
            this.replicaClients = replicaClients;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean primary() {
            return primary;
        }

        @Override
        public Route route() {
            return route;
        }

        @Override
        public Client readClient() {
            return primary ? readClient.primaryClient() : replicaClients.read();
        }

        @Override
        public Client writerClient() {
            return primary ? writerClient.primaryClient() : replicaClients.write();
        }

        @Override
        public void unreachable() {
            if (!primary) {
                exclude(name);
            }
        }

        @Override
        public String toString() {
            return name + (primary ? " (primary)" : "");
        }
    }
}
