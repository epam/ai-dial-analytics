## ADDED Requirements

### Requirement: Catalog metadata is queryable as INFORMATION_SCHEMA

The SQL surface SHALL expose a schema named `INFORMATION_SCHEMA` holding three relations — `TABLES`,
`COLUMNS`, and `SCHEMATA` — describing the same catalog that backs `GET /v1/queries/entities` and
`GET /v1/queries/entities/schema/{name}`, so the SQL catalog and the REST catalog cannot disagree
about which entities exist or what columns they have.

A statement whose tables are all in `INFORMATION_SCHEMA` SHALL be **executed in process** against
those rows and SHALL NOT be translated to a `StructuredQuery` or sent to ClickHouse. It SHALL support
the same statement shapes as the rest of the surface — projection, `DISTINCT`, `WHERE`, `ORDER BY`,
`LIMIT`/`OFFSET`, and aggregates — evaluated with standard SQL semantics rather than a shape-matched
subset, so a client is not restricted to the exact statements one connector happens to send.

A statement mixing `INFORMATION_SCHEMA` relations with a data entity SHALL be rejected; this follows
from the existing rejection of joins and subqueries and introduces no new construct.

Because the rows are held in process, a metadata statement SHALL NOT be subject to the default page
limit, the maximum-limit ceiling, or the Flight concurrency gate — all of which govern ClickHouse
execution. Its result size is bounded by the catalog itself.

Rows SHALL be derived from the caller's resolved catalog model, so a column hidden from a caller by
sensitive-column access control is absent from `INFORMATION_SCHEMA.COLUMNS` for that caller, matching
its absence from discovery and from query resolution.

`INFORMATION_SCHEMA` and its relations SHALL resolve case-insensitively, quoted or unquoted, matching
the front end's existing identifier handling — `INFORMATION_SCHEMA."TABLES"`,
`information_schema.tables`, and `Information_Schema.COLUMNS` SHALL all resolve.

`TABLES` SHALL carry the columns `TABLE_CATALOG`, `TABLE_SCHEMA`, `TABLE_NAME`, `TABLE_TYPE`, in that
order, all character-typed. One row per queryable entity, with `TABLE_TYPE` `TABLE`, plus one row per
`INFORMATION_SCHEMA` relation with `TABLE_TYPE` `SYSTEM_TABLE`. `TABLE_CATALOG` SHALL be the empty
string, consistent with the empty catalog term the Flight endpoint declares — ADAS has no catalog
namespace and SHALL NOT invent a name for one.

`COLUMNS` SHALL carry the seventeen columns `TABLE_CATALOG`, `TABLE_SCHEMA`, `TABLE_NAME`,
`COLUMN_NAME`, `ORDINAL_POSITION`, `COLUMN_DEFAULT`, `IS_NULLABLE`, `DATA_TYPE`, `COLUMN_SIZE`,
`CHARACTER_MAXIMUM_LENGTH`, `CHARACTER_OCTET_LENGTH`, `NUMERIC_PRECISION`, `NUMERIC_PRECISION_RADIX`,
`NUMERIC_SCALE`, `DATETIME_PRECISION`, `INTERVAL_TYPE`, `INTERVAL_PRECISION`, in that order.
`ORDINAL_POSITION` SHALL be 1-based. `IS_NULLABLE` SHALL be `YES` for every column, which matches the
Arrow mapping — every result field is nullable — rather than being a default. `COLUMN_DEFAULT`,
`INTERVAL_TYPE`, and `INTERVAL_PRECISION` SHALL be null: ADAS has no column defaults and no interval
type. A value ADAS cannot determine SHALL be null rather than zero, so "unknown" is distinguishable
from "none".

`SCHEMATA` SHALL carry `CATALOG_NAME`, `SCHEMA_NAME`, `SCHEMA_OWNER`, `TYPE`, `IS_MUTABLE`, with one
row for the data schema and one for `INFORMATION_SCHEMA`.

`CATALOGS` and `VIEWS` SHALL NOT be provided: ADAS has no catalog namespace and no views, and an
invented catalog row would contradict the endpoint's declared empty catalog term.

`DATA_TYPE` SHALL report the type-name vocabulary Dremio-compatible clients expect, because such a
client resolves the value against a fixed table and fails the whole navigation on an unrecognised
name:

| Declared field type | `DATA_TYPE` |
|---|---|
| `string`, `uuid`, `object` | `CHARACTER VARYING` |
| `integer` | `INTEGER` |
| `long` | `BIGINT` |
| `decimal` | `DECIMAL` |
| `boolean` | `BOOLEAN` |
| `date` | `DATE` |
| `timestamp` | `TIMESTAMP` |
| `array` | `ARRAY` |

`CHARACTER VARYING` SHALL be used rather than `VARCHAR`, and `INTEGER` rather than `INT`.

#### Scenario: Schema listing

- **WHEN** a client executes `SELECT DISTINCT TABLE_SCHEMA FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_TYPE <> 'SYSTEM_TABLE' ORDER BY TABLE_SCHEMA`
- **THEN** the result is a single row whose `TABLE_SCHEMA` is the data schema name, and no
  `INFORMATION_SCHEMA` row is included

#### Scenario: Table listing for a schema

- **WHEN** a client executes `SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_SCHEMA = 'adas' AND TABLE_TYPE <> 'SYSTEM_TABLE'`
- **THEN** the result carries one row per entity listed by `GET /v1/queries/entities`, each with
  `TABLE_TYPE` `TABLE`

#### Scenario: Column listing for a table

- **WHEN** a client executes `SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'adas' AND TABLE_NAME = 'dial_usage_log'`
- **THEN** the result carries one row per field of that entity's flat schema, `IS_NULLABLE` is `YES`
  throughout, and every `DATA_TYPE` is one of the names in the mapping above

#### Scenario: A string column reports Dremio's type spelling

- **WHEN** a client reads `DATA_TYPE` for a `string` column
- **THEN** the value is `CHARACTER VARYING`, never `VARCHAR`

#### Scenario: Case-insensitive resolution

- **WHEN** a client executes the same query as `information_schema.tables` and as `INFORMATION_SCHEMA."TABLES"`
- **THEN** both resolve and return the same rows

#### Scenario: Metadata query does not reach ClickHouse

- **WHEN** a metadata statement is executed while ClickHouse is unavailable
- **THEN** it still succeeds

#### Scenario: Sensitive columns are hidden from COLUMNS

- **WHEN** a caller who may not see sensitive columns lists `INFORMATION_SCHEMA.COLUMNS` for an entity
  that has one
- **THEN** the sensitive column is absent, matching its absence from discovery

#### Scenario: Metadata query is not page-limited

- **WHEN** a metadata statement with no `LIMIT` returns more rows than the default page limit
- **THEN** every row is returned

### Requirement: Entity names resolve schema-qualified as well as bare

Every queryable entity SHALL resolve both unqualified (`FROM dial_usage_log`) and qualified by the
data schema name `adas` (`FROM adas.dial_usage_log`, `FROM "adas"."dial_usage_log"`), resolving to the
same entity with the same columns and the same translation.

The unqualified form SHALL remain canonical: it is what ADAS's Flight capability advertisement steers
clients toward, and no behaviour depends on the qualifier being present. The qualified form exists
because a client that discovers entities through `INFORMATION_SCHEMA` reads a schema name from
`TABLE_SCHEMA` and qualifies every subsequent statement with it.

The schema name SHALL be a fixed constant, not configuration: it is part of the statements clients
save, so varying it per deployment would make a saved statement deployment-specific.

A qualifier that is not the data schema name SHALL continue to fail validation, naming the
unresolvable qualifier.

#### Scenario: Qualified and bare names agree

- **WHEN** a client executes `SELECT event_kind FROM adas.dial_usage_log LIMIT 5` and
  `SELECT event_kind FROM dial_usage_log LIMIT 5`
- **THEN** both translate to the same structured query

#### Scenario: Quoted qualifier resolves

- **WHEN** a client executes `SELECT event_kind FROM "adas"."dial_usage_log" LIMIT 5`
- **THEN** it resolves, matching the case-insensitive handling of every other identifier

#### Scenario: Unknown qualifier still fails

- **WHEN** a client executes `SELECT event_kind FROM warehouse.dial_usage_log LIMIT 5`
- **THEN** validation fails and the message names `warehouse` as not found

### Requirement: A trailing statement separator is accepted

A single trailing `;`, optionally followed by whitespace, SHALL be accepted and ignored on every
statement the SQL surface accepts. Clients that terminate generated statements — including the
catalog SQL a Dremio-compatible connector emits — SHALL NOT be rejected for the terminator alone.

Tolerating a terminator SHALL NOT be treated as accepting a batch: the "exactly one statement" screen
is unchanged, so a separator *between* two statements SHALL still fail, and so SHALL more than one
trailing separator.

#### Scenario: Trailing separator ignored

- **WHEN** a client executes `SELECT event_kind FROM dial_usage_log LIMIT 5;`
- **THEN** it translates exactly as the same statement without the separator

#### Scenario: Batch still rejected

- **WHEN** a client submits `SELECT 1 FROM dial_usage_log; SELECT 2 FROM dial_usage_log`
- **THEN** it is rejected as more than one statement

#### Scenario: A semicolon inside a literal is untouched

- **WHEN** a client executes a statement whose final token is the string literal `';'`
- **THEN** the literal is preserved and the statement translates normally
