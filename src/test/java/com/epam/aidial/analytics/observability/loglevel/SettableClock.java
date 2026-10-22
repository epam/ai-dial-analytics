package com.epam.aidial.analytics.observability.loglevel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock a test moves explicitly, so a {@code validTill} is crossed deterministically instead of by sleeping. */
final class SettableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    SettableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    private SettableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new SettableClock(instant, newZone);
    }

    /** Moves the clock forward. */
    void advance(Duration amount) {
        instant = instant.plus(amount);
    }
}
