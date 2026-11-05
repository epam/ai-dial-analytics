package com.epam.aidial.analytics.configuration;

import java.time.Duration;

/** The positive-duration rule the ClickHouse replication keys share. */
final class DurationRules {

    private DurationRules() {
    }

    /** {@code value}, or {@code fallback} when unset; refuses zero and negative durations, naming {@code property}. */
    static Duration positive(String property, Duration value, Duration fallback) {
        final Duration effective = value == null ? fallback : value;
        if (effective.isZero() || effective.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration, but was " + effective);
        }
        return effective;
    }
}
