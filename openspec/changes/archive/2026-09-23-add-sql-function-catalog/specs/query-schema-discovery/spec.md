## ADDED Requirements

### Requirement: SQL function catalog discovery

The system SHALL expose, at `GET /v1/queries/sql-functions`, the catalog of function-like spellings
that the ad-hoc SQL surface accepts — `POST /v1/queries/execute-sql`, `validate-sql`, `translate-sql`
and Flight SQL — so that a caller who writes SQL learns what they may write from discovery alone rather
than from the DSL catalog, whose names and signatures differ on this surface. The response SHALL be a
JSON **object** `{"functions": [...]}`, with the entries in stable order by name compared
case-insensitively.

Each entry SHALL carry:

- `name` — the primary SQL spelling, lowercase (`length`, `mod`, `coalesce`, `+`, `like`, `case`);
- `form` — `function` for a `name(args)` call, `operator` for an infix or prefix operator (`+`, `||`,
  `LIKE`, `ILIKE`), or `syntax` for a keyword construct (`CASE … END`, `CAST(x AS VARCHAR)`);
- `aliases` — the other spellings accepted with the same meaning, lowercase, possibly empty (for
  example `char_length` and `character_length` for `length`, `ceiling` for `ceil`, `current_timestamp` and
  `localtimestamp` for `now`, `not like` for `like`);
- `group`, `returns`, `distinct_supported`, `deterministic` and `args` — with the meanings and value sets
  of the DSL catalog (see "Supported function catalog discovery"); an entry lowering onto a DSL catalog
  function SHALL carry that function's values for all five, except that an `operator` entry whose
  operand count is fixed by its syntax MAY declare its own `args` — `||` is binary, so it lists two
  `expression` operands `a` and `b` where `concat` declares a variadic `value`;
- `signature` — the spelling **as written in SQL**, which SHALL differ from the DSL signature wherever
  SQL requires a different form: `percentile_cont(fraction) WITHIN GROUP (ORDER BY value)`,
  `count(*)` alongside `count([DISTINCT] value)`, `array_has_any(array, ARRAY[value, ...])`, and an `OVER`
  clause on the window-only functions;
- `description` — a short human-readable description, stating the SQL-specific rules where they differ
  from the DSL function it lowers onto;
- `dsl_function` — the name of the DSL catalog function the spelling lowers onto, or `null` where the
  lowering target is not a catalog function (`coalesce`, `nullif` and `case` lower onto the DSL `case`
  expression; `like` and `ilike` onto the filter grammar's contains comparisons);
- `example` — one complete `SELECT` statement using the entry, which the SQL surface accepts.

The catalog SHALL be exact in both directions:

- every entry, written as its `example` shows and under each of its `aliases`, SHALL be accepted by the
  SQL surface;
- every DSL catalog function SHALL be reachable from at least one entry's `dsl_function`, so every DSL
  function has a discoverable SQL spelling;
- every caller-facing function name the SQL surface registers SHALL be an entry `name` or alias —
  internal markers the surface refuses when a caller spells them (`to_string_cast`, `__lambdaparam`)
  SHALL NOT be listed;
- the five DSL arithmetic names `plus`, `minus`, `multiply`, `div` and `modulo`, which the SQL surface
  does not accept as function names, SHALL NOT appear as a `name` or alias. Their SQL spellings are the
  operator entries `+`, `-`, `*`, `/`, `%` (and `mod` for `%`).

The catalog lists spellings that compute a value. The predicate and statement grammar — comparisons,
`IN`, `BETWEEN`, `IS NULL`, `AND`/`OR`/`NOT`, joins and set operations — is not listed. It is
documented in `docs/sql-reference.md`.

The endpoint SHALL inherit the `/v1/**` authorization, like `GET /v1/queries/functions`. The published
API description of `GET /v1/queries/functions` SHALL state that it lists the DSL's `fn` names, and
SHALL point SQL callers at this endpoint, so that a caller who knows only one of the two can find the
other.

#### Scenario: SQL catalog is an object envelope in stable order

- **WHEN** `GET /v1/queries/sql-functions` is called
- **THEN** the response is a JSON object whose `functions` member is an array ordered by `name`
  compared case-insensitively, and every entry carries `name`, `form`, `aliases`, `group`, `signature`,
  `returns`, `distinct_supported`, `deterministic`, `description`, `dsl_function`, `example` and `args`

#### Scenario: DSL-only arithmetic names are absent and their operators present

- **WHEN** a client lists the SQL function catalog
- **THEN** no entry's `name` or alias is `plus`, `minus`, `multiply`, `div` or `modulo`
- **AND** operator entries `+`, `-`, `*`, `/` and `%` exist with `form` `operator` and `dsl_function`
  `plus`, `minus`, `multiply`, `div` and `modulo` respectively, and a `function` entry `mod` has
  `dsl_function` `modulo`

#### Scenario: SQL-only spellings are discoverable

- **WHEN** a client lists the SQL function catalog
- **THEN** `coalesce` and `nullif` appear as `function` entries, and `case` and `cast` as `syntax`
  entries, with `cast`'s `dsl_function` `to_string`
- **AND** `like` and `ilike` appear as `operator` entries returning `boolean`, each `description` stating
  that only the `'%text%'` contains pattern is accepted, with `not like` and `not ilike` as their aliases
- **AND** `||` appears as an `operator` entry with `dsl_function` `concat`, carrying `concat`'s
  `group`, `returns`, `distinct_supported` and `deterministic`, and two `expression` args `a` and `b`

#### Scenario: Synonyms are listed as aliases of one entry

- **WHEN** a client lists the SQL function catalog
- **THEN** the `length` entry lists `char_length` and `character_length` as aliases, `ceil` lists
  `ceiling`, and `now` lists `current_timestamp` and `localtimestamp`, and the `now` entry keeps
  `deterministic` false

#### Scenario: SQL signatures are written the way SQL requires

- **WHEN** a client lists the SQL function catalog
- **THEN** `percentile_cont` and `percentile_disc` carry signatures containing
  `WITHIN GROUP (ORDER BY`, `count`'s signature includes `count(*)`, `array_has_any` and
  `array_has_all` carry signatures using `ARRAY[`, and `row_number`, `rank` and `dense_rank` carry
  signatures containing `OVER (`

#### Scenario: Every example is accepted by the SQL surface

- **WHEN** each entry's `example` is submitted to `POST /v1/queries/translate-sql`
- **THEN** translation succeeds for every entry

#### Scenario: A new DSL function without a SQL spelling fails the build

- **WHEN** a DSL catalog function is added with no SQL catalog entry lowering onto it
- **THEN** the parity test between the two catalogs fails, naming the function

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
- `deterministic` — whether two evaluations of the function over the same input rows are guaranteed to
  produce the same value. It SHALL be present on **every** entry rather than only where it is `false`,
  because a client reading a catalog that omits it cannot tell "deterministic" from "an older service
  that did not say". Where it is `false`, the entry's `description` SHALL say what the value depends
  on. The property is what the derivation paths refuse a function by (see
  [structured-query](../structured-query/spec.md), "The catalog declares which functions are
  non-deterministic"), so publishing it lets a client predict a pipeline rejection from discovery
  alone instead of discovering it on submission;
- `description` — a short human-readable description;
- `args` — the ordered argument list; each argument SHALL carry its `name`, its `kind` (one of
  `expression`, `integer_literal`, `numeric_literal`, `string_literal`, `lambda`), an `optional` flag when the
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
  `concat`, `to_string`, `split_string`, `now`, `date_add`, `date_sub`, `date_trunc`, `match`, `arrayExists`,
  `arrayAll`, `arrayCount`, `arrayFilter`, `arrayMap`, `groupUniqArrayArray`, `groupArrayArray`,
  `row_number`, `rank`
  and `dense_rank` —
  in alphabetical order, each with its `group`, `signature`, `returns`, `distinct_supported`,
  `deterministic`, `description`, and `args`

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
- **AND** each of the five `description`s states that the name is **not callable in SQL**, names the
  operator to write there instead (`+`, `-`, `*`, `/`, `%`), and points at `GET /v1/queries/sql-functions`
  for the SQL spellings — none of them says the name is "also" spelled with the operator, which would
  imply the function name works in SQL too

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


#### Scenario: The clock is discoverable and marked non-deterministic

- **WHEN** a client lists the function catalog
- **THEN** `now` appears in the scalar group with the signature `now()`, a `returns` of `timestamp`,
  an empty `args` list, `distinct_supported` false, and `deterministic` **false** — with a
  `description` stating that its value is the data store's clock at execution, in UTC, and that it is
  refused where a derivation must be reproducible

#### Scenario: The offset functions advertise their literal constraints

- **WHEN** a client lists the function catalog
- **THEN** `date_add` and `date_sub` each appear in the scalar group with a `returns` of `timestamp`,
  declaring `unit` as a `string_literal` whose `allowed_values` are exactly the units the translator
  accepts (`second`, `minute`, `hour`, `day`, `month`, `year`), `amount` as an `integer_literal` with
  `min` 1, and `timestamp` as an `expression`

#### Scenario: Every entry states its determinism

- **WHEN** a client lists the function catalog
- **THEN** every entry carries a `deterministic` member, and `now` is the only entry whose value is
  `false`

#### Scenario: A higher-order entry declares its lambda argument

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the `arrayExists` entry's `group` is `scalar`, its `returns` is `boolean`, and its `args`
  declare the first argument with `kind` `lambda` and the second as an `expression` — so a client can
  tell from discovery alone that the call takes a lambda, and in which position

#### Scenario: A higher-order array result is advertised as an array

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** the `arrayFilter` and `arrayMap` entries' `returns` is `array`, and the
  `groupUniqArrayArray` and `groupArrayArray` entries are `group` `aggregate` with `returns` `array`
  and `distinct_supported` `false`, each `description` stating why the quantifier does not apply

#### Scenario: The cost of a higher-order call is documented

- **WHEN** `GET /v1/queries/functions` is called
- **THEN** each higher-order entry's `description` states that it reads every element and cannot use a
  data-skipping index, and each array-valued aggregate's `description` states that it holds an
  accumulator per group — the same way `json_extract_string_ci`'s cost is already documented
