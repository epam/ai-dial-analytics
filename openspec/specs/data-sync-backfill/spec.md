# data-sync-backfill Specification

## Purpose

Provide an operator-triggered, asynchronous backfill of DIAL usage logs over a bounded UTC date range,
so a historical window (e.g. the past 30 days) can be ingested on demand without cycling the scheduled
job's day offset. The backfill reuses the `data-sync` discovery and parse → map → batch-insert pipeline;
by default it skips the blobs the `data-sync` ingest ledger records, so it writes only what is missing,
and on request it re-ingests them, the overlap collapsed by the target table's engine-level
deduplication. It is exposed over HTTP with a trigger endpoint, a
status endpoint and a list of recently started runs, runs one at a time per instance on a background
worker, isolates per-day failures, and holds its run state in memory only (not retained across a
restart). It is deliberately HTTP-triggered, distinct from the in-process scheduled reconciliation and
poller paths.
## Requirements
### Requirement: Operator-triggered date-range backfill

The service SHALL provide an explicitly-triggered backfill that ingests DIAL usage logs for an
inclusive range of UTC dates through the same parse → map → batch-insert pipeline used by the
scheduled and poller paths. The backfill SHALL be exposed over HTTP at `POST /v1/data-sync/backfill`,
accepting an inclusive UTC date range with fields `from` and `to` (ISO `yyyy-MM-dd`), and an optional
boolean `includeIngested`; an absent or `null` value means `false`, and a value that cannot be read as a
boolean SHALL be rejected with `400 bad_request` and start no run. Unlike the
scheduled reconciliation and poller paths — which run in-process with no HTTP trigger — the backfill
is deliberately operator-triggered. The backfill MUST be gated by a configurable enable flag; when
disabled the endpoints SHALL NOT initiate runs.

The backfill SHALL iterate the range one day at a time, and for each day resolve the date-derived
blob prefix `<path-prefix>/<date-path>/` using the same discovery logic — and therefore the same
configured date-directory layout — as the scheduled path, list every blob under it, and ingest the
records. The backfill SHALL NOT carry a layout setting of its own: a deployment whose log blobs use a
non-default date directory SHALL be backfillable with the same configuration that makes its scheduled
and poller paths work.

By default the backfill SHALL skip, and count, every blob the ingest ledger records, and ingest only the
rest, so a backfill over days already ingested — or a re-trigger of a range a previous run left part-done —
writes only what is missing. With `includeIngested` set to `true` it SHALL ingest every blob it lists whether
or not the ledger records it; this is how an operator repairs what the ledger cannot know is missing (rows
lost to a dropped or restored partition) or applies a changed mapping to history. Re-ingesting a day this way
re-writes its rows, so everything that reacts to new rows (row-grain enrichment scans, aggregate refreshes) reacts
to them again. In both modes the backfill SHALL record each blob it ingests, as soon as that blob's rows are
written. Because deduplication is performed by the target table's `ReplacingMergeTree` engine, a backfill
that re-ingests dates already ingested SHALL NOT produce surviving duplicate rows, and the service SHALL NOT
pre-check for already-ingested rows.

#### Scenario: Backfill ingests every day in the range

- **WHEN** an enabled backfill is triggered with `from` = 2026-05-26 and `to` = 2026-06-24
- **THEN** the service ingests blobs for each date from 2026-05-26 through 2026-06-24 inclusive, each
  via its own date-derived prefix

#### Scenario: Backfill follows the configured date-directory layout

- **WHEN** an enabled backfill is triggered on a deployment configured with a non-default
  date-directory layout (e.g. `yyyy-MM-dd`)
- **THEN** each day of the range is listed under a prefix carrying that layout, matching what the
  scheduled path would list for the same date

#### Scenario: Overlap with prior ingestion does not duplicate rows

- **WHEN** a backfill range covers dates whose blobs were already ingested by the scheduled or poller
  path and the ledger does not record them, or the backfill includes already-ingested blobs
- **THEN** the service re-inserts the records without an existence pre-check and the table's engine
  collapses the duplicates, so no duplicate row survives

#### Scenario: A default backfill skips what is already ingested

- **WHEN** an operator backfills a range without `includeIngested` and the ledger records some of its blobs
- **THEN** those blobs are neither read nor inserted, they are counted as skipped, and only the rest are
  ingested

#### Scenario: A re-triggered backfill finishes what a stopped run left

- **WHEN** a backfill stopped part-way through a range (for example on disk exhaustion) and the operator
  triggers the same range again without `includeIngested`
- **THEN** the new run ingests only the blobs the stopped run did not finish

#### Scenario: Including ingested blobs re-ingests the whole range

- **WHEN** an operator backfills a range with `includeIngested: true` and the ledger records its blobs
- **THEN** every blob of the range is ingested again, the rows collapse in the target table, and the ledger
  entries are refreshed

#### Scenario: A boolean given as a string is accepted

- **WHEN** a backfill is triggered with `includeIngested` set to `"true"`
- **THEN** the run starts and includes already-ingested blobs

#### Scenario: An unreadable includeIngested is rejected

- **WHEN** a backfill is triggered with `includeIngested` set to a value that cannot be read as a boolean,
  such as `"maybe"` or an object
- **THEN** the service returns `400 bad_request` and starts no run

#### Scenario: Backfill disabled

- **WHEN** the backfill enable flag is not set and a trigger is received
- **THEN** the service does not start a backfill run and reports that the feature is disabled

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
- **THEN** the response carries no failed-dates member at all, rather than an empty list or a null

#### Scenario: Unknown run id

- **WHEN** the caller requests the status of a `runId` the service does not have in memory
- **THEN** the service returns an `ErrorView` with a `404`-class status and a stable machine error code

#### Scenario: A day of already-ingested blobs reports no files processed

- **WHEN** a default backfill processes a day whose blobs the ledger all records
- **THEN** the day completes with zero files processed and zero rows inserted, and the backfill's
  skipped-by-ledger metric counts the blobs

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
count observable so the operator can re-run the affected range. A failure while preparing a day's
ingestion — resolving its prefix or reading the catalog to set up its write path — SHALL count as that
day's failure like any other, not abort the run.

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

### Requirement: Backfill observability

The service SHALL expose backfill observability through logs and Micrometer metrics, including
backfill runs started, days processed, days failed, rows inserted / skipped / dropped, and blobs skipped
because the ingest ledger records them, reusing the existing ingestion counters where applicable. Backfill ingestion metrics SHALL be distinguishable from
the scheduled and poller paths.

#### Scenario: Metrics reflect a backfill run

- **WHEN** a backfill run completes
- **THEN** the backfill metrics (runs started, days processed/failed, rows inserted/skipped/dropped, blobs
  skipped by the ledger) are updated to reflect that run and are attributable to the backfill path

### Requirement: Backfill is authorized by DATA_SYNC_OPERATE

Triggering a backfill (`POST /v1/data-sync/backfill`) SHALL require `DATA_SYNC_OPERATE`, and reading
a run's status (`GET /v1/data-sync/backfill/{runId}`) or listing the retained runs
(`GET /v1/data-sync/backfill`) SHALL require `REGISTRY_READ`.

`DATA_SYNC_OPERATE` SHALL cover operations that advance ingestion without reference to a stored
declaration, and SHALL NOT authorize anything that executes one — a pipeline refresh or preview
requires `REGISTRY_ADMIN` (see `aggregate-pipeline`). This is what lets an operator re-run a stalled
ingest without acquiring the authority to change what any pipeline computes.

#### Scenario: An operator can trigger a backfill without authoring rights

- **WHEN** a caller holding `DATA_SYNC_OPERATE` and `REGISTRY_READ`, and no other privilege, triggers
  a backfill
- **THEN** the run starts and its status is readable by that caller

#### Scenario: Backfill requires its own privilege

- **WHEN** a caller holding `REGISTRY_ADMIN` but not `DATA_SYNC_OPERATE` triggers a backfill
- **THEN** the response is `403 forbidden`

### Requirement: Backfill runs are listable

The service SHALL expose `GET /v1/data-sync/backfill`, returning the backfill runs it retains as an
object whose `runs` member is an array of run statuses, so that a caller who did not trigger a run — or
no longer holds its `runId` — can learn whether a backfill is active, which run it is, and how recent
runs ended. Each entry SHALL carry exactly the representation `GET /v1/data-sync/backfill/{runId}`
returns for that run, including its live progress while running and its failed dates when any day
failed, so one entry and one by-id read are interchangeable.

The list SHALL be a runtime cache of recent runs, not a history: it is cleared on restart and redeploy
and scoped to the serving instance. Entries SHALL be ordered newest started first. The service SHALL
retain at most the 5 most recently started runs, evicted oldest-first; one retention governs both the
list and `GET /{runId}`, so an evicted run SHALL NOT be listed and its `GET /{runId}` SHALL return
`404`. A trigger the service refused to start SHALL NOT appear once the refusal has been returned,
and SHALL NOT evict a retained run.
Because the list is bounded by retention, it SHALL NOT be paginated.

An optional `state` query parameter SHALL filter the list to runs in that state, accepting the wire
values the status reports — `running`, `completed`, `failed` — matched case-insensitively. Because at
most one run is in flight, `state=running` SHALL yield at most one entry. A `state` value outside that
set SHALL be rejected with a `400` `ErrorView` whose `error` is `bad_request` and whose message names
the accepted values; an empty `state=` SHALL be rejected the same way rather than read as no filter. A
list with no matching run — including when the service retains no runs at all — SHALL be a `200` with an
empty `runs` array, never a `404`.

The list is a read: it SHALL be available to the same callers as `GET /{runId}`, including a read-only
admin, while triggering a backfill SHALL remain restricted to a full admin.

#### Scenario: The active run is discoverable without its run id

- **WHEN** a backfill is running and a caller that does not hold its `runId` requests
  `GET /v1/data-sync/backfill?state=running`
- **THEN** the service responds `200` with exactly one entry, carrying that run's `runId`, state
  `running`, current date and cumulative progress as `GET /{runId}` would report them

#### Scenario: No active run is an empty list, not an error

- **WHEN** no backfill is running and the caller requests `GET /v1/data-sync/backfill?state=running`
- **THEN** the service responds `200` with an empty `runs` array

#### Scenario: Runs are listed newest first

- **WHEN** three runs were started one after another and the caller requests `GET /v1/data-sync/backfill`
- **THEN** the entries are ordered from the most recently started run to the earliest

#### Scenario: A run evicted from retention is not listed

- **WHEN** five runs have been started and a sixth is started
- **THEN** the list holds the five most recently started runs, the first run is absent from it, and its
  `GET /{runId}` returns `404`

#### Scenario: A refused trigger never appears

- **WHEN** a trigger passed validation but the service could not start the run, and the caller then lists
  the runs
- **THEN** no entry for that trigger is listed, and every run retained before the trigger is still listed

#### Scenario: A failed run lists its failed dates

- **WHEN** a retained run ended with one or more failed days and the caller lists runs with `state=failed`
  or with no filter
- **THEN** that run's entry carries its failed dates exactly as its `GET /{runId}` does

#### Scenario: An unknown state filter is a bad request

- **WHEN** the caller requests `GET /v1/data-sync/backfill?state=paused`
- **THEN** the service responds with a `400` `ErrorView` whose `error` is `bad_request` and whose message
  names `running`, `completed` and `failed`

#### Scenario: An empty state filter is a bad request

- **WHEN** the caller requests `GET /v1/data-sync/backfill?state=`
- **THEN** the service responds with a `400` `ErrorView` whose `error` is `bad_request` and whose message
  names the accepted values

#### Scenario: A read-only admin can list but not trigger

- **WHEN** a caller holding only the read-only admin role lists backfill runs and then triggers a backfill
- **THEN** the list is served with `200`, and the trigger is refused with a `403` `ErrorView` whose
  `error` is `forbidden`

### Requirement: Backfill run state is per service instance

The service SHALL scope backfill run state to the service instance that holds it: the retained runs, the
single-in-flight guard, and every read over them (`GET /{runId}` and the list). A read SHALL report only
the runs of the instance that serves it. When more than one instance is serving — as during a rolling
update, where the outgoing and incoming instances overlap — a list MAY omit a run another instance
holds, `GET /{runId}` MAY return `404` for it, and a second trigger routed to another instance MAY be
accepted while the first still runs. A caller SHALL NOT treat an empty `state=running` list as proof
that no backfill is running anywhere in the deployment.

#### Scenario: A read reports only its own instance's runs

- **WHEN** a run was started on one instance and a list request is served by another
- **THEN** the list served by the second instance does not include that run, and `GET /{runId}` on the
  second instance returns `404`

#### Scenario: A restart empties the list

- **WHEN** the instance holding retained runs restarts and the caller lists runs from the new process
- **THEN** the list is empty, consistent with run state not surviving a restart
