## MODIFIED Requirements

### Requirement: Execution reuses structured-query governance

Every Flight query SHALL execute by constructing a `StructuredQuery` and invoking the same
execution path as `POST /v1/queries/execute`. All structured-query governance SHALL apply
unchanged with one exception, the row ceiling: entity and field validation, the closed function
catalog, and enrichment join rules apply as-is, while the maximum number of rows a Flight query may
return is the Flight transport's own configured cap `flight.max-rows` (default 10000) rather than the
shared 1000-row maximum. That the Flight ceiling exceeds the REST ceiling is deliberate: Flight streams
Arrow record batches with per-request Arrow memory bounded by `flight.request-memory-limit-bytes` and
concurrency bounded by `flight.max-concurrent-streams`, whereas the REST surfaces materialize a JSON
document.

A SQL `LIMIT` above `flight.max-rows` SHALL be rejected with an `INVALID_ARGUMENT` status naming the
requested limit and the configured cap — never silently clamped — at `getFlightInfo`, before ClickHouse
is contacted, and also on the `doGet` ticket path so a client-supplied ticket cannot bypass it.

A statement whose `LIMIT` was **not** stated (no `LIMIT` at all, or an `OFFSET` with no `LIMIT`) SHALL
be executed under an implicit bound of `flight.max-rows` and SHALL NOT be rejected for lacking a
`LIMIT` (**BREAKING**: superseding `flight-sql-catalog-metadata`, which rejected every statement without
an explicit `LIMIT`; that rule blocked results that cannot truncate, such as `SELECT count(*)`, and every
query a BI tool's DirectQuery generator emits). Whether the limit was stated SHALL be carried explicitly
by the translation and SHALL NOT be inferred from the limit's value.

Such a statement SHALL be executed requesting one row more than the cap, and:

- if more than `flight.max-rows` rows are returned, the call SHALL fail with `INVALID_ARGUMENT` naming
  the configured cap and both remedies (add an explicit `LIMIT`, or aggregate), and SHALL NOT stream a
  partial page;
- otherwise the rows SHALL be streamed, and the extra probe row SHALL NEVER be visible to the client.

A statement with an explicit `LIMIT n` where `n <= flight.max-rows` SHALL return up to `n` rows with no
truncation error and no probe: the caller asked for a bound, so stopping at it is not a surprise.

Because `getFlightInfo` validates without executing, the truncation failure can only surface at `doGet`:
`getFlightInfo` SHALL succeed for such a statement and the stream SHALL then fail. Consequently a query's
success depends on the data as well as the statement — a result that fits the cap today may exceed it
later — and a client that supplies an explicit `LIMIT` is not subject to it.

The REST surfaces are unaffected: `POST /v1/queries/execute-sql`, `POST /v1/queries/execute`, and the
translation endpoints SHALL keep the shared default limit of 100 and maximum of 1000, including their
existing silent truncation of an unbounded query to the default.

#### Scenario: Limit above the configured cap rejected

- **WHEN** a client runs `SELECT request_id FROM dial_usage_log LIMIT 50000` via Flight with
  `flight.max-rows` at its default
- **THEN** the call fails at `getFlightInfo` with `INVALID_ARGUMENT`, the message names both 50000 and
  10000, and ClickHouse is not contacted

#### Scenario: Unbounded query under the cap succeeds

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log` with no `LIMIT` and the entity holds
  fewer rows than `flight.max-rows`
- **THEN** every matching row is streamed and no error is raised

#### Scenario: Unbounded query over the cap fails without a partial page

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log` with no `LIMIT` and the entity holds
  more than `flight.max-rows` rows
- **THEN** `getFlightInfo` succeeds, the `doGet` stream fails with `INVALID_ARGUMENT` naming the cap and
  the remedies, and no rows are delivered to the client

#### Scenario: Unbounded aggregate succeeds

- **WHEN** a client runs `SELECT count(*) AS n FROM dial_usage_log` with no `LIMIT` over an entity of any
  size
- **THEN** the single aggregate row is returned and no error is raised, because one row cannot exceed the
  cap

#### Scenario: Probe row never reaches the client

- **WHEN** an unbounded query's result is exactly `flight.max-rows` rows
- **THEN** exactly `flight.max-rows` rows are streamed — the extra row requested to detect truncation is
  never part of the result

#### Scenario: Explicit limit stops at the bound

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log LIMIT 7` where far more rows match
- **THEN** exactly 7 rows are streamed and no truncation error is raised

#### Scenario: Offset without limit is bounded, not rejected

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log ORDER BY total_tokens OFFSET 5` with no
  `LIMIT`
- **THEN** the query executes under the implicit cap instead of being rejected

#### Scenario: Configured cap is honored in both directions

- **WHEN** `flight.max-rows` is configured to 5 and a client runs an unbounded query over an entity
  holding 6 rows, and separately runs `SELECT … LIMIT 6`
- **THEN** the unbounded query fails naming 5, and the explicit `LIMIT 6` is rejected for exceeding 5

#### Scenario: REST keeps the shared governance

- **WHEN** the same unbounded statement is sent to `POST /v1/queries/execute-sql` over an entity holding
  more than 100 rows
- **THEN** it succeeds and returns the shared default of 100 rows, unchanged by the Flight cap

### Requirement: Flight SQL server lifecycle and configuration
The service SHALL run an Arrow Flight SQL server on a dedicated gRPC port when and only when
`flight.enabled` is true (default false). The port SHALL default to 32010 and SHALL be
configurable via `flight.port`; port 0 SHALL bind an ephemeral port (for tests). The server
SHALL start after the application context is ready and SHALL shut down cleanly on context
close, releasing all Arrow allocator memory. The transport's row ceiling SHALL be configurable via
`flight.max-rows` (default 10000). All `flight.*` properties SHALL be env-driven
(`${ENV_VAR:default}`) with descriptions documented only in `docs/configuration.md`.

#### Scenario: Disabled by default
- **WHEN** the service starts with default configuration
- **THEN** no gRPC listener is opened and no Flight components hold resources

#### Scenario: Enabled startup
- **WHEN** the service starts with `flight.enabled=true` and `flight.port=32010`
- **THEN** a Flight SQL server accepts gRPC connections on port 32010

#### Scenario: Clean shutdown
- **WHEN** the application context closes while the Flight server is running
- **THEN** the server stops and the root Arrow allocator reports zero allocated bytes
