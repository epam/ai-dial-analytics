package com.epam.aidial.analytics.clickhouse.replication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gate's opening runs every waiting action even when one fails, and readiness reports the failure. */
class StorageReadyGateTest {

    private final StorageReadyGate gate = new StorageReadyGate();
    private final StorageReadyHealthIndicator readiness = new StorageReadyHealthIndicator(gate);

    @Test
    void failingActionDoesNotKeepTheOthersFromRunning() {
        final List<String> ran = new ArrayList<>();
        gate.whenOpen(() -> ran.add("first"));
        gate.whenOpen(() -> {
            throw new IllegalStateException("Flight SQL listener failed to bind port 0");
        });
        gate.whenOpen(() -> ran.add("third"));

        assertThatThrownBy(gate::open).isInstanceOf(IllegalStateException.class).hasMessageContaining("bind");

        assertThat(ran).containsExactly("first", "third");
        assertThat(gate.isOpen()).isTrue();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails().get("failures")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).singleElement().asString()
                .contains("bind");
    }

    @Test
    void readinessIsUpOnceOpenWithEveryActionSucceeded() {
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        gate.whenOpen(() -> { });
        gate.open();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }
}
