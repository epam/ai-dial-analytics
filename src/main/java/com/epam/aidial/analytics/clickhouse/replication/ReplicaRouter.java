package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseFailures;
import com.epam.aidial.analytics.clickhouse.ClickHouseIdentifiers;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseReplicasProperties;
import com.epam.aidial.analytics.configuration.ClickHouseRoutingProperties;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Decides which replica each unit of work executes on, and binds it ({@link ReplicaScope}). See
 * {@code openspec/specs/clickhouse-replication/spec.md}.
 *
 * <ul>
 *   <li><b>Writes and anything unnamed:</b> the primary, by default — nothing here is consulted.</li>
 *   <li><b>A pipeline run:</b> its home, a rendezvous hash of the pipeline name over the healthy non-primary replicas
 *       (the primary when there is none), synchronized before the run's first data statement: the database's DDL
 *       log, then every table the run reads, in {@link SyncPlan} order. A sync that cannot complete refuses the run
 *       with {@code 503}. The syncs run on the writer credential, outside any statement budget.</li>
 *   <li><b>A user query:</b> a healthy replica, spread over the candidates; the primary only when there is exactly one
 *       healthy non-primary replica, or none. Re-run once on the primary when the replica cannot be reached or reports
 *       a name the catalog resolved as unknown.</li>
 * </ul>
 */
@Slf4j
@Component
public class ReplicaRouter {

    /** The MDC key naming the replica a pipeline run executes on; printed on every log line of the run. */
    public static final String REPLICA_KEY = "clickhouse_replica";

    /** The span attribute naming the replica a pipeline run executes on. */
    public static final String REPLICA_ATTRIBUTE = "clickhouse.replica";

    /** The table-sync rounds a run may take to get one under half its safety lag. */
    static final int SYNC_ROUNDS = 3;

    private final ClickHouseTopology topology;
    private final ClickHouseRoutingProperties routing;
    private final ClickHouseReplicasProperties replicas;
    private final ClickHouseProperties connection;
    private final ReplicaMeters meters;
    private final AtomicLong spread = new AtomicLong();


    public ReplicaRouter(ClickHouseTopology topology, ClickHouseRoutingProperties routing,
            ClickHouseReplicasProperties replicas, ClickHouseProperties connection, ReplicaMeters meters) {
        this.topology = topology;
        this.routing = routing;
        this.replicas = replicas;
        this.connection = connection;
        this.meters = meters;
    }

    // ---- user queries -------------------------------------------------------------------------------------------

    /** The replica the next user query executes on. */
    ReplicaTarget queryTarget() {
        if (routing.queries() == ClickHouseRoutingProperties.Route.PRIMARY) {
            return topology.primary(ReplicaTarget.Route.QUERY);
        }
        final List<Replica> healthy = topology.candidates().stream().filter(Replica::healthy).toList();
        final List<Replica> others = healthy.stream().filter(replica -> !replica.primary()).toList();
        final List<Replica> preferred = new ArrayList<>(others);
        if (others.size() == 1) {
            healthy.stream().filter(Replica::primary).findFirst().ifPresent(preferred::add);
        }
        if (preferred.isEmpty()) {
            return topology.primary(ReplicaTarget.Route.QUERY);
        }
        final Replica chosen = preferred.get((int) Math.floorMod(spread.getAndIncrement(), preferred.size()));
        return topology.target(chosen, ReplicaTarget.Route.QUERY);
    }

    /**
     * Runs a user query's statements on one replica, and once more on the primary when the replica cannot be reached
     * or does not yet know a table or column. {@code body} must not hand any row to the caller before it returns.
     */
    public <T> T query(Supplier<T> body) {
        return queryWithin(spent -> body.get());
    }

    /**
     * As {@link #query(Supplier)}, telling {@code body} how much of the request's time an earlier attempt already spent:
     * {@link Duration#ZERO} on the first attempt, the time to the replica's failure on the re-run. A body under a
     * wall-clock ceiling runs the re-run under what is left of it, so the request as a whole stays within the ceiling.
     */
    public <T> T queryWithin(Function<Duration, T> body) {
        final ReplicaTarget target = queryTarget();
        final long started = System.nanoTime();
        try {
            return ReplicaScope.on(target, () -> body.apply(Duration.ZERO));
        } catch (RuntimeException ex) {
            if (target.primary()) {
                throw ex;
            }
            final boolean unreachable = ClickHouseFailures.isConnectFailure(ex);
            if (!unreachable && !ClickHouseFailures.isUnknownName(ex)) {
                throw ex;
            }
            final Duration spent = Duration.ofNanos(System.nanoTime() - started);
            final ReplicaTarget primary = topology.primary(ReplicaTarget.Route.QUERY);
            if (unreachable) {
                target.unreachable();
                meters.queryFallback("unreachable");
                log.info("User query re-run on the primary: replica '{}' could not be reached", target.name());
                return ReplicaScope.on(primary, () -> body.apply(spent));
            }
            try {
                final T answer = ReplicaScope.on(primary, () -> body.apply(spent));
                meters.queryFallback("ddl_lag");
                log.info("User query re-run on the primary: replica '{}' has not applied a recent DDL yet", target.name());
                return answer;
            } catch (RuntimeException again) {
                if (ClickHouseFailures.isUnknownName(again)) {
                    // Unknown on the primary too: the name is the query's own error, not a replica behind on DDL.
                    log.debug("User query re-run on the primary reported the same unknown name: not DDL lag");
                } else {
                    meters.queryFallback("ddl_lag");
                }
                throw again;
            }
        }
    }

    // ---- pipeline runs ------------------------------------------------------------------------------------------

    /**
     * The home of {@code pipeline}: a rendezvous hash over the healthy non-primary replicas, else the primary. A replica
     * that is lagging (a fresh one still fetching, or one fallen behind) would have every run of its share of pipelines
     * wait out the sync timeout and answer {@code 503}; the primary holds every committed part and syncs at once.
     */
    ReplicaTarget home(String pipeline) {
        if (routing.pipelines() == ClickHouseRoutingProperties.Route.PRIMARY) {
            return topology.primary(ReplicaTarget.Route.PIPELINE);
        }
        return topology.candidates().stream()
                .filter(replica -> !replica.primary() && replica.healthy())
                .max(Comparator.comparingLong((Replica replica) -> weight(pipeline, replica.name()))
                        .thenComparing(Replica::name))
                .map(replica -> topology.target(replica, ReplicaTarget.Route.PIPELINE))
                .orElseGet(() -> topology.primary(ReplicaTarget.Route.PIPELINE));
    }

    /** The rendezvous weight of {@code replica} for {@code pipeline}: the first 8 bytes of SHA-256 over both. */
    static long weight(String pipeline, String replica) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pipeline.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(replica.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Opens one run of {@code pipeline} on its home: brings the home up to date per {@code plan}, then binds it, so
     * every statement the thread issues until {@link PipelineRun#close()} executes there. Call it after the run's
     * catalog reads and before its first ClickHouse statement.
     *
     * @throws ClickHouseUnavailableException when the home cannot be reached or a sync fails or exceeds
     *                                        {@code clickhouse.replicas.sync-timeout}; nothing has been read or written
     */
    public PipelineRun openRun(String pipeline, SyncPlan plan) {
        final ReplicaTarget home = home(pipeline);
        sync(home, plan);
        return new PipelineRun(pipeline, home, meters.runsInFlight(home.name()));
    }

    /** Runs {@code body} as one run of {@code pipeline}; see {@link #openRun}. */
    public <T> T run(String pipeline, SyncPlan plan, Supplier<T> body) {
        try (PipelineRun ignored = openRun(pipeline, plan)) {
            return body.get();
        }
    }

    /**
     * Synchronizes {@code tables} on the primary, for a primary-side reader of tables a pipeline writes from its home
     * (the enrichment relayout).
     */
    public void syncOnPrimary(List<String> tables) {
        final ReplicaTarget primary = topology.primary(ReplicaTarget.Route.PRIMARY);
        for (final String table : tables) {
            syncTable(primary, table);
        }
    }

    /**
     * Issues both sync statements once on the primary, so a writer credential missing the {@code SYSTEM SYNC REPLICA}
     * or {@code SYSTEM SYNC DATABASE REPLICA} grant fails startup naming it, instead of every ingest cycle and pipeline
     * run failing later. Any other failure is only logged: the syncs are retried per cycle and per run.
     *
     * @param table a table of the session database, synchronized to prove the table-level grant; it may be absent or
     *              not yet replicated (a fresh install, or before the conversion), because access is checked first
     * @throws IllegalStateException when the writer credential is denied either statement
     */
    public void verifySyncGrants(String table) {
        final ReplicaTarget primary = topology.primary(ReplicaTarget.Route.PRIMARY);
        final String database = connection.sessionDatabase();
        verifyGrant(primary, "SYSTEM SYNC DATABASE REPLICA " + ClickHouseIdentifiers.quote(database), "SYSTEM SYNC DATABASE REPLICA", database);
        verifyGrant(primary, "SYSTEM SYNC REPLICA " + ClickHouseIdentifiers.quote(table) + " LIGHTWEIGHT", "SYSTEM SYNC REPLICA", database);
    }

    private void verifyGrant(ReplicaTarget primary, String statement, String grant, String database) {
        try {
            issue(primary, statement);
        } catch (UnknownTableSkipped skipped) {
            // The grant is checked before the table is looked up, so a missing table means the grant is held.
            log.debug("The {} grant is held; the table it was checked on does not exist yet", grant);
        } catch (RuntimeException ex) {
            if (ClickHouseFailures.isAccessDenied(ex)) {
                throw new IllegalStateException("The ClickHouse writer credential lacks the " + grant + " grant: GRANT "
                        + grant + " ON " + database + ".* TO <writer> (see docs/clickhouse-server.md)", ex);
            }
            log.warn("Could not verify the {} grant on the primary: {}", grant, ex.getMessage());
        }
    }

    private void sync(ReplicaTarget home, SyncPlan plan) {
        final String database = connection.sessionDatabase();
        timed(home, "_database", () -> issue(home, "SYSTEM SYNC DATABASE REPLICA " + ClickHouseIdentifiers.quote(database)));
        // A table sync waits only for writes committed when it starts, and the window's safety lag is applied at the
        // probe right after the last one: a round that took too long would leave a write committed during it, and
        // not yet fetched, below the horizon. A later round starts later, so it is repeated until one is quick.
        for (int round = 1; ; round++) {
            final long started = System.nanoTime();
            for (final String table : plan.tables()) {
                syncTable(home, table);
            }
            final Duration took = Duration.ofNanos(System.nanoTime() - started);
            if (plan.safetyLag() == null || plan.safetyLag().isZero() || plan.tables().isEmpty()
                    || took.compareTo(plan.safetyLag().dividedBy(2)) <= 0) {
                return;
            }
            if (round == SYNC_ROUNDS) {
                meters.sync("_rounds", "slow", took);
                log.warn("ClickHouse replica '{}' took {} ms to sync the run's tables {} times in a row, more than half the "
                        + "safety lag ({} ms): the run is refused. Raise the safety lag if the replica is routinely this "
                        + "slow", home.name(), took.toMillis(), SYNC_ROUNDS, plan.safetyLag().toMillis());
                throw new ClickHouseUnavailableException("The pipeline's ClickHouse replica could not be brought up to "
                        + "date within the safety lag", null);
            }
            log.debug("ClickHouse replica '{}' took {} ms to sync the run's tables; syncing again", home.name(),
                    took.toMillis());
        }
    }

    private void syncTable(ReplicaTarget target, String table) {
        timed(target, table, () -> issue(target, "SYSTEM SYNC REPLICA " + ClickHouseIdentifiers.quote(table) + " LIGHTWEIGHT"));
    }

    private void timed(ReplicaTarget target, String table, Runnable sync) {
        final long started = System.nanoTime();
        String outcome = "ok";
        try {
            sync.run();
        } catch (UnknownTableSkipped skipped) {
            outcome = "ok";
        } catch (RuntimeException ex) {
            outcome = ClickHouseFailures.isTimeout(ex) ? "timeout" : "failed";
            if (!target.primary() && ClickHouseFailures.isConnectFailure(ex)) {
                target.unreachable();
            }
            final boolean run = target.route() == ReplicaTarget.Route.PIPELINE;
            log.warn("ClickHouse replica '{}' could not be brought up to date on '{}' ({}): {}", target.name(), table,
                    outcome, run ? "the run is refused" : "the step that needed it is refused");
            throw new ClickHouseUnavailableException(run ? "The pipeline's ClickHouse replica could not be brought up to date"
                    : "The ClickHouse primary could not be brought up to date", ex);
        } finally {
            meters.sync(table, outcome, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private void issue(ReplicaTarget target, String statement) {
        final QuerySettings settings = new QuerySettings()
                .serverSetting("receive_timeout", Long.toString(Math.max(1, replicas.syncTimeout().toSeconds())));
        final Client client = target.writerClient();
        meters.statement(target.route(), target.name());
        try {
            client.queryAll(statement, settings);
        } catch (RuntimeException ex) {
            if (statement.startsWith("SYSTEM SYNC REPLICA") && ClickHouseFailures.isUnknownName(ex)) {
                // Not a table this database holds after the database sync: the run's own statement reports it.
                throw new UnknownTableSkipped();
            }
            throw ex;
        }
    }

    /** A table sync skipped because the table does not exist on the synchronized replica. */
    private static final class UnknownTableSkipped extends RuntimeException {

        UnknownTableSkipped() {
            super(null, null, false, false);
        }
    }

    /** One open pipeline run: its home is bound until {@link #close()}. */
    public static final class PipelineRun implements AutoCloseable {

        private final ReplicaTarget home;
        private final ReplicaScope.Binding binding;
        private final AtomicInteger inFlight;
        private final String previousReplica;
        private boolean closed;

        private PipelineRun(String pipeline, ReplicaTarget home, AtomicInteger inFlight) {
            this.home = home;
            this.inFlight = inFlight;
            this.previousReplica = MDC.get(REPLICA_KEY);
            MDC.put(REPLICA_KEY, home.name());
            Span.current().setAttribute(REPLICA_ATTRIBUTE, home.name());
            inFlight.incrementAndGet();
            this.binding = ReplicaScope.bind(home);
            log.debug("Pipeline '{}' runs on ClickHouse replica '{}'", pipeline, home.name());
        }

        /** A run bound to {@code home} with no sync, for a stand-in router in tests of this package. */
        static PipelineRun unsynced(String pipeline, ReplicaTarget home) {
            return new PipelineRun(pipeline, home, new AtomicInteger());
        }

        /** The replica the run executes on. */
        public String replica() {
            return home.name();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            binding.close();
            inFlight.decrementAndGet();
            if (previousReplica == null) {
                MDC.remove(REPLICA_KEY);
            } else {
                MDC.put(REPLICA_KEY, previousReplica);
            }
        }
    }
}
