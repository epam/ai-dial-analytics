package com.epam.aidial.analytics.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ThreadPoolTaskScheduler} that decorates each scheduled <em>task</em> with {@link StorageReadyTaskDecorator}.
 *
 * <p>Not {@link ThreadPoolTaskScheduler#setTaskDecorator}: that decorator wraps the executor's scheduled future, whose
 * {@code run()} also re-arms a repeating or cron task, so a decorator that skips the call would stop the job for good.
 * Wrapping the user task at scheduling time skips only the run.
 *
 * <p>A trigger-scheduled (cron) job whose fire was skipped runs once on this pool when the gate opens: its next fire
 * may be hours away (the daily data-sync), so skipping it would silently drop that run. Fixed-delay and fixed-rate
 * jobs fire again shortly and need no catch-up.
 */
@Slf4j
final class GatedTaskScheduler extends ThreadPoolTaskScheduler {

    private final StorageReadyTaskDecorator gate;

    GatedTaskScheduler(StorageReadyTaskDecorator gate) {
        this.gate = gate;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        final AtomicBoolean catchUpPending = new AtomicBoolean();
        final Runnable gated = gate.decorate(task, () -> {
            if (catchUpPending.compareAndSet(false, true)) {
                gate.gate().whenOpen(() -> catchUp(task));
            }
        });
        return super.schedule(gated, trigger);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return super.schedule(gate.decorate(task), startTime);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return super.scheduleAtFixedRate(gate.decorate(task), startTime, period);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return super.scheduleAtFixedRate(gate.decorate(task), period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return super.scheduleWithFixedDelay(gate.decorate(task), startTime, delay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return super.scheduleWithFixedDelay(gate.decorate(task), delay);
    }

    /** Runs a skipped cron job once on this pool; never on the thread opening the gate, and never throwing into it. */
    private void catchUp(Runnable task) {
        try {
            execute(task);
        } catch (RuntimeException ex) {
            log.warn("Could not run a scheduled job skipped during the ClickHouse storage phase: {}", ex.getMessage());
        }
    }
}
