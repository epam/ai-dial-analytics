package com.epam.aidial.analytics.clickhouse.replication;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness contributor {@code storageReady}: down while the {@link StorageReadyGate} is closed. Spring reports the
 * instance ready once its runners return, which the storage phase does without opening the gate when ClickHouse was
 * unreachable at startup and the phase is retried in the background; this keeps readiness down until it completes.
 * Not part of liveness, so a ClickHouse outage never restarts the instance.
 */
@Component
public class StorageReadyHealthIndicator implements HealthIndicator {

    private final StorageReadyGate gate;

    public StorageReadyHealthIndicator(StorageReadyGate gate) {
        this.gate = gate;
    }

    @Override
    public Health health() {
        return gate.isOpen()
                ? Health.up().build()
                : Health.down().withDetail("reason", "the ClickHouse storage has not been verified yet").build();
    }
}
