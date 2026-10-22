## ADDED Requirements

### Requirement: Catalog metadata RPCs stream from the query catalog

The endpoint SHALL implement the Flight SQL catalog RPCs end to end — both `getFlightInfo` and
`doGet` — for `CommandGetCatalogs`, `CommandGetDbSchemas`, `CommandGetTables`,
`CommandGetTableTypes`, and `CommandGetXdbcTypeInfo`, in addition to the already-supported
`CommandGetSqlInfo`. Each stream SHALL carry the Arrow schema the Flight SQL protocol fixes for that
command (`FlightSqlProducer.Schemas.*`).

Every row SHALL be derived from the same catalog that backs `GET /v1/queries/entities` and
`GET /v1/queries/entities/schema/{name}` (`CatalogQueryService`), so the RPC catalog and the REST
catalog cannot disagree about which entities exist or what columns they have.

ADAS exposes no catalog and no schema namespace: `CommandGetCatalogs` and `CommandGetDbSchemas`
SHALL stream zero rows, and every `CommandGetTables` row SHALL carry a null `catalog_name` and a null
`db_schema_name` — the namespace a client would otherwise qualify a generated statement with does not
resolve in the SQL front end. Zero rows alone does NOT tell a client the namespace is absent, only that
it is empty; the declaration that makes clients emit unqualified names is in "Capability advertisement
declares no catalog and no schema namespace". `CommandGetTableTypes` SHALL stream exactly one row, `TABLE`: the
queryable entities are the catalog's ACTIVE `source` tables and nothing else.

`CommandGetTables` SHALL list exactly the queryable entities, in the catalog's stable alphabetical
order, and SHALL honor its filters: a `table_name_filter_pattern` and `db_schema_filter_pattern` as
SQL `LIKE` patterns (`%`, `_`, with `\` escaping either), matched **case-insensitively** to mirror the
SQL front end's case-insensitive identifier handling — which `GetSqlInfo` already advertises as
`SQL_CASE_SENSITIVITY_CASE_INSENSITIVE`, so a pattern that matches an entity name must also match the
identifier a client would then write; a `table_types` list (empty means no filter; a list not
containing `TABLE` matches nothing); and a `catalog` field that when omitted matches everything, when
empty matches objects without a catalog (all of them), and when non-empty matches nothing.

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

### Requirement: Catalog metadata honors the caller's column visibility

Catalog RPCs SHALL resolve the catalog under the Flight caller's identity, exactly as the statement
path does, so a column hidden from the caller by sensitive-column filtering is absent from the
metadata as well as from query results. A caller without `FULL_ADMIN` SHALL NOT be able to learn from
any catalog RPC that a `sensitive` column exists. When `security.mode=none` the filtering fails open,
matching the query path.

#### Scenario: Sensitive columns absent from a read-only caller's table schema

- **WHEN** `security.mode=oidc` and a `READ_ONLY_ADMIN` caller calls `CommandGetTables` with
  `include_schema=true` for `dial_usage_log`, whose `jwt_claims` and `request_tags` columns are
  marked `sensitive`
- **THEN** the returned Arrow schema carries neither `jwt_claims` nor `request_tags`, while a
  `FULL_ADMIN` caller's schema for the same entity carries both

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

## MODIFIED Requirements

### Requirement: Execution reuses structured-query governance

Every Flight query SHALL execute by constructing a `StructuredQuery` and invoking the same
execution path as `POST /v1/queries/execute`. All structured-query governance SHALL apply
unchanged: entity and field validation, the closed function catalog, enrichment join rules,
and the maximum limit of 1000 rows. A SQL `LIMIT` above the maximum SHALL be
rejected with an `INVALID_ARGUMENT` status naming the requested limit and the maximum — never
silently clamped.

A Flight SQL statement SHALL carry an explicit `LIMIT`. A statement with no `LIMIT` — including one
with an `OFFSET` but no `LIMIT` — SHALL be rejected at `getFlightInfo` with `INVALID_ARGUMENT`, before
ClickHouse is contacted, with a message naming the fix and the default that would otherwise have
applied (**BREAKING**: such a statement previously received the structured-query default limit of 100
rows). The reason is that the endpoint can never return a complete table — the 1000-row ceiling makes
every result a sample — so a client generating `SELECT * FROM <entity>` from catalog metadata must not
receive 100 rows that look complete. This rule is stricter than structured-query governance, never
looser, and applies to the Flight surface only: `POST /v1/queries/execute-sql`,
`POST /v1/queries/execute`, and the translation endpoints SHALL keep applying the default limit of 100
to a request that omits one.

#### Scenario: Limit above the maximum rejected

- **WHEN** a client runs `SELECT request_id FROM dial_usage_log LIMIT 50000` via Flight
- **THEN** the call fails with `INVALID_ARGUMENT` and no rows are returned, matching REST
  behavior for `limit: 50000`

#### Scenario: Missing limit rejected

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log` via Flight, with no `LIMIT`
- **THEN** the call fails at `getFlightInfo` with `INVALID_ARGUMENT`, the message names the required
  explicit `LIMIT` and the maximum of 1000, and ClickHouse is not contacted

#### Scenario: Same rows as REST

- **WHEN** the same logical query is issued via Flight SQL with an explicit `LIMIT` and via
  `POST /v1/queries/execute` with the same `limit`
- **THEN** both return the same rows and values (modulo JSON vs Arrow encoding)

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

#### Scenario: FlightInfo schema for a star omits heavy fields

- **WHEN** an entitled client calls getFlightInfo with `SELECT * FROM dial_usage_log LIMIT 1` where
  `request_body` and `response_body` are marked `heavy`
- **THEN** the advertised Arrow schema carries every non-heavy source column and neither payload field,
  and the doGet stream delivers exactly those fields

#### Scenario: Star schema carries no enrichment field

- **WHEN** a client calls getFlightInfo with `SELECT * FROM <source> LIMIT 1` for a source with an
  attached enrichment
- **THEN** the advertised schema carries no `<enrichment>.<column>` field, and the executed query joins
  no enrichment

#### Scenario: Explicitly named heavy column streams normally

- **WHEN** the client runs `SELECT event_id, response_body FROM dial_usage_log LIMIT 5`
- **THEN** the Arrow schema carries `response_body` as `Utf8` and the stream delivers its values

#### Scenario: All-heavy star fails fast
- **WHEN** a client calls getFlightInfo with `SELECT * FROM <entity> LIMIT 1` whose every visible field
  is marked `heavy`
- **THEN** the call fails with `INVALID_ARGUMENT` and ClickHouse is not contacted
