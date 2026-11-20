package com.epam.aidial.analytics.service;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The operator requeue's write: prompt, and reported as unavailable when ClickHouse's coordination is gone
 * (openspec/specs/clickhouse-replication/spec.md).
 */
class ClickHouseIngestFailureLedgerTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<IngestFailureEvent> EVENTS = List.of(IngestFailureEvent.requeued(
            IngestFailureKind.BLOB, "logs/2026/01/01/0.log.gz", LocalDate.parse("2026-01-01"), AT, AT));

    private final UsageLogWriter writer = mock(UsageLogWriter.class);
    private final ClickHouseIngestFailureLedger ledger = new ClickHouseIngestFailureLedger(writer);

    @Test
    void promptAppendWritesPromptly() {
        ledger.appendPromptly(EVENTS);

        verify(writer).insertPromptly(eq(ClickHouseIngestFailureLedger.TABLE), anyList());
        verify(writer, never()).insert(eq(ClickHouseIngestFailureLedger.TABLE), anyList());
    }

    @Test
    void promptAppendWithKeeperGoneIsUnavailable() {
        doThrow(new IllegalStateException("ClickHouse insert failed", new ServerException(242, "read-only", 500, "q")))
                .when(writer).insertPromptly(eq(ClickHouseIngestFailureLedger.TABLE), anyList());

        assertThatThrownBy(() -> ledger.appendPromptly(EVENTS)).isInstanceOf(ClickHouseUnavailableException.class);
    }

    @Test
    void promptAppendRefusedForAnotherReasonIsNotRelabelled() {
        doThrow(new IllegalStateException("ClickHouse insert failed", new ServerException(62, "syntax", 400, "q")))
                .when(writer).insertPromptly(eq(ClickHouseIngestFailureLedger.TABLE), anyList());

        assertThatThrownBy(() -> ledger.appendPromptly(EVENTS))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClickHouseUnavailableException.class);
    }
}
