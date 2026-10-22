package com.epam.aidial.analytics.blob;

import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.observability.TestClientSpanTracer;

import java.util.Map;

/**
 * Test helper: a {@link BlobSpans} backed by the no-op tracer, for unit tests that construct
 * {@link BlobClient} directly (no Spring context). The placeholder properties only feed span
 * attributes (never the actual blob I/O, which uses the client's own properties), and span creation
 * is a no-op regardless.
 */
public final class TestBlobSpans {

    private TestBlobSpans() {
    }

    /** A {@link BlobSpans} that emits no real spans. */
    public static BlobSpans noop() {
        return new BlobSpans(
                TestClientSpanTracer.noop(),
                new BlobProperties("filesystem", null, null, "logs", null, Map.of()));
    }
}
