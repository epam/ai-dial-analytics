## 1. SQL logger move and pins

- [x] 1.1 Move the statement DEBUG lines to a shared `com.epam.aidial.analytics.sql` logger. The sites are `ClickHouseInsertSelectExecutor:66`, `StructuredQueryExecutor:104` and `:274`, and `AggregateRefreshService:256`. Leave the non-SQL DEBUG lines (row counts) on their class loggers.
- [x] 1.2 In `src/main/resources/log4j2.xml`, pin `com.epam.aidial.analytics.sql` and the Enrichment Runner's set (`org.springframework.web.client`, `org.springframework.http.converter`, `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`, `org.springframework.jdbc.core`) at INFO. Keep Root at a fixed INFO: `APP_LOG_LEVEL` is the application-package level only (`com.epam`, and `logging.level.com.epam.aidial.analytics`), and driving Root from it would put every library at DEBUG. Drop the unused `<Properties>` default. Add no `monitorInterval`.
- [x] 1.3 Test: with `com.epam.aidial.analytics` and each executor class at TRACE, running a materialize and a structured query logs no statement text.

## 2. Dynamic per-logger levels

- [x] 2.1 Add an `ObservabilityProperties` record (`observability.log-levels.path` and `.interval` as a `Duration` with a 1 s minimum, `observability.execution-trace.enabled`). Add bare lines to `application.yml`.
- [x] 2.2 Implement `observability/loglevel/`: a file reader with a content hash, an entry resolver (`validTill`, `defaultLevel`, validation against the `LogLevel` names), a baseline snapshot and revert through `LoggingSystem`, and a denylist covering the three loggers and their descendants. Put the `@Scheduled` poller behind interfaces, and use Lombok.
- [x] 2.3 Handle the file states: missing at startup (one INFO), removed after apply (revert all, one INFO), unparsable (keep state, one WARN per hash). Log the change line and the missing-`validTill` WARN.
- [x] 2.4 Tests:
  - `configuredLevel` before and after `validTill`
  - a removed entry restores the snapshot
  - a pinned logger returns to its XML level
  - an ancestor at DEBUG leaves the pinned child at INFO
  - a denylisted name and a descendant are refused
  - missing, removed and bad-JSON files
  - an unchanged hash is a no-op
  - an interval under 1 s fails startup

## 3. @LogExecution tracing

- [x] 3.1 Add the `@LogExecution` annotation (RUNTIME, TYPE and METHOD), `LogExecutionInterceptor` (TRACE-gated enter, exit and exception lines using the simple class name, then rethrow), and the advisor `@Bean` with the composable pointcut, `Object`-method exclusion, `@Role(ROLE_INFRASTRUCTURE)` and the enable switch.
- [x] 3.2 Annotate `MaterializeService`, `ScanService`, `GroupFetchService`, `EnrichWriteService`, `AggregateRefreshService`, `PipelineService`, `ClickHouseInsertSelectExecutor` and `StructuredQueryExecutor`. This depends on group 1.
- [x] 3.3 Tests:
  - at TRACE, one enter line and one exit line appear
  - at INFO nothing is logged and `proceed` runs exactly once
  - the exception line carries no message, using a `RestClientResponseException` with a marker body
  - `Object` methods aren't traced
  - the switch removes the advisor
  - an annotated bean is an AOP proxy

## 4. Request MDC, access log and OTLP attributes

- [x] 4.1 Add an access-log filter ordered after `RunnerContractHeaderFilter`. It covers `/v1/pipelines/**` at INFO, everything else at DEBUG, and skips actuator. It sets MDC `pipeline_name` (truncated) and `request_id`, reads `caller` from a request attribute set by a `HandlerInterceptor`, and clears MDC in `finally`.
- [x] 4.2 Add `captureContextDataAttributes="pipeline_name,request_id,caller,query_id"` to the OTel appender, and `pipeline_name` and `request_id` to the console pattern.
- [x] 4.3 Tests:
  - a MockMvc materialize call logs one access line with the pipeline, status, caller and contract
  - a `401` from the chain is logged
  - MDC is cleared after the request

## 5. Outcome lines and query_id

- [x] 5.1 Add a `StatementTag(pipeline, stage)` and mint `adas-{pipeline}-{stage}-{uuid}` in `PipelineReadBudget.run` and `ClickHouseInsertSelectExecutor.insertSelect`. Set it after `ClickHouseBudget.remainingTime` copies the settings, so every retry and re-probe gets a fresh id. Record `db.query.id` on the `ClickHouseSpans` span.
- [x] 5.2 Route every pipeline statement through a settings-taking client call. Assert that no pipeline path uses `ClickHouseClientWrapper.query(String)`.
- [x] 5.3 In `MaterializeService`, time `probe_ms`, `reprobe_ms`, `write_ms` and `total_ms`, and log the outcome line (`written`, `drained`, `withheld` or `clamped_empty`, with the clamp bound and `query_id`). Throttle the non-written outcomes per pipeline on state change.
- [x] 5.4 Add a logger and the outcome line to `ScanService`, logging an empty page only on the first call or after a non-empty one.
- [x] 5.5 Log the soft-deadline WARN when `total_ms` passes half of `pipeline.budget.timeout`.
- [x] 5.6 Make `PipelineRepository.discardMarkOfOtherGeneration` a native `UPDATE … FROM (SELECT … FOR UPDATE) prev … RETURNING prev.materialized_generation`, and log `position discarded: stored_gen -> gen` in `MaterializePositionWriter` only when the stored generation is non-null.
- [x] 5.7 Tests:
  - outcome lines for each outcome
  - throttling of repeated `withheld`
  - distinct `query_id`s across a narrowing sequence
  - `query_id` on the span
  - a discard is logged and the first mark is not (PostgreSQL integration)
  - no row value or literal in any outcome line

## 6. Single deadline per materialize call

- [x] 6.1 Start the call clock before the first `probeWindow`. Make `probeWindow` take a `ResourceBudget`, and pass `ClickHouseBudget.remaining(...)` to `clampHeldRowsBack`, the narrowing re-probes and the writes. Refuse with the existing messages when nothing remains.
- [x] 6.2 Tests:
  - a slow probe leaves the write only the remaining time
  - the clamp re-probe runs on the remaining time
  - the whole call ends within one budget, using a unit test of `narrowing` with a fake clock plus one integration test

## 7. DefaultExceptionHandler

- [x] 7.1 Add a rate limiter keyed by (handler, `error` code, path pattern) with a 60 s window and a suppressed count. Apply it to the ClickHouse query, ClickHouse unavailable, PostgreSQL, preview budget, query budget, pipeline read budget and runner contract handlers. Log without a stack, and log the stack at DEBUG.
- [x] 7.2 Add the caller to the contract refusal line. Log `409 pipeline_conflict` at INFO. Move the catch-all to ERROR with the stack. Add the throwable at DEBUG in `MaterializeService.recording`.
- [x] 7.3 Tests:
  - N contract refusals in a minute give one line and then a suppressed count
  - the catch-all logs at ERROR
  - the `ErrorView` bodies are unchanged

## 8. Runner last-attempt series

- [x] 8.1 Add the attempt map and gauge to `RunnerContactMetrics`, recorded in the four data-plane handlers once the pipeline is resolved and before the outcome. Remove it in `forget`. Leave `recordContact` and the existing gauge unchanged, and add no stale WARN.
- [x] 8.2 Tests:
  - a refused call (such as `403` or `422`) moves the attempt series and not contact
  - a contract refusal records nothing
  - delete and disable remove both series

## 9. Aggregates

- [x] 9.1 In `AggregateScheduler`, log the startup INFO (`enabled`, `cron`, `pool`) and the tick-overrun WARN.
- [x] 9.2 Move `AggregateRefreshService:257` to the budgeted `insertSelect` with `pipeline.budget.*` and a statement tag. On a refusal, fail and record the run with no narrowing.
- [x] 9.3 Log the "held back entirely" (`:210`) and "probed only above bound" (`:237`) lines at INFO, on state change only. Keep "no new input rows" (`:222`) at DEBUG.
- [x] 9.4 Tests:
  - the overrun WARN
  - a budget refusal on the aggregate write fails the run and leaves the position unmoved, and an on-demand refresh answers `422`
  - the held-back line appears once per transition

## 10. Generation bumps

- [x] 10.1 Give `PipelineService.stamp` a cause, and log `generation {old} -> {new} (cause=…, actor=…)`. Add the generation to the register, re-declare and enable lines (`:144`, `:231`, `:254`).
- [x] 10.2 Log one line per pipeline, with old and new, at `PipelineGenerationService:85` (repoint) and `:160` (target change).
- [x] 10.3 Tests: a patch logs one bump line with the right cause, and a cascaded bump logs one line per pipeline.

## 11. docs/configuration.md

- [x] 11.1 Add an observability table with rows for `LOG_CONFIG_PATH`, `LOG_CONFIG_INTERVAL` and `EXECUTION_TRACE_ENABLED`, with a one-line description each.
- [x] 11.2 Correct the `PIPELINE_BUDGET_TIMEOUT` row (:294) to "Wall-clock ceiling per pipeline call (a materialize's probes and writes share it). Keep below the runner's HTTP read timeout." Adjust the adjacent timeout paragraph, which says per statement, to match.

## 12. Spec sync and verification

- [x] 12.1 Confirm the delta specs match the shipped behaviour: observability, enrichment-runtime, class1-enrichment, aggregate-pipeline and pipeline-registry. Run `openspec validate improve-logging-observability --strict`.
- [x] 12.2 Confirm the shared contract matches the runner change `improve-logging-observability`: the config names, the file format, the change log line, MDC `pipeline_name`, and the read-timeout pairing note.
- [x] 12.3 `./gradlew clean build` passes.
