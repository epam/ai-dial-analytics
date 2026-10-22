## 1. `runForDate` never throws

- [x] 1.1 In `service/DataSyncService.ingestDate`, move `prefixResolver.dayPrefix(targetDate)` (`:142`)
      and `blobIngestor.newRunSink(...)` (`:151`) inside the `try`. Declare `sink` (and `prefix`)
      before the `try` so the summary can read them. When the sink was never built, report
      `rowsInserted = 0` and an empty `rowsInsertedByTable`. The catch log names the prefix, so make
      it tolerate a `null` prefix: log the target date instead. A storage-full classification cannot
      arise before the sink exists, and nothing else changes.
- [x] 1.2 The javadoc at `:111-113` ("It never throws") becomes true. Leave it as it is, or add that a
      failure preparing the day (prefix, catalog read) is included. Do not weaken it.
- [x] 1.3 `service/BlobPollingService.runCycle`: move `blobIngestor.newRunSink(...)` (`:136`) inside
      the `try`, declaring `sink` before it. The `finally` records `poll.rows.inserted` from
      `sink.insertedByTable()`, so make it null-safe; a sink that was never built contributes no rows.
      The cycle fails like any other cycle fault: the watermark holds, and `poll.cycles{outcome=failure}`
      and the duration are recorded.

## 2. An aborted run ends `FAILED`, naming the day it died on

- [x] 2.1 `service/BackfillRun.java`:
      - `completeDay(LocalDate date, RunSummary summary, boolean daySucceeded)`. On failure, append
        `date` to a `List<LocalDate> failedDates` (an `ArrayList`) as `daysFailed` increments.
      - Update the one existing caller (`DataBackfillService.java:124`). The abort arm (2.3) is the
        second.
- [x] 2.2 `BackfillRun.finish` and `stop`: return immediately unless `state == RUNNING`, so the first
      terminal transition wins. `stop` returns `boolean`, true only when it made the transition. Say
      both in the javadoc in one line each. The disk-exhaustion call ignores the result.
- [x] 2.3 `service/DataBackfillService.execute`, the catch-all arm (`:154-158`):
      - Catch `RuntimeException | Error`. Keep the day in progress in a local, set before `startDay`.
      - Shape: `try { if (day in progress and not completed) { run.completeDay(day, null, false);
        backfill.days{outcome=failure}++ } } finally { stopped = run.stop(Instant.now()) }`. Then log
        and rethrow. An `Error` from `completeDay` or the meter under memory pressure must not skip
        the stop.
      - Log: if `stopped`, `ERROR` "Backfill run {} aborted unexpectedly on {}" with the throwable.
        Otherwise the run was already terminal, so `WARN` "Backfill run {} hit a fault after reaching
        {}" with the snapshot's state and the throwable.
      - Rethrow if the throwable is an `Error`.
      - Rewrite the arm's comment: `runForDate` is not the only thing in the loop. An abandoned run is
        `FAILED` whatever the tallies say, and an `Error` is rethrown because the `submit` `Future` is
        never read.
      - Leave the `finally`, the normal `finish` and the disk-exhaustion `stop` alone.
      - "Not yet completed" means `completeDay` for that date has not run. A throw after it (metrics,
        log) must not count the day twice. Track it with the local cleared right after `completeDay`.
- [x] 2.4 `DataBackfillService.start`:
      - Keep `runs.put` before `worker.submit`, so a run is registered before its worker can end it and
        release the guard. This keeps the invariant at `:45`: the active run is always the newest.
      - Keep the "starting" log before `worker.submit`, so no worker log can precede it. Move the
        `backfill.runs` increment to after a successful submit, so a refused trigger is not counted.
      - Catch `RuntimeException | Error` around the submit (the lazily-created worker thread can fail
        with an `OutOfMemoryError`). In the rejection branch, `runs.remove(run.runId())`, release the
        guard, log the withdrawal and rethrow.
      - One comment line explaining the order.

## 3. Name the failed dates

- [x] 3.1 `BackfillRun.Snapshot` gains `List<LocalDate> failedDates`, populated in `snapshot()` with
      `List.copyOf(failedDates)`.
- [x] 3.2 `web/dto/backfill/BackfillStatusResponse.java` gains a `List<String> failedDates` component
      after `daysFailed`:
      - Annotate it `@JsonInclude(JsonInclude.Include.NON_EMPTY)` and
        `@ArraySchema(schema = @Schema(type = "string", format = "date"), arraySchema = @Schema(description
        = "Dates (UTC, ascending) of the days that failed; absent when none did."))`.
      - Map it from the snapshot with `LocalDate::toString`, preserving order.
      - Extend the record's javadoc sentence on omitted fields.

## 4. Name the backfill conflict code

- [x] 4.1 `web/handler/DefaultExceptionHandler.handleBackfillInProgress` (`:246-250`) emits
      `"backfill_conflict"`. Add a short comment in the style of the `write_output_column_required`
      note:
      - the remedy (wait for the run holding the slot) differs from any `TableConflictException`
        remedy;
      - the string matches the enrichment runner's.
      - Leave `handleTableConflict` (`:214-218`) alone.

## 5. Tests

- [x] 5.1 `DataSyncServiceTest`:
      - The test builds a real `BlobIngestor` with `IngestFlushOrder.NONE` (`:96`). Build one whose
        `IngestFlushOrder` throws a `DataAccessResourceFailureException` instead, standing in for the
        catalog read. `runForDate` then returns a summary with `success() == false`,
        `storageFull() == false`, zero inserted and an empty per-table map, and does not throw.
      - Same for `runOnce`, which also records `sync.runs{outcome=failure}`. `setUp` passes `new
        SimpleMeterRegistry()` inline (`:97-98`), so build this service with a registry the test holds,
        following `flatServiceReporting` (`:230`).
- [x] 5.1a `BlobPollingServiceTest`: with the ingestor's `IngestFlushOrder` throwing, `runOnce` does not
      throw. The watermark is unchanged, `poll.cycles{outcome=failure}` is 1, and the duration timer
      has one sample.
- [x] 5.2 `DataBackfillServiceTest`, abort path:
      - Stub `runForDate` to return `OK` for days 1-4 and throw a `RuntimeException` on day 5 of a
        10-day range. Assert state `FAILED`, non-null `finishedAt`, `daysCompleted == 4`,
        `daysFailed == 1`, `failedDates == [day 5]`, `daysCompleted + daysFailed < daysTotal`, and
        that day 6 is never attempted.
      - Repeat with an `AssertionError` (an `Error`) and assert the same status.
      - In both cases a subsequent `start` is accepted (guard released). `running.set(false)` runs in
        the `finally`, after the terminal state is visible, so retry the trigger:
        `await().ignoreException(BackfillInProgressException.class).until(() -> svc.start(...) != null)`.
        Awaitility is already used in this class.
      - Harden `allowsRetriggerAfterStoppingOnDiskExhaustion` (the `start` after awaiting `FAILED`) and
        `rejectsConcurrentBackfill` (the second `start` after awaiting `COMPLETED`) the same way. Both
        re-trigger right after seeing the terminal state, so both carry the race today.
      - This provokes the arm directly; 1.1 closes the known runtime path to it.
- [x] 5.3 `DataBackfillServiceTest`, failed dates:
      - Days 2 and 4 of a 5-day range fail → `failedDates == [day 2, day 4]` and the run is
        `COMPLETED`.
      - A clean run → an empty list.
      - A run where day 2 fails and day 4 is `DISK_FULL` → `[day 2, day 4]`, day 5 not among them,
        state `FAILED`.
      - Extend `stopsTheRangeWhenClickHouseIsOutOfDiskSpace` to assert `failedDates == [2026-06-02]`.
- [x] 5.4 `DataBackfillServiceTest`, rejected submission:
      - `newService` builds its `SimpleMeterRegistry` inline. Keep a handle to it (a field or
        overload).
      - Call the package-private `shutdown()`, then assert `start` throws `RejectedExecutionException`,
        `backfill.runs` was not incremented, and a following trigger fails the same way rather than
        with `BackfillInProgressException` (guard released).
      - Limit: `DataBackfillService` has no accessor for the registry, and the caller never gets the
        `runId`, so the `runs.remove` is not observable here. The run-listing change adds the read that
        makes it assertable; do not add a seam for it now.
- [x] 5.5 `BackfillRunTest` (new, `service` package), terminal finality:
      - `finish` then `stop` leaves `COMPLETED` and the first `finishedAt`, and `stop` returns false.
      - `stop` then `finish` leaves `FAILED`; the first `stop` returned true.
- [x] 5.5a `DataBackfillServiceTest`, no double count: give the service a `MeterRegistry` whose
      `backfill.days{outcome=success}` counter throws. With `runForDate` returning `OK`, day 1 has
      already been through `completeDay` when the throw reaches the arm. Assert `daysCompleted == 1`,
      `daysFailed == 0`, no `failedDates`, and state `FAILED`. The day was completed, not aborted. A
      `SimpleMeterRegistry` subclass, or a counter pre-registered as a throwing custom `Counter`, will
      do. If neither is practical without a seam, record that here and drop this test.
- [x] 5.6 The unaffected `finish` semantics are already pinned by `isolatesFailingDayAndContinues`
      (`DataBackfillServiceTest:122-135`, `COMPLETED` with one failed day) and
      `allDaysFailingYieldsFailedState` (`:181-191`). Confirm they still pass unchanged; add nothing.
- [x] 5.7 `DataSyncBackfillControllerTest`:
      - Update the positional `snapshot(...)` helper (`:36-43`) for the new `Snapshot` component, and
        let it take a failed-dates list.
      - Present case: `jsonPath("$.failedDates[0]")` is the ISO string, in order.
      - Absent case: `jsonPath("$", not(hasKey("failedDates")))`, because `doesNotExist()` passes on a
        present-`null` key and cannot prove absence. Do not rely on `@Import(JsonMapperConfiguration)`
        for it: the component-level `NON_EMPTY` must hold under the test's default mapper.
- [x] 5.8 `DataSyncBackfillControllerTest`, concurrent-trigger case:
      - `inProgressMapsToConflict` (`:88-99`) moves to `status 409`, `$.error == "backfill_conflict"` and the
        unchanged message.
      - The other `"conflict"` assertions in `src/test` (the otel sink/ingest, enrich-reads-entity,
        compiled-projection, table-management and `TableTtlIntegrationTest:301` tests) are
        `TableConflictException`. Leave them.

## 6. Conventions, spec and build

- [x] 6.1 `openspec/config.yaml` `context:`: in the DTO snake_case bullet (`:107-111`), record the
      `data-sync-backfill` DTOs as a second known camelCase exception, shipped that way and kept for
      compatibility, so the next member added to them does not have to rediscover it.
- [x] 6.2 No configuration property is added or renamed, so `docs/configuration.md` gains nothing.
      Confirm it. `docs/release-0.2-upgrade.md` is a shipped release's guide and is not edited. The
      release-note text lives in `proposal.md` → *Impact*.
- [x] 6.3 `openspec validate 2026-09-23-backfill-run-outcome-reporting --strict` passes.
- [x] 6.4 `./gradlew clean build` passes (compile + checkstyle + tests).
- [x] 6.5 Before archiving, re-diff the three `MODIFIED` requirements against
      `openspec/specs/data-sync-backfill/spec.md`: a `MODIFIED` delta replaces its base wholesale. Any
      base text changed since this delta was written must be carried in.
