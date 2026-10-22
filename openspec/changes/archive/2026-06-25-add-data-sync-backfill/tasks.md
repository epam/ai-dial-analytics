## 1. Refactor shared per-day ingest

- [x] 1.1 Extract the per-date ingest step from `DataSyncService.runOnce()` into a reusable `runForDate(LocalDate)` returning the existing per-run summary (files processed, rows inserted/skipped/dropped). Keep all per-run state local to the invocation (the `RowBatchSink` is already a local var; do not promote batch buffers to instance fields) so the method is re-entrant and a backfill thread can call it concurrently with the scheduler thread without sharing batch state
- [x] 1.2 Reimplement `runOnce()` as `runForDate(today − dayOffset)`; confirm behavior is unchanged and existing data-sync tests still pass

## 2. Configuration

- [x] 2.1 Add `BackfillProperties` (`backfill.enabled` default `true`, `backfill.max-days` default `366`) bound from env-driven `application.yml` keys (`BACKFILL_ENABLED`, `BACKFILL_MAX_DAYS`)
- [x] 2.2 Add the bare `backfill:` block to `application.yml` (no inline comments)
- [x] 2.3 Document the `backfill.*` group in `docs/configuration.md` (one table); add an inline mention + link in `README.md` only if needed (no description table there)

## 3. Backfill orchestration service

- [x] 3.1 Define `BackfillRun` (mutable status: runId, from, to, state, daysTotal, daysCompleted, daysFailed, currentDate, filesProcessed, rowsInserted/Skipped/Dropped, startedAt, finishedAt)
- [x] 3.2 Implement `DataBackfillService` with a single-threaded executor, a single in-flight guard, and a bounded in-memory run registry (LRU, ~16 retained)
- [x] 3.3 Implement range validation (missing/unparseable, `from > to`, `to` in future UTC, span > `max-days`) returning a typed error usable by the controller
- [x] 3.4 Implement the background run: iterate days `from..to` inclusive, call `runForDate(d)` per day, isolate per-day failures (count `daysFailed`, log, continue), update the run-status object as each day completes, set terminal state on finish
- [x] 3.5 Honor `backfill.enabled`: when disabled, do not start runs and signal feature-unavailable to the controller

## 4. Web layer

- [x] 4.1 Add request DTO (`from`, `to`) and status response DTO mapped from `BackfillRun`
- [x] 4.2 Add controller: `POST /v1/data-sync/backfill` → validate, start run, return `202` with `{ runId, from, to, days }`
- [x] 4.3 Add `GET /v1/data-sync/backfill/{runId}` → return status view; unknown id → typed not-found
- [x] 4.4 Add `@ExceptionHandler`s in `DefaultExceptionHandler` for the new failure modes (invalid range `400`, in-progress `409`, unknown run `404`, disabled `503`) emitting `ErrorView` with stable machine codes

## 5. Observability

- [x] 5.1 Add Micrometer metrics for backfill (runs started, days processed, days failed, rows inserted/skipped/dropped), attributable to the backfill path and distinct from scheduled/poller
- [x] 5.2 Add structured logs at run start, per-day completion, and terminal state (including failed-day count)

## 6. Tests

- [x] 6.1 Unit tests for range validation (inverted, future, over-cap, valid boundary)
- [x] 6.2 Unit tests for orchestration: single in-flight (`409` on concurrent trigger), per-day failure isolation, empty-day handling, terminal-state transitions, unknown runId
- [x] 6.3 Controller/web-layer tests: `202` shape on trigger, status payload, `ErrorView` for invalid range / in-progress / unknown run
- [x] 6.4 Verify a small real range end-to-end (`DataBackfillServiceFilesystemTest`: 2-day range over the jclouds filesystem provider + in-memory writer — rows land for every day, status reports completion). NOTE: the ClickHouse-container leg of this verification is blocked by the same Testcontainers/Docker constraint that blocks the existing `integration/*` tests in this environment.

## 7. Spec & build

- [x] 7.1 Run `openspec validate add-data-sync-backfill` and resolve any issues
- [x] 7.2 `./gradlew checkstyleMain checkstyleTest` passes; all non-container unit/web tests pass (incl. the 4 backfill/data-sync classes). NOTE: full `./gradlew clean build` cannot go green in this environment — Testcontainers cannot launch containers (`ContainerLaunchException: testcontainers/ryuk`), failing all 7 pre-existing `integration/*` classes + the full-context smoke test. This is the documented "Docker CLI working ≠ build works" constraint and is unrelated to this change.
