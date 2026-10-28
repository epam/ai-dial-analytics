# flight-sql-endpoint Specification

## Purpose

The Arrow Flight SQL transport for ADAS: a gRPC listener (default port 32010, disabled by default)
that accepts ad-hoc SQL `SELECT` statements, translates them to the structured query DSL (see
[sql-query-translation](../sql-query-translation/spec.md)), executes them through the existing
structured-query pipeline, and streams results back as typed Arrow record batches. All
structured-query governance applies unchanged except the row ceiling, which is this transport's own:
`flight.max-rows` (default 10000) replaces the shared 1000-row maximum and bounds the rows returned,
never the size of the `LIMIT` a client may write. A statement with no `LIMIT`, or one above the cap, is
clamped to the cap and runs asking for one row beyond it, failing only if that row appears, proving the
result was truncated — so a truncated page is never returned and no statement is rejected for the size
of its `LIMIT`. Authentication mirrors the service `security.mode`.

Catalog discovery is supported two ways, because clients split on which one they use. The metadata RPCs
(`GetCatalogs`, `GetDbSchemas`, `GetTables` including `include_schema`, `GetTableTypes`,
`GetXdbcTypeInfo`, `GetSqlInfo`) stream from the same catalog as the `/v1/queries` discovery endpoints,
under the same per-caller sensitive-column filtering; a client that discovers through catalog SQL
instead queries the `INFORMATION_SCHEMA` relations `CommandStatementQuery` serves (see "Catalog metadata
statements stream over the statement path"). On the RPC surface ADAS publishes no catalog and no schema namespace, and says so
on the wire through `GetSqlInfo` (see "Capability advertisement declares no catalog and no schema
namespace"), so an RPC-driven client generates unqualified names. That is a statement about what the RPCs
declare, not the limit of what the SQL front end accepts: every entity also resolves qualified by the
data schema name the `INFORMATION_SCHEMA` rows carry (see
[sql-query-translation](../sql-query-translation/spec.md)), so a catalog-SQL client's qualified
statements resolve identically. Still unsupported, and rejected at `getFlightInfo`
rather than mid-stream: prepared statements, the key-metadata RPCs (`GetPrimaryKeys`,
`GetExportedKeys`, `GetImportedKeys`, `GetCrossReference`), DML/DDL, transactions, and TLS on the
listener.

## Requirements
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

### Requirement: Ad-hoc statement execution via getFlightInfo and doGet
The endpoint SHALL support the Flight SQL ad-hoc statement flow: `getFlightInfo` with a SQL
statement SHALL translate and validate the query and return a `FlightInfo` carrying the Arrow
result schema and a single endpoint with an opaque ticket, WITHOUT executing the query against
ClickHouse. `doGet` with that ticket SHALL re-run the full translate → validate → execute
pipeline and stream the result; the server SHALL NOT trust any client-supplied state beyond the
SQL statement in the ticket. Prepared-statement, DML, DDL, and transaction RPCs SHALL NOT be
supported and SHALL return an UNIMPLEMENTED or INVALID_ARGUMENT status. Catalog metadata RPCs ARE
supported (see "Catalog metadata RPCs stream from the query catalog"); the key-metadata RPCs are not.

#### Scenario: getFlightInfo returns schema without executing
- **WHEN** a client calls getFlightInfo with `SELECT deployment, count(*) AS n FROM dial_usage_log GROUP BY deployment LIMIT 100`
- **THEN** the response carries an Arrow schema with fields `deployment: Utf8` and `n: Int64` and no query is executed against ClickHouse

#### Scenario: doGet executes and streams
- **WHEN** the client calls doGet with the ticket from getFlightInfo
- **THEN** the query executes through the structured-query pipeline and the stream delivers Arrow record batches followed by stream completion

#### Scenario: Prepared statement rejected
- **WHEN** a client issues a CreatePreparedStatement action
- **THEN** the server responds with an UNIMPLEMENTED status

### Requirement: Execution reuses structured-query governance

Every Flight query SHALL execute through the same execution path as `POST /v1/queries/execute`: a
single-block statement by constructing a `StructuredQuery`, a composite statement (one that joins, reads a
derived table or CTE, carries a subquery, or combines branches with a set operation — see the
sql-query-translation contract) by executing the
composite plan the shared front end produces. All structured-query governance SHALL apply unchanged with
one exception, the row ceiling: entity and field validation in every block, the closed function catalog,
sensitive-column masking, `final` deduplication, the query budget and enrichment join rules apply as-is,
while the maximum number of rows a Flight query may return is the Flight transport's own configured cap
`flight.max-rows` (default 10000) rather than the shared 1000-row maximum. That the Flight ceiling exceeds
the REST ceiling is deliberate: Flight streams Arrow record batches with per-request Arrow memory bounded
by `flight.request-memory-limit-bytes` and concurrency bounded by `flight.max-concurrent-streams`, whereas
the REST surfaces materialize a JSON document. For a composite statement the ceiling, the clamp and the
truncation probe below apply to the **outermost** block; an inner block's `LIMIT`/`OFFSET` is executed as
written and an inner block without one is not bounded. For a set-operation statement the outermost block
is the combined result, so the cap, the clamp and the probe count the rows the branches produce together.

`flight.max-rows` is a **ceiling on the number of rows the endpoint returns**, not a constraint on the
`LIMIT` literal a client may write. No statement SHALL be rejected for the size of its `LIMIT`
(**BREAKING**: superseding the rule that a `LIMIT` above `flight.max-rows` is rejected with
`INVALID_ARGUMENT` naming the requested limit and the cap). A `LIMIT` far above the cap is how BI tools
express a self-protection bound — Power BI's DirectQuery generator emits `LIMIT 1000001` on results of
a few dozen rows — and refusing it blocks queries the endpoint can answer while protecting nothing.

The governing distinction is therefore whether the client stated a bound **that ADAS can honour**:

- A statement with an explicit `LIMIT n` where `n <= flight.max-rows` SHALL return up to `n` rows with
  no truncation error and no probe: the caller asked for a bound, so stopping at it is not a surprise.
- Every other statement — one whose `LIMIT` was not stated (no `LIMIT` at all, or an `OFFSET` with no
  `LIMIT`), **or one whose explicit `LIMIT` exceeds `flight.max-rows`** — SHALL be executed under an
  implicit bound of `flight.max-rows`, with its effective limit clamped to the cap. Such a statement
  SHALL NOT be rejected for lacking a `LIMIT` or for stating an oversized one (**BREAKING**:
  superseding `flight-sql-catalog-metadata`, which rejected every statement without an explicit
  `LIMIT`; that rule blocked results that cannot truncate, such as `SELECT count(*)`, and every query a
  BI tool's DirectQuery generator emits).

Whether the limit was stated SHALL be carried explicitly by the translation and SHALL NOT be inferred
from the limit's value; whether a stated limit can be honoured SHALL be decided by comparing it to the
configured cap, so a non-default `flight.max-rows` governs both the clamp and the ceiling.

A statement executed under the implicit bound SHALL be executed requesting one row more than the cap,
and:

- if more than `flight.max-rows` rows are returned, the call SHALL fail with `INVALID_ARGUMENT` naming
  the configured cap and both remedies (add an explicit `LIMIT`, or aggregate), and SHALL NOT stream a
  partial page;
- otherwise the rows SHALL be streamed, and the extra probe row SHALL NEVER be visible to the client.

Any `OFFSET` the statement carried SHALL be preserved when the limit is clamped.

Because `getFlightInfo` validates without executing, a truncation failure can only surface at `doGet`:
`getFlightInfo` SHALL succeed for such a statement and the stream SHALL then fail. Consequently a
query's success depends on the data as well as the statement — a result that fits the cap today may
exceed it later — and a client that supplies an explicit `LIMIT` within the cap is not subject to it.
The clamp SHALL be applied on the `doGet` ticket path as well as at `getFlightInfo`, because the ticket
carries client-supplied SQL and a hand-forged ticket must not reach execution unbounded.

The REST surfaces are unaffected: `POST /v1/queries/execute-sql`, `POST /v1/queries/execute`, and the
translation endpoints SHALL keep the shared default limit of 100 and maximum of 1000, including their
existing silent truncation of an unbounded query to the default and their rejection of an explicit
`limit` above the shared maximum. The clamp specified here SHALL NOT be extended to them: it is a
property of a streaming transport whose clients write guard rails, not of a caller hand-writing a page
size.

#### Scenario: Limit above the configured cap is clamped, not rejected

- **WHEN** a client runs the Power BI DirectQuery statement
  `select "deployment", "C1" from (select "deployment", SUM("total_price") as "C1" from "adas"."dial_usage_log" group by "deployment") as "ITBL" where not "C1" is null LIMIT 1000001 OFFSET 0`
  via Flight with `flight.max-rows` at its default, and the aggregate produces fewer rows than the cap
- **THEN** the call succeeds and every result row is streamed — the `LIMIT` literal alone causes no
  error

#### Scenario: Clamped limit still fails on an oversized result

- **WHEN** a client runs a statement whose explicit `LIMIT` exceeds `flight.max-rows` and whose result
  exceeds `flight.max-rows`
- **THEN** `getFlightInfo` succeeds, the `doGet` stream fails with `INVALID_ARGUMENT` naming the cap and
  the remedies, and no rows are delivered to the client

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

- **WHEN** a query executed under the implicit bound — whether it stated no `LIMIT` or one above the cap
  — returns exactly `flight.max-rows` rows
- **THEN** exactly `flight.max-rows` rows are streamed; the extra row requested to detect truncation is
  never part of the result

#### Scenario: Explicit limit stops at the bound

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log LIMIT 7` where far more rows match
- **THEN** exactly 7 rows are streamed and no truncation error is raised

#### Scenario: Offset survives the clamp

- **WHEN** a client runs a statement with an explicit `LIMIT` above the cap and a non-zero `OFFSET`
- **THEN** the offset is applied and the result is bounded by the cap, exactly as it would be for the
  same statement with no `LIMIT`

#### Scenario: Offset without limit is bounded, not rejected

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log ORDER BY total_tokens OFFSET 5` with no
  `LIMIT`
- **THEN** the query executes under the implicit cap instead of being rejected

#### Scenario: Configured cap governs the clamp

- **WHEN** `flight.max-rows` is configured to 5 and a client runs, over an entity holding 6 rows, an
  unbounded query and separately `SELECT … LIMIT 6`
- **THEN** both are executed under the bound of 5 and both fail naming 5, because the result exceeds the
  cap — the explicit `LIMIT 6` is not rejected for its literal but for the size of its result

#### Scenario: REST keeps the shared governance

- **WHEN** the same unbounded statement is sent to `POST /v1/queries/execute-sql` over an entity holding
  more than 100 rows
- **THEN** it succeeds and returns the shared default of 100 rows, unchanged by the Flight cap

#### Scenario: REST still rejects an oversized explicit limit

- **WHEN** a client sends `SELECT project_id FROM dial_usage_log LIMIT 50000` to
  `POST /v1/queries/execute-sql`
- **THEN** it fails with the shared page-limit error naming 50000 and 1000 — the Flight clamp does not
  apply to REST

#### Scenario: Composite statement streams under the same cap

- **WHEN** a client runs `SELECT a.project_id, b.rate FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id` via Flight with no `LIMIT`
- **THEN** `getFlightInfo` reports the two result columns with their Arrow types, the stream is bounded
  by `flight.max-rows` with the same truncation probe as a single-entity statement, and the rows are
  streamed

#### Scenario: Composite statement failures map like single-entity ones

- **WHEN** a client runs a join whose `ON` carries no equality, or a correlated subquery, via Flight
- **THEN** `getFlightInfo` fails with `INVALID_ARGUMENT` carrying the translator's message, and nothing
  is executed

#### Scenario: Set-operation statement streams under the same cap

- **WHEN** a client runs `SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics` via
  Flight with no `LIMIT`
- **THEN** `getFlightInfo` reports the single result column with its Arrow type, the stream is bounded by
  `flight.max-rows` with the same truncation probe applied to the combined result, and the rows are
  streamed

### Requirement: Arrow result schema and type mapping

Result columns SHALL be mapped to Arrow types by a single normative mapping: string→Utf8,
integer→Int32, long→Int64, decimal→Decimal128(38,9), boolean→Bool, date→DateDay,
timestamp→Timestamp(MILLI, UTC), uuid→Utf8. An `array` result column SHALL map to
Arrow `List` with a single child field named `item` whose type is the element type's scalar
mapping from the same table (e.g. an array of `string` → `List<item: Utf8>`); array cells
SHALL be written as list values whose elements use the same value coercion as the corresponding
scalar kind, an empty array SHALL be encoded as an empty (non-null) list, and a NULL cell as a
null list. An `object` result column SHALL map to Arrow `Utf8` carrying the value's JSON
serialization as text (Arrow has no schemaless-JSON type): each cell SHALL carry exactly the JSON
the REST surfaces produce for that same cell, serialized with the same mapper, so the two surfaces
never disagree about a value — an object cell therefore appears as a JSON object literal, never as
a Java-style `toString` rendering — and a NULL cell SHALL be an Arrow null. All fields SHALL be nullable. The schema returned
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

#### Scenario: Array column streams as a List vector
- **WHEN** a client runs `SELECT execution_path FROM dial_usage_log LIMIT 5` via Flight SQL
- **THEN** getFlightInfo advertises `execution_path` as `List<item: Utf8>`, and the doGet stream delivers each row's value as an Arrow list of Utf8 elements matching the JSON endpoint's values for the same query

#### Scenario: Empty array is an empty list, not null
- **WHEN** a streamed row's array cell is an empty ClickHouse array
- **THEN** the Arrow cell is a non-null list of length zero

#### Scenario: Object column streams as JSON text
- **WHEN** a client runs `SELECT jwt_claims FROM dial_usage_log LIMIT 5` via Flight SQL and a row's value is a nested object
- **THEN** getFlightInfo advertises `jwt_claims` as `Utf8`, and the doGet stream delivers that cell as a JSON document string that parses back to the same value the JSON endpoint returns for the same query

#### Scenario: Empty object is an empty JSON document
- **WHEN** a streamed row's object cell holds an empty object
- **THEN** the Arrow cell is the non-null string `{}`

### Requirement: Flight authentication mirrors service security mode
When `security.mode=oidc`, the Flight endpoint SHALL require authentication on every RPC — the
`Handshake` RPC included — and SHALL accept three client-presented credential forms, plus the marked
bearer envelope specified further below that the endpoint itself hands out in answer to a `Basic`
handshake:

1. `authorization: Bearer <token>`, validated by the same multi-issuer JWT/introspection machinery as
   HTTP — except for a value carrying the `adas-api-key:` marker, which is routed to the API-key path
   instead and never presented to an identity provider;
2. `Api-Key: <key>`, validated by the same DIAL Core introspection with the same cache;
3. `authorization: Basic base64(<username>:<password>)`, where `<username>` SHALL be the fixed sentinel
   `api-key` (compared case-insensitively) and `<password>` SHALL be a DIAL API key, validated by that
   same Core introspection and cache.

Precedence SHALL be `Bearer` → `Api-Key` → `Basic`: a call carrying more than one form SHALL be
authenticated by the first present in that order and the others SHALL NOT be introspected. A `Basic`
credential SHALL be validated on **every** call that presents it, exactly as an `Api-Key` header is, and
the endpoint SHALL NOT issue a session token: no server-side credential exists that could outlive the
API key's revocation, and a revoked key SHALL stop working within the same
`security.api-key.cache-ttl-seconds` bound that governs the `Api-Key` header path.

A `Basic` credential whose username is anything other than the sentinel SHALL be rejected with
UNAUTHENTICATED and a message naming the accepted username, and SHALL NOT be introspected — it SHALL NOT
be silently ignored in favour of another credential form or of the password alone, because the
authenticated principal comes from introspecting the key and a caller who supplied a different username
must not be left believing they authenticated as that identity. A `Basic` value that is not valid base64,
or that decodes to a string containing no colon, SHALL be rejected with UNAUTHENTICATED without
disclosing which part was malformed.

So that clients which authenticate once per connection can work at all, a call authenticated by the
`Basic` form SHALL carry the response header `authorization: Bearer adas-api-key:<the same API key>`,
and the endpoint SHALL accept `authorization: Bearer adas-api-key:<key>` as a credential form equivalent
to `Api-Key: <key>`, routed to the same introspection path and stripped of the marker before validation.
This value SHALL NOT be a minted token: it carries the presented API key unchanged, has no independent
lifetime, is never stored server-side, and is re-introspected on every call that presents it. Absent
this response, Arrow-derived clients that authenticate with username/password retain no credential for
subsequent calls, and the Arrow Flight SQL ODBC drivers construct a gRPC metadata entry with a
zero-length key and abort the client process.

Unauthenticated or rejected credentials SHALL yield gRPC UNAUTHENTICATED; an unreachable introspection
backend SHALL yield UNAVAILABLE. When `security.mode=none`, the endpoint SHALL be open and SHALL accept
no credential form, matching HTTP behavior — no authenticator is registered, so `Basic` is neither
required nor inspected and no `authorization` response header is emitted. Authorization SHALL mirror the
HTTP `/v1/**` rule for every credential form alike, and that rule is now authentication plus a per-RPC
privilege check rather than a role test. The gate SHALL admit any authenticated principal that could be
authorized for something — one holding some privilege, or carrying some raw provider role a grant could
name — and SHALL yield gRPC PERMISSION_DENIED only for a principal holding no privilege **and** carrying
no provider role, for which no table is reachable by any route. Each RPC is then authorized by the
privilege it requires (see **The Flight surface authorizes per RPC by privilege**).

Testing the principal for an application role here is what this replaces, and it had to go: a caller
authorized entirely by per-table grants holds no global privilege, and it is exactly the caller — a BI
tool presenting a DIAL API key — that those grants exist for.

Because every credential form crosses the wire in a request header, an `oidc`-mode deployment SHALL
terminate TLS in front of the Flight listener.

#### Scenario: Missing credentials rejected in oidc mode
- **WHEN** `security.mode=oidc` and a client calls getFlightInfo with no auth headers
- **THEN** the call fails with UNAUTHENTICATED and the message names all three accepted credential forms

#### Scenario: Valid api-key accepted
- **WHEN** `security.mode=oidc` and a client presents an `Api-Key` header that DIAL Core resolves to a mapped role
- **THEN** the query proceeds

#### Scenario: Basic credential with the sentinel username is accepted
- **WHEN** `security.mode=oidc` and a client presents `authorization: Basic base64("api-key:<valid key>")` whose Core introspection resolves to a mapped role
- **THEN** the caller is authenticated as the key's principal with that principal's roles and the query proceeds, identically to presenting the same key in an `Api-Key` header

#### Scenario: Sentinel username is case-insensitive
- **WHEN** a client presents the same valid key with the username `API-KEY`, or `Api-Key`, instead of `api-key`
- **THEN** the credential is accepted exactly as the lowercase form is

#### Scenario: Wrong username is rejected, not ignored
- **WHEN** a client presents `authorization: Basic base64("someone@example.com:<valid key>")`
- **THEN** the call fails with UNAUTHENTICATED, the message names `api-key` as the accepted username, the key is not introspected, and the caller is not authenticated as the key's principal

#### Scenario: Invalid key in a Basic credential fails like the header path
- **WHEN** a client presents `authorization: Basic base64("api-key:<key Core rejects>")`
- **THEN** the call fails with UNAUTHENTICATED with the same message the `Api-Key` header path produces for a rejected key

#### Scenario: Basic-authenticated principal without an application role is denied
- **WHEN** a client presents a valid key via `Basic` that Core resolves with **no roles at all**, so the
  principal holds no privilege and carries no provider role
- **THEN** the call fails with PERMISSION_DENIED, as it does for the same key in an `Api-Key` header
- **AND WHEN** the key instead resolves to roles that map to no privilege
- **THEN** the call is admitted, because one of those raw roles may name it in a table's grant list

#### Scenario: Bearer wins over Basic
- **WHEN** a call carries both `authorization: Bearer <valid token>` and a `Basic` credential
- **THEN** the bearer token authenticates the call and the API key in the `Basic` credential is not introspected

#### Scenario: Handshake answers a Basic credential with the marked bearer value
- **WHEN** a client authenticates the `Handshake` RPC with the `Basic` form
- **THEN** the response carries `authorization: Bearer adas-api-key:<the presented key>`, and a subsequent call presenting that value authenticates as the same principal

#### Scenario: Marked bearer value is revalidated on every call
- **WHEN** a client authenticates with `authorization: Bearer adas-api-key:<key>` and the key subsequently stops introspecting successfully
- **THEN** calls made after the cached introspection expires fail with UNAUTHENTICATED — no session credential keeps the connection working

#### Scenario: Basic-authenticated call does not weaken the introspection cache
- **WHEN** the same valid key is presented repeatedly via `Basic` within the cache TTL
- **THEN** DIAL Core is introspected at most once for those calls, sharing the cache entry the `Api-Key` header path would use for that key

#### Scenario: Open in none mode
- **WHEN** `security.mode=none`
- **THEN** Flight RPCs succeed without credentials, a `Basic` header is neither required nor inspected, and no `authorization` response header is emitted

### Requirement: Concurrency and memory governance
Concurrent **statement** `doGet` executions SHALL be capped by `flight.max-concurrent-streams`
(default 4); a statement request arriving at saturation SHALL fail fast with RESOURCE_EXHAUSTED
before any ClickHouse call. Catalog metadata `doGet` calls SHALL NOT consume that gate — they never
reach ClickHouse, and gating them would let statement saturation break a driver's discovery on a call
it treats as infallible. Each request, statement or catalog, SHALL allocate Arrow memory from a
bounded per-request child allocator (`flight.request-memory-limit-bytes`, default 64 MiB) that is
always closed when the request ends, including on error and client cancellation.

#### Scenario: Saturation fails fast
- **WHEN** max-concurrent-streams statement doGet calls are in flight and another arrives
- **THEN** the new call fails with RESOURCE_EXHAUSTED without querying ClickHouse

#### Scenario: Catalog RPCs stay available at saturation
- **WHEN** max-concurrent-streams statement doGet calls are in flight and a catalog metadata doGet
  arrives
- **THEN** the catalog call succeeds and streams its rows, without waiting on the gate

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

### Requirement: Star projections omit heavy and enrichment columns in the Arrow schema and stream

The Flight SQL endpoint SHALL inherit the star expansion of the shared SQL front end (see the
`sql-query-translation` capability's "Star projection expands to the non-heavy source columns"). For a
`SELECT *` statement, `getFlightInfo` SHALL advertise an Arrow schema whose fields are exactly the
entity's **non-heavy source** columns, and the `doGet` stream SHALL carry those same fields — so an ADBC
or pandas client issuing the canonical `SELECT * FROM <entity> LIMIT <n>` never pays for a large payload
column, nor for an enrichment `LEFT JOIN`, it did not ask for (**BREAKING** as of the
heavy-column-projection change: a star previously advertised the entity's enrichment columns too). A
statement naming a `heavy` or enrichment column SHALL stream it unchanged, with the same Arrow type mapping as any other column of
that kind. The same Arrow schema SHALL be what `CommandGetTables` reports for that entity when
`include_schema` is set, so catalog metadata and the star statement cannot disagree.

A star whose expansion would be empty SHALL fail with `INVALID_ARGUMENT` naming the entity, at
`getFlightInfo` time, before ClickHouse is contacted, and SHALL do so whether or not the statement
carries a `LIMIT`. The pre-existing schema-agreement guarantee SHALL hold: the `doGet` stream's schema
SHALL equal the `FlightInfo` schema field-for-field for the same statement.

On `dial_usage_log` the two exclusions now coincide for the body columns: each is `heavy` **and** owned
by the `dial_usage_log_payload` enrichment, so a star omits it under either rule and the canonical
`SELECT * FROM dial_usage_log` reads only the small columns of a single table. The entity's
`object`-typed claim and header columns are source columns and are still advertised.

#### Scenario: FlightInfo schema for a star omits heavy fields

- **WHEN** an entitled client calls getFlightInfo with `SELECT * FROM <entity> LIMIT 1` for an entity
  one of whose own **source** columns is marked `heavy`
- **THEN** the advertised Arrow schema carries every non-heavy source column and not that field,
  and the doGet stream delivers exactly those fields

#### Scenario: A star over dial_usage_log advertises no body field

- **WHEN** an entitled client calls getFlightInfo with `SELECT * FROM dial_usage_log LIMIT 1`
- **THEN** the advertised schema carries no `request_body`, `response_body`, `assembled_response`,
  `jwt_claims` or `request_tags` field under any spelling, the executed query joins no enrichment,
  and `request_body_bytes` and `response_body_bytes` are present

#### Scenario: Star schema carries no enrichment field

- **WHEN** a client calls getFlightInfo with `SELECT * FROM <source> LIMIT 1` for a source with an
  attached enrichment
- **THEN** the advertised schema carries no `<enrichment>.<column>` field, and the executed query joins
  no enrichment

#### Scenario: Explicitly named heavy column streams normally

- **WHEN** the client runs `SELECT event_id, "dial_usage_log_payload.response_body" FROM dial_usage_log LIMIT 5`
- **THEN** the Arrow schema carries `dial_usage_log_payload.response_body` as `Utf8` and the stream
  delivers its values

#### Scenario: All-heavy star fails fast
- **WHEN** a client calls getFlightInfo with `SELECT * FROM <entity> LIMIT 1` whose every visible field
  is marked `heavy`
- **THEN** the call fails with `INVALID_ARGUMENT` and ClickHouse is not contacted

### Requirement: Catalog metadata RPCs stream from the query catalog

The endpoint SHALL implement the Flight SQL catalog RPCs end to end — both `getFlightInfo` and
`doGet` — for `CommandGetCatalogs`, `CommandGetDbSchemas`, `CommandGetTables`,
`CommandGetTableTypes`, and `CommandGetXdbcTypeInfo`, in addition to the already-supported
`CommandGetSqlInfo`. Each stream SHALL carry the Arrow schema the Flight SQL protocol fixes for that
command (`FlightSqlProducer.Schemas.*`).

Every row SHALL be derived from the same catalog that backs `GET /v1/queries/entities` and
`GET /v1/queries/entities/schema/{name}` (`CatalogQueryService`), so the RPC catalog and the REST
catalog cannot disagree about which entities exist or what columns they have.

ADAS exposes no catalog namespace. The **RPC** surface additionally reports no schema namespace:
`CommandGetCatalogs` and `CommandGetDbSchemas` SHALL stream zero rows, and every `CommandGetTables`
row SHALL carry a null `catalog_name` and a null `db_schema_name`. Zero rows alone does NOT tell a
client the namespace is absent, only that it is empty; the declaration that makes clients emit
unqualified names is in "Capability advertisement declares no catalog and no schema namespace".
`CommandGetTableTypes` SHALL stream exactly one row, `TABLE`: the queryable entities are the catalog's
ACTIVE `source` tables and nothing else.

This is deliberately **narrower than the SQL surface**, which does resolve a schema-qualified entity
name (`sql-query-translation`, "Entity names resolve schema-qualified as well as bare"). The
asymmetry is intentional and SHALL be preserved: the unqualified name is the form ADAS steers clients
toward, and `SQL_SCHEMA_TERM` is precisely the value that decides whether an RPC-driven client
qualifies the statements it generates. Declaring a schema here would change the SQL every such client
emits, for no gain to clients that discover through catalog SQL — those read their schema name from
`INFORMATION_SCHEMA` and never consult these RPCs. Accordingly, the SQL surface accepting a qualifier
SHALL NOT by itself be treated as a reason to start reporting one here.

`CommandGetTables` SHALL list exactly the queryable entities, in the catalog's stable alphabetical
order, and SHALL honor its filters: a `table_name_filter_pattern` and `db_schema_filter_pattern` as
SQL `LIKE` patterns (`%`, `_`, with `\` escaping either), matched **case-insensitively** to mirror the
SQL front end's case-insensitive identifier handling — which `GetSqlInfo` already advertises as
`SQL_CASE_SENSITIVITY_CASE_INSENSITIVE`, so a pattern that matches an entity name must also match the
identifier a client would then write; a `table_types` list (empty means no filter; a list not
containing `TABLE` matches nothing); and a `catalog` field that when omitted matches everything, when
empty matches objects without a catalog (all of them), and when non-empty matches nothing.

`INFORMATION_SCHEMA` relations SHALL NOT be listed by `CommandGetTables`, and `CommandGetTableTypes`
SHALL NOT gain a `SYSTEM_TABLE` row. These RPCs describe the entities a client may query for data;
the catalog relations are reachable through SQL, where they are marked `SYSTEM_TABLE` and where the
one connector that reads them filters that value out anyway.

When `include_schema` is set, each row's `table_schema` SHALL carry the IPC-serialized Arrow schema of
that entity's wildcard projection — its non-heavy source columns, typed by the same normative mapping
as query results — so the schema a client reads from the catalog is byte-for-byte the schema
`SELECT * FROM <entity>` advertises and streams. An entity whose wildcard expansion is empty SHALL
still be listed, with an empty Arrow schema.

`CommandGetXdbcTypeInfo` SHALL stream one row per scalar result-column family the Arrow type mapping
can produce (`VARCHAR`, `INTEGER`, `BIGINT`, `DOUBLE`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIMESTAMP`),
each reporting `NULLABILITY_NULLABLE`, and SHALL narrow to a single row when the command carries a
`data_type` filter. A `uuid` or `object` column surfaces as `VARCHAR`; `array` columns have no XDBC
type code and SHALL NOT be reported.

Catalog RPCs SHALL NOT contact ClickHouse. They SHALL allocate Arrow memory from a bounded
per-request child allocator that is always closed, and SHALL NOT consume the
`flight.max-concurrent-streams` gate, which governs ClickHouse execution only. A catalog RPC whose row
building fails after Arrow buffers have been taken SHALL release those buffers before the failure
reaches the client, so a failed metadata call leaves the root allocator's allocated-byte count
unchanged and clean shutdown still reports zero allocated bytes.

#### Scenario: Table list matches REST discovery

- **WHEN** a client calls `CommandGetTables` with `include_schema=false`
- **THEN** the stream carries one row per entity listed by `GET /v1/queries/entities`, in the same
  order, each with `table_type` `TABLE` and null `catalog_name` and `db_schema_name`

#### Scenario: Included table schema equals the star schema

- **WHEN** a client calls `CommandGetTables` with `include_schema=true` for `dial_usage_log`
- **THEN** the row's `table_schema` deserializes to an Arrow schema equal, field-for-field, to the
  schema `getFlightInfo` advertises for `SELECT * FROM dial_usage_log LIMIT 1` — carrying every
  non-heavy source column and neither payload field

#### Scenario: Empty catalog and schema listings

- **WHEN** a client calls `CommandGetCatalogs` or `CommandGetDbSchemas`
- **THEN** the call succeeds and the stream completes with zero rows

#### Scenario: RPC listings ignore the SQL schema namespace

- **WHEN** a client calls `CommandGetDbSchemas` or `CommandGetTables` after the SQL surface has begun
  resolving `adas`-qualified entity names
- **THEN** `CommandGetDbSchemas` still streams zero rows and every `CommandGetTables` row still carries
  a null `db_schema_name`

#### Scenario: Catalog relations are absent from the RPC listing

- **WHEN** a client calls `CommandGetTables` with no filter
- **THEN** no `INFORMATION_SCHEMA` relation is streamed, and `CommandGetTableTypes` still streams
  exactly the one row `TABLE`

#### Scenario: Table types

- **WHEN** a client calls `CommandGetTableTypes`
- **THEN** the stream carries exactly one row whose `table_type` is `TABLE`

#### Scenario: Table name filter

- **WHEN** a client calls `CommandGetTables` with `table_name_filter_pattern` `dial\_%`
- **THEN** only entities whose names match the pattern are streamed

#### Scenario: Table name filter is case-insensitive

- **WHEN** a client calls `CommandGetTables` with `table_name_filter_pattern` `DIAL_USAGE_LOG`
- **THEN** `dial_usage_log` is streamed, matching the case-insensitivity of the identifier the client
  would use in a statement against it

#### Scenario: Non-empty catalog filter matches nothing

- **WHEN** a client calls `CommandGetTables` with `catalog` set to a non-empty string
- **THEN** the call succeeds and the stream completes with zero rows

#### Scenario: Type info covers the Arrow type mapping

- **WHEN** a client calls `CommandGetXdbcTypeInfo` with no filter
- **THEN** the stream carries one row for each of `VARCHAR`, `INTEGER`, `BIGINT`, `DOUBLE`,
  `DECIMAL`, `BOOLEAN`, `DATE`, and `TIMESTAMP`, each nullable

#### Scenario: A failed catalog build releases its Arrow memory

- **WHEN** row building for a catalog RPC fails after Arrow buffers have been allocated — the catalog
  read fails, or an entity disappears from the catalog mid-request while `include_schema` is set
- **THEN** the call fails with an error and every buffer allocated for it is released, leaving the
  allocated-byte count of the allocator it was building on at zero

#### Scenario: Type info filtered by data type

- **WHEN** a client calls `CommandGetXdbcTypeInfo` with `data_type` set to the XDBC code for `BIGINT`
- **THEN** the stream carries only the `BIGINT` row

### Requirement: Capability advertisement declares no catalog and no schema namespace

`CommandGetSqlInfo` SHALL advertise an **empty** `SQL_CATALOG_TERM` and an **empty** `SQL_SCHEMA_TERM`,
together with `SQL_CATALOGS_SUPPORTED_ACTIONS` and `SQL_SCHEMAS_SUPPORTED_ACTIONS` carrying **no
supported actions**, so that a client can discover that neither namespace level exists and generates
unqualified table names. Both keys SHALL be present in the response when requested: an omitted key
leaves a client on its own default, which for schemas is "supported".

Declaring DDL support (`SQL_DDL_CATALOG`, `SQL_DDL_SCHEMA`) SHALL NOT be treated as satisfying this
requirement — it answers whether DDL can create such objects, not whether the namespace level exists.

An unadvertised `GetSqlInfo` value is **not** a default for a client — it fails the call. A requested
code the server holds no value for aborts the whole `CommandGetSqlInfo` response, so every other
value requested alongside it is lost too; the blast radius of one unanswered code is however many the
client asked for in that call. Beyond the namespace declaration above, `CommandGetSqlInfo` SHALL
therefore also advertise:

- **identifier and search metadata** — an empty `SQL_EXTRA_NAME_CHARACTERS` (no character beyond
  `[A-Za-z0-9_]` may appear in an *unquoted* identifier; the dotted enrichment form is addressable
  only double-quoted), a case-insensitive `SQL_QUOTED_IDENTIFIER_CASE` matching the front end's
  case-insensitive identifier handling, a `SQL_SEARCH_STRING_ESCAPE` of backslash matching the
  catalog RPCs' `LIKE` pattern escaping, and an empty `SQL_KEYWORDS` (ADAS reserves no keyword beyond
  standard SQL);
- **the absence of every construct the SQL front end rejects and every feature the endpoint does not
  implement** — no `CONVERT`, positioned commands, or transaction
  isolation levels (each an empty set), and `false` for stored procedures and call syntax, correlated
  subqueries, `SELECT FOR UPDATE`, batch updates, savepoints, named parameters, a `LIKE ESCAPE`
  clause, the integrity-enhancement facility, and non-nullable columns; an empty `SQL_PROCEDURE_TERM`
  and a `SQL_DEFAULT_TRANSACTION_ISOLATION` of none;
- **limits** — every `SQL_MAX_*` name-length and size value as `0`, the ODBC/JDBC encoding of "the
  source imposes no limit", which is ADAS's actual position: it validates neither identifier length
  nor statement size. `SQL_MAX_TABLES_IN_SELECT` SHALL be `1`, which is no longer a real bound — the
  join and set-operation requirements widened the single-entity `FROM` it was derived from — and is
  recorded below as an inherited under-claim rather than restated as a limit here;
- **set operations** — `SQL_SUPPORTED_UNIONS` SHALL declare `SQL_UNION` and `SQL_UNION_ALL`, both
  determined by the `sql-query-translation` set-operation requirement and non-steering because every
  spelling a client generates from them is accepted. `INTERSECT` and `EXCEPT` have no `SqlInfo` code, so
  their support is not advertisable and their absence from this list is not a claim about them;
- **transport facts** — forward-only result sets, no server-side query cancellation, no bulk
  ingestion, and no server-enforced statement or transaction timeout;
- **the capabilities ADAS does have** — column aliasing, table correlation names (with no rule that a
  correlation name differ from the table it names, so `SQL_SUPPORTS_DIFFERENT_TABLE_CORRELATION_NAMES`
  is `false`), standard `NULL + NULL IS NULL` semantics, the default null ordering
  (`SQL_NULL_ORDERING`), and that every table the catalog lists is selectable. `SQL_NULL_ORDERING`
  describes ClickHouse's placement rather than any behaviour ADAS specifies — the DSL exposes explicit
  `NULLS FIRST`/`LAST` without fixing a default — so it SHALL be determined by measurement against the
  backend and SHALL NOT be derived. `SQL_ALL_TABLES_ARE_SELECTABLE` SHALL hold only while the endpoint
  gates no entity's `SELECT`: it is a statement about the *absence* of per-table read authorization,
  not about the catalog listing being restricted to what the caller may read — the catalog filters
  columns, not entities. A change that introduces a per-table read grant SHALL flip this value in the
  same change;
- **three inherited under-claims, recorded rather than fixed** — `SQL_SUPPORTED_SUBQUERIES` and
  `SQL_OUTER_JOIN_SUPPORT_LEVEL` SHALL keep declaring the empty set, and `SQL_MAX_TABLES_IN_SELECT` the
  value `1`, even though subqueries, outer joins and multi-entity statements are all supported. Each
  under-claims in the safe direction (a client that believes it generates simpler SQL that ADAS
  accepts), each was left behind by the change that widened the subset rather than introduced here, and
  correcting them means one derivation across all three — Flight's subquery bitmask distinguishes
  comparison, `EXISTS`, `IN` and quantified forms and the last is still rejected — which is a change of
  its own. Their values SHALL NOT be read as statements that the constructs are rejected, and a client
  reading `SQL_MAX_TABLES_IN_SELECT` alongside the union declaration above SHALL NOT be assumed to have
  been told anything coherent about how many entities a statement may name;
- **two deliberate under-claims** — `SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY` and
  `SQL_SUPPORTS_ORDER_BY_UNRELATED` SHALL be advertised as `false` even though ADAS supports each in
  part: `ORDER BY count(*)` resolves through a select output and works while `ORDER BY price * 2` is
  rejected as arithmetic, and ordering by an unselected column works in row mode but not in aggregate
  mode. `false` steers a client to name the output column, which always works; `true` would let it
  generate the half that does not. Together with the two inherited under-claims above, these are the
  only values this requirement declares narrower
  than reality, and they SHALL NOT be widened on the observation that part of the construct succeeds —
  only evidence that the rejected half is gone, or a measurement of how clients act on a partial
  answer, justifies flipping them.

Which values are admissible SHALL be governed by a rule, so that the set can be extended without
re-deriving the analysis: a value SHALL be advertised only when its answer follows from behaviour
this capability or `sql-query-translation` already specifies (or from the total absence of a
feature) or from a measurement of the backend pinned by a test, **and** it cannot cause a client to
generate SQL the front end then rejects. That admits a negative declaration, a limit of `0`, a
positive declaration covered by a test, and a **conservative `false` on a partially supported
feature** where the narrower answer steers the client to a form that always works. Every such
under-claim SHALL be recorded here with its reason, so that a later reader re-derives neither the
value nor the omission. A value that fails both the determinacy and the non-steering test SHALL be
left unadvertised rather than guessed.

The **function-support sets** — `SQL_STRING_FUNCTIONS`, `SQL_NUMERIC_FUNCTIONS`,
`SQL_SYSTEM_FUNCTIONS`, `SQL_DATETIME_FUNCTIONS` — and the grammar-level claims
`SQL_SUPPORTED_GRAMMAR`, `SQL_ANSI92_SUPPORTED_LEVEL`, and `SQL_SUPPORTED_GROUP_BY` SHALL NOT be
advertised under this requirement. They are claims about what ADAS can *execute*, and a client that
believes one folds an expression into a generated statement: over-claiming converts a computation
that succeeded locally into a rejected query. Advertising them requires mapping each set against the
closed function catalog and measuring how clients act on a partial answer, which is a separate change.
This withholding is a deliberate trade rather than a neutral omission — because an unanswered code
fails the whole call, a client that asks for a function set alongside supported values loses those
too. A broken *query* is worse than broken *discovery*, and discovery already fails today, so the
trade holds; it is recorded here so it is re-decided rather than inherited.

Every value this requirement declares as **supported** SHALL be covered by a test that exercises the
behaviour through a statement, not by a declaration alone; a value whose answer depends on the
backend rather than on ADAS SHALL be pinned by a test that fails if the backend's behaviour changes.
Where the front end makes a declared value **unobservable** — `SQL_NULL_PLUS_NULL_IS_NULL`, which no
statement can reach because arithmetic is rejected outright — a wire-level assertion on the advertised
value SHALL suffice, and the value SHALL be named here so the exemption is enumerated rather than
open-ended.

#### Scenario: Schema namespace declared absent

- **WHEN** a client requests `SQL_SCHEMA_TERM` and `SQL_SCHEMAS_SUPPORTED_ACTIONS` via
  `CommandGetSqlInfo`
- **THEN** both are present in the stream, the term is the empty string, and the supported-actions
  bitmask is zero — so an ODBC client reports `SQL_SCHEMA_TERM=''` and `SQL_SCHEMA_USAGE=0`

#### Scenario: Catalog namespace declared absent

- **WHEN** a client requests `SQL_CATALOG_TERM` and `SQL_CATALOGS_SUPPORTED_ACTIONS`
- **THEN** both are present, the term is the empty string, and the supported-actions bitmask is zero

#### Scenario: Generated statements carry no qualifier

- **WHEN** a metadata-driven client opens a table from a navigator after reading `GetSqlInfo` and
  `GetTables`
- **THEN** the statement it generates names the entity unqualified (`FROM "rate_analytics"`), never
  qualified by an empty identifier (`FROM ""."rate_analytics"`), which the SQL front end would reject as
  a parse error

#### Scenario: Identifier metadata answers instead of erroring

- **WHEN** a client requests `SQL_EXTRA_NAME_CHARACTERS` — the value an ODBC driver surfaces as
  `SQL_SPECIAL_CHARACTERS`, and one that previously failed the `SQLGetInfo` call outright
- **THEN** the stream carries the value as the empty string, and the same call for
  `SQL_QUOTED_IDENTIFIER_CASE`, `SQL_SEARCH_STRING_ESCAPE`, and `SQL_KEYWORDS` likewise returns a
  value rather than nothing

#### Scenario: Rejected constructs are declared absent

- **WHEN** a client requests `SQL_SUPPORTS_CONVERT`, `SQL_SUPPORTED_POSITIONED_COMMANDS`, and
  `SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE`
- **THEN** each is present and declares no support, matching the SQL front end, which rejects a
  `CONVERT`, a positioned command, and a `LIKE … ESCAPE` clause before contacting ClickHouse

#### Scenario: Single-entity FROM is advertised as a one-table limit

- **WHEN** a client requests `SQL_MAX_TABLES_IN_SELECT`
- **THEN** the value is `1`, an inherited under-claim this requirement records: a join or a set
  operation may in fact name any number of entities

#### Scenario: Union support is declared

- **WHEN** a client requests `SQL_SUPPORTED_UNIONS`
- **THEN** the bitmask declares `SQL_UNION` and `SQL_UNION_ALL`, and a statement generated from that
  answer (`… UNION SELECT …`, `… UNION ALL SELECT …`) streams successfully rather than failing with
  `INVALID_ARGUMENT`

#### Scenario: Subquery and outer-join support stay under-claimed

- **WHEN** a client requests `SQL_SUPPORTED_SUBQUERIES` and `SQL_OUTER_JOIN_SUPPORT_LEVEL`
- **THEN** both are present and empty, which is an under-claim recorded in this requirement and not a
  statement that the front end rejects a subquery or a `LEFT JOIN` — both execute

#### Scenario: Unbounded name lengths are declared as no limit

- **WHEN** a client requests the `SQL_MAX_*` identifier-length values — table name, column name,
  catalog name, schema name, cursor name
- **THEN** each is present and zero, the encoding for "no limit imposed", because ADAS never rejects
  an identifier for its length

#### Scenario: A declared capability is exercised, not merely asserted

- **WHEN** `SQL_SUPPORTS_TABLE_CORRELATION_NAMES` is advertised as supported
- **THEN** a statement using a table correlation name (`SELECT t.deployment FROM dial_usage_log t`)
  translates and streams successfully, so the advertisement matches executable behaviour

#### Scenario: Every listed table is selectable by the caller it was listed to

- **WHEN** `SQL_ALL_TABLES_ARE_SELECTABLE` is advertised as supported and a non-admin caller reads the
  entity names `GetTables` streams to it
- **THEN** a statement selecting from each of those names, issued with the same credentials, streams
  successfully — the declaration is checked against executed statements rather than inferred from the
  catalog's filtering, which narrows columns and not entities

#### Scenario: Partially supported ORDER BY forms are under-claimed on purpose

- **WHEN** a client requests `SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY` and
  `SQL_SUPPORTS_ORDER_BY_UNRELATED`
- **THEN** both are present and `false`, even though `ORDER BY count(*)` resolves and ordering by an
  unselected column works in row mode — the conservative answer keeps a client from folding the
  arithmetic and aggregate-mode forms the front end rejects, and it stays `false` until the rejected
  half is gone

#### Scenario: Default null ordering is measured against the backend

- **WHEN** `SQL_NULL_ORDERING` is advertised
- **THEN** its value matches the placement an actual sort of a nullable column with no explicit
  `NULLS FIRST`/`LAST` produces, so the declaration fails here if the backend's default changes rather
  than misleading a client

#### Scenario: An unadvertised code fails the whole call

- **WHEN** a client requests `SQL_DATETIME_FUNCTIONS` — deliberately still unadvertised — alongside
  codes ADAS does supply
- **THEN** the call fails rather than returning the supported values with the unsupported one
  omitted, which is the cost the function-set exclusion knowingly accepts and the reason the excluded
  set is kept as small as the admissibility rule allows

### Requirement: Catalog metadata honors the caller's column visibility

Catalog RPCs SHALL resolve the catalog under the Flight caller's identity, exactly as the statement
path does, so a column hidden from the caller by sensitive-column filtering is absent from the
metadata as well as from query results. A caller without `TABLE_SENSITIVE` on the table that owns the column SHALL NOT be able to learn
from
any catalog RPC that a `sensitive` column exists. When `security.mode=none` the filtering fails open,
matching the query path.

#### Scenario: Sensitive columns absent from a read-only caller's table schema

- **WHEN** `security.mode=oidc` and a caller holding `TABLE_READ` but not `TABLE_SENSITIVE` calls
  `CommandGetTables` with
  `include_schema=true` for `dial_usage_log`, whose `jwt_claims` and `request_tags` columns are
  marked `sensitive`
- **THEN** the returned Arrow schema carries neither `jwt_claims` nor `request_tags`, while a
  caller entitled to that table's sensitive columns receives a schema for the same entity carrying both

### Requirement: Catalog metadata statements stream over the statement path

`CommandStatementQuery` SHALL accept a statement against `INFORMATION_SCHEMA` and stream its rows,
using the same Arrow type mapping and batching as a data query, so that a client which discovers
through catalog SQL can complete discovery over Flight alone.

`getFlightInfo` SHALL advertise the result schema of such a statement without executing it, exactly as
it does for a data statement, so the schema a client reads matches what `doGet` streams.

Because the rows are held in process, a metadata statement SHALL NOT consume the
`flight.max-concurrent-streams` gate and SHALL NOT be subject to `flight.max-rows` — neither the
explicit-limit ceiling nor the unbounded-result truncation probe. Its size is bounded by the catalog,
and failing discovery because ClickHouse streams are saturated is the behaviour "Catalog RPCs stay
available at saturation" already rejects for the RPC path.

#### Scenario: Metadata statement streams

- **WHEN** a client executes `SELECT DISTINCT TABLE_SCHEMA FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_TYPE <> 'SYSTEM_TABLE' ORDER BY TABLE_SCHEMA` over `CommandStatementQuery`
- **THEN** `getFlightInfo` advertises a one-column character schema and `doGet` streams the data
  schema name

#### Scenario: Metadata statement is exempt from the row cap

- **WHEN** a client executes an unbounded `SELECT` against `INFORMATION_SCHEMA.COLUMNS` that returns
  more rows than `flight.max-rows`
- **THEN** every row is streamed rather than the call failing for truncation

#### Scenario: Metadata statement survives saturation

- **WHEN** every `flight.max-concurrent-streams` permit is held and a client executes a metadata
  statement
- **THEN** it succeeds rather than failing fast

### Requirement: Unimplemented commands fail at getFlightInfo

`getFlightInfo` SHALL return an endpoint only for a command the endpoint can actually stream. A
command that is not implemented — `CommandGetPrimaryKeys`, `CommandGetExportedKeys`,
`CommandGetImportedKeys`, `CommandGetCrossReference`, and any future command not explicitly
supported — SHALL fail at `getFlightInfo` with `UNIMPLEMENTED` naming the command, rather than
returning an endpoint whose `doGet` then fails (**BREAKING**: these commands previously succeeded at
`getFlightInfo` and failed only once the client fetched, which drivers treat as a protocol violation).

#### Scenario: Key metadata command rejected up front

- **WHEN** a client calls `getFlightInfo` with `CommandGetPrimaryKeys`
- **THEN** the call fails with `UNIMPLEMENTED` and no endpoint or ticket is returned

### Requirement: Map result columns travel as Utf8 JSON text

A `map` result column SHALL map to Arrow `Utf8` carrying the value's JSON serialization as text, exactly
as an `object` result column does and serialized with the same mapper — so a map cell appears over Flight
SQL as the same JSON object literal the REST surfaces produce for that cell, and a NULL cell as a null
`Utf8`.

In `CommandGetXdbcTypeInfo` and the XDBC type metadata, a `map` column SHALL surface as `VARCHAR`; it
SHALL NOT introduce a new XDBC type code.

#### Scenario: Map column streams as JSON text

- **WHEN** a Flight SQL client executes a statement projecting a `map` column
- **THEN** the Arrow schema types the column as `Utf8` and each cell carries the same JSON object text
  the REST surface returns for that cell

#### Scenario: Map column reports as VARCHAR in XDBC metadata

- **WHEN** a client reads the XDBC column metadata for a table carrying a `map` column
- **THEN** that column reports the `VARCHAR` type code

### Requirement: An unmatched payload row streams as an Arrow null

A statement naming a payload-enrichment column for an event whose payload row is absent SHALL stream
that cell as an Arrow null and SHALL NOT fail. The row is most often absent because the event is older
than the payload table's retention (90 days by default) while its `dial_usage_log` row is not. This is the existing
unmatched-`LEFT JOIN` rule (see "Arrow result schema and type mapping"), and it becomes reachable in
ordinary use once the two tables carry different retentions.

#### Scenario: An expired payload reads back as null

- **WHEN** a client selects `"dial_usage_log_payload.request_body"` for an event whose payload has
  passed the payload table's retention
- **THEN** the Arrow cell is null and the row is streamed normally, because the grain join found no row

### Requirement: The Flight surface authorizes per RPC by privilege

The Flight SQL surface SHALL authorize each RPC by the privilege that RPC requires, rather than by
testing the caller for an application role. Statement execution (`GetFlightInfo` and `DoGet`) SHALL
require `TABLE_READ` on every table the statement resolves. The metadata RPCs (`GetTables`,
`GetCatalogs`, `GetDbSchemas`, `GetTableTypes`, `GetSqlInfo`) SHALL apply the same per-table filter
as the HTTP discovery surface and SHALL omit tables the caller may not read.

Because Flight is an execution protocol, it SHALL NOT advertise a table that a subsequent `DoGet`
would refuse: `GetTables` SHALL be filtered by `TABLE_READ`, not by `TABLE_GET`, even though it
returns schema information. A caller holding only `TABLE_GET` on a table therefore sees that table
over HTTP and not over Flight.

The gate SHALL NOT test the caller for an application role or for any particular privilege, because a
caller authorized entirely by per-table grants holds none globally and is exactly the caller those grants
exist for. It SHALL reject only a caller that could match nothing at all — one holding no privilege
**and** carrying no provider role — rather than serving it an empty catalog on every call. Refusal SHALL
use the Flight surface's existing error mapping.

#### Scenario: A grant-only caller reaches Flight

- **GIVEN** a credential that maps to no privilege but carries a provider role named in a table's
  `read` list
- **WHEN** it authenticates to the Flight surface
- **THEN** it is admitted, and `GetTables` returns that table

#### Scenario: A caller that can match nothing is refused

- **WHEN** a credential that maps to no privilege and carries no provider role authenticates
- **THEN** the call is refused, because no table is reachable for it by any route

#### Scenario: Metadata is filtered to readable tables

- **GIVEN** two tables, one of which grants the caller's provider role `read`
- **WHEN** the caller calls `GetTables`
- **THEN** only the granted table is returned

#### Scenario: A statement over an unreadable table is refused

- **WHEN** the caller executes a statement naming the other table
- **THEN** the statement fails as referring to an unknown entity, without revealing that it exists

#### Scenario: A get-only grant does not appear over Flight

- **GIVEN** a table whose `get` list contains the caller's provider role and whose `read` list does
  not
- **WHEN** the caller calls `GetTables`
- **THEN** that table is absent, while `GET /v1/tables/{name}` over HTTP returns it

#### Scenario: Flight honours per-table grants for an API-key caller

- **GIVEN** a DIAL API key whose Core role appears in a table's `read` list, and which holds no
  global read privilege
- **WHEN** the key is used to execute a statement over Flight against that table
- **THEN** the statement resolves and executes, whichever surface first introspected the key

## Implementation notes

- Server/producer/auth/error mapping: `com.epam.aidial.analytics.web.flight` —
  `FlightSqlServerLifecycle` (SmartLifecycle, ephemeral-port support), `AdasFlightSqlProducer`
  (extends `BasicFlightSqlProducer`), `FlightHeaderAuthenticator` (CallHeaderAuthenticator over the
  HTTP security components), `FlightErrorMapper`, `FlightStreamGate`, `FlightProperties`.
- Arrow result path: `com.epam.aidial.analytics.service.query.arrow` — `ArrowTypeMapper` (normative
  type table), `ArrowBatchWriter` (batching, null preservation, client-v2 value coercion),
  `ArrowAllocators` (application root allocator; per-request bounded children).
- Row cap: `flight.max-rows` on `FlightProperties`. `AdasFlightSqlProducer.honorsStatedLimit` is the one
  predicate both the clamp and the truncation check consult — true only for an explicit `LIMIT` at or
  under the cap — so they cannot drift; `effectiveQuery` substitutes a page of `max-rows + 1` (preserving
  any `OFFSET`) for every other statement, at `getFlightInfo` and on the ticket path alike, and
  `rejectIfTruncated` fails before anything is streamed. Whether the statement stated a bound travels as
  `SqlTranslation.explicitLimit`, set from the parser rather than inferred from the page. Authorising a page above the shared 1000 is what the
  `maxLimit` overloads of `StructuredQueryBuilder.build` / `StructuredQueryExecutor.execute` /
  `StructuredQueryService.execute` exist for; every non-Flight caller uses the forms without it and keeps
  the shared 100/1000 governance.
- Catalog metadata: `FlightCatalogStreams` builds every metadata RPC's rows over the protocol's fixed
  schemas (`FlightSqlProducer.Schemas.*`) as a projection of `CatalogQueryService` — there is no second
  catalog, so the RPCs and `/v1/queries` discovery cannot drift. `include_schema` renders through the
  shared `sql.ResultColumns` declared-type mapping and `ArrowTypeMapper`, then serializes with
  `MessageSerializer`. `AdasFlightSqlProducer.determineEndpoints` whitelists the supported commands, and
  each catalog `getStream*` runs its reads inside `asCaller` on a bounded child allocator, without the
  `FlightStreamGate`.
- Capability advertisement: `AdasFlightSqlProducer.getStreamSqlInfo` is a flat `SqlInfoBuilder` chain
  grouped by concern (server identity / namespace / identifiers / absences / limits / transport /
  capabilities), with the admissibility rule and the reason the function sets are withheld recorded in
  its Javadoc. `SqlInfoBuilder.send` looks up a provider per requested code with **no null check**, so
  an unadvertised code throws and aborts the whole response — which is why an omission costs a client
  every value it batched, not one row, and why the excluded set is kept minimal.
- All Flight beans are `@ConditionalOnProperty(flight.enabled)` — disabled means no beans exist.
- JVM flags required by Arrow (wired into Gradle `test`/`bootRun` and the Docker entrypoint,
  documented in `docs/configuration.md`): `--add-opens=java.base/java.nio=ALL-UNNAMED`,
  `--sun-misc-unsafe-memory-access=allow` (JDK 24+, JEP 498), and `--enable-native-access=ALL-UNNAMED`
  (JDK 24+, JEP 472 — silences the restricted-method warning from Netty's native library loader).
- End-to-end coverage: `integration/FlightSqlIntegrationTest` (statements plus the metadata RPCs),
  `FlightRowCapIntegrationTest` (the clamp, the probe row, and the truncation failure),
  `FlightMetadataAuthIntegrationTest` (`oidc` mode: a `READ_ONLY_ADMIN` caller's
  catalog omits `sensitive` columns a `FULL_ADMIN`'s carries), `FlightClickHouseDownIntegrationTest`,
  `FlightDisabledIntegrationTest` (Testcontainers ClickHouse + Postgres, part of `./gradlew build`).
