## Why

An aggregate pipeline resolves `group_by`, `measures[].column`, `filter` and `measures[].where`
against the declared columns of **one source table**, so anything an enrichment computed is
invisible to it. Three refusals show both faces of the wall:

```
group_by column 'usage_client_identity.client_session_id'
  is not a declared column of input 'dial_usage_log'      -- qualified name
group_by column 'client_session_id'
  is not a declared column of input 'dial_usage_log'      -- bare name
input table 'usage_client_identity' is not a source       -- enrichment as the input
```

This blocks any rollup whose grain is a **derived** value. The concrete case is `client_session_id`,
the normalised coding-agent session id produced by the `usage_client_identity` enrichment: without
it a `sessions` rollup can never be filled, and coding-agent sessions stay invisible at conversation
grain.

It is an asymmetry between two ways of reading the same catalog, not a one-off gap. The read path
(`/v1/queries/execute`, `execute-sql`, Flight) has resolved enrichment columns through the flat
entity model since the table-management API landed — the qualified flat name is already what
discovery publishes — and only the pipeline path never caught up. So the same column is addressable
in a query and unaddressable in a rollup over the same entity.

Nothing here changes which table kinds write what. An aggregate pipeline writes a `source`, an
enrichment `rule` writes an `enrichment`, and *Target shape governance* refuses an `enrichment`
target with 422; *One pipeline per target* depends on that separation, since it is what stops the two
registries contending for the same table. This change adds a **read** edge and no write edge.

## What Changes

- **Column resolution moves from the input table to the input entity.** A pipeline's `group_by`,
  `measures[].column`, `filter` and `measures[].where` resolve against the flat field set
  `GET /v1/queries/entities/{name}` already publishes: the source's columns plus every ACTIVE
  enrichment's exposed names, addressed by the existing quoted qualified flat name
  (`"usage_client_identity.client_session_id"`). No new naming scheme.
- **`inputs` still names a source.** An enrichment is reached *through* its source's grain join,
  never declared as the input. The existing refusal and its message are unchanged.
- **The compiled statements emit the grain join.** A `LEFT JOIN` per *touched* enrichment is added
  to every statement the refresh and preview paths compose that references one — the window probe,
  the recompute select, the dirty-key sub-select, both bucket-bound scalar sub-queries, and the
  preview sample. An untouched enrichment emits no join, as on the read path.
- **Every enrichment-sourced column is nullable**, whatever its declaration, because the join is
  outer. The measure-nullability rule and the null-group-key rule both extend to cover it. And because
  ClickHouse's `join_use_nulls` defaults to 0 — so an unmatched join renders a *non-nullable* column as its
  type default rather than as NULL, defeating both rules exactly where they matter — a group key or measure
  may only read an enrichment column that is itself **declared** nullable. Predicates are exempt (they
  select rows rather than produce values); `array`/`map` are exempt (their default is empty).
- **A group key over an enrichment column must declare `as`**: its qualified flat name carries a `.`, which
  no target column name may, so without an alias the key can bind to no target that could exist.
- **Sensitivity crosses the join.** A measure or group key reading a `sensitive` enrichment column
  into a target column not itself declared `sensitive` is refused (422, naming both columns).
  This is not new policy: it is the existing anti-laundering rule, and it is the stated
  justification for the refresh scheduler running as `FULL_ADMIN`.
- **A refresh window is clamped to the slowest referenced enrichment.** An enrichment row is
  written later than its source row, so an unclamped window would read source rows whose enrichment
  cells are still NULL and write a permanently wrong group that nothing later re-dirties. **Specified but
  NOT implemented** — ADAS holds no written-through position to clamp against (design D-6 / Q-4) — so the
  gap ships, and it can be worse than a wrong group: where the `filter` reads an enrichment column, an
  unreached row followed by a reached one is carried past by the window end and never aggregated at all.
  Ordinary trailing lag is safe (the window ends at the last *matching* row, so it parks before the
  unreached newest rows); non-contiguous coverage — a backfill filling older gaps, `sampling`,
  `ready_when` — is what loses rows. Verified against a live ClickHouse, documented against the operator surface,
  and the requirement itself **moved to `aggregate-enrichment-freshness`**, which clamps to a materialized
  position instead of the writer cursor this change could not find. Nothing unimplemented is left in this
  change's delta.
- **Re-running an enrichment over history invalidates dependent rollups.** The clamp guards late
  *arrival*, not late *mutation*; the obligation to reset a dependent pipeline becomes a stated
  requirement rather than folklore.

Not in scope, and deliberately: joins in general (one source plus its own ACTIVE enrichments on the
declared grain key, 0..1, exactly the closed shape the read path allows), a second input, a change
to the one-function-one-column measure contract, and lineage/delete-safety for the new dependency
edge — that last is a follow-up change, because its failure mode is a loud broken refresh rather
than a silently wrong number.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: column resolution changes from the input table's declared columns to the
  input entity's flat field set; new requirements for enrichment-sourced nullability (including the
  declared-nullable rule the outer join's type-default behaviour forces), the alias an enrichment group key
  needs, sensitivity crossing the grain join, the operator obligation after an enrichment re-run, and the
  documented cost of an enrichment-joined refresh. Refresh-window clamping is **not** here — it moved to
  `aggregate-enrichment-freshness`.

`query-schema-discovery` is untouched — the field set this change consumes is already what it
publishes. `table-management` is untouched here and is the subject of the follow-up change.

## Impact

**Behaviour.** Additive on the API surface: no endpoint, request shape or response shape changes,
and a pipeline that references no enrichment column compiles byte-identical SQL and refreshes
exactly as before. Declarations that are refused today start being accepted. Five new 422 refusals
become reachable (non-nullable target for an enrichment-sourced measure; enrichment-sourced group
key with no `filter`; sensitive enrichment column into a plain target column; a value-producing reference
to a non-nullable enrichment column; an enrichment group key with no `as`).

**Code.**

- `service/enrichment/support/ScanSourceMetadata` — a new entity-model accessor beside
  `sourceModel`, which deliberately drops enrichments and is shared with the Class-1 scan, group
  assembly and materialize paths; those keep the enrichment-free model unchanged.
- `service/pipeline/AggregateSpecValidator` — its per-table `Columns` view becomes an entity-wide
  view keyed by flat name, carrying each column's owning table, type, nullability and sensitivity.
- `service/query/translate/StructuredQueryBuilder` — `addReferencedJoins` extracted to a shared
  helper so the read and pipeline paths cannot drift.
- `service/pipeline/AggregateCompiler` — joins added to the recompute select, the dirty-key
  sub-select, both bucket-bound sub-queries, and the preview sample.
- `service/pipeline/AggregateRefreshService` — joins added to the window probe. (The enrichment clamp and
  its state indicator moved to `aggregate-enrichment-freshness`, so `PipelineStateWriter` and the pipeline
  state DTO are untouched here.)
- `service/enrichment/support/SensitiveProjectionGuard` — enforcement across a source's
  enrichments, not just its own columns.

**Operational.** The writer credential already holds `SELECT` on scan/input sources; an
enrichment-joined refresh additionally reads the enrichment table, so `SELECT` must be granted on
enrichment tables that a pipeline references. `docs/configuration.md` gains the cost guidance for
an enrichment-joined refresh.

**Risk carried into design.** A ClickHouse hash join reads its right table in full and does not
push left-side keys down, so an enrichment-joined refresh reads the whole enrichment table on every
tick, at every joined statement site. The recompute half cannot bound this — it must see every row
of every dirty key, so its grain-key set is unbounded by the window. This change measures the cost
and documents it as a property of the declaration; it does not build a mechanism against it.
