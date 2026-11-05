package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;

/**
 * Skips a scheduled run that fires before the ClickHouse storage phase has opened the {@link StorageReadyGate}.
 * Applied to the <em>user</em> task of every schedule on both schedulers ({@link GatedTaskScheduler}), so a job
 * added later is held back without remembering to check. A skipped run is not retried: the job's next fire runs.
 */
@Slf4j
final class StorageReadyTaskDecorator implements TaskDecorator {

    private final StorageReadyGate gate;

    StorageReadyTaskDecorator(StorageReadyGate gate) {
        this.gate = gate;
    }

    @Override
    public Runnable decorate(Runnable task) {
        return () -> {
            if (!gate.isOpen()) {
                log.debug("Scheduled run skipped: the ClickHouse storage phase has not completed");
                return;
            }
            task.run();
        };
    }
}
