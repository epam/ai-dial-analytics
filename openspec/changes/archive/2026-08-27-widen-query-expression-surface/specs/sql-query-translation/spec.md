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
  boolean-returning function call. Conversely a boolean-returning function call **IS a
  predicate**: `WHERE not_empty(x)` — a bare function call as a `WHERE` or `HAVING` condition — SHALL be
  accepted and SHALL lower to the comparison the filter grammar does have, `<call> = TRUE`. The two are
  equivalent in predicate position (a null-valued call filters the row out either way), so this is a
  spelling the grammar can carry rather than a new node in it.

  **This reverses the earlier rule**, which rejected the bare form on the grounds that the equivalent test
  "SHALL be written as an ordinary comparison (`WHERE x <> ''`)". That reasoning held while every boolean
  scalar had a comparison equivalent. The array predicates do not: the subset has no membership operator,
  so there is no comparison spelling of `array_has(roles, 'admin')` to fall back on, and the originating
  request's own example is the bare form. Widened for the whole family rather than for the array
  predicates alone, since the alternative is an arbitrary line through one operand type. Not breaking:
  every one of these previously failed translation.

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

#### Scenario: A boolean function call is a predicate
- **WHEN** a client submits `SELECT chat_id FROM dial_usage_log WHERE not_empty(chat_id)`
- **THEN** translation succeeds and the filter tree carries the call compared to `true`

#### Scenario: An array predicate is a predicate on its own
- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log WHERE ARRAY_HAS(execution_path, 'r')`
- **THEN** translation succeeds — there is no comparison spelling of a membership test to fall back on

#### Scenario: The other boolean scalars gain the same position
- **WHEN** a client submits a `WHERE` clause that is a bare `CONTAINS`, `EQUALS`, `STARTS_WITH`,
  `MAP_CONTAINS` or `JSON_EXTRACT_BOOL` call
- **THEN** translation succeeds

#### Scenario: A non-boolean call is still not a predicate
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log WHERE lower(deployment)`
- **THEN** translation fails — the subset has no truthiness rule for a non-boolean value

#### Scenario: Standard COALESCE and NULLIF remain unsupported
- **WHEN** a client submits `SELECT coalesce(chat_id, trace_id) FROM dial_usage_log` or `SELECT nullif(chat_id, '') FROM dial_usage_log`
- **THEN** translation fails with the unsupported-construct error, since neither name is in the catalog

### Requirement: Literals translate with DSL value typing
SQL literals SHALL convert to `ValueExpr` entries typed by the DSL's `value_type` set (string,
integer, long, decimal, boolean, date, timestamp, uuid, null), with values encoded as the DSL
expects: timestamps as epoch-milliseconds, dates as ISO-8601 dates, numbers as plain decimal
strings. Validator-injected identity or widening casts within the same type family SHALL be
unwrapped and ignored; a `DATE` widened to `TIMESTAMP` (e.g. a date literal compared to a
timestamp column) SHALL convert to midnight UTC of that date. Explicit or narrowing `CAST`s
SHALL be rejected. SQL runtime parameters (`?`) SHALL be rejected.

**A character-string literal compared against a `timestamp` column SHALL coerce to a timestamp** rather
than being rejected as an unsupported `CAST` from `CHAR`. The accepted spellings SHALL be ISO-8601
date (`'2026-08-20'`, taken as midnight UTC of that date), ISO-8601 date-and-time
(`'2026-08-20 00:00:00'`), and ISO-8601 date-and-time with a `T` separator and a `Z` offset
(`'2026-08-20T00:00:00Z'`). A string literal that is not one of those spellings SHALL be rejected with
`invalid_argument` naming the literal and the expected formats, rather than being silently read as a
different instant or surfacing as a raw ClickHouse failure. The coercion SHALL apply only where the
other side of the comparison is a `timestamp` column; a character literal compared against a
character column SHALL remain a string, and the coercion SHALL NOT license `CAST` from `CHAR` to any
other type.

This exists because generated SQL — Grafana, Power BI, and most BI clients — emits bare string literals
for time bounds, so requiring the `TIMESTAMP '…'` prefix fails every such query at translation.

#### Scenario: Timestamp literal
- **WHEN** a filter compares a timestamp field to `TIMESTAMP '2026-07-01 00:00:00'`
- **THEN** the ValueExpr carries value_type `timestamp` and the epoch-millis encoding of that instant

#### Scenario: ISO-8601 date string coerces against a timestamp column
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE request_time >= '2026-08-20'`
- **THEN** translation succeeds and the ValueExpr carries value_type `timestamp` encoding midnight UTC
  of that date

#### Scenario: ISO-8601 date-time spellings coerce against a timestamp column
- **WHEN** the same comparison is written with `'2026-08-20 00:00:00'` and with `'2026-08-20T00:00:00Z'`
- **THEN** both translate and both yield the same epoch-millis encoding

#### Scenario: Unparseable time string is rejected clearly
- **WHEN** a client compares a timestamp column to `'last tuesday'`
- **THEN** translation fails with `invalid_argument` naming the literal and the expected formats, and
  ClickHouse is not contacted

#### Scenario: String coercion does not apply to a non-timestamp column
- **WHEN** a client compares a `string` column to a character literal
- **THEN** the ValueExpr carries value_type `string` and no timestamp coercion occurs

#### Scenario: Parameter marker rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE model = ?`
- **THEN** translation fails with an unsupported-construct error

### Requirement: Array fields are projection-only in SQL

An `array`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased) **or as the first argument of an `ARRAY_HAS`/`ARRAY_HAS_ANY`/`ARRAY_HAS_ALL`/`ARRAY_LENGTH`
call**. Any other use SHALL be rejected at translation time — before any ClickHouse contact —
with an error naming the array field and stating that array fields can only be projected.
Rejected uses include: as an operand of any predicate or comparison (including `IS NULL` /
`IS NOT NULL` and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under `SELECT DISTINCT`
(row-mode distinct deduplicates over the projected array), and as an argument to any scalar or
aggregate function other than the four named above. The resulting `StructuredQuery` SHALL carry the
array field as an ordinary field projection, and the derived result schema SHALL type the column as an
array of its catalog `element_type`.

The carve-out SHALL be **positional and function-specific**, matching the treatment `object` fields
receive for `JSON_EXTRACT_*`: only argument 0, only those four functions. An array field supplied as
`ARRAY_HAS`'s *second* argument SHALL be rejected under the rule above. The four calls return scalars
(boolean, boolean, boolean, integer), so their results SHALL carry no projection-only restriction and
SHALL be legal in `WHERE`, `GROUP BY`, `ORDER BY`, `HAVING` and as an `IF` condition.

Filtering is the whole of the carve-out: no function in the subset explodes an array into rows, so an
`array` field SHALL remain rejected as a `GROUP BY` key even though it is now testable.

#### Scenario: Array projection translates
- **WHEN** a client submits `SELECT execution_path FROM dial_usage_log LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `execution_path`, and the result schema types the column as an array with element type `string`

#### Scenario: Array field in WHERE rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE execution_path IS NOT NULL`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction, and ClickHouse is not contacted

#### Scenario: Array field in GROUP BY rejected
- **WHEN** a client submits `SELECT execution_path, count(*) FROM dial_usage_log GROUP BY execution_path`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction

#### Scenario: Array field in ORDER BY rejected
- **WHEN** a client submits `SELECT execution_path FROM dial_usage_log ORDER BY execution_path`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction

#### Scenario: Array field as function argument rejected
- **WHEN** a client submits `SELECT count(execution_path) FROM dial_usage_log`
- **THEN** translation fails before execution with an error identifying the unsupported use of the array field

#### Scenario: Array predicate over an array field translates
- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log WHERE ARRAY_HAS("usage_client_identity.user_roles", 'admin')`
- **THEN** translation succeeds, producing an `array_has` FnExpr over that field, and the array field is not rejected

#### Scenario: Array length over an array field translates
- **WHEN** a client submits `SELECT ARRAY_LENGTH(execution_path) AS n FROM dial_usage_log`
- **THEN** translation succeeds and the result schema types `n` as an integer, not an array

#### Scenario: Array field in the predicate's value position rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE ARRAY_HAS('admin', execution_path)`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction — the carve-out is argument 0 only

### Requirement: Map fields are projection-only in SQL

A `map`-typed field SHALL be usable in SQL only as a plain projection item (optionally aliased) **or as
the first argument of a `MAP_VALUE`, `MAP_CONTAINS` or `MAP_KEYS` call**. Any
other use SHALL be rejected at translation time — before any ClickHouse contact — with an error naming
the map field and stating that map fields can only be projected; the error SHALL NOT be an "unknown
column" error and SHALL NOT surface as a raw ClickHouse failure. Rejected uses include: as an operand of
any predicate or comparison (including `IS NULL` / `IS NOT NULL`, `LIKE`, and `IN`), as a `GROUP BY` key,
as an `ORDER BY` key, under `SELECT DISTINCT`, and as an argument to any scalar or aggregate function
other than those three. **The
`JSON_EXTRACT_*` carve-out granted to `object` fields SHALL NOT extend to `map` fields**: a ClickHouse
`Map(String, String)` is not a JSON document and the DSL has no rendering that extracts a path from
one, so a `JSON_EXTRACT_*` call over a `map` field SHALL be rejected like any other function use.
`MAP_VALUE`, `MAP_CONTAINS` and `MAP_KEYS` are the map's own accessors and are not JSON extractors:
they read flat keys by exact literal match and traverse nothing, which is why they are expressible
where `JSON_EXTRACT_*` is not.
The resulting `StructuredQuery` SHALL carry the map field as an ordinary field projection, and the
derived result schema SHALL type the column as an object column, since a map cell travels as a JSON
object exactly as an `object` cell does.

The carve-out SHALL be **positional and function-specific**: only argument 0, only those three
functions. A map
field supplied as `MAP_VALUE`'s or `MAP_CONTAINS`'s second argument SHALL be rejected under the rule
above, and that second argument SHALL be a single character-string literal — a column reference, an
expression, or a variadic key list there SHALL be rejected. `MAP_KEYS` SHALL take exactly one argument.

`MAP_VALUE` returns text and `MAP_CONTAINS` boolean, so their results
SHALL carry no projection-only restriction and SHALL be legal as a `GROUP BY` key, an `ORDER BY` key, a
predicate operand and an aggregate operand. `MAP_KEYS` returns an array, so its result SHALL be
projection-only under the array-returning-scalar rule, with the array-accessor carve-out as its only
exception.

A star projection over an entity carrying `map` columns SHALL translate, carrying them as ordinary field
projections.

In `INFORMATION_SCHEMA.COLUMNS`, a `map` column SHALL report `DATA_TYPE` `CHARACTER VARYING` — the same
value an `object` column reports, and for the same reason: it travels as JSON text.

#### Scenario: Map projection translates

- **WHEN** a client submits `SELECT log_attributes FROM otel_app_logs LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `log_attributes`, and the result
  schema types the column as an object column

#### Scenario: Map field in WHERE rejected

- **WHEN** a client submits `SELECT trace_id FROM otel_app_logs WHERE log_attributes IS NOT NULL`
- **THEN** translation fails with an error naming `log_attributes` and the projection-only restriction,
  and ClickHouse is not contacted

#### Scenario: Map field in GROUP BY rejected

- **WHEN** a client submits `SELECT log_attributes, count(*) FROM otel_app_logs GROUP BY log_attributes`
- **THEN** translation fails with an error naming `log_attributes` and the projection-only restriction

#### Scenario: Map field in ORDER BY rejected

- **WHEN** a client submits `SELECT log_attributes FROM otel_app_logs ORDER BY log_attributes`
- **THEN** translation fails with an error naming `log_attributes` and the projection-only restriction

#### Scenario: JSON_EXTRACT over a map field is refused

- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING(log_attributes, 'k') FROM otel_app_logs`
- **THEN** translation fails with an error naming `log_attributes` and the projection-only restriction,
  and ClickHouse is not contacted — the carve-out is `object`-only

#### Scenario: Map column reports as CHARACTER VARYING

- **WHEN** a client reads `INFORMATION_SCHEMA.COLUMNS` for a table carrying a `map` column
- **THEN** that column's `DATA_TYPE` is `CHARACTER VARYING`

#### Scenario: MAP_VALUE over a map field translates and groups

- **WHEN** a client submits
  `SELECT MAP_VALUE(point_attributes, 'model') AS m, sum(point_value) FROM otel_claude_code_metrics GROUP BY m`
- **THEN** translation succeeds, producing a `map_value` FnExpr over that field as the group key, and the
  map field is not rejected

#### Scenario: MAP_VALUE key is a literal, not an expression

- **WHEN** a client submits `SELECT MAP_VALUE(point_attributes, metric_name) FROM otel_claude_code_metrics`
- **THEN** translation fails with `invalid_argument` stating the key must be a single string literal

#### Scenario: MAP_CONTAINS over a map field translates

- **WHEN** a client submits
  `SELECT count(*) FROM otel_claude_code_metrics WHERE MAP_CONTAINS(point_attributes, 'model')`
- **THEN** translation succeeds, producing a `map_contains` FnExpr over that field, and the map field is
  not rejected

#### Scenario: MAP_KEYS over a map field translates and types as an array

- **WHEN** a client submits `SELECT MAP_KEYS(point_attributes) AS k FROM otel_claude_code_metrics LIMIT 10`
- **THEN** translation succeeds and the result schema types `k` as an array with element kind string

#### Scenario: MAP_KEYS as a GROUP BY key rejected

- **WHEN** a client submits `SELECT MAP_KEYS(point_attributes) AS k, count(*) FROM otel_claude_code_metrics GROUP BY k`
- **THEN** translation fails with an error naming the function and the projection-only restriction

#### Scenario: Map size via a nested array accessor translates

- **WHEN** a client submits `SELECT ARRAY_LENGTH(MAP_KEYS(point_attributes)) AS n FROM otel_claude_code_metrics`
- **THEN** translation succeeds, the array-returning call sitting in the accessor's argument 0, and `n`
  types as an integer

#### Scenario: Map field in MAP_VALUE's key position rejected

- **WHEN** a client submits `SELECT MAP_VALUE('model', point_attributes) FROM otel_claude_code_metrics`
- **THEN** translation fails with an error naming `point_attributes` and the projection-only restriction —
  the carve-out is argument 0 only

## ADDED Requirements

### Requirement: Arithmetic operators translate to catalog functions

The SQL subset SHALL accept the infix arithmetic operators `+`, `-`, `*`, `/` and `%` and unary minus
over
numeric operands, and SHALL lower each to the corresponding closed-catalog function — `plus`, `minus`,
`multiply`, `div`, `modulo`, and `minus` against a zero literal respectively — in the resulting
`StructuredQuery`. Calcite's standard `MOD(a, b)` function SHALL lower to `modulo` as well, so the
remainder has two inbound spellings. No new expression kind SHALL be introduced in the DSL: the DSL
carries these as ordinary `fn` expressions.

**The arithmetic catalog names SHALL NOT be accepted as SQL function calls.** `plus(a, b)`,
`minus(a, b)`, `multiply(a, b)`, `div(a, b)` and `modulo(a, b)` are the *DSL* spelling; in SQL the
operation is written with its operator. `MINUS` is a reserved set operator in the accepted grammar (the
`EXCEPT` alias), so `minus(a, b)` cannot parse as a function call at all, and accepting the other four
would leave one arithmetic name behaving unlike its siblings. The round-trip does not need them: the
DSL → SQL renderer emits operators, so a catalog arithmetic name never appears in a statement this
subset has to read back.

Operators SHALL be accepted in projection, in `WHERE`, and in `HAVING`, over raw columns and over
aggregate results alike, with standard SQL precedence and parenthesised grouping. `round`, `floor` and
`ceil` — which have no operator form — SHALL be accepted as ordinary function calls in the same
positions.

`/` and `%` SHALL carry the null-on-zero-or-null-right-operand contract, including for integer
operands: a
division or remainder by an empty denominator SHALL yield a null cell, never a translation error and
never a
ClickHouse division failure.

A projection-only field (`object`, `array` or `map`) as an arithmetic operand SHALL be rejected under
the projection-only rules, the same as for any other function.

#### Scenario: Division of two aggregates in projection
- **WHEN** a client submits `SELECT deployment, sum(cached_prompt_tokens) / sum(prompt_tokens) AS rate FROM dial_usage_log GROUP BY deployment`
- **THEN** translation succeeds, producing a `div` FnExpr over the two `sum` calls aliased `rate`

#### Scenario: Subtraction of two aggregates in projection
- **WHEN** a client submits `SELECT sum(prompt_tokens) - sum(cached_prompt_tokens) AS fresh FROM dial_usage_log`
- **THEN** translation succeeds, producing a `minus` FnExpr over the two `sum` calls

#### Scenario: Arithmetic catalog names are not SQL functions
- **WHEN** a client submits `SELECT minus(a, b) FROM t`, or `SELECT plus(a, b) FROM t`
- **THEN** translation fails — arithmetic is spelled with operators on this surface

#### Scenario: Modulo accepts both of its spellings
- **WHEN** a client submits `SELECT a % b FROM t` and `SELECT MOD(a, b) FROM t`
- **THEN** both produce the same `StructuredQuery`

#### Scenario: Remainder by zero yields null, not an error
- **WHEN** a client submits `SELECT a % b FROM t` over a row whose `b` is `0`
- **THEN** translation succeeds and the cell is null rather than the query failing

#### Scenario: Precedence and parentheses are honoured
- **WHEN** a client submits `SELECT (a + b) * c AS x, a + b * c AS y FROM t`
- **THEN** translation succeeds and the two expressions nest differently, `x` multiplying the sum and `y` adding the product

#### Scenario: Arithmetic in HAVING
- **WHEN** a client submits `SELECT deployment FROM dial_usage_log GROUP BY deployment HAVING sum(a) / sum(b) > 0.5`
- **THEN** translation succeeds and the comparison lands in the StructuredQuery's `having` tree

#### Scenario: Arithmetic over a projection-only field rejected
- **WHEN** a client submits `SELECT execution_path + 1 FROM dial_usage_log`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction

### Requirement: Array-returning scalar functions are projection-only in SQL

A scalar function whose result is an array SHALL be usable only as a plain projection item, optionally
aliased. This covers `MAP_KEYS`, `SPLIT_STRING` and `JSON_EXTRACT_ARRAY`, and holds for the same reason
it holds for a declared `array` field and for
`group_uniq_array`: the DSL has no array literal or comparison, so nothing else is expressible. Any
other use — as a `GROUP BY` key, an `ORDER BY` key, an operand of a predicate, or an argument to
another function — SHALL be rejected at translation time with an error naming the function and stating
that its array result can only be projected.

The restriction SHALL be recognised from the producing function's name rather than from a declared
column type, since a computed column has none. The derived result schema SHALL type the column as an
array with element kind string.

The one exception is the array-predicate carve-out: because `ARRAY_HAS`/`ARRAY_HAS_ANY`/
`ARRAY_HAS_ALL`/`ARRAY_LENGTH` accept an array in argument 0, an array-returning scalar call SHALL be
legal there, so both `ARRAY_HAS(SPLIT_STRING(topics, ','), 'python')` and
`ARRAY_LENGTH(MAP_KEYS(point_attributes))` SHALL translate.

#### Scenario: split_string projects
- **WHEN** a client submits `SELECT SPLIT_STRING("conversation_insights.topics", ',') AS t FROM conversations LIMIT 10`
- **THEN** translation succeeds and the result schema types `t` as an array with element kind string

#### Scenario: An array-returning call is refused as a group key by its alias too
- **WHEN** a client groups by the alias of a `MAP_KEYS` or `SPLIT_STRING` projection
- **THEN** translation fails — resolving the key through its alias SHALL NOT bypass the restriction, which
  would otherwise group by whole arrays and return a plausible-looking answer to a question nobody asked

#### Scenario: split_string as a GROUP BY key rejected
- **WHEN** a client submits `SELECT SPLIT_STRING(topics, ',') AS t, count(*) FROM conversations GROUP BY t`
- **THEN** translation fails with an error naming the function and the projection-only restriction — exploding an array into rows is not in the subset

#### Scenario: split_string nested in an array predicate translates
- **WHEN** a client submits `SELECT count(*) FROM conversations WHERE ARRAY_HAS(SPLIT_STRING(topics, ','), 'python')`
- **THEN** translation succeeds, the array-returning call sitting in the predicate's argument 0
