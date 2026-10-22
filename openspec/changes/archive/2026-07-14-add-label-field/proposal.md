## Why

Columns are surfaced to analytics consumers by their physical/alias name (e.g. `total_money`),
which is not friendly to read in a UI. The existing `description` (long-form help text) and `tag`
(semantic grouping) fields cannot serve as a concise display name. Consumers need a short,
human-friendly **display name** — e.g. "Total money spend" for `total_money` — so tools can render the
field-picker cleanly.

## What Changes

- Add an optional `display_name` field to column metadata: a short, human-friendly name stored as
  catalog-only metadata (never part of ClickHouse DDL), alongside `description`/`tag`/`sensitive`.
- Accept `display_name` when creating a table and when adding a column (`POST /v1/tables`,
  `PATCH /v1/tables/{name}/schema` `add` op).
- Add a `set_display_name` operation to `PATCH /v1/tables/{name}/schema` to edit a column's display
  name after creation, mirroring the existing `set_sensitive` operation.
- Preserve `display_name` across a column `rename` (mirroring `description`/`tag`/`sensitive`).
- Return `display_name` from table-management responses and from the query schema-discovery endpoint
  (`GET /v1/queries/entities/schema/{name}`), so the field-picker can display it.
- Validate/normalize `display_name` length to **at most 128 characters** (mirroring the `tag` length rule).
- Persist via a new Postgres Flyway migration adding a nullable `display_name` column to `column_mapping`.
- Backfill curated display names for the two seeded system tables (`dial_usage_log`, `rate_analytics`)
  via a second data-only migration, mirroring the `V6`/`V7` description/tag backfills.

No breaking changes: `display_name` is optional everywhere and absent display names serialize as `null`.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `table-management`: column metadata gains an optional `display_name`; create/add accept it, a new
  `set_display_name` PATCH op edits it, `rename` preserves it, and it is validated to ≤128 characters.
- `query-schema-discovery`: schema-discovery field responses carry the column `display_name` alongside
  `description` and `tag`.

## Impact

- **DB migrations**: new `src/main/resources/db/migration/postgres/V8__add_column_display_name.sql`
  (`ALTER TABLE column_mapping ADD COLUMN display_name TEXT`) and
  `V9__backfill_column_display_names.sql` (data-only backfill for the seeded system tables). No
  ClickHouse DDL change.
- **Persistence/service**: `ColumnMappingEntity`, `ColumnInput`, `CatalogSchemaWriter`
  (`columnEntity`, `renameColumn`, new `setDisplayNameColumn`), `TableSchemaService`, `PatchInput`,
  `TableColumnRules` (display-name normalization/limit).
- **Web DTOs**: `CreateTableRequest.ColumnRequest`, `PatchSchemaRequest` (new `SetDisplayNameColumn`),
  `TableDto.ColumnDto`, `TableMapper`, and `QuerySchemaFieldDto`.
- **Query discovery**: `CatalogQueryService.addColumn` passes the display name through.
- **Specs**: `openspec/specs/table-management/spec.md`, `openspec/specs/query-schema-discovery/spec.md`.
- **Tests**: table-management + schema-discovery controller/integration tests and
  `CatalogSchemaWriterTest` assert on these DTOs and will be extended.
- **Docs**: `docs/column-tags.md` neighbor / table-management API docs note the new field (no new
  env config).
