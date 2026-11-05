package com.epam.aidial.analytics.sync.mapping;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Reports one column's coercion failures: each increments {@value #METRIC}, and a WARN names the column, the kind, the
 * JSON type the producer sent and the blob — never the value, since a field mapped as a number can carry arbitrary
 * producer text.
 *
 * <p>At most one line per {@link #WINDOW}: the first failure in a window is logged, the rest are only counted, and the
 * next logged line carries how many were not. One instance per compiled column, a set fixed at startup, so the limiter
 * needs no key map and no bound. Thread-safe: the window is claimed by compare-and-set.
 */
@Slf4j
final class CoercionFailures {

    /** The counter of coercion failures, tagged by {@code table}, {@code column} and {@code kind}. */
    static final String METRIC = "sync.values.coercion.failures";

    /** How long one logged line stands for its column. */
    static final Duration WINDOW = Duration.ofMinutes(1);

    /** {@link #admit()} result for a failure that must not be logged. */
    static final long SUPPRESSED = -1L;

    private final String table;
    private final String column;
    private final ColumnKind kind;
    private final Counter counter;
    private final LongSupplier nanoClock;
    private final long windowNanos;

    /** When the last line was logged, or {@code Long.MIN_VALUE} before the first. */
    private final AtomicLong lastLogged = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressed = new AtomicLong();

    CoercionFailures(String table, String column, ColumnKind kind, MeterRegistry registry) {
        this(table, column, kind, registry, System::nanoTime);
    }

    CoercionFailures(String table, String column, ColumnKind kind, MeterRegistry registry, LongSupplier nanoClock) {
        this.table = table;
        this.column = column;
        this.kind = kind;
        this.counter = Counter.builder(METRIC).tag("table", table).tag("column", column).tag("kind", kind.token()).register(registry);
        this.nanoClock = nanoClock;
        this.windowNanos = WINDOW.toNanos();
    }

    /**
     * Counts a failure, and logs it unless a line for this column was logged within the window.
     *
     * @param nodeType   the JSON type of the producer value, e.g. {@code STRING}; {@code DERIVED} when a deriver reported it
     * @param sourceFile the blob the record came from
     */
    void report(String nodeType, String sourceFile) {
        counter.increment();
        long notLogged = admit();
        if (notLogged == SUPPRESSED) {
            return;
        }
        log.warn("Value of kind '{}' for column '{}' of table '{}' could not be read from a {} producer value in {}; "
                + "it took the column's fallback ({} further failure(s) since the last line were not logged)",
                kind.token(), column, table, nodeType, sourceFile, notLogged);
    }

    /**
     * Decides whether this failure is logged: the first in a window is, and carries how many were suppressed since the
     * previous logged one; the rest are suppressed.
     *
     * @return the failures not logged since the previous line, or {@link #SUPPRESSED}
     */
    long admit() {
        long now = nanoClock.getAsLong();
        long last = lastLogged.get();
        if ((last != Long.MIN_VALUE && now - last < windowNanos) || !lastLogged.compareAndSet(last, now)) {
            suppressed.incrementAndGet();
            return SUPPRESSED;
        }
        return suppressed.getAndSet(0);
    }
}
