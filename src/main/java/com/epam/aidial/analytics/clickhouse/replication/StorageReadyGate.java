package com.epam.aidial.analytics.clickhouse.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
    private volatile boolean open;

    /** Whether the storage phase has completed. */
    public boolean isOpen() {
        return open;
    }

    /** Opens the gate and runs every {@link #whenOpen} action registered so far, in registration order. */
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
        actions.forEach(Runnable::run);
    }

    /** Runs {@code action} once the gate opens: now when it already is, else on {@link #open()}. */
    public void whenOpen(Runnable action) {
        synchronized (this) {
            if (!open) {
                waiting.add(action);
                return;
            }
        }
        action.run();
    }
}
