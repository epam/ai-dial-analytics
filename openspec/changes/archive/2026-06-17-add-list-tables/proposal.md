## Why

ADAS needs a first vertical slice that proves the service can stand up, connect to the
configured ClickHouse, and expose data over HTTP. Listing the tables in the connected
ClickHouse is the smallest piece of real functionality that exercises the whole path
(bootstrap → config → ClickHouse connection → query → serialization → HTTP response),
de-risks the plumbing, and gives downstream consumers a concrete contract to integrate
against.

## What Changes

- Add a single read-only endpoint `GET /v1/tables` that returns the list of tables available
  in the connected ClickHouse instance, each identified by `database` and `name`.
- Add ClickHouse connection configuration (host, port, optional target database, credentials)
  driven entirely by environment variables — nothing hard-coded.
- Add an isolated ClickHouse client wrapper that runs a single read-only query against
  `system.tables` on each request (no caching); results are sorted by `database`, then `name`.
- Exclude ClickHouse internal/system schemas (`system`, `information_schema`,
  `INFORMATION_SCHEMA`) so consumers see only analytics-relevant tables.
- Add endpoint-specific error mapping onto the existing `ErrorView` contract: `503`
  `clickhouse_unavailable` when ClickHouse is unreachable, `500` `internal_error` on
  unexpected query failure.

No authentication, authorization, tenancy, PII handling, per-table metadata, write paths, or
pagination are introduced (see Out of Scope in design.md). The endpoint is treated as an
internal, trusted-network endpoint for this phase.

## Capabilities

### New Capabilities

- `table-discovery`: Discovery of the tables available in the connected ClickHouse instance
  over HTTP, returning a stable, documented JSON contract (names only) with meaningful error
  responses.

### Modified Capabilities

<!-- None: no existing capability specs yet. -->

## Impact

- **New code**: a REST controller under `web/controller` (`/v1/tables`), a response DTO under
  `web/dto`, a `service` layer, a ClickHouse client wrapper + its configuration properties, and
  one or two new `@ExceptionHandler`s on `DefaultExceptionHandler`.
- **New dependency**: a ClickHouse client (ClickHouse Java client over HTTP).
- **Configuration**: new env vars `CLICKHOUSE_HOST`, `CLICKHOUSE_PORT`, `CLICKHOUSE_DATABASE`
  (optional target), `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`; documented in `application.yml`
  and `README.md`.
- **Operational**: a ClickHouse read-only user/credential must be provisioned and supplied via
  the deployment's secret store; until auth lands, ADAS must run on a trusted network segment.
