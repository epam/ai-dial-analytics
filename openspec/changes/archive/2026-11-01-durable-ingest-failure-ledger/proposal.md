## Why

A blob that fails to ingest can go permanently missing from analytics, and the only trace it leaves is a log
line and an aggregate counter. On the reconciliation run and the backfill, one unreadable or unmappable blob
aborts the rest of its day. If the failure is deterministic, every re-trigger stops at the same blob. On the
poller, a failed blob is retried only while it stays inside the overlap window. A reconciliation day that
fails is never retried automatically, and backfill run state is lost on restart. Nothing durable records which
blobs or days are still missing. Every ingest path is idempotent (the ingest ledger plus `ReplacingMergeTree`),
so the missing piece is not recovery but **knowing what to recover and retrying it without an operator**.

This is the first of three changes that close silent data loss on the ingest path. It covers blob- and
day-level failures. Isolating a row that ClickHouse rejects (a poison row) and recording skipped records are
the second change; observable value coercion is the third.

## What Changes

- **Per-blob isolation on every path.** A blob that fails to read or map no longer aborts its reconciliation
  day or backfill day. The failure is recorded and the day continues with the next blob, as the poller already
  does. Only day-level faults still fail a day: the listing fails, preparing the day fails, a batch write or
  ledger write fails, or the disk is full.
- **A durable failure ledger.** A new ClickHouse table, `_dial_internal_failed_ingest`, sits next to the ingest
  ledger. It is an append-only event log covering failed **blobs** (from every path) and failed **days** (from
  the reconciliation run, the backfill and the retry sweep). Each failure event carries its classification
  (`transient` or `deterministic`), a stable reason code, a short message and the path that saw it. State is
  derived from the events: `pending`, `parked` or `resolved`. A blob resolves once the ingest ledger records it.
  A day resolves when a run of that day succeeds.
- **Classify and park.** An unambiguous deterministic failure (a corrupt gzip header or CRC, a mapping or
  deriver fault, a blob that no longer exists) is parked at once. Every other failure, an `EOF` on read
  included, is transient. A transient failure is retried with exponential backoff and parked once it reaches
  the attempt limit.
- **A retry sweep.** A new scheduled job retries due `pending` entries. It re-ingests a single blob through the
  shared pipeline, or re-runs a day in the default skip-recorded mode. It also refreshes the
  outstanding-failure gauges. A failure event that cannot be written because ClickHouse is unavailable is held
  in memory and written by a later sweep.
- **Operator surface.**
  - `GET /v1/data-sync/failures` lists outstanding (`pending` and `parked`) entries, with filters. It requires
    `REGISTRY_READ`.
  - `POST /v1/data-sync/failures/retry` re-queues matching outstanding entries, so the next sweep attempts them
    with their attempt count reset. Typical use is after a mapping fix. It requires `DATA_SYNC_OPERATE`.
- **Backfill status** reports `failedBlobs`. A day with any failed blob still counts as failed and is still
  named in `failedDates`. The rest of that day is ingested.
- **Scheduled reconciliation**: a failed day is now retried automatically by the sweep, not only by an
  operator backfill.
- **Metrics**:
  - gauge `sync.failures.outstanding{kind,state}`;
  - counters `sync.failures.recorded{kind,path,reason}` and `sync.retry.attempts{kind,outcome}`;
  - gauge `sync.failures.unwritten`.
- **New config** `sync.retry.*`: `enabled`, `cron`, `initial-backoff`, `max-backoff`, `max-attempts`,
  `max-per-sweep`.

Nothing here is a breaking change. Every response member added is additive. The one behavior change is that a
day with a bad blob now ingests that day's other blobs instead of stopping at the bad one.

Out of scope:
- isolating rows ClickHouse rejects inside a batch (the second change);
- recording malformed, oversized or keyless records (the second change);
- coercion metrics (the third change);
- persisting backfill run history. The failure ledger is the durable record of what is missing, so
  `failedDates` no longer has to survive a restart;
- a per-day coverage report.

## Capabilities

### New Capabilities

- `data-sync-failures`: the persisted failure ledger, failure classification and the state derivation, the
  retry sweep with backoff and parking, the failures list and retry endpoints, and the failure metrics.

### Modified Capabilities

- `data-sync`:
  - "Scheduled daily ingestion": a blob failure no longer fails the day; a failed day is recorded and retried
    automatically.
  - "Near-real-time polling ingestion": a failed blob is recorded in the failure ledger and retried by the
    sweep beyond the overlap window.
  - "Ingested blobs are recorded in a persisted ledger": a blob that failed to ingest is recovered through the
    failure ledger.
- `data-sync-backfill`:
  - "Per-day failure isolation": failures are isolated per blob within a day; such a day is still failed and
    its blobs are recorded.
  - "Asynchronous execution with a status endpoint": the status adds a `failedBlobs` count.

## Impact

- **Code**:
  - `service/DataSyncService`: the per-blob catch in the day loop, day-failure recording, and day resolution.
  - `service/BlobPollingService`: blob-failure recording.
  - `service/BlobIngestor`: mapping faults are distinguishable from read faults.
  - `service/BackfillRun`, `DataBackfillService` and the backfill DTOs: `failedBlobs`.
  - New: `IngestFailureLedger` and its ClickHouse implementation, the retry sweep service, the failure
    classifier, a controller and DTOs under `web/controller/datasync/` and `web/dto/datasync/`, and
    `SyncRetryProperties`.
- **Schema**: ClickHouse migration `V16__create_dial_internal_failed_ingest.sql`. The writer credential needs
  `INSERT` and `SELECT` on it, as it already has on the ingest ledger.
- **API**: two new `/v1/data-sync/failures` endpoints, and an additive `failedBlobs` member on the backfill
  status and list entries.
- **Config / docs**: `sync.retry.*` in `application.yml`, and a new table in `docs/configuration.md`. The
  writer grants are listed in the ClickHouse server doc if they are enumerated there.
- **Not touched**: the query catalog (the new table is internal and not queryable), and the PostgreSQL schema.
