# Proposal: add-arrow-flight-sql-endpoint

## Why

Analytics consumers (pandas/ADBC, Arrow-native tooling) want to query ADAS with plain SQL over
Arrow Flight SQL instead of hand-building structured-query JSON, and want results as typed
columnar Arrow batches instead of untyped JSON rows. This change adds a Flight SQL server
(Arrow 18.3.0, Calcite 1.42.0) together with a translator that maps external SQL onto ADAS's
own `StructuredQuery` object DSL, so every Flight query flows through the exact same
governance (validation, closed function catalog, enrichment join rules, pagination limits) as
`POST /v1/queries/execute`.

## What Changes

- New Arrow Flight SQL endpoint on a dedicated gRPC port (default 32010, off by default),
  supporting ad-hoc `SELECT` statements via `getFlightInfo`/`doGet` (statement-only v1; no
  prepared statements, no DML/DDL).
- New Calcite-based SQL front end: parse (Babel) → validate against a Calcite schema adapter
  backed by the existing Postgres-driven query catalog (`CatalogQueryService`) → lower to
  `RelNode` → translate the `RelNode` into a `StructuredQuery` DSL instance. Any SQL construct
  the DSL cannot express fails closed with `INVALID_ARGUMENT`.
- Execution reuses the existing structured-query pipeline unchanged (`StructuredQueryService` →
  jOOQ render → ClickHouse client-v2). Flight inherits REST governance verbatim, including the
  1000-row limit clamp and default limit 100.
- Results are converted from the executor's rows into Arrow record batches
  (`VectorSchemaRoot` batching) with a deterministic
  `QueryFieldType` → Arrow type mapping; `getFlightInfo` derives the result schema without
  executing the query.
- Flight authentication reuses ADAS security: when `security.mode=oidc`, a Flight header
  authenticator validates `Api-Key` / `Bearer` credentials via the existing introspection and
  JWT machinery; in `security.mode=none` the endpoint is open, matching REST behavior.
- New dependencies: `org.apache.arrow:flight-sql`/`flight-core`/`arrow-vector`/
  `arrow-memory-netty` 18.3.x, `org.apache.calcite:calcite-core`/`calcite-babel` 1.42.x; JVM
  `--add-opens=java.base/java.nio` flag for Arrow's Netty allocator.
- New config group `flight.*` (enabled, port, batch sizing, max concurrent streams, per-request
  memory limit) documented in `docs/configuration.md`.

Out of scope: prepared statements / parameter binding, pgwire front door, row limits above the
REST cap, streaming reads from ClickHouse, TLS on the gRPC listener, and Flight metadata RPCs
beyond what `BasicFlightSqlProducer` provides by default.

## Capabilities

### New Capabilities

- `flight-sql-endpoint`: the Arrow Flight SQL transport — server lifecycle and configuration,
  supported RPCs, authentication, concurrency and memory governance, Arrow result schema/type
  mapping and batch streaming, and error mapping to gRPC status codes.
- `sql-query-translation`: the SQL → `StructuredQuery` mapping contract — the Calcite catalog
  derived from query-schema discovery, the supported SQL subset (single-entity `SELECT` with
  projection, filters, aggregates, `GROUP BY`/`HAVING`, `ORDER BY`, `LIMIT`/`OFFSET`,
  `DISTINCT`, the closed function catalog), translation rules (operator/function/type mapping,
  quoted enrichment column names, implicit-cast tolerance), and fail-closed rejection of
  everything else.

### Modified Capabilities

- `structured-query`: global aggregates (aggregate mode with no `group_by`) now render without a
  `GROUP BY` clause, so they return exactly one row even over an empty match — a latent bug
  (jOOQ's `GROUP BY (SELECT 1)` "group by nothing" form returned zero rows) surfaced by the
  Flight integration tests and fixed in the shared pipeline. `query-schema-discovery` and
  `authentication` requirements are reused as-is; Flight-specific auth requirements live in
  `flight-sql-endpoint`.

## Impact

- **Code**: new packages `web/flight/` (server lifecycle, producer, auth adapter, error
  mapping, Arrow batch writer/type mapper) and `service/query/sql/` (Calcite config, catalog
  adapter, RelNode→DSL translator). No changes to the existing structured-query execution path
  beyond making translation-friendly seams visible (`StructuredQueryService` is called as-is).
- **APIs**: new gRPC listener (default port 32010) alongside the HTTP API; HTTP surface
  unchanged.
- **Dependencies**: Arrow 18.3.x, Calcite 1.42.x added to `build.gradle`; `bootRun`/`test`
  JVM args gain `--add-opens=java.base/java.nio=...`.
- **Config/Docs**: `application.yml` gains bare `flight.*` keys; property descriptions added to
  `docs/configuration.md`; README gains a short Flight SQL section linking there.
- **Security**: one new network listener; disabled by default (`FLIGHT_ENABLED=false`) so
  existing deployments are unaffected until explicitly enabled.
