## Context

ADAS has every piece an aggregate pipeline needs except the pipeline itself:

- **Compilation** — `StructuredQueryBuilder.buildAggregate` already turns a `StructuredQuery` in
  `AGGREGATE` mode into a jOOQ `SelectQuery` with group keys, aliased aggregate calls, and `having`.
  `ExprTranslator` already validates and renders `count/sum/avg/min/max` and `date_bin(1, unit, ts)`
  against a closed catalog (`QueryFunctionCatalog`).
- **Set-based write** — `MaterializeService` (Class-1) already performs *probe-then-write*: a keyset probe
  fixes a window end, then one `INSERT … SELECT` runs through `ClickHouseInsertSelectExecutor` on the
  writer credential. `CursorPredicates` supplies the safety-lag, window, and keyset-seek conditions;
  `ScanSourceMetadata` resolves a source's cursor columns and `QueryEntityModel`.
- **Governance** — `FilterSqlValidator` for the bounded predicate grammar, `SensitiveProjectionGuard` for
  the sensitive-column rule.
- **A collapsing target** — `write: upsert_by_key` from `align-table-write-facet`.
- **Scheduling precedent** — `@EnableScheduling` with cron-driven `DataSyncService` / `BlobPollingService`.

So this change is mostly *assembly plus a registry*, and the design decisions are about which existing
seam to reuse and what the refresh contract is — not about new machinery.

Two constraints shape it. The runner's design keeps the Enrichment Runner out of the aggregate path (it runs
`enrich` only), so scheduling must live in ADAS or in ClickHouse. And the Runner hard-codes `/v1/rules*`
in six places, so the existing `rule` registry cannot be renamed or re-shaped.

Decided before planning: admin self-serve API · `periodic` only, ADAS-internal cron · a new
`pipeline` object at `/v1/pipelines` with `kind=aggregate` only · only the prerequisites the aggregate
path genuinely needs.

## Goals / Non-Goals

**Goals:**

- Persist a rollup that is correct under repeated refresh, late-arriving rows, retries, and concurrent
  runs on multiple instances.
- Reuse the query stack for compilation — no second SQL builder, no second scan implementation.
- Make a stalled or misconfigured rollup visible (preview before enable, state and staleness after).
- Shape the registry so `external` and `enrich` can converge onto it later without re-modelling.
- Leave the Enrichment Runner untouched.

**Non-Goals:**

- `incremental` freshness (`AggregatingMergeTree` + `*State`/`*Merge`, the mergeability gate) — rejected
  at registration, deferred.
- Multi-source inputs (D-TR-4), tuple grain beyond a rollup's own multi-column `ordering_key`, a
  recursive lineage DAG / `GET /tables/{T}/lineage`, cascade delete, per-tenant quotas.
- `external` / `enrich` pipeline kinds; auto-provisioned default pipelines for plain sources.
- Calendar `month` buckets and approx `count_distinct`/`percentile` — real gaps against the design
  vocabulary, but query-catalog work that stands on its own.

## Decisions

**1. The refresh recomputes every dirty group key in full — the correctness core.**

The target is a `ReplacingMergeTree` keyed on the group tuple, so a write **replaces** a key's row. A
naive cursor-windowed aggregate would compute a partial sum over just the new window and insert it,
overwriting the bucket's running total with the newest slice — silently, with no error and no duplicate
row to notice. The only shape that is correct under `upsert_by_key` is: find which keys the window
touched, then recompute those keys from all of their input rows.

```sql
INSERT INTO usage_daily (deployment, day, call_count, cost, _updated_at)
SELECT deployment, date_bin(1,'day',request_time) AS day, count(), sum(total_price), now64(3)
FROM dial_usage_log
WHERE success = true
  AND (deployment, date_bin(1,'day',request_time)) IN (      -- dirty keys, from the probed window only
      SELECT DISTINCT deployment, date_bin(1,'day',request_time)
      FROM dial_usage_log
      WHERE success = true AND <safety lag> AND <cursor, probed_end>)
GROUP BY deployment, day
```

The probe is kept for one reason: it pins the window end to two scalars *before* the write, so the
sub-select inside the write covers exactly the range that was probed. That is what makes a retry
re-derive the identical key set and write identical values — which the target then collapses. Idempotence
falls out of the shape rather than being bolted on, and it is also what lets the scheduler run without
leader election (below).

*Alternatives considered:* **partial `*State` accumulation** on an `AggregatingMergeTree` — this is the
deferred `incremental` design; it makes windows additive but constrains measures to mergeable ones and
changes the target's stored column types, so it cannot be the `periodic` contract.
**Time-window lookback** (recompute the last N buckets) — simpler and prunable, but only works when a
time bucket exists; `conv_rollup` groups by `chat_id` alone. The dirty-key form covers both uniformly.

*Known cost:* the sub-select scans only the window, but the outer half scans every input row for the
affected keys. That is inherent to "recompute the bucket" and is the price of correctness. When a `trunc`
key is present the compiled statement can additionally carry explicit range bounds on the underlying time
column so ClickHouse prunes partitions — a follow-up optimization, not a v1 blocker, and deliberately not
specced so it cannot be mistaken for part of the contract.

**2. The API takes the design's `{group_by, measures}` shape; the internals reuse the query DSL.**

The wire shape is the engine-agnostic declaration from the tables-and-datasets design — not a raw
`StructuredQuery` passthrough. Two reasons: the shape *is* the constraint (D-DECL-4's "one function over
one column" is structural, not a rule to enforce), and it keeps the persisted spec portable. Internally
`AggregateCompiler` lowers `trunc` to `date_bin(1, unit, col)` and measures to catalog function calls
through **`ExprTranslator`**, then composes the `SelectQuery` directly.

*Amended during implementation:* the compiler deliberately does **not** go through
`StructuredQueryBuilder.build`. That builder applies a default `LIMIT 100` when a query carries no page
(`applyPage`), which on a write path would silently truncate a rollup to its first 100 group keys — the
exact class of quiet wrongness this design is otherwise built to avoid. Stopping at the expression
translator keeps the reuse that matters (one function catalog, one expression translator, no SQL string
assembly) and is also what `MaterializeService` does on the Class-1 write path.

*Consequence accepted:* the bucket units are whatever `date_bin` supports (`hour`/`day`/`week`), so
calendar `month` is rejected rather than silently approximated as 30 days. Adding `date_trunc` to the
shared catalog would lift this and benefits the query DSL too — a clean follow-up.

**3. The target must already exist, and its `ordering_key` must equal `group_by`.**

The design derives a rollup's grain *from* `group_by`. Deriving it would mean the pipeline emits DDL,
which collides with ADAS's explicit **create → define schema → materialize** lifecycle and would give
rollups a second, privileged provisioning path. Validating equality instead achieves the same no-drift
property with none of that: two calls to set up a rollup, and a 422 the moment the two could diverge.
Rejecting an `append` target is part of the same gate — it is the one misconfiguration whose symptom is
silently multiplied measures rather than an error.

**4. `periodic` is rejected-not-downgraded for `incremental`.**

The design permits "reject **or** auto-downgrade". Rejecting is the honest choice: a caller who asks for
real-time materialization and silently receives hourly batches has no way to discover it. The error names
the deferred capability.

**5. Scheduling is an ADAS-internal tick with a per-pipeline `next_run_at`.**

One `@Scheduled` tick selects enabled pipelines whose `next_run_at` has passed, refreshes them, and
recomputes `next_run_at` from the pipeline's cron. A tick plus a due-check (rather than dynamically
registering per-pipeline `TaskScheduler` triggers) is restart-safe and needs no rescheduling when a
pipeline is registered, patched, or disabled.

**No leader election, deliberately.** Two instances may refresh the same pipeline on the same tick; both
recompute the same keys from the same committed input and write the same values, which the target
collapses. This is the same argument `DataSyncService` already rests on, and it is only available because
of decision 1 — correctness resting on idempotence rather than on coordination.

*Alternative considered:* **ClickHouse refreshable MV** (`REFRESH EVERY`) — least code, but an
experimental engine feature that gives ADAS no cursor, no rows-written count, and no staleness signal,
and the design frames it as a permitted adapter optimization rather than the contract.

**6. A new `pipeline` table rather than extending `rule`.**

The tables-and-data-movement design says the class-2 `rule` *is* the `enrich` pipeline kind, generalised rather than duplicated. Done
literally today that means surgery on `rule` — nullable `evaluator_name`, `target_enrichment` renamed to
`target`, its enrichment-only FK and CHECKs relaxed — and a changed registry-sync payload for the Runner,
which consumes `/v1/rules` in six places. So: introduce the Pipeline object properly, with the `kind`
discriminator from the start, implement only `aggregate`, and leave `rule` as the `enrich` realization
with a documented convergence path. The one-pipeline-per-target invariant holds across both registries
for free, since a rule targets an `enrichment` and a pipeline targets a `source`.

**7. Runtime state as typed columns, exposed as a read-only `state` object.**

The design specifies a free-form `state` bag with `GET/PUT` endpoints. Here the cursor is ADAS-held (no
caller drives the loop), and it must be queryable for due-selection, so typed columns
(`cursor_version`, `cursor_identity`, `next_run_at`, `last_run_at`, `last_error`) are the right storage.
The wire shape still presents them under a `state` object so the API matches the design's concept, and
there is no `PUT`: a caller-chosen cursor could skip input rows and produce measures that are wrong
without being obviously wrong.

**8. Sensitive-column governance runs at registration *and* execution.**

Mirrors `MaterializeService` exactly, and for a sharper reason here: an aggregate is a laundering path.
`min`/`max` over a sensitive column, or a sensitive column as a group key, copies protected values into a
target column that carries no `sensitive` flag. Re-checking at execution matters because a column can be
marked sensitive after a pipeline is registered.

## Risks / Trade-offs

- **Recompute cost on a non-prunable group key** (e.g. `chat_id`, which is not the leading `ORDER BY`
  column of `dial_usage_log`) → inherent to bucket recomputation; bounded per run by the window limit,
  with `has_more` continuing on the next tick. The `trunc` range-bound optimization is the follow-up.
- **A rollup's measures are only as fresh as the last refresh**, and a stalled scheduler looks identical
  to a quiet period at the query surface → staleness is both an API field and a metric; preview-before-enable
  catches the misconfiguration class before it can stall silently.
- **Rows deleted from the input (TTL expiry) do not re-dirty their keys**, so a rollup can retain a bucket
  whose inputs have aged out → this is usually *desired* ("aggregate outlives raw"), and a rollup target
  carries its own retention. Worth stating rather than fixing.
- **Duplicate concurrent refreshes waste work** even though they are correct → acceptable at this scale;
  advisory locking can be added later without changing the contract, precisely because correctness does
  not depend on it.
- **`kind` is a one-member enum for now** → the alternative (no discriminator) forces a re-model when
  `external`/`enrich` converge; a one-member closed set is the cheaper direction to be wrong in.
- **Two registries for one conceptual object** until `rule` converges → mitigated by the structural
  impossibility of target collision and by documenting the path; the alternative breaks the Runner today
  for a refactor with no user-visible benefit.

## Migration Plan

1. `V20__aggregate_pipeline.sql` creates `pipeline` (FK `target` → `table_definition` `ON DELETE CASCADE`,
   FK `input_table` → `table_definition`, `UNIQUE (target)`). Purely additive; no existing table or row is
   touched, no ClickHouse DDL is issued.
2. Deploy with `aggregate.enabled=false` to land the schema and API without starting the scheduler; the
   surface is inert until a pipeline is both registered and enabled, so there is no default-on behaviour
   change.
3. Rollback: the previous image ignores the `pipeline` table entirely (no code reads it) and the new
   `/v1/pipelines` routes simply disappear. A rollup target created in the meantime remains a normal
   `upsert_by_key` source holding its rows; it merely stops being refreshed.

## Open Questions

- Whether `refresh` should accept an explicit `(from, to)` window for backfilling a specific range, as
  `DataSyncBackfillController` does per-day. Deferring: the cursor-driven form already backfills forward
  from wherever the cursor sits, and a bounded-range variant is additive whenever a concrete need appears.
- Whether a rollup should be usable as an enrichment **parent** in this change. It is a `source`, so
  nothing forbids it, and single-key rollups (`conv_rollup` on `chat_id`) work with today's single-column
  grain. Multi-column-key rollups would need tuple grain, which stays deferred — so this is left
  unasserted rather than promised.
