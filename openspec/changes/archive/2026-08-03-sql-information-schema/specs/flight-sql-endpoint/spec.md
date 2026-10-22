## MODIFIED Requirements

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

## ADDED Requirements

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
