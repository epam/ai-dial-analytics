## Why

Column metadata carries a free-form `description` (long-form help text shown next to a field), but
today a column's `description` can only be set at create time (`POST /v1/tables`) or when a new column
is added (`PATCH .../schema` `add` op). Unlike `tag` (`retag`), `display_name` (`set_display_name`),
and `sensitive` (`set_sensitive`), there is **no operation to edit an existing column's description** —
the only workaround is a destructive drop + re-add of the column, or delete + recreate of the table.
Operators need to tune descriptions iteratively after a table exists.

## What Changes

- Add a `set_description` operation to `PATCH /v1/tables/{name}/schema` to set (or clear, when null or
  blank) an existing column's `description`, mirroring the existing `set_display_name` operation.
  Catalog-only metadata — no ClickHouse `ALTER`.
- Apply `set_description` in the operation order after `set_sensitive`, resolving against post-op
  exposed names (so it MAY target a just-added or just-renamed column) and rejecting an unknown column
  with 422. It carries no mutability restriction (system, grain, and ordering-key columns may be
  described), exactly like the other metadata ops. `description` is already preserved across a `rename`.
- Give `description` a length cap of **1024 characters** (it was previously uncapped) and normalize it
  (trim, blank → absent) **consistently at both create/`add` and `set_description`**, via a new
  `normalizeDescription` helper mirroring `normalizeDisplayName`.

No breaking changes: `description` remains optional everywhere and absent descriptions serialize as
`null`. There is **no DB migration and no entity change** — `column_mapping.description` (TEXT) and
`ColumnMappingEntity.description` already exist and description is already returned by the
table-management and query schema-discovery responses.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `table-management`: `PATCH .../schema` gains a `set_description` op that edits an existing column's
  description; description is normalized and validated to ≤1024 characters at create/`add` and on the
  new op (previously uncapped and un-normalized at create).

## Impact

- **DB migrations**: none (`column_mapping.description TEXT` already exists; no ClickHouse DDL change).
- **Service**: `PatchInput` (new `SetDescription` case), `TableSchemaService` (resolve/validate/apply
  the op), `CatalogSchemaWriter` (new `setDescriptionColumn`; `columnEntity` now normalizes description
  at create/`add`), `TableColumnRules` (`MAX_DESCRIPTION_LENGTH` + `normalizeDescription`).
- **Web DTOs**: `PatchSchemaRequest` (new `SetDescriptionColumn` record + `set_description` field),
  `TableMapper` (`toPatchInput` maps the op).
- **Specs**: `openspec/specs/table-management/spec.md` (the "Modify table schema via PATCH" and
  "Schema validation" requirements).
- **Tests**: `TableManagementIntegrationTest` (set/clear/rename, unknown → 422, over-limit → 422) and
  `CatalogSchemaWriterTest` (`setDescriptionColumn` set/clear + unknown-column rejection).
- **Docs**: `docs/column-tags.md` notes the column `description` field and the `set_description` op (no
  new env config).
