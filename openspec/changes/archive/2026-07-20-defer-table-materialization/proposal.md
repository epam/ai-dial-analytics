> **Update (post-archive):** the two-step `PUT /v1/tables/{name}/schema` (draft) +
> `POST /v1/tables/{name}/materialize` was subsequently combined into a single atomic
> `POST /v1/tables/{name}/schema` that defines the complete physical schema and materializes the table
> in one call (no durable draft). This document describes the design as originally archived; the
> shipped behavior is the source of truth in `openspec/specs/table-management/spec.md`.

## Why

Today `POST /v1/tables` couples three concerns into one call: registering the table's identity,
declaring its full physical schema (columns **and** the ClickHouse keys), and synchronously running
`CREATE TABLE`. That leaves columns defined in *two* places (create and `PATCH .../schema`) and the
structural keys (`ordering_key`/`grain_key`/`partition_by`/`cardinality`) tangled into the create/table
API even though they are physical-schema attributes, not identity. The boundaries are unclear and
materialization happens as an invisible side effect.

This change draws explicit boundaries around a table's lifecycle — **identity → draft schema →
materialize → live evolution** — so each concern lives in exactly one place and materialization is a
deliberate, named action.

## What Changes

- **BREAKING**: `POST /v1/tables` becomes **identity-only** and issues no ClickHouse DDL. Its body is
  `{name, type, source_table?, description?, tag_order?}` — a source needs only `{name, type}`, an
  enrichment `{name, type, source_table}` (`source_table` required and immutable). It SHALL NOT accept
  `columns` or any physical key; it records the definition as `status=PENDING` and returns 201.
- **BREAKING (new)**: `PUT /v1/tables/{name}/schema` defines/replaces the **draft physical schema** —
  columns **plus** the physical keys (`ordering_key`/`partition_by` for a source; `grain_key`/
  `cardinality` for an enrichment) — as one document. It is allowed only while the table is not yet
  materialized (`PENDING`/`FAILED`), is catalog-only (no ClickHouse DDL), and is freely repeatable.
  Called on an `ACTIVE` table it is rejected with 409.
- **BREAKING (new)**: `POST /v1/tables/{name}/materialize` commits the draft: it validates the draft
  schema, issues a single ClickHouse `CREATE TABLE` (writer credential), and flips the definition to
  `ACTIVE`. On an already-`ACTIVE` table it returns 409; on an incomplete/invalid draft, 422; on a
  ClickHouse failure it issues a best-effort `DROP TABLE IF EXISTS`, records `FAILED`, and returns an
  `ErrorView` (retryable).
- **BREAKING**: `PATCH /v1/tables/{name}/schema` becomes **live-only**: it applies `add`/`drop`/`rename`
  as immediate ClickHouse `ALTER`s (plus per-column metadata `update`) on an `ACTIVE` table. The
  physical keys are frozen — a key member is rejected with 422 — and calling it on a `PENDING`/`FAILED`
  table returns 409 ("materialize the table first").
- The physical keys move **out** of the create/table API and live only in the draft schema
  (`PUT .../schema`), frozen after materialization. `tag_order` moves the other way — it is catalog
  metadata (never touches ClickHouse), set via `POST /v1/tables` and `PUT /v1/tables/{name}` alongside
  `description`, and removed from the schema endpoints.
- `PENDING` becomes a durable "defined, not materialized" state. Row writes and the query/discovery
  path continue to require `ACTIVE`, so a never-materialized table is neither writable nor queryable.

## Capabilities

### New Capabilities

<!-- None: this change modifies the existing table-management capability. -->

### Modified Capabilities

- `table-management`: create becomes identity-only; a new draft-schema resource
  (`PUT /v1/tables/{name}/schema`) owns columns + physical keys before materialization; a new
  `POST /v1/tables/{name}/materialize` performs the `CREATE TABLE`; `PATCH /v1/tables/{name}/schema`
  becomes live-only (ALTERs, keys frozen); `tag_order` becomes table catalog metadata; the
  `PENDING`/`ACTIVE`/`FAILED` lifecycle, validation, and role-based authorization are updated.

## Impact

- **Code**: `TableController` (new `PUT .../schema` and `POST .../materialize`; `POST` slimmed; `PATCH`
  live-only); services split by concern — `TableCatalogService` (identity + metadata), a draft-schema
  service (replace draft columns+keys), a materialize service (validate + `CREATE TABLE`),
  `TableSchemaService` (live ALTERs); `CatalogSchemaWriter`/`TableDdlRenderer` (build `CREATE` from the
  stored draft); DTOs (`CreateTableRequest` slimmed to identity; new `DraftSchemaRequest`;
  `PatchSchemaRequest` loses key/tag_order members; `UpdateTableRequest` gains `tag_order`);
  `TableMapper`. `TableDataService` and the query/discovery path are unchanged (already gate on
  `ACTIVE`).
- **API**: `POST /v1/tables` no longer accepts `columns`/keys and returns `status=PENDING`; new
  `PUT .../schema` and `POST .../materialize`; `PATCH .../schema` rejects keys and 409s while pending;
  `PUT /v1/tables/{name}` accepts `tag_order`. `ErrorView` contract throughout.
- **Data model**: `ordering_key`/`partition_by`/`source_table` and the `enrichment_grain` row may be
  absent on a `PENDING` definition and are populated by the draft-schema resource. No Flyway migration
  required (already nullable/optional).
- **Specs/docs**: `openspec/specs/table-management/spec.md`; `docs/` table-management wording.
- **Tests**: existing tests that create a fully-specified table in one call and expect `ACTIVE` must
  split into create → `PUT .../schema` → `materialize`; `PATCH` tests move to the live-only contract.
