# Design — modify-table-ttl

## Context

ClickHouse table TTL (`TTL <expr> + INTERVAL n <unit>`) is the native row-retention mechanism.
Today it exists only as hardcoded clauses in the seeded ClickHouse migrations
(`db/migration/clickhouse/V1__create_dial_usage_log.sql:75`, `V2__create_rate_analytics.sql:41` —
`TTL toDateTime(request_time) + INTERVAL 1 YEAR`). Tables created through `/v1/tables` have no TTL
and no way to get one; the catalog (`table_definition` in PostgreSQL) has no TTL model, and
`TableDdlRenderer` renders no TTL clause.

The table-management vertical this change extends:

- `TableController` (`web/controller/table/`) → services in `service/table/`
  (`TableCatalogService`, `TableSchemaCreationService`, `TableSchemaService`,
  `CatalogSchemaWriter`), DDL via `TableDdlRenderer` + `ClickHouseDdlExecutor` (writer credential).
- `partition_by` is the closest existing analogue: nested request DTO
  (`PartitionByRequest(column, granularity)`), enum `PartitionGranularity` (`@JsonValue`
  lowercase), flattened into `DefineSchemaInput`, persisted as two nullable columns on
  `table_definition` (with an all-or-neither CHECK), rebuilt into `TableDdlRenderer.CreateSpec`.
- `PATCH /v1/tables/{name}/schema` today issues **one `ALTER` per operation** (hence the spec's
  "mid-sequence failure MAY leave earlier operations applied" caveat).

**Empirical findings (probed live on the pinned `clickhouse/clickhouse-server:25.3` image,
version 25.3.14.14) that shaped this design:**

1. Column operations and TTL commands combine freely in a single comma-separated `ALTER`
   statement (`ADD`/`DROP`/`RENAME COLUMN` mixed with `MODIFY TTL`/`REMOVE TTL` — all tested).
2. A multi-command `ALTER` validates **all-or-nothing**: with one invalid command, no other
   command of the statement is applied.
3. `TTL <DateTime64 col> + INTERVAL ...` is rejected (`BAD_TTL_EXPRESSION`); `toDateTime(<col>)`
   works — confirming the seeded migrations' wrap is mandatory for `timestamp` columns.
4. A TTL expression over a `Nullable` column is rejected (`BAD_TTL_EXPRESSION`).
5. `DROP COLUMN` of the TTL-referenced column is rejected by ClickHouse (`UNKNOWN_IDENTIFIER`).
6. `RENAME COLUMN` of the TTL-referenced column succeeds and ClickHouse **rewrites the TTL
   expression** to the new name.

## Goals / Non-Goals

**Goals:**

- Declare a TTL at materialization time (`POST /v1/tables/{name}/schema`, rendered into
  `CREATE TABLE`).
- Set, replace, and remove the TTL of a live (`ACTIVE`) table through the existing schema-PATCH
  endpoint, atomically with any column operations in the same request.
- Persist the TTL in the catalog and expose it on the read surface (`GET /v1/tables/{name}`).
- Keep the surface injection-safe: structured `{column, interval, unit}` only.

**Non-Goals:**

- Changing the seeded system tables' retention (stays migration-managed; system tables keep
  rejecting all API mutation).
- Column-level TTL, `TTL ... TO DISK/VOLUME/DELETE WHERE`, or multiple TTL expressions.
- Forcing immediate expiry (`ALTER TABLE ... MATERIALIZE TTL` / `OPTIMIZE`); expiry stays lazy.
- Any change to the query path, discovery, data-sync, or authorization model.

## Decisions

### 1. TTL rides the schema endpoints — a `ttl` member on PATCH, not a new sub-resource

A dedicated `PUT`/`DELETE /v1/tables/{name}/ttl` sub-resource was designed first and rejected
after the empirical investigation (and explicit user direction): TTL **is** physical schema, the
PATCH endpoint is the live-schema-evolution surface, and findings (1)+(2) mean a TTL change can
share one atomic-at-validation `ALTER` statement with column operations — something a separate
endpoint can never offer. No new routes; authorization and system-table protection come for free
from the existing endpoint contracts.

Merge-patch semantics of the `ttl` member (mirroring the codebase's `tag_order` convention, where
`[]` clears): **absent/null** → unchanged; **full object** `{column, interval, unit}` → set or
replace (`MODIFY TTL`); **empty object `{}`** → remove (`REMOVE TTL`). A partial object (some but
not all fields) is a 422. Removing when no TTL is stored is an idempotent no-op — no `REMOVE TTL`
command is emitted (its behavior on a TTL-less table is untested/undocumented, so we don't emit
it). Jackson binds `{}` to a `TtlRequest` with all-null members, which is distinguishable from an
absent member — no custom deserialization needed.

The PATCH contract keeps rejecting the *frozen* physical members (`ordering_key`, `partition_by`,
`grain_key`, `cardinality`) and `tag_order` with 422; `ttl` becomes the endpoint's first mutable
physical member, and the spec text changes from "physical members are rejected" to "the frozen
physical keys are rejected".

### 2. One PATCH = one multi-command `ALTER` (all-or-nothing)

All physical commands of a PATCH are rendered into a **single** comma-separated `ALTER TABLE`
statement in the spec's canonical order — `DROP COLUMN ...` → `RENAME COLUMN ...` →
`ADD COLUMN ...` → `MODIFY TTL ...`/`REMOVE TTL` — and executed as one
`ClickHouseDdlExecutor.execute` call. Finding (2) makes this all-or-nothing at ClickHouse
validation, which **upgrades the existing endpoint's semantics**: the spec's per-operation
"mid-sequence failure" caveat is replaced by "the statement is rejected or applied as a whole".
`update` operations remain catalog-only (no DDL), and a PATCH with only `update`s (or only an
idempotent TTL removal) issues no `ALTER` at all. The catalog write happens after the successful
`ALTER` in one Postgres transaction (see Decision 5).

Trade-off: this changes shipped behavior of an existing endpoint. It strictly *reduces* the set of
reachable failure states (no more partially-applied sequences), but the delta spec must modify the
schema-PATCH requirement and the integration tests must cover the all-or-nothing property
(e.g. invalid `ttl` + valid `add` in one request → nothing applied).

### 3. TTL modeled like `partition_by`: structured object + enum, no expressions

Wire shape `ttl: {"column": <declared column>, "interval": <positive int>, "unit": "day"}`, the
same nested-record DTO shape on `DefineSchemaRequest` and `PatchSchemaRequest`. New enum `TtlUnit`
(`HOUR`, `DAY`, `WEEK`, `MONTH`, `YEAR`) mirroring `PartitionGranularity` (`@JsonValue` lowercase
code, `@Enumerated(STRING)` uppercase in Postgres). Free-form expressions are rejected by
construction — the renderer only ever interpolates a validated identifier, a positive integer, and
an enum keyword.

Validation (all → `TableValidationException`, 422): `column` names a declared column of the table
— on PATCH, resolved **after** the request's own drop/rename/add operations, so a just-added or
just-renamed column is referenceable (same rule as `update` entries); the column is
**non-nullable** and of type `date` or `timestamp` (findings 3–4); `interval >= 1` (checked in
`TableTtlRules.requireValid` — the DTO deliberately carries no bean-validation `@Positive`, which
would preempt the 422 with a generic 400). A `unit` value outside the enum is the one exception:
it fails Jackson binding → 400 `bad_request` via the existing malformed-body handler, the same as
any other unbindable enum (e.g. a `partition_by` granularity). `POST /v1/tables`
(create) rejects a supplied `ttl` in the existing `TableMapper.toCreateInput` tripwire.

### 4. Rendering matches the seeded migrations

In `TableDdlRenderer`: `renderCreate` appends `TTL <col> + INTERVAL <n> <UNIT>` when the
`CreateSpec` carries a TTL; the PATCH path renders `MODIFY TTL ...` / `REMOVE TTL` as commands of
the consolidated `ALTER`. A `timestamp` column (materialized as `DateTime64(3)`) is wrapped as
`toDateTime(<col>)` — exactly the seeded style (finding 3); a `date` column is used bare. The
column is rendered by its physical `source_name`.

### 5. Catalog storage: three nullable columns on `table_definition`; DDL first, catalog last

Postgres migration `V17__add_table_ttl.sql`: `ttl_column TEXT`, `ttl_interval INTEGER`,
`ttl_unit TEXT`, plus a CHECK (`ttl_all_or_none`) that all three are set or all three are null —
same pattern as `partition_by_both_or_neither` in `V1__create_table_catalog.sql`. Entity fields on
`TableDefinitionEntity`; exposed as `TableDto.TtlDto` via `TableMapper` (omitted when unset,
`NON_NULL`). The seeded system tables' rows stay NULL — their physical TTL remains
migration-managed and deliberately not mirrored into the catalog (the API can't touch them anyway;
mirroring would create a second source of truth).

The PATCH flow keeps the existing posture: validate everything up front, issue the single `ALTER`,
then persist all catalog changes (column mapping + TTL) in one Postgres transaction. A failed
`ALTER` surfaces as the mapped `ErrorView` (503 `clickhouse_unavailable` / 400 `bad_request`), the
table stays `ACTIVE`, catalog unchanged — and thanks to Decision 2 the physical table is unchanged
too. The DDL+catalog pair is still not atomic across systems (impossible without 2PC); if the
catalog write fails after a successful `ALTER`, retrying the same PATCH reconverges (`MODIFY TTL`
to the same value is a no-op).

### 6. TTL-column interplay with column operations

- A PATCH `drop` of the column the stored TTL references is rejected with 422 ("remove the TTL
  first"), even if the same request also removes or repoints the TTL — conservative, because the
  validity of `DROP <ttl col>` combined with a TTL change *in the same statement* depends on
  ClickHouse's intra-statement validation order (finding 5 shows the bare drop is rejected
  server-side); two sequential PATCHes are unambiguous.
- A PATCH `rename` of that column is allowed: ClickHouse rewrites the TTL expression itself
  (finding 6), and the catalog's stored TTL column is updated in the same transaction that renames
  the column mapping — keeping catalog and physical table in sync.

### 7. System tables stay locked (decided)

System-table retention SHALL NOT become admin-tunable (explicit product decision confirmed with
the user). It would carve the first-ever exception into "a system table rejects every modifying
operation", and the seeded TTLs live in migration files that would silently desync. Since TTL
rides the existing schema endpoints, system tables reject it with 409 with zero new code; the
seeded tables' retention remains migration-managed only.

## Risks / Trade-offs

- [Changed semantics of shipped PATCH endpoint (per-op → single statement)] → strictly fewer
  partial-failure states; delta spec updates the requirement; integration test asserts
  all-or-nothing explicitly.
- [Lazy expiry surprises users] Rows past the TTL remain until ClickHouse merges parts →
  documented in the spec ("SHALL NOT force immediate re-materialization"); no `MATERIALIZE TTL`.
- [`MODIFY TTL` can trigger heavy TTL re-materialization on big tables]
  (`materialize_ttl_after_modify` is on by default server-side) → accepted; the endpoint is
  admin/modify-gated and rare; noted for reviewers rather than tuned per-request.
- [DDL/catalog non-atomicity] `ALTER` succeeds, Postgres write fails → catalog understates the
  physical state until the caller retries the idempotent PATCH → mitigated by doing the catalog
  write last in one small transaction (matches existing trade-off).
- [Multi-command `ALTER` behavior drift across ClickHouse versions] The all-or-nothing property
  and command-mixing rules were verified on 25.3 only → integration tests run against the same
  pinned image and would catch a regression on a future bump.

## Migration Plan

- Additive Postgres migration `V17__add_table_ttl.sql` (nullable columns + CHECK); no backfill, no
  ClickHouse migration. Rollback = revert the code; the columns are inert if unused. (The slot was
  renumbered several times over the change's life as other migrations landed, ending at V17. A local
  database that applied an earlier draft numbering needs a `flyway repair` or a fresh volume.)

## Open Questions

- None. (System-table retention staying non-tunable is decided, see Decision 7.)
