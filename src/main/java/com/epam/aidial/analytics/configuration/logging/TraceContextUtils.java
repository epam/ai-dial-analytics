package com.epam.aidial.analytics.configuration.logging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;

/**
 * Formats the W3C Trace Context {@code traceparent} value ({@code 00-{trace-id}-{span-id}-{trace-flags}})
 * from the current OpenTelemetry span context. Returns {@code null} when no valid (non-zero) trace
 * context is active — e.g. when tracing is disabled, the default.
 */
public final class TraceContextUtils {

    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";
    private static final String INVALID_SPAN_ID = "0000000000000000";

    private TraceContextUtils() {
    }

    /**
     * Formats the W3C {@code traceparent} header value from {@link Span#current()}.
     *
     * @return the traceparent value, or {@code null} if the current trace context is invalid
     */
    public static String formatTraceParent() {
        SpanContext spanContext = Span.current().getSpanContext();
        if (!spanContext.isValid()) {
            return null;
        }

        String traceId = spanContext.getTraceId();
        String spanId = spanContext.getSpanId();
        if (INVALID_TRACE_ID.equals(traceId) || INVALID_SPAN_ID.equals(spanId)) {
            return null;
        }

        TraceFlags traceFlags = spanContext.getTraceFlags();
        return String.format("00-%s-%s-%02x", traceId, spanId, traceFlags.asByte() & 0xFF);
    }
}
