package com.epam.aidial.analytics.service.enrichment;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The clock of one materialize call: the single deadline every statement after the first probe runs on, and
 * the stage split the outcome line and the soft-deadline WARN report.
 *
 * <p>Started immediately before the first window probe, so the probe's own time counts against the budget
 * the clamp re-probe, the narrowing re-probes and the writes then share. Confined to the call's thread.
 */
final class MaterializeCall {

    /** Which accumulator a timed statement adds to. */
    enum Stage {
        /** The first window probe. */
        PROBE,
        /** Every later probe: the clamp re-probe and each narrowing re-probe. */
        REPROBE,
        /** Every write attempt. */
        WRITE
    }

    private final LongSupplier nanoClock;
    private final long startedNanos;
    /** Nanoseconds spent per {@link Stage}, indexed by ordinal. */
    private final long[] stageNanos = new long[Stage.values().length];

    MaterializeCall(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.startedNanos = nanoClock.getAsLong();
    }

    /** A call whose clock starts now. */
    static MaterializeCall start() {
        return new MaterializeCall(System::nanoTime);
    }

    /** How long the call has run since its first probe began. */
    Duration elapsed() {
        return Duration.ofNanos(nanoClock.getAsLong() - startedNanos);
    }

    /** Runs {@code body}, adding its duration to {@code stage} whether it returns or throws. */
    <T> T time(Stage stage, Supplier<T> body) {
        final long started = nanoClock.getAsLong();
        try {
            return body.get();
        } finally {
            stageNanos[stage.ordinal()] += nanoClock.getAsLong() - started;
        }
    }

    long probeMs() {
        return millis(Stage.PROBE);
    }

    long reprobeMs() {
        return millis(Stage.REPROBE);
    }

    long writeMs() {
        return millis(Stage.WRITE);
    }

    long totalMs() {
        return elapsed().toMillis();
    }

    private long millis(Stage stage) {
        return stageNanos[stage.ordinal()] / 1_000_000L;
    }
}
