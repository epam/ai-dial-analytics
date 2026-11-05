package com.epam.aidial.analytics.service;

/**
 * Who made an ingest attempt: the path, and for a backfill the run, so the failure ledger can attribute a failure to
 * the backfill run that hit it.
 *
 * @param path          the ingest path
 * @param backfillRunId the backfill run's id; empty unless {@code path} is {@link IngestPath#BACKFILL}
 */
public record IngestSource(IngestPath path, String backfillRunId) {

    public IngestSource {
        backfillRunId = backfillRunId == null ? "" : backfillRunId;
    }

    /** An attempt by {@code path}, attributed to no backfill run. */
    public static IngestSource of(IngestPath path) {
        return new IngestSource(path, "");
    }

    /** An attempt by the backfill run {@code runId}. */
    public static IngestSource backfill(String runId) {
        return new IngestSource(IngestPath.BACKFILL, runId);
    }
}
