package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.ClientFaultCause;
import com.clickhouse.client.api.ServerException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * The one place that decides whether a ClickHouse failure means <em>ClickHouse could not serve this</em>
 * (503, worth retrying) or <em>ClickHouse rejected this statement</em> (4xx, not worth retrying).
 *
 * <p>The distinction has to be drawn identically whatever statement failed — a read, a row insert, an
 * {@code INSERT … SELECT}, a DDL, a usage-log batch — because it is what tells every caller whether to back
 * off or to give up. It was previously drawn by four byte-identical private copies plus one public one; this
 * class is those five folded into one, which is the only way a code added here reaches every path.
 *
 * <h2>Two kinds of "could not serve this"</h2>
 *
 * <p><b>Transport.</b> The statement never got an answer: the socket was refused, timed out, or the host did
 * not resolve. This half is unchanged from the copies it replaces, including the deliberately loose
 * class-name check — the driver wraps its own timeouts in types that do not extend the JDK's socket
 * exceptions, so matching on the name is what keeps a driver upgrade from silently reclassifying a timeout
 * as a statement defect.
 *
 * <p><b>Server-side resource exhaustion.</b> The statement reached ClickHouse and ClickHouse gave up on it:
 * out of memory, out of disk, out of query slots, too many parts, cancelled. These arrive as a
 * {@link ServerException} carrying a numeric code, and they were previously classified as statement
 * rejections — so a read killed by memory pressure was reported as {@code 400 bad_request} with the message
 * "check field/function/type compatibility", about a statement whose fields and types were fine. That
 * mislabels the failure twice over: it sends an operator to debug a correct statement, and it tells a
 * first-party caller the work is non-retryable, so the caller discards it — valid enrichment rows lost
 * because ClickHouse was out of memory and the runner was told the rows were bad.
 *
 * <h2>Why match on the code and not the message</h2>
 *
 * <p>Codes are stable across versions and locales; message text is neither. Anything unrecognised stays a
 * statement rejection, so a driver or server change that stops these codes being reported costs the specific
 * classification and nothing else — the failure still surfaces, just as a 4xx. That is the same trade
 * {@link ClickHouseBudget} makes for the two codes it translates, and it is the conservative direction: a
 * statement defect misreported as an outage would have every caller retry a query that can never succeed.
 *
 * <h2>Relationship to the budgets</h2>
 *
 * <p>{@code MEMORY_LIMIT_EXCEEDED} and {@code TIMEOUT_EXCEEDED} appear both here and in
 * {@link ClickHouseBudget}. That is not a conflict, because the two answer different questions and run in a
 * fixed order. This class assigns the 503 from the code alone. A call site that attached a budget then
 * catches that and asks {@code ClickHouseBudget} to re-read the cause chain, which downgrades it to the
 * budget's own 422 <b>only</b> where the server's message attributes the exhaustion to that statement's own
 * ceiling — see {@code ClickHouseBudget.ownCeiling}. A ceiling something else set (the node's, the
 * account's) keeps the 503 this class assigned, because the identical statement succeeds once that load
 * clears. Read the pair as: this class asks <em>did ClickHouse run out of something</em>; the budget asks
 * <em>was it our something</em>.
 */
public final class ClickHouseFailures {

    /** {@code UNEXPECTED_END_OF_FILE} — a part vanished under the read, typically a merge completing. */
    private static final int UNEXPECTED_END_OF_FILE = 3;

    /** {@code FILE_DOESNT_EXIST} — same race as above, seen from the filesystem side. */
    private static final int FILE_DOESNT_EXIST = 107;

    /** {@code TIMEOUT_EXCEEDED} — the server's own wall-clock ceiling, whoever set it. */
    private static final int TIMEOUT_EXCEEDED = 159;

    /** {@code CANNOT_ALLOCATE_MEMORY} — the allocator refused, below the accounted ceiling. */
    private static final int CANNOT_ALLOCATE_MEMORY = 173;

    /** {@code TOO_MANY_SIMULTANEOUS_QUERIES} — the concurrency slot pool is full. */
    private static final int TOO_MANY_SIMULTANEOUS_QUERIES = 202;

    /** {@code NO_FREE_CONNECTION} — no connection available to a shard. */
    private static final int NO_FREE_CONNECTION = 203;

    /** {@code SOCKET_TIMEOUT} — the server's own socket gave up (distinct from the client's). */
    private static final int SOCKET_TIMEOUT = 209;

    /** {@code NETWORK_ERROR} — inter-node transport failed mid-statement. */
    private static final int NETWORK_ERROR = 210;

    /** {@code ABORTED} — the server abandoned the statement. */
    private static final int ABORTED = 236;

    /** {@code MEMORY_LIMIT_EXCEEDED} — the memory ceiling, after spilling failed to contain it. */
    private static final int MEMORY_LIMIT_EXCEEDED = 241;

    /** {@code NOT_ENOUGH_SPACE} — out of disk; clears when space is reclaimed, so retryable. */
    private static final int NOT_ENOUGH_SPACE = 243;

    /** {@code TOO_MANY_PARTS} — ingest outran merges; clears as merges catch up. */
    private static final int TOO_MANY_PARTS = 252;

    /** {@code QUERY_WAS_CANCELLED} — cancelled server-side, not by the caller. */
    private static final int QUERY_WAS_CANCELLED = 394;

    private ClickHouseFailures() {
    }

    /**
     * The fault causes a ClickHouse client may replay a statement for: the driver's own defaults <b>minus</b>
     * {@code ServerRetryable}.
     *
     * <p>Transport faults are worth replaying — nothing reached the server, or nothing came back, so a
     * second attempt costs one round trip and may well succeed. A <b>server-side refusal is not</b>, and the
     * driver's default set includes it: {@code ServerException#isRetryable()} answers true for every code in
     * {@link #isResourceExhaustion}, so out of the box a statement ClickHouse refused for lack of memory is
     * re-executed up to {@code retry=3} more times.
     *
     * <p>That is the opposite of what a resource refusal calls for, on both halves of the split this class
     * draws. Where the ceiling is the service's own, the refusal is deterministic — the replays are three
     * more executions of a statement that cannot succeed. Where it is the node's, the node is already out of
     * memory and the replays are three more heavy statements aimed at it, which is how a refusal meant to
     * protect a node becomes a way of finishing it off. Either way the attempts happen below this service,
     * so they are invisible to {@code ClickHouseBudget}'s wall-clock accounting: a budget that believes it
     * spent one statement's time has spent four.
     *
     * <p>Returned fresh rather than held as a constant because the driver takes a varargs array, and a
     * shared array is caller-mutable.
     */
    public static ClientFaultCause[] transportFaults() {
        return new ClientFaultCause[] {
            ClientFaultCause.NoHttpResponse,
            ClientFaultCause.ConnectTimeout,
            ClientFaultCause.ConnectionRequestTimeout,
        };
    }

    /**
     * Whether {@code ex}, or anything in its cause chain, means ClickHouse could not serve the statement —
     * a transport failure or server-side resource exhaustion — as opposed to having rejected it.
     *
     * <p>Callers translate a {@code true} into {@code ClickHouseUnavailableException} (503) and a
     * {@code false} into {@code ClickHouseQueryException} (4xx). The chain walk guards against a
     * self-referential cause, as every copy it replaces did.
     */
    public static boolean isUnavailable(Throwable ex) {
        for (Throwable cause = ex; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
            if (cause instanceof ServerException server && isResourceExhaustion(server.getCode())) {
                return true;
            }
            final String type = cause.getClass().getSimpleName().toLowerCase();
            if (type.contains("timeout") || type.contains("connect")) {
                return true;
            }
        }
        return false;
    }

    /** Whether a ClickHouse server error code reports the server running out of a resource. */
    private static boolean isResourceExhaustion(int code) {
        return switch (code) {
            case UNEXPECTED_END_OF_FILE,
                 FILE_DOESNT_EXIST,
                 TIMEOUT_EXCEEDED,
                 CANNOT_ALLOCATE_MEMORY,
                 TOO_MANY_SIMULTANEOUS_QUERIES,
                 NO_FREE_CONNECTION,
                 SOCKET_TIMEOUT,
                 NETWORK_ERROR,
                 ABORTED,
                 MEMORY_LIMIT_EXCEEDED,
                 NOT_ENOUGH_SPACE,
                 TOO_MANY_PARTS,
                 QUERY_WAS_CANCELLED -> true;
            default -> false;
        };
    }
}
