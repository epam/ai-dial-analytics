package com.epam.aidial.analytics.web.flight;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Concurrency cap for in-flight Flight result streams: a permit
 * spans the {@code doGet} execution window only — {@code getFlightInfo} and metadata calls take
 * none. Non-blocking: a saturated gate fails fast with {@link FlightStreamSaturatedException}
 * before any ClickHouse call; connections themselves are uncapped and the HTTP API is unaffected.
 */
@Component
@ConditionalOnProperty(prefix = "flight", name = "enabled", havingValue = "true")
public class FlightStreamGate {

    private final Semaphore permits;

    public FlightStreamGate(FlightProperties properties) {
        this.permits = new Semaphore(properties.maxConcurrentStreams());
    }

    /** Acquires a stream slot or fails fast (mapped to RESOURCE_EXHAUSTED). */
    public void acquire() {
        if (!permits.tryAcquire()) {
            throw new FlightStreamSaturatedException(
                    "concurrent Flight stream cap reached; retry after a stream completes");
        }
    }

    public void release() {
        permits.release();
    }

    /** Permits currently available — used by tests to assert the no-leak invariant. */
    public int availablePermits() {
        return permits.availablePermits();
    }
}
