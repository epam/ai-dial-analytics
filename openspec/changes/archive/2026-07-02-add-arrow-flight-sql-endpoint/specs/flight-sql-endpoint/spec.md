# flight-sql-endpoint

Arrow Flight SQL transport for ADAS: a gRPC listener that accepts ad-hoc SQL `SELECT`
statements, translates them to the structured query DSL (see `sql-query-translation`), executes
them through the existing structured-query pipeline, and streams results back as Arrow record
batches.

## ADDED Requirements

### Requirement: Flight SQL server lifecycle and configuration
The service SHALL run an Arrow Flight SQL server on a dedicated gRPC port when and only when
`flight.enabled` is true (default false). The port SHALL default to 32010 and SHALL be
configurable via `flight.port`; port 0 SHALL bind an ephemeral port (for tests). The server
SHALL start after the application context is ready and SHALL shut down cleanly on context
close, releasing all Arrow allocator memory. All `flight.*` properties SHALL be env-driven
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

### Requirement: Ad-hoc statement execution via getFlightInfo and doGet
The endpoint SHALL support the Flight SQL ad-hoc statement flow: `getFlightInfo` with a SQL
statement SHALL translate and validate the query and return a `FlightInfo` carrying the Arrow
result schema and a single endpoint with an opaque ticket, WITHOUT executing the query against
ClickHouse. `doGet` with that ticket SHALL re-run the full translate → validate → execute
pipeline and stream the result; the server SHALL NOT trust any client-supplied state beyond the
SQL statement in the ticket. Prepared-statement, DML, DDL, and transaction RPCs SHALL NOT be
supported and SHALL return an UNIMPLEMENTED or INVALID_ARGUMENT status.

#### Scenario: getFlightInfo returns schema without executing
- **WHEN** a client calls getFlightInfo with `SELECT deployment, count(*) AS n FROM dial_usage_log GROUP BY deployment`
- **THEN** the response carries an Arrow schema with fields `deployment: Utf8` and `n: Int64` and no query is executed against ClickHouse

#### Scenario: doGet executes and streams
- **WHEN** the client calls doGet with the ticket from getFlightInfo
- **THEN** the query executes through the structured-query pipeline and the stream delivers Arrow record batches followed by stream completion

#### Scenario: Prepared statement rejected
- **WHEN** a client issues a CreatePreparedStatement action
- **THEN** the server responds with an UNIMPLEMENTED status

### Requirement: Execution reuses structured-query governance
Every Flight query SHALL execute by constructing a `StructuredQuery` and invoking the same
execution path as `POST /v1/queries/execute`. All structured-query governance SHALL apply
unchanged: entity and field validation, the closed function catalog, enrichment join rules,
default limit 100, and the limit clamp of 1000 rows. A SQL `LIMIT` above the clamp SHALL be
clamped, not rejected. A SQL query with no `LIMIT` SHALL receive the default limit.

#### Scenario: Limit clamped
- **WHEN** a client runs `SELECT request_id FROM dial_usage_log LIMIT 50000` via Flight
- **THEN** at most 1000 rows are returned, matching REST behavior for `limit: 50000`

#### Scenario: Same rows as REST
- **WHEN** the same logical query is issued via Flight SQL and via `POST /v1/queries/execute`
- **THEN** both return the same rows and values (modulo JSON vs Arrow encoding)

### Requirement: Arrow result schema and type mapping
Result columns SHALL be mapped to Arrow types by a single normative mapping: string→Utf8,
integer→Int32, long→Int64, decimal→Decimal128(38,9), boolean→Bool, date→DateDay,
timestamp→Timestamp(MILLI, UTC), uuid→Utf8. All fields SHALL be nullable. The schema returned
by `getFlightInfo` SHALL be identical to the schema of the `doGet` stream for the same
statement. Result rows SHALL be delivered in batches bounded by `flight.batch-rows` and
`flight.batch-max-bytes`. NULL cells (including unmatched enrichment LEFT JOIN columns) SHALL
be preserved as Arrow nulls.

#### Scenario: Schema agreement
- **WHEN** getFlightInfo and doGet are called for the same statement
- **THEN** the doGet stream's schema equals the FlightInfo schema field-for-field

#### Scenario: Null preservation
- **WHEN** a selected enrichment column has no matching enrichment row
- **THEN** the corresponding Arrow cell is null, not absent and not a default value

### Requirement: Flight authentication mirrors service security mode
When `security.mode=oidc`, the Flight endpoint SHALL require authentication on every RPC:
a `Bearer` token validated by the same multi-issuer JWT/introspection machinery as HTTP, or an
`Api-Key` header validated by the same DIAL Core introspection (with the same cache and the
same bearer-wins precedence). Unauthenticated or rejected credentials SHALL yield gRPC
UNAUTHENTICATED; an unreachable introspection backend SHALL yield UNAVAILABLE. When
`security.mode=none`, the endpoint SHALL be open, matching HTTP behavior.

#### Scenario: Missing credentials rejected in oidc mode
- **WHEN** `security.mode=oidc` and a client calls getFlightInfo with no auth headers
- **THEN** the call fails with UNAUTHENTICATED

#### Scenario: Valid api-key accepted
- **WHEN** `security.mode=oidc` and a client presents an `Api-Key` header that DIAL Core resolves to a mapped role
- **THEN** the query proceeds

#### Scenario: Open in none mode
- **WHEN** `security.mode=none`
- **THEN** Flight RPCs succeed without credentials

### Requirement: Concurrency and memory governance
Concurrent `doGet` executions SHALL be capped by `flight.max-concurrent-streams` (default 4);
a request arriving at saturation SHALL fail fast with RESOURCE_EXHAUSTED before any ClickHouse
call. Each request SHALL allocate Arrow memory from a bounded per-request child allocator
(`flight.request-memory-limit-bytes`, default 64 MiB) that is always closed when the request
ends, including on error and client cancellation.

#### Scenario: Saturation fails fast
- **WHEN** max-concurrent-streams doGet calls are in flight and another arrives
- **THEN** the new call fails with RESOURCE_EXHAUSTED without querying ClickHouse

#### Scenario: No allocator leak on error
- **WHEN** a doGet fails mid-stream or is cancelled by the client
- **THEN** the per-request child allocator is closed and no Arrow memory remains allocated

### Requirement: Error mapping to gRPC statuses
Failures SHALL map to stable gRPC statuses without leaking raw backend messages: SQL parse
errors, unsupported SQL constructs, and structured-query validation failures → INVALID_ARGUMENT;
unknown entity or column → INVALID_ARGUMENT; ClickHouse query rejection → INVALID_ARGUMENT;
ClickHouse connectivity failure → UNAVAILABLE; authentication failure → UNAUTHENTICATED;
concurrency saturation → RESOURCE_EXHAUSTED; any unexpected failure → INTERNAL with a redacted
message.

#### Scenario: Unsupported SQL construct
- **WHEN** a client runs `SELECT a + b FROM dial_usage_log`
- **THEN** the call fails with INVALID_ARGUMENT and a message identifying the unsupported construct, and ClickHouse is not contacted

#### Scenario: ClickHouse down
- **WHEN** ClickHouse is unreachable during doGet
- **THEN** the stream fails with UNAVAILABLE and no raw driver message is exposed
