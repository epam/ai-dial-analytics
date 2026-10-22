## MODIFIED Requirements

### Requirement: Supported function catalog discovery

The system SHALL expose, at `GET /v1/queries/functions`, the closed catalog of functions accepted in
structured query expressions (see [structured-query](../structured-query/spec.md), "Supported
function catalog"). The response SHALL be a JSON array with one entry per supported function, in
stable alphabetical order by function name. Each entry SHALL carry:

- `name` — the function's wire name as accepted in a `fn` expression;
- `group` — one of `scalar`, `aggregate`, `ordered_set_aggregate`;
- `signature` — a human-readable signature string (e.g. `date_bin(amount, unit, timestamp)`);
- `returns` — one of `string`, `integer`, `long`, `numeric`, `boolean`, `timestamp`, `array`,
  `same_as_argument`; `array` names the shape only — the element type follows the operand, as
  `same_as_argument` does for the scalar case;
- `distinct_supported` — whether the `distinct` flag is meaningful for the function;
- `description` — a short human-readable description;
- `args` — the ordered argument list; each argument SHALL carry its `name`, its `kind` (one of
  `expression`, `integer_literal`, `numeric_literal`, `string_literal`), an `optional` flag when the
  argument may be omitted (e.g. `count`'s single argument), a `variadic` flag when the argument may
  be repeated (e.g. a `json_extract_*` path key), and — only when applicable — a `constraints` object
  with `allowed_values` (closed value set) and/or `min`/`max` (inclusive numeric bounds). Inapplicable
  members SHALL be omitted from the JSON rather than serialized as null.

The advertised catalog SHALL match what the query translator accepts: every listed function with a
conforming argument list SHALL be accepted by query validation, and the advertised constraint values
(e.g. `date_bin` units, `percentile_cont`/`percentile_disc` fraction bounds) SHALL equal the values
the translator enforces.

#### Scenario: Function catalog lists all supported functions

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the response lists exactly the functions of the structured-query supported function
  catalog — including `date_bin`, `width_bucket`, `count`, `sum`, `avg`, `min`, `max`,
  `arg_max`, `group_uniq_array`, `percentile_cont`, `percentile_disc`, `lower`, `upper`, `length`, `trim`,
  `abs`, `json_extract_string`, `json_extract_long`, `json_extract_double`, and `json_extract_bool` —
  in alphabetical order, each with its `group`, `signature`, `returns`, `distinct_supported`,
  `description`, and `args`

#### Scenario: date_bin entry advertises its literal constraints

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the `date_bin` entry's `args` declare `amount` as an `integer_literal` with `min` 1,
  `unit` as a `string_literal` whose `allowed_values` are exactly the units accepted by the
  translator (`second`, `minute`, `hour`, `day`, `week`), and `timestamp` as an `expression`

#### Scenario: Aggregate entries advertise distinct support

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** `count`, `sum`, and `avg` carry `distinct_supported: true`, and `count`'s single argument
  is marked `optional` (arity 0 or 1), while scalar functions carry `distinct_supported: false`

#### Scenario: json_extract entries advertise a variadic literal path

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** each `json_extract_*` entry declares `json` as an `expression` and `key` as a
  `string_literal` marked `variadic`, and carries a fixed `returns` of `string`, `long`, `numeric`,
  or `boolean` respectively

#### Scenario: arg_max is discoverable with its two-argument signature
- **WHEN** a client lists the function catalog
- **THEN** `arg_max` appears in the aggregate group with signature `arg_max(value, order)`, two required
  expression arguments, a return type of same-as-argument, and `distinct_supported` false
