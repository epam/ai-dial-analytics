## Why

ADAS's roadmap calls for **data querying** over ClickHouse — the real capability that the
`table-discovery` (`GET /v1/tables`) PoC was always meant to be replaced by. Rather than invent a
query contract, this change brings over the proven, body-delivered **structured query DSL** from
`ai-dial-admin-evaluation-framework-backend` (a JSON envelope with a CQL2-JSON filter tree,
expression grammar, aggregation, sorting, and offset pagination) and retargets its execution from
Postgres/jOOQ to ClickHouse. The DSL's request model is framework-agnostic and ports as-is; only the
translation/execution layer changes.

## What Changes

- Add `POST /v1/queries/execute` that accepts a structured query envelope (`entity`, `filter`,
  `mode`, `select`, `group_by`, `having`, `sort`, `page`), validates it against the named entity's
  discovered schema, translates it to ClickHouse SQL, and returns `{ rows, totalCount }`.
- Add schema-discovery endpoints under `/v1/queries`: `GET /v1/queries/entities` (catalog) and
  `GET /v1/queries/entities/schema/{name}` (flat base schema), plus a `.../detailed` endpoint that
  returns 400 for simple entities (no complex entity is registered yet).
- Register a single queryable entity, `dial_usage_log`, with a hand-built flat schema mirroring the
  ClickHouse table DDL (ADAS has no jOOQ code generation).
- Reuse jOOQ in **render-only** mode: build the query on a connection-less `SQLDialect.CLICKHOUSE`
  `DSLContext`, render to a SQL string with inlined literals, and execute via the existing ClickHouse
  HTTP client (a new `query(sql)` method on the client wrapper).
- Map new failure modes onto the existing `ErrorView` contract: `QueryValidationException` and
  `ClickHouseQueryException` → `400 bad_request`; `QueryEntityNotFoundException` → `404 not_found`;
  ClickHouse connectivity failure reuses `503 clickhouse_unavailable`.
- Migrate the toolchain to **Java 25 / Gradle 9.6.0**, bump `io.freefair.lombok` to 9.5.0, add
  `org.jooq:jooq:3.21.5`, and disable `FAIL_ON_NULL_FOR_PRIMITIVES` on the shared `JsonMapper` so
  absent primitive query flags default instead of failing deserialization.

No authentication, authorization, tenancy, or PII handling is introduced; the endpoints are
trusted-network only in this phase, consistent with `table-discovery`.

## Capabilities

### New Capabilities

- `structured-query`: body-delivered query execution over ClickHouse — envelope + filter tree +
  expression grammar + aggregation/sort/pagination, validated, translated to ClickHouse SQL, and
  executed, returning a `{ rows, totalCount }` envelope.
- `query-schema-discovery`: read-only discovery of queryable entities and their flat field schemas
  under `/v1/queries`.

### Modified Capabilities

<!-- None. `table-discovery` remains as-is; this change adds the querying surface alongside it. -->

## Impact

- **New code**: `com.epam.aidial.analytics.query.{model,service,service.dto,service.translate,
  service.repository,web}`; `configuration.QueryDslConfiguration`; exceptions
  `QueryValidationException`, `QueryEntityNotFoundException`, `ClickHouseQueryException`.
- **Modified code**: `clickhouse/ClickHouseClientWrapper` (`query(sql)`), `web/handler/
  DefaultExceptionHandler` (new handlers), `configuration/JsonMapperConfiguration`
  (`FAIL_ON_NULL_FOR_PRIMITIVES`).
- **New dependency**: `org.jooq:jooq:3.21.5` (render-only; no `jooq-codegen`, no DataSource).
- **Toolchain**: Java 21 → 25, Gradle 8.14.5 → 9.6.0, `io.freefair.lombok` 8.10 → 9.5.0.
- **Operational**: queries run against the same configured ClickHouse connection as
  `table-discovery`; the only data table consulted is `dial_usage_log`.
