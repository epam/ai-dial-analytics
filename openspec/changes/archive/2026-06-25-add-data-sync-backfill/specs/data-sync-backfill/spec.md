## ADDED Requirements

### Requirement: Operator-triggered date-range backfill

The service SHALL provide an explicitly-triggered backfill that ingests DIAL usage logs for an
inclusive range of UTC dates through the same parse → map → batch-insert pipeline used by the
scheduled and poller paths. The backfill SHALL be exposed over HTTP at `POST /v1/data-sync/backfill`,
accepting an inclusive UTC date range with fields `from` and `to` (ISO `yyyy-MM-dd`). Unlike the
scheduled reconciliation and poller paths — which run in-process with no HTTP trigger — the backfill
is deliberately operator-triggered. The backfill MUST be gated by a configurable enable flag; when
disabled the endpoints SHALL NOT initiate runs.

The backfill SHALL iterate the range one day at a time, and for each day resolve the date-derived
blob prefix `<path-prefix>/{yyyy}/{MM}/{dd}/` using the same discovery logic as the scheduled path,
list every blob under it, and ingest the records. Because deduplication is performed by the target
table's `ReplacingMergeTree` engine, a backfill that overlaps dates already ingested by the schedule
or poller SHALL NOT produce surviving duplicate rows, and the service SHALL NOT pre-check for
already-ingested files or rows.

#### Scenario: Backfill ingests every day in the range

- **WHEN** an enabled backfill is triggered with `from` = 2026-05-26 and `to` = 2026-06-24
- **THEN** the service ingests blobs for each date from 2026-05-26 through 2026-06-24 inclusive, each
  via its own date-derived prefix

#### Scenario: Overlap with prior ingestion does not duplicate rows

- **WHEN** a backfill range covers dates whose blobs were already ingested by the scheduled or poller
  path
- **THEN** the service re-inserts the records without an existence pre-check and the table's engine
  collapses the duplicates, so no duplicate row survives

#### Scenario: Backfill disabled

- **WHEN** the backfill enable flag is not set and a trigger is received
- **THEN** the service does not start a backfill run and reports that the feature is disabled

### Requirement: Asynchronous execution with a status endpoint

A backfill trigger SHALL start the run asynchronously: the `POST` SHALL return `202 Accepted` with a
generated `runId` and the resolved range, without waiting for ingestion to complete. The service SHALL
expose `GET /v1/data-sync/backfill/{runId}` returning the run's current status, including its state
(at least `RUNNING`, `COMPLETED`, and `FAILED`), the total number of days in the range, the number of
days completed, the date currently being processed (while running), cumulative counts of files
processed and rows inserted / skipped / dropped, the count of days that failed, and the start and (when
finished) completion timestamps. A `GET` for a `runId` the service does not know SHALL return a
`404`-class error using the `ErrorView` contract.

The cumulative file and row counts SHALL advance incrementally as each blob is ingested, so a day
containing many blobs shows progress while it is still being processed rather than only when the day
completes. When a day completes, its counts SHALL equal the authoritative per-day ingestion totals
(reconciled regardless of intra-day batching/flush timing). The running "rows inserted" total MAY lead
the rows actually flushed to the table by at most one un-flushed batch and SHALL converge at day end.

Run state SHALL be held in memory and is not required to survive a restart; a backfill interrupted by a
restart SHALL NOT resume automatically, and its `runId` MAY become unknown after restart.

#### Scenario: Trigger returns a run id immediately

- **WHEN** a valid backfill is triggered
- **THEN** the service responds `202 Accepted` with a `runId` and the resolved `from`/`to` range, and
  ingestion proceeds in the background

#### Scenario: Status reflects progress and completion

- **WHEN** the caller polls `GET /v1/data-sync/backfill/{runId}` during and after the run
- **THEN** the response reports the state, days completed of total, current date while running, and the
  cumulative files-processed and rows inserted/skipped/dropped counts, transitioning to a terminal
  state with a completion timestamp when the run finishes

#### Scenario: Progress advances within a day as blobs are ingested

- **WHEN** the day currently being processed contains multiple blobs and some, but not all, have been
  ingested
- **THEN** the status already reflects the files and rows from the ingested blobs while the run is still
  `RUNNING` and the day is not yet counted as completed

#### Scenario: Unknown run id

- **WHEN** the caller requests the status of a `runId` the service does not have in memory
- **THEN** the service returns an `ErrorView` with a `404`-class status and a stable machine error code

### Requirement: Single in-flight backfill

The service SHALL allow at most one backfill run to be in progress at a time. A trigger received while
a backfill is already running SHALL be rejected with a `409`-class error using the `ErrorView`
contract, and SHALL NOT start a second concurrent run. The backfill SHALL execute on a dedicated
background worker so that it does not block request-handling threads.

#### Scenario: Concurrent trigger is rejected

- **WHEN** a backfill is in progress and a second `POST /v1/data-sync/backfill` is received
- **THEN** the service rejects it with a `409`-class `ErrorView` and the first run continues unaffected

#### Scenario: New backfill allowed after completion

- **WHEN** a previous backfill has reached a terminal state and a new trigger is received
- **THEN** the service accepts the new trigger and starts a fresh run

### Requirement: Range validation

The service SHALL validate the requested range before starting a run and reject an invalid request
with a `400`-class `ErrorView` (and SHALL NOT start a run). The range is invalid when `from` or `to`
is absent or unparseable, when `from` is after `to`, when `to` is in the future relative to the
current UTC date, or when the inclusive span exceeds a configurable maximum number of days. The
maximum-span cap SHALL be configurable and SHALL guard against an unbounded run.

#### Scenario: Inverted range is rejected

- **WHEN** a backfill is triggered with `from` later than `to`
- **THEN** the service returns a `400`-class `ErrorView` and starts no run

#### Scenario: Future date is rejected

- **WHEN** a backfill is triggered with `to` after the current UTC date
- **THEN** the service returns a `400`-class `ErrorView` and starts no run

#### Scenario: Over-long range is rejected

- **WHEN** a backfill is triggered whose inclusive span exceeds the configured maximum number of days
- **THEN** the service returns a `400`-class `ErrorView` and starts no run

#### Scenario: Valid range within the cap is accepted

- **WHEN** a backfill is triggered with a well-formed range whose span is within the configured cap and
  whose `to` is not in the future
- **THEN** the service accepts the request and starts a run

### Requirement: Per-day failure isolation

A failure to ingest a single day's data (e.g. the store is unreachable for that day's listing) MUST
NOT abort the remaining days of the backfill. The service SHALL count the failed day, continue with
the subsequent days, and report the count of failed days in the run status. A run that completes with
one or more failed days SHALL still reach a terminal state, and the status SHALL make the failed-day
count observable so the operator can re-run the affected range.

#### Scenario: A failing day does not abort the range

- **WHEN** listing or ingesting one day in the range fails while other days succeed
- **THEN** the service counts that day as failed, ingests the remaining days, and reaches a terminal
  state reporting the failed-day count

#### Scenario: Empty day completes cleanly

- **WHEN** a day in the range resolves to a prefix containing no blobs
- **THEN** that day completes successfully having ingested zero rows, and the backfill continues

### Requirement: Backfill observability

The service SHALL expose backfill observability through logs and Micrometer metrics, including
backfill runs started, days processed, days failed, and rows inserted / skipped / dropped, reusing the
existing ingestion counters where applicable. Backfill ingestion metrics SHALL be distinguishable from
the scheduled and poller paths.

#### Scenario: Metrics reflect a backfill run

- **WHEN** a backfill run completes
- **THEN** the backfill metrics (runs started, days processed/failed, rows inserted/skipped/dropped)
  are updated to reflect that run and are attributable to the backfill path
