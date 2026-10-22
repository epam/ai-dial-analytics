## Why

An operator who can read only ADAS's logs (console or OTLP), with no HTTP or database access, can't answer
"why did this materialize call time out, and on which side?" or "where is this pipeline stuck?". Successful
materialize and scan calls log nothing. ADAS sets no MDC of its own and has no access log. MDC never reaches
exported logs. ClickHouse statements carry no `query_id` that could be joined to `system.query_log`. Log levels
can't be changed without a restart. And DEBUG on ADAS's own classes, or on any ancestor package, prints SQL with
inlined literals, because the four SQL DEBUG lines live on ordinary class loggers and nothing is pinned.

A materialize call can also run for about 3× `pipeline.budget.timeout` (probe, then re-probe, then write, each
with its own clock). That is about 360 s at the defaults, against the runner's 180 s read timeout, so the runner
hangs up and retries a call ADAS is still running.

This is the ADAS half of one cross-repo effort. The Enrichment Runner's change of the same name
(`improve-logging-observability`) is the other half. The two changes share the config names, the level-file
format and the MDC key spelling.

## What Changes

- **SQL logger move and pins.** The inlined-SQL DEBUG lines in `ClickHouseInsertSelectExecutor`,
  `StructuredQueryExecutor` (two sites) and `AggregateRefreshService`, and the parser and Flight failure lines
  that quote a caller's statement, move to one dedicated logger, `com.epam.aidial.analytics.sql`. `log4j2.xml`
  pins that logger and the Enrichment Runner's set (`org.springframework.web.client`,
  `org.springframework.http.converter`, `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`,
  `org.springframework.jdbc.core`) at INFO. There is still no `monitorInterval`.
- **Dynamic per-logger levels from a mounted file.** New config `observability.log-levels.path` and
  `observability.log-levels.interval`. Levels are applied through Boot's `LoggingSystem` on the exact logger
  name, with a baseline snapshot and revert. `configuredLevel` can be time-boxed with `validTill`. A denylist
  refuses any level below INFO for the sensitive loggers and their descendants. Actuator exposure doesn't change.
- **`@LogExecution` on-demand tracing.** A marker annotation plus our own `LogExecutionInterceptor`. It writes
  TRACE lines for enter, exit and exception, with durations, only while the target class's logger is at TRACE.
  It never logs arguments, return values, stacks or bodies. The switch is `observability.execution-trace.enabled`.
  The annotation goes on eight pipeline-path components.
- **Request context and access log.** A filter writes one INFO access line per `/v1/pipelines/**` request and
  sets the MDC keys `pipeline_name`, `request_id` and `caller`. The OTLP appender exports
  `pipeline_name,request_id,caller,query_id` as attributes.
- **Outcome lines.** One INFO outcome line per materialize and scan call, with stage durations and `query_id`.
  `withheld` and `drained` outcomes are logged only when they change. A soft-deadline WARN fires when a
  materialize call passes half of its budget. A position discard is logged when a mark under an older generation
  is thrown away.
- **Single deadline per materialize call.** The budget clock starts before the first probe, and every later
  statement (the clamp re-probe, narrowing re-probes, writes) runs on what is left. **BREAKING (behaviour):** a
  call that used to finish in 1–3× the budget is now refused with `422 pipeline_read_budget_exceeded` once one
  budget is spent. The aggregate refresh write also runs under `pipeline.budget.*`.
- **ClickHouse `query_id`.** Every pipeline statement gets `adas-{pipeline}-{stage}-{uuid}`. Each narrowing
  retry gets a fresh id. The id is recorded on the `ClickHouseSpans` span too.
- **`DefaultExceptionHandler`.** Repeated refusals are logged without a stack and rate-limited. The contract
  refusal names the caller. The 409 `pipeline_conflict` is logged. The unexpected-500 catch-all logs at ERROR.
- **Runner contact.** A new, separate last-attempt series is recorded for every call to an existing, enabled
  enrich pipeline, even when the call is then refused. A served call records it with no extra registry read. The existing `enrichment.runner.contact.staleness.seconds` gauge doesn't
  change. There is no stale-contact WARN.
- **Aggregates.** A startup INFO gives `enabled`, `cron` and `pool`. A WARN fires when a tick overruns. The
  "held back entirely" and "probed only above bound" lines are logged only when they change.
- **Generation.** Every bump logs the old value, the new value and the cause, and the actor when one is
  available. The register, re-declare and enable lines show the generation.
- **Docs.** `docs/configuration.md` gets rows for the three new properties. The `PIPELINE_BUDGET_TIMEOUT` row
  (:294) is corrected from per statement to per call.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `observability`: runtime log levels from a file; pinned and denylisted sensitive loggers, including the new
  SQL logger; `@LogExecution` tracing; request MDC and the access log; an explicit list of exported MDC
  attributes; pipeline `query_id`; refusal logging in `DefaultExceptionHandler`.
- `enrichment-runtime`: the pipeline read budget becomes one deadline per materialize call and also covers the
  aggregate write; a scan outcome line; a runner last-attempt series separate from last contact.
- `class1-enrichment`: a materialize outcome line with stage durations, the soft-deadline WARN, and the
  position-discard line.
- `aggregate-pipeline`: refresh observability gains the startup line, the tick-overrun WARN, state-change
  throttling, and SQL moved to the dedicated logger.
- `pipeline-registry`: generation bumps are logged with old value, new value and cause.

## Impact

- **Code.** `observability/` (new `loglevel/` and `trace/` packages), `clickhouse/` (the SQL logger,
  `query_id`, spans), `service/enrichment/` (`MaterializeService`, `ScanService`,
  `MaterializePositionWriter`, `RunnerContactMetrics`), `service/pipeline/` (`AggregateRefreshService`,
  `AggregateScheduler`, `PipelineService`, `PipelineRepository`), `web/handler/DefaultExceptionHandler`, and a
  new access-log filter under `web/`.
- **Config.** New `observability.log-levels.path` (`LOG_CONFIG_PATH`), `observability.log-levels.interval`
  (`LOG_CONFIG_INTERVAL`) and `observability.execution-trace.enabled` (`EXECUTION_TRACE_ENABLED`). No credential
  and no Flyway migration.
- **`/v1` surface.** Unchanged. No new endpoint, error code or response shape. The `Adas-Contract-Version` echo
  doesn't change: ADAS still sets it on every response it produces, so the runner is right to read a gateway
  5xx with no header as "not skew".
- **Metrics.** One new per-pipeline series for the runner's last attempt.
- **Deployment.** The deployment needs to mount a ConfigMap as a directory at `/app/log-config` (no `subPath`, with
  `optional: true`, not immutable), the same as for the runner.
- **Cross-repo pairing.**
  - These must match the runner exactly: the config names, the file format, the MDC spelling `pipeline_name`,
    and the runner-side read timeout.
  - With the single deadline, `pipeline.budget.timeout` bounds a whole materialize call. The runner's
    `ADAS_READ_TIMEOUT_MS` must stay above it, and both repos' docs must state that pairing.
  - Every replica runs the same throttles independently. The pod name is on the OTLP resource, so this is
    acceptable.
