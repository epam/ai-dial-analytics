package com.epam.aidial.analytics.clickhouse.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Closed until the ClickHouse storage phase ({@link ClickHouseStoragePhase}) has verified the storage, then open for
 * the life of the instance. Nothing in the instance may write to ClickHouse or serve a request before it opens:
 * Spring starts the {@code @Scheduled} jobs and the web server when the context refresh completes, which is before
 * any {@code ApplicationRunner} runs, so the phase alone cannot hold them back.
 *
 * <p>Enforced in three places, never per job: a gated scheduler skips every scheduled run
 * ({@code SchedulingConfiguration}), {@code StorageReadyFilter} answers {@code 503 clickhouse_unavailable} on
 * {@code /v1/**}, and the Flight SQL server starts only through {@link #whenOpen}. See
 * {@code openspec/specs/clickhouse-replication/spec.md} "The service runs only on a Replicated database".
 */
@Slf4j
@Component
public class StorageReadyGate {

    private final List<Runnable> waiting = new ArrayList<>();
    private final List<String> failed = new CopyOnWriteArrayList<>();
    private volatile boolean open;

    /** Whether the storage phase has completed. */
    public boolean isOpen() {
        return open;
    }

    /** Why the {@link #whenOpen} actions that threw failed; empty while every one has succeeded. */
    public List<String> failedActions() {
        return List.copyOf(failed);
    }

    /**
     * Opens the gate and runs every {@link #whenOpen} action registered so far, in registration order. One action
     * failing does not keep the others from running; each failure is recorded for readiness
     * ({@link StorageReadyHealthIndicator}), and the first is rethrown once all have run, so a start that opens the
     * gate in the foreground still fails on it.
     */
    public void open() {
        final List<Runnable> actions;
        synchronized (this) {
            if (open) {
                return;
            }
            open = true;
            actions = List.copyOf(waiting);
            waiting.clear();
        }
        log.info("ClickHouse storage verified; scheduled jobs, /v1 and Flight SQL are enabled");
        RuntimeException first = null;
        for (final Runnable action : actions) {
            try {
                runRecorded(action);
            } catch (RuntimeException ex) {
                if (first == null) {
                    first = ex;
                } else {
                    first.addSuppressed(ex);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** Runs {@code action} once the gate opens: now when it already is, else on {@link #open()}. */
    public void whenOpen(Runnable action) {
        synchronized (this) {
            if (!open) {
                waiting.add(action);
                return;
            }
        }
        runRecorded(action);
    }

    private void runRecorded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            failed.add(String.valueOf(ex.getMessage()));
            log.error("An action waiting for the ClickHouse storage to be verified failed: {}", ex.getMessage(), ex);
            throw ex;
        }
    }
}
