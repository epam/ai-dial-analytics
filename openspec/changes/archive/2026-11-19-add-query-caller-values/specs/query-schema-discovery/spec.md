## MODIFIED Requirements

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

The two caller values (see [sql-query-translation](../sql-query-translation/spec.md), "Caller values in SQL") SHALL be
listed as `function` entries named `caller.user_hash` and `caller.project`, with no aliases, `group` `scalar`,
`returns` `string`, `distinct_supported` false, `deterministic` false, an empty `args` array, the `signature`
`caller.user_hash()` or `caller.project()`, `dsl_function` `null`
(each lowers onto the DSL `param` of its name), a `description` stating that the value is the executing caller's and
may only be compared with `=` or `<>` against a `string` column in a filter, and an `example` filtering on it. Every
other rule of this requirement applies to them, so their `example`s SHALL be accepted by the SQL surface, and any
internal operator name the surface uses to carry them is an internal marker and SHALL NOT be listed.

The catalog lists spellings that compute a value. The predicate and statement grammar — comparisons,
`IN`, `BETWEEN`, `IS NULL`, `AND`/`OR`/`NOT`, joins and set operations — is not listed. It is
documented in `docs/sql-reference.md`.

The endpoint SHALL require authentication only, like `GET /v1/queries/functions` (see "Discovery is
authorized per entity"). The published
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

#### Scenario: Caller values are listed

- **WHEN** a client lists the SQL function catalog
- **THEN** `caller.project` and `caller.user_hash` appear as `function` entries with `returns` `string`,
  `deterministic` false, an empty `args` array and `dsl_function` `null`, each with an `example` such as
  `SELECT count(*) FROM "dial_usage_log" WHERE "user_hash" = caller.user_hash()`

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

