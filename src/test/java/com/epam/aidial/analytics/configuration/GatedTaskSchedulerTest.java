package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Scheduled runs before the ClickHouse storage phase completes are skipped, and only the runs: a repeating schedule
 * keeps firing, so the job starts running as soon as the gate opens.
 */
class GatedTaskSchedulerTest {

    private final StorageReadyGate gate = new StorageReadyGate();
    private final GatedTaskScheduler scheduler = new GatedTaskScheduler(new StorageReadyTaskDecorator(gate));

    @AfterEach
    void shutdown() {
        scheduler.shutdown();
    }

    @Test
    void repeatingJobSkipsRunsUntilTheGateOpensThenRuns() throws Exception {
        scheduler.initialize();
        final AtomicInteger runs = new AtomicInteger();
        scheduler.scheduleWithFixedDelay(runs::incrementAndGet, Duration.ofMillis(10));

        Thread.sleep(150);
        assertThat(runs).hasValue(0);

        gate.open();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.get()).isPositive());
    }

    @Test
    void cronStyleTriggerKeepsReschedulingWhileClosed() throws Exception {
        scheduler.initialize();
        final AtomicInteger runs = new AtomicInteger();
        scheduler.schedule(runs::incrementAndGet, context -> java.time.Instant.now().plusMillis(10));

        Thread.sleep(150);
        assertThat(runs).hasValue(0);

        gate.open();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.get()).isPositive());
    }

    /** A cron job whose fire was skipped runs once when the gate opens; its next fire may be a day away. */
    @Test
    void skippedCronJobRunsOnceWhenTheGateOpens() throws Exception {
        scheduler.initialize();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger fires = new AtomicInteger();
        // Fires a few times quickly, then not again for a day.
        scheduler.schedule(runs::incrementAndGet, context -> fires.incrementAndGet() <= 3
                ? java.time.Instant.now().plusMillis(10) : java.time.Instant.now().plus(Duration.ofDays(1)));

        await().atMost(Duration.ofSeconds(5)).until(() -> fires.get() > 3);
        assertThat(runs).hasValue(0);

        gate.open();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.get()).isEqualTo(1));
        Thread.sleep(100);
        assertThat(runs).hasValue(1);
    }
}
