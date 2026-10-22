## Context

Column metadata in ADAS is Postgres-backed catalog data (the `column_mapping` table) and never enters
ClickHouse DDL. Today each exposed column carries a physical `source_name`, an exposed `name`, a
`type`, a `nullable` flag, a `description` (long-form help text), a `tag` (semantic grouping —
identifier/dimension/etc., see `docs/column-tags.md`), and a `sensitive` flag. The field-picker UI
renders columns by their exposed `name` (e.g. `total_money`), which reads poorly. We need a short,
human-friendly `display_name` (e.g. "Total money spend") that flows through both the table-management
write surface and the query schema-discovery read surface.

The catalog-only fields fall into two edit patterns:
- `description` is set only at create/`add` and preserved across `rename`, with **no** dedicated PATCH
  edit operation.
- `tag` and `sensitive` are set at create/`add`, preserved across `rename`, **and** independently
  editable via `retag` / `set_sensitive` PATCH operations.

`display_name` is presentation metadata that operators will want to tune after a table exists, so it
follows the `tag`/`retag` / `sensitive`/`set_sensitive` pattern (editable), not the `description`
pattern.

## Goals / Non-Goals

**Goals:**
- Add an optional catalog-only `display_name` to columns, threaded end-to-end: DB → entity → service →
  request/response DTOs → schema-discovery field DTO.
- Accept `display_name` at create and `PATCH add`; edit it via a new `PATCH set_display_name` op;
  preserve it across `rename`.
- Validate `display_name` length ≤ 128 characters, treating blank as absent (mirroring `tag`'s rule).
- Keep the change non-breaking: `display_name` optional everywhere, `null` when unset.

**Non-Goals:**
- No ClickHouse DDL change (display names never materialize into the physical table).
- No change to query execution, projection, or filtering — `display_name` is display-only metadata.
- No uniqueness constraint on display names; no default/auto-derived display name from `name`.
- No change to query execution semantics from a display name (it is metadata carried alongside results,
  never a filter/group/sort key).

The seeded system tables (`dial_usage_log`, `rate_analytics`) ARE given curated display names in this
change, via the `V9__backfill_column_display_names.sql` backfill (see Migration Plan / Open Questions).

## Decisions

**Decision: Model `display_name` as a nullable `TEXT` column in `column_mapping`, mirroring `description`.**
Alternatives considered: reusing `description` (rejected — different purpose: long help text vs. short
display name) or `tag` (rejected — `tag` is a semantic grouping key the UI buckets on, not a display
name). A dedicated column keeps concerns separate and matches how `sensitive` was added (`V3`).

**Decision: Make `display_name` editable via a new `set_display_name` PATCH operation (mirror `set_sensitive`).**
Rationale: display names are tuned iteratively; requiring a column drop/recreate to change one (the
`description` pattern) is hostile. `set_display_name` is catalog-only, issues no ClickHouse `ALTER`,
applies in the `... → retag → set_display_name → set_sensitive` phase order, and clears the display
name when given a null/blank value — exactly like `retag`.

**Decision: Cap `display_name` at 128 characters, blank-as-absent, validated in `TableColumnRules`.**
`tag` caps at 64; a display sentence like "Total money spend" is short but display names are prose, so
128 gives headroom while still bounding storage/UI. Reuse the existing normalize-and-limit helper
pattern (`normalizeTag`/`MAX_TAG_LENGTH`) with a `normalizeDisplayName`/`MAX_DISPLAY_NAME_LENGTH` sibling.

**Decision: Preserve `display_name` across `rename` in `CatalogSchemaWriter.renameColumn`.**
`renameColumn` already copies `description`/`tag`/`sensitive` onto the renamed mapping; `display_name`
joins that set so a rename never silently drops the display name.

**Decision: Surface `display_name` on the query schema-discovery field DTO (`QuerySchemaFieldDto`).**
This is the DTO the "Search fields" UI consumes; `CatalogQueryService.addColumn` passes
`col.getDisplayName()` through alongside the existing `description`/`tag`/`sensitive`. Sensitive-column
hiding is unchanged (a hidden field carries no display name because it is absent entirely).

## Risks / Trade-offs

- **[New PATCH op surface increases the operation matrix]** → `set_display_name` is a near-exact clone
  of `set_sensitive`/`retag`; reuse the same validation/ordering/unknown-column handling and add
  parallel tests so they stay consistent.
- **[Existing rows have `NULL` display_name after migration]** → acceptable and intended; `null`
  serializes cleanly and the UI falls back to the exposed `name`. No backfill required for correctness.
- **[Forgetting to thread `display_name` through one of the ~11 touch points]** → the delta specs
  enumerate observable behavior (create returns it, set_display_name edits it, rename preserves it,
  discovery carries it) and each maps to a test, so a missed hop fails a test rather than shipping silently.
- **[128-char cap is a guess]** → low risk; it is a validation constant easily tuned later and
  documented in the spec, not a stored-format constraint.

## Migration Plan

1. Add Postgres Flyway migration `V8__add_column_display_name.sql`:
   `ALTER TABLE column_mapping ADD COLUMN display_name TEXT;` (nullable, no default) — models `V3`
   (`add_sensitive_columns`). No ClickHouse migration.
2. Add Postgres Flyway migration `V9__backfill_column_display_names.sql`: data-only `UPDATE`s giving
   every exposed column of the two seeded system tables (`dial_usage_log`, `rate_analytics`) a curated
   display name (mirrors the `V6`/`V7` description/tag backfills). No DDL, no ClickHouse.
3. Deploy is additive and backward-compatible: older code ignores the new column; new code reads
   `NULL` for pre-existing rows (user-created tables keep a null display name until one is set).
4. Rollback: the column is nullable and unread by prior versions, so rolling back the app needs no DB
   change; if the column must be dropped, a follow-up `DROP COLUMN display_name` migration is safe
   because no ClickHouse object depends on it.

## Open Questions

- Should the seeded system tables (`dial_usage_log`, `rate_analytics`) get curated display names via a
  backfill migration (like `V6`/`V7` did for descriptions/tags)? Resolved: added as
  `V9__backfill_column_display_names.sql`.
