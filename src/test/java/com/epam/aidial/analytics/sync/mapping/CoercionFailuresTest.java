package com.epam.aidial.analytics.sync.mapping;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-column coercion-failure counter and its one-line-per-minute log limiter. */
class CoercionFailuresTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final CoercionFailures failures = new CoercionFailures("dial_usage_log", "prompt_tokens", ColumnKind.LONG, registry, nanos::get);

    @Test
    void everyFailureIsCountedButOnlyOneLinePerWindowIsLogged() {
        assertThat(failures.admit()).isEqualTo(0);
        for (int i = 0; i < 999; i++) {
            assertThat(failures.admit()).isEqualTo(CoercionFailures.SUPPRESSED);
        }

        nanos.addAndGet(CoercionFailures.WINDOW.toNanos());

        assertThat(failures.admit()).isEqualTo(999);
        assertThat(failures.admit()).isEqualTo(CoercionFailures.SUPPRESSED);
    }

    @Test
    void reportCountsUnderTheColumnTags() {
        failures.report("STRING", "logs/a.log.gz");
        failures.report("STRING", "logs/a.log.gz");

        assertThat(registry.get(CoercionFailures.METRIC).tags("table", "dial_usage_log", "column", "prompt_tokens", "kind", "long")
                .counter().count()).isEqualTo(2);
    }
}
