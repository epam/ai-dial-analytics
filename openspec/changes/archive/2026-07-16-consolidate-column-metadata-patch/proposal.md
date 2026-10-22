## Why

`PATCH /v1/tables/{name}/schema` has accreted four near-identical column-metadata operations —
`retag`, `set_display_name`, `set_sensitive`, `set_description` — that are the same operation
("update field X on this column") written out four times. Each new column attribute forces ~6
lockstep additions (request record, service record, resolved record, validation loop, apply loop,
writer method), and forgetting one silently leaves an attribute un-editable (as happened with
`description` until it was retrofitted). This does not scale as more column metadata is added.

## What Changes

- **BREAKING**: Replace the four metadata operations (`retag`, `set_display_name`, `set_sensitive`,
  `set_description`) on `PATCH /v1/tables/{name}/schema` with a single `update` list. Each entry
  carries a column `name` plus optional metadata fields (`tag`, `display_name`, `description`,
  `sensitive`).
- Merge-patch field semantics (dependency-free, aligned with existing blank-as-absent
  normalization): a metadata field **absent / null → left unchanged**; **blank `""` → cleared**;
  **non-blank → set**. `sensitive`: absent/null → left unchanged; `true`/`false` → set.
- The structural operations `add`, `drop`, `rename` are **unchanged** in shape and behavior.
- No transition aliases: the old `set_X` fields are removed outright (internal, spec-driven
  surface with no external consumers).
- All existing metadata behavior is preserved: same validation (unknown-column 422, length caps),
  same no-`ALTER` catalog-only nature, same "resolve against post-add/drop/rename exposed names",
  same preservation across `rename`, same no-mutability-restriction (system/grain/ordering-key
  columns may still carry metadata).

## Capabilities

### New Capabilities

<!-- none -->

### Modified Capabilities

- `table-management`: The "Modify table schema via PATCH" requirement changes its request contract
  — the four `set_X` operations collapse into one `update` merge-patch list with absent/blank/set
  semantics. The "Schema validation" requirement's scenarios that reference `PATCH retag` /
  `PATCH set_display_name` / `PATCH set_description` are reworded to reference `PATCH update`.

## Impact

- **API**: `PATCH /v1/tables/{name}/schema` request body (breaking). Response body and all other
  `/v1/tables` endpoints are unaffected.
- **Code**: `web/dto/table/PatchSchemaRequest.java`, `service/table/PatchInput.java`,
  `web/mapper/table/TableMapper.java`, `service/table/TableSchemaService.java`,
  `service/table/CatalogSchemaWriter.java` (four setter methods collapse into one
  `updateColumnMetadata`). Reuses `TableColumnRules.normalize*` and `MAX_*_LENGTH` unchanged.
- **Docs**: `docs/column-tags.md`, the bundled tables demo page (since removed).
- **Tests**: `service/table/CatalogSchemaWriterTest.java`,
  `integration/TableManagementIntegrationTest.java`, and any `service/query` test referencing the
  old op shape.
- **Dependencies**: none added. The app's primary mapper is Jackson 3 (`tools.jackson.*`), so the
  Jackson-2-only `JsonNullable` is deliberately avoided; the blank-as-clear convention needs no
  library.
