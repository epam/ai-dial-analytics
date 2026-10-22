## MODIFIED Requirements

### Requirement: Supported SQL subset maps onto StructuredQuery

The translator SHALL map exactly the following SQL surface onto `StructuredQuery`, and SHALL
produce a query semantically equivalent to the corresponding structured-query JSON:
- single-entity `SELECT` (one table reference; the entity becomes `entity`)
- projection of columns and supported function calls, with **optional** `AS` aliases (`select`) — an
  unnamed expression takes a derived name instead of being rejected or named `EXPR$n`
- `WHERE` as an AND/OR/NOT tree of supported predicates (`filter`)
- `GROUP BY` on columns or select aliases, or on a computed expression that also appears in the
  select list under an output name that is not an entity column (`group_by`,
  `mode="aggregate"`); absence of aggregation yields `mode="row"`
- `HAVING` (`having`)
- `ORDER BY` with per-key `ASC`/`DESC` and `NULLS FIRST`/`LAST` (`sort`)
- `LIMIT`/`OFFSET` (`OffsetPage`; downstream limit governance applies — a `LIMIT` above the
  structured-query maximum is rejected, not clamped)
- `SELECT DISTINCT` in row mode (`distinct=true`)
- predicates: `=`, `<>`, `<`, `>`, `<=`, `>=` → `eq/ne/lt/gt/le/ge`; `IN (literal list)` →
  `in`; `IS NULL`/`IS NOT NULL` → `eq`/`ne` against a null literal
- functions, 1:1 with the DSL catalog: `lower, upper, length, trim, abs, width_bucket,
  date_bin, date_trunc, if, not_empty, starts_with, contains, equals, json_extract_string,
  json_extract_long, json_extract_double, json_extract_bool, json_extract_string_ci,
  json_extract_array` (scalar); `count` (incl. `COUNT(*)` and `COUNT(DISTINCT x)`), `sum`, `avg`, `min`,
  `max`, `arg_max`, `group_uniq_array`, `percentile_cont`, `percentile_disc` (aggregate). Every DSL catalog name SHALL be an
  accepted SQL spelling — the DSL→SQL renderer emits catalog names verbatim, so a rejected
  catalog spelling would break the render/translate round-trip. For `length`, the standard SQL
  synonyms `char_length` and `character_length` SHALL also be accepted, all mapping to the DSL
  `length` function.

  Every catalog name SHALL be registered as an ADAS operator resolved **ahead of** Calcite's
  standard operator table, so that a name Calcite also defines — whether as a builtin with different
  operand order, a dialect-library function, or a form its standard convertlet rewrites — lowers to
  the ADAS function rather than to Calcite's. Each catalog name SHALL be spelled identically as the
  DSL wire name and the SQL name, so that the render/translate round-trip holds. `IF`, `CONTAINS` and
  `EQUALS` are keywords of the Babel grammar but **non-reserved** ones, so all three parse as ordinary
  function calls and need no alternative spelling; a permanent front-end test SHALL hold that, since a
  Calcite upgrade that reserved one of them would break every rendered statement carrying it.

  A `SELECT` whose projection is a bare comparison (`SELECT a > b FROM …`) SHALL remain rejected:
  comparisons map onto the filter grammar only, and `if`'s condition SHALL be supplied by a
  boolean-returning function call. Conversely a boolean-returning function call is an **expression,
  not a predicate**: `WHERE not_empty(x)` — a bare function call as a `WHERE` or `HAVING` condition —
  SHALL be rejected as an unsupported construct, because the DSL's filter grammar is built from
  comparisons and has no node for one. The equivalent test SHALL be written as an ordinary comparison
  (`WHERE x <> ''`).

#### Scenario: Aggregate query round-trips
- **WHEN** a client submits `SELECT model, count(*) AS n FROM dial_usage_log WHERE price > 0 GROUP BY model HAVING count(*) > 10 ORDER BY n DESC LIMIT 50`
- **THEN** the translator produces a StructuredQuery with `mode="aggregate"`, `filter` on price, `group_by=["model"]`, a `count` FnExpr aliased `n`, `having` on count, sort on `n` desc, and an OffsetPage with limit 50

#### Scenario: Row query with defaults
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log`
- **THEN** the translator produces `mode="row"` with no filter and offset paging with the default limit

#### Scenario: SELECT DISTINCT maps to distinct flag
- **WHEN** a client submits `SELECT DISTINCT model FROM dial_usage_log`
- **THEN** the translator produces a row-mode query with `distinct=true`, not an aggregate query

#### Scenario: Every length spelling maps to the DSL length function
- **WHEN** a client submits `SELECT length(deployment) FROM dial_usage_log`, or the same statement spelled `char_length(...)` or `character_length(...)`
- **THEN** each translates to the DSL `length` function on `deployment`

#### Scenario: Aggregate query without an alias round-trips
- **WHEN** a client submits `SELECT model, count(*) FROM dial_usage_log GROUP BY model ORDER BY count(*) DESC`
- **THEN** the translator produces an aggregate query whose outputs are named `model` and `count`, with
  the sort resolved against the `count` output

#### Scenario: arg_max translates as a two-operand aggregate
- **WHEN** a client submits `SELECT response_id, arg_max(rate, request_time) AS final_rate FROM rate_analytics GROUP BY response_id`
- **THEN** the translator produces an aggregate-mode StructuredQuery with an `arg_max` FnExpr over `rate` and `request_time` aliased `final_rate`, and the result schema types that column as the operand's type

#### Scenario: DISTINCT on arg_max rejected
- **WHEN** a client submits `SELECT response_id, arg_max(DISTINCT rate, request_time) FROM rate_analytics GROUP BY response_id`
- **THEN** translation fails before execution with an error stating the function does not accept `DISTINCT`

#### Scenario: arg_max with wrong arity rejected
- **WHEN** a client submits `arg_max` with one argument or with three
- **THEN** translation fails before execution with an error naming the function's two-argument signature

#### Scenario: arg_max over a projection-only operand rejected
- **WHEN** a client submits `arg_max` whose `value` or `order` operand is a projection-only column (`array`, `object`, `map`)
- **THEN** translation fails, under the same projection-only classification that applies to every other function's operands — `arg_max` adds no rule of its own

#### Scenario: A nested conditional translates
- **WHEN** a client submits `SELECT if(not_empty(chat_id), 'chat', if(starts_with(lower(deployment), 'gpt'), 'gpt', 'other')) AS kind FROM dial_usage_log`
- **THEN** the translator produces a row-mode StructuredQuery with nested `if` FnExprs aliased `kind`, and the result schema types the column as text

#### Scenario: A bare comparison in the projection is still rejected
- **WHEN** a client submits `SELECT if(length(deployment) > 0, 'a', 'b') FROM dial_usage_log`
- **THEN** translation fails before execution, because the comparison has no expression-position form

#### Scenario: A boolean function call is not a predicate
- **WHEN** a client submits `SELECT chat_id FROM dial_usage_log WHERE not_empty(chat_id)`
- **THEN** translation fails before execution with the unsupported-construct error — the filter
  grammar is built from comparisons, and the same test is expressible as `WHERE chat_id <> ''`

#### Scenario: Standard COALESCE and NULLIF remain unsupported
- **WHEN** a client submits `SELECT coalesce(chat_id, trace_id) FROM dial_usage_log` or `SELECT nullif(chat_id, '') FROM dial_usage_log`
- **THEN** translation fails with the unsupported-construct error, since neither name is in the catalog

### Requirement: Object fields are projection-only in SQL

An `object`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased) **or as the first argument of a `JSON_EXTRACT_STRING`/`JSON_EXTRACT_LONG`/
`JSON_EXTRACT_DOUBLE`/`JSON_EXTRACT_BOOL`/`JSON_EXTRACT_STRING_CI`/`JSON_EXTRACT_ARRAY` call**.
Any other use SHALL be rejected at translation
time — before any ClickHouse contact —
with an error naming the object field and stating that object fields can only be projected;
the error SHALL NOT be an "unknown column" error and SHALL NOT surface as a raw ClickHouse
failure. Rejected uses include: as an operand of any predicate or comparison (including
`IS NULL` / `IS NOT NULL`, `LIKE`, and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under
`SELECT DISTINCT`, and as an argument to any scalar or aggregate function other than the
`JSON_EXTRACT_*` family. The resulting
`StructuredQuery` SHALL carry the object field as an ordinary field projection, and the derived
result schema SHALL type the column as an object column.

A `JSON_EXTRACT_*` call whose first argument is an `object` field SHALL translate to the
corresponding DSL `json_extract_*` function expression, exactly as one over a character column does,
and the derived result schema SHALL type the column by the function's fixed return type (text,
long, numeric, boolean, or — for `JSON_EXTRACT_ARRAY` — an array of text) rather than as an object
column. For the five members whose result is a **scalar**, the translated node is an ordinary scalar
expression, so such a call SHALL be accepted wherever the SQL subset accepts a scalar expression — in
`WHERE`, `HAVING`, `GROUP BY`, `ORDER BY`, `SELECT DISTINCT`, and as an aggregate argument — and the
restriction above SHALL apply to the bare `object` field only, never to the extraction's result.
`JSON_EXTRACT_ARRAY` is the exception on the second half of that only: its `object` argument is accepted
on the same terms, and its result carries a restriction of its own, stated in the paragraph below.
Path arguments SHALL remain string literals, so an `object` field passed to
`JSON_EXTRACT_*` with a computed path SHALL be rejected for the path, not for the field's type.

`JSON_EXTRACT_ARRAY` is the one member of the family whose **result** is itself projection-only:
it returns an array, so the array restriction (see "Array fields are projection-only in SQL" and
"The array-returning aggregate is projection-only too") applies to its output even though the
`object` first argument is accepted. On this surface that restriction reaches further than on the DSL
one, because the SQL front end types the result and refuses the nesting itself: a call used as another
expression's operand, as an `ORDER BY` key, or as a `GROUP BY` key SHALL be rejected. `SELECT DISTINCT`
over the projected call SHALL be **accepted**, unlike `SELECT DISTINCT` over a declared `array` column:
the rule is about using an array as an **operand**, and a distinct projection does not — it
deduplicates the projected rows. `JSON_EXTRACT_STRING_CI` returns a scalar and carries no such
restriction, but SHALL accept exactly two arguments — a case-insensitive key match is defined for a
single top-level key, so a third argument SHALL be rejected for arity rather than treated as a path
segment.

#### Scenario: Object projection translates
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `jwt_claims`, and the result schema types the column as an object column

#### Scenario: Object field in WHERE rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims IS NOT NULL`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction, and ClickHouse is not contacted

#### Scenario: Object field compared to a literal rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims = 'x'`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field in GROUP BY rejected
- **WHEN** a client submits `SELECT jwt_claims, count(*) FROM dial_usage_log GROUP BY jwt_claims`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field in ORDER BY rejected
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log ORDER BY jwt_claims`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field under SELECT DISTINCT rejected
- **WHEN** a client submits `SELECT DISTINCT jwt_claims FROM dial_usage_log`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field as function argument rejected
- **WHEN** a client submits `SELECT count(jwt_claims) FROM dial_usage_log`
- **THEN** translation fails before execution with an error identifying the unsupported use of the object field

#### Scenario: Star projection over an entity with object columns translates
- **WHEN** a client submits `SELECT * FROM dial_usage_log LIMIT 1` on an entity whose source columns include `object`-typed fields
- **THEN** translation succeeds and the object columns are carried as ordinary field projections

#### Scenario: JSON_EXTRACT over an object field in the projection translates
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING(jwt_claims, 'sub') AS sub FROM dial_usage_log LIMIT 10`
- **THEN** translation succeeds, producing a DSL `json_extract_string` call over the `jwt_claims` field aliased `sub`, and the result schema types the column as text rather than as an object column

#### Scenario: JSON_EXTRACT over an object field in WHERE translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE JSON_EXTRACT_STRING(jwt_claims, 'sub') = 'user-1'`
- **THEN** translation succeeds, producing a DSL `eq` comparison whose left operand is the `json_extract_string` call — the projection-only restriction does not apply to this position

#### Scenario: JSON_EXTRACT over an object field in GROUP BY and ORDER BY translates
- **WHEN** a client submits a statement grouping and ordering by `JSON_EXTRACT_STRING(jwt_claims, 'sub')` with an aggregate
- **THEN** translation succeeds and the group and sort keys reference the extraction's output name, not the bare object field

#### Scenario: JSON_EXTRACT over an object field with a computed path rejected
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING(jwt_claims, request_id) FROM dial_usage_log`
- **THEN** translation fails with an error naming the literal-path restriction on `json_extract_*`, not the object field's type

#### Scenario: The extraction result is not itself projection-restricted
- **WHEN** a client submits `SELECT DISTINCT JSON_EXTRACT_STRING(jwt_claims, 'sub') FROM dial_usage_log`
- **THEN** translation succeeds — `DISTINCT` is restricted over a bare `object` field, not over a scalar extracted from one


#### Scenario: Case-insensitive extraction over an object field translates
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING_CI(request_tags, 'x-session-id') AS sid FROM dial_usage_log`
- **THEN** translation succeeds, producing a DSL `json_extract_string_ci` call over `request_tags` aliased `sid`, and the result schema types the column as text

#### Scenario: Case-insensitive extraction with a path segment rejected
- **WHEN** a client submits `JSON_EXTRACT_STRING_CI(request_tags, 'a', 'b')`
- **THEN** translation fails with an error naming the function's two-argument signature

#### Scenario: Array extraction over an object field translates
- **WHEN** a client submits `SELECT JSON_EXTRACT_ARRAY(jwt_claims, 'roles') AS roles FROM dial_usage_log`
- **THEN** translation succeeds, producing a DSL `json_extract_array` call over `jwt_claims` aliased `roles`, and the result schema types the column as an array with element kind string

#### Scenario: The array extraction's result is projection-only
- **WHEN** a client submits `SELECT chat_id FROM dial_usage_log ORDER BY JSON_EXTRACT_ARRAY(jwt_claims, 'roles')`, the same call as a `GROUP BY` key, or the same call nested in another function
- **THEN** translation fails with an error naming the projection-only restriction on the array result, not on the `jwt_claims` object field

#### Scenario: A distinct projection of the array extraction is accepted
- **WHEN** a client submits `SELECT DISTINCT JSON_EXTRACT_ARRAY(jwt_claims, 'roles') FROM dial_usage_log`
- **THEN** translation succeeds — `DISTINCT` deduplicates the projected rows rather than using the array as an operand, which is the distinction the restriction draws (a declared `array` column under `DISTINCT` is still rejected, because there the restriction is on the field itself)
