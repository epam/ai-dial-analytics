package com.epam.aidial.analytics.service.otel;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The three OpenTelemetry signals a sink may land. The {@link #code()} is both the wire value of the
 * {@code signals} member and the last path segment an OTLP/HTTP exporter appends to the sink's base URL
 * ({@code /v1/logs}, {@code /v1/traces}, {@code /v1/metrics}), so the two can never drift apart.
 *
 * <p>Stored by {@link Enum#name()} (uppercase) in PostgreSQL, matching the
 * {@code @Enumerated(EnumType.STRING)} convention the rest of this catalog uses.
 */
public enum OtelSignal {
    LOGS("logs"),
    TRACES("traces"),
    METRICS("metrics");

    private final String code;

    OtelSignal(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** Resolves a wire code (e.g. {@code "logs"}) to its signal, or null when {@code code} matches none. */
    public static OtelSignal fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (final OtelSignal signal : values()) {
            if (signal.code.equals(code)) {
                return signal;
            }
        }
        return null;
    }
}
