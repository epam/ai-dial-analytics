# Add REST SQL execution endpoint

## Why

ADAS already accepts ad-hoc SQL — but only over the Arrow Flight SQL gRPC transport, which
requires an Arrow-capable client, a dedicated port, and Arrow-specific JVM flags. Consumers who
already talk to the plain REST API (and get JSON from `POST /v1/queries/execute`) have no way to
submit SQL without adopting a whole new transport. A REST endpoint that accepts the same SQL the
Flight endpoint accepts and returns the same JSON envelope the DSL endpoint returns closes that
gap with near-zero new machinery: the SQL→DSL translator and the execution pipeline both exist
and are already shared.

## What Changes

- New endpoint `POST /v1/queries/execute-sql` accepting a JSON body carrying a single SQL
  `SELECT` statement (the same SQL subset the Flight SQL endpoint accepts, per
  `sql-query-translation`).
- The SQL is translated to a `StructuredQuery` via the existing `SqlQueryFrontEnd` (Calcite
  parse → validate against the query-schema catalog → lower → translate) and executed through
  the existing structured-query pipeline (`StructuredQueryService`), so all structured-query
  governance applies unchanged: entity/field validation, closed function catalog, enrichment
  joins, default limit 100, max limit 1000 (rejected, never clamped).
- The response is the exact `POST /v1/queries/execute` JSON envelope
  (`StructuredQueryResultDto`): `{ "rows": [...] }` plus `totalCount` when populated.
  `totalCount` is never populated on this path — SQL has no `include_total` opt-in — so the key
  is omitted from the JSON, exactly as the DSL endpoint serializes a null `totalCount`.
- New HTTP error mapping: `SqlTranslationException` → 400 `bad_request` via a new
  `@ExceptionHandler` in `DefaultExceptionHandler` (today it is mapped only to gRPC
  `INVALID_ARGUMENT` by the Flight error mapper). All other failure modes reuse existing
  mappings (validation → 400, ClickHouse rejection → 400, ClickHouse down → 503).
- Authentication/authorization: none added — the endpoint lives under `/v1/**` and inherits the
  existing security rule (`FULL_ADMIN` or `READ_ONLY_ADMIN` in `oidc` mode; open in `none` mode).
- No new configuration: the endpoint is always on, like `POST /v1/queries/execute` (it is a
  read-only path with the same governance; the `flight.*` toggles govern only the gRPC listener).

## Capabilities

### New Capabilities

- `rest-sql-endpoint`: the HTTP/JSON transport for ad-hoc SQL — `POST /v1/queries/execute-sql`
  request/response contract, reuse of the `sql-query-translation` contract and structured-query
  governance, HTTP error mapping (including the new `SqlTranslationException` → 400), and
  security inheritance. Named symmetrically with `flight-sql-endpoint`, which specs the gRPC
  transport over the same translator.

### Modified Capabilities

<!-- none: the SQL→StructuredQuery translation contract (sql-query-translation), the
     structured-query execution/response contract, and authentication requirements are all
     unchanged; this change only adds a second transport in front of them. The
     sql-query-translation Purpose section's cross-reference ("submitted via the
     flight-sql-endpoint") gets a wording touch-up at archive time, but no requirement changes. -->

## Impact

- **New code**: a controller under `web/controller/query/` (e.g. `SqlQueryController`), a small
  request DTO (`{ "sql": "..." }` — an object, not a bare string, per API conventions), and one
  `@ExceptionHandler` in `web/handler/DefaultExceptionHandler`.
- **Reused unchanged**: `service/query/sql/SqlQueryFrontEnd` (and the whole Calcite translator),
  `service/query/StructuredQueryService`, `StructuredQueryExecutor`, `ClickHouseClientWrapper`,
  `web/dto/query/StructuredQueryResultDto`.
- **Bean availability note**: the SQL front-end components must be constructible without
  `flight.enabled=true` — verify `SqlQueryFrontEnd` and its collaborators are not gated behind
  the Flight `@ConditionalOnProperty`, and lift the gate if they are.
- **APIs**: adds `POST /v1/queries/execute-sql`; no changes to existing endpoints or wire
  contracts.
- **Docs**: README/API docs mention the new endpoint; no `docs/configuration.md` changes (no new
  properties).
- **Tests**: a `@WebMvcTest` controller slice (template: `StructuredQueryControllerTest`) plus a
  Testcontainers integration test asserting REST-SQL vs DSL row equivalence and error mapping.
