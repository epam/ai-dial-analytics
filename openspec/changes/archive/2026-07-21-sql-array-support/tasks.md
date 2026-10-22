# Tasks: sql-array-support

## 1. Thread element type into the query model

- [x] 1.1 Add nullable `elementType` to `QueryFieldBinding` and populate it in
  `CatalogQueryService.addColumn` from `ColumnMappingEntity.elementType` (set only for
  `ARRAY`-typed fields; defensive fallback to `string` for an array with no stored element
  type); update `DialUsageLogTestModel` and any other binding constructors

## 2. Make array fields SQL-visible and projection-only

- [x] 2.1 `CalciteQueryCatalog`: include `ARRAY` fields in `sqlVisible`/`fieldTypes`/`getRowType`
  (element type carried in the per-request snapshot) and map them via
  `typeFactory.createArrayType(<element type>, -1)`; `object` stays invisible
- [x] 2.2 Add `ARRAY` to `ResultColumnKind` and a nullable element kind to `ResultColumn`;
  map array fields in `RelToStructuredQueryTranslator.fieldKind`/`kind()` to the array result
  kind with the element's scalar kind
- [x] 2.3 Enforce projection-only in `RelToStructuredQueryTranslator`/`RexToExprTranslator`:
  reject array field references as predicate/comparison operands (incl. IS [NOT] NULL, IN),
  GROUP BY keys, sort keys, DISTINCT projections (full-width group-by shape), and
  function/aggregate arguments — `SqlTranslationException` naming the field and the
  projection-only restriction
- [x] 2.4 `SqlQueryFrontEndTest`: array projection translates (row mode, result kind
  array-of-string); rejection cases for WHERE/GROUP BY/ORDER BY/DISTINCT/function-arg misuse
  with the descriptive message (not "unknown column")

## 3. DSL→SQL renderer

- [x] 3.1 `StructuredQueryToSqlRenderer.bareSelectList`: narrow the "no SQL representation"
  rejection to `object` columns; array columns render as double-quoted identifiers (bare and
  explicit selects)
- [x] 3.2 `StructuredQueryToSqlRendererTest`: explicit array select renders and round-trips
  through `SqlQueryFrontEnd.translate`; bare select on an entity with an array source column
  renders (object rejection retained)
- [x] 3.3 Enforce projection-only in the renderer too (mirrors 2.3): reject
  execution-acceptable DSL that references an array field in `filter`/`having` (e.g. `eq`
  null), as a `sort`/`group_by` key (directly or via a projected alias), as a
  function/aggregate argument, or under a `distinct` projection — the rendered SQL would be
  rejected on translate-back, breaking the round-trip guarantee; renderer tests cover each
  rejection

## 4. Arrow encoding

- [x] 4.1 `ArrowTypeMapper`: map the array result kind to `ArrowType.List` with a nullable
  child field `item` typed by the element kind's existing scalar mapping; add
  `ArrowTypeMapperTest` asserting the list mapping and totality over the declarable element
  types
- [x] 4.2 `ArrowBatchWriter.setValue`: add a `ListVector` branch accepting `List`/`Object[]`
  cells — null cell → null list, empty → empty non-null list, elements coerced by the
  element kind's existing scalar coercion; extend `ArrowBatchWriterTest`
- [x] 4.3 `FlightSqlIntegrationTest`: `SELECT execution_path FROM dial_usage_log` —
  getFlightInfo schema advertises `List<item: Utf8>`, doGet streams list values equal to the
  JSON endpoint's rows, schema agreement holds, allocator still closes clean

## 5. REST SQL and JSON shape

- [x] 5.1 Integration coverage (`SqlQueryExecutionIntegrationTest` /
  `StructuredQueryExecutionIntegrationTest`): `execute-sql` projecting `execution_path`
  returns native JSON string arrays (empty array → `[]`), equal to `/execute` for the same
  projection; array misuse in SQL yields 400 `bad_request` with the projection-only message

## 6. Specs, docs, verification

- [x] 6.1 Keep the delta specs in `openspec/changes/sql-array-support/specs/` in sync with
  shipped behavior (four capabilities: sql-query-translation, query-translation-endpoint,
  rest-sql-endpoint, flight-sql-endpoint)
- [x] 6.2 `./gradlew clean build` passes (compile + checkstyle + full test suite)
