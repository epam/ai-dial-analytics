package com.epam.aidial.analytics.exception;

import java.util.function.Predicate;

/**
 * Walks an exception's cause chain. Exists so the several places that ask "was this failure ultimately
 * caused by X?" share one traversal instead of re-deriving it: a failure crosses two or three wrapping
 * layers on its way out of the ingest write path ({@code ServerException} → {@code IllegalStateException} /
 * {@link ClickHouseStorageFullException} → {@code RowBatchSink.FlushException}), so the question is never
 * answerable by an {@code instanceof} on the exception at hand.
 */
public final class CauseChain {

    /** Hop ceiling: far above any real wrapping depth, low enough to end a cycle. */
    private static final int MAX_DEPTH = 64;

    private CauseChain() {
    }

    /**
     * Whether {@code error} or any of its causes satisfies {@code match}.
     *
     * <p>The exception at hand is inspected <em>before</em> the chain is advanced, so a self-referential
     * top-level exception is still matched, and the traversal is depth-bounded rather than stopping at
     * "the cause repeats", so a cycle of any length terminates instead of spinning.
     *
     * @param error the exception to inspect; {@code null} matches nothing
     * @param match the test applied to each exception in the chain
     * @return true if any exception in the chain satisfies {@code match}
     */
    public static boolean anyMatch(Throwable error, Predicate<Throwable> match) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
            if (match.test(cause)) {
                return true;
            }
            Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return false;
    }
}
