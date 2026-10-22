## Context

ADAS runs several replicas behind a load balancer, and its aggregate tick runs on every replica with no lock.
Logging is log4j2 (`src/main/resources/log4j2.xml`, not `log4j2-spring.xml`), exported through the OTel
appender. That config pins nothing: it declares only Root and `com.epam`. The four SQL DEBUG lines are on the
owning classes' own loggers: `ClickHouseInsertSelectExecutor:66`, `StructuredQueryExecutor:104` and `:274`,
and `AggregateRefreshService:256`. So DEBUG or TRACE on any of those classes, or on `com.epam`, prints SQL
with inlined literals. AspectJ is on the classpath only transitively (JPA brings in `spring-aspects`). All
runtime ClickHouse calls go through client-v2 `QuerySettings` and `CommandSettings`, and `CommandSettings`
extends `QuerySettings`, so `setQueryId` is available on every path.

The materialize budget today: the first probe runs under a full `pipeline.budget.timeout`
(`MaterializeService:265` → `probeWindow` → `PipelineReadBudget.run`, `:719`). The write clock starts
afterwards (`writeNarrowing`, `:341`). A narrowing re-probe (`:348`) runs under the full budget again. The
remaining-time check covers only the write attempts. The worst case is about 3× the timeout (≈360 s at
120 s), against the runner's 180 s `ADAS_READ_TIMEOUT_MS`.

This change is paired with the Enrichment Runner's `improve-logging-observability`.

## Goals / Non-Goals

**Goals:**
- An operator can answer "is pipeline X materializing, and why not?" and "why did this call take so long?"
  from logs alone.
- Per-logger levels change at runtime across all replicas, with no restart and no HTTP surface.
- On-demand method tracing on a live pod.
- No log line at any level carries SQL literals or HTTP bodies, except on the pinned SQL logger under a
  static level.
- A materialize call is bounded by one budget.

**Non-Goals:**
- Flight SQL request logging and MDC: Flight isn't a servlet path.
- Actuator `loggers` exposure.
- A `@LogExecution(arguments = true)` opt-in, configurable trace templates, and an ArchUnit coverage rule.
- A runner-contact stale WARN.
- Narrowing the aggregate write.
- Helm chart changes: the deployment owns the values, and this change only states what they need.

## Decisions

1. **Move the SQL lines to one logger, `com.epam.aidial.analytics.sql`, before anything else.** This is the
   precondition for both runtime levels and TRACE tracing. Without it, a TRACE on
   `ClickHouseInsertSelectExecutor` also enables its DEBUG SQL line, because TRACE is below DEBUG.
   *Alternative:* pinning each executor class at INFO. Rejected: that also blocks the TRACE that tracing
   needs on exactly those classes.

2. **Protect sensitive loggers with a pin plus a denylist, not a denylist alone.** The set is the Enrichment
   Runner's (`org.springframework.web.client`, `org.springframework.http.converter`, `org.hibernate.SQL`,
   `org.hibernate.orm.jdbc.bind`, `org.springframework.jdbc.core`) plus `com.epam.aidial.analytics.sql`. The
   parser diagnosis, the identifier probe's parse failure and the Flight statement failure quote the caller's
   statement, so their messages go to the SQL logger too. The XML gives each
   sensitive logger its own `LoggerConfig` at INFO, so a change to an ancestor can't reach it. The denylist
   refuses anything below INFO for the name and its descendants, and it doesn't block ancestors. The static
   path (`logging.level.*` or `LOGGING_LEVEL_*`) bypasses the denylist. That's accepted, because it needs a
   restart and control of the environment.

3. **Apply levels through Boot's `LoggingSystem`, not `Configurator.setAllLevels`.**
   - `setAllLevels` matches on a string prefix: it clobbers pins (`org.springframework` → `.web.client`) and
     catches siblings (`…sql` → `…sqlx`).
   - Take the baseline from `getLoggerConfiguration(name).getConfiguredLevel()`. On revert, restore it, or
     call `setLogLevel(name, null)` when it was null.
   - Never use `Configurator.setLevel(name, null)` on a pinned logger: that drops the pin.

4. **Poll the file on a schedule and hash its bytes.** Use `@Scheduled` with the `Duration` interval (bound
   to `@ConfigurationProperties`, with `@DurationMin(1s)`). Don't use a `WatchService` or mtime: the
   ConfigMap `..data` symlink swap is atomic, and a bare-number interval would be read as milliseconds.
   There's no distributed lock, because each replica applies to itself.

5. **Write our own `LogExecutionInterceptor` rather than use `CustomizableTraceInterceptor`.** Its
   `$[exception]` and the default stack logging print `RestClientResponseException` bodies, and its templates
   can't vary per method.
   - Pointcut: `ComposablePointcut(new AnnotationMatchingPointcut(LogExecution.class, true))`
     `.union(AnnotationMatchingPointcut.forMethodAnnotation(LogExecution.class))`, intersected with a matcher
     that excludes `Object` methods. The two-argument constructor is AND, so don't use it.
   - The advisor `@Bean` is `@Role(ROLE_INFRASTRUCTURE)`, so it still applies if Boot falls back to
     `InfrastructureAdvisorAutoProxyCreator`.
   - Guard it with `@ConditionalOnProperty(observability.execution-trace.enabled, matchIfMissing = true)`.
   - ADAS has no error classifier, so the exception line uses `getClass().getSimpleName()`.
   - All eight target classes are non-final and none is a record.
   - Self-invocation limits: `MaterializeService.probeWindow`, `writeWindow` and `clampHeldRowsBack`, the
     `ScanService.scan(String…)` → `scan(Resolved…)` chain, `GroupFetchService` internals, and
     `StructuredQueryExecutor.scan` (private, `:271`). The probe is visible indirectly as
     `StructuredQueryExecutor.executeScan`. The stage durations on the outcome line are there for exactly
     these gaps.

6. **The access log is a filter, and the caller comes from a request attribute.** The filter is ordered just
   after `RunnerContractHeaderFilter`, ahead of the security chain, so a `401` or `403` from the chain is
   still logged. The chain clears `SecurityContextHolder` on its way out, so a `HandlerInterceptor` (or the
   existing contract interceptor) writes the principal name into a request attribute and MDC `caller`. The
   filter reads that attribute in its `finally`.
   - `pipeline_name` is taken from the path segment and truncated to a fixed length. It is caller-chosen, and
     it goes only into MDC, never into a metric tag.
   - `request_id` is a fresh UUID. Using an inbound header was rejected because it lets a caller choose the
     value.

7. **The OTLP appender uses `captureContextDataAttributes="pipeline_name,request_id,caller,query_id"`, not
   `*`.** `*` would also export `trace_id` and `span_id` a second time, and any future key.

8. **The single deadline wraps the whole materialize call in one clock.**
   - Take an `Instant started` before the first `probeWindow`.
   - Pass `ClickHouseBudget.remaining(readBudget, elapsed)` to every later statement: the clamp re-probe
     (`clampHeldRowsBack`), narrowing re-probes and writes. `probeWindow` therefore takes a `ResourceBudget`
     parameter instead of reading `readBudget` itself.
   - When the remaining budget is null, refuse with the existing `pipeline_read_budget_exceeded` messages.
     Their wording already covers both cases, "no narrowed attempt" and "exhausted across retries".
   - Scan and group fetch are unchanged, because each is one statement per page or phase.
   - *Alternative:* a per-stage split (such as 1/3 each). Rejected, because a cheap probe then strands time a
     heavy write needs.

9. **The soft-deadline threshold is half of `pipeline.budget.timeout`, not a new property.** Under a single
   deadline the budget is the call's upper bound. Half matches the runner's own half-timeout WARN.
   *Alternative:* a separate `observability.*` threshold property. Rejected as a knob with no independent
   meaning.

10. **Outcome-line throttling and the other throttles are in-memory maps per replica, keyed by pipeline.**
    - One `TransitionLog` component holds the last logged state per pipeline for each change-only line (the
      materialize outcome, the scan's empty page, the aggregate held-back lines). `PipelineService` prunes it
      where it drops the runner-contact and aggregate series on delete and disable, so it is bounded by the
      registry.
    - The `DefaultExceptionHandler` rate limit is keyed by (handler, `error` code, best-matching path pattern)
      with a 60 s window. A pipeline read-budget refusal adds the pipeline name to the key: every pipeline
      shares the pattern, and the name is bounded because that refusal comes only after it resolved.
    - Duplicates across replicas are accepted, because the pod name is on the OTLP resource.

11. **`query_id` is `adas-{pipeline}-{stage}-{uuid}`, minted per statement.**
    - `PipelineReadBudget.run` and `ClickHouseInsertSelectExecutor.insertSelect` take a
      `StatementTag(pipeline, stage)` and mint the id there.
    - `ClickHouseBudget.remainingTime` copies all settings, which include `query_id`, so the retry path sets
      the id after the copy.
    - `ClickHouseSpans` gets the id as a span attribute (`db.query.id`).
    - The aggregate write (`AggregateRefreshService:257`, the three-argument `insertSelect` with a null budget)
      moves to the budgeted form, with `pipeline.budget.*` and a tag. The unbudgeted overloads are removed.
    - The caller hands a `StatementTag` and the callee mints, so no call site can reuse an id. The id is also
      published as MDC `query_id` and left in place after the statement: it is the thread's most recent
      pipeline statement, so the outcome line and the error handler's refusal line carry the id they describe.
      The access-log filter restores it with the other request keys, and the aggregate scheduler clears it
      after each pipeline. `ClickHouseBudget.remainingTime` drops the id from the settings it copies.
    - The outcome line's clamp bound gives the version half as its epoch-millisecond position and the
      identity half as an 8-hex SHA-256 digest: the identity is a source column value, and the digest still
      shows whether the bound moved. `CursorPosition` renders every such position, so the aggregate
      clamped-window line and the position writer's lines, which printed the raw identity before, use the
      same `(version_ms,#8hex)` form.
    - A statement refused while running on a remainder names the configured timeout, with the remainder beside
      it, because the remainder (`PT43.217S`) is no value an operator can set.

12. **The position-discard line needs the old generation from the same statement.** `discardMarkOfOtherGeneration`
    is JPQL and returns a count. JPQL has no `RETURNING`, and on PostgreSQL 16 (the test image) plain
    `RETURNING` gives the *new* row; `RETURNING old.*` needs PostgreSQL 18. So make it a native
    `UPDATE pipeline p SET … FROM (SELECT name, materialized_generation FROM pipeline WHERE name = :name FOR UPDATE) prev`
    `WHERE p.name = prev.name AND … RETURNING prev.materialized_generation`. It returns an `Optional<Long>`:
    empty means no discard, a null value means the first mark (not logged), and a value means log it.

13. **The runner last-attempt series is a second map and gauge in `RunnerContactMetrics`,
    `enrichment.runner.attempt.staleness.seconds`.**
    - The four data-plane handlers record it when the call completes. A call that succeeded resolved the name
      as an enrich pipeline on its way, so it is recorded with no lookup of its own. Only a refused call asks
      the registry (`existsByNameAndKindAndEnabledTrue`), and a `404` skips even that.
    - *Rejected:* a lookup before every delegation. It put one extra PostgreSQL read and transaction on every
      scan, group fetch, write and materialize to learn what the successful call proves anyway.
    - A disabled pipeline's refusal is not an attempt. Disabling drops both series, and a straggling call that
      re-created the attempt series would leave it climbing for a pipeline nothing is meant to call.
    - Contract refusals happen in the interceptor before lookup, and are not recorded.
    - `forget` removes both series.

14. **Generation logging lives at the three bump sites:** `PipelineService.stamp` (`:708`, called from `create`
    `:136` and `patch` `:271`), `PipelineGenerationService:85` (repoint) and `:160` (target change). `stamp`
    takes a cause argument. The actor is the principal the service already resolves for Envers attribution,
    when one is in scope. Nothing new is threaded into the cascade paths, which log `actor=-`, as does a mutation with no principal (the Runner logs `principal=-` the same way).

15. **Aggregates.**
    - `AggregateScheduler` logs the startup line from `AggregateProperties` in `@PostConstruct`.
    - Overrun: compute the cron's next fire after the tick's start, and WARN if the tick ended past it.
      Spring skips overlapping fires on a pool of one, so the lost slot is otherwise silent.

## Risks / Trade-offs

- **A materialize that used to succeed within 1–3× the budget is now refused.** → The refusal is the
  existing, documented 422. The runner already treats it as deterministic, and the outcome line and the
  soft WARN show the stage split before it happens. The operator raises `PIPELINE_BUDGET_TIMEOUT`, and the
  runner's read timeout, which must stay above it, together.
- **The aggregate write under a budget may refuse a legitimately large window every tick.** The preview
  requirement's own reasoning says constraining refresh "would make a legitimate large window fail". →
  `AGGREGATE_WINDOW_LIMIT` bounds the recompute, and a refusal is a recorded failure with a remedy. The
  capacity risk of an unbounded write holding a pool of one is the larger one.
- **A static `LOGGING_LEVEL_COM_EPAM_AIDIAL_ANALYTICS_SQL=DEBUG` still prints literals.** → It is
  restart-scoped and environment-controlled, and it is documented as such.
- **Per-replica throttles mean N first lines per state change.** → This is accepted, and the pod name
  separates them.
- **The dynamic levels depend on a ConfigMap mount the deployment has to add.** → With no file, the service logs one
  INFO and runs on its XML levels. It fails soft.

## Migration Plan

- The change is additive except for the single deadline. There's no Flyway migration: the native query
  changes no schema.
- Before rollout, confirm that the runner's `ADAS_READ_TIMEOUT_MS` is above `PIPELINE_BUDGET_TIMEOUT` in
  each environment. With the single deadline this becomes the true per-call pairing.
- Deployment requirement: a ConfigMap with key `logging.levels.json` and an empty `loggerLevels`, mounted as a
  directory at `/app/log-config` with no `subPath`, `optional: true`, not immutable, and `LOG_CONFIG_PATH`.
  Expect 60–90 s of kubelet sync delay on top of the poll interval.
- Rollback: redeploy the previous build. The level file is ignored by older builds.

## Open Questions

- Whether a principal is in scope at `PipelineService.stamp` for every mutation path. Where it isn't, the
  line gives `actor=-`, as the spec requires.
