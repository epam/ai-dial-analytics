package com.epam.aidial.analytics.configuration;

import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * A {@link ThreadPoolTaskScheduler} that decorates each scheduled <em>task</em> with {@link StorageReadyTaskDecorator}.
 *
 * <p>Not {@link ThreadPoolTaskScheduler#setTaskDecorator}: that decorator wraps the executor's scheduled future, whose
 * {@code run()} also re-arms a repeating or cron task, so a decorator that skips the call would stop the job for good.
 * Wrapping the user task at scheduling time skips only the run.
 */
final class GatedTaskScheduler extends ThreadPoolTaskScheduler {

    private final TaskDecorator gate;

    GatedTaskScheduler(TaskDecorator gate) {
        this.gate = gate;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return super.schedule(gate.decorate(task), trigger);
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
}
