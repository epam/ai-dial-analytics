package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobClient;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.PollProperties;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.observability.TestClientSpanTracer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.LocalDate;

/**
 * Builds the poller and the reconciliation run over caller-supplied parts, for an integration test outside
 * this package (both depend on the package-private {@link BlobPrefixResolver}, and the poller's cycle entry
 * point is package-private).
 */
public final class IngestPathsTestAccess {

    private IngestPathsTestAccess() {
    }

    /** Runs one poll cycle of a fresh poller and reports whether it succeeded. */
    public static boolean pollOnce(BlobClient blobClient, BlobIngestor ingestor, UsageLogWriter writer, SyncProperties syncProps,
            PollProperties pollProps, IngestLedger ledger) {
        BlobPollingService poller = new BlobPollingService(blobClient, ingestor, writer, syncProps, pollProps,
                new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()), new SimpleMeterRegistry(), TestClientSpanTracer.noop(),
                ledger);
        return poller.runOnce().success();
    }

    /** Runs the reconciliation of {@code day}, skipping what the ledger records, as the scheduled run does. */
    public static DataSyncService.RunSummary reconcile(BlobClient blobClient, BlobIngestor ingestor, UsageLogWriter writer,
            SyncProperties syncProps, IngestLedger ledger, LocalDate day) {
        DataSyncService service = new DataSyncService(blobClient, ingestor, writer, syncProps,
                new BlobPrefixResolver(syncProps, new SimpleMeterRegistry()), new SimpleMeterRegistry(), TestClientSpanTracer.noop(),
                ledger);
        return service.runForDate(day);
    }
}
