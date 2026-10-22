package com.epam.aidial.analytics.web.handler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decides which refusal lines {@link DefaultExceptionHandler} writes: at most one per key per window, where
 * the key is (handler, {@code error} code, best-matching path pattern). The first occurrence in a window is
 * admitted; the rest are counted, and the first occurrence after the window rolls is admitted together with
 * that count.
 *
 * <p><b>Why.</b> A refusal such as a runner contract skew repeats on every call until a deploy fixes it; a
 * stack per call buried everything else in the log and said nothing the first line had not.
 *
 * <p><b>Bounded.</b> The path pattern is the matched handler mapping, never the raw path, so the key space is
 * the product of handlers, codes and mappings — all fixed by the build. {@link #MAX_KEYS} is a backstop
 * against a mistake in that reasoning: past it, expired windows are dropped, and if the map is still full the
 * refusal is folded into one overflow key per (handler, code).
 *
 * <p><b>Per replica.</b> Held in memory, so each replica logs its own first line; the pod name on the OTLP
 * resource tells them apart.
 *
 * <p>Thread-safe: each key's window is updated inside {@link ConcurrentHashMap#compute}, so two concurrent
 * refusals can neither both be admitted nor lose a suppressed count.
 */
final class RefusalLogLimiter {

    /** How long one admitted line stands for its key. */
    static final Duration WINDOW = Duration.ofSeconds(60);

    /** Backstop on tracked keys; see the class note. */
    static final int MAX_KEYS = 1024;

    /** The path pattern the overflow key uses once {@link #MAX_KEYS} is reached. */
    static final String OVERFLOW_PATTERN = "*";

    /** {@link #admit} result for a refusal that must not be logged. */
    static final long SUPPRESSED = -1L;

    private final LongSupplier nanoClock;
    private final long windowNanos;
    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    RefusalLogLimiter(LongSupplier nanoClock) {
        this(nanoClock, WINDOW);
    }

    RefusalLogLimiter(LongSupplier nanoClock, Duration window) {
        this.nanoClock = nanoClock;
        this.windowNanos = window.toNanos();
    }

    /**
     * Registers one refusal and decides whether it is logged.
     *
     * @return {@link #SUPPRESSED} when it must not be logged; otherwise how many refusals of the same key were
     *     suppressed since the previous logged line ({@code 0} for the first one)
     */
    long admit(String handler, String code, String pathPattern) {
        final long now = nanoClock.getAsLong();
        final Key key = keyFor(new Key(handler, code, pathPattern), now);
        final long[] decision = {SUPPRESSED};
        windows.compute(key, (ignored, window) -> {
            if (window == null) {
                decision[0] = 0L;
                return new Window(now);
            }
            if (now - window.startedNanos >= windowNanos) {
                decision[0] = window.suppressed;
                window.startedNanos = now;
                window.suppressed = 0L;
            } else {
                window.suppressed++;
            }
            return window;
        });
        return decision[0];
    }

    /** Number of keys tracked; for tests. */
    int trackedKeys() {
        return windows.size();
    }

    private Key keyFor(Key key, long now) {
        if (windows.size() < MAX_KEYS || windows.containsKey(key)) {
            return key;
        }
        // An expired window's pending count is dropped with it: losing a count is cheaper than unbounded growth.
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedNanos >= windowNanos);
        return windows.size() < MAX_KEYS ? key : new Key(key.handler(), key.code(), OVERFLOW_PATTERN);
    }

    private record Key(String handler, String code, String pathPattern) {
    }

    /** Mutated only inside {@code compute} for its own key; volatile for the expiry sweep, which reads it outside. */
    private static final class Window {

        private volatile long startedNanos;
        private volatile long suppressed;

        private Window(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }
}
