## 1. Configuration

- [x] 1.1 Add `@Min(1) int schedulerPoolSize` to `AggregateProperties`, with a javadoc paragraph saying it
      sizes the aggregate tick's own scheduler and that raising it permits overlapping ticks, not parallel
      refreshes within one tick.
- [x] 1.2 `application.yml`: add `scheduler-pool-size: ${AGGREGATE_SCHEDULER_POOL_SIZE:1}` under
      `aggregate:` — bare `key: ${ENV:default}`, no comment (descriptions live in `docs/configuration.md`).

## 2. Scheduler beans

- [x] 2.1 New `configuration/SchedulingConfiguration`. Declare
      `@Bean @Primary ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder)` —
      reproducing Boot's auto-configured bean, which our own `TaskScheduler` bean would otherwise
      suppress via `@ConditionalOnMissingBean(TaskScheduler.class)`. Put that reason in the class javadoc;
      it is not inferable from the code.
- [x] 2.2 Declare `@Bean ThreadPoolTaskScheduler aggregateTaskScheduler(AggregateProperties properties)` —
      pool size from `schedulerPoolSize`, a distinct thread-name prefix, and shutdown behaviour matching
      the shared scheduler's.
- [x] 2.3 `AggregateScheduler.tick`:
      `@Scheduled(cron = "${aggregate.tick-cron}", scheduler = "aggregateTaskScheduler")`.
- [x] 2.4 Correct the `AggregateScheduler` class javadoc (`:39-43`): the elevation is still scoped per
      pipeline, but because a tick refreshes many pipelines on one thread — not because the pool is shared
      with the poller and the sync job.

## 3. Docs

- [x] 3.1 `docs/configuration.md`: add the `aggregate.scheduler-pool-size` / `AGGREGATE_SCHEDULER_POOL_SIZE`
      row to the aggregate table, stating that it permits overlapping ticks rather than within-tick
      parallelism.
- [x] 3.2 Amend the aggregate-section sentence that says the tick shares the scheduled-task pool with the
      blob poller and the data-sync job, and re-point the cross-reference to
      "Scheduled task execution" so `SCHEDULING_POOL_SIZE` is not read as sizing the tick.

## 4. Spec

- [x] 4.1 Apply the `aggregate-pipeline` delta: "Scheduled refreshes execute under a system identity"
      gains the isolation paragraph and the guarantee that the ingestion scheduler is undisturbed, with
      two new scenarios; its elevation rationale is re-grounded on the per-tick thread.

## 5. Tests and verification

- [x] 5.1 Context test: both `taskScheduler` and `aggregateTaskScheduler` beans exist, and `taskScheduler`
      is the primary.
- [x] 5.2 Regression test for the `@ConditionalOnMissingBean` trap: with `SCHEDULING_POOL_SIZE` set, the
      shared scheduler's pool size matches it. This is the failure that would otherwise be invisible.
- [x] 5.3 Test that the tick executes on the aggregate scheduler — assert the executing thread's name
      prefix from inside the scheduled method, or assert the registered task's scheduler.
- [x] 5.4 `./gradlew clean build` (Docker available for the integration tests)
      is green, and `openspec validate isolate-aggregate-scheduler-pool` passes.
- [x] 5.5 Smoke against `./gradlew bootRun`: refresh log lines carry an aggregate-pool thread name, and the
      poller/sync lines carry the shared pool's.
