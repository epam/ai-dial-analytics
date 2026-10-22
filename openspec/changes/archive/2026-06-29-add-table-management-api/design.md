## Context

ClickHouse table structure is hardcoded in `service/query/DialUsageLogSchema.java` — a hand-built
jOOQ schema mirroring `db/migration/V1__create_dial_usage_log.sql`. Its `QueryFieldBinding`
map (`name → {jOOQ Field, type}`) is the single source of truth for the whole query path:
discovery (`/v1/queries/entities`), field resolution in filters/projections/sorts, and SQL
rendering. Adding a queryable table today means writing Java and redeploying.

A throwaway Postgres `metadata` table (`name TEXT PRIMARY KEY`) plus a full `/v1/metadata` CRUD
stack already exists from an earlier change — it proved the Spring Data JPA +
Flyway Postgres path but carries no real domain value. ClickHouse is reached over HTTP for reads
(read-only credential) and a separate **writer** credential is used for ingest and for programmatic
Flyway migrations (`ClickHouseMigrationRunner`). Until now, ADAS has performed ClickHouse DDL only
through migrations — never from an HTTP request.

This change introduces a runtime catalog in Postgres and a management API over it, makes the read
path read its bindings from that catalog, and adds source+enrichment flattening with automatic
joins to the query surface.

## Goals / Non-Goals

**Goals:**
- Operators define ClickHouse tables and column mappings at runtime, stored in Postgres.
- Expose a column under a different name than its physical ClickHouse column (`source_name` →
  `name`).
- A `source` table plus its `enrichment` tables present as one flat query schema; queries that
  reference enrichment columns are resolved by automatic `LEFT JOIN`s.
- Materialize source/enrichment tables in ClickHouse (`CREATE TABLE`) when their schema is set.
- Replace the hardcoded `DialUsageLogSchema` and the throwaway `metadata` stack; backfill
  `dial_usage_log` into the catalog.

**Non-Goals:**
- Schema **modification** of a materialized table (add/drop/retype columns). Schema is set once;
  modifying a live schema is future work.
- `aggregation`-type behavior (rollups / materialized views). The enum value is accepted; no
  special materialization or query routing is implemented.
- Multi-row (1-to-many) enrichments, composite or configurable join keys, `INNER` joins. v1 is a
  single `(local_key, source_key)` pair, `LEFT`, 0..1 cardinality.
- Querying an enrichment table on its own — enrichments exist only as flattened columns on their
  source.
- Auth/tenancy (still deferred per `project.md`).

## Decisions

### D1 — Catalog data model: `table_definition` + `column_mapping` + `enrichment_join`

> **Amended by D8 (second wave):** the `enrichment_join` (`local_key`/`source_key`) is replaced by
> a grain declaration (`grain_key` + `cardinality`); `table_definition`/`column_mapping` stand.

```
table_definition
  name          PK   -- == ClickHouse table name == {table_id} in the URL path
  description
  type          source | enrichment | aggregation
  source_table  null, FK→table_definition.name   -- required iff type=enrichment
  status        DECLARED | MATERIALIZED | FAILED
  ordering_key  text[]  -- MergeTree ORDER BY columns (source_names), set at schema PUT

column_mapping  -- one row per EXPOSED column
  table_name    FK→table_definition.name
  source_name   physical ClickHouse column
  name          exposed/flat field name (the alias clients see)
  type          QueryFieldType (STRING/LONG/INTEGER/TIMESTAMP/ARRAY/...)
  element_type  null, QueryFieldType -- required iff type=ARRAY
  description

enrichment_join  -- 0..1 row per enrichment table
  table_name    FK→table_definition.name (the enrichment)
  local_key     enrichment physical column (added by us, hidden, type inferred from source)
  source_key    source physical column referenced
  -- join_kind fixed LEFT, cardinality 0..1 in v1
```

**Rationale.** `name` is the catalog key and equals the ClickHouse table name (chosen over a
synthetic UUID: human-readable URLs, and the table name is already the natural identity). The FK
column is *not* a `column_mapping` row — the consumer submits only the columns they want exposed;
ADAS adds the physical join key itself and infers its type from the source key. This keeps "columns
list == flat schema" (no listed-but-hidden surprise) and structurally avoids the join-key name
collision with the source's own key column. Alternative considered: a nullable exposed `name` to
mark hidden columns — rejected because it leaks an exposure toggle into the API the user shouldn't
have to think about.

### D2 — Lifecycle and synchronous ClickHouse materialization

> **Superseded by D10 (second wave):** `POST` now materializes immediately (no `DECLARED` state),
> and schema is modified via `PATCH` rather than set-once-then-frozen.

```
POST /v1/tables → DECLARED (Postgres row only, no ClickHouse table)
PUT  /v1/tables/{name}/schema:
   1. validate (see D5)
   2. write column_mapping (+ enrichment_join, ordering_key) in Postgres
   3. CREATE TABLE in ClickHouse using the WRITER credential
   4. status = MATERIALIZED   (CH failure → status = FAILED)
```

Re-PUT is allowed while `DECLARED`/`FAILED` (overwrite + retry); once `MATERIALIZED` it returns
409. **Rationale.** Synchronous create gives immediate, observable success/failure. The `status`
column makes Postgres↔ClickHouse drift explicit and recoverable: a `FAILED` table is simply
re-PUT. `dial_usage_log` is seeded directly as `MATERIALIZED` because it already exists in
ClickHouse — the backfill must never re-issue its `CREATE TABLE`.

Alternative considered: two-phase / async materialization with reconciliation — rejected as
over-engineered for the expected low volume of table-management operations.

### D3 — Writer credential for management-path DDL

The management path issues `CREATE TABLE` (PUT schema) and `DROP TABLE` (delete) using the existing
**writer** credential (`clickhouse.writer.*`), which already holds DDL grants for migrations. The
query read path stays strictly read-only. **Rationale.** Reuses an existing privileged credential
rather than introducing a third; the writer already needs `CREATE TABLE` for migrations. This
relaxes the "ClickHouse DDL is migration-only" posture in `project.md`, which is updated to note
runtime DDL from the management path. A dedicated `clickhouse.ddl.*` credential was considered and
deferred (more config for no v1 benefit; revisit if least-privilege separation is required).

### D4 — Catalog-backed bindings replace `DialUsageLogSchema`; join-aware query building

> **Amended by D8/D9 (second wave):** the join is now `ON source.grain_key = enrichment.grain_key`;
> the 0..1 guarantee is enforced
> physically by `ReplacingMergeTree` rather than merely asserted. Binding/qualification logic stands.

`DialUsageLogSchema` is deleted. A catalog-backed provider builds `QueryFieldBinding`s from
`column_mapping`, with each binding carrying its **owning table** so jOOQ can qualify columns and
emit joins. For discovery and `execute`, a source's flat schema = its own exposed columns + every
enrichment's exposed columns (FK columns excluded). `StructuredQueryBuilder` inspects which
bindings a query touches (select + filter + sort + group_by), collects the distinct owning tables,
and emits a `LEFT JOIN` per referenced enrichment on `source.source_key = enrichment.local_key`.
Joins are emitted only for enrichments actually referenced. **Rationale.** The existing builder
already qualifies every field and walks all clauses, so "which tables are touched" is computable
with no rework of the DSL; single-key 0..1 joins can't inflate row counts, so aggregates stay
correct.

Source columns are exposed under their bare name; each enrichment column is exposed under a
table-qualified flat name `<enrichment_table>.<column>` (dot separator). Because table/column names
match `^[a-z][a-z0-9_]*$` (no dot), qualified names are unambiguous and the flat namespace is
collision-free by construction, so a source column and an enrichment column — or two enrichments — may
share the same bare name. Qualification is computed in `CatalogQueryService` when assembling the flat
schema; nothing in the catalog (`column_mapping`) changes, and PATCH/write keep operating on bare names.

> **Amended (table-name qualification):** the original design enforced exposed-name uniqueness across a
> source + all its enrichments. That cross-family rejection is removed in favor of always qualifying
> enrichment columns by their table name, which makes collisions impossible rather than rejected.
> Only within-table exposed-name uniqueness is still enforced.

### D5 — Validation and type mapping

> **Amended by D11/D12 (second wave):** identifiers tighten to snake_case with a reserved
> `_`-prefix; columns gain `nullable`; `array` is no longer user-declarable (kept only for the
> backfilled `dial_usage_log`); grain-key validation is added.

At schema PUT: `name` must be a valid ClickHouse identifier; `ordering_key` and `foreign_key`
columns must reference declared `source_name`s; for enrichments the `source_key` must exist on the
named source and the source must be `type=source`; exposed `name`s must be unique within a single
table (cross-table collisions are allowed — enrichment columns are surfaced qualified by their table
name; see D4). Column types reuse the existing `QueryFieldType` enum; ADAS owns the mapping to
ClickHouse DDL (`STRING→String`, `LONG→Int64`, `INTEGER→Int32`, `TIMESTAMP→DateTime64`,
`ARRAY→Array(<element_type>)`). `ARRAY` requires an `element_type`. Created columns are
non-nullable (ClickHouse best practice; enrichment-miss nulls come from the `LEFT JOIN`, not column
nullability).

### D6 — Delete semantics

`DELETE /v1/tables/{name}` removes the catalog rows and issues `DROP TABLE` in ClickHouse (writer
credential). Deleting a `source` that still has enrichments referencing it is rejected with 409 —
enrichments must be deleted first. **Rationale.** No surprise cascade drops of ClickHouse data; the
blast radius of a delete is exactly one table.

### D7 — Migration: replace `metadata` outright

`db/migration/postgres/V1__create_metadata.sql`, the entire `/v1/metadata` stack, and the
`metadata-store` capability spec are deleted; a new `db/migration/postgres/V1__create_table_catalog.sql`
(the Postgres location is `db/migration/postgres`; ClickHouse migrations live under
`db/migration/clickhouse`) creates the three catalog tables and backfills `dial_usage_log` (one
`source` row, its 20 `column_mapping` rows, `ordering_key` from the existing DDL, `status =
MATERIALIZED`). This assumes the `metadata` migration only ran on disposable databases (confirmed —
it is test scaffolding), so editing the applied V1 is safe. If it had run on a protected
environment, Flyway would forbid editing V1 and a V2 `DROP`+create would be required.

## Risks / Trade-offs

- **Postgres↔ClickHouse consistency** → `status` column + re-PUT-on-`FAILED` makes drift explicit
  and recoverable; no distributed transaction attempted.
- **Runtime DDL widens the management path's blast radius** → uses the already-privileged writer
  credential only; read path stays read-only; deletes are single-table and blocked when
  enrichments depend on the source. `project.md` updated to reflect the new posture.
- **Join correctness** → constrained to single-key, `LEFT`, 0..1 cardinality so joins cannot
  inflate rows or corrupt aggregates; multi-row enrichment is explicitly out of scope.
- **Name collisions in the flat schema** → enrichment columns are exposed qualified by their table
  name (`<table>.<column>`), so cross-table collisions are impossible by construction (only within-table
  uniqueness is validated); FK columns are hidden so they can't collide with the source key.
- **Editing applied migration V1** → safe only because `metadata` is throwaway scaffolding; noted
  in D7 with the V2 fallback if that assumption ever changes.
- **`aggregation` accepted but inert** → a table created as `aggregation` has no defined behavior
  yet; validation should make clear it is not queryable/materialized specially, to avoid operators
  assuming rollup semantics that don't exist.

## Second-wave decisions (D8–D13)

This wave aligns the join model with the upstream table design
while keeping ADAS's runtime-created **source** tables (the upstream keeps
sources migration-managed, so it has no equivalent). It supersedes the parts of D1–D5 marked above.

### D8 — Grain-based enrichment join replaces the free `foreign_key`

An enrichment declares its relationship to its source as a **grain**, not a free key pair:

```
enrichment_grain                 -- replaces enrichment_join
  table_name   PK FK→table_definition.name (the enrichment)
  grain_key    column on BOTH the enrichment and the source (same name, the join key)
  cardinality  enum — v1: ZERO_OR_ONE (1:0..1).  MANY (1:N) reserved → post-v1
```

The query builder emits `source LEFT JOIN enrichment ON source.grain_key = enrichment.grain_key`.
**Generalized whitelist (the key adaptation):** the upstream
restricts grain keys to a hardcoded DIAL list (`event_id/response_id/trace_id/chat_id/user_hash/
deployment/project_id`) because its sources are fixed/external. ADAS owns the source definition, so
the rule generalizes to **`grain_key` MUST be a declared column of the source** — any column, not
only an `ordering_key` column. **Rationale.** The grain key is validated only as a real source
column (its type seeds the enrichment's hidden grain column); the choice of *which* column is left to
the operator. A grain key that is a prefix of the source's `ORDER BY` is index-seekable; a grain key
outside the `ordering_key` forces a full-scan hash join on the source side — accepted deliberately as
the operator's trade-off for flexibility (e.g. joining on a dimension the source is not sorted by).
The enrichment side stays index-seekable regardless, via its own `ReplacingMergeTree … ORDER BY
(grain_key)`. Alternatives considered and rejected: porting the hardcoded DIAL whitelist (wrong for
non-DIAL sources the API now lets operators create); and the earlier stricter rule "`grain_key` MUST
be in the source's `ordering_key`" (rejected — it blocks legitimate joins on non-sort-key columns and
the index-seek benefit is a performance concern, not a correctness one).

### D9 — Enrichment storage = `ReplacingMergeTree`; keyed-lookup join

Enrichment tables are `ReplacingMergeTree(_updated_at)` with `ORDER BY (grain_key)`. This makes
"one row per grain key, latest write wins" **physically true**, so the `LEFT JOIN` cannot inflate
source rows — the 0..1 cardinality is enforced, not merely asserted (the latent double-count risk in
the first wave).

The join is a single **keyed-lookup**: `ON (grain_key)` alone, current-value semantics (the one row
per key applies to all matching source rows regardless of date).

> **Superseded (post-archive):** an earlier two-shape design also emitted a **partition-aligned**
> variant — `ON (partition_col, grain_key)` when the enrichment co-stored the source's partition
> column — for partition pruning. It was removed in review: alignment was *inferred* from an
> incidental column-name match rather than declared, and because enrichment columns default to
> nullable under a `LEFT JOIN`, a non-aligned or null value silently dropped every enrichment column
> to NULL (silently wrong results). Partition pruning may return later as a *declared* property of
> the grain.

Sources keep `MergeTree` with their declared `ordering_key` (operators own a source's dedup/sort
discipline; forcing `ReplacingMergeTree` would change semantics and `dial_usage_log` is plain
`MergeTree`). **System columns** (reserved `_`-prefix, platform-stamped, not user-declarable):
`_updated_at` on every enrichment (the `ReplacingMergeTree` version). `_writer` from the upstream is
**deferred** — it records the writing identity, and ADAS has no auth yet, so there is nothing to
stamp; revisit when auth lands. The upstream's mandatory `event_date` is **not** a forced column;
partition pruning is opt-in per source via D11.

### D10 — Create-time materialization + `PATCH` schema evolution (supersedes D2)

`POST /v1/tables` creates the ClickHouse table immediately; the `DECLARED` state and the separate
PUT-to-materialize step are removed. Because ClickHouse cannot redefine `ORDER BY`/`PARTITION BY`
after creation, the two kinds create differently:

```
POST source:      {name, type:source, columns[], ordering_key[], partition_by?, description?}
                  → CREATE TABLE … MergeTree ORDER BY (ordering_key) [PARTITION BY …]   (immediate)
POST enrichment:  {name, type:enrichment, parent, grain_key, cardinality, description?}
                  → CREATE TABLE … ReplacingMergeTree(_updated_at) ORDER BY (grain_key)
                    (system-column-only shell; user columns added via PATCH)
PATCH /v1/tables/{name}/schema  {add[], drop[], rename[]}
                  → immediate ClickHouse ALTER (ADD/DROP/RENAME COLUMN); type-change excluded
```

A source therefore declares its columns up front (its `ORDER BY` is over user columns); an
enrichment is a shell whose `ORDER BY` is the system grain key, with user columns added later.
Status collapses to `ACTIVE` (created) / `FAILED` (ClickHouse create failed → retriable). **Schema
modification is breaking and immediate** (drop/rename take effect at once, no grace shim — matching
the upstream design); system columns (`_`-prefix) and the grain key cannot be dropped or renamed; a
`rename` is `name`-only and does not alter type. **Rationale.** Matches the upstream schema-evolution flow
and lets enrichments evolve without a delete+recreate; the asymmetry is forced by ClickHouse DDL, not
chosen. Alternative — keep PUT-then-unfreeze — rejected: leaves the dead `DECLARED` state and a
redundant first-PUT special case.

### D11 — Source partitioning

A source may declare `partition_by` = `{column, granularity}` (e.g. `{request_time, MONTH}` →
`PARTITION BY toYYYYMM(request_time)`); omitted → no partitioning (today's `dial_usage_log`
behavior). The partition column drives the source's `PARTITION BY` (and TTL/retention later). Kept
optional and source-only.

### D12 — Identifiers, nullability, and the type vocabulary (amends D5)

- **Identifiers:** user-declared table and column names must match `^[a-z][a-z0-9_]*$` (snake_case),
  ≤ 64 chars; the `_`-prefix is **reserved** for system columns and rejected on API input. Applies to
  API input only — backfilled/system rows (`_ingested_at`, `_source_file` on `dial_usage_log`) are
  exempt, and are exactly the system columns the reservation protects.
- **Nullability:** column declarations gain `nullable` → `Nullable(T)` in DDL when true. Enrichment
  user columns default `nullable=true` (data arrives late); source columns default non-nullable.
- **`array` postponed:** `array`/`element_type` are **no longer user-declarable** (the upstream design leaves it
  out of the v1 vocabulary). The validator rejects `type=array` on create/`PATCH`. It remains valid only
  for the system-backfilled `dial_usage_log` (`execution_path`), which is inserted via migration and
  bypasses the API validator. `object`→`JSON` stays (a single opaque unit).

### D13 — Catalog limits (lightweight guardrails)

Config-driven caps under `tables.limits.*` (env-overridable per `project.md` conventions),
validation-only — not metered quotas:

- `max-columns-per-table` (default 256) — enforced at `POST` (source columns) and `PATCH add`.
- `max-tables` (default 1000) — enforced at `POST`.
- `max-write-batch-rows` (default 10000) — **specified but inert**: ADAS has no generic per-table
  write API yet (only the DIAL `data-sync`/backfill path), so there is no endpoint to enforce it on.
  Recorded now so the constant exists when a write API lands.

**Rationale.** Stops runaway mistakes (a 10k-column table, unbounded table creation) cheaply; matches
the upstream design's "lightweight validation caps now, metered per-tenant quotas → v1.x" posture. Single-
tenant today, so per-tenant metering is out of scope.
