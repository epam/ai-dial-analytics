## Context

`PATCH /v1/tables/{name}/schema` currently exposes four catalog-metadata operations — `retag`,
`set_display_name`, `set_sensitive`, `set_description` — each modelled as its own list of
single-purpose records. They share an identical shape ("name + one field to set"), an identical
resolution rule (target the post-add/drop/rename exposed name), and an identical apply path
(catalog-only, no `ALTER`). Every new column attribute has required duplicating the full stack
(request record → `PatchInput` record → `Resolved*` record → validation loop → apply loop →
`CatalogSchemaWriter` method), and the pattern already produced a real gap: `description` was
set-at-create but not editable until it was retrofitted as a fourth operation.

The structural trio (`add`/`drop`/`rename`) is different in kind — it maps to irreversible SQL
`ALTER`s with mutability restrictions — and is well-shaped. This change touches only the metadata
operations.

## Goals / Non-Goals

**Goals:**
- Collapse the four metadata setters into one `update` list; make each future attribute a single
  optional field rather than a new operation.
- Preserve all existing behavior: validation, length caps, no-`ALTER` nature, post-op name
  resolution, preservation across `rename`, no mutability restriction.
- Add no new runtime dependency.

**Non-Goals:**
- Changing `add`/`drop`/`rename` shape or semantics.
- Splitting metadata onto a separate endpoint (`/columns`) — kept on `.../schema`.
- Backward compatibility for the removed `set_X` fields (hard replace).
- Making `description` (or any attribute) editable at create differently than today.

## Decisions

### Single `update` list with per-field merge-patch semantics

One `update` entry carries `{name, tag?, display_name?, sensitive?, description?}`. Per field:
absent/null → leave; blank string → clear; non-blank → set; `sensitive` present → set boolean.

*Why over alternatives:* keeping four lists is the status quo we are removing. A JSON Patch
(`[{op,path,value}]`) array is more machine-general but far more verbose for the common
"set two fields on a column" case and foreign to the rest of this API. A merge-patch object
matches how callers think about column metadata and mirrors the existing `add` record.

### Dependency-free absent-vs-null handling (blank = clear)

The standard JSON Merge Patch helper (`org.openapitools:jackson-databind-nullable`, `JsonNullable`)
is built for Jackson 2 (`com.fasterxml.jackson.databind`). This app's primary mapper is **Jackson 3**
(`tools.jackson.*`, `JsonMapperConfiguration`), so `JsonNullable` will not bind. Rather than add a
mapper shim, we lean on behavior already in the code: `TableColumnRules.normalize{Tag,DisplayName,
Description}` already treat a blank string as "absent → null". So the API contract becomes:
**Java-null request field = not present = leave unchanged; blank `""` = clear; non-blank = set.** No
new dependency, no Jackson-3 module wiring, and it reuses the existing normalization verbatim.

*Trade-off vs RFC 7386:* RFC 7386 uses `null` to delete; here `null` means "leave" and `""` means
"clear". This is a deliberate, documented divergence chosen to stay dependency-free on Jackson 3 and
consistent with the existing blank-as-absent rule.

*Alternative considered:* deserialize each `update` entry as a `Map`/`JsonNode` and test key
presence to honor RFC 7386 `null`-deletes. Rejected: it loses Jakarta bean validation on the entry
and adds parsing code for a semantic the blank convention already covers.

### A small `Change<T>` wrapper carries tri-state to the writer

After normalization both "leave" and "clear" would be Java-null, collapsing the distinction. A tiny
`record Change<T>(boolean present, T value)` (with `leave()` / `set(v)` factories) preserves it from
the service down to the writer. It lives in the `service.table` package; `Change` is built in the
mapper/service from the nullable DTO fields, so Jackson types never leak past the DTO layer.

### One consolidated writer method

`CatalogSchemaWriter`'s four `setXColumn` methods collapse into one
`updateColumnMetadata(tableName, exposedName, ResolvedMetadataUpdate change)` that loads the column
once, conditionally applies each present field, and saves once — replacing the up-to-4× load+save a
naive per-field port would do. `renameColumn` already copies all four metadata fields; it is
re-verified, not changed.

## Risks / Trade-offs

- **[Breaking API change]** → Any caller of the old `set_X` fields breaks. Mitigated by the surface
  being internal and spec-driven with no external consumers; `set_description` itself landed only in
  the immediately preceding change. Spec, docs, and the demo page are updated in this change.
- **[Divergence from RFC 7386 null-semantics]** → A caller expecting `null`-deletes would instead
  leave the field unchanged. Mitigated by documenting absent/blank/set explicitly in the spec and
  OpenAPI `@Schema` descriptions; blank-clear already matches create-time behavior.
- **[Silent field drop if `@JsonProperty` names are wrong]** → `display_name` must map to the
  `displayName` field. Mitigated by an integration test asserting a `display_name` update round-trips.

## Migration Plan

Single deploy; no data migration (the Postgres columns `tag`, `display_name`, `sensitive`,
`description` are unchanged). Rollback is a straight revert — no schema or storage changes are made.
Executed as an OpenSpec change: `/opsx:apply` then `/opsx:archive`; done only when
`./gradlew clean build` passes.

## Open Questions

None.
