package com.epam.aidial.analytics.clickhouse;

import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.observability.TestClientSpanTracer;

/**
 * Test helper: a {@link ClickHouseSpans} backed by the no-op tracer, for unit tests that construct
 * ClickHouse components directly (no Spring context). Uses harmless placeholder properties so it stays
 * NPE-safe even if a traced path runs; span creation is a no-op regardless.
 */
public final class TestClickHouseSpans {

    private TestClickHouseSpans() {
    }

    /** A {@link ClickHouseSpans} that emits no real spans. */
    public static ClickHouseSpans noop() {
        return new ClickHouseSpans(
                TestClientSpanTracer.noop(),
                new ClickHouseProperties("localhost", 8123, null, "default", ""));
    }
}
