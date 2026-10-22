package com.epam.aidial.analytics.observability;

import io.opentelemetry.api.OpenTelemetry;

/**
 * Test helper: a {@link ClientSpanTracer} backed by the no-op {@link OpenTelemetry}, for unit tests
 * that construct instrumented components directly (no Spring context). Span creation is a cheap
 * no-op, so tests keep asserting behavior without a live tracing backend.
 */
public final class TestClientSpanTracer {

    private TestClientSpanTracer() {
    }

    /** A {@link ClientSpanTracer} that emits no real spans. */
    public static ClientSpanTracer noop() {
        return new ClientSpanTracer(OpenTelemetry.noop());
    }
}
