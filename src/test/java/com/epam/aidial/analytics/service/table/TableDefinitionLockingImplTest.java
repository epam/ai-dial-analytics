package com.epam.aidial.analytics.service.table;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code tables.lock-timeout} below one millisecond renders as PostgreSQL's "no limit" or an error, so startup refuses it. */
class TableDefinitionLockingImplTest {

    @Test
    void lockTimeoutBelowOneMillisecondIsRefused() {
        for (final Duration timeout : List.of(Duration.ZERO, Duration.ofNanos(500_000), Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> locking(timeout).validateLockTimeout())
                    .as("%s", timeout)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tables.lock-timeout");
        }
    }

    @Test
    void lockTimeoutOfOneMillisecondIsAccepted() {
        assertThatCode(() -> locking(Duration.ofMillis(1)).validateLockTimeout()).doesNotThrowAnyException();
    }

    private static TableDefinitionLockingImpl locking(Duration timeout) {
        final TableDefinitionLockingImpl locking = new TableDefinitionLockingImpl();
        ReflectionTestUtils.setField(locking, "lockTimeout", timeout);
        return locking;
    }
}
