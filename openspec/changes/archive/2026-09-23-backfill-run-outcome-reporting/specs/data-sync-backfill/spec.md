## MODIFIED Requirements

### Requirement: Asynchronous execution with a status endpoint

A backfill trigger SHALL start the run asynchronously: the `POST` SHALL return `202 Accepted` with a
generated `runId` and the resolved range, without waiting for ingestion to complete. The service SHALL
expose `GET /v1/data-sync/backfill/{runId}` returning the run's current status, including its state
(at least `RUNNING`, `COMPLETED`, and `FAILED`, serialized in lowercase as `running`, `completed` and
`failed`), the total number of days in the range, the number of days completed, the date currently
being processed (while running), cumulative counts of files processed and rows inserted / skipped /
dropped, the count of days that failed, the dates of the days that failed, and the start and (when
finished) completion timestamps. A `GET` for a `runId` the service does not know SHALL return a
`404`-class error using the `ErrorView` contract.

The failed days SHALL be reported as ISO `yyyy-MM-dd` dates in ascending order — the order in which
the range was worked through — and the field SHALL be omitted entirely when no day failed, so a clean
run's status is unchanged by this requirement. The list SHALL NOT be truncated or capped
independently: it is already bounded by the range validation's maximum span, so a run can name at most
as many dates as its range has days.

The cumulative file and row counts SHALL advance incrementally as each blob is ingested, so a day
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
- **THEN** the response carries no failed-dates member at all, rather than an empty list or a null

#### Scenario: Unknown run id

- **WHEN** the caller requests the status of a `runId` the service does not have in memory
- **THEN** the service returns an `ErrorView` with a `404`-class status and a stable machine error code

### Requirement: Single in-flight backfill

The service SHALL allow at most one backfill run to be in progress at a time on a given service
instance. A trigger received while a backfill is already running on that instance SHALL be rejected with
a `409`-class error using the `ErrorView` contract carrying the stable machine code `backfill_conflict`,
and SHALL NOT start a second concurrent run. The code SHALL be specific to this condition rather than
the service's generic conflict code: a backfill conflict's remedy is to wait for the run that holds the
slot, which is not interchangeable with the remedy for any other `409` the service returns, and a caller
acting on a shared code would apply the wrong one. The backfill SHALL execute on a dedicated background
worker so that it does not block request-handling threads.

The in-flight slot SHALL be released however the run ends — having worked through its range, stopped
on disk exhaustion, or aborted on an unexpected fault of any kind — so no ending wedges the feature. A
trigger whose run cannot be handed to the background worker SHALL release the slot and SHALL withdraw
the run it registered, so no run is left that no worker will ever end. A run SHALL be registered
before its worker can start, so a run that ends at once cannot let a later trigger register ahead of
it.

#### Scenario: Concurrent trigger is rejected

- **WHEN** a backfill is in progress and a second `POST /v1/data-sync/backfill` is received by the same
  instance
- **THEN** the service rejects it with a `409`-class `ErrorView` whose `error` is `backfill_conflict`,
  and the first run continues unaffected

#### Scenario: New backfill allowed after completion

- **WHEN** a previous backfill has reached a terminal state and a new trigger is received
- **THEN** the service accepts the new trigger and starts a fresh run

#### Scenario: A run that ends early releases the in-flight slot

- **WHEN** a run ends before working through its range, whether on disk exhaustion or on an
  unexpected internal fault
- **THEN** the single-in-flight guard is released and a subsequent trigger is accepted rather than
  rejected as already in progress

#### Scenario: A trigger the worker cannot accept leaves no run behind

- **WHEN** a valid trigger is received but the background worker refuses the run (for example because
  the service is shutting down)
- **THEN** the trigger fails, the in-flight guard is released, the run it registered is withdrawn so
  none reports `running` indefinitely, and the runs-started metric is not incremented

### Requirement: Per-day failure isolation

A failure to ingest a single day's data (e.g. the store is unreachable for that day's listing) MUST
NOT abort the remaining days of the backfill. The service SHALL count the failed day, continue with
the subsequent days, and report the count of failed days in the run status. A run that completes with
one or more failed days SHALL still reach a terminal state, and the status SHALL make the failed-day
count observable so the operator can re-run the affected range. A failure while preparing a day's
ingestion — resolving its prefix or reading the catalog to set up its write path — SHALL count as that
day's failure like any other, not abort the run.

The status SHALL further identify **which** days failed, so the operator re-runs those days rather
than the whole range. The identity of a failed day exists only inside the run and cannot be derived
from the counts, and a re-run of a whole range to recover a handful of days is redundant listing,
egress and insertion of data the target table's engine will collapse.

A run that ends **before working through its range**, for any reason, SHALL reach the failed terminal
state — whatever the per-day tallies say, and even if every day it did attempt succeeded. The
completed terminal state SHALL be reserved for a run that worked through its whole range, however
many of its individual days failed. The two SHALL NOT be conflated: a terminal state reporting
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
