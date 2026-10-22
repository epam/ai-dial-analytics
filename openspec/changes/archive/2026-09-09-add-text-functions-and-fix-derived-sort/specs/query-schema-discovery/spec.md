## MODIFIED Requirements

### Requirement: Supported function catalog discovery

The system SHALL expose, at `GET /v1/queries/functions`, the closed catalog of functions accepted in
structured query expressions (see [structured-query](../structured-query/spec.md), "Supported
function catalog" and "Window-only functions extend the catalog"). The response SHALL be a JSON array with
one entry per supported function, in stable alphabetical order by function name. Each entry SHALL carry:

- `name` — the function's wire name as accepted in a `fn` expression;
- `group` — one of `scalar`, `aggregate`, `ordered_set_aggregate`, `window`; `window` marks a function
  accepted only as the `fn` of a `window` expression;
- `signature` — a human-readable signature string (e.g. `date_bin(amount, unit, timestamp)`);
- `returns` — one of `string`, `integer`, `long`, `numeric`, `boolean`, `timestamp`, `array`,
  `same_as_argument`, `element_of_argument`; `array` names the shape only — the element type follows
  the operand, as `same_as_argument` does for the scalar case. `element_of_argument` is the inverse of
  `array`: the result is the **element** type of the function's first argument, which is what
  `array_join` returns, and neither `array` nor `same_as_argument` can name it;
- `distinct_supported` — whether the `distinct` flag is meaningful for the function. Where it is
  `false` for an aggregate, the entry's `description` SHALL state **why** the quantifier does not apply
  (a no-op for `min`/`max`, a different statistic for the percentiles, already-distinct for
  `group_uniq_array`, operand-detaching for `arg_max`), so a client can distinguish a deliberate
  exclusion from an unimplemented one;
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
  `abs`, `json_extract_string`, `json_extract_long`, `json_extract_double`, `json_extract_bool`,
  `json_extract_string_ci`, `json_extract_array`, `if`, `not_empty`, `starts_with`, `contains`,
  `equals`, `plus`, `minus`, `multiply`, `div`, `modulo`, `round`, `floor`, `ceil`, `map_value`,
  `map_contains`, `map_keys`, `array_has`,
  `array_has_any`, `array_has_all`, `array_join`, `array_length`, `array_slice`, `array_to_string`,
  `concat`, `to_string`, `split_string`, `row_number`, `rank`
  and `dense_rank` —
  in alphabetical order, each with its `group`, `signature`, `returns`, `distinct_supported`,
  `description`, and `args`

#### Scenario: The window-only functions are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `row_number`, `rank` and `dense_rank` each appear in the `window` group with no arguments, a
  `returns` of `long`, `distinct_supported` false, and a `description` stating that the function is
  accepted only inside a `window` expression (an `OVER` clause in SQL)

#### Scenario: The arithmetic scalars are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `plus`, `minus`, `multiply`, `div` and `modulo` each appear in the scalar group with two
  required expression arguments and a `returns` of `numeric`, and the `description` of `div` and of
  `modulo` each state that a zero or null right operand yields null rather than an error, so a client
  can tell the contract from the catalog alone. `modulo`'s `description` additionally states that the
  result takes the sign of the left operand

#### Scenario: The rounding scalars advertise their optional precision

- **WHEN** a client lists the function catalog
- **THEN** `round` declares a second `digits` argument of kind `integer_literal` marked `optional`,
  while `floor` and `ceil` each declare a single required expression argument, all three with a
  `returns` of `numeric`

#### Scenario: The map accessors advertise a single literal key

- **WHEN** a client lists the function catalog
- **THEN** `map_value` (`returns` `string`) and `map_contains` (`returns` `boolean`) each appear in the
  scalar group declaring `map` as an `expression` and `key` as a `string_literal` that is **not** marked
  `variadic` — distinguishing them from the `json_extract_*` entries, whose path keys are variadic —
  and each `description` states that the key is matched literally rather than as a path

#### Scenario: The map key lister advertises an array return and single arity

- **WHEN** a client lists the function catalog
- **THEN** `map_keys` appears in the scalar group with a `returns` of `array` and exactly one
  `expression` argument, so a client can tell from the catalog alone that its result is projection-only
  as `json_extract_array`'s is

#### Scenario: The array predicates advertise a boolean return

- **WHEN** a client lists the function catalog
- **THEN** `array_has`, `array_has_any` and `array_has_all` each appear in the scalar group with a
  fixed `returns` of `boolean`, so a client can tell they may supply `if`'s condition, and
  `array_length` appears with a `returns` of `integer`

#### Scenario: The string splitter advertises an array return

- **WHEN** a client lists the function catalog
- **THEN** `split_string` appears in the scalar group with a `returns` of `array`, declaring `text` as
  an `expression` and `delimiter` as a `string_literal`

#### Scenario: A refused distinct quantifier carries its reason

- **WHEN** a client lists the function catalog
- **THEN** each aggregate carrying `distinct_supported` false — `min`, `max`, `percentile_cont`,
  `percentile_disc`, `group_uniq_array`, `arg_max` — carries a `description` naming why the quantifier
  does not apply to it

#### Scenario: The conditional and its predicate producers are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `if` appears in the scalar group with signature `if(condition, then, else)` and a return
  type of same-as-argument, and `not_empty`, `starts_with`, `contains` and `equals` each appear in
  the scalar group with a fixed `returns` of `boolean`, so a client can tell from the catalog alone
  which functions may supply `if`'s condition

#### Scenario: The case-insensitive extractor advertises its fixed arity

- **WHEN** a client lists the function catalog
- **THEN** `json_extract_string_ci` declares `json` as an `expression` and `key` as a
  `string_literal` that is **not** marked `variadic`, distinguishing it from the other
  `json_extract_*` entries, and carries a fixed `returns` of `string`

#### Scenario: The array extractor advertises an array return

- **WHEN** a client lists the function catalog
- **THEN** `json_extract_array` appears in the scalar group with a `returns` of `array`, declaring
  `json` as an `expression` and `key` as a `variadic` `string_literal`

#### Scenario: date_bin entry advertises its literal constraints

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the `date_bin` entry's `args` declare `amount` as an `integer_literal` with `min` 1,
  `unit` as a `string_literal` whose `allowed_values` are exactly the units accepted by the
  translator (`second`, `minute`, `hour`, `day`, `week`), and `timestamp` as an `expression`

#### Scenario: Aggregate entries advertise distinct support

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** `count`, `sum`, and `avg` carry `distinct_supported: true`, and `count`'s single argument
  is marked `optional` (arity 0 or 1), while scalar functions carry `distinct_supported: false`

#### Scenario: The path-addressable json_extract entries advertise a variadic literal path

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** each of `json_extract_string`, `json_extract_long`, `json_extract_double` and
  `json_extract_bool` declares `json` as an `expression` and `key` as a `string_literal` marked
  `variadic`, and carries a fixed `returns` of `string`, `long`, `numeric`, or `boolean` respectively —
  the two later members of the family differ on both counts and are covered by their own scenarios
  above (`json_extract_string_ci`'s `key` is not variadic; `json_extract_array` returns `array`)

#### Scenario: arg_max is discoverable with its two-argument signature
- **WHEN** a client lists the function catalog
- **THEN** `arg_max` appears in the aggregate group with signature `arg_max(value, order)`, two required
  expression arguments, a return type of same-as-argument, and `distinct_supported` false

#### Scenario: The array expansion function is discoverable

- **WHEN** a client lists the function catalog
- **THEN** `array_join` appears in the scalar group with the signature `array_join(array)`, a
  `returns` of `element_of_argument`, `distinct_supported` false, and exactly one required `array`
  expression argument — with a `description` stating that the call produces one row per element and
  therefore changes the number of rows the query returns

#### Scenario: The text composition functions are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `concat` appears in the scalar group with the signature
  `concat(value, value[, value…])`, a `returns` of `string`, `distinct_supported` false, and a
  `variadic` second argument marking that further operands may be repeated; and `to_string` appears
  with the signature `to_string(value)`, a `returns` of `string` and one required expression argument

#### Scenario: The array text and slicing functions are discoverable

- **WHEN** a client lists the function catalog
- **THEN** `array_to_string` appears in the scalar group with the signature
  `array_to_string(array, delimiter)`, a `returns` of `string`, and a `string_literal` second
  argument; and `array_slice` appears with the signature `array_slice(array, offset, length)`, a
  `returns` of `array`, and two `integer_literal` bound arguments
