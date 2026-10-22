package com.epam.aidial.analytics.exception;

/**
 * Raised when an OTLP sink does not exist, or when an export names a signal the sink did not enable.
 * Mapped by {@code DefaultExceptionHandler} to {@code 404 not_found}.
 *
 * <p>The two conditions share one code deliberately: an exporter pointed at a sink that was never created
 * and one pointed at a signal that sink does not land have the same fix — correct the endpoint URL — and
 * distinguishing them would tell an unauthenticated prober which sink names exist.
 */
public class OtelSinkNotFoundException extends RuntimeException {

    public OtelSinkNotFoundException(String message) {
        super(message);
    }
}
