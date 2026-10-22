## Context

Three scheduled subsystems run off the request thread: `DataSyncService`, `BlobPollingService`, and
`AggregateScheduler`. The first two log their runs at INFO and open an `INTERNAL` parent span
(`DataSyncService:137-143,153,169`). The aggregate path does neither — its only INFO-level output is a
WARN when something fails. So at INFO the subsystem is indistinguishable from one that is not running,
which is the state a stalled scheduler is actually in.

Metrics are not the gap. `AggregateMetrics` already publishes runs by outcome, rows written, duration,
and an observation-time `aggregate.lag.seconds` watermark gauge, all tagged per pipeline, and the
existing "Refresh observability" requirement governs them. This change extends that requirement rather
than opening a second home for the same subject.

The `lag_seconds` half came out of validating a drain: the same cursor reports one value from
`POST …/refresh` (sampled as the write finished) and a value larger by the elapsed time from a
`GET /v1/pipelines/{name}` issued later. That is the contract working — the spec requires observation-time measurement precisely so a
stall keeps climbing — but nothing states the consequences, and the field's current description ("How
far behind the input this rollup is, in seconds") invites reading it as a level.

## Goals / Non-Goals

**Goals:**

- Make a healthy refresh visible at INFO, and a tick's work visible once per tick.
- Give the ClickHouse client spans a parent that names the pipeline.
- Make the compiled statement recoverable after an odd scheduled run.
- Log the control-plane mutations that decide what gets written.
- State what `lag_seconds` is, where its readers are.

**Non-Goals:**

- Any change to `AggregateMetrics`, to the metric names, or to their tags.
- Any change to the refresh contract, the window, the cursor, or the API's shape.
- Durable audit — that is a separate, larger change. Logs here are operational and disposable.
- Per-pipeline log level control, or structured/JSON log fields beyond what the existing appender does.

## Decisions

**Extend the existing requirement, don't add one.** `aggregate-pipeline` already owns "Refresh
observability" and already covers logs in its opening sentence; the obligations added here are the ones
that sentence implied but never spelled out. A second requirement would split the subject and leave two
places to keep consistent.

**The span goes in `AggregateRefreshService.refresh`, wrapping `runRefresh`.** Placing it there rather
than in the scheduler means the on-demand `POST …/refresh` path gets it too, and it nests inside the
existing timing so the span's duration and `aggregate.refresh.duration` describe the same interval. The
eligibility gates (`require`, `requireEnabled`) stay outside it, matching the existing decision that a
rejection which executes no statement is not a run — instrumenting it would mint a span per unknown
name, the same unbounded-cardinality problem the metric rule already guards against.
`ClientSpanTracer.internalSpan` is the existing seam and is inert when tracing is off.

**One INFO line per refresh, at the service, not two.** `PipelineStateWriter:48` already logs a
success at debug. Promoting *that* one instead would put the line in the transaction boundary rather
than at the end of the run, where `has_more` and the elapsed time are known. It stays at debug; the new
line at the refresh service supersedes it.

**The tick summary is suppressed when nothing was due.** `AGGREGATE_TICK_CRON` defaults to
`0 * * * * *`. A line per tick is 1,440 lines a day saying nothing happened, on every replica, which
trains operators to filter the very logger the useful line arrives on. Logging only non-empty ticks
makes the line's presence itself informative.

**The statement goes to DEBUG, not INFO.** It is large, it is the same text on every run of an unchanged
declaration, and preview already returns it on demand. DEBUG is what someone turns on for one pipeline
when a run looks wrong.

**`lag_seconds` is fixed with words, not code.** The value is correct and the contract behind it is
deliberate; only its description is misleading. Changing the measurement to something flatter would
destroy the stall signal the spec exists to preserve. The text lands in two places — the requirement
(next to the observation-time rule it qualifies) and the `@Schema` description on
`PipelineDto.StateDto.lagSeconds` (where an API consumer actually reads it).

**Concrete numbers stay out of the spec.** A pipeline's actual floor and ceiling are a
property of one deployment's cron and ingest lag. The spec states the *shape* (floor ≈ safety lag +
refresh duration + ingest lag; ceiling ≈ floor + one cron period) so a reader can compute their own.

## Risks / Trade-offs

- **Log volume grows with pipeline count** → one line per refresh plus one per non-empty tick. At a
  handful of pipelines on a 15-minute cron that is negligible; at hundreds it would want revisiting,
  and the tick summary is the line that stays constant.
- **A span per refresh adds overhead when tracing is on** → `ClientSpanTracer` allocates no `SdkSpan`
  when tracing is disabled (the default), and one INTERNAL span per refresh is far below the CLIENT span
  volume the same run already produces.
- **The statement at DEBUG could expose data** → the compiled `INSERT … SELECT` inlines the pipeline's
  declared `filter`, which is operator-authored, not user input, and the same text is already returned
  in full by `POST …/preview` to an admin caller. It is DEBUG-only, consistent with how
  `SqlQueryFrontEnd` already logs rejected statements.
- **Promoting logs changes what existing log-assertion tests see** → expected churn; no test currently
  asserts the absence of INFO output on this path.
