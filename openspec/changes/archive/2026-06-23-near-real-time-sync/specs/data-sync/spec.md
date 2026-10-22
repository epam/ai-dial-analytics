## ADDED Requirements

### Requirement: Near-real-time polling ingestion

The service SHALL ingest new log blobs in near-real-time by polling blob storage on a short,
configurable interval, using ONLY the provider-agnostic blob-listing abstraction and WITHOUT
depending on any cloud-provider-specific notification service (e.g. Azure Event Grid, AWS S3 event
notifications, GCS Pub/Sub) or any externally provisioned infrastructure. On each poll cycle the
service SHALL list the active date-derived prefix(es) — the current day, and within a configurable
post-midnight grace window also the previous day — and SHALL select for ingestion every blob whose
last-modified time is at or after a moving watermark less a configurable overlap. Each selected blob
SHALL be ingested through the same parse → map → batch-insert pipeline used by the scheduled path,
and the watermark SHALL then advance to the greatest last-modified time observed. The service MUST
read each blob's last-modified time from the listing itself and MUST NOT require a per-blob metadata
fetch. Correctness under overlap, last-modified ties, clock skew, and restarts SHALL rest on the
target table's primary-key deduplication rather than on exactly-once discovery: the service SHALL
keep only an in-memory watermark (initialized on startup to the current time less a configurable
lookback) and MUST NOT require a persisted cursor. A failure to ingest a single blob (e.g. a corrupt
file) MUST NOT abort the poll cycle or hold back the watermark: the service SHALL count and skip that
blob, continue ingesting the remaining blobs, and still advance the watermark, so that one bad blob
cannot wedge the poller — the skipped blob remains eligible for retry within the overlap window and is
otherwise recovered by the reconciliation path. A failure to list blobs (e.g. the store is
unreachable) SHALL instead leave the watermark unchanged so the next cycle re-covers the window. The
poller MUST be controlled by an enable flag, MUST run in-process with no HTTP trigger, and MUST NOT
allow a new poll cycle to start while a previous one is still in progress.

#### Scenario: New blob within the window is ingested

- **WHEN** the poller is enabled and a not-yet-ingested log blob under the active prefix has a
  last-modified time at or after the watermark
- **THEN** the service ingests that blob's rows and advances the watermark to that blob's
  last-modified time

#### Scenario: Blob older than the watermark is not ingested

- **WHEN** a blob under the active prefix has a last-modified time before the watermark less the
  configured overlap
- **THEN** the service does not ingest that blob

#### Scenario: Re-observed blob does not duplicate rows

- **WHEN** a blob is observed again within the watermark overlap, on a clock-skew boundary, or after
  a restart re-scans the lookback window
- **THEN** the blob is re-inserted at most as duplicate rows that the target table's
  `ReplacingMergeTree` engine collapses on merge, so no duplicate row survives

#### Scenario: A bad blob does not wedge the poller

- **WHEN** one blob in the active prefix fails to ingest (e.g. it is corrupt) while other newer blobs
  are present
- **THEN** the service counts and skips the failing blob, ingests the other blobs, and advances the
  watermark, so the failing blob does not block subsequent ingestion

#### Scenario: A listing failure holds the watermark

- **WHEN** a poll cycle cannot list the active prefix (e.g. the store is unreachable)
- **THEN** the service leaves the watermark unchanged so the next cycle re-covers the same window

#### Scenario: Poller disabled

- **WHEN** the poller enable flag is not set
- **THEN** the service does not poll for new blobs and ingestion proceeds only via the scheduled
  reconciliation path

## MODIFIED Requirements

### Requirement: Scheduled daily ingestion

The service SHALL run a data-sync job automatically on a daily schedule driven by a configurable
cron expression. This scheduled full-prefix run serves as a **reconciliation safety net** that
complements the near-real-time polling path: a once-daily full listing of the target prefix
re-ingests any blobs the poller missed (e.g. during downtime longer than the poller's restart
lookback window), deduplicated by the target table's engine. The job MUST run in-process and MUST
NOT be triggerable via an HTTP endpoint in this phase. The scheduler MUST be configured such that a
new run cannot start while a previous run is still in progress.

#### Scenario: Job runs on schedule

- **WHEN** the configured cron time is reached and no sync is currently running
- **THEN** the service starts a reconciliation sync run without any external trigger

#### Scenario: Overlapping runs are prevented

- **WHEN** the cron time is reached while a previous sync run is still in progress
- **THEN** the service does not start a second concurrent run

#### Scenario: Reconciliation re-ingests what the poller missed

- **WHEN** the daily run lists the target prefix and some of its blobs were already ingested by the
  poller
- **THEN** the run re-inserts every record and the target table's engine collapses the duplicates,
  so blobs missed by the poller are recovered and no duplicate rows survive

### Requirement: Ingest observability

The service SHALL expose ingest observability through logs and Micrometer metrics, including counts
of files processed, rows inserted, rows skipped (unparseable lines), rows dropped (records lacking the
deduplication key), run outcome, run duration, and the timestamp of the last successful run. The
service SHALL additionally expose poller metrics: poll cycles executed, blobs discovered and ingested
per cycle, poll failures, and the current watermark lag (how far the watermark trails the current
time). Because deduplication is performed by the table engine rather than by skipping whole files, the
files-skipped metric SHALL NOT denote already-ingested files. No HTTP status endpoint is provided in
this phase.

#### Scenario: Metrics reflect a completed run

- **WHEN** a sync run or poll cycle completes
- **THEN** the corresponding metrics (files processed, rows inserted/skipped/dropped, run/poll
  outcome, duration, last-success timestamp, and poller discovery/lag counters) are updated to
  reflect that run

#### Scenario: Dropped records are counted

- **WHEN** a run encounters records lacking a deduplication key
- **THEN** the dropped-rows metric is incremented by the number of such records
