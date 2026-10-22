## Context

See `proposal.md` - Why. `TableDataService.write()` already resolves a per-request `WriteColumns`
record (`physical`, `timestamps`) once per batch and loops each row twice: once to reject unknown
columns, once (via `normalizeRow`) to normalize `timestamp` values. `ColumnMappingEntity.nullable`
carries the declared constraint but is never read on this path. The grain key of an enrichment is
always rendered `Nullable`-free in DDL (`TableSchemaCreationService.enrichmentSpec`, hardcoded
`false`), independent of the source column's own `nullable` flag.

## Goals / Non-Goals

**Goals:**
- Reject, at the application layer, any row that omits or explicitly nulls a physical column
  declared non-nullable, before any row of the batch reaches ClickHouse.
- Reuse the existing `TableValidationException` → `422 validation_error` contract so this behaves
  like the unknown-column and timestamp checks the caller already handles.
- Cover `POST /v1/rules/{id}/rows` for free via the shared `TableDataService.write()` choke point.

**Non-Goals:**
- No change to schema-definition behavior, `nullable` defaults, or DDL rendering (`table-management`
  requirements for schema definition are unaffected).
- No attempt to also validate *type* of a supplied value (e.g. a string where a number is declared) -
  out of scope, matching the existing pass-through behavior for every non-timestamp type.
- No change to how ClickHouse itself would react to a null insert attempt that somehow still reaches
  it (defense in depth is not the goal; closing the ADAS-level gap is).

## Decisions

- **Add a `nonNullable` set to the existing `WriteColumns` record**, populated in the same
  single pass over `ColumnMappingRepository.findByTableNameOrderByOrdinal` that already builds
  `physical` and `timestamps` (`!col.isNullable()`), plus the grain key added unconditionally when
  present (mirroring the hardcoded `false` passed to `toDdlType` in `TableSchemaCreationService`).
  Alternative considered: a separate repository query filtered by `nullable = false`. Rejected -
  `writeColumns()` already reads every column once; a second query would double catalog reads with
  no benefit.
- **Check each row against `nonNullable` in the same loop that rejects unknown columns**, using
  `!row.containsKey(column) || row.get(column) == null` per non-nullable column, before
  `normalizeRow` runs. Alternative considered: a separate pass after the unknown-column loop.
  Rejected - the existing loop already iterates every non-nullable column's presence is best
  checked once per row rather than once per row per column-in-row, and keeping the ordering
  "structure of the row first, then its values" matches the existing unknown-column-before-
  timestamp ordering documented in the class Javadoc.
- **Reuse `TableValidationException`/`validation_error`**, not a new error code. Alternative
  considered: a dedicated `not_null_violation` code. Rejected - every other row-shape defect on
  this path (empty row, unknown column, bad timestamp) already collapses to `validation_error`;
  a caller parses the `message` for detail, not a proliferation of codes, and the spec's existing
  requirement already documents this convention.
- **Grain key is always in `nonNullable`**, regardless of the source column's own `nullable` value,
  because it is physically non-nullable in ClickHouse (`TableSchemaCreationService.enrichmentSpec`
  passes `false` unconditionally when rendering the grain key's DDL type) - the write path must
  agree with what was actually materialized, not with the source's declared constraint.

## Risks / Trade-offs

- [A batch that both omits a non-nullable column and carries an unrelated unknown column reports
  only one defect per row, in existing-loop order (unknown column first)] → Acceptable: this
  matches how the existing unknown-column/timestamp checks already interact (Scenario "An unknown
  column is rejected for that reason regardless of its values"); no caller-visible change to that
  ordering contract.
- [Existing callers who previously wrote a row without a non-nullable column and relied on
  ClickHouse's silent default-fill will now get a 422] → This is the change's explicit intent
  (closing a validation gap), not a regression; called out plainly in the MR description for
  reviewer awareness.

## Migration Plan

No data migration. Purely additive validation on an existing endpoint; no config, no DDL change.
Rollback is a plain revert of the `TableDataService` change.
