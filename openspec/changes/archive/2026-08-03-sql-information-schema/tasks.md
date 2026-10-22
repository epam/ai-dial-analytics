# Tasks

## 1. The catalog relations

- [x] 1.1 Add `InformationSchema` (`service/query/sql/`) holding the namespace constants
      (`INFORMATION_SCHEMA`, the data schema `adas`) and the `QueryFieldType` → Dremio `DATA_TYPE`
      mapping from design D2. Keep the mapping in one place — it is the value a Dremio-compatible
      client hard-errors on.
- [x] 1.2 Add the three relations as Calcite `ScannableTable`s built from `CatalogQueryService`:
      `TABLES` (4 columns), `COLUMNS` (17), `SCHEMATA` (5), with the column order and value semantics
      the delta spec fixes. Rows come from `queryableEntityNames()` + `findModel(...)`, so
      sensitive-column filtering is inherited rather than reimplemented.
- [x] 1.3 Derive `COLUMN_SIZE`/`CHARACTER_MAXIMUM_LENGTH`/`NUMERIC_PRECISION`/`NUMERIC_PRECISION_RADIX`/
      `NUMERIC_SCALE`/`DATETIME_PRECISION` per field type; null where ADAS has no answer. Keep
      `COLUMN_DEFAULT`, `INTERVAL_TYPE`, `INTERVAL_PRECISION` null.

## 2. Schema namespace in the Calcite root

- [x] 2.1 In `CalciteQueryCatalog.snapshot()`, register each entity table twice — bare in the root and
      under the `adas` sub-schema — pointing at the same table instance, so bare names resolve exactly
      as today.
- [x] 2.2 Add the `INFORMATION_SCHEMA` sub-schema with the three relations.
- [x] 2.3 Confirm resolution stays case-insensitive for the new schema names, quoted and unquoted.

## 3. Executing a metadata statement

- [x] 3.1 Add the sealed `SqlStatementPlan` with `SqlTranslation` and the new
      `SqlMetadataResult(List<ResultColumn> columns, List<Map<String, Object>> rows)` — the shapes
      `ArrowTypeMapper`/`ArrowBatchWriter`/`StructuredQueryResultDto` already consume.
- [x] 3.2 Add `SqlQueryFrontEnd.plan(String)`: after `planner.rel(...)`, decide from the plan's table
      scans whether the statement is a metadata statement; if so evaluate it with Calcite's
      `Interpreter` and return `SqlMetadataResult`, else translate as today.
- [x] 3.3 Keep `translate(String)`'s signature and meaning; it SHALL fail on a metadata statement, so
      `FilterSqlValidator`/`ExprSqlValidator` cannot receive one.
- [x] 3.4 Map interpreter values to the `ResultColumn` kinds so the Arrow schema and the REST JSON
      agree with the declared column types.

## 4. Trailing separator

- [x] 4.1 Strip a single trailing `;` (and trailing whitespace) in `SqlQueryFrontEnd` before screening,
      so every caller behaves the same. Leave the "exactly one statement" screen untouched.

## 5. Endpoints

- [x] 5.1 `SqlQueryController.executeSql` — return `SqlMetadataResult` rows in the standard envelope
      with `totalCount` omitted.
- [x] 5.2 `QueryTranslationController.translateSql` — reject a metadata statement with `400`
      `bad_request` and the message the delta spec fixes.
- [x] 5.3 `AdasFlightSqlProducer.getFlightInfoStatement` — advertise the metadata result's schema
      without executing, skipping `requireLimitWithinCap`.
- [x] 5.4 `AdasFlightSqlProducer.streamQuery` — stream a metadata result without taking the
      concurrency gate, without the truncation probe, and without contacting ClickHouse; keep the
      per-request child allocator and its close.

## 6. Tests

- [x] 6.1 `SqlQueryFrontEnd` unit tests: the three connector statements verbatim (including the
      trailing `;` on the column query), case variants, `SELECT *` on each relation, the
      `DATA_TYPE` vocabulary, and a metadata/data mix being rejected.
- [x] 6.2 Qualified-name tests: `adas.x`, `"adas"."x"`, and bare `x` translating identically; an
      unknown qualifier still failing and naming itself.
- [x] 6.3 Trailing-separator tests: accepted once, batch still rejected, literal-with-semicolon
      untouched.
- [x] 6.4 REST test: `execute-sql` returns catalog rows with no `totalCount`; `translate-sql` returns
      `400 bad_request` for the same statement.
- [x] 6.5 Flight integration test: a metadata statement's `getFlightInfo` schema matches what `doGet`
      streams; the catalog RPCs still report no schema and no `INFORMATION_SCHEMA` rows.
- [x] 6.6 Sensitive-column test: a caller who may not see a sensitive column does not see it in
      `INFORMATION_SCHEMA.COLUMNS`.

## 7. Close out

- [x] 7.1 `./gradlew clean build` green (compile + checkstyle + tests).
- [x] 7.2 Runtime check against the local stack: run the three connector statements over
      `POST /v1/queries/execute-sql` and over Flight, and confirm a bare and an `adas`-qualified data
      query agree.
