package com.epam.aidial.analytics.clickhouse.replication;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The replica the current thread's ClickHouse statements execute on. A pipeline run or a user query binds its
 * {@link ReplicaTarget} here for its duration; {@code ClickHouseClientWrapper} and
 * {@code ClickHouseInsertSelectExecutor} then issue every statement through the bound replica's clients, and
 * anything unbound executes on the primary. That makes "every statement of a run executes on its home" hold by
 * construction rather than by threading a handle through each call.
 *
 * <p>DDL and the system-catalog reads ({@code ClickHouseDdlExecutor}, {@code ClickHouseTableInspector}) ignore the
 * binding and always use the primary, as do the ingest and row writers, which own their clients.
 *
 * <p>A thread-local rather than a scoped value, so a long service method can bind at the point its statements start
 * and unbind in the {@code finally} it already has. Bindings nest: closing one restores the binding it replaced.
 */
public final class ReplicaScope {

    private static final ThreadLocal<ReplicaTarget> CURRENT = new ThreadLocal<>();

    private ReplicaScope() {
    }

    /** The replica bound to the current thread, if any. */
    public static Optional<ReplicaTarget> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The replica a pipeline statement executes on, refusing a statement issued outside a pipeline run. A pipeline
     * statement that ran unbound would execute on the primary while the rest of its run executes on the home — a
     * probe on one replica and its write on another, which is exactly the divergence home routing exists to prevent.
     * Failing here makes a run that hands work to another thread, which the binding does not follow, fail loudly.
     *
     * @throws IllegalStateException when the current thread has no pipeline run bound
     */
    public static ReplicaTarget requirePipelineRun(String statement) {
        final ReplicaTarget target = CURRENT.get();
        if (target == null || target.route() != ReplicaTarget.Route.PIPELINE) {
            throw new IllegalStateException("The pipeline statement '" + statement + "' was issued outside a pipeline "
                    + "run, so it would not execute on the run's home replica; open it through ReplicaRouter.openRun");
        }
        return target;
    }

    /** Binds {@code target} until the returned binding is closed. */
    public static Binding bind(ReplicaTarget target) {
        final ReplicaTarget previous = CURRENT.get();
        CURRENT.set(target);
        return new Binding(previous);
    }

    /** Runs {@code body} with {@code target} bound and returns its result. */
    public static <T> T on(ReplicaTarget target, Supplier<T> body) {
        try (Binding ignored = bind(target)) {
            return body.get();
        }
    }

    /** An open binding; closing it restores whatever was bound before. */
    public static final class Binding implements AutoCloseable {

        private final ReplicaTarget previous;
        private boolean closed;

        private Binding(ReplicaTarget previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
