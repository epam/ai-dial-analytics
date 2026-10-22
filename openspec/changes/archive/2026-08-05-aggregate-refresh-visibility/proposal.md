## Why

The aggregate subsystem is well metered and effectively invisible. `AggregateMetrics` publishes
`aggregate.refresh.runs` (by outcome), `aggregate.refresh.rows.written`, `aggregate.refresh.duration`
and a watermark `aggregate.lag.seconds` gauge — but at INFO the whole path is silent until something
fails, no tick reports what it did, the refresh raises no span, and the control plane logs nothing at
all. Compare the two sibling scheduled jobs (`DataSyncService`, `BlobPollingService`), both of which log
their runs at INFO and open an `INTERNAL` parent span.

This is the diagnostic surface every other aggregate follow-up is debugged through, so it is worth
having before the rest.

Separately, `lag_seconds` is easy to read wrongly. It is sampled at observation time (by contract, so a
stall keeps climbing), which makes it a **sawtooth**, not a level — the same cursor can read tens of
seconds on `POST …/refresh` and minutes more on a `GET` shortly afterwards. Nothing says so, and a reader who assumes a
level reads a healthy pipeline as a degrading one.

## What Changes

- **INFO on the happy path.** A completed refresh logs pipeline, rows written, duration and `has_more` at
  INFO; today it logs at `debug` (`PipelineStateWriter:48`, `AggregateRefreshService:145,156`). Failures
  stay at WARN with the throwable.
- **One tick summary.** `AggregateScheduler.runDue` already returns a count that nothing logs. It gains a
  single INFO line per tick — due, refreshed, skipped, failed, elapsed — **suppressed entirely when
  nothing was due**, so the default per-minute cron produces no log line at rest.
- **A span per refresh.** The refresh opens an `INTERNAL`-kind parent span following the
  `DataSyncService` pattern, so the ClickHouse `CLIENT` spans raised by the `INSERT … SELECT` nest under
  it instead of hanging parentless with no way to attribute them to a pipeline.
- **The compiled statement at DEBUG.** Preview returns it; a scheduled refresh that behaves unexpectedly
  at 03:00 currently leaves nothing to compare against.
- **Control-plane mutations logged.** `PipelineService` register / patch / enable / disable / delete are
  silent today, and they decide what gets written.
- **`lag_seconds` documented as what it is** — a sawtooth whose floor is roughly safety lag + refresh
  duration + the input's own ingest lag and whose ceiling is one cron period above that; and *not*
  "time since the last run", since it is derived from the cursor and so bundles time-since-refresh with
  how far behind the cursor already was. Read alongside `last_run_at`, `last_error` and `has_more`.

No metric changes, and no new metric series.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: the existing "Refresh observability" requirement already owns this area. It gains
  the logging and tracing obligations, and the `lag_seconds` interpretation, rather than a second home
  being created for them.
- `observability`: "Outbound dependency calls emit CLIENT spans" currently names the scheduled poll, the
  scheduled daily sync and each backfill day as the background cycles that open an `INTERNAL` parent
  span. The aggregate refresh joins that list.

## Impact

- `service/pipeline/AggregateRefreshService` — injects `ClientSpanTracer`, opens the span, logs INFO on
  completion and the statement at DEBUG.
- `service/pipeline/AggregateScheduler` — tick summary.
- `service/pipeline/PipelineService` — INFO on accepted mutations.
- `web/dto/pipeline/PipelineDto.StateDto` — `lag_seconds` `@Schema` description (OpenAPI text only).
- No change to `AggregateMetrics`, to the refresh contract, to any endpoint's shape, or to configuration.
