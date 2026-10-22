package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.service.pipeline.AggregateScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the scheduler split: the aggregate tick must have a pool of its own, and the ingestion jobs must
 * keep the one they had, sized by the property that has always sized it.
 *
 * <p>The second half is the one worth having. Boot's
 * {@code TaskSchedulingConfigurations.TaskSchedulerConfiguration} is
 * {@code @ConditionalOnMissingBean(TaskScheduler.class)}, so contributing any {@code TaskScheduler} —
 * whatever it is called — removes Boot's. If someone later deletes the re-declared {@code taskScheduler}
 * bean as apparently redundant, {@code SCHEDULING_POOL_SIZE} silently stops applying and the only symptom
 * is ingestion getting slower after a deploy. That has no other test surface.
 */
class SchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfiguration.class)
            .withBean(AggregateProperties.class, () -> new AggregateProperties(
                    true, "0 * * * * *", 2, java.time.Duration.ofSeconds(5), 100, 20,
                    new AggregateProperties.Preview(java.time.Duration.ofSeconds(30),
                            org.springframework.util.unit.DataSize.ofGigabytes(2),
                            org.springframework.util.unit.DataSize.ofGigabytes(1))));

    @Test
    void bothSchedulersExistAndTheSharedOneIsPrimary() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("taskScheduler").hasBean("aggregateTaskScheduler");
            // Two TaskScheduler beans means a @Scheduled method naming none resolves by type; @Primary is
            // what makes that unambiguous rather than relying on the bean happening to be called
            // "taskScheduler".
            assertThat(context.getBeanFactory().getBeanDefinition("taskScheduler").isPrimary()).isTrue();
            assertThat(context.getBean(TaskScheduler.class))
                    .isSameAs(context.getBean("taskScheduler", TaskScheduler.class));
        });
    }

    @Test
    void ingestionSchedulerKeepsItsConfiguredPoolSize() {
        contextRunner.withPropertyValues("spring.task.scheduling.pool.size=7").run(context -> {
            final ThreadPoolTaskScheduler shared = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            // Core pool size, not getPoolSize(): the latter reports *live* threads, which is 0 until the
            // executor has run something.
            assertThat(shared.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(7);
        });
    }

    @Test
    void aggregateTickIsBoundToTheAggregateScheduler() throws Exception {
        final Scheduled scheduled = AggregateScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);

        // Asserts the wiring declaration, not the runtime: a name that does not resolve already fails at
        // startup (so every context-loading test covers that), but dropping the attribute would silently
        // put the tick back on the ingestion pool with nothing failing.
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("aggregateTaskScheduler");
    }

    @Test
    void aggregateSchedulerIsSizedFromItsOwnProperty() {
        contextRunner.withPropertyValues("spring.task.scheduling.pool.size=7").run(context -> {
            final ThreadPoolTaskScheduler aggregate =
                    context.getBean("aggregateTaskScheduler", ThreadPoolTaskScheduler.class);
            // Its own property (2 in the fixture), not the ingestion pool's 7 — the point of the split.
            assertThat(aggregate.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(aggregate.getThreadNamePrefix()).startsWith("aggregate-");
        });
    }
}
