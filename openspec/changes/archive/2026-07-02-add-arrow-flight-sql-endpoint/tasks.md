# Tasks: add-arrow-flight-sql-endpoint

## 1. Build setup and dependencies

- [x] 1.1 Add Arrow 18.3.x (`flight-core`, `flight-sql` with `flight-core` exclusion, `arrow-vector`, `arrow-memory-netty`) and Calcite 1.42.x (`calcite-core`, `calcite-babel`) to `build.gradle`; resolve Guava/Jackson version alignment against the Boot BOM
- [x] 1.2 Add `--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED` to `bootRun` and `test` JVM args; verify `./gradlew clean build` still passes with the new deps
- [x] 1.3 Add `flight.*` config group to `application.yml` (bare `key: ${ENV:default}` lines: enabled, port, batch-rows, batch-max-bytes, max-concurrent-streams, request-memory-limit-bytes)

## 2. Calcite catalog and SQL front end (`service.query.sql`)

- [x] 2.1 Implement the Calcite schema adapter: root schema + one `Table` per ACTIVE queryable entity from `CatalogQueryService`, flat fields (including dotted enrichment columns) mapped to Calcite types per design D4, `object`/`array` fields excluded
- [x] 2.2 Implement the Calcite framework config (Babel parser, case-insensitive, no optimization rules) and the parse → validate → `RelNode` front end; enforce single read-only statement (reject multi-statement and non-SELECT kinds)
- [x] 2.3 Unit tests: known entity/columns validate, unknown table/column rejected, quoted dotted enrichment identifiers resolve, `object`/`array` fields invisible, case-insensitive matching

## 3. RelNode → StructuredQuery translator (`service.query.sql`)

- [x] 3.1 Implement the RelNode-stack walker: TableScan→entity, Filter placement (filter vs having), Aggregate→mode/group_by, Project→select with aliases, Sort→sort + OffsetPage, DISTINCT-as-Aggregate detection; reject joins, multiple scans, grouping sets, windows, set ops, subqueries
- [x] 3.2 Implement the RexNode→Expr translator: InputRef→FieldExpr, Literal→ValueExpr with DSL value typing (timestamps→epoch-millis, dates→ISO, decimals→plain string), comparison/logical operator mapping, IS NULL/IS NOT NULL, cast-tolerance list (unwrap identity/widening, reject others), reject arithmetic/CASE/params
- [x] 3.3 Implement Sarg/`SEARCH` expansion: point sets → `in`/`eq`, simple bounds → `gt/ge/lt/le` conjunctions, reject non-expandable Sargs; isolate in its own class
- [x] 3.4 Implement function mapping (scalar + aggregate catalog 1:1, `COUNT(*)`, DISTINCT aggregates, `percentile_cont/disc`, `date_bin`, `width_bucket`) and the `LIKE`→`co`/`nc` contains-only rule
- [x] 3.5 Unit tests for every scenario in `specs/sql-query-translation/spec.md`: round-trip SQL→StructuredQuery equivalence for the supported subset and fail-closed rejection (with construct-naming messages) for the unsupported list; include validator-injected-cast cases and real pandas/ADBC-style query patterns

## 4. Arrow result path (`service.query.arrow`)

- [x] 4.1 Implement `ArrowTypeMapper`: normative QueryFieldType/DSL-type → ArrowType mapping (design D4), all fields nullable; result-schema derivation from the translated StructuredQuery + Calcite validated row type (aggregate result types)
- [x] 4.2 Implement `ArrowBatchWriter`: fill `VectorSchemaRoot` from executor row maps, flush on batch-rows/batch-max-bytes, preserve nulls; coerce ClickHouse client-v2 native Java values per type
- [x] 4.3 Unit tests: type mapping table, null preservation, batch boundary behavior, value coercion for each supported type

## 5. Flight server and producer (`web.flight`)

- [x] 5.1 Implement `FlightProperties` (prefix `flight`), root/child `ArrowAllocators`, and the `SmartLifecycle` server bean (start only when enabled, ephemeral port support, clean shutdown with zero-allocation assertion hook)
- [x] 5.2 Implement the producer extending `BasicFlightSqlProducer`: `getFlightInfoStatement` (translate+validate, derive schema, no execution), `getStreamStatement` (full pipeline + batch streaming), `determineEndpoints` + ticket codec, `getStreamSqlInfo` (read-only, no transactions/DDL, case-insensitive); leave prepared-statement RPCs UNIMPLEMENTED
- [x] 5.3 Implement the concurrency gate (semaphore held only during doGet execution, RESOURCE_EXHAUSTED on saturation) and per-request bounded child allocator with guaranteed close on error/cancel
- [x] 5.4 Implement `FlightErrorMapper` per design D8 (parse/translation/validation→INVALID_ARGUMENT, ClickHouse query→INVALID_ARGUMENT, ClickHouse connectivity→UNAVAILABLE, saturation→RESOURCE_EXHAUSTED, other→INTERNAL redacted)

## 6. Flight authentication (`web.flight`)

- [x] 6.1 Implement the Flight header auth middleware active only when `security.mode=oidc`: Bearer via existing JWT/introspection machinery, `Api-Key` via `CoreApiKeyIntrospector`+`ApiKeyCache`, bearer-wins precedence; UNAUTHENTICATED on rejection, UNAVAILABLE on introspection outage; no middleware in `none` mode
- [x] 6.2 Confirm and encode the authority rule (any authenticated principal, mirroring HTTP read rules) against `OidcSecurityConfiguration`; update design.md open question with the outcome — resolved: HTTP `/v1/**` requires FULL_ADMIN or READ_ONLY_ADMIN, Flight mirrors exactly that
- [x] 6.3 Tests: missing/invalid credentials rejected in oidc mode, valid api-key accepted (mocked Core), open in none mode

## 7. Integration tests against real ClickHouse (part of this change, gating)

These run against Testcontainers ClickHouse + Postgres in the standard test suite (no separate
profile), so `./gradlew clean build` fails if they fail — they are part of the feature, not a
follow-up.

- [x] 7.1 Flight end-to-end with a Flight SQL client: getFlightInfo/doGet happy path, schema agreement between the two calls, REST-vs-Flight same-rows/same-values equivalence for identical logical queries (per supported type: string, integer, long, decimal, boolean, date, timestamp, uuid, null cells)
- [x] 7.2 Execute every supported SQL construct end-to-end on real ClickHouse via Flight: filters (all comparison ops, IN, IS NULL, contains), GROUP BY/HAVING, ORDER BY with NULLS FIRST/LAST, DISTINCT, LIMIT/OFFSET, enrichment column selection (LEFT JOIN with unmatched rows → Arrow nulls)
- [x] 7.3 Verify the provisional jOOQ renderings on real ClickHouse through the Flight path: `percentile_cont`, `percentile_disc`, `width_bucket`, and `date_bin` produce executable SQL and correct values (closes the "verify against live ClickHouse" caveat in the structured-query spec for these paths)
- [x] 7.4 Governance integration: limit clamp (LIMIT 50000 → ≤1000 rows), default limit applied, saturation fails fast, allocator leak gate (zero allocated bytes after error/cancel), ClickHouse-down → UNAVAILABLE, disabled-by-default (no listener)

## 8. Documentation and finalization

- [x] 8.1 Add the `flight.*` property table to `docs/configuration.md` (including the deployment `--add-opens` note); add a short Flight SQL section to README linking there
- [x] 8.2 Verify delta specs match shipped behavior (LIKE case-insensitivity deviation, dotted enrichment identifiers, clamp semantics documented); adjust specs if implementation forced deviations — adjusted: `COUNT(*)` scenario, DATE→TIMESTAMP widening, lossless derived tables, plus a new `structured-query` delta spec for the global-aggregate GROUP BY fix
- [x] 8.3 `./gradlew clean build` passes (compile + checkstyle + full test suite); run `openspec validate add-arrow-flight-sql-endpoint`
