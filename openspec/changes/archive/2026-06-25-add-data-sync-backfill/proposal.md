## Why

The data-sync paths (scheduled daily reconciliation and the near-real-time poller) only ever target
recent dates — the previous day and the moving watermark window. There is no way to ingest a bounded
historical window (e.g. the past 30 days) without manually cycling `SYNC_DAY_OFFSET` and restarting
the service once per day. Operators standing up a new ClickHouse instance, recovering from extended
downtime, or onboarding historical DIAL usage logs need a single, explicitly-triggered backfill over
an arbitrary date range — without disturbing the recurring schedule.

## What Changes

- Add an explicitly-triggered, **asynchronous** backfill that ingests every day in a caller-supplied
  date range through the existing parse → map → batch-insert pipeline.
- Add `POST /v1/data-sync/backfill` accepting an inclusive UTC date range (`from`/`to`); it validates
  the range, starts a background run, and returns `202 Accepted` with a `runId`.
- Add `GET /v1/data-sync/backfill/{runId}` returning the run's live status (state, days completed/total,
  rows inserted/skipped/dropped, files processed, current date, timestamps, per-day failures).
- Enforce **single in-flight backfill**: a second `POST` while one is running is rejected (`409`).
- Iterate the range day-by-day, resolving each day's date-derived prefix (`<path-prefix>/{yyyy}/{MM}/{dd}/`)
  with the same discovery logic the scheduled path uses; a failed day is counted and skipped without
  aborting the rest of the range. Duplicates are collapsed by the target table's `ReplacingMergeTree`
  engine, so overlap with the schedule/poller is harmless.
- Keep run state **in-memory** (consistent with the existing no-persisted-cursor design); it is lost on
  restart, matching the one-time operational nature of a backfill.
- Add config for the feature toggle and a safety cap on range span; document new properties in
  `docs/configuration.md`.

## Capabilities

### New Capabilities

- `data-sync-backfill`: An operator-triggered, asynchronous, bounded-date-range backfill of DIAL usage
  logs from blob storage into ClickHouse, exposed via HTTP with a trigger endpoint and a status
  endpoint, reusing the data-sync ingestion pipeline and the table's engine-level deduplication.

### Modified Capabilities

<!-- None. The scheduled and poller requirements in `data-sync` are unchanged: they remain in-process
     with no HTTP trigger. Backfill is a separate, explicitly-triggered path that reuses the same
     discovery and parse → map → batch-insert pipeline without altering those requirements. -->

## Impact

- **APIs**: New `POST /v1/data-sync/backfill` and `GET /v1/data-sync/backfill/{runId}`; new request and
  status response views. Errors use the existing `ErrorView` contract (new machine codes for invalid
  range, backfill-in-progress, and unknown run).
- **Code** (`com.epam.aidial.analytics`): new `web/` controller + DTOs; new `service/` backfill
  orchestrator with a single-threaded background executor and an in-memory run registry; refactor the
  per-day ingest step out of `DataSyncService` so backfill and the scheduled path share it; new
  `@ExceptionHandler`s in `DefaultExceptionHandler`; new `BackfillProperties`.
- **Config**: new `backfill.*` properties (env-driven) with descriptions in `docs/configuration.md`.
- **Dependencies / data stores**: no new dependencies; reuses the writer credential, mapping, batching,
  and `ReplacingMergeTree` dedup. No schema change.
- **Specs**: new `openspec/specs/data-sync-backfill/spec.md`; no change to `data-sync`.
