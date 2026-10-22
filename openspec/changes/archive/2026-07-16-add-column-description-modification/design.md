## Context

Column metadata in ADAS is Postgres-backed catalog data (the `column_mapping` table) and never enters
ClickHouse DDL. Each exposed column carries a physical `source_name`, an exposed `name`, a `type`, a
`nullable` flag, a `description` (long-form help text), a `display_name` (short UI label), a `tag`
(semantic grouping), and a `sensitive` flag.

These catalog-only fields fall into two edit patterns:
- `tag`, `display_name`, and `sensitive` are set at create/`add`, preserved across `rename`, **and**
  independently editable via `retag` / `set_display_name` / `set_sensitive` PATCH operations.
- `description` is set only at create/`add` and preserved across `rename`, with **no** dedicated PATCH
  edit operation — the odd one out.

This change moves `description` into the first group by adding a `set_description` PATCH op, so it is
editable after a table exists just like the other metadata fields.

## Goals / Non-Goals

**Goals:**
- Add a `set_description` PATCH op that edits an existing column's description (set or clear), mirroring
  `set_display_name` exactly (catalog-only, no `ALTER`, post-op name resolution, unknown → 422, no
  mutability restriction).
- Cap `description` at 1024 characters and normalize it (trim, blank → absent) consistently at
  create/`add` and on the new op.
- Keep the change non-breaking: `description` optional everywhere, `null` when unset.

**Non-Goals:**
- No DB migration or entity change (`column_mapping.description` already exists).
- No ClickHouse DDL change (descriptions never materialize into the physical table).
- No change to query execution/projection/filtering — description is display-only metadata already
  surfaced by the read paths.
- No uniqueness constraint on descriptions; no auto-derived default.

## Decisions

**Decision: Make `description` editable via a new `set_description` PATCH op (mirror `set_display_name`).**
Rationale: descriptions are tuned iteratively; requiring a destructive drop/recreate to change one is
hostile and inconsistent with `tag`/`display_name`/`sensitive`. `set_description` is catalog-only,
issues no ClickHouse `ALTER`, applies in the `... → set_sensitive → set_description` phase order, and
clears the description when given a null/blank value — exactly like `retag`/`set_display_name`.

**Decision: Cap `description` at 1024 characters, blank-as-absent, validated in `TableColumnRules`.**
`tag` caps at 64 and `display_name` at 128; a description is longer prose (help text), so 1024 gives
generous headroom while still bounding storage/UI. Reuse the existing normalize-and-limit helper
pattern with a `normalizeDescription`/`MAX_DESCRIPTION_LENGTH` sibling of
`normalizeDisplayName`/`MAX_DISPLAY_NAME_LENGTH`.

**Decision: Normalize `description` at create/`add` too, not only on the new op.**
Previously create-time description was stored raw (no trim, no blank → null, no cap), unlike
`display_name`/`tag`. Applying `normalizeDescription` in `CatalogSchemaWriter.columnEntity` makes the
create and PATCH paths behave identically (a blank description clears to `null`, over-length is
rejected). This is a small, deliberate behavior change; existing rows are unaffected (no migration).

**Decision: No delta on the read/discovery specs.**
`description` is already returned by the table-management responses and the query schema-discovery
field DTO (`QuerySchemaFieldDto`), and that behavior is unchanged, so only the `table-management`
"Modify table schema via PATCH" and "Schema validation" requirements change.

## Risks / Trade-offs

- **[New PATCH op increases the operation matrix]** → `set_description` is a near-exact clone of
  `set_display_name`; reuse the same validation/ordering/unknown-column handling and add parallel tests
  so they stay consistent.
- **[Create-time normalization is a behavior change]** → a create/`add` that previously stored a blank
  or whitespace-only description now stores `null`, and an over-1024-char description is now rejected
  (422). Low risk: blank descriptions have no meaning, and no client is known to rely on multi-KB
  descriptions; the cap is a validation constant easily tuned.
- **[Coordination with `rework-uac-role-based-access`]** → that in-flight change also has a
  `table-management` delta but only touches the PATCH access-control sentence; reconcile the two deltas
  on the "Modify table schema via PATCH" requirement at merge time (low conflict risk).

## Migration Plan

None. `column_mapping.description` (TEXT, migration V1) and `ColumnMappingEntity.description` already
exist. The change is additive at the API layer and backward-compatible: older code ignores the new
`set_description` op, and existing rows keep their current description until one is set. Rollback needs
no DB change.

## Open Questions

- None.
