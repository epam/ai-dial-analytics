## ADDED Requirements

### Requirement: Map cells serialize as JSON objects

A projected `map` column SHALL be typed in the derived result schema as an object column — a map cell
travels as a JSON object exactly as an `object` cell does — and SHALL serialize in the response
envelope's `rows` as a JSON object whose values are strings. An empty map SHALL serialize as `{}`, never
as `null` and never as a quoted JSON string. A wildcard projection over an entity carrying `map` columns
SHALL include them, subject to the existing heavy-column exclusion.

#### Scenario: Map projection returns a JSON object

- **WHEN** a row-mode query projects a `map` column holding `{"http.method": "GET"}`
- **THEN** the response envelope types the column as an object column and the row carries
  `{"http.method": "GET"}` as a JSON object

#### Scenario: Empty map cell serializes as an empty object

- **WHEN** a row-mode query projects a `map` column whose stored value is the empty map
- **THEN** the row carries `{}` for that column

### Requirement: Map fields are projection-only when translating to SQL

A `map` field SHALL be projection-only in the DSL→SQL translation subset, on the same footing as an
`array` and an `object` field: the SQL dialect has no map literal and no comparison over one, so a
`StructuredQuery` that uses a `map` field as a `distinct` projection, a `group_by` key, a `sort` key, or
a filter operand SHALL be rejected by `POST /v1/queries/translate` with HTTP 400 and `error`
`bad_request`, naming the field and the projection-only restriction. Carrying a `map` field as a plain
projection SHALL translate.

#### Scenario: Map field projects in a translated query

- **WHEN** `POST /v1/queries/translate` is given a query projecting a `map` field
- **THEN** the translation succeeds and the rendered SQL selects that column

#### Scenario: Map field as a sort key is refused by translation

- **WHEN** `POST /v1/queries/translate` is given a query sorting by a `map` field
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction

#### Scenario: Map field as a group_by key is refused by translation

- **WHEN** `POST /v1/queries/translate` is given a query grouping by a `map` field
- **THEN** the request is rejected with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction
