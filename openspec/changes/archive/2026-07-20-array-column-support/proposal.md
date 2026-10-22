# Proposal: array-column-support

## Why

The table-management API rejects `type=array` in user-declared schemas, even though the entire
downstream stack already supports array columns: the ClickHouse DDL mapper renders
`Array(<element>)`, the Postgres catalog has an `element_type` column, the row-write path passes
JSON arrays through natively, and the structured-query surface projects the backfilled
`dial_usage_log.execution_path` array column today. Operators need to model list-shaped data
(paths, tags, ids) in user-created tables without resorting to delimited strings or opaque
`object` columns.

## What Changes

- `POST /v1/tables` (source create) and `PATCH /v1/tables/{name}/schema` (`add` operation) accept
  columns of `type: "array"` with a new required companion field `element_type` naming a scalar
  element type (`string`, `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid`).
  The column materializes in ClickHouse as `Array(<element>)`.
- Validation: an `array` column without `element_type` is rejected (422); `element_type` on a
  non-array column is rejected (422); `element_type` of `array` or `object` is rejected (422);
  an `array` column explicitly declared `nullable: true` is rejected (422) — ClickHouse does not
  support `Nullable(Array(...))`, and silently dropping the flag would create a catalog/DDL
  mismatch. An array column always resolves to `nullable=false`, including on enrichments where
  the default is otherwise nullable.
- The table read surface (`GET /v1/tables`, `GET /v1/tables/{name}`) returns each column's
  `element_type` (present for array columns, absent/null otherwise).
- Schema discovery (`GET /v1/queries/entities/schema/{name}`) exposes `element_type` on array
  fields so query consumers know the element shape.
- Row writes (`POST /v1/tables/{name}/rows`) accept JSON array values for array columns — this
  already works mechanically; it becomes covered, specified behavior.
- Unchanged (explicit non-goals, see design): array columns stay invisible to the SQL translation
  surface (Calcite), the structured-query DSL gains no array operators (arrays remain
  projectable-but-opaque), and the Arrow/Flight result path still cannot emit them.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `table-management`: the "Schema validation" requirement reverses the `type=array` exclusion
  (array becomes user-declarable with a required scalar `element_type`, with the new 422 rules);
  "Create a source table", "Modify table schema via PATCH", and "Read and list table definitions"
  gain array/element_type behavior and scenarios.
- `query-schema-discovery`: schema fields carry an optional `element_type` (populated for
  `array`-typed fields, absent otherwise).

## Impact

- **Code**: `TableColumnRules` (validator), `CreateTableRequest.ColumnRequest` +
  `PatchSchemaRequest` (wire DTO, shared), `TableMapper` (stops hardcoding `elementType=null`),
  `TableDto.ColumnDto`, `QuerySchemaFieldDto` + `CatalogQueryService` (discovery),
  `ClickHouseTypeMapper` (element vocabulary tightening: reject `object` elements),
  `StructuredQueryExecutor` (unwraps the ClickHouse client's internal `ArrayValue` so array cells
  serialize as JSON arrays — fixes a latent serialization bug already affecting `execution_path`).
- **Persistence**: none — `column_mapping.element_type` already exists (V1 migration); no new
  migration.
- **API**: additive request/response field `element_type`; new 422 validation outcomes.
- **Tests**: `CatalogSchemaWriterTest.rejectsUserDeclaredArrayColumn` and
  `TableManagementIntegrationTest.userDeclaredArrayTypeIsRejected` assert the old rejection and
  will be replaced by acceptance + new rejection-shape tests.
