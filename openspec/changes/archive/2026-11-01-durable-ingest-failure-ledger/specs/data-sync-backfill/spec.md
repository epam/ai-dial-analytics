## MODIFIED Requirements

### Requirement: Asynchronous execution with a status endpoint

A backfill trigger SHALL start the run asynchronously: the `POST` SHALL return `202 Accepted` with a
generated `runId` and the resolved range, without waiting for ingestion to complete. The service SHALL
expose `GET /v1/data-sync/backfill/{runId}` returning the run's current status, including its state
(at least `RUNNING`, `COMPLETED`, and `FAILED`, serialized in lowercase as `running`, `completed` and
`failed`), the total number of days in the range, the number of days completed, the date currently
being processed (while running), cumulative counts of files processed and rows inserted / skipped /
dropped, the count of days that failed, the dates of the days that failed, the count of blobs that
failed to ingest (`failedBlobs`, always present and zero for a run in which no blob failed), and the
start and (when finished) completion timestamps. Like the run's other counts, `failedBlobs` SHALL
advance as each failing blob is met within a day. It does not say which blobs failed: they are named in the
failure ledger (see `data-sync-failures`), which outlives the run. A `GET` for a `runId` the service does not know SHALL return a
`404`-class error using the `ErrorView` contract.

The failed days SHALL be reported as ISO `yyyy-MM-dd` dates in ascending order — the order in which
the range was worked through — and the field SHALL be omitted entirely when no day failed, so a clean
run's status is unchanged by this requirement. The list SHALL NOT be truncated or capped
independently: it is already bounded by the range validation's maximum span, so a run can name at most
as many dates as its range has days.

The files-processed count SHALL count the blobs the run ingested, not the blobs it skipped because the ingest
ledger records them, so a day whose blobs were all already ingested reports zero files processed, as an empty
day does; the skipped blobs are reported in logs and in the backfill metrics. The cumulative file and row
counts SHALL advance incrementally as each blob is ingested, so a day
containing many blobs shows progress while it is still being processed rather than only when the day
completes. When a day completes, its counts SHALL equal the authoritative per-day ingestion totals
(reconciled regardless of intra-day batching/flush timing). The running "rows inserted" total MAY lead
the rows actually flushed to the tables by at most one un-flushed batch per target table and SHALL
converge at day end. The one exception is a day in progress when the run aborts on an unexpected
fault: no authoritative per-day total exists for it, so its live counts SHALL be kept as they stood at
the abort and are not reconciled — they MAY lead the rows written by at most one un-flushed batch per
target table, which is never written, because the abandoned day's batches are not flushed.

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

#### Scenario: A clean run carries no failed-date field

- **WHEN** the caller reads the status of a run in which no day failed
- **THEN** the response carries no failed-dates member at all, rather than an empty list or a null, and
  `failedBlobs` is `0`

#### Scenario: Failed blobs are counted while the run is in progress

- **WHEN** the day being processed has met two blobs that failed to ingest and the run is still `RUNNING`
- **THEN** the status reports `failedBlobs` as `2`

#### Scenario: Unknown run id

- **WHEN** the caller requests the status of a `runId` the service does not have in memory
- **THEN** the service returns an `ErrorView` with a `404`-class status and a stable machine error code

#### Scenario: A day of already-ingested blobs reports no files processed

- **WHEN** a default backfill processes a day whose blobs the ledger all records
- **THEN** the day completes with zero files processed and zero rows inserted, and the backfill's
  skipped-by-ledger metric counts the blobs

### Requirement: Per-day failure isolation

A failure to ingest a single day's data (e.g. the store is unreachable for that day's listing) MUST
NOT abort the remaining days of the backfill. The service SHALL count the failed day, continue with
the subsequent days, and report the count of failed days in the run status. A run that completes with
one or more failed days SHALL still reach a terminal state, and the status SHALL make the failed-day
count observable so the operator can re-run the affected range. A failure while preparing a day's
ingestion — resolving its prefix or reading the catalog to set up its write path — SHALL count as that
day's failure like any other, not abort the run.

Failures SHALL also be isolated per blob within a day. A blob that cannot be opened or read, whose gzip stream
is invalid, or one of whose records fails to map SHALL NOT stop its day. The service SHALL record the blob's
failure in the failure ledger (see `data-sync-failures`), count it in the run's `failedBlobs`, and ingest the
day's remaining blobs. A day in which any blob failed SHALL still count as failed and be named among the
failed dates, because it is incomplete. Only a failure of the day as a whole SHALL also be recorded as a day
failure in the failure ledger: a listing failure, a preparation failure, a failed batch insert or ledger write,
or disk exhaustion. A day failure is retried by the retry sweep. A blob failure is retried, or parked for an
operator, on its own failure entry. Neither needs the run's in-memory status, so what a run left missing stays
known after a restart. A day the run never attempted, the remainder after an early stop, is not a failure and
SHALL NOT be recorded.

The status SHALL further identify **which** days failed, so the operator re-runs those days rather
than the whole range. The identity of a failed day exists only inside the run and cannot be derived
from the counts, and a re-run of a whole range to recover a handful of days is redundant listing of days
that already succeeded, even though the ingest ledger keeps it from re-inserting their blobs.

A run that ends **before working through its range**, for any reason, SHALL reach the failed terminal
state — whatever the per-day tallies say, and even if every day it did attempt succeeded. A run
that worked through its whole range SHALL reach the completed terminal state however many of its
individual days failed, unless every day failed, in which case it SHALL reach the failed terminal state. The two SHALL NOT be conflated: a terminal state reporting
success over days that were never attempted tells the operator the window is ingested when it is not.
The status SHALL continue to report the range total alongside the completed and failed day counts, so
the unattempted remainder stays derivable as `daysCompleted + daysFailed < daysTotal`. A run that ends
early on the last day of its range leaves no remainder: its counts sum to the range total, and the
failed terminal state alone carries the early stop, even though not every day failed. A client SHALL
NOT read counts summing to the range total as proof that a failed run covered its range. A run's first
terminal state SHALL be final: nothing that happens after it is reached SHALL change it.

When a run aborts on an unexpected fault, the day in progress at the abort was attempted and did not
complete: it SHALL be counted as failed and named among the failed dates. The days after it are the
unattempted remainder and SHALL NOT be named.

Exhaustion of ClickHouse's disk space is the one exception to per-day isolation, because it is not
isolated to a day: every remaining day would fail the same way, and attempting them adds load and
partial writes to a store that is already out of space. When a day fails because ClickHouse refused
the write for lack of disk space, the service SHALL stop the run immediately, leaving the remaining
days unattempted, and SHALL reach the failed terminal state under the rule above even if earlier days
succeeded. The day refused for lack of space SHALL itself be counted and named as failed. The reason
for stopping SHALL be reported in logs and metrics rather than as a new status field.

The backfill capability SHALL remain enabled — the operator can re-trigger the same range once space is
available, and a re-trigger that still hits an exhausted disk SHALL again stop at its first failing day
rather than working through the range.

#### Scenario: A failing day does not abort the range

- **WHEN** listing or ingesting one day in the range fails while other days succeed
- **THEN** the service counts that day as failed, ingests the remaining days, and reaches a terminal
  state reporting the failed-day count

#### Scenario: A bad blob does not stop its day

- **WHEN** one blob of a backfill day cannot be mapped and the day holds other blobs
- **THEN** the other blobs of that day are ingested, the day is counted and named as failed, `failedBlobs`
  is incremented, the blob's failure is recorded as parked with the reason `mapping_error`, and no day
  failure is recorded

#### Scenario: A deterministic bad blob no longer blocks the blobs after it

- **WHEN** a day's blob has a corrupt gzip stream and the operator re-triggers the day's range
- **THEN** each run ingests every other blob of the day, so no blob listed after the corrupt one is left
  unattempted

#### Scenario: A failed day is recorded for retry

- **WHEN** a backfill day fails because listing its prefix fails
- **THEN** the day is named among the failed dates and recorded in the failure ledger as a day failure, which
  the retry sweep attempts once its backoff has elapsed

#### Scenario: Days left unattempted by an early stop are not recorded

- **WHEN** a run stops on disk exhaustion with days of its range unattempted
- **THEN** the day refused for lack of space is recorded as a day failure with the reason `storage_full`,
  and none of the unattempted days are recorded

#### Scenario: A catalog read failure fails the day, not the run

- **WHEN** reading the table catalog to prepare one day's ingestion fails (for example the metadata
  database is briefly unreachable) while other days succeed
- **THEN** that day is counted and named as failed, the remaining days are ingested, and the run reaches
  the completed terminal state having worked through its range

#### Scenario: A run reporting failed days names them

- **WHEN** a run over 2026-05-26 → 2026-06-24 finishes with three days failed
- **THEN** the status reports those three dates in ascending order alongside the count, so the operator
  can re-trigger exactly those days

#### Scenario: Empty day completes cleanly

- **WHEN** a day in the range resolves to a prefix containing no blobs
- **THEN** that day completes successfully having ingested zero rows, and the backfill continues

#### Scenario: An unexpected abort does not report success

- **WHEN** a run over a 30-day range ingests its first four days without failure and then aborts on an
  unexpected internal fault while processing the fifth
- **THEN** the run reaches the failed terminal state with a completion timestamp, reporting four days
  completed, one day failed and the fifth day as its only failed date, so `daysCompleted + daysFailed
  < daysTotal`, rather than reporting the completed terminal state over a range it never covered

#### Scenario: An abort on the last day still reports failure

- **WHEN** a run over a 3-day range ingests its first two days and then aborts on an unexpected internal
  fault while processing the third
- **THEN** the run reaches the failed terminal state with two days completed and the third named as
  failed; its counts sum to the range total, and the failed state alone reports the early stop

#### Scenario: Disk exhaustion stops the remaining days

- **WHEN** a day in the range fails because ClickHouse refused the batch insert for lack of disk space
  and days remain unprocessed
- **THEN** the run stops without attempting the remaining days and reaches the failed terminal state,
  with the completed and failed day counts summing to less than the range total

#### Scenario: A stopped run names only the days that were attempted and failed

- **WHEN** a run stops early on disk exhaustion after one earlier day had already failed
- **THEN** the reported failed dates are the earlier failed day and the day refused for lack of disk
  space, and none of the unattempted remainder — it stays derivable from the counts against the range
  total

#### Scenario: An early stop is distinguishable from a completed range

- **WHEN** one run stops on disk exhaustion after some days succeeded and another run covers its whole
  range with one day failed for an unrelated reason
- **THEN** the first reports the failed terminal state and the second reports the completed terminal
  state, so the two outcomes are not conflated

#### Scenario: The range can be re-triggered after space is restored

- **WHEN** the operator re-triggers a range that a previous run stopped on disk exhaustion
- **THEN** the trigger is accepted (the capability is not disabled by the earlier failure) and the run
  proceeds through the range, stopping again at its first failing day only if the disk is still
  exhausted
