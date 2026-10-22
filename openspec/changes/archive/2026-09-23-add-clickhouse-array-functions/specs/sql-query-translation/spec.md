## ADDED Requirements

### Requirement: Lambda syntax carries the higher-order array functions

The SQL subset SHALL accept a lambda expression spelled `x -> <expression>`, in **ClickHouse's
operand position — first**: `arrayExists(x -> starts_with(x, 'coder-'), "usage_client_identity.user_roles")`.
It SHALL be accepted only as the lambda argument of a higher-order array function (`arrayExists`,
`arrayAll`, `arrayCount`, `arrayFilter`, `arrayMap`) and SHALL be rejected anywhere else, with an
error naming the call's accepted form. A higher-order call whose first argument is not a lambda SHALL
be rejected the same way, and one whose second argument is not an array SHALL be rejected naming the
declared form rather than the operand — the arity and shape check fires before any projection-only
rule is consulted, as it already does for `ARRAY_HAS`.

The lambda's parameter SHALL be typed as the **element type of the array operand**, so a body calling
a text function over the parameter of a `string` array resolves, and one calling it over a numeric
array does not. An identifier in the body SHALL resolve to the parameter where it names it and to a
**column of the enclosing row** otherwise — ClickHouse's own scoping, so
`arrayExists(x -> starts_with(x, deployment), roles)` means here what it means there. A parameter
SHALL shadow a column of the same name. A captured column SHALL be governed exactly as a projected
one: it counts as a column the statement reads, and a column the caller may not see SHALL be
rejected as unknown inside a body as it is anywhere else.

An **aggregate** or **window** call inside a lambda body SHALL be rejected before execution with an
error naming the call, rather than being reported through the grouping rules — a body is evaluated
per element of one row's array. An aggregate producing the array operand itself stays legal (see
"Array-valued aggregates over an array column translate").

The identifier `__lambdaParam` is **reserved**: a statement naming it SHALL be rejected, because the
translator uses it internally to carry a lambda parameter through validation.

The resulting `StructuredQuery` SHALL carry the lambda as a `lambda` expression in the same operand
position, and the DSL → SQL renderer SHALL emit the ClickHouse form `<param> -> <body>` in that same
position, so a rendered statement reads back to the document it was rendered from.

`ARRAY_JOIN` remains the only row-expanding construct: a higher-order call SHALL NOT change a
statement's row count, so `SELECT DISTINCT` and the deduplication rules are unaffected by one.

The result schema SHALL type `arrayFilter` as an array carrying its **operand's** element kind and
`arrayMap` as an array carrying the kind its **body** produces. That derivation SHALL be the same
whether such a call is projected or consumed: `ARRAY_JOIN` reports one element of its operand and
`ARRAY_SLICE` narrows it, so both SHALL derive their own kind from the operand's element kind by the
identical rule — the flattening aggregates and the two array-returning higher-order calls included.
Otherwise the same expression types one way projected and another way consumed, and the element kind
is what chooses the Arrow writer.

#### Scenario: A prefix test inside a lambda translates
- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log WHERE arrayExists(x -> starts_with(x, 'coder-'), "usage_client_identity.user_roles")`
- **THEN** translation succeeds, producing an `arrayExists` FnExpr whose first argument is a `lambda` and whose second is the array field, and the array field is not rejected as projection-only

#### Scenario: A lambda body captures a column of the enclosing row
- **WHEN** a client submits `SELECT arrayExists(x -> starts_with(x, deployment), execution_path) AS f FROM dial_usage_log`
- **THEN** translation succeeds, the document's lambda body carries the captured column as an ordinary field reference and the parameter as a `lambda_param`, and `deployment` counts as a column the statement reads

#### Scenario: A sensitive column cannot be captured inside a body
- **WHEN** a caller who may not see sensitive columns submits a statement capturing one inside a lambda body
- **THEN** translation fails with the ordinary unknown-column error, exactly as projecting it does

#### Scenario: An aggregate inside a lambda body is rejected
- **WHEN** a client submits `SELECT chat_id, arrayExists(x -> starts_with(x, max(deployment)), execution_path) FROM dial_usage_log GROUP BY chat_id`
- **THEN** translation fails with an error naming the aggregate, not with a grouping error

#### Scenario: The internal parameter marker is reserved
- **WHEN** a client submits a statement calling `__lambdaParam(execution_path, 'x')`
- **THEN** the statement is rejected

#### Scenario: A consumed array-returning call keeps its element kind
- **WHEN** a client submits `SELECT array_join(arrayMap(x -> length(x), execution_path)) AS e FROM dial_usage_log`
- **THEN** translation succeeds and `e` is typed as an integer column — the mapped body's kind, not the
  operand's text — and `SELECT array_slice(arrayMap(x -> length(x), execution_path), 1, 2)` is typed as an
  array of integers, the same answer the projected `arrayMap` gives

#### Scenario: A lambda outside a higher-order call is rejected
- **WHEN** a client submits `SELECT x -> starts_with(x, 'a') FROM dial_usage_log`
- **THEN** the statement is rejected before execution

#### Scenario: A higher-order call over a non-array operand is rejected
- **WHEN** a client submits `SELECT arrayExists(x -> starts_with(x, 'a'), deployment) FROM dial_usage_log`
- **THEN** translation fails naming the call's declared form `arrayExists(<lambda>, <array>)`

#### Scenario: Higher-order calls nest
- **WHEN** a client submits `SELECT arrayExists(x -> starts_with(x, 'c'), arrayFilter(y -> not_empty(y), execution_path)) AS f FROM dial_usage_log`
- **THEN** translation succeeds with the inner call in the outer call's array position

#### Scenario: A rendered lambda reads back to the same document
- **WHEN** a document carrying `arrayExists(x -> starts_with(x, 'coder-'), roles)` is rendered to SQL by `POST /v1/queries/translate` and the result is translated back by `POST /v1/queries/translate-sql`
- **THEN** the second document equals the first, and the rendered statement spells the lambda `x -> starts_with(x, 'coder-')` in the first argument position

### Requirement: Array-valued aggregates over an array column translate

The subset SHALL accept `groupUniqArrayArray(<array>)` and `groupArrayArray(<array>)` as aggregate
calls whose operand is an `array` column or an array-returning call — the one place an array is a
legal aggregate operand, alongside `arg_max`'s value. Their results SHALL be typed as an array whose
element kind follows the operand's element kind, SHALL be projection-only like `group_uniq_array`'s,
and SHALL be legal as the array operand of a higher-order call — which is what makes the
post-aggregation role test a single statement:

```sql
SELECT "usage_client_identity.user_ref" AS user_ref, sum(deployment_price) AS total_cost
FROM dial_usage_log
WHERE request_time >= date_sub('day', 30, now())
GROUP BY user_ref
HAVING arrayExists(r -> starts_with(r, 'coder-'), groupUniqArrayArray("usage_client_identity.user_roles"))
```

A `DISTINCT` quantifier on either SHALL be rejected rather than ignored, and an **aggregate** SHALL
NOT stand as the operand of either: these two are themselves aggregates, so
`groupUniqArrayArray(group_uniq_array(x))` is a nested aggregate and is rejected before execution —
the same refusal the DSL surface gives, so the two surfaces answer that statement alike.

#### Scenario: The union of a group's arrays projects
- **WHEN** a client submits `SELECT chat_id, groupUniqArrayArray(execution_path) AS steps FROM dial_usage_log GROUP BY chat_id`
- **THEN** translation succeeds and the result schema types `steps` as an array with element kind string

#### Scenario: A post-aggregation array predicate translates
- **WHEN** a client submits the `HAVING arrayExists(r -> starts_with(r, 'coder-'), groupUniqArrayArray(...))` statement above
- **THEN** translation succeeds with the predicate carried in `having`, and the aggregate's measures are computed over the unfiltered group

#### Scenario: The latest array value projects
- **WHEN** a client submits `SELECT chat_id, arg_max(execution_path, request_time) AS last_path FROM dial_usage_log GROUP BY chat_id`
- **THEN** translation succeeds and `last_path` is typed as an array

## MODIFIED Requirements

### Requirement: Supported SQL subset maps onto StructuredQuery

The translator SHALL map exactly the following SQL surface onto `StructuredQuery`, and SHALL
produce a query semantically equivalent to the corresponding structured-query JSON:
- single-entity `SELECT` (one table reference; the entity becomes `entity`) — a statement whose plan
  needs more than one such block (a join, a derived table or CTE that cannot flatten, a subquery) is a
  **composite statement** and is governed by "Composite statements execute without a structured-query
  document" instead
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
  date_bin, date_trunc, if, not_empty, starts_with, contains, equals, match, concat, to_string,
  array_to_string, array_slice, json_extract_string,
  json_extract_long, json_extract_double, json_extract_bool, json_extract_string_ci,
  json_extract_array` (scalar); `arrayExists`, `arrayAll`, `arrayCount`, `arrayFilter`, `arrayMap`
  (scalar, higher-order — their lambda argument is governed by "Lambda syntax carries the
  higher-order array functions"); `count` (incl. `COUNT(*)` and `COUNT(DISTINCT x)`), `sum`, `avg`, `min`,
  `max`, `arg_max`, `group_uniq_array`, `groupUniqArrayArray`, `groupArrayArray`, `percentile_cont`,
  `percentile_disc` (aggregate). Every DSL catalog name SHALL be an
  accepted SQL spelling — the DSL→SQL renderer emits catalog names verbatim, so a rejected
  catalog spelling would break the render/translate round-trip. For `length`, the standard SQL
  synonyms `char_length` and `character_length` SHALL also be accepted, all mapping to the DSL
  `length` function. No further ClickHouse spelling of an existing catalog function is accepted:
  `startsWith`, `hasAny` and `argMax` SHALL keep failing as unknown functions, so that every
  accepted name remains a DSL name the renderer can emit. The functions **new** in this change carry
  their ClickHouse names as their DSL names, which is why they need no alias.

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
- **THEN** translation now **succeeds**: neither name is a catalog function, but the validator lowers both onto
  `CASE` — `CASE WHEN chat_id IS NOT NULL THEN chat_id ELSE trace_id END` and `CASE WHEN chat_id = '' THEN
  NULL ELSE chat_id END` — which the `case` expression carries (see "CASE expressions translate to the DSL
  case expression"). This supersedes the earlier rejection; the scenario keeps its historical title so the
  archive can match it

### Requirement: Array fields are projection-only in SQL

An `array`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased), **as the first argument of an `ARRAY_HAS`/`ARRAY_HAS_ANY`/`ARRAY_HAS_ALL`/`ARRAY_LENGTH`/
`ARRAY_JOIN`/`ARRAY_TO_STRING`/`ARRAY_SLICE` call** — the array-operand functions — **as the second
argument of a higher-order call** (`arrayExists`, `arrayAll`, `arrayCount`, `arrayFilter`,
`arrayMap`), or **as the operand of an array-consuming aggregate** (`groupUniqArrayArray`,
`groupArrayArray`, and `arg_max`'s `value`). Any other use SHALL
be rejected at translation time — before any ClickHouse contact —
with an error naming the array field and stating that array fields can only be projected.
Rejected uses include: as an operand of any predicate or comparison (including `IS NULL` /
`IS NOT NULL` and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under `SELECT DISTINCT`
(row-mode distinct deduplicates over the projected array), and as an argument to any scalar or
aggregate function other than those named above. The resulting `StructuredQuery` SHALL carry the
array field as an ordinary field projection, and the derived result schema SHALL type the column as an
array of its catalog `element_type`.

The carve-out SHALL be **positional and function-specific**, matching the treatment `object` fields
receive for `JSON_EXTRACT_*`: one named position per named function — argument 0 for the
array-operand functions and the array-consuming aggregates, argument 1 for the higher-order
functions, because that is where ClickHouse puts the array. An array field supplied as
`ARRAY_HAS`'s *second* argument, or as a higher-order call's *first*, SHALL be rejected under the
rule above. Five of the seven array-operand functions answer with a
scalar — `ARRAY_HAS`/`ARRAY_HAS_ANY`/`ARRAY_HAS_ALL` a boolean, `ARRAY_LENGTH` an integer,
`ARRAY_TO_STRING` text — so their results SHALL carry no projection-only restriction and
SHALL be legal in `WHERE`, `GROUP BY`, `ORDER BY`, `HAVING` and as an `IF` condition. `ARRAY_JOIN`
likewise returns a scalar — the array's element type — and is row-expanding; its contract is stated by
"ARRAY_JOIN expands an array into rows on the SQL surface". `ARRAY_SLICE` is the one member whose own
result is an array, and it therefore carries the projection-only restriction of the array-returning
calls in its turn.

Testing, rendering, slicing, expanding, per-element evaluation and flattening across a group are the
whole of the carve-out. An `array` field SHALL remain
rejected as a `GROUP BY` key: only a scalar result — `ARRAY_JOIN`'s element, `ARRAY_TO_STRING`'s text,
or a higher-order predicate's boolean or count
— is groupable, and only through the alias of the
projection carrying it, never the array column itself.

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

#### Scenario: Array field in a higher-order call's lambda position rejected
- **WHEN** a client submits `SELECT arrayExists(execution_path, x -> not_empty(x)) FROM dial_usage_log`
- **THEN** translation fails before execution — the carve-out is argument 1 for a higher-order call, and the message names the call's declared form `arrayExists(<lambda>, <array>)`

#### Scenario: Array predicate over an array field translates
- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log WHERE ARRAY_HAS("usage_client_identity.user_roles", 'admin')`
- **THEN** translation succeeds, producing an `array_has` FnExpr over that field, and the array field is not rejected

#### Scenario: Array length over an array field translates
- **WHEN** a client submits `SELECT ARRAY_LENGTH(execution_path) AS n FROM dial_usage_log`
- **THEN** translation succeeds and the result schema types `n` as an integer, not an array

#### Scenario: Array field in the predicate's value position rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE ARRAY_HAS('admin', execution_path)`
- **THEN** translation fails before execution — the carve-out is argument 0 only. The refusal comes from
  the operand-type check (`ARRAY_HAS(<ARRAY>, <ANY>)`), which fires before the projection-only rule is
  consulted, so the message names the call's declared form rather than the array field

#### Scenario: Array field as ARRAY_JOIN's operand translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(execution_path) AS step FROM dial_usage_log`
- **THEN** translation succeeds and the array field is not rejected — `ARRAY_JOIN`'s operand position
  is part of the carve-out

#### Scenario: Array field as the rendering and slicing functions' operand translates
- **WHEN** a client submits `SELECT ARRAY_TO_STRING(execution_path, '>') AS path FROM dial_usage_log`
  or `SELECT ARRAY_SLICE(execution_path, 1, 3) AS head FROM dial_usage_log`
- **THEN** translation succeeds in both cases and the array field is not rejected — argument 0 of each
  is part of the carve-out

### Requirement: The array-returning aggregate is projection-only too

`group_uniq_array(col)` SHALL be accepted as an aggregate call over a scalar column and SHALL be usable
only as a plain projection item (optionally aliased), for the same reason an `array` field is: the DSL
has no array literal or comparison, so nothing else is expressible. The derived result schema SHALL
type the column as an array whose element kind follows the operand — not a declared `element_type`,
which a computed column has none of. A `DISTINCT` quantifier SHALL be rejected rather than ignored (the
function already returns the distinct values), and an `array`-typed operand SHALL be rejected under the
projection-only rule above — its result would be an array of arrays, which the DSL cannot type — with
a message naming `groupUniqArrayArray` as the function that flattens instead (see "Array-valued
aggregates over an array column translate").

#### Scenario: Array-valued aggregate translates and types from its operand
- **WHEN** a client submits `SELECT chat_id, group_uniq_array(deployment) AS deployments FROM dial_usage_log GROUP BY chat_id`
- **THEN** the translator produces an aggregate-mode StructuredQuery with a `group_uniq_array` FnExpr over `deployment` aliased `deployments`, and the result schema types that column as an array with element kind string

#### Scenario: DISTINCT on the array-valued aggregate rejected
- **WHEN** a client submits `SELECT chat_id, group_uniq_array(DISTINCT deployment) FROM dial_usage_log GROUP BY chat_id`
- **THEN** translation fails with an error stating the function does not accept `DISTINCT`

#### Scenario: Array-valued aggregate over an array field rejected
- **WHEN** a client submits `SELECT chat_id, group_uniq_array(execution_path) FROM dial_usage_log GROUP BY chat_id`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction, and naming `groupUniqArrayArray` as the flattening alternative
