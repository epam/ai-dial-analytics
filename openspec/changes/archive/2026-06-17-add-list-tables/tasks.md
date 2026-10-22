## 1. Configuration & dependency

- [x] 1.1 Add the ClickHouse Java (HTTP) client dependency to `build.gradle`
- [x] 1.2 Add a typed `@ConfigurationProperties` (`clickhouse.*`: host, port, database, user, password) and bind it
- [x] 1.3 Add `clickhouse.*` keys to `application.yml` using `${ENV_VAR:default}` bindings (uncomment/finalize the documented block)
- [x] 1.4 Document the new env vars (`CLICKHOUSE_HOST/PORT/DATABASE/USER/PASSWORD`) in `README.md`

## 2. ClickHouse client wrapper

- [x] 2.1 Create `clickhouse/ClickHouseClientWrapper` that opens a read-only HTTP connection from config
- [x] 2.2 Implement `listTables()` running `SELECT database, name FROM system.tables WHERE database NOT IN ('system','information_schema','INFORMATION_SCHEMA') ORDER BY database, name`, constraining `database = {configured}` when a target database is set
- [x] 2.3 Translate connection/timeout failures into a `ClickHouseUnavailableException`; let other failures surface as generic exceptions; never leak raw ClickHouse messages/stack traces

## 3. Service & web layer

- [x] 3.1 Add `service/TableDiscoveryService` that calls the wrapper and returns domain table entries
- [x] 3.2 Add response DTO(s): `TablesResponse` (`{ tables: [...] }`) and `TableEntry` (`{ database, name }`) under `web/dto`
- [x] 3.3 Add `web/controller/TablesController` exposing `GET /v1/tables` returning `TablesResponse`, documented with OpenAPI annotations

## 4. Error mapping

- [x] 4.1 Add `exception/ClickHouseUnavailableException`
- [x] 4.2 Add an `@ExceptionHandler` to `DefaultExceptionHandler` mapping it to `503` `clickhouse_unavailable`; confirm unexpected failures fall through to `500` `internal_error`

## 5. Tests & verification

- [x] 5.1 Web-layer test (MockMvc) asserting the `{ "tables": [...] }` shape, deterministic ordering, and the empty-list case
- [x] 5.2 Test error mapping: unreachable ClickHouse -> `503 clickhouse_unavailable`; unexpected failure -> `500 internal_error`
- [x] 5.3 Run `./gradlew clean build` (compile + checkstyle + tests) green
- [x] 5.4 Manual smoke test: `curl /v1/tables` against a reachable ClickHouse and with ClickHouse down
