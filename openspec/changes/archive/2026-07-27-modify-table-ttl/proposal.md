# Modify Table TTL

## Why

Tables materialized through `/v1/tables` have no retention policy: rows accumulate in ClickHouse
indefinitely, and the only TTLs in the system are the migration-hardcoded 1-year TTLs on the seeded
system tables (`dial_usage_log`, `rate_analytics`). Operators need to declare a table's row-expiry
(TTL) when defining it and to change or remove it later on a live table — without hand-issuing
ClickHouse DDL out of band (which would also desync the catalog from the physical table).

## What Changes

- `POST /v1/tables/{name}/schema` (define + materialize) accepts an optional structured `ttl`
  declaration — `{column, interval, unit}` — rendered into the `CREATE TABLE` as
  `TTL <column> + INTERVAL <interval> <UNIT>`.
- `PATCH /v1/tables/{name}/schema` (live schema evolution) gains an optional `ttl` member with
  merge-patch semantics: **absent/null** leaves the TTL unchanged, a **full object** sets or
  replaces it (`MODIFY TTL`), an **empty object `{}`** removes it (`REMOVE TTL`; a no-op without
  DDL when no TTL is set). TTL is part of the schema, so it rides the schema endpoint — no new
  routes.
- All physical commands of one PATCH (`drop` → `rename` → `add` → ttl) are emitted as a **single
  multi-command ClickHouse `ALTER`** statement, which validates all-or-nothing (verified
  empirically on the pinned ClickHouse 25.3). This upgrades the existing PATCH contract: the
  previous "a mid-sequence failure MAY leave earlier operations applied" caveat is replaced by
  whole-statement semantics.
- The TTL is catalog metadata too: persisted on the `table_definition` row (new nullable columns
  via Postgres migration) and returned by `GET /v1/tables/{name}` (absent when unset, per
  `NON_NULL` serialization).
- Validation (422 on violation): the TTL column must be a declared **non-nullable** column of the
  table with type `date` or `timestamp` (resolved after the request's own column operations);
  `interval` is a positive integer; `unit` ∈ {`hour`, `day`, `week`, `month`, `year`}. Free-form
  TTL expressions are NOT accepted (injection-safe, structured only). A `timestamp` column renders
  wrapped as `toDateTime(<col>)` (ClickHouse rejects bare `DateTime64` and nullable TTL
  expressions — verified).
- Interplay rules: a PATCH `drop` of the TTL-referenced column is rejected with 422 (remove the
  TTL first); a `rename` of it succeeds — ClickHouse rewrites the TTL expression and the catalog
  TTL column follows.
- `POST /v1/tables` (create) rejects a supplied `ttl` with 422 like every other physical-schema
  field (the existing tripwire convention).
- Authorization is unchanged: the schema endpoints stay `FULL_ADMIN`-or-`modify`-grant, and
  `system` tables keep rejecting them with 409 (the seeded tables' retention stays
  migration-managed). No new endpoints, so no authorization surface changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-management`: the define-and-materialize schema body gains the optional `ttl` member; the
  schema-PATCH requirement gains the `ttl` member semantics and the single-statement `ALTER`
  consolidation; a new requirement defines the TTL model (shape, validation, rendering, catalog
  persistence, read-surface exposure).

## Impact

- **API**: extended request bodies of `POST`/`PATCH /v1/tables/{name}/schema`; extended read
  responses (`ttl` object on `GET /v1/tables/{name}`); no new endpoints.
- **Behavior**: schema PATCH becomes a single multi-command `ALTER` (all-or-nothing at ClickHouse
  validation) instead of one `ALTER` per operation.
- **Code**: table DTOs (`web/dto/table`), `TableMapper`, `TableSchemaService` /
  `TableSchemaCreationService`, `CatalogSchemaWriter`, `TableDdlRenderer`,
  `TableDefinitionEntity`.
- **Storage**: one PostgreSQL migration adding nullable TTL columns to `table_definition`;
  ClickHouse DDL now includes `TTL` clauses (`CREATE TABLE`, `ALTER ... MODIFY/REMOVE TTL`).
- **Docs/specs**: `table-management` delta spec; `openspec/project.md` summary; no new env vars
  (no `docs/configuration.md` change).
- **Not affected**: query path (`/v1/queries`), data-sync ingest, seeded system tables' retention,
  authorization model.
