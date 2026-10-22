package com.epam.aidial.analytics.web.flight;

/**
 * The concurrent Flight stream cap was reached; the request was rejected before anything touched
 * ClickHouse. Mapped to gRPC {@code RESOURCE_EXHAUSTED} (retryable) by {@link FlightErrorMapper}.
 */
public class FlightStreamSaturatedException extends RuntimeException {

    public FlightStreamSaturatedException(String message) {
        super(message);
    }
}
