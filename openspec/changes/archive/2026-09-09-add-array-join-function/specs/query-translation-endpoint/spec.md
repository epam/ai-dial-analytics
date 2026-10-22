## ADDED Requirements

### Requirement: Array expansion renders and round-trips

`POST /v1/queries/translate` SHALL render an `array_join` call as the lowercase single-operand
function `array_join(...)` over its rendered operand, within the subset
`POST /v1/queries/execute-sql` accepts. An `array` field SHALL render as `array_join`'s operand on the
same terms it renders as an array accessor's first argument, and an array-returning call (`map_keys`,
`split_string`, `json_extract_array`) SHALL render nested there.

Because the result is a scalar, a rendered `array_join` projection SHALL be usable as the statement's
`GROUP BY` key through its alias, and the rendered statement SHALL therefore carry the alias in both
the select list and the `GROUP BY` clause for an aggregate-mode query grouping by it.

An `array_join` call SHALL render wherever the DSL accepts one, including in a rendered `WHERE` or
`HAVING` clause and inside an aggregate's arguments — the renderer SHALL NOT refuse positions the DSL
and the SQL subset both accept.

#### Scenario: Expansion renders over an array field

- **WHEN** `POST /v1/queries/translate` is given a row-mode query selecting `array_join(topics)`
  aliased `topic`
- **THEN** the rendered SQL selects `array_join("topics") AS "topic"` from the quoted entity name

#### Scenario: Grouping by an expanded element renders

- **WHEN** an aggregate-mode query selects `array_join(topics)` aliased `topic` plus `count()` aliased
  `requests`, grouped by `topic`
- **THEN** the rendered SQL selects both entries and groups by `"topic"`

#### Scenario: Expansion round-trips

- **WHEN** a DSL query using `array_join` — once over a plain `array` field, once nested over
  `split_string`, and once as a filter operand — is translated to SQL and that SQL is submitted to
  `POST /v1/queries/translate-sql`
- **THEN** the returned DSL is semantically equivalent to the input

## MODIFIED Requirements

### Requirement: Map and array accessors render and round-trip

`POST /v1/queries/translate` SHALL render the map accessors `map_value`, `map_contains` and `map_keys`
and the array accessors `array_has`, `array_has_any`, `array_has_all` and `array_length` as lowercase
function calls over the double-quoted field name, each within the subset
`POST /v1/queries/execute-sql` accepts.

The projection-only carve-outs SHALL be rendered on the same terms the SQL subset accepts them: a
`map` field SHALL render as a map accessor's first argument, and an `array` field as an array
accessor's or `array_join`'s first argument, while a DSL query using either field in any other
position SHALL continue to fail closed with HTTP 400 and `error` `bad_request`, naming the field and
the projection-only restriction. Both directions SHALL agree on the carve-out's position and function
set, or the round-trip breaks. `array_join`'s own rendering is stated by "Array expansion renders and
round-trips".

`map_keys` and `split_string` SHALL render as lowercase function calls, and their array results SHALL
be subject to the same projection-only rendering rule as `json_extract_array`'s: a DSL query using one
outside a plain projection — other than as an array accessor's or `array_join`'s operand — SHALL fail
closed rather than render a statement the SQL subset would reject. A nested
`array_length(map_keys(m))` SHALL therefore render, and SHALL round-trip, and so SHALL
`array_join(split_string(t, ','))`.

#### Scenario: Map accessor renders over a map field

- **WHEN** `POST /v1/queries/translate` is given an aggregate query grouping by
  `map_value(point_attributes, 'model')`
- **THEN** the rendered SQL selects and groups by that call over the quoted field name, and the map
  field is not refused

#### Scenario: Map field outside the carve-out still fails closed

- **WHEN** a DSL query sorts on, groups by, or filters on a bare `map` field
- **THEN** the request is refused with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction

#### Scenario: Array predicate renders over an array field

- **WHEN** a DSL query filters on `array_has` over an `array` enrichment field
- **THEN** the rendered SQL carries that call over the quoted flat field name in its `WHERE` clause

#### Scenario: Accessors round-trip

- **WHEN** a DSL query using `map_value`, `map_contains`, `map_keys`, `array_has` and `array_length` is
  translated to SQL and that SQL is submitted to `POST /v1/queries/translate-sql`
- **THEN** the returned DSL is semantically equivalent to the input

