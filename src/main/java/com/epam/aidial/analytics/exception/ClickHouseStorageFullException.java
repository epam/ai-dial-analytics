package com.epam.aidial.analytics.exception;

/**
 * Raised when ClickHouse refuses a write because it has no disk space left (server error code 243,
 * {@code NOT_ENOUGH_SPACE}). Distinguished from a generic insert failure because the two call for opposite
 * responses: an ordinary failure is worth retrying, whereas writing again into an exhausted volume only adds
 * load and partial parts to a store that cannot accept them. The ingest paths use it to name the cause in
 * logs and metrics, and the backfill uses it to stop a run instead of attempting every remaining day.
 *
 * <p>Not mapped by {@code DefaultExceptionHandler}: the {@code data-sync} write path runs on the scheduler
 * and the backfill worker, never on a request thread, so this exception never has to render as an
 * {@code ErrorView}.
 */
public class ClickHouseStorageFullException extends RuntimeException {

    public ClickHouseStorageFullException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Whether {@code error} is, or was caused by, a storage-full refusal. Callers that catch a wrapped
     * failure (the ingest paths catch {@code RowBatchSink.FlushException}) use this rather than an
     * {@code instanceof} on the top-level exception.
     *
     * @param error the exception to inspect; {@code null} is not a storage-full failure
     * @return true if the chain contains a {@link ClickHouseStorageFullException}
     */
    public static boolean isIn(Throwable error) {
        return CauseChain.anyMatch(error, ClickHouseStorageFullException.class::isInstance);
    }
}
