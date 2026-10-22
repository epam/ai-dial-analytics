# Tasks: sql-object-support

## 1. Make object fields SQL-visible

- [x] 1.1 `CalciteQueryCatalog`: delete the `sqlVisible` predicate and its two call sites
  (`fieldTypes`, `EntityTable.getRowType`) so every declared field type is in the schema; map
  `OBJECT` to `SqlTypeName.VARCHAR` in `EntityTable.scalarType` (replacing the
  `IllegalStateException`); rewrite the class javadoc's "object fields are excluded" paragraph
  to state the projection-only rule for `array` and `object`
- [x] 1.2 Add `OBJECT` to `ResultColumnKind` (javadoc: JSON text once it leaves the DSL) and map
  `OBJECT` fields in `RelToStructuredQueryTranslator.fieldKind` to it instead of throwing;
  `ResultColumn.elementKind` stays array-only

## 2. Projection-only enforcement (translation side)

- [x] 2.1 `RexToExprTranslator`: generalize `rejectArrayOperand` to
  `rejectProjectionOnlyOperand` covering `ARRAY` and `OBJECT`, interpolating the type word into
  the existing message shape (`object field 'jwt_claims' can only be projected; <use> an object
  field is not supported`); array messages must stay byte-identical
- [x] 2.2 `RelToStructuredQueryTranslator`: same generalization for `rejectArrayField` →
  `rejectProjectionOnlyField` (GROUP BY keys, sort keys, `SELECT DISTINCT` full-width group-by,
  aggregate arguments)
- [x] 2.3 `SqlQueryFrontEndTest`: object projection translates (row mode, result kind `OBJECT`);
  rejection cases for WHERE (`IS NULL` and `= 'x'`), LIKE, IN, GROUP BY, ORDER BY,
  `SELECT DISTINCT`, and function/aggregate argument, each asserting the descriptive message
  (naming the field, not "unknown column"); `SELECT *` over an entity with object columns
  translates

## 3. DSL→SQL renderer

- [x] 3.1 `StructuredQueryToSqlRenderer.bareSelectList`: remove the `object` rejection entirely
  (object columns render as double-quoted identifiers); update the method and class javadoc,
  which currently document "object … has no SQL representation" as a rejection reason
- [x] 3.2 `StructuredQueryToSqlRenderer.rejectNonProjectionArrayUse` →
  `rejectNonProjectionUse`: cover `OBJECT` alongside `ARRAY` in `filter`/`having` operands,
  `sort`/`group_by` keys (direct or via a projected alias), function/`in`-list arguments and
  `distinct` projections, with the type word in the message
- [x] 3.3 `StructuredQueryToSqlRendererTest`: replace
  `rejectsEmptySelectWhenBareProjectionColumnIsNotSqlRepresentable` with a test that the bare
  select over `dial_usage_log` now renders and includes `"jwt_claims"`/`"request_tags"`;
  explicit object select renders and round-trips through `SqlQueryFrontEnd.translate`; one
  rejection test per non-projection use
- [x] 3.4 `QueryTranslationServiceTest` / `QueryTranslationControllerTest`: update any case
  pinned to the removed bare-select rejection

## 4. Arrow encoding

- [x] 4.1 `ArrowTypeMapper`: map `OBJECT` → `ArrowType.Utf8`; update the class javadoc and
  extend `ArrowTypeMapperTest` (object field is nullable Utf8 with no children; the
  kind→type mapping stays total)
- [x] 4.2 `ArrowBatchWriter`: coerce every object cell to compact JSON text with the shared Jackson
  mapper — no `String` pass-through, so Arrow and REST cannot disagree about a cell — threading the
  mapper through `drain(...)` from the Flight producer; extend `ArrowBatchWriterTest` with a nested
  `Map` cell (asserting real JSON, not `toString`), a `String` cell, an empty object → `{}`, and a
  null cell → Arrow null
- [x] 4.3 Update the Flight producer call sites that invoke `ArrowBatchWriter.drain` to pass the
  mapper (both the `getFlightInfo`/schema path and `doGet`, whichever call it)
- [x] 4.4 `FlightSqlIntegrationTest`: `SELECT project_id, jwt_claims FROM dial_usage_log` —
  getFlightInfo advertises `jwt_claims` as `Utf8`, doGet streams JSON text that parses back to
  the value the JSON endpoint returns, empty object streams as `{}`, schema agreement holds and
  the allocator still closes clean; plus an object-misuse statement failing `INVALID_ARGUMENT`
  with the field named

## 5. REST SQL and JSON shape

- [x] 5.1 `SqlQueryExecutionIntegrationTest`: `execute-sql` projecting `jwt_claims` returns a
  native JSON object (nested values preserved, empty object → `{}`) and the response body is
  byte-identical to `/v1/queries/execute` for the same projection; `SELECT * FROM
  dial_usage_log LIMIT 1` succeeds; object misuse yields 400 `bad_request` with the
  projection-only message
- [x] 5.2 REST/JSON path: the client's `ArrayValue` wrapper turned out to need unwrapping at every
  depth (a `JSON` cell's nested arrays carried it), so `StructuredQueryExecutor.normalize` now
  recurses through maps and lists — a latent `/execute` defect, recorded in design decision 6, in
  the `structured-query` delta spec, and pinned by a `/execute` integration test

## 6. Governance check

- [x] 6.1 Coverage that a model with a sensitive `object` column filtered out yields "unknown
  column" in SQL (and that `SELECT *` does not smuggle it back). Placed in `SqlQueryFrontEndTest`
  rather than an integration test: the integration context runs `security.mode=none`, where
  `SensitiveColumnAccess` fails open by design, so the filtering is only observable at the
  model→schema boundary this test exercises

## 7. Specs, docs, verification

- [x] 7.1 Keep the delta specs in `openspec/changes/sql-object-support/specs/` in sync with
  shipped behavior (five capabilities: sql-query-translation, query-translation-endpoint,
  rest-sql-endpoint, flight-sql-endpoint, and structured-query for the recursive unwrap)
- [x] 7.2 Grep the repo for the removed "no SQL representation" wording and the "object fields
  are not SQL-visible" javadoc/comments and remove every stale claim
- [x] 7.3 `./gradlew clean build` passes (compile + checkstyle + full test suite)
