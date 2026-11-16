package com.epam.aidial.analytics.clickhouse.replication;

import com.epam.aidial.analytics.clickhouse.ClickHouseMigrationRunner;
import com.epam.aidial.analytics.configuration.ClickHouseReplicasProperties;
import com.epam.aidial.analytics.sync.IngestTtlReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * With migrations disabled and ClickHouse unreachable at startup, the instance starts with the gate closed and opens it
 * only once a background retry has verified the storage.
 */
class ClickHouseStoragePhaseTest {

    private static final Duration INTERVAL = ClickHouseReplicasProperties.MIN_DISCOVERY_INTERVAL;

    private final DatabaseEngineCheck engineCheck = mock(DatabaseEngineCheck.class);
    private final ClickHouseTopology topology = mock(ClickHouseTopology.class);
    private final StoragePhaseLock lock = mock(StoragePhaseLock.class);
    private final ReplicatedTableCheck tableCheck = mock(ReplicatedTableCheck.class);
    private final StorageReadyGate gate = new StorageReadyGate();
    private final StorageReadyHealthIndicator readiness = new StorageReadyHealthIndicator(gate);
    private final ClickHouseStoragePhase phase = new ClickHouseStoragePhase(engineCheck, topology, lock,
            mock(ReplicatedTableConversion.class), mock(ClickHouseMigrationRunner.class), mock(IngestTtlReconciler.class),
            tableCheck, gate, mock(ReplicaRouter.class), new ClickHouseReplicasProperties(INTERVAL, null), false);

    ClickHouseStoragePhaseTest() {
        willAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).given(lock).run(any());
    }

    @AfterEach
    void shutdown() {
        phase.shutdown();
    }

    @Test
    void unreachableClickHouseKeepsTheGateClosedUntilRetryVerifiesIt() {
        given(engineCheck.check()).willReturn(DatabaseEngineCheck.Outcome.UNREACHABLE, DatabaseEngineCheck.Outcome.REPLICATED);

        phase.run(null);
        assertThat(gate.isOpen()).isFalse();
        assertThat(readiness.health().getStatus().getCode()).isEqualTo("DOWN");
        verify(tableCheck, never()).check(anyMap());

        await().atMost(INTERVAL.multipliedBy(3)).until(gate::isOpen);
        verify(tableCheck).check(Map.of());
        verify(topology).startRefreshing();
        assertThat(readiness.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void failedVerificationAfterDelayedStartKeepsTheGateClosed() {
        given(engineCheck.check()).willReturn(DatabaseEngineCheck.Outcome.UNREACHABLE, DatabaseEngineCheck.Outcome.REPLICATED);
        willThrow(new IllegalStateException("'orders_raw' uses MergeTree")).given(tableCheck).check(anyMap());

        phase.run(null);
        await().atMost(INTERVAL.multipliedBy(3)).untilAsserted(() -> verify(tableCheck).check(anyMap()));
        assertThat(gate.isOpen()).isFalse();
    }
}
