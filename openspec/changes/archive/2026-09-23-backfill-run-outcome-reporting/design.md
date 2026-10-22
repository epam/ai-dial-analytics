## Context

`BackfillRun` has two terminal methods that encode different claims. `finish(Instant)` means "the loop
worked through the range" and computes the state from the tallies — correct given its precondition,
because at the normal end of the loop `daysCompleted + daysFailed == daysTotal`. `stop(Instant)` means
"the run ended without working through the range" and sets `FAILED` unconditionally.
`DataBackfillService.execute` has three terminal sites: the normal end (`finish`, correct), the
disk-exhaustion return (`stop`, correct), and the catch-all arm, which calls `finish` on a run whose
precondition does not hold.

The arm was written as a guard for a case believed unreachable, because `runForDate` is documented as
never throwing. It can throw: `ingestDate` calls `prefixResolver.dayPrefix` and
`blobIngestor.newRunSink` — which reads the catalog via `CatalogIngestFlushOrder.sourceOf` — before
its `try`. So the defect is live on a Postgres blip, and the abort itself is a second defect: a
day-preparation failure is a single day's failure and per-day isolation says it must not end the run.
The poller has the same pre-`try` sink construction (`BlobPollingService.java:136`). There a catalog
blip escapes `runCycle` before the `try` whose `finally` records `poll.cycles{outcome}`, the cycle
duration and the row counters, so the failed cycle is invisible in the metrics.

Separately, the status names no failed day and the conflict carries the generic code. Both land on
the same DTO / handler pair and the same requirement text as the abort fix, which is why they ship
together: two changes each carrying a `MODIFIED` *Per-day failure isolation* would have to be rebased
onto each other by hand, and a stale `MODIFIED` delta silently reverts the other's text on archive.

`BackfillRun` funnels every mutation through `synchronized` methods and serves reads through an
immutable `Snapshot`, so new state is a field plus a copy.

## Goals / Non-Goals

**Goals:**

- `runForDate` never throws, as documented; a day-preparation failure is a failed day.
- Any run that does not work through its range ends `FAILED`, whatever threw, and a run once terminal
  stays so.
- No run is left reading `running` with no worker behind it.
- The status names the failed days, including the aborted and disk-full days.
- The backfill conflict has a code a client can branch on.

**Non-Goals:**

- Listing runs, a failure reason, a new state value (see proposal).
- Changing ingestion, range validation, or the disk-exhaustion stop.

## Decisions

### Decision: Close the throw at its source, and still fix the guard

Moving `dayPrefix` and `newRunSink` inside `ingestDate`'s `try` restores the documented contract and
turns a catalog blip into an isolated failed day — the per-day isolation the capability already
requires. The sink may then be null when the summary is built: a null sink reports zero inserted and
an empty per-table map, which is what the day achieved.

The catch-all arm is fixed anyway. After the move, what can still reach it is the tracing wrapper,
`BackfillRun`'s own methods, the metric calls, and `Error`s — rare, but the arm exists precisely for
the rare case, and a guard that fires and reports success is worse than none because the operator
acts on the report.

- *Alternative — fix only the arm*: rejected. The run would then correctly report `failed`, but a
  one-second Postgres blip would still throw away the rest of a 366-day range.

The poller gets the same move. `newRunSink` goes inside `runCycle`'s `try`, so a catalog blip is a
failed cycle: the watermark holds and the next cycle retries, as for any other cycle fault. The
`finally` reads the sink for the per-table row counters, so it must tolerate a sink that was never
built.

### Decision: Fix the call site, not `finish()`

The arm calls `run.stop(...)`. Hardening `finish()` to yield `FAILED` when `daysCompleted +
daysFailed < daysTotal` would fix this arm without touching it, but it makes `finish` and `stop` differ
only in a condition never true at `finish`'s one legitimate call site, inviting a later reader to
collapse them. A defect caused by calling the wrong method is not well answered by making the wrong
method behave like the right one.

### Decision: Catch `Error` too, and rethrow it

The arm catches `RuntimeException | Error`. The task is submitted with `worker.submit`, whose `Future`
nobody reads, so an escaping `Error` is swallowed without a trace and the run stays `RUNNING` forever.
The arm stops the run, logs, and rethrows the `Error` — the run's state is corrected without the
service pretending to have handled a VM error. Checked exceptions cannot escape the lambda.

The arm runs code of its own before the stop: it records the aborted day and increments a metric.
Under an `OutOfMemoryError` either can throw again. So the recording runs in `try { … } finally {
run.stop(now) }`, and nothing the arm does can skip the stop.

- *Alternative — `execute()` in place of `submit()`*: gets the uncaught-exception handler to log the
  `Error`, but still leaves the run `RUNNING`. It does not replace the catch, so it is not worth a
  second change.

### Decision: The aborted day is a failed day; its counts are left as they stood

When the arm fires, `startDay` has run for the day in progress but `completeDay` has not, so today the
day is in neither tally and `stop` clears `currentDate` — the status no longer says where the run
died. The arm records it with `completeDay(date, null, false)`: it was attempted and did not complete,
the same reading the disk-full day already gets, and naming it in `failedDates` tells the operator
where to resume. It also counts as `backfill.days{outcome=failure}`, so the day metrics sum to the
days attempted.

With no summary there is no authoritative total to reconcile to, so the live per-blob counts stay.
They may include rows still sitting in an un-flushed batch — the same lead the spec already tolerates
mid-day. The alternative, rolling back to the day's baseline, would under-report rows that *were*
flushed; neither is exact, and keeping the counts is the smaller lie and needs no code. The spec
states it as the one exception to "SHALL converge at day end".

`completeDay` with a null summary already skips reconciliation (`BackfillRun.java:93`). The day is
recorded only if `completeDay` has not already run for it. A fault in the metrics or log after a
normal `completeDay` must not count the day twice.

On the range's last day the aborted day being counted means `daysCompleted + daysFailed == daysTotal`
under `FAILED`, although not every day failed. That is the case the `stop` javadoc already names ("there
is no remainder to see, and the terminal state alone carries it"). The spec says so rather than
promising the remainder is always visible in the counts.

### Decision: First terminal transition wins

`finish` and `stop` return immediately when the state is no longer `RUNNING`. Without this, the fix
creates a new flip: anything thrown after `finish` in the try (the snapshot and log at `:150-153`)
would reach the arm and `stop` a run that genuinely completed, rewriting `finishedAt`.

`stop` returns whether it made the transition. The arm logs "aborted unexpectedly" at `ERROR` only
when it did. When the run was already terminal, the fault came after the outcome was decided, so the
arm logs a distinct `WARN`, "fault after run reached `<state>`". A completed run's log then does not
claim it aborted.

### Decision: Register before submitting; withdraw the run on rejection

Today `start` registers the run, counts it (`backfill.runs`) and logs it, then submits. On rejection it
releases the guard and rethrows, so the caller never learns the `runId`, and the registry keeps a run
no worker will end.

The fix keeps `runs.put` and the "starting" log before `worker.submit`, so no worker log can precede
the run's start line, and moves the count to after a successful submit. The submit is guarded for
`RuntimeException | Error`, since the lazily-created worker thread can fail with an `OutOfMemoryError`.
On rejection, the branch calls `runs.remove(runId)`, releases the guard, logs the withdrawal and
rethrows. Nothing is left behind and nothing was counted.

- *Alternative — submit first, then register*: rejected. The worker can finish a short run and
  release the guard before the `put`. A second trigger can then register first, which falsifies the
  registry's invariant that "the active run is always the newest, so it is never evicted"
  (`DataBackfillService.java:45`). The worker's day logs and metrics would also precede the run's own
  start log.
- *Alternative — `stop` the leftover run*: also leaves nothing reading `running`, but it keeps a
  `failed` run no caller asked about in a 16-slot registry. That matters once the next change lists
  the registry.

### Decision: A flat, uncapped array of dates, emitted only when non-empty

`failedDates` is `["2026-05-29", …]` in the loop's (ascending) order: each entry is a `from`/`to` for
a one-day re-run. The bound is `daysTotal ≤ backfill.max-days`, so a separate cap would be a second
ceiling guarding the first.

- The component is `List<String>`, matching every other date in `BackfillStatusResponse`, which maps
  via `toString()`.
- It carries `@JsonInclude(NON_EMPTY)` on the component rather than mapping empty to `null`. The global
  `NON_NULL` inclusion in `JsonMapperConfiguration` is not loaded by `@WebMvcTest`, and a
  component-level annotation makes the omission hold under any mapper.
- `@ArraySchema(schema = @Schema(type = "string", format = "date"))` documents it in the style of the
  surrounding `@Schema` descriptions.
- `Snapshot` copies the list (`List.copyOf`), because it is the concurrency contract: a request thread
  must not iterate the worker's live list.
- *Alternatives rejected*: a per-day object list (366 objects for three facts, and no per-day
  discriminant exists); compacted ranges (failures are usually scattered); a metric tagged by date
  (unbounded tag cardinality).

### Decision: `completeDay` takes the date

Reading the `currentDate` that `startDay` set would work on the single worker thread, but it couples
two calls through hidden state. Passing the date makes the recorded day the summary's day by
construction.

### Decision: `backfill_conflict`, from the sibling service

The code matches the enrichment runner's for its analogous — not identical, since that one is
per-pipeline — condition, so one console handler covers a backfill conflict from either service.
`message` stays human prose. Clients must not substring-match it.

The remedies are not interchangeable. A backfill conflict's remedy is "wait for the run holding the
slot". A `TableConflictException`'s remedy depends on which of its conflicts fired — pick another name,
wait for the table to become `ACTIVE`, or detach the pipeline that reads it. A client acting on
`conflict` alone cannot pick the right one.

### Decision: Say the single-in-flight guard is per instance

*Single in-flight backfill* is already being rewritten here, so its "at most one at a time" is
qualified as "on a given service instance", and the concurrent-trigger scenario as "received by the
same instance". This describes the shipped guard, a process-local `AtomicBoolean`
(`DataBackfillService.java:57`); it does not change it. Left unqualified, the requirement promised a
deployment-wide guarantee that a rolling-update overlap, with two pods live, has never kept. Making the
guard deployment-wide (for example with a Postgres lock) is out of scope.

## Risks / Trade-offs

- **A previously "successful" outcome becomes a failure.** Intended — and more likely than first
  thought, because the abort was reachable. It needs a release note.
- **A catalog outage now fails day after day instead of aborting.** During a sustained Postgres outage
  a backfill works through the range failing every day, rather than stopping at the first. This is
  cheap: each day fails at setup, before any blob is listed. The run ends `FAILED` via `finish`'s
  all-days-failed rule, and `failedDates` names the whole range. It is also what per-day isolation
  says.
- **The conflict code change is technically breaking.** No in-tree client branches on it. `adas-mcp`
  derives its own code from the status, and the console is unreleased.
- **`failedDates` is visible.** Clients that render unknown members will show it, but only for runs
  that had failures.
- **Terminal state lands before the guard is released.** `stop` / `finish` run inside the `try`, and
  `running.set(false)` runs in the `finally`. A test that sees a terminal state and immediately
  re-triggers can get `BackfillInProgressException`. The existing
  `allowsRetriggerAfterStoppingOnDiskExhaustion` and `rejectsConcurrentBackfill` already carry this
  race. The new tests retry the trigger, and the two existing ones are hardened the same way. The race
  is test-only: an operator sees `failed` a moment before the slot opens, and a retry succeeds.

## Migration Plan

No schema, config, data or persisted state. Deploy normally.
