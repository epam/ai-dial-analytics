package com.epam.aidial.analytics.configuration;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Splits the aggregate tick onto a scheduler of its own, away from the pool the ingestion jobs share.
 *
 * <p><b>Why the split.</b> {@code AggregateScheduler.tick}, {@code BlobPollingService} and
 * {@code DataSyncService} all ran on Boot's single {@code taskScheduler}, sized by
 * {@code spring.task.scheduling.pool.size}. A refresh is the one job whose duration is unbounded by
 * design — it recomputes every dirty key, and for a grain with no time bucket that means reading the
 * whole input — so on a shared pool a slow recompute does not merely lengthen the tick, it occupies a
 * thread ingestion needs. The two failure modes are not equivalent: a late rollup serves a stale number,
 * while late ingestion loses ground against its source.
 *
 * <p><b>Why <em>both</em> beans are declared here.</b> This is the part that is not obvious from the code.
 * Boot's {@code TaskSchedulingConfigurations.TaskSchedulerConfiguration} is annotated
 * {@code @ConditionalOnMissingBean(TaskScheduler.class)}, so contributing <em>any</em> {@code TaskScheduler}
 * — whatever it is named — removes Boot's {@code taskScheduler} along with it. The ingestion jobs would
 * then fall back to whatever {@code ScheduledAnnotationBeanPostProcessor} picks and
 * {@code SCHEDULING_POOL_SIZE} would silently stop applying: a configuration knob that reads as honoured
 * and is not. So the shared scheduler is re-declared here from {@link ThreadPoolTaskSchedulerBuilder},
 * which is still auto-configured and still fed by {@code spring.task.scheduling.*} — only the {@code @Bean}
 * declaration is ours, so Boot keeps supplying the defaults.
 *
 * <p>{@link Primary} states the intent rather than relying on an accident of naming: with two
 * {@code TaskScheduler} beans, a {@code @Scheduled} method that names none resolves by type, and Spring's
 * fallback to the bean literally called {@code taskScheduler} would otherwise be what saves it.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration {

    /** The pool the blob poller and the data-sync job run on; exactly what Boot would have built. */
    @Bean
    @Primary
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /**
     * The aggregate tick's own pool, selected via {@code @Scheduled(scheduler = "aggregateTaskScheduler")}.
     *
     * <p>One thread by default, which keeps today's semantics exactly: refreshes serial within a tick, and
     * ticks unable to overlap. Sizing it above 1 permits overlapping ticks — safe, because a refresh is
     * idempotent, but that is a separate change to make deliberately rather than a side effect of
     * splitting the pool.
     */
    @Bean
    public ThreadPoolTaskScheduler aggregateTaskScheduler(AggregateProperties properties) {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.schedulerPoolSize());
        scheduler.setThreadNamePrefix("aggregate-sched-");
        // A refresh in flight has already probed its window; letting it finish means the cursor advances
        // and the next start does not re-run the same range. It is idempotent either way, so this saves
        // duplicated work rather than preventing an error.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
