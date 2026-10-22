## 1. Refresh span and logging

- [x] 1.1 Inject `ClientSpanTracer` into `AggregateRefreshService` and wrap the `runRefresh(pipeline)`
      call inside `refresh(name)` in `internalSpan("aggregate refresh", attributes, …)`, with attributes
      naming the pipeline, its input table, and its target. Keep it inside the existing timing and
      outside the `require`/`requireEnabled` gates.
- [x] 1.2 In `runRefresh`, log INFO on completion — pipeline, rows written, duration, `has_more`. Leave
      the no-op branch (`window == null`) at debug; it writes nothing.
- [x] 1.3 Log `compiled.sql()` at DEBUG alongside the existing `:156` "Refreshing pipeline…" line.
- [x] 1.4 Leave `PipelineStateWriter:48` at debug — the new line supersedes it; do not log twice.

## 2. Tick summary

- [x] 2.1 In `AggregateScheduler.runDue`, count refreshed / skipped-as-ineligible / failed and measure
      elapsed. `refreshOne` currently swallows both outcomes; return or record enough to distinguish them.
- [x] 2.2 Emit one INFO summary line when `due` is non-empty; emit nothing when it is empty. Keep the
      existing per-pipeline debug and warn lines as they are.

## 3. Control-plane logging

- [x] 3.1 `PipelineService.create` — INFO on the saved registration (name, kind, input, target).
- [x] 3.2 `PipelineService.patch` — INFO on each accepted branch: re-declared (cursor reset), enabled,
      disabled. Nothing on a no-op patch, which does not bump `generation` either.
- [x] 3.3 `PipelineService.delete` — INFO naming the pipeline and that its rollup rows are kept.

## 4. `lag_seconds` documentation

- [x] 4.1 Rewrite the `@Schema` description on `PipelineDto.StateDto.lagSeconds`: sampled at observation
      time; a sawtooth between a floor (safety lag + refresh duration + input ingest lag) and one cron
      period above it; not "time since the last run"; read alongside `last_run_at`, `last_error`,
      `has_more`; any alert threshold must exceed one cron period.

## 5. Specs

- [x] 5.1 Apply the `aggregate-pipeline` delta: "Refresh observability" gains the INFO/tick-summary/span/
      DEBUG-statement obligations and the `lag_seconds` sawtooth paragraph, plus the six new scenarios.
- [x] 5.2 Apply the `observability` delta: the background-cycle parent-span sentence now covers the
      aggregate refresh, with its own scenario.

## 6. Tests and verification

- [x] 6.1 Test that an idle tick logs no summary and a non-empty tick logs exactly one.
- [x] 6.2 Test that a completed refresh logs at INFO with rows written and `has_more`, and that a failed
      one still logs at WARN with the throwable.
- [x] 6.3 Test that the refresh opens an INTERNAL span naming the pipeline (follow the existing
      `DataSyncService` span test, if one exists; otherwise assert against a recording span exporter).
- [x] 6.4 `./gradlew clean build`
      is green.
- [x] 6.5 `openspec validate aggregate-refresh-visibility` passes.
- [x] 6.6 Smoke against `./gradlew bootRun`: one INFO line per on-demand refresh; no tick line during an
      idle minute; `GET /v1/pipelines/{name}` OpenAPI shows the new `lag_seconds` description.
