package com.epam.aidial.analytics.clickhouse.replication;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The routing meters; see {@code openspec/specs/clickhouse-replication/spec.md} "Routing is observable and
 * configurable".
 */
@Component
public class ReplicaMeters {

    /** Gauge: replicas the instance routes over. */
    static final String ACTIVE = "clickhouse.replicas.active";
    /** Counter: discovery refreshes, by {@code outcome} ({@code ok}, {@code failed}). */
    static final String DISCOVERY = "clickhouse.replicas.discovery";
    /** Timer: replica syncs, by {@code table} and {@code outcome} ({@code ok}, {@code timeout}, {@code failed}). */
    static final String SYNC = "clickhouse.replicas.sync";
    /** Counter: statements, by {@code route} and {@code replica}. */
    static final String STATEMENTS = "clickhouse.statements";
    /** Gauge: pipeline runs in flight, by {@code replica}. */
    static final String RUNS_IN_FLIGHT = "clickhouse.pipeline.runs.in_flight";
    /** Counter: user queries re-executed on the primary, by {@code reason} ({@code unreachable}, {@code ddl_lag}). */
    static final String QUERY_FALLBACK = "clickhouse.queries.primary_fallback";
    /** Counter: refreshes that found a different replica answering through {@code clickhouse.host}. */
    static final String PRIMARY_CHANGES = "clickhouse.replicas.primary_changes";
    /** Counter: refreshes that found {@code clickhouse.host} resolving to several addresses. */
    static final String PRIMARY_SPREAD = "clickhouse.replicas.primary_spread";

    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger(1);
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private volatile String primaryName = "primary";

    public ReplicaMeters(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge(ACTIVE, active);
    }

    /** The primary's name for statements that run unbound; set by discovery. */
    public String primaryName() {
        return primaryName;
    }

    void primaryName(String name) {
        this.primaryName = name;
    }

    void active(int count) {
        active.set(count);
    }

    void discovery(boolean ok) {
        registry.counter(DISCOVERY, "outcome", ok ? "ok" : "failed").increment();
    }

    void sync(String table, String outcome, Duration took) {
        Timer.builder(SYNC).tag("table", table).tag("outcome", outcome).register(registry).record(took);
    }

    /** Counts one statement on {@code route} and {@code replica}. */
    public void statement(ReplicaTarget.Route route, String replica) {
        registry.counter(STATEMENTS, "route", route.tag(), "replica", replica).increment();
    }

    /** Counts one statement issued with no replica bound, which runs on the primary. */
    public void unboundStatement() {
        statement(ReplicaTarget.Route.PRIMARY, primaryName);
    }

    AtomicInteger runsInFlight(String replica) {
        return inFlight.computeIfAbsent(replica, name -> {
            final AtomicInteger gauge = new AtomicInteger();
            registry.gauge(RUNS_IN_FLIGHT, List.of(Tag.of("replica", name)), gauge);
            return gauge;
        });
    }

    void queryFallback(String reason) {
        registry.counter(QUERY_FALLBACK, "reason", reason).increment();
    }

    void primaryChange() {
        registry.counter(PRIMARY_CHANGES).increment();
    }

    void primarySpread() {
        registry.counter(PRIMARY_SPREAD).increment();
    }
}
