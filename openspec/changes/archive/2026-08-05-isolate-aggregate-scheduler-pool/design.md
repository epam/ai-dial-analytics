## Context

ADAS has three `@Scheduled` entry points: `BlobPollingService` (near-real-time blob poll),
`DataSyncService.syncDaily` (reconciliation ingest), and `AggregateScheduler.tick` (rollup refresh,
default cron `0 * * * * *`). All three share Boot's auto-configured `taskScheduler`, sized by
`SCHEDULING_POOL_SIZE` (default 4) and documented in `docs/configuration.md` as "Threads available to
scheduled jobs. Must exceed the number of concurrent jobs, or a slow one delays the others."

That warning is the whole issue: a refresh is the one job whose duration is unbounded by design — it
recomputes every dirty key, and for a grain with no time bucket that means reading the whole input.
`AggregateScheduler`'s own javadoc already notes the pool is shared ("this thread pool is shared with the
blob poller and the data-sync job") as the reason the system-identity elevation must be unwound per
pipeline.

## Goals / Non-Goals

**Goals:**

- The aggregate tick cannot occupy a thread the ingestion jobs need.
- The isolation is sized from configuration, so widening it later needs no code change.
- `SCHEDULING_POOL_SIZE` keeps meaning exactly what it means today for the two ingestion jobs.

**Non-Goals:**

- Concurrency *within* a tick. Refreshes stay serial; a single-thread default preserves current
  behaviour bit for bit.
- Any coordination between replicas (advisory lock, leader election, refresh claim). A single-replica
  deployment pays none of the duplicated-work cost the doc describes, and the design's standing
  position — correctness rests on idempotence, not coordination — is untouched.
- Touching `DataSyncService` or `BlobPollingService`.

## Decisions

**Declare both schedulers, not just the new one.** This is the load-bearing detail. Boot 4's
`TaskSchedulingConfigurations$TaskSchedulerConfiguration` is annotated
`@ConditionalOnMissingBean(TaskScheduler.class)` (verified against `spring-boot-autoconfigure-4.0.7`), so
contributing *any* `TaskScheduler` bean removes Boot's `taskScheduler` — regardless of what the new bean
is named. The ingestion jobs would then fall back to whatever `ScheduledAnnotationBeanPostProcessor`
picks, and `SCHEDULING_POOL_SIZE` would silently stop applying: a configuration knob that reads as
honoured and is not. The new `SchedulingConfiguration` therefore declares the shared `taskScheduler` from
`ThreadPoolTaskSchedulerBuilder` — reproducing what Boot would have built, `spring.task.scheduling.*`
included — alongside `aggregateTaskScheduler`.

**Mark the shared one `@Primary`.** With two `TaskScheduler` beans, `@Scheduled` methods that name no
scheduler resolve by type and would otherwise hit `NoUniqueBeanDefinitionException` (Spring then falls
back to the bean *named* `taskScheduler`, which would work, but by accident of naming rather than by
declaration). `@Primary` states the intent: the shared pool is the default, and the aggregate tick is the
one job that opts out.

**Default the aggregate pool to 1, not to `SCHEDULING_POOL_SIZE`'s 4.** One thread reproduces today's
semantics exactly — refreshes serial, ticks non-overlapping — so this change is isolation and nothing
else. Sizing it above 1 would let two ticks overlap, which is a different change with its own
correctness argument to make (it happens to be safe, because refresh is idempotent, but that argument
should be made deliberately and measured, not arrive as a side effect of a pool-splitting commit).

**Configuration lives under `aggregate.*`, not `spring.task.*`.** It is a property of this capability,
sits next to `tick-cron` and `window-limit`, and validates with the rest of `AggregateProperties`. Its
description goes in `docs/configuration.md` only, per the project's single-source rule; `application.yml`
gets a bare `key: ${ENV:default}` line.

**Fix the two places that now assert something false.** `AggregateScheduler`'s javadoc justifies the
per-pipeline elevation scope with the shared pool; the scoping is still right, but the reason becomes
"a tick refreshes many pipelines on one thread". `docs/configuration.md`'s aggregate section says the
tick shares the pool with the poller and the sync job, and links to the sizing guidance — both need
amending, or the next reader sizes the wrong pool.

## Risks / Trade-offs

- **The `@ConditionalOnMissingBean` trap regresses silently if the shared bean is ever dropped** →
  mitigated by a test asserting the ingestion scheduler is sized by `SCHEDULING_POOL_SIZE`, which is the
  only symptom that would otherwise surface in a deployment as "ingestion got slower after a deploy".
- **Two schedulers means two thread pools even where one would do** → the aggregate default is a single
  thread, so the cost is one idle thread per instance.
- **Reproducing Boot's `taskScheduler` could drift from Boot's own defaults across upgrades** → building
  it from `ThreadPoolTaskSchedulerBuilder` rather than by hand means the builder (still auto-configured,
  and fed by `spring.task.scheduling.*`) supplies those defaults; only the `@Bean` declaration is ours.
- **An operator raises `AGGREGATE_SCHEDULER_POOL_SIZE` expecting faster drains** → it permits overlapping
  *ticks*, not parallel refreshes within one; worth stating in the `docs/configuration.md` row so the
  knob is not misread as within-tick parallelism.
