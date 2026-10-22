package com.epam.aidial.analytics.exception;

/**
 * Raised when any {@code /v1/otel/**} endpoint is called while the OTLP surface is disabled
 * ({@code otel-sink.enabled=false}). Mapped by {@code DefaultExceptionHandler} to
 * {@code 503 otel_disabled}.
 */
public class OtelDisabledException extends RuntimeException {

    public OtelDisabledException(String message) {
        super(message);
    }
}
