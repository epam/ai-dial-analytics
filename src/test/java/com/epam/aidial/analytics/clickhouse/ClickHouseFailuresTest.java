package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.ClientFaultCause;
import com.clickhouse.client.api.ServerException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 503-versus-4xx line, which decides whether every caller of ClickHouse backs off or gives up. The
 * cases that matter are the resource codes: they used to fall on the "ClickHouse rejected your statement"
 * side, so a first-party caller was told its valid work was malformed and discarded it.
 */
class ClickHouseFailuresTest {

    private static ServerException serverError(int code) {
        return new ServerException(code, "server said no", 500, "query-1");
    }

    /** Self-referential cause, which a "walk until the cause repeats" loop must not spin on. */
    private static final class SelfCausing extends RuntimeException {
        private SelfCausing() {
            super("loops forever");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    @Test
    void transportFailuresAreUnavailable() {
        assertThat(ClickHouseFailures.isUnavailable(new ConnectException("refused"))).isTrue();
        assertThat(ClickHouseFailures.isUnavailable(new SocketTimeoutException("read timed out"))).isTrue();
        assertThat(ClickHouseFailures.isUnavailable(new UnknownHostException("no such host"))).isTrue();
    }

    /**
     * The driver wraps its own timeouts in types that do not extend the JDK's socket exceptions, so the
     * name-shaped check has to stay. This is the case that keeps a driver upgrade from silently
     * reclassifying a timeout as a statement defect.
     */
    @Test
    void driverTimeoutTypesAreRecognizedByName() {
        class ClientTimeoutException extends RuntimeException {
        }

        assertThat(ClickHouseFailures.isUnavailable(new ClientTimeoutException())).isTrue();
    }

    /**
     * The fix this class exists for. Every one of these means ClickHouse ran out of something, not that the
     * statement was wrong, so the caller must be told to retry rather than to discard its work.
     */
    @Test
    void serverSideResourceExhaustionIsUnavailable() {
        assertThat(ClickHouseFailures.isUnavailable(serverError(241))).isTrue();  // MEMORY_LIMIT_EXCEEDED
        assertThat(ClickHouseFailures.isUnavailable(serverError(159))).isTrue();  // TIMEOUT_EXCEEDED
        assertThat(ClickHouseFailures.isUnavailable(serverError(173))).isTrue();  // CANNOT_ALLOCATE_MEMORY
        assertThat(ClickHouseFailures.isUnavailable(serverError(202))).isTrue();  // TOO_MANY_SIMULTANEOUS_QUERIES
        assertThat(ClickHouseFailures.isUnavailable(serverError(203))).isTrue();  // NO_FREE_CONNECTION
        assertThat(ClickHouseFailures.isUnavailable(serverError(209))).isTrue();  // SOCKET_TIMEOUT
        assertThat(ClickHouseFailures.isUnavailable(serverError(210))).isTrue();  // NETWORK_ERROR
        assertThat(ClickHouseFailures.isUnavailable(serverError(236))).isTrue();  // ABORTED
        assertThat(ClickHouseFailures.isUnavailable(serverError(243))).isTrue();  // NOT_ENOUGH_SPACE
        assertThat(ClickHouseFailures.isUnavailable(serverError(252))).isTrue();  // TOO_MANY_PARTS
        assertThat(ClickHouseFailures.isUnavailable(serverError(394))).isTrue();  // QUERY_WAS_CANCELLED
    }

    /**
     * A part vanishing under a read as a merge completes. These two are the reason dropping
     * {@code ServerRetryable} needed a compensating change: the driver used to retry them and this class
     * did not recognise them, so removing its retry would have surfaced a merge race as
     * {@code 400 bad_request} — a valid query told it was malformed, which is the exact mislabelling this
     * class exists to prevent.
     */
    @Test
    void mergeRaceIsUnavailableRatherThanRejected() {
        assertThat(ClickHouseFailures.isUnavailable(serverError(3))).isTrue();    // UNEXPECTED_END_OF_FILE
        assertThat(ClickHouseFailures.isUnavailable(serverError(107))).isTrue();  // FILE_DOESNT_EXIST
    }

    /**
     * This classifier never reads the message, and must not start: {@code ClickHouseBudget} depends on it
     * answering the same way for both kinds of memory refusal. The order is fixed — this class assigns the
     * 503 from the code, and a call site with a ceiling attached then decides whether the refusal was that
     * ceiling's. If this class began discriminating too, a ceiling the service did not set would have two
     * places to be classified and they could disagree.
     */
    @Test
    void theMemoryCodeIsUnavailableWhoeverSetTheCeiling() {
        final String ownCeiling = "Code: 241. DB::Exception: Query memory limit exceeded: maximum: 2.00 GiB";
        final String nodeCeiling = "Code: 241. DB::Exception: Total memory limit exceeded: maximum: 7.45 GiB";

        assertThat(ClickHouseFailures.isUnavailable(new ServerException(241, ownCeiling, 500, "q"))).isTrue();
        assertThat(ClickHouseFailures.isUnavailable(new ServerException(241, nodeCeiling, 500, "q"))).isTrue();
    }

    /**
     * Statement defects stay rejections. This is the conservative half of the trade: an unrecognised code is
     * a 4xx, because a real defect misreported as an outage would have every caller retry a statement that
     * can never succeed.
     */
    @Test
    void statementDefectsAreNotUnavailable() {
        assertThat(ClickHouseFailures.isUnavailable(serverError(47))).isFalse();   // UNKNOWN_IDENTIFIER
        assertThat(ClickHouseFailures.isUnavailable(serverError(60))).isFalse();   // UNKNOWN_TABLE
        assertThat(ClickHouseFailures.isUnavailable(serverError(62))).isFalse();   // SYNTAX_ERROR
        assertThat(ClickHouseFailures.isUnavailable(serverError(43))).isFalse();   // ILLEGAL_TYPE_OF_ARGUMENT
        assertThat(ClickHouseFailures.isUnavailable(new IllegalStateException("no server exception here")))
                .isFalse();
    }

    /** Wrapped as deep as the insert, scan and DDL paths wrap it — the code must still be found. */
    @Test
    void findsTheCodeAnywhereInTheCauseChain() {
        assertThat(ClickHouseFailures.isUnavailable(
                new IllegalStateException("ClickHouse insert failed",
                        new RuntimeException("wrapped", serverError(241))))).isTrue();
        assertThat(ClickHouseFailures.isUnavailable(
                new IllegalStateException("outer", new RuntimeException("inner", serverError(62)))))
                .isFalse();
    }

    @Test
    void terminatesOnSelfReferentialCauseChain() {
        assertThat(ClickHouseFailures.isUnavailable(new SelfCausing())).isFalse();
        assertThat(ClickHouseFailures.isUnavailable(new IllegalStateException("wrapper", new SelfCausing())))
                .isFalse();
    }

    @Test
    void handlesNull() {
        assertThat(ClickHouseFailures.isUnavailable(null)).isFalse();
    }

    /**
     * The driver replays a statement for these causes, and a server-side refusal must not be among them.
     *
     * <p>{@code ServerException#isRetryable()} answers true for {@code MEMORY_LIMIT_EXCEEDED} among others
     * (not for every code this class recognises — 173, 236, 243 and 394 were never in the driver's list),
     * and the driver ships {@code ServerRetryable} in its default set with {@code retry=3} — so out of the
     * box a refusal is executed four times. Where the ceiling is ours that is three executions of a
     * statement that cannot succeed; where it is the node's it is three more heavy statements aimed at a
     * node already out of memory. Both are invisible to the budget's wall-clock accounting.
     *
     * <p>The transport causes stay, because nothing reached the server or nothing came back and a second
     * attempt may well succeed — dropping them would take retry away from usage-log ingest, which depends
     * on the client replaying a batch.
     */
    @Test
    void serverSideRefusalIsNotAmongTheCausesTheDriverReplays() {
        assertThat(ClickHouseFailures.transportFaults())
                .containsExactly(ClientFaultCause.NoHttpResponse,
                        ClientFaultCause.ConnectTimeout,
                        ClientFaultCause.ConnectionRequestTimeout);
    }

    /** Callers get their own array, so one cannot quietly re-arm retries for the whole service. */
    @Test
    void theCausesAreHandedOutFreshRatherThanShared() {
        final ClientFaultCause[] first = ClickHouseFailures.transportFaults();
        first[0] = ClientFaultCause.ServerRetryable;

        assertThat(ClickHouseFailures.transportFaults()).doesNotContain(ClientFaultCause.ServerRetryable);
    }
}
