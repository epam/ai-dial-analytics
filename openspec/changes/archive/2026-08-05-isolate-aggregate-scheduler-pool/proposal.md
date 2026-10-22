## Why

`AggregateScheduler.tick` runs on the shared `spring.task.scheduling` pool (`SCHEDULING_POOL_SIZE`,
default 4) — the same pool as `DataSyncService` and `BlobPollingService`. Refreshes run serially inside
one tick, so a slow recompute does not merely lengthen the tick: it occupies a thread that ingestion also
needs. Rollup cost and ingest liveness should not share a budget, and the direction of harm is the wrong
one — a rollup being late is a stale number, ingestion being late is missing data.

This is more than theoretical. A non-time-bucketed rollup's recompute reads the whole input on every
refresh; refresh durations are the thing most likely to grow.

## What Changes

- A dedicated `TaskScheduler` for the aggregate tick, selected with `@Scheduled(scheduler = "…")`, so a
  slow recompute can no longer starve the blob poller or the daily sync.
- Its size is configurable (`AGGREGATE_SCHEDULER_POOL_SIZE`, default `1`) so a deployment can widen it
  later without a code change. One thread preserves today's behaviour exactly: refreshes stay serial and
  ticks still cannot overlap.
- Boot's own `taskScheduler` bean is **re-declared explicitly** in the same configuration class. Boot 4
  auto-configures it under `@ConditionalOnMissingBean(TaskScheduler.class)`, so introducing any
  `TaskScheduler` bean would silently remove it — taking `SCHEDULING_POOL_SIZE` with it and leaving
  ingestion on whatever fallback Spring picks. Declaring both keeps the existing knob working and makes
  the default scheduler unambiguous for the two jobs that still use it.

Explicitly not in this change: parallelising refreshes *within* a tick (premature at a handful of pipelines — the
right trigger is `aggregate.refresh.duration` plus the tick summary regularly overrunning the cron), a
per-pipeline refresh claim or advisory lock (a single-replica deployment pays nothing for duplicated
work), and any change to `DataSyncService` or `BlobPollingService`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: "Scheduled refreshes execute under a system identity" justifies unwinding the
  elevation with "Scheduled jobs share a thread pool" — which stops being true of the aggregate tick. The
  requirement gains the isolation statement, and its rationale for scoping the elevation is re-grounded
  (the aggregate pool is still shared *between pipelines*, which is what the scoping actually protects).

## Impact

- New `configuration/SchedulingConfiguration` declaring both the shared `taskScheduler` and
  `aggregateTaskScheduler`.
- `configuration/AggregateProperties` — one new property; `application.yml` — one new line.
- `service/pipeline/AggregateScheduler` — `@Scheduled(scheduler = …)` and a javadoc correction.
- `docs/configuration.md` — one row in the aggregate table, and the sentence in the aggregate section
  claiming the tick shares the scheduled-task pool becomes false and must be amended.
- No API change, no migration, no behaviour change beyond isolation.
