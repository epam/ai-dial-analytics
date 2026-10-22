## ADDED Requirements

### Requirement: List tables endpoint

The service SHALL expose `GET /v1/tables` that returns the list of tables available in the
connected ClickHouse instance. The request takes no path parameters and no body. The response
SHALL be `200 OK` with `application/json` body of the shape `{ "tables": [ ... ] }`, where
`tables` is an array (possibly empty). Each entry SHALL be an object identifying a table by its
`database` and `name`; no other metadata is returned in this phase.

#### Scenario: Tables are returned

- **WHEN** a client sends `GET /v1/tables` and the connected ClickHouse has user tables
- **THEN** the service responds `200 OK` with `{ "tables": [ { "database": "...", "name": "..." }, ... ] }`

#### Scenario: No user tables exist

- **WHEN** a client sends `GET /v1/tables` and no analytics-relevant tables exist
- **THEN** the service responds `200 OK` with `{ "tables": [] }`

### Requirement: Live read with no caching

The service SHALL read the table list live from ClickHouse on each request and MUST NOT cache
results in this phase. The response SHALL reflect the current state of the instance.

#### Scenario: Newly created table appears

- **WHEN** a table is created in ClickHouse and a client subsequently sends `GET /v1/tables`
- **THEN** the response includes that table without requiring a service restart or cache eviction

### Requirement: System schemas are excluded

The service SHALL exclude ClickHouse internal/system schemas (`system`, `information_schema`,
`INFORMATION_SCHEMA`) from the result so consumers see only analytics-relevant tables. When a
single target database is configured, the result SHALL be constrained to that database;
otherwise all non-system databases SHALL be returned.

#### Scenario: System databases are filtered out

- **WHEN** a client sends `GET /v1/tables`
- **THEN** no entry has a `database` of `system`, `information_schema`, or `INFORMATION_SCHEMA`

#### Scenario: Target database constrains the result

- **WHEN** a target database is configured and a client sends `GET /v1/tables`
- **THEN** every returned entry has `database` equal to the configured target database

### Requirement: Deterministic ordering

The service SHALL return entries sorted by `database`, then by `name`, so output is
deterministic across requests.

#### Scenario: Stable ordering

- **WHEN** a client sends `GET /v1/tables` twice against an unchanged instance
- **THEN** both responses list the entries in the same order, sorted by `database` then `name`

### Requirement: Configuration-driven connection

ClickHouse connection details (host, port, optional target database, user, password) SHALL be
supplied by configuration (environment variables) and MUST NOT be hard-coded. The configured
user only needs read access to `system.tables` and the target databases; no DDL or write grants
are required.

#### Scenario: Connection uses configured values

- **WHEN** the service starts with ClickHouse connection environment variables set
- **THEN** `GET /v1/tables` queries the configured ClickHouse host/port/database using the
  configured credentials

### Requirement: Stable error contract

The endpoint SHALL return meaningful, stable error responses using the shared error contract
(`error` machine code + human-readable `message`). When ClickHouse is unreachable or the
connection times out, the service SHALL respond `503` with error code `clickhouse_unavailable`.
When the query fails unexpectedly, the service SHALL respond `500` with error code
`internal_error`. Error responses MUST NOT leak stack traces or raw ClickHouse internals.

#### Scenario: ClickHouse is unreachable

- **WHEN** a client sends `GET /v1/tables` and ClickHouse cannot be reached or the connection times out
- **THEN** the service responds `503` with body `{ "error": "clickhouse_unavailable", "message": "..." }`

#### Scenario: Query fails unexpectedly

- **WHEN** a client sends `GET /v1/tables` and the ClickHouse query fails for an unexpected reason
- **THEN** the service responds `500` with body `{ "error": "internal_error", "message": "..." }`
  and no stack trace or raw ClickHouse internals are exposed
