## ADDED Requirements

### Requirement: Arithmetic renders as operators and round-trips

`POST /v1/queries/translate` SHALL render the arithmetic catalog functions `plus`, `minus`, `multiply`,
`div` and `modulo` as the **infix operators** `+`, `-`, `*`, `/` and `%` rather than as function calls,
with
parentheses inserted wherever the expression tree's nesting differs from standard SQL precedence. The
rendered statement SHALL be within the subset `POST /v1/queries/execute-sql` accepts and SHALL
translate back to a structured query semantically equivalent to the input.

Operator rendering is chosen over the function spelling because the rendered SQL's audience is external
BI clients and generated-SQL consumers, for which the operator form is the portable one. The round-trip
is what keeps the choice safe: `sql-query-translation` lowers each operator back to the same catalog
function, so `div` → `/` → `div` and `modulo` → `%` → `modulo` are stable, and a translated statement
re-submitted to `/translate-sql` SHALL yield the original DSL. `modulo` SHALL NOT render as
`MOD(a, b)`: the subset accepts that spelling inbound, but rendering one canonical form per operation
keeps the round-trip single-valued.

Rendering the operator is also the only option: the arithmetic catalog names are not SQL functions at
all (see `sql-query-translation`), so a rendering that emitted `minus(a, b)` would produce a statement
the subset cannot read back.

`round`, `floor` and `ceil` have no operator form and SHALL render as lowercase function calls, as
every other catalog function does.

#### Scenario: Division renders as an operator

- **WHEN** `POST /v1/queries/translate` is given an aggregate query selecting
  `div(sum(cached_prompt_tokens), sum(prompt_tokens))` aliased `rate`
- **THEN** the rendered SQL carries the division as an infix `/` between the two `sum` calls aliased
  `rate`, not a `div(...)` call

#### Scenario: Arithmetic round-trips through both endpoints

- **WHEN** a DSL query using `div`, `plus` and `round` is translated to SQL and that SQL is submitted to
  `POST /v1/queries/translate-sql`
- **THEN** the returned DSL is semantically equivalent to the input, each operator having lowered back
  to its catalog function

#### Scenario: Precedence is preserved with parentheses

- **WHEN** a DSL query selects `multiply(plus(a, b), c)`
- **THEN** the rendered SQL parenthesises the addition so that re-translation reproduces the same
  expression tree rather than the precedence-default grouping

#### Scenario: Remainder renders as the percent operator

- **WHEN** a DSL query selects `modulo(a, 3)`
- **THEN** the rendered SQL carries the remainder as an infix `%`, not a `modulo(...)` or `MOD(...)` call

#### Scenario: Rounding renders as a function call

- **WHEN** a DSL query selects `round(div(sum(x), count()), 2)`
- **THEN** the rendered SQL carries a lowercase `round` call whose first argument is the infix division
  and whose second is the integer literal

### Requirement: Map and array accessors render and round-trip

`POST /v1/queries/translate` SHALL render the map accessors `map_value`, `map_contains` and `map_keys`
and the array accessors `array_has`, `array_has_any`, `array_has_all` and `array_length` as lowercase
function calls over the double-quoted field name, each within the subset
`POST /v1/queries/execute-sql` accepts.

The projection-only carve-outs SHALL be rendered on the same terms the SQL subset accepts them: a
`map` field SHALL render as a map accessor's first argument and an `array` field as an array accessor's
first argument, while a DSL query using either field in any other position SHALL continue to fail
closed with HTTP 400 and `error` `bad_request`, naming the field and the projection-only restriction.
Both directions SHALL agree on the carve-out's position and function set, or the round-trip breaks.

`map_keys` and `split_string` SHALL render as lowercase function calls, and their array results SHALL
be subject to the same projection-only rendering rule as `json_extract_array`'s: a DSL query using one
outside a plain projection — other than as an array accessor's first argument — SHALL fail closed
rather than render a statement the SQL subset would reject. A nested `array_length(map_keys(m))` SHALL
therefore render, and SHALL round-trip.

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

### Requirement: String time literals normalize on the way back to DSL

`POST /v1/queries/translate-sql` SHALL accept the ISO-8601 string spellings that
`sql-query-translation` coerces against a `timestamp` column, and SHALL return a DSL `value` expression
carrying `value_type` `timestamp` with the epoch-millis encoding — never a `string` literal that the
execute path would then reject. Re-rendering that DSL through `POST /v1/queries/translate` SHALL emit
the canonical `TIMESTAMP` literal form, so the round-trip normalizes a client's loose spelling into the
subset's explicit one rather than preserving it.

#### Scenario: Loose date string normalizes to a timestamp value

- **WHEN** `POST /v1/queries/translate-sql` is given a filter comparing a timestamp column to the bare
  string `'2026-08-20'`
- **THEN** the returned DSL carries a `value` expression of `value_type` `timestamp` encoding midnight
  UTC of that date

#### Scenario: Re-rendering emits the canonical timestamp literal

- **WHEN** that returned DSL is submitted to `POST /v1/queries/translate`
- **THEN** the rendered SQL carries the explicit `TIMESTAMP` literal form rather than the bare string
