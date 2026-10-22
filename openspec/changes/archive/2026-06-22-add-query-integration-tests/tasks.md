## 1. Test dependencies

- [x] 1.1 Add BOM-managed test deps to `build.gradle`: `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-clickhouse` (no pinned versions — Spring Boot 4 imports the Testcontainers BOM → 2.0.x). **Note:** Testcontainers 2.0 renamed the module artifactIds to the `testcontainers-` prefix; the old `org.testcontainers:junit-jupiter`/`:clickhouse` coordinates stop at 1.21.x and fail to resolve under the 2.0 BOM.
- [x] 1.2 Confirm the clickhouse Testcontainers module resolves and `org.testcontainers.clickhouse.ClickHouseContainer` is importable under JDK 25

## 2. Container fixture & wiring

- [x] 2.1 Add `AbstractClickHouseIntegrationTest` (`@SpringBootTest` + `@AutoConfigureMockMvc`, `webEnvironment = MOCK`) holding a `static` singleton `ClickHouseContainer` (`clickhouse/clickhouse-server:24.8`) started once in a static initializer and reused (not stopped)
- [x] 2.2 Add a `@DynamicPropertySource` that maps `clickhouse.host`/`clickhouse.port` (`getMappedPort(8123)`), `clickhouse.user`/`password`, `clickhouse.writer.user`/`password`, `clickhouse.database` (container default), and `clickhouse.migrations.enabled=true`
- [x] 2.3 Provide per-test fixture helpers in the base — `truncate()` and a fluent `fixture().row(…).insert()` — backed by an in-JVM client-v2 `Client` (built from the container's host/mapped-port/credentials, mirroring `UsageLogWriter`). Each execution test truncates in `@BeforeEach` and inserts only the rows it asserts on; no shared fixture. (In-JVM writes keep per-test seeding cheap vs. `execInContainer`; the single-table entity binding means isolation is per-row, not per-table.)
- [x] 2.4 Verify the real `ClickHouseMigrationRunner` (Flyway `V1__create_dial_usage_log`) runs on context startup against the container

## 3. Structured query execution IT

- [x] 3.1 Add `StructuredQueryExecutionIntegrationTest` extending the base; drive `POST /v1/queries/execute` via MockMvc
- [x] 3.2 Row mode: explicit `select` projection + `filter` (`eq`+`ge` AND-combined, and `in`) returns exactly the matching seeded rows (assert `$.rows` values)
- [x] 3.3 `select *` (omitted projection) returns all bound `dial_usage_log` columns (asserts leading/trailing DDL columns present)
- [x] 3.4 Aggregate mode: `group_by` + `count`/`sum` returns correct per-group aggregates computed by ClickHouse
- [x] 3.5 Sort + offset paging with `include_total`: rows are page-limited and ordered, and `$.totalCount` equals the full match count
- [x] 3.6 Error mapping end to end: unknown field → 400 `bad_request` (`QueryValidationException`); unknown/unsupported entity → 400 via `StructuredQueryService`
- [x] 3.7 (Optional, non-gating) `@Disabled` probe documenting the provisional `co` (case-insensitive contains) construct — present and runnable on demand, never gates the build

## 4. Schema discovery IT

- [x] 4.1 Add `QuerySchemaDiscoveryIntegrationTest` extending the base; `GET /v1/queries/entities` lists `dial_usage_log` (against the real `QueryEntityRegistry`)
- [x] 4.2 `GET /v1/queries/entities/schema/dial_usage_log` returns the flat base schema (20 fields, names/types) matching `DialUsageLogSchema`
- [x] 4.3 `GET /v1/queries/entities/schema/{unknown}` → 404 `not_found`

## 5. Docs & verification

- [x] 5.1 Document the Docker prerequisite for `./gradlew clean build` / `test` in `README.md`
- [x] 5.2 Note in the prior change (`2026-06-22-add-structured-query` task 6.2) that end-to-end execution of the supported core is now covered by these integration tests
- [x] 5.3 Run `./gradlew clean build` (Java 25, checkstyle + tests) green with Docker available; all new ITs pass

## 6. Expanded coverage (all missing cases)

- [x] 6.1 Enrich the `Fixture` builder with a fluent `Row` (request_time, trace_id, token columns, …) so tests can seed exactly the columns they assert on
- [x] 6.2 `StructuredQueryFilterIntegrationTest` — operators `ne`/`lt`/`gt`/`le`, logical `or`/`not`, `IS NULL`/`IS NOT NULL` (non-nullable semantics), value types `integer`/`decimal`/`uuid`/`timestamp`, scalar fns `lower`/`upper`/`length`/`trim`/`abs`, empty result, and a real ClickHouse rejection → 400
- [x] 6.3 `StructuredQueryAggregateAndPagingIntegrationTest` — `avg`/`min`/`max`, `count`/`sum` distinct, multi-key `group_by`, group-by an aliased expression, `HAVING`, `desc`/multi-key/by-alias sort, `offset>0`, `include_total:false`, query-level `distinct` (default-limit rendering is covered by the unit test in 6.5)
- [x] 6.4 `StructuredQueryProvisionalIntegrationTest` — probe & lock `percentile_cont`/`percentile_disc` (execute → median), `co`/`nc` (execute → case-insensitive substring, render fix 6.9), and `width_bucket` (execute → bucket numbers, render fix 6.10)
- [x] 6.5 `QueryTranslatorValidationTest` (unit) — every `QueryValidationException` throw path across `FilterTranslator`/`ExprTranslator`/`StructuredQueryBuilder`/`ValueExprToObjectMapper`, plus limit-clamping/default-limit render checks
- [x] 6.6 Slice/discovery: `StructuredQueryControllerTest` 503 / malformed-JSON / 405; `QuerySchemaDiscoveryIntegrationTest` detailed-schema → 400
- [x] 6.7 **Production fix** — `ExprTranslator` renders `timestamp` literals via `fromUnixTimestamp64Milli(...)` so they compare correctly against `DateTime64(3)` (was: bare epoch-millis matched nothing); integration container `TZ` pinned to UTC. Resolves the prior change's timestamp open question
- [x] 6.9 **Production fix** — render `co`/`nc` without an `ESCAPE` clause (`FilterTranslator` uses the no-escape `likeIgnoreCase` overload); ClickHouse rejects `LIKE … ESCAPE` but honors backslash escaping by default. The operators now execute as case-insensitive substring matches.
- [x] 6.10 **Production fix** — `width_bucket`: coerce the bucket-count argument to unsigned via `toUInt32(...)` in `ExprTranslator` (ClickHouse requires `UInt*`; jOOQ's `cast(Integer)` emitted signed `Int32` → `ILLEGAL_TYPE_OF_ARGUMENT`). The function now executes and buckets correctly.
- [x] 6.8 Re-run `./gradlew clean build` green — 118 tests, 0 failures
