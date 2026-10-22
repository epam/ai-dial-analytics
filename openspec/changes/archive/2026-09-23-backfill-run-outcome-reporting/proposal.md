## Why

The backfill status is the only thing an operator without pod-log access can see about a run, and
today it is wrong in one case and incomplete in two.

**A run that aborts reports that it succeeded — and the abort is reachable.** The worker loop's
catch-all calls the wrong terminal method:

```java
} catch (RuntimeException ex) {
    // runForDate never throws, but guard the loop so an unexpected fault still reaches a terminal
    // state and the in-flight guard is released in finally.
    run.finish(Instant.now());          // DataBackfillService.java:157
```

`finish()` sets `FAILED` only when every day failed — `state = (daysTotal > 0 && daysFailed ==
daysTotal) ? FAILED : COMPLETED` (`BackfillRun.java:110`). A run that aborts on day 5 of 30 has
`daysFailed == 0` and lands on `COMPLETED`: `state: "completed"`, `daysCompleted: 4`, `daysTotal: 30`,
a terminal state claiming success over a range it never covered. The right method already exists —
`BackfillRun.stop` (`:113-126`), whose javadoc describes exactly this case — and the capability
already requires an early stop be distinguishable from a covered range.

The comment's premise is false. `runForDate` is documented as never throwing
(`DataSyncService.java:111-113`), but `ingestDate` resolves the day prefix and builds the run sink
*before* its `try` (`:142`, `:151`), and `BlobIngestor.newRunSink` (`BlobIngestor.java:77-80`) reads
the table catalog through `CatalogIngestFlushOrder.sourceOf`, a `@Transactional` Postgres read
(`CatalogIngestFlushOrder.java:31-44`). A Postgres blip or pool timeout on any day of a backfill
therefore escapes `runForDate`, aborts the whole run — itself a breach of per-day isolation — and
reports it `completed`. The same throw on the scheduled path leaves `runOnce`'s `summary` unassigned,
so `sync.runs{outcome}` is never recorded for that run (`DataSyncService.java:86-92`). The poller
builds its sink before its `try` too (`BlobPollingService.java:136`), so a catalog blip there skips the
`finally` that records `poll.cycles{outcome}`, the cycle duration and the row counters.

The arm has two further holes. It catches `RuntimeException` only; an `Error` (an OOM is plausible on
~86 KB rows) is captured by the unread `Future` from `worker.submit` (`DataBackfillService.java:95`),
logged nowhere, and leaves the run `RUNNING` forever — the `finally` releases the guard, so a second
run can start while the first still reads `running`. And `start` registers and counts the run
(`:89-92`) before `submit`; a rejected submission releases the guard but leaves that run `running`
forever too.

**The failed-day count is not actionable.** `BackfillRun` counts failed days (`daysFailed`,
`BackfillRun.java:34`) and nothing more. The capability justifies the count as letting the operator
"re-run the affected range", but a count does not identify a range. An operator reading `daysCompleted:
27, daysFailed: 3` over a 30-day window can learn *which* three only from the log line `Backfill run {}
failed to ingest {}` (`DataBackfillService.java:131-132`); without log access, or after the pod
rotates, the only remedy is re-triggering all 30 days — redundant listing, egress, parsing and inserts
into a `ReplacingMergeTree` that collapses them. The identity of a failed day exists only inside the
worker loop and is discarded there.

**A concurrent trigger is refused with the generic code.** `BackfillInProgressException` renders as
`409 conflict` (`DefaultExceptionHandler.java:246-250`), the same code as `TableConflictException`
(`:214-218`), which covers name clashes and state/dependency conflicts across `/v1/tables`,
`/v1/otel/sinks` and OTel ingest. These are the only two generic `409`s left; every other one carries
a specific code (`table_is_system`, `evaluator_immutable`, `pipeline_conflict`,
`runner_contract_*`). That is observed practice, not a rule written in `openspec/config.yaml`, but it
is consistent, and the sibling `analytics-enrichment-runner` already answers its analogous per-pipeline
backfill conflict with `backfill_conflict` (`web/handler/DefaultExceptionHandler.java:95`). No client
branches on the ADAS code yet; once the admin console ships a handler for `conflict`, changing it is a
coordinated release.

## What Changes

- **`runForDate` keeps its never-throws contract.** `DataSyncService.ingestDate` moves prefix
  resolution and sink construction inside its `try`; a failure there becomes `success() == false`
  like any other day failure. A catalog blip fails one day of a backfill instead of aborting it, and
  the scheduled run records its outcome metric.
- **The poller records a failed cycle.** `BlobPollingService.runCycle` builds its sink inside its
  `try`, and its `finally` tolerates a sink that was never built. A catalog blip then becomes a failed
  cycle: the watermark holds and the cycle metrics are recorded.
- **An aborted run is `FAILED`.**
  - The catch-all arm calls `run.stop(...)` in a `finally`, so the arm's own recording cannot skip it.
  - It catches `Error` as well as `RuntimeException`; an `Error` is logged and rethrown after the stop,
    so VM errors are not swallowed.
  - `stop` reports whether it made the transition. A fault after the run was already terminal is
    logged as such, not as an abort.
- **The aborted day counts as failed.** It was attempted and did not complete, so it is counted in
  `daysFailed` and named in `failedDates`; the days after it are the unattempted remainder. Its live
  per-blob counts stay as they were, since no authoritative day total exists.
- **Terminal state is final.** `finish` and `stop` do nothing once the run is terminal, so nothing
  thrown after a normal `finish` (the snapshot, the log line) can turn `COMPLETED` into `FAILED`.
- **A rejected submission leaves no run behind.** `start` still registers the run before
  `worker.submit`, so the registry order matches start order. On rejection it removes the run, and it
  counts the run only once the submit succeeds.
- **`failedDates` on the status.** `BackfillRun.completeDay` takes the day's date and records it on
  failure; `Snapshot` carries a copied `List<LocalDate> failedDates`; `BackfillStatusResponse` gains
  `failedDates` — ISO `yyyy-MM-dd` strings, ascending, omitted when empty. The day refused for lack of
  disk space is included; the unattempted remainder is not.

  ```json
  {
    "runId": "f1d2c3b4-...",
    "from": "2026-05-26",
    "to": "2026-06-24",
    "daysTotal": 30,
    "state": "completed",
    "daysCompleted": 27,
    "daysFailed": 3,
    "failedDates": ["2026-05-29", "2026-06-02", "2026-06-17"]
  }
  ```

- **`backfill_conflict`.** `handleBackfillInProgress` emits `backfill_conflict`; status stays `409`,
  message stays `"A backfill run is already in progress"`.

## Non-goals

- **Listing active or recent runs.** The status is reachable only by the `runId` returned once from
  the trigger. Exposing the registry to a UI is the next change and the place the status surface
  grows; this one fixes what the existing surface reports.
- **A failure reason on the status** (`failureReason`). It reverses the shipped clause that the reason
  for stopping "SHALL be reported in logs and metrics rather than as a new status field", and belongs
  with the run-listing change if anywhere.
- **A new state value** (`ABORTED`, `COMPLETED_WITH_ERRORS`). The vocabulary is `RUNNING` /
  `COMPLETED` / `FAILED`; `failed` plus `daysCompleted + daysFailed < daysTotal` already says
  "stopped early".
- **A cap or truncation flag on `failedDates`.** A run names at most `daysTotal ≤ backfill.max-days`
  (default `366`) dates: under 5 KB of JSON at the cap, under 200 KB across all 16 retained runs.
- **A per-day breakdown** (`days: [{date, state, filesProcessed, …}]`). `runForDate` gives no per-day
  failure discriminant, and the counters would duplicate the cumulative ones.
- **Re-coding `TableConflictException`.** It has real clients across three surfaces and needs its own
  compatibility argument.
- **Re-coding the backfill DTOs to snake_case.** They are camelCase with no `@JsonProperty`, against
  the stated convention; the new member follows the DTO it joins. The deviation is recorded in
  `openspec/config.yaml` instead of left implicit.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync-backfill`:
  - *Asynchronous execution with a status endpoint* names the failed dates in the status payload,
    states the lowercase wire values of the state, and carves the aborted day out of the "converge at
    day end" rule.
  - *Single in-flight backfill* names the `backfill_conflict` code, and requires that the slot is
    released however a run ends and that a refused submission leaves no run behind. It also qualifies
    "at most one run at a time" as per service instance, which is what the in-process guard has always
    enforced: the guard is an `AtomicBoolean` and nothing coordinates it across pods.
  - *Per-day failure isolation* generalises the early-stop rule from disk exhaustion to any early end,
    names the failed days (including the aborted day and the disk-full day), makes a day-preparation
    failure an isolated day failure, and makes the first terminal state final.

`data-sync` is not modified. *Ingest observability* already requires the run and poll-cycle outcome
metrics for every run. Both the scheduled path and the poller were falling short of that text on a
day-preparation failure. The fix brings the code up to the spec.

## Impact

- **APIs**:
  - `GET /v1/data-sync/backfill/{runId}` gains one optional array member. A run with no failed days
    is byte-identical to today's.
  - The `409` from `POST /v1/data-sync/backfill` changes `error` from `conflict` to
    `backfill_conflict`. No status-code, endpoint or request-shape change.
  - A run that aborts now reports `failed` where it reported `completed`, and a run hit by a catalog
    blip now works through its range instead of aborting.
- **Release note** (there is no changelog or next-release doc; `docs/release-0.2-upgrade.md` is a
  shipped release's guide and is left as it was). Carry into the release that ships this:
  - A backfill that aborted used to report `completed`. It now reports `failed` with
    `daysCompleted + daysFailed < daysTotal`. A client treating `completed` as "nothing to do" will
    see runs it used to ignore — those runs never covered their range.
  - A transient metadata-database failure no longer aborts a backfill; it fails one day.
  - `failedDates` names the days to re-run.
  - The backfill conflict's code is `backfill_conflict`.
- **Code**:
  - `service/DataSyncService.java`: `ingestDate` `try` boundary and summary construction.
  - `service/BlobPollingService.java`: `runCycle` `try` boundary and a null-safe `finally`.
  - `service/DataBackfillService.java`: the catch arm, and registration order in `start`.
  - `service/BackfillRun.java`: `completeDay` signature, `failedDates`, terminal guards, `Snapshot`.
  - `web/dto/backfill/BackfillStatusResponse.java`: one component.
  - `web/handler/DefaultExceptionHandler.java`: one line.
- **Tests**: `DataBackfillServiceTest`, `DataSyncServiceTest`, `BlobPollingServiceTest`,
  `DataSyncBackfillControllerTest`, and a new `BackfillRunTest`.
- **Config / migration / credential**: none. No property is added, so `docs/configuration.md` gains
  nothing. `openspec/config.yaml` records the backfill DTOs' camelCase as a known exception.
- **Follow-up in `adas-mcp`** (separate repo, not tasked here):
  - `src/adas_mcp/tools/backfill.py:27-33`: add `failedDates` to the documented status shape.
  - `backfill.py:31-32`: replace the claim that "`daysFailed > 0` … per-day failures do not abort
    the range". The days to re-run are now `failedDates`, and a run can stop early (`failed` with
    `daysCompleted + daysFailed < daysTotal`, or on the last day with the counts at the total).
  - `backfill.py:72`: the MCP derives its own `code` from the HTTP status, so it still says
    `conflict`, with `backfill_conflict` in `details.error`.
  - Regenerate `src/adas_mcp/swagger/adas-openapi.json` with `--sort-keys` to avoid hash-order churn.
