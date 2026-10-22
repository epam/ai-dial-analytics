## 1. Toolchain & dependencies

- [x] 1.1 Bump Java toolchain 21 → 25 in `build.gradle`; Gradle wrapper 8.14.5 → 9.6.0
- [x] 1.2 Bump `io.freefair.lombok` 8.10 → 9.5.0 (older Lombok cannot run under JDK 25)
- [x] 1.3 Add `org.jooq:jooq:3.21.5` (render-only; `SQLDialect.CLICKHOUSE` requires jOOQ 3.20+); no `jooq-codegen`
- [x] 1.4 Add `configuration/QueryDslConfiguration` with a connection-less `DSL.using(SQLDialect.CLICKHOUSE)` `DSLContext` bean
- [x] 1.5 Disable `FAIL_ON_NULL_FOR_PRIMITIVES` on the shared `JsonMapper` so absent primitive query flags default

## 2. Request model & translators (ported)

- [x] 2.1 Port `query.model.**` verbatim (records, sealed `Expr`/`FilterNode`/`PageSpec`, enums, `FilterNodeDeserializer`)
- [x] 2.2 Port `query.service.dto.**` and `QueryFieldBinding`
- [x] 2.3 Port the translators (`FilterTranslator`, `ExprTranslator`, `StructuredQueryBuilder`, `ValueExprToObjectMapper`): drop the JSONB field resolver, swap to `QueryValidationException`, select-all via bindings, count as a renderable query

## 3. ClickHouse schema & execution

- [x] 3.1 Add `DialUsageLogSchema` (hand-built table + field bindings mirroring `docs/clickhouse/dial_usage_log.sql`) and `DialUsageLogSchemaProvider`
- [x] 3.2 Keep the entity-agnostic `QueryEntityRegistry` / `QueryableEntitySchemaProvider` / `StructuredQueryService` dispatch; add `DialUsageLogQueryRepository`
- [x] 3.3 Add `ClickHouseClientWrapper.query(String sql)` (connectivity → `ClickHouseUnavailableException` 503; other failures → `ClickHouseQueryException` 400)
- [x] 3.4 Rewrite `StructuredQueryExecutor` to render `getSQL(ParamType.INLINED)` on the CLICKHOUSE context and execute via the client, mapping `GenericRecord.getValues()` to rows

## 4. Web layer & error mapping

- [x] 4.1 Port `StructuredQueryController` (`POST /v1/queries/execute`) and `QuerySchemaController` (`/v1/queries`), dropping `@LogExecution` and the JSONB row converter
- [x] 4.2 Add exceptions `QueryValidationException`, `QueryEntityNotFoundException`, `ClickHouseQueryException`
- [x] 4.3 Add `@ExceptionHandler`s to `DefaultExceptionHandler`: validation/ClickHouse-query → 400 `bad_request`, unknown entity → 404 `not_found`

## 5. Tests & verification

- [x] 5.1 Port `StructuredQueryDeserializationTest` (binding round-trip) adapted to the `dial_usage_log` domain
- [x] 5.2 Add `DialUsageLogQueryRenderTest` asserting the rendered CLICKHOUSE SQL (row projection, aggregate group-by, `in`, select-all)
- [x] 5.3 Add controller slice tests (`@WebMvcTest`): execute 200 shape + 400 mapping; schema catalog/base/404
- [x] 5.4 Run `./gradlew clean build` (Java 25, checkstyle + tests) green — 32 tests pass

## 6. Specs & docs

- [x] 6.1 Author the `structured-query` and `query-schema-discovery` capability specs (this change's delta + the live specs)
- [x] 6.2 Verify against a live ClickHouse: ordered-set aggregates / `width_bucket` / `co`/`nc` rendering, and `timestamp` epoch-millis vs `DateTime64` comparison (provisional — see design Risks)
  - Done in change `2026-06-22-add-query-integration-tests` (Testcontainers). Findings & fixes: `percentile_cont`/`percentile_disc` **execute** (the CLICKHOUSE dialect renders an executable ordered-set aggregate). `co`/`nc` were rejected because jOOQ emitted a `LIKE … ESCAPE` clause ClickHouse doesn't accept — **fixed** by rendering without the explicit escape char (ClickHouse honors backslash escaping by default); they now execute as case-insensitive substring matches. `width_bucket` was rejected because jOOQ's `cast(Integer)` made the bucket count a signed `Int32` while ClickHouse requires `UInt*` — **fixed** by coercing the count via `toUInt32(...)`; it now executes and buckets correctly. The `timestamp` epoch-millis vs `DateTime64(3)` comparison was **broken** (matched nothing) and is now **fixed** — `ExprTranslator` renders `timestamp` literals via `fromUnixTimestamp64Milli(...)`. The supported core, the full operator/function/value-type matrix, paging/sort/grouping/`HAVING`, and the validation 400 paths are all covered there.
