## Context

ADAS ingests DIAL usage logs into ClickHouse via two in-process paths: a scheduled daily
reconciliation (`DataSyncService.runOnce()`, target date = `today − SYNC_DAY_OFFSET`) and a
near-real-time poller (`BlobPollingService`, moving in-memory watermark). Both target only recent
dates and neither is HTTP-triggerable by design — the `data-sync` spec states the job "MUST run
in-process and MUST NOT be triggerable via an HTTP endpoint in this phase."

There is no facility to ingest an arbitrary historical window. Backfilling N days today means cycling
`SYNC_DAY_OFFSET` and restarting once per day. Operators need a single explicit action to backfill a
bounded range (e.g. the past 30 days) when standing up a new store, recovering from extended downtime,
or onboarding history — without touching the recurring schedule.

The ingestion primitive already exists: `DataSyncService` resolves a date-derived prefix
(`<path-prefix>/{yyyy}/{MM}/{dd}/`) and runs a per-day parse → map → batch-insert pipeline. Dedup is
handled entirely by the target table's `ReplacingMergeTree` engine on `(trace_id, core_span_id)`, so
re-ingesting overlapping dates is safe. Backfill is therefore a *loop over the existing per-day ingest*
plus a thin HTTP surface and run-tracking — not new ingestion logic.

## Goals / Non-Goals

**Goals:**

- One explicit, operator-triggered action to backfill an inclusive UTC date range.
- Asynchronous execution: trigger returns immediately; progress is pollable via a status endpoint.
- Reuse the existing discovery + parse → map → batch-insert pipeline and engine-level dedup unchanged.
- Bound the blast radius: single in-flight run, validated range, configurable max-span cap, per-day
  failure isolation.
- Leave the scheduled and poller paths and their requirements untouched.

**Non-Goals:**

- Persisting run state across restarts, or resuming an interrupted backfill. State is in-memory,
  consistent with the existing no-persisted-cursor design.
- A general job framework, queueing multiple backfills, or cancellation of an in-flight run (single
  in-flight; cancellation can be a later change).
- Authentication / authorization for the endpoint — the service has no auth framework today; access
  control stays operational (network/ingress), as already assumed for the rest of `/v1`.
- Any change to dedup semantics, mapping, batching, or the target schema.

## Decisions

### Decision: Reuse a refactored per-day ingest, don't duplicate the pipeline

Extract the per-date ingest step from `DataSyncService.runOnce()` into a reusable method —
`runForDate(LocalDate)` returning the existing per-run summary (files processed, rows inserted /
skipped / dropped). `runOnce()` becomes `runForDate(today − dayOffset)`; the backfill orchestrator
calls `runForDate(d)` for each `d` in the range.

- *Why*: guarantees backfill and the schedule share identical discovery, mapping, batching, and dedup
  behavior; no second code path to keep in sync.
- *Alternative*: a standalone backfill ingest path — rejected as duplication and drift risk.

### Decision: New `DataBackfillService` owns orchestration + an in-memory run registry

A new `service/DataBackfillService` holds: a single-permit guard for in-flight runs (e.g.
`AtomicReference<runId>` / `tryLock`), a single-threaded `ExecutorService` worker, and a
`Map<runId, BackfillRun>` registry of mutable run-status objects updated as each day completes. `runId`
is a UUID.

- *Why single-threaded executor*: bounds ClickHouse write load and keeps "single in-flight" trivial;
  the trigger thread returns immediately after submitting.
- *Why in-memory registry*: matches the existing watermark/no-cursor philosophy; a backfill is a
  one-time operational action, so losing status on restart is acceptable and called out in the spec.
- *Registry growth*: cap retained runs (small LRU, e.g. last 16) so memory can't grow unbounded across
  many backfills in one process lifetime.

### Decision: HTTP surface under `/v1/data-sync/backfill`

- `POST /v1/data-sync/backfill` — body `{ "from": "yyyy-MM-dd", "to": "yyyy-MM-dd" }`. Validates,
  registers a run, submits to the worker, returns `202 Accepted` with `{ runId, from, to, days }`.
- `GET /v1/data-sync/backfill/{runId}` — returns the status view (state, daysTotal, daysCompleted,
  daysFailed, currentDate, filesProcessed, rowsInserted, rowsSkipped, rowsDropped, startedAt,
  finishedAt). Unknown id → `404`.

New `web/` controller + request/response DTOs. Errors go through `DefaultExceptionHandler` as
`ErrorView` with new stable machine codes: invalid range (`400`), backfill in progress (`409`),
unknown run (`404`), feature disabled (`409`/`404` — settle in apply; treat as not-available).

- *Why `202` + status endpoint over a blocking `200`*: a 30-day range can run for minutes; blocking
  invites client/proxy timeouts. Confirmed with the maintainers.

### Decision: Validation rules and the max-span cap

Reject (`400`, no run started) when: `from`/`to` missing or unparseable; `from > to`; `to >` current
UTC date; or inclusive span `> backfill.max-days`. The cap (default e.g. 366) prevents an accidental
unbounded run. Validation happens on the request thread before submission.

### Decision: Per-day failure isolation, like the existing pipeline

The orchestrator wraps each `runForDate(d)` in try/catch: a day that throws (e.g. store unreachable for
that listing) is counted in `daysFailed` and logged; the loop continues. The run still reaches a
terminal state; the operator reads `daysFailed` and re-runs the affected sub-range. Empty days
complete cleanly (zero rows), mirroring the existing "empty prefix completes cleanly" behavior. A run
where every day failed MAY be surfaced as `FAILED`; otherwise `COMPLETED` with a non-zero `daysFailed`.

### Decision: Concurrency with the poller and scheduled reconciler

Backfill is allowed to run while the poller and the scheduled reconciler are enabled and active — the
existing "no overlapping runs" guards are per-path and guard each path only against itself (the
single-threaded scheduler, the poller's in-cycle guard, and backfill's single-in-flight guard). There
is deliberately no cross-path mutual exclusion. Correctness under that overlap rests on two properties:

1. **Engine-level dedup** — all three paths write the same `(trace_id, core_span_id)` rows through the
   same writer credential into the same `ReplacingMergeTree`; any rows a backfill re-ingests for a date
   already covered by the poller/reconciler collapse on merge, exactly as poller↔reconciler overlap
   already does today. Concurrent `INSERT`s create independent parts, so there is no write-write
   corruption.
2. **Per-run-local ingest state** — `DataSyncService` already builds its `RowBatchSink` as a local
   variable per run, and its only instance state is thread-safe (`AtomicLong` + Micrometer counters).
   The extracted `runForDate(LocalDate)` therefore stays re-entrant: a backfill thread and the
   scheduler thread invoke it concurrently with **separate** sinks, with no shared batch buffer to
   interleave. This re-entrancy is a hard requirement of the refactor, not an accident.

The poller's watermark tracks blob *last-modified* time and is untouched by a backfill inserting old
*rows*, so backfilling history never perturbs the poller's position.

- *Trade-off*: the only cost of concurrent paths is resource contention — additional ClickHouse write
  load and more parts to merge while a backfill runs alongside live ingestion. Operators run large
  backfills in lower-traffic windows; the `max-days` cap bounds a single request. No path needs to be
  disabled for correctness.
- *Alternative*: pause the poller/reconciler for the duration of a backfill — rejected as unnecessary
  (dedup makes it safe) and as adding cross-path coupling and a gap in live ingestion.

### Decision: Configuration

New env-driven `backfill.*` group (descriptions live only in `docs/configuration.md`):

- `backfill.enabled` / `BACKFILL_ENABLED` (default `true`) — master on/off for the endpoints.
- `backfill.max-days` / `BACKFILL_MAX_DAYS` (default `366`) — max inclusive span accepted.

Batch size, mapping, path-prefix, table, and the writer credential are reused from `sync.*` and the
existing ClickHouse config — no duplication.

## Risks / Trade-offs

- **In-memory run state lost on restart** → Spec states a backfill does not auto-resume and a `runId`
  may become unknown after restart; the operator re-triggers. Acceptable for a one-time tool.
- **Long run holds resources / competes with live ingestion** → Single-threaded worker bounds write
  concurrency; dedup makes overlap with the poller harmless. Operators run large backfills in
  lower-traffic windows; the max-days cap bounds a single request.
- **Unauthenticated trigger could be abused** → Same posture as the rest of `/v1` (no auth framework);
  single in-flight + max-days cap limit blast radius. Auth is out of scope and noted as a follow-up.
- **Status endpoint exists for backfill but `data-sync` says "no HTTP status endpoint in this phase"**
  → That statement scopes the scheduled/poller paths; backfill is a separate capability with its own
  contract. No `data-sync` requirement changes.
- **Registry memory growth across many backfills** → Bounded LRU of retained run statuses.

## Migration Plan

Additive only — new endpoints, service, config, and spec; no schema or behavior change to existing
paths. Deploy normally. Rollback: set `BACKFILL_ENABLED=false` (endpoints inert) or revert the change;
nothing persisted needs cleanup. Verify with a small range (1–2 days) against a running instance and
confirm rows land and the status endpoint reports completion before backfilling the full window.

## Open Questions

- Exact terminal-state taxonomy when some days fail (`COMPLETED` with `daysFailed > 0` vs a distinct
  `COMPLETED_WITH_ERRORS`) — settle during apply; the spec only requires the failed-day count be
  observable and a terminal state be reached.
- Whether to add an optional `dryRun` (resolve + count blobs without inserting) — deferred unless asked.
