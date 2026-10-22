## ADDED Requirements

### Requirement: Map column type

The catalog column-type vocabulary SHALL include `map`, materialized as ClickHouse
`Map(String, String)` — an unordered set of string-keyed string values, which is what OpenTelemetry-style
attribute bags need (see [otel-sink](../otel-sink/spec.md)). A `map` column declares no `element_type`
and takes no key/value type parameters; an `element_type` on a `map` column SHALL be rejected.

Because ClickHouse has no `Nullable(Map(...))`, a `map` column SHALL always be non-nullable: an explicit
`nullable: true` on a `map` column SHALL be rejected with HTTP 422, and an omitted `nullable` SHALL
resolve to `false` for a `map` column even on an enrichment (whose other columns default nullable) —
the same rule an `array` column already follows. `map` SHALL NOT be a valid `array` `element_type`, and
SHALL NOT be usable as a table's `ordering_key`, `partition_by`, `grain_key`, TTL column,
`identity_column` or `version_column`.

A `map` value written through `POST /v1/tables/{name}/rows` SHALL be a JSON object whose values are
strings; the write path SHALL pass it through unchanged, exactly as it does for every non-`timestamp`
type.

#### Scenario: Map column is declared and materialized

- **WHEN** `POST /v1/tables/{name}/schema` declares a column `attributes` of type `map`
- **THEN** the table is materialized with that column as ClickHouse `Map(String, String)` and
  `GET /v1/tables/{name}` reports the column with `type` `map` and no `element_type`

#### Scenario: Nullable map column is rejected

- **WHEN** a schema declares a `map` column with `nullable: true`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`, and nothing is persisted

#### Scenario: Map is not a valid array element type

- **WHEN** a schema declares an `array` column with `element_type` `map`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error`

#### Scenario: Map column cannot key the table

- **WHEN** a schema names a `map` column in its `ordering_key`, as its `partition_by`, or as an
  enrichment's `grain_key`
- **THEN** the request is rejected with HTTP 422 and `error` `validation_error` before anything is
  persisted — the definition stays `pending` with no columns, rather than the `CREATE TABLE` failing
  in ClickHouse and leaving a `failed` definition behind

#### Scenario: Rows carry map values as JSON objects

- **WHEN** `POST /v1/tables/{name}/rows` writes a row whose `attributes` value is
  `{"http.method": "GET"}`
- **THEN** the row is stored with that map, and a query projecting `attributes` returns the same object
