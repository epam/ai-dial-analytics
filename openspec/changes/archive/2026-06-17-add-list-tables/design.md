## Context

ADAS is currently a runnable Spring Boot skeleton (Java 21, Spring Web MVC) with a stable
`ErrorView` error contract, Actuator health/metrics, and no data-plane functionality yet. This
change adds the first functional endpoint and the first connection to ClickHouse. ClickHouse is
reached over its HTTP interface (default port `8123`). The endpoint is internal/trusted-network
only in this phase — there is no auth, tenancy, or PII handling.

## Goals / Non-Goals

**Goals:**

- A single read-only `GET /v1/tables` endpoint backed by a live query to ClickHouse.
- An isolated ClickHouse client wrapper so connection handling and the query are separated from
  the web layer, and so auth/tenant scoping can later be layered in front of the same query.
- Configuration-driven connection; stable JSON success and error contracts.
- Forward-compatible shapes: a response object (`{"tables": [...]}`) rather than a bare array,
  and object entries (`{database, name}`) so fields can be added later without breaking clients.

**Non-Goals:**

- Authentication, authorization, scopes, tenancy, row-level policies, or PII handling.
- Per-table metadata (engine, row/byte counts, columns, partition/order keys).
- Querying/writes (`POST /query`, ODBC/ADBC SQL, ingestion), caching, or pagination.
- Cold storage / Iceberg — only the connected ClickHouse is consulted.

## Decisions

- **Layering**: `web/controller/TablesController` (`GET /v1/tables`) → `service/TableDiscoveryService`
  → `clickhouse/ClickHouseClientWrapper`. The controller returns a `TablesResponse` DTO
  (`{ tables: List<TableEntry> }`, each `{ database, name }`). The wrapper is the only component
  that knows about ClickHouse, keeping the SQL and connection isolated.
  - *Alternative considered*: controller calling the client directly — rejected; it couples the
    web layer to ClickHouse and blocks later entitlement filtering at the service layer.

- **Source query**: read from `system.tables`, the canonical table-discovery view (needs only
  read access). Project two columns and filter/sort in SQL for deterministic output:
  ```sql
  SELECT database, name
  FROM system.tables
  WHERE database NOT IN ('system', 'information_schema', 'INFORMATION_SCHEMA')
  ORDER BY database, name
  ```
  When a target database is configured, additionally constrain `database = {configured}`.
  - *Alternative considered*: `SHOW TABLES` — rejected; `system.tables` exposes `database` +
    `name` uniformly across databases and is easier to filter/sort.

- **ClickHouse client**: use the official ClickHouse Java client over HTTP (port `8123`),
  matching the HTTP access convention. Connection is read-only.
  - *Alternative considered*: JDBC driver — heavier and unnecessary for a single read query.

- **Configuration**: bind `clickhouse.*` properties from env vars (`CLICKHOUSE_HOST`,
  `CLICKHOUSE_PORT`, `CLICKHOUSE_DATABASE` optional, `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`)
  via a typed `@ConfigurationProperties`. Documented in `application.yml` and `README.md`.

- **Error mapping**: reuse the existing `ErrorView` contract. Add a dedicated
  `ClickHouseUnavailableException` mapped to `503` `clickhouse_unavailable` in
  `DefaultExceptionHandler`; unexpected failures fall through to the existing generic handler
  (`500` `internal_error`). The wrapper translates connection/timeout failures into the
  unavailable exception and never lets raw ClickHouse messages or stack traces reach the client.

## Risks / Trade-offs

- **No auth, internal-network only** → Must be deployed on a trusted network segment; this
  constraint is documented and must be lifted before broader exposure in a later phase.
- **Live read on every request** → Slightly higher load on ClickHouse and latency tied to its
  responsiveness. Acceptable for a discovery endpoint at this stage; caching can be added later
  without changing the contract.
- **Distinguishing "unreachable" from "query failed"** → Mitigation: the wrapper inspects the
  failure (connection/timeout vs. query execution) and maps to `503` vs. `500` accordingly;
  default to `500 internal_error` when ambiguous.
- **Leaking ClickHouse internals in messages** → Mitigation: `ErrorView` sanitizes messages and
  handlers emit fixed, generic messages rather than raw driver output.

## Open Questions

- Should the absence of `CLICKHOUSE_HOST` configuration fail startup or only fail the endpoint
  at call time? Default assumption: fail the request with `503 clickhouse_unavailable` so the
  service still boots for health checks; revisit if startup validation is preferred.
