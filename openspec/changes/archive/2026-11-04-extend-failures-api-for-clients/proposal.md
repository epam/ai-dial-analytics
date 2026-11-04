## Why

A UI built on `/v1/data-sync/failures` cannot answer five questions it needs to answer:

- **How many failures are there?** A list returns at most 1000 entries and only says whether it was truncated. There
  is no total, and no count of how many entries wait for an operator against how many retry on their own.
- **How many will a retry requeue?** The count is known only after the requeue, so a "Requeue 12?" confirmation
  cannot be shown.
- **How do I requeue one entry?** A blob's `blob` and `records` subjects share its key, so `subjects` requeues both.
  `kind` plus `subjects` names one kind, but a mixed selection of several kinds cannot be expressed in one request.
- **Which failures did this backfill cause?** Events carry no run. A date range also selects other paths' failures,
  and those of earlier backfills.
- **Will anything happen?** With `sync.retry.enabled=false` a requeue answers `200`, but nothing is attempted. A
  client cannot tell that the sweep is off, or when it next runs.

## What Changes

- `GET /v1/data-sync/failures` adds `total`, `by_state` (`pending` and `parked`, disregarding the `state` filter) and
  `retry_sweep`. With the same filters as a retry, `total` is the count that retry would requeue.
- The retry body adds `targets`: exact `{kind, subject}` pairs.
- A failure a backfill run hits carries that run's id. ClickHouse migration `V17` creates the failure
  ledger with a `backfill_run_id` column. Listed entries report `backfill_run_ids`, and the list and the retry body accept a
  `backfill_run_id` filter.
- The list and retry responses carry `retry_sweep`: `{enabled, next_sweep_at, max_per_sweep}`.

All additions are additive: existing requests and response members are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync-failures`: list totals, exact targets, backfill-run attribution and filter, and sweep
  status.

## Impact

- ClickHouse migration `V17` gains the `backfill_run_id` column. Startup schema validation expects it.
- `DataSyncService.runForDate(LocalDate, Mode, IngestSource, listener)` replaces the overload that took an
  `IngestPath`, so a backfill can pass its run id.
- No new configuration property.
