# Add per-table tag ordering

## Why

Schema discovery consumers group columns by their `tag`, but the service gives them no way to know
in what order the tag groups should be displayed — so UIs render tag sections in arbitrary order and
the result looks messy. Admins need an optional, per-table ordering of tags that survives everyday
metadata churn (retagging a column, adding a column with a brand-new tag, dropping columns).

## What Changes

- New optional per-table ordered list of tags (`tag_order`), stored as a nullable `text[]` array
  column on the `table_definition` catalog row (`NULL` = not set). Ordering is **optional**: a
  table with no ordering set behaves exactly as today.
- New top-level `tag_order` member on `PATCH /v1/tables/{name}/schema`, following the endpoint's
  merge-patch convention: absent/`null` leaves the ordering unchanged, an empty array (`[]`) clears
  it, and a non-empty array replaces it.
- `POST /v1/tables` accepts an optional `tag_order` for the new table.
- `GET /v1/tables/{name}` returns the table's `tag_order` (`null` when not set) and, when an
  ordering is set, returns its `columns` array sorted by the same tag-group rules (ordinal order
  preserved when not set). The two GET surfaces order columns consistently.
- `GET /v1/queries/entities/schema/{name}` returns a schema-level `tag_order` (`null` when not set)
  and, when an ordering is set, returns the `fields` array **already sorted**: tag groups in
  `tag_order` order first, then groups for unlisted tags (alphabetical by tag), untagged fields
  last, and fields within a group sorted alphabetically by flat name. When no ordering is set the
  field order is unchanged from today (column ordinal order).
- The ordering is a **display hint over tag names**, deliberately decoupled from which tags
  currently exist on columns:
  - Entries may reference tags not (yet) present on any column — so an admin can set the order
    before retagging, and the order survives a column being temporarily retagged away.
  - Tags present on columns but missing from the ordering are not an error — the server sorts
    their groups after the ordered ones (alphabetically by tag name).
  - Column `update` (retag etc.) / `add` / `drop` / `rename` operations never mutate the stored
    ordering.
- Flyway migration `V10` adds the column and seeds a sensible taxonomy order for the seeded
  system tables.
- Validation: entries are trimmed, non-blank, ≤64 chars (same limit as `tag`), unique within the
  list, list capped at 100 entries; violations are 422 `TableValidationException` errors.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `table-management`: new top-level `tag_order` merge-patch member on `PATCH /v1/tables/{name}/schema`,
  optional `tag_order` on table creation, `tag_order` in the single-table GET response, plus its
  validation rules and interaction with other schema operations.
- `query-schema-discovery`: schema response gains a top-level `tag_order` array (`null` when
  unset) and returns `fields` pre-sorted by tag group when an ordering is set.

## Impact

- **DB**: new nullable `tag_order text[]` column on `table_definition`, migration
  `V10__add_table_tag_order.sql` with seeds for the system tables.
- **Code**: `TableDefinitionEntity` (new array-mapped field), `PatchInput`, `TableSchemaService`,
  `CatalogSchemaWriter`, `TableColumnRules`, `TableCatalogService`/`TableView`,
  `CatalogQueryService`, web DTOs (`PatchSchemaRequest`, `CreateTableRequest`, `TableDto`,
  `QueryEntitySchemaDto`) and `TableMapper`.
- **API**: additive only — no breaking changes; existing clients see one new nullable field in two
  GET responses.
- **Docs/specs**: `openspec/specs/table-management/spec.md`,
  `openspec/specs/query-schema-discovery/spec.md`, `docs/column-tags.md`.
