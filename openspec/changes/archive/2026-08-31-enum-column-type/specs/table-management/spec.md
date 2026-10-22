## ADDED Requirements

### Requirement: Enum column type

The catalog column-type vocabulary SHALL include `enum` — a closed, ordered set of string values,
materialized as ClickHouse `Enum8`/`Enum16`. An `enum` column SHALL declare `enum_values`: an
ordered, non-empty list of distinct strings, declared on `POST /v1/tables/{name}/schema` and on a
`PATCH /v1/tables/{name}/schema` `add` entry. This mirrors the parameter shape `array` already
uses: `enum_values` is required iff `type` is `enum`, and SHALL be rejected on a column of any
other type.

An `enum` column SHALL materialize as `Enum8('<v1>' = 1, '<v2>' = 2, …)` when it declares at most
127 values, and `Enum16(…)` above that, with each value assigned its 1-based position in the
declared list as its numeric id. Enum columns follow the ordinary nullability rules — a source
column defaults non-nullable, an enrichment column defaults nullable, an explicit `nullable` wins —
and a nullable enum column materializes as `Nullable(Enum8(…))`/`Nullable(Enum16(…))`. Each value
is embedded in the DDL as a single-quoted literal with any single quote **and any backslash**
escaped, so a value containing either materializes faithfully rather than altering the statement.

Declaration SHALL be rejected with HTTP 422 and `error` `validation_error`, before anything is
persisted, when: a column of type `enum` declares no `enum_values`; a column of any other type
declares `enum_values`; the list is empty; any value is null or blank; two values are equal after
trimming; any value is longer than 64 characters; or the list holds more than 512 values. `enum`
SHALL NOT be a valid `array` `element_type`.

Declared values SHALL be **trimmed**, and the trimmed form is canonical: it is what materializes into
the DDL, what the catalog stores, and what every read surface returns — on the whole-table define path
and on the `PATCH .../schema` `add` path alike. The two SHALL NOT diverge; a column whose physical
domain held ` high ` while its catalog row advertised `high` would refuse the value it publishes, with
no repair available, since the domain is immutable.

`enum_values` SHALL be returned wherever a column's shape is returned — `GET /v1/tables/{name}`,
`GET /v1/tables` when column definitions are requested, and the historical snapshot
`GET /v1/tables/{name}/revision/{revision}` — as a JSON array in its declared order, and absent or
null for a column that is not an `enum`. Declaration order is significant: it is both the DDL id
assignment and the order returned, so it is preserved exactly as declared and never sorted.

The declared domain SHALL be immutable for the life of the column. A `PATCH .../schema` `update`
entry carrying `enum_values` SHALL be rejected with HTTP 422 and `error` `validation_error` rather
than silently ignored, because changing a ClickHouse enum's domain rewrites the column and can fail
against rows already stored; a domain is widened by dropping and re-adding the column, the same way
a column's type is changed. A `PATCH .../schema` `rename` SHALL carry the column's `enum_values`
across unchanged, along with its type, nullability and other metadata.

An `enum` column MAY be named in a table's `ordering_key` or as an enrichment's `grain_key`:
ClickHouse compares an enum by its numeric id, so ordering follows the **declared order of the
values**, not their alphabetical order. An `enum` column SHALL NOT be a table's TTL column or a
source's `version_column`, which require a `timestamp`.

A row written through `POST /v1/tables/{name}/rows` SHALL supply an `enum` column's value as a JSON
string, exactly as a `string` column takes; the write path passes it through unchanged. A value
outside the declared domain is rejected by ClickHouse, and SHALL surface through the endpoint's
existing row-write failure contract rather than a new error code.

#### Scenario: Enum column is declared and materialized

- **WHEN** `POST /v1/tables/{name}/schema` declares a column `status` of type `enum` with
  `enum_values: ["pending", "running", "failed"]`
- **THEN** the table is materialized with that column as ClickHouse
  `Enum8('pending' = 1, 'running' = 2, 'failed' = 3)` and `GET /v1/tables/{name}` reports the
  column with `type` `enum` and `enum_values` in that same order

#### Scenario: Nullable enum column materializes wrapped

- **WHEN** a schema declares an `enum` column with `nullable: true`
- **THEN** the column is materialized as `Nullable(Enum8(…))` and reported as `nullable: true`

#### Scenario: Enum column without values is rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of type `enum` with no
  `enum_values`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and nothing is
  persisted

#### Scenario: Enum values on a non-enum column are rejected

- **WHEN** a `POST .../schema` or a `PATCH add` declares a column of type `string` (or any type
  other than `enum`) carrying `enum_values`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Empty, blank, or duplicate enum values are rejected

- **WHEN** a schema declares an `enum` column whose `enum_values` is an empty list, contains a
  blank value, or lists the same value twice
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Oversized enum domain is rejected

- **WHEN** a schema declares a column whose `enum_values` holds more than 512 values, or any single
  value longer than 64 characters
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Enum domain above the Enum8 range uses Enum16

- **WHEN** a schema declares an `enum` column with 200 distinct `enum_values`
- **THEN** the column is materialized as `Enum16(…)` rather than `Enum8(…)`

#### Scenario: A value containing a quote is escaped into the DDL

- **WHEN** a schema declares an `enum` column whose `enum_values` includes `it's`
- **THEN** the `CREATE TABLE` embeds it as an escaped literal, the table materializes successfully
  (the physical type carries the value as data rather than the statement being altered), and the
  value is returned unchanged by `GET /v1/tables/{name}`

#### Scenario: Enum is not a valid array element type

- **WHEN** a schema declares an `array` column with `element_type` `enum`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Adding an enum column to a live table

- **WHEN** `PATCH /v1/tables/{name}/schema` adds an `enum` column with
  `enum_values: ["a", "b"]` to an `ACTIVE` table
- **THEN** an `ALTER TABLE … ADD COLUMN … Enum8('a' = 1, 'b' = 2)` is issued and the column mapping
  records the declared values

#### Scenario: Whitespace around a declared value is trimmed on both paths

- **WHEN** a `POST .../schema` column or a `PATCH .../schema` `add` entry declares `enum_values`
  `[" high ", "low"]`
- **THEN** the column materializes as `Enum8('high' = 1, 'low' = 2)` and is returned with
  `enum_values` `["high", "low"]` — the DDL and the catalog carrying the same trimmed values, so a
  row written with `high` is accepted

#### Scenario: Changing an enum domain through update is rejected

- **WHEN** a `PATCH .../schema` `update` entry for an existing column carries `enum_values`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and the column's
  stored domain is unchanged

#### Scenario: Renaming an enum column preserves its domain

- **WHEN** a `PATCH .../schema` renames an `enum` column
- **THEN** the renamed column keeps type `enum` and the same `enum_values`, in the same order

#### Scenario: Enum column orders by declared position

- **WHEN** a table declares an `enum` column with `enum_values: ["low", "medium", "high"]` in its
  `ordering_key`, and rows of each value are written
- **THEN** rows sort in declared order (`low`, `medium`, `high`), not alphabetical order
  (`high`, `low`, `medium`)

#### Scenario: Enum column cannot carry the TTL or row version

- **WHEN** a schema names an `enum` column as its TTL column or as a source's `version_column`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Rows carry enum values as plain strings

- **WHEN** `POST /v1/tables/{name}/rows` writes a row whose `status` value is `"running"` into a
  column declaring that value
- **THEN** the row is stored and a query projecting `status` returns the string `"running"`

#### Scenario: A value outside the declared domain is rejected

- **WHEN** `POST /v1/tables/{name}/rows` writes a row whose `status` value is not one of the
  column's declared `enum_values`
- **THEN** the write fails and is reported through the endpoint's existing row-write failure
  contract, with no partial row stored
