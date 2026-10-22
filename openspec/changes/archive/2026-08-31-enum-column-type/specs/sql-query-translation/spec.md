## ADDED Requirements

### Requirement: Enum columns are typed as character data on the SQL surface

A catalog column of type `enum` SHALL be presented to the SQL surface as a character type: it SHALL
be typed `VARCHAR` in the row type inbound SQL is validated against, and `information_schema`
SHALL report its `DATA_TYPE` as `CHARACTER VARYING` with no declared length bound, exactly as a
`string` column is reported.

This is what keeps an enum column usable by clients that speak only SQL and its catalog — a BI tool
reading `information_schema` or a Flight SQL schema sees text, compares it with string literals,
and needs no knowledge of the catalog's `enum` type. The declared domain remains discoverable
through `GET /v1/queries/entities/schema/{name}`; it is deliberately not encoded into the SQL
type.

A projection of an `enum` column SHALL be returned as a string value in the result set and written
into a UTF-8 Arrow vector, the same as a `string` column. On the JSON surfaces this means a
**quoted JSON string**: the response body SHALL be valid JSON under a strict reader, on both
`POST /v1/queries/execute` and `POST /v1/queries/execute-sql`. This is stated explicitly because the
underlying driver represents an enum cell as a numeric type carrying its name, which a naive
pass-through serializes as a bare unquoted token — valid-looking output that no strict JSON parser
accepts.

`VARCHAR` is what the SQL *front end* validates against, not the last word: an inbound statement is
translated into the DSL and then executed through the ordinary DSL pipeline, so every DSL validation
rule applies to it too. A statement applying a string function to an `enum` column — including the
`LIKE`/`ILIKE` forms, which translate into the DSL's contains family — SHALL therefore be rejected
before execution, with the same validation error the DSL surface gives, naming `enum` as the
offending type, rather than being sent to ClickHouse and failing there. The two surfaces are not
asymmetric: the SQL type is widened to `VARCHAR` so a BI client can *read and compare* the column,
while the shared DSL validation is what keeps a string function over an enum from reaching the
server.

#### Scenario: A string function over an enum column is rejected on the SQL surface

- **WHEN** a `POST /v1/queries/execute-sql` statement applies a string function to an `enum` column
  (for example `WHERE status LIKE '%run%'`)
- **THEN** the request is rejected with a validation error naming `enum` as the offending type,
  the same rejection the DSL surface gives, rather than a ClickHouse execution failure

#### Scenario: Inbound SQL filters an enum column with a string literal

- **WHEN** a `POST /v1/queries/execute-sql` statement compares an `enum` column against a string
  literal
- **THEN** the statement validates and executes, returning the matching rows

#### Scenario: information_schema reports an enum column as character data

- **WHEN** a client reads `information_schema.columns` for a table holding an `enum` column
- **THEN** that column's `DATA_TYPE` is `CHARACTER VARYING`, with a null character-maximum-length,
  identical to a `string` column's row

#### Scenario: An enum column projects as a string

- **WHEN** a SQL or Flight query projects an `enum` column
- **THEN** the value is returned as its string form, in a UTF-8 result column

#### Scenario: An enum projection is valid JSON

- **WHEN** either JSON query endpoint projects an `enum` column
- **THEN** the value appears as a quoted JSON string and the whole response body parses under a
  strict JSON reader — never as a bare unquoted token

## MODIFIED Requirements

### Requirement: Catalog metadata is queryable as INFORMATION_SCHEMA

The SQL surface SHALL expose a schema named `INFORMATION_SCHEMA` holding three relations — `TABLES`,
`COLUMNS`, and `SCHEMATA` — describing the same catalog that backs `GET /v1/queries/entities` and
`GET /v1/queries/entities/schema/{name}`, so the SQL catalog and the REST catalog cannot disagree
about which entities exist or what columns they have.

A statement whose tables are all in `INFORMATION_SCHEMA` SHALL be **executed in process** against
those rows and SHALL NOT be translated to a `StructuredQuery` or sent to ClickHouse. It SHALL support
projection, `DISTINCT`, `WHERE`, `ORDER BY`, `LIMIT`/`OFFSET`, and non-`DISTINCT` aggregates
(including `GROUP BY` and `HAVING`), evaluated with standard SQL semantics rather than a shape-matched
subset, so a client is not restricted to the exact statements one connector happens to send.

A statement the in-process evaluator cannot evaluate SHALL be rejected as a **client error** — 400
`bad_request` over REST, `INVALID_ARGUMENT` over Flight — naming the construct and the supported
shapes. It SHALL NOT surface as `500 internal_error` or gRPC `INTERNAL`. This covers a join between two
catalog relations, a subquery, a window function, `ROLLUP`/`CUBE`/`GROUPING SETS`, and any expression
the evaluator rejects at run time (`MIN`/`MAX` over a character column among them): the evaluator is
not total over the plans the validator accepts, so the boundary is enforced rather than assumed.

An aggregate call carrying `DISTINCT` SHALL be rejected rather than evaluated. This is not a shape the
evaluator merely lacks — it evaluates the call as though the `DISTINCT` were absent, so
`COUNT(DISTINCT TABLE_SCHEMA)` would answer with the total row count: a wrong number a client cannot
distinguish from the right one. `SELECT DISTINCT` on the column is unaffected and is what the rejection
message names, so the question remains answerable.

An aggregate with no `GROUP BY` SHALL return exactly one row whether or not its input is empty — `COUNT`
as 0 and every other aggregate as null — because SQL requires it and because
`SELECT COUNT(*) … WHERE TABLE_NAME = '<name>'` is the ordinary way a client asks whether an object
exists. Returning no row there reads to a JDBC-shaped client as "no data" rather than as "none". A bound
that discards the row anyway (`OFFSET 1`) SHALL keep returning nothing, and a grouped aggregate over an
empty input SHALL keep returning no rows: it has no groups to report.

Where that row cannot be produced the statement SHALL be rejected **on its shape**, so the outcome never
depends on whether a particular catalog happened to match. Three shapes qualify, all of which would
require evaluating something over the synthesized row:

- `HAVING` without `GROUP BY` — the row would have to be tested against the predicate.
- Any expression computed over a grouping-less aggregate. This is wider than arithmetic: `COUNT(*) + 1`,
  `CAST(COUNT(*) AS INTEGER)`, `COALESCE(MAX(x), 0)` and `CASE WHEN COUNT(*) > 0 THEN … END` are all
  refused, though each is admitted in its grouped form, where no row is owed. Selecting the aggregate
  itself always works, which is the form the rejection message names.
- An aggregate over a **grouping-less** aggregate, including through a derived table
  (`SELECT COUNT(n) FROM (SELECT COUNT(*) AS n FROM … WHERE <no match>) x`). The inner aggregate owes one
  row, so the outer one would have to be computed over a row the evaluator never produced — answering 0
  where 1 is correct.

  The condition is on the inner aggregate being grouping-less, not on nesting as such. `SELECT DISTINCT`
  and a bare `GROUP BY` also lower to an aggregate, and neither owes a row for an empty input, so
  `SELECT COUNT(*) FROM (SELECT DISTINCT <col> FROM …) x` SHALL be answered — it is the only way this
  surface can count distinct values, and the form the `DISTINCT`-aggregate rejection recommends.

A `DATE` or `TIMESTAMP` value produced by a catalog statement SHALL carry the same Java type, and encode
the same way, as one produced by a query against an entity. The evaluator's internal encodings (a date as
an epoch-day integer, a timestamp as epoch millis) SHALL NOT reach the Arrow writer or the JSON encoder:
the writer has no coercion for the former, so it would fail *after* the stream had started — a mid-stream
gRPC `INTERNAL` outside the client-error guarantee above — and over REST both would serialize as bare
integers where every other ADAS temporal value is an ISO string.

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
| `string`, `uuid`, `enum`, `object`, `map` | `CHARACTER VARYING` |
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

#### Scenario: Aggregates over the catalog relations

- **WHEN** a client executes `SELECT TABLE_SCHEMA, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS GROUP BY TABLE_SCHEMA`
- **THEN** one row per schema is returned with the correct count, and `COUNT` over a column that is null
  in every row returns 0 rather than the row count

#### Scenario: A DISTINCT aggregate is refused, not answered wrongly

- **WHEN** a client executes `SELECT COUNT(DISTINCT TABLE_SCHEMA) FROM INFORMATION_SCHEMA.COLUMNS`
- **THEN** the statement is rejected as a client error naming `SELECT DISTINCT` as the supported form,
  rather than returning the total row count

#### Scenario: A global aggregate matching nothing still answers

- **WHEN** a client executes `SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_NAME = 'no_such_table'`
- **THEN** one row is returned with `n = 0`, and the same statement under `OFFSET 1` returns no rows

#### Scenario: A temporal value encodes like a query result

- **WHEN** a client executes `SELECT DATE '2020-01-01' AS d FROM INFORMATION_SCHEMA."TABLES" LIMIT 1` over
  Flight
- **THEN** the row streams as a date, not as an epoch-day integer, and the call does not fail mid-stream

#### Scenario: An unevaluable construct is a client error

- **WHEN** a client joins two `INFORMATION_SCHEMA` relations, uses a subquery, a window function, or
  `ROLLUP` over them
- **THEN** each is rejected with 400 `bad_request` / `INVALID_ARGUMENT` naming the construct, never as
  `500 internal_error` / gRPC `INTERNAL`
