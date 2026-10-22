## Why

The catalog seeds two tables — `dial_usage_log` and `rate_analytics` — that the `data-sync` ingest
job continuously populates by writing straight to ClickHouse (bypassing the API). Today the
`/v1/tables` management surface treats them like any other definition: a `FULL_ADMIN` can rename or
drop their columns, change their description, delete them, or write rows through `POST /{name}/rows`.
Any such API modification corrupts or destroys the tables the ingest pipeline depends on. These
tables are owned by ADAS itself and must be protected from modification via the API, while remaining
fully readable and queryable.

## What Changes

- Add a table-level `system` flag to the catalog (`table_definition`), marking a definition as
  owned/managed by ADAS itself. It is server-controlled catalog metadata: seeded via migration,
  surfaced read-only on the read API, and **never** settable, clearable, or otherwise editable
  through any API request.
- Seed `system = true` for the two ingest-owned tables, `dial_usage_log` and `rate_analytics`. Every
  other definition (existing and future) is `system = false`.
- **BREAKING** (for `system` tables only): the mutating `/v1/tables` endpoints reject a `system`
  table — `PUT /{name}` (description), `PATCH /{name}/schema`, `DELETE /{name}`, and
  `POST /{name}/rows` all fail with a stable `ErrorView` error. Non-`system` tables are unaffected.
- `GET /{name}`, `GET /` (list), and the query/discovery path are unchanged: a `system` table is
  fully readable, listable, and queryable, and its definition/columns carry the `system` flag so
  operators can see the protection.
- `POST /v1/tables` (create) never accepts a `system` value; newly created tables are always
  `system = false`.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `table-management`: adds the `system` flag to the definition catalog and its read surface; makes
  the update, schema-PATCH, delete, and row-write requirements reject a `system` table; and forbids
  `system` from being set via create/update.

## Impact

- **Spec**: `openspec/specs/table-management/` — new "system table" requirement plus deltas to the
  catalog, read, update, schema-PATCH, row-write, and delete requirements.
- **Migration**: new `src/main/resources/db/migration/postgres/V4__add_system_table_flag.sql`
  (`ALTER TABLE table_definition ADD COLUMN system BOOLEAN NOT NULL DEFAULT FALSE`, then set it
  `TRUE` for `dial_usage_log` and `rate_analytics`). Mirrors the V3 `sensitive`-column precedent.
- **Entity/DTO**: `TableDefinitionEntity`, `TableDto`, `TableMapper` gain the `system` field
  (response-only; absent from `CreateTableRequest`/`UpdateTableRequest`).
- **Service guards**: `TableCatalogService.updateDescription`/`delete`, `TableSchemaService.patchSchema`,
  and `TableDataService.write` reject a `system` table via a new exception mapped in
  `DefaultExceptionHandler` to a stable error code.
- **Docs**: note the `system` flag and the protected two tables where table management is described.
- No new dependencies. ClickHouse ingest is unaffected (it never goes through the API).
