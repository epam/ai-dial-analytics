# Proposal: sql-array-support

## Why

Array columns are now first-class on the table-management and structured-query surfaces: users
can declare `type=array` columns with a scalar `element_type` (change `array-column-support`,
2026-07-20), discovery exposes `element_type`, the row-write path accepts JSON arrays, and
`POST /v1/queries/execute` returns array cells as native JSON arrays. But the governed SQL
pipeline still hides them: `array` fields are excluded from the Calcite SQL-visible schema, so
any SQL statement referencing them fails as "unknown column" on both
`POST /v1/queries/execute-sql` and the Arrow Flight SQL endpoint, and the Arrow result encoder
has no list type at all. SQL/Flight consumers (BI tools, ADBC clients) cannot read columns the
JSON path already serves — a gap the `array-column-support` change explicitly deferred.

## What Changes

- Array-typed fields become **SQL-visible** in the Calcite schema used by `execute-sql`,
  Flight SQL, and `translate-sql` — they can be projected (`SELECT execution_path FROM ...`,
  including bare `SELECT *`-equivalent projection), for any scalar `element_type`.
- Array fields are **projection-only**: using an array field in `WHERE`, `GROUP BY`, `ORDER BY`,
  `SELECT DISTINCT`, or as a function/aggregate argument is rejected with a stable, descriptive
  translation error (not a generic "unknown column"). `object` fields remain excluded from the
  SQL-visible schema entirely.
- The SQL renderer (`translate` / bare-select rendering) no longer rejects entities whose source
  columns include an array column; array columns render as plain quoted identifiers.
- **Arrow Flight SQL results encode array columns as Arrow `List` vectors** with a typed child
  element field; schema (`GetFlightInfo`) and data batches agree.
- **REST SQL results** (`execute-sql`) return array values as native JSON arrays (same shape the
  structured `/execute` path already produces).
- Array *operators* (membership, length, element access) in SQL/DSL filters stay out of scope.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `sql-query-translation`: array fields are added to the SQL-visible schema as projection-only
  columns (typed by their catalog `element_type`); comparisons/grouping/ordering/aggregation
  over array fields are rejected with a descriptive error. `object` fields remain SQL-invisible.
- `query-translation-endpoint`: bare `select` over an entity with array source columns now
  renders (array columns are projected like scalars); the "no SQL representation" rejection
  narrows to `object` columns.
- `rest-sql-endpoint`: array result values are returned as JSON arrays in the row objects.
- `flight-sql-endpoint`: array result columns are encoded as Arrow `List` vectors with a typed
  element child; both the FlightInfo schema and the record batches carry the list type.

## Impact

- **Code**: `service/query/QueryFieldBinding` + `CatalogQueryService` (thread `element_type`
  into the query model — discovery already exposes it), `service/query/sql/CalciteQueryCatalog`
  (visibility + `createArrayType`), `service/query/sql/RelToStructuredQueryTranslator` (array
  result kind + projection-only enforcement), `service/query/sql/StructuredQueryToSqlRenderer`
  (bare-select allowance), `ResultColumn`/`ResultColumnKind` (array kind + element kind),
  `service/query/arrow/ArrowTypeMapper` + `ArrowBatchWriter` (Arrow `List` schema and
  `ListVector` writing).
- **APIs**: additive only — previously rejected SQL statements now succeed; no request or
  response shape changes on existing accepted calls. No breaking changes.
- **Dependencies**: none added (Calcite 1.42.0 and Arrow 18.3.0 already support array/list types).
- **Specs**: delta specs for the four modified capabilities above (query-schema-discovery
  already documents `element_type` from the `array-column-support` change).
