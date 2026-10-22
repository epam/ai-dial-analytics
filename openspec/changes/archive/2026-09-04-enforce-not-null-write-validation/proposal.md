## Why

`POST /v1/tables/{name}/rows` never checks a value against the column's `nullable` flag. A row that
omits a NOT NULL column, or explicitly supplies `null` for one, passes every ADAS-level check today,
and ClickHouse itself never rejects it either: `input_format_null_as_default` (a `JSONEachRow`
default, `1`/on, and nothing in `CatalogRowWriter`/`ClickHouseWriterClient` overrides it) makes
ClickHouse treat an explicit JSON `null` for a non-`Nullable` column exactly like an omitted one -
both are silently backfilled with the column's type default (`0`, `''`, epoch date, ...). There is no
DB-side error to surface; the write simply succeeds with the wrong data. A table declared NOT NULL at
schema-definition time therefore accepts null data through the write API with no signal anywhere,
contradicting the constraint the caller asked for when they defined the schema.

## What Changes

- `TableDataService.write()` gains a per-row, per-column NOT NULL check: for every physical column
  declared non-nullable (including an enrichment's hidden grain key, which is always non-nullable),
  the row must both contain the column and carry a non-null value.
- A row violating this is rejected with the existing `TableValidationException` → `422
  validation_error` contract (same shape as the existing unknown-column and timestamp-format
  checks), naming the offending row and column so the caller can fix the payload without
  guessing.
- `POST /v1/rules/{id}/rows` (rule-scoped write-back via `RuleWriteService`) inherits the same check
  for free, since it shares the `TableDataService.write()` choke point.
- No change to schema-definition behavior, DDL rendering, or ClickHouse-side defaults — this only
  closes the application-level gap between the declared constraint and the write path that is
  supposed to enforce it.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `table-management`: the "Write rows into a materialized table" requirement gains scenarios
  rejecting a row that omits, or explicitly nulls, a non-nullable physical column (including the
  hidden grain key of an enrichment table).

## Impact

- **Code**: `TableDataService.java` (the `write()` method and its `WriteColumns` resolution) is the
  only production code expected to change. `RuleWriteService`/`RuleController` (`/v1/rules/{id}/rows`)
  are affected only through the shared choke point, no direct changes anticipated.
- **API**: `POST /v1/tables/{name}/rows` and `POST /v1/rules/{id}/rows` — new `422 validation_error`
  cases for previously-accepted-but-wrong payloads; no change to any success response shape.
- **Spec**: `openspec/specs/table-management/spec.md`, "Write rows into a materialized table"
  requirement.
- **Tests**: `TableManagementIntegrationTest` (and `EnrichmentWriteBackIntegrationTest` for the
  write-back path) gain coverage for the previously-untested missing/null-on-NOT-NULL case.
