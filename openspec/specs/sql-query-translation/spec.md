# sql-query-translation Specification

## Purpose

The SQL → `StructuredQuery` mapping contract: how external SQL text (submitted via the
[flight-sql-endpoint](../flight-sql-endpoint/spec.md), the
[rest-sql-endpoint](../rest-sql-endpoint/spec.md), or the translation-only
[query-translation-endpoint](../query-translation-endpoint/spec.md)) is parsed and validated with Apache Calcite
against the query-schema-discovery catalog, lowered to a relational plan, and translated into query
blocks: a single-entity statement becomes one `StructuredQuery`; a statement that joins, nests a derived
table or CTE, or carries a subquery becomes a composite plan of such blocks that executes through the same
pipeline but has no DSL document. The translator is fail-closed: every construct outside the supported
subset (set operations, recursive CTEs, correlated subqueries, general `CAST`, functions outside the
catalog, …) is rejected before any ClickHouse contact. A statement against `INFORMATION_SCHEMA` is
answered in process rather than translated, so its boundary is what the evaluator can compute — also
fail-closed, against a different limit.
## Requirements
### Requirement: SQL is validated against the query-schema catalog

SQL statements SHALL be parsed and validated with Calcite against a schema derived from the
same catalog as `GET /v1/queries/entities` and `GET /v1/queries/entities/schema/{name}`: each
ACTIVE queryable entity is a table whose columns are the entity's flat fields (source columns
plus dotted enrichment columns). Identifier matching SHALL be case-insensitive. Enrichment
columns SHALL be addressable only as double-quoted flat identifiers (e.g.
`"deployment_ref.display_name"`, `"dial_usage_log_payload.request_body"`). Every declared field type
SHALL be included in the SQL-visible schema: fields of type `array` SHALL be typed as array columns
(element type from the catalog's `element_type`) and fields of type `object` SHALL be typed as
character columns; both are projection-only (see the respective projection requirements). The schema
SHALL be derived from the same access-filtered entity model the JSON query surface uses, so a column
the caller may not see (e.g. a sensitive column without the grant) SHALL be absent from the
SQL-visible schema and rejected as an unknown column. References to unknown tables or columns
SHALL be rejected at validation time.

Because every seeded verbatim-content column is owned by the `dial_usage_log_payload` enrichment,
their only valid spelling on `dial_usage_log` is the double-quoted qualified one; the bare names
`request_body`, `response_body`, `assembled_response`, `jwt_claims` and `request_tags` SHALL be
rejected as unknown columns of that entity.

#### Scenario: Known entity and columns validate
- **WHEN** a client submits `SELECT request_id, "deployment_ref.display_name" FROM dial_usage_log`
- **THEN** validation succeeds and both columns resolve to the entity's flat fields

#### Scenario: Unknown column rejected
- **WHEN** a client submits `SELECT no_such_column FROM dial_usage_log`
- **THEN** translation fails with a validation error naming the unknown column, before execution

#### Scenario: A bare body column name is rejected
- **WHEN** a client submits `SELECT request_body FROM dial_usage_log`
- **THEN** translation fails with a validation error naming `request_body` as an unknown column,
  before execution

#### Scenario: The qualified body column name resolves
- **WHEN** a client submits `SELECT "dial_usage_log_payload.request_body" FROM dial_usage_log`
- **THEN** validation succeeds and the column resolves to the entity's flat field, emitting the grain
  join

#### Scenario: Object field visible to SQL
- **WHEN** a client submits `SELECT request_id, "dial_usage_log_payload.jwt_claims" FROM dial_usage_log`
- **THEN** validation succeeds, the column resolves to the entity's `object`-typed flat field, and the
  grain join is emitted

#### Scenario: A bare claim column name is rejected
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log`
- **THEN** translation fails with a validation error naming `jwt_claims` as an unknown column, before
  execution

#### Scenario: Array field visible to SQL
- **WHEN** a client submits `SELECT request_id, execution_path FROM dial_usage_log`
- **THEN** validation succeeds and `execution_path` resolves to the entity's array-typed flat field

#### Scenario: Sensitive object column stays invisible without the grant
- **WHEN** a caller who may not see sensitive columns submits a statement selecting a sensitive `object` column
- **THEN** translation fails as an unknown column, exactly as the column is hidden from schema discovery

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

### Requirement: LIKE maps only to the contains operators

`LIKE` and `ILIKE` predicates SHALL be translated only when the pattern is exactly `'%text%'`
where `text` contains no `%` or `_` metacharacters, and SHALL map to the DSL contains operators
with standard SQL semantics preserved: `LIKE` → `co` / `NOT LIKE` → `nc` (case-sensitive,
standard SQL semantics), and `ILIKE` → `ico` / `NOT ILIKE` → `inc` (case-insensitive). There is
no case-sensitivity deviation: each SQL form translates to the DSL operator with identical
semantics. Any other pattern (prefix/suffix wildcards, metacharacters inside the text, an
`ESCAPE` clause, a non-literal pattern) SHALL be rejected.

#### Scenario: Case-sensitive contains pattern accepted

- **WHEN** a client submits `WHERE model LIKE '%gpt%'`
- **THEN** the predicate translates to `co` on `model` with value `gpt` (case-sensitive)

#### Scenario: Case-insensitive contains pattern accepted

- **WHEN** a client submits `WHERE model ILIKE '%gpt%'`
- **THEN** the predicate translates to `ico` on `model` with value `gpt`

#### Scenario: Negated contains patterns map to the not-contains operators

- **WHEN** a client submits `WHERE model NOT LIKE '%gpt%'` or `WHERE model NOT ILIKE '%gpt%'`
- **THEN** the predicates translate to `nc` and `inc` respectively, on `model` with value `gpt`

#### Scenario: Prefix pattern rejected

- **WHEN** a client submits `WHERE model LIKE 'gpt%'`
- **THEN** translation fails with an unsupported-construct error

### Requirement: Literals translate with DSL value typing
SQL literals SHALL convert to `ValueExpr` entries typed by the DSL's `value_type` set (string,
integer, long, decimal, boolean, date, timestamp, uuid, null), with values encoded as the DSL
expects: timestamps as epoch-milliseconds, dates as ISO-8601 dates, numbers as plain decimal
strings. Validator-injected identity or widening casts within the same type family SHALL be
unwrapped and ignored; a `DATE` widened to `TIMESTAMP` (e.g. a date literal compared to a
timestamp column) SHALL convert to midnight UTC of that date. Explicit or narrowing `CAST`s
SHALL be rejected, **except a cast to a character type**, which is either the `to_string` call or —
where the operand is already text — the unwrapped operand, governed by "Text composition and value
rendering in the SQL subset". SQL runtime parameters (`?`)
SHALL be rejected.

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

### Requirement: Unsupported SQL fails closed before execution

The rules below SHALL govern a statement **against queryable entities** — one that has to become a
`StructuredQuery` or a composite plan of them. A statement against `INFORMATION_SCHEMA` SHALL instead be
bounded by what the in-process evaluator can do, which is a different set (see "Catalog metadata is
queryable as INFORMATION_SCHEMA" for that boundary). Both are fail-closed, but they fail closed against
different limits.

Any SQL construct outside the supported subset SHALL be rejected at translation time with an error
identifying the construct, and nothing SHALL be sent to ClickHouse. Rejected constructs include
(non-exhaustively): multi-statement input; non-`SELECT` statements;
`WITH RECURSIVE`; correlated subqueries; `LATERAL`/`APPLY`; quantified comparisons (`= SOME`, `> ALL`,
`UNIQUE`); ROLLUP/CUBE/GROUPING SETS; explicit `CAST` other than the identity/widening,
literal-to-timestamp and to-character forms the translator tolerates; `BETWEEN` (unless expressible — see range expansion
below); `FILTER (WHERE …)` on aggregates; functions outside the DSL catalog, including window functions
other than the ranking functions and aggregates the "Window functions" requirement names; a join whose
`ON` carries no equality between its sides; `IN` with non-literal, non-subquery elements; a set operation
whose branches project a different number of columns, or whose corresponding columns have no common type;
a set operation over `INFORMATION_SCHEMA` relations or mixing one with a queryable entity. Joins, CTEs,
derived tables, uncorrelated subqueries, `CASE`, arithmetic operators, window functions and **set
operations** are **no longer** rejected — each is governed by its own requirement above. Range predicates that Calcite lowers
into `SEARCH`/Sarg form SHALL be expanded back into supported comparison conjunctions or `in` lists where
the Sarg consists of point values and/or simple bounds; Sargs that cannot be expanded losslessly SHALL be
rejected.

#### Scenario: Set operation rejected
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log UNION ALL SELECT response_id, rate FROM rate_analytics` — a set operation whose branches project a different number of columns
- **THEN** translation fails with an error naming the column-count mismatch and ClickHouse is not contacted; the same statement with matching branch arity is accepted (see "Set operations combine query blocks"), so what a set operation is rejected for is now branch incompatibility rather than the operator

#### Scenario: Join rejected
- **WHEN** a client submits `SELECT a.x FROM t1 a JOIN t2 b ON a.id > b.id` — a join whose `ON` carries no equality between its two sides
- **THEN** translation fails with an error stating that a join condition must contain an equality and ClickHouse is not contacted; the same join written `ON a.id = b.id` is accepted (see "Joins compose queryable entities")

#### Scenario: Arithmetic rejected
- **WHEN** a client submits `SELECT plus(price, 2) FROM dial_usage_log` — an arithmetic catalog name spelled as a SQL function rather than as the operator `price + 2`
- **THEN** translation fails with an unsupported-construct error naming `PLUS`; the operator spelling is accepted (see "Arithmetic operators translate to catalog functions")

#### Scenario: IN list expanded from Sarg
- **WHEN** a client submits `WHERE model IN ('a', 'b', 'c')`
- **THEN** the predicate translates to the DSL `in` operator with a three-element literal array

#### Scenario: Range conjunction expanded from Sarg
- **WHEN** a client submits `WHERE price > 10 AND price <= 20`
- **THEN** the predicate translates to an `and` of `gt` and `le` comparisons

#### Scenario: Correlated subquery rejected
- **WHEN** a client submits a subquery that references a column of the enclosing statement
- **THEN** translation fails with an error stating that correlated subqueries are not supported and ClickHouse is not contacted

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

### Requirement: Object fields are projection-only in SQL

An `object`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased), **as an operand of a document comparison** (see the paragraph below),
**or as the first argument of a `JSON_EXTRACT_STRING`/`JSON_EXTRACT_LONG`/
`JSON_EXTRACT_DOUBLE`/`JSON_EXTRACT_BOOL`/`JSON_EXTRACT_STRING_CI`/`JSON_EXTRACT_ARRAY` call**.
Any other use SHALL be rejected at translation
time — before any ClickHouse contact —
with an error naming the object field and stating that object fields can only be projected;
the error SHALL NOT be an "unknown column" error and SHALL NOT surface as a raw ClickHouse
failure. Rejected uses include: as an operand of `IS NULL` / `IS NOT NULL`, as an operand of an
**ordering** comparison (`<`, `>`, `<=`, `>=`), as a `GROUP BY` key, as an `ORDER BY` key, under
`SELECT DISTINCT`, and as an argument to any scalar or aggregate function other than the
`JSON_EXTRACT_*` family. The resulting
`StructuredQuery` SHALL carry the object field as an ordinary field projection, and the derived
result schema SHALL type the column as an object column.

**Document comparison.** An `object` field SHALL additionally be legal as the **left** operand of
`LIKE`, `ILIKE`, `NOT LIKE`, `NOT ILIKE`, `=`, `<>` and `IN` against character literals, in `WHERE`.
The carve-out SHALL NOT extend to `HAVING`, and that is a consequence rather than a separate rule: a
`HAVING` predicate may only reference a grouped or aggregated expression, and `GROUP BY` on an
`object` field stays rejected above — so a statement putting an `object` field in `HAVING` is refused
either for the ungrouped reference or for the `GROUP BY` key, and none reaches the carve-out. (The DSL
surface's `having` is a different clause with different resolution rules; see the `structured-query`
capability.) That shape — `<object field> <operator> <character literal>` — is the whole of the
carve-out: a comparison whose other operand is anything else SHALL be rejected at translation time with
an error naming the object field and the projection-only restriction. This covers an `object` field as
the **right** operand (`'{"a":1}' = jwt_claims`, `event_kind = jwt_claims`) and an `object` field
compared with any non-literal (`jwt_claims = event_kind`, `request_tags = jwt_claims`). The rejection is
what keeps the SQL front end from admitting more than the DSL surface does: `/v1/queries/execute` reads
the carve-out off the left operand only and refuses a projection-only field on the right, so a shape
accepted here but refused there would break the `translate-sql` → `execute` round trip.

Such a comparison SHALL translate to the corresponding DSL comparison
(`co`, `ico`, `nc`, `inc`, `eq`, `ne`, `in`) carrying the bare `object` field as its left operand, and
a `StructuredQuery` carrying one SHALL render back to the same SQL — the field rendering as its plain
quoted identifier, never wrapped in a coercion, so the round-trip is single-valued. The comparison's
meaning is defined over the document's **canonical JSON text** (see the `structured-query` capability):
`LIKE`/`ILIKE` match a substring of it, and `=`/`<>`/`IN` compare documents for equality independently
of key order, whitespace and number format. A literal compared with `=`, `<>` or `IN` SHALL parse as
JSON; one that does not SHALL be rejected at translation time with an error naming the field and the
literal, and SHALL NOT reach ClickHouse. Literals compared with `LIKE`/`ILIKE` are ordinary patterns
and carry no JSON requirement. `IS NULL` and `IS NOT NULL` stay rejected: a ClickHouse `JSON` column is
never null, so either would be a constant predicate rather than a document test.

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
The extractors remain the precise instrument for reading one key; a document comparison searches or
matches the whole document and is therefore the coarser tool of the two.

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
- **THEN** translation fails with an error naming `jwt_claims` and the JSON-literal requirement — not the projection-only restriction — and ClickHouse is not contacted

#### Scenario: Object field compared to a JSON literal translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims = '{"sub":"user-1"}'`
- **THEN** translation succeeds, producing a DSL `eq` comparison whose left operand is the bare `jwt_claims` field and whose right operand is the character literal

#### Scenario: Object field under LIKE and ILIKE translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE request_tags ILIKE '%gpt-4%'`
- **THEN** translation succeeds, producing a DSL `ico` comparison over the bare `request_tags` field, and the same query renders back to the same statement

#### Scenario: Object field under IN translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims IN ('{"a":1}', '{"b":2}')`
- **THEN** translation succeeds, producing a DSL `in` comparison over the bare `jwt_claims` field with both literals

#### Scenario: Object field as the right operand of a comparison rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE '{"a":1}' = jwt_claims` (or the same with the non-JSON literal `'x'`)
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction on the comparison's right operand, and ClickHouse is not contacted

#### Scenario: Object field compared with a non-literal rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims = event_kind` or `... WHERE request_tags = jwt_claims`
- **THEN** translation fails with an error naming the object field and the projection-only restriction — the document carve-out is defined only against a character literal — and ClickHouse is not contacted

#### Scenario: Object field under an ordering comparison rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims > '{"a":1}'`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction — ordering is not defined over documents

#### Scenario: Object field in a HAVING comparison rejected
- **WHEN** a client submits `SELECT event_kind, count(*) AS n FROM dial_usage_log GROUP BY event_kind HAVING request_tags ILIKE '%gpt%'`, or the same statement grouped by `request_tags` instead
- **THEN** translation fails and ClickHouse is not contacted — the first because `request_tags` is neither grouped nor aggregated, the second with the error naming `request_tags` and the projection-only restriction on the `GROUP BY` key; the document carve-out is reachable only from `WHERE`

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

### Requirement: Unnamed SQL select expressions take PostgreSQL-derived names

The translator SHALL NOT surface the planner's synthetic `EXPR$<n>` names. An output column that the
SQL statement does not name — no `AS` alias, not a bare column reference, not a star — SHALL take a
PostgreSQL-derived name, assigned **before the statement is validated** so that the derived name is a
real alias in the validated statement:

- a function-syntax call SHALL take its lowercase function name as written, so `count(*)` → `count` and
  `char_length(x)` → `char_length` (the spelling the caller used, even where the DSL maps that call onto
  another catalog function);
- an aggregate wrapped in `WITHIN GROUP` SHALL take the wrapped aggregate's name, so
  `percentile_cont(0.5) WITHIN GROUP (ORDER BY x)` → `percentile_cont`;
- any other unnamed expression SHALL take `?column?`.

The resulting name list SHALL be made unique by the same positional `_<n>` suffixing the
structured-query naming contract defines, so a derived name can never produce two identically named
outputs. An `AS` alias, a bare column reference, and star-expanded columns SHALL keep the name the SQL
statement gives them.

The derived name SHALL be used consistently in the produced `StructuredQuery` `select`, in the
`ResultColumn` descriptors that carry the result schema (REST SQL row keys and the Flight SQL Arrow
schema), and in any `group_by`/`sort` reference the translator resolves through a select output.

Because the derived name is present before validation, it SHALL be referenceable from the statement
exactly as an explicit `AS` alias is, with PostgreSQL's per-clause ambiguity resolution: a bare
`ORDER BY <name>` SHALL resolve to the output column, while a bare `GROUP BY <name>` that matches both
an input column and an output column SHALL resolve to the **input column**.

A `GROUP BY` on a **computed expression** is expressible in the DSL only as the name of the select
output carrying that expression, and a `group_by` name that is also a column of the entity resolves to
the column (input columns win, above). The translator SHALL therefore, for a computed group key, use a
select output whose name is **not** a column of the entity, and SHALL reject the statement with a 400
`bad_request` naming the colliding name when every select output carrying that expression is named
after an entity column — it SHALL NOT emit a `group_by` name that would regroup the query by an
unrelated column. This applies to derived and explicit names alike, since both can shadow a column.

The mirror shape SHALL fail the same way: when the statement groups by a plain **column** while a select
item is explicitly aliased after that column but projects a different expression, the DSL `group_by` name
would again be both a column and a foreign alias — a key the structured-query surface rejects as
ambiguous — so the translator SHALL reject the statement with a 400 `bad_request` naming the collision
rather than emit a document that only fails when executed.

The produced DSL SHALL omit a select entry's `as` only where omitting it leaves the **whole** select
list resolving, by the structured-query naming contract, to exactly the same output names; otherwise
the resolved name SHALL be carried as an explicit `as`. Redundancy is therefore judged against the
produced select list itself, never against a hypothetical list in which no entry carries an alias: an
entry that keeps its alias reserves that name and shifts the names the remaining derived entries take,
so an explicit alias of the shape `<base>_<n>` can be indispensable even though some other entry would
derive that very name. A DSL document produced from SQL SHALL therefore always execute to exactly the
reported `ResultColumn` names.

#### Scenario: Unnamed aggregate takes the function name

- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log`
- **THEN** the single result column is named `count` (never `EXPR$0`), and the produced DSL select entry
  carries no `as`

#### Scenario: Unnamed scalar call takes the function name

- **WHEN** a client submits `SELECT lower(deployment) FROM dial_usage_log`
- **THEN** the result column is named `lower` and the produced DSL select entry carries no `as`

#### Scenario: Explicit alias is preserved

- **WHEN** a client submits `SELECT count(*) AS n FROM dial_usage_log`
- **THEN** the result column is named `n` and the produced DSL select entry carries `"as": "n"`

#### Scenario: Derived name colliding with an alias is suffixed

- **WHEN** a client submits `SELECT count(*), count(deployment) AS "count" FROM dial_usage_log`
- **THEN** the aliased column is named `count`, the unnamed aggregate is named `count_1`, and both names
  appear in the `ResultColumn` list in select order

#### Scenario: An alias colliding with another entry's derived suffix is kept

- **WHEN** a client submits `SELECT count(*) AS foo, count(deployment) AS count_1 FROM dial_usage_log`
- **THEN** both entries carry their `as` (`foo` and `count_1`) — dropping `count_1` as "already derivable"
  would make the produced DSL execute to `foo`/`count` instead of the reported `foo`/`count_1`

#### Scenario: Unnamed literal takes PostgreSQL's placeholder name

- **WHEN** a client submits a statement projecting a bare literal (e.g. `SELECT 1 FROM dial_usage_log`)
- **THEN** the result column is named `?column?`

#### Scenario: A derived name is orderable without an alias

- **WHEN** a client submits `SELECT model, count(*) FROM dial_usage_log GROUP BY model ORDER BY count DESC`
- **THEN** the statement is accepted (the derived name resolves like an explicit alias, as in
  PostgreSQL) and the sort is resolved against the `count` output

#### Scenario: Computed group key named after an entity column is rejected

- **WHEN** a client submits `SELECT date_bin(1, 'hour', request_time) AS deployment, count(*) FROM dial_usage_log GROUP BY date_bin(1, 'hour', request_time)`
- **THEN** the statement is rejected with a 400 `bad_request` naming `deployment` as both the output name
  and a column of the entity — the query is never executed grouped by the `deployment` column

#### Scenario: Grouped column shadowed by a select alias of another expression is rejected

- **WHEN** a client submits `SELECT deployment AS parent_deployment, parent_deployment AS deployment, count(*) FROM dial_usage_log GROUP BY deployment, parent_deployment`
- **THEN** the statement is rejected with a 400 `bad_request` naming the alias/column collision, because
  each `group_by` name would be both a column and the alias of a different expression

#### Scenario: Grouped column projected under its own name stays accepted

- **WHEN** a client submits `SELECT request_time AS request_time, count(*) FROM dial_usage_log GROUP BY request_time`
- **THEN** `group_by` is `["request_time"]` and the statement translates normally — the alias names the
  same column the grouping does

#### Scenario: Computed group key uses the output name that is not an entity column

- **WHEN** a client submits `SELECT date_bin(1, 'hour', request_time) AS deployment, date_bin(1, 'hour', request_time) AS bucket, count(*) FROM dial_usage_log GROUP BY date_bin(1, 'hour', request_time)`
- **THEN** `group_by` is `["bucket"]`, which resolves to the hour-bucket output rather than to the
  `deployment` column

#### Scenario: SQL synonym is named as written

- **WHEN** a client submits `SELECT char_length(deployment) FROM dial_usage_log`
- **THEN** the result column is named `char_length` — the spelling the caller used — while the produced
  DSL entry is a `length` call carrying `"as": "char_length"`

#### Scenario: Ordered-set aggregate is named by the aggregate, not the WITHIN GROUP wrapper

- **WHEN** a client submits `SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY price) FROM dial_usage_log`
- **THEN** the result column is named `percentile_cont`

#### Scenario: Star projection keeps its column names

- **WHEN** a client submits `SELECT * FROM dial_usage_log`
- **THEN** every result column keeps its own source column name and no derived name is introduced

### Requirement: Star projection expands to the non-heavy source columns

A star select item — `SELECT *` or `SELECT <table-or-alias>.*` — SHALL be expanded, **before the
statement is validated**, into the entity's **source** columns that are not marked `heavy` in the
table-management catalog (see the `table-management` capability's "Heavy column metadata"), each as a
plain column reference in catalog order.

**BREAKING**: a star SHALL NOT expand to the entity's enrichment columns. Previously a star expanded to
the entity's whole flat schema, dotted enrichment columns included, which also emitted a `LEFT JOIN`
per attached enrichment. A star SHALL now expand to exactly the field set a row-mode DSL query with an
omitted `select` projects (see the `structured-query` capability's "Wildcard projection excludes heavy
columns"), so the two wildcard surfaces agree and an enrichment join stays opt-in. An enrichment column
SHALL remain selectable, filterable, and sortable by its quoted qualified flat identifier
(e.g. `"deployment_ref.display_name"`) — the only spelling under which it was ever addressable in SQL —
and SHALL remain present in schema discovery and in the SQL-visible schema.

Because the expansion happens in the parsed statement, an expanded star SHALL be indistinguishable
downstream from a caller-written column list: validation, the relational plan, the produced
`StructuredQuery` `select`, and the reported `ResultColumn` names SHALL all be exactly those of the
equivalent explicit statement, and each column SHALL keep its own name (no derived name, no alias).

A `heavy` field named **explicitly** SHALL be accepted and projected unchanged, and a `heavy` field
SHALL remain usable in `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, and as a function argument exactly
as before — subject only to the pre-existing array/object projection-only rules. Heaviness SHALL NOT
remove a field from the SQL-visible schema; an unknown-column error SHALL never be produced for a
heavy field.

A star whose expansion would be **empty** — every source column visible to the caller is heavy — SHALL
be rejected at translation time with a message naming the entity and directing the caller to list
columns explicitly (`400 bad_request` on REST, gRPC `INVALID_ARGUMENT` on Flight), and nothing SHALL
be sent to ClickHouse.

Only a **single-entity** `FROM` is expanded. Where the `FROM` is a derived table that flattens
losslessly into the single-entity shape, the expansion SHALL be applied to the inner select that names
the entity, so heaviness is honored at the point the base table is referenced; every other unsupported
`FROM` shape stays rejected by the existing fail-closed rules. `COUNT(*)` and any other function-star
argument SHALL NOT be affected — only top-level select-list star items are expanded.

#### Scenario: Star omits a heavy column

- **WHEN** a client submits `SELECT * FROM dial_usage_log LIMIT 1` on an entity whose `response_body`
  column is marked `heavy`
- **THEN** the translated query projects every non-heavy source column, `response_body` is absent from
  the projection and from the reported result columns, and each remaining column keeps its own name

#### Scenario: Star omits enrichment columns and joins no enrichment

- **WHEN** a client submits `SELECT * FROM <source>` for a source with an attached enrichment
- **THEN** the projection carries only the source's non-heavy columns, no `<enrichment>.<column>` output
  appears, and the executed query emits no enrichment `LEFT JOIN`

#### Scenario: Enrichment column stays selectable by its qualified name

- **WHEN** a client submits `SELECT request_id, "deployment_ref.display_name" FROM dial_usage_log`
- **THEN** translation succeeds, the enrichment is joined, and the column is projected under its
  qualified flat name — the star rule restricts only what a star expands to

#### Scenario: Explicitly named heavy column is projected

- **WHEN** a client submits `SELECT request_id, response_body FROM dial_usage_log LIMIT 1`
- **THEN** translation succeeds and both columns are projected — an explicit reference is never pruned

#### Scenario: Heavy column is usable in WHERE and ORDER BY

- **WHEN** a client submits `SELECT * FROM dial_usage_log WHERE response_body LIKE '%error%' ORDER BY response_body LIMIT 5`
- **THEN** the predicate and the sort translate normally against the heavy column, while the projection
  still omits it

#### Scenario: Qualified star is expanded

- **WHEN** a client submits `SELECT t.* FROM dial_usage_log t LIMIT 1`
- **THEN** the star is expanded to the same non-heavy field list as an unqualified `*`

#### Scenario: COUNT(*) is unaffected

- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log`
- **THEN** the statement translates to a global aggregate exactly as before, with no column expansion

#### Scenario: All-heavy star is rejected before execution

- **WHEN** a client submits `SELECT * FROM <entity>` where every source column visible to the caller is
  marked `heavy`
- **THEN** translation fails with a message naming the entity and the explicit-columns remedy, and
  ClickHouse is not contacted

#### Scenario: Star over a derived table honors heaviness at the base table

- **WHEN** a client submits `SELECT * FROM (SELECT * FROM dial_usage_log) LIMIT 1`
- **THEN** the inner star expands to the non-heavy fields and the outer star projects exactly those,
  so the heavy column is absent

### Requirement: A parse failure reports its diagnosis, not the grammar

When SQL or a filter predicate fails to **parse**, the client-facing message SHALL carry the parser's
diagnosis — the offending token and its position — and SHALL NOT carry the parser's expectation set.

Calcite reports a parse failure as a short diagnosis followed by an enumeration of every token that would
have been legal at that position. The enumeration runs to dozens of lines of grammar. It is not
actionable: a caller who mistyped an operator does not need the list of all other operators, and the list
is long enough to displace the one line that actually locates the mistake. Returned through `ErrorView`,
it also makes the response unreadable wherever errors are surfaced as a single line.

When the parse stops on a **keyword** at a position where an identifier would have been legal, the message
SHALL additionally name the reserved word responsible, give its position, and state the two available
fixes: rename it, or quote it as a delimited identifier. The parser's own diagnosis anchors on the keyword
it stopped at, which for the common case of a reserved word used as an unquoted alias is the *preceding*
token — so the unaided message names a token that is not the problem, and the caller inspects the wrong
part of their statement. The reserved word SHALL be identified from the parser's own vocabulary rather
than from a list maintained alongside it, because which words are reserved is not intuitive and a
hand-kept list would drift from the grammar it describes.

The additional identification SHALL be **added to** the parser's diagnosis, never substituted for it. The
diagnosis is correct about where parsing stopped; only the attribution is incomplete. A statement whose
failure does not fit this shape SHALL be reported exactly as before, so an unrecognised failure is left
un-improved rather than misreported.

The full parser output SHALL remain available in the service log at debug level. The requirement is that
verbosity move to where it costs nothing, not that it be discarded.

This applies wherever the shared front end parses caller input — ad-hoc SQL, translation requests,
enrichment rule filters, and aggregate pipeline filters — because all four inherit the same message. It
governs **parse** failures specifically; validation failures already report a single targeted sentence
naming the offending column or construct, and SHALL keep doing so.

The status code, the stable `error` code, and which statements are rejected SHALL be unchanged. Only the
message's shape is constrained.

#### Scenario: A parse failure names the token and position

- **WHEN** a caller submits SQL or a filter that does not parse
- **THEN** the message identifies the offending token and its position, and does not include the parser's
  list of expected tokens

#### Scenario: A reserved word used as an alias is named, with its fix

- **WHEN** a caller submits SQL whose select-item alias is an unquoted reserved word, such as
  `count(*) AS rows`
- **THEN** the message names that word and its position, and states that it can be renamed or quoted —
  rather than reporting only the preceding `AS` the parser stopped at

#### Scenario: Quoting or renaming the alias parses

- **WHEN** the same statement is resubmitted with the alias quoted, or renamed to a non-reserved word
- **THEN** it parses and executes normally

#### Scenario: A failure of another shape is reported unchanged

- **WHEN** a parse failure does not involve a reserved word where an identifier was legal
- **THEN** the message is the parser's diagnosis alone, with no added identification

#### Scenario: The full parser output is still recoverable

- **WHEN** a parse failure is returned to a caller
- **THEN** the parser's complete output, including the expectation set, is written to the service log at
  debug level

#### Scenario: Validation failures are unaffected

- **WHEN** a statement parses but fails validation — an unknown column, an unsupported construct, or a
  projection-only violation
- **THEN** the message continues to name the offending column or construct exactly as before

#### Scenario: The rejection itself is unchanged

- **WHEN** a parse failure is returned
- **THEN** its HTTP status and its stable `error` code are the same as before this requirement, so a
  client switching on the code sees no change

### Requirement: Catalog metadata is queryable as INFORMATION_SCHEMA

The SQL surface SHALL expose a schema named `INFORMATION_SCHEMA` holding three relations — `TABLES`,
`COLUMNS`, and `SCHEMATA` — describing the same catalog that backs `GET /v1/queries/entities` and
`GET /v1/queries/entities/schema/{name}`, so the SQL catalog and the REST catalog cannot disagree
about which entities exist or what columns they have.

A statement whose tables are all in `INFORMATION_SCHEMA` SHALL be **executed in process** against
those rows and SHALL NOT be translated to a `StructuredQuery` or sent to ClickHouse. It SHALL support
projection, `DISTINCT`, `WHERE`, `ORDER BY`, `LIMIT`/`OFFSET`, and non-`DISTINCT` aggregates
(including `GROUP BY` and `HAVING`), evaluated with standard SQL semantics rather than a shape-matched
subset, so a client is not restricted to the exact statements one connector happens to send.

A statement the in-process evaluator cannot evaluate SHALL be rejected as a **client error** — 400
`bad_request` over REST, `INVALID_ARGUMENT` over Flight — naming the construct and the supported
shapes. It SHALL NOT surface as `500 internal_error` or gRPC `INTERNAL`. This covers a join between two
catalog relations, a subquery, a window function, `ROLLUP`/`CUBE`/`GROUPING SETS`, and any expression
the evaluator rejects at run time (`MIN`/`MAX` over a character column among them): the evaluator is
not total over the plans the validator accepts, so the boundary is enforced rather than assumed.

An aggregate call carrying `DISTINCT` SHALL be rejected rather than evaluated. This is not a shape the
evaluator merely lacks — it evaluates the call as though the `DISTINCT` were absent, so
`COUNT(DISTINCT TABLE_SCHEMA)` would answer with the total row count: a wrong number a client cannot
distinguish from the right one. `SELECT DISTINCT` on the column is unaffected and is what the rejection
message names, so the question remains answerable.

An aggregate with no `GROUP BY` SHALL return exactly one row whether or not its input is empty — `COUNT`
as 0 and every other aggregate as null — because SQL requires it and because
`SELECT COUNT(*) … WHERE TABLE_NAME = '<name>'` is the ordinary way a client asks whether an object
exists. Returning no row there reads to a JDBC-shaped client as "no data" rather than as "none". A bound
that discards the row anyway (`OFFSET 1`) SHALL keep returning nothing, and a grouped aggregate over an
empty input SHALL keep returning no rows: it has no groups to report.

Where that row cannot be produced the statement SHALL be rejected **on its shape**, so the outcome never
depends on whether a particular catalog happened to match. Three shapes qualify, all of which would
require evaluating something over the synthesized row:

- `HAVING` without `GROUP BY` — the row would have to be tested against the predicate.
- Any expression computed over a grouping-less aggregate. This is wider than arithmetic: `COUNT(*) + 1`,
  `CAST(COUNT(*) AS INTEGER)`, `COALESCE(MAX(x), 0)` and `CASE WHEN COUNT(*) > 0 THEN … END` are all
  refused, though each is admitted in its grouped form, where no row is owed. Selecting the aggregate
  itself always works, which is the form the rejection message names.
- An aggregate over a **grouping-less** aggregate, including through a derived table
  (`SELECT COUNT(n) FROM (SELECT COUNT(*) AS n FROM … WHERE <no match>) x`). The inner aggregate owes one
  row, so the outer one would have to be computed over a row the evaluator never produced — answering 0
  where 1 is correct.

  The condition is on the inner aggregate being grouping-less, not on nesting as such. `SELECT DISTINCT`
  and a bare `GROUP BY` also lower to an aggregate, and neither owes a row for an empty input, so
  `SELECT COUNT(*) FROM (SELECT DISTINCT <col> FROM …) x` SHALL be answered — it is the only way this
  surface can count distinct values, and the form the `DISTINCT`-aggregate rejection recommends.

A `DATE` or `TIMESTAMP` value produced by a catalog statement SHALL carry the same Java type, and encode
the same way, as one produced by a query against an entity. The evaluator's internal encodings (a date as
an epoch-day integer, a timestamp as epoch millis) SHALL NOT reach the Arrow writer or the JSON encoder:
the writer has no coercion for the former, so it would fail *after* the stream had started — a mid-stream
gRPC `INTERNAL` outside the client-error guarantee above — and over REST both would serialize as bare
integers where every other ADAS temporal value is an ISO string.

A statement mixing `INFORMATION_SCHEMA` relations with a data entity SHALL be rejected; this follows
from the existing rejection of joins and subqueries and introduces no new construct.

Because the rows are held in process, a metadata statement SHALL NOT be subject to the default page
limit, the maximum-limit ceiling, or the Flight concurrency gate — all of which govern ClickHouse
execution. Its result size is bounded by the catalog itself.

Rows SHALL be derived from the caller's resolved catalog model, so a column hidden from a caller by
sensitive-column access control is absent from `INFORMATION_SCHEMA.COLUMNS` for that caller, matching
its absence from discovery and from query resolution.

`INFORMATION_SCHEMA` and its relations SHALL resolve case-insensitively, quoted or unquoted, matching
the front end's existing identifier handling — `INFORMATION_SCHEMA."TABLES"`,
`information_schema.tables`, and `Information_Schema.COLUMNS` SHALL all resolve.

`TABLES` SHALL carry the columns `TABLE_CATALOG`, `TABLE_SCHEMA`, `TABLE_NAME`, `TABLE_TYPE`, in that
order, all character-typed. One row per queryable entity, with `TABLE_TYPE` `TABLE`, plus one row per
`INFORMATION_SCHEMA` relation with `TABLE_TYPE` `SYSTEM_TABLE`. `TABLE_CATALOG` SHALL be the empty
string, consistent with the empty catalog term the Flight endpoint declares — ADAS has no catalog
namespace and SHALL NOT invent a name for one.

`COLUMNS` SHALL carry the seventeen columns `TABLE_CATALOG`, `TABLE_SCHEMA`, `TABLE_NAME`,
`COLUMN_NAME`, `ORDINAL_POSITION`, `COLUMN_DEFAULT`, `IS_NULLABLE`, `DATA_TYPE`, `COLUMN_SIZE`,
`CHARACTER_MAXIMUM_LENGTH`, `CHARACTER_OCTET_LENGTH`, `NUMERIC_PRECISION`, `NUMERIC_PRECISION_RADIX`,
`NUMERIC_SCALE`, `DATETIME_PRECISION`, `INTERVAL_TYPE`, `INTERVAL_PRECISION`, in that order.
`ORDINAL_POSITION` SHALL be 1-based. `IS_NULLABLE` SHALL be `YES` for every column, which matches the
Arrow mapping — every result field is nullable — rather than being a default. `COLUMN_DEFAULT`,
`INTERVAL_TYPE`, and `INTERVAL_PRECISION` SHALL be null: ADAS has no column defaults and no interval
type. A value ADAS cannot determine SHALL be null rather than zero, so "unknown" is distinguishable
from "none".

`SCHEMATA` SHALL carry `CATALOG_NAME`, `SCHEMA_NAME`, `SCHEMA_OWNER`, `TYPE`, `IS_MUTABLE`, with one
row for the data schema and one for `INFORMATION_SCHEMA`.

`CATALOGS` and `VIEWS` SHALL NOT be provided: ADAS has no catalog namespace and no views, and an
invented catalog row would contradict the endpoint's declared empty catalog term.

`DATA_TYPE` SHALL report the type-name vocabulary Dremio-compatible clients expect, because such a
client resolves the value against a fixed table and fails the whole navigation on an unrecognised
name:

| Declared field type | `DATA_TYPE` |
|---|---|
| `string`, `uuid`, `enum`, `object`, `map` | `CHARACTER VARYING` |
| `integer` | `INTEGER` |
| `long` | `BIGINT` |
| `decimal` | `DECIMAL` |
| `boolean` | `BOOLEAN` |
| `date` | `DATE` |
| `timestamp` | `TIMESTAMP` |
| `array` | `ARRAY` |

`CHARACTER VARYING` SHALL be used rather than `VARCHAR`, and `INTEGER` rather than `INT`.

#### Scenario: Schema listing

- **WHEN** a client executes `SELECT DISTINCT TABLE_SCHEMA FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_TYPE <> 'SYSTEM_TABLE' ORDER BY TABLE_SCHEMA`
- **THEN** the result is a single row whose `TABLE_SCHEMA` is the data schema name, and no
  `INFORMATION_SCHEMA` row is included

#### Scenario: Table listing for a schema

- **WHEN** a client executes `SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_SCHEMA = 'adas' AND TABLE_TYPE <> 'SYSTEM_TABLE'`
- **THEN** the result carries one row per entity listed by `GET /v1/queries/entities`, each with
  `TABLE_TYPE` `TABLE`

#### Scenario: Column listing for a table

- **WHEN** a client executes `SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'adas' AND TABLE_NAME = 'dial_usage_log'`
- **THEN** the result carries one row per field of that entity's flat schema, `IS_NULLABLE` is `YES`
  throughout, and every `DATA_TYPE` is one of the names in the mapping above

#### Scenario: A string column reports Dremio's type spelling

- **WHEN** a client reads `DATA_TYPE` for a `string` column
- **THEN** the value is `CHARACTER VARYING`, never `VARCHAR`

#### Scenario: Case-insensitive resolution

- **WHEN** a client executes the same query as `information_schema.tables` and as `INFORMATION_SCHEMA."TABLES"`
- **THEN** both resolve and return the same rows

#### Scenario: Metadata query does not reach ClickHouse

- **WHEN** a metadata statement is executed while ClickHouse is unavailable
- **THEN** it still succeeds

#### Scenario: Sensitive columns are hidden from COLUMNS

- **WHEN** a caller who may not see sensitive columns lists `INFORMATION_SCHEMA.COLUMNS` for an entity
  that has one
- **THEN** the sensitive column is absent, matching its absence from discovery

#### Scenario: Metadata query is not page-limited

- **WHEN** a metadata statement with no `LIMIT` returns more rows than the default page limit
- **THEN** every row is returned

#### Scenario: Aggregates over the catalog relations

- **WHEN** a client executes `SELECT TABLE_SCHEMA, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS GROUP BY TABLE_SCHEMA`
- **THEN** one row per schema is returned with the correct count, and `COUNT` over a column that is null
  in every row returns 0 rather than the row count

#### Scenario: A DISTINCT aggregate is refused, not answered wrongly

- **WHEN** a client executes `SELECT COUNT(DISTINCT TABLE_SCHEMA) FROM INFORMATION_SCHEMA.COLUMNS`
- **THEN** the statement is rejected as a client error naming `SELECT DISTINCT` as the supported form,
  rather than returning the total row count

#### Scenario: A global aggregate matching nothing still answers

- **WHEN** a client executes `SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA."TABLES" WHERE TABLE_NAME = 'no_such_table'`
- **THEN** one row is returned with `n = 0`, and the same statement under `OFFSET 1` returns no rows

#### Scenario: A temporal value encodes like a query result

- **WHEN** a client executes `SELECT DATE '2020-01-01' AS d FROM INFORMATION_SCHEMA."TABLES" LIMIT 1` over
  Flight
- **THEN** the row streams as a date, not as an epoch-day integer, and the call does not fail mid-stream

#### Scenario: An unevaluable construct is a client error

- **WHEN** a client joins two `INFORMATION_SCHEMA` relations, uses a subquery, a window function, or
  `ROLLUP` over them
- **THEN** each is rejected with 400 `bad_request` / `INVALID_ARGUMENT` naming the construct, never as
  `500 internal_error` / gRPC `INTERNAL`

### Requirement: Entity names resolve schema-qualified as well as bare

Every queryable entity SHALL resolve both unqualified (`FROM dial_usage_log`) and qualified by the
data schema name `adas` (`FROM adas.dial_usage_log`, `FROM "adas"."dial_usage_log"`), resolving to the
same entity with the same columns and the same translation.

The unqualified form SHALL remain canonical: it is what ADAS's Flight capability advertisement steers
clients toward, and no behaviour depends on the qualifier being present. The qualified form exists
because a client that discovers entities through `INFORMATION_SCHEMA` reads a schema name from
`TABLE_SCHEMA` and qualifies every subsequent statement with it.

The schema name SHALL be a fixed constant, not configuration: it is part of the statements clients
save, so varying it per deployment would make a saved statement deployment-specific.

The data schema name and `INFORMATION_SCHEMA` SHALL therefore be **reserved table names**, refused when a
table is created (see table-management, "Schema validation"). SQL resolves a one-part name against
schemas before tables, so an entity named after either schema could not be reached by its bare name,
while `GET /v1/queries/entities` and `INFORMATION_SCHEMA.TABLES` would both keep listing it — a
contradiction the caller cannot act on. Refusing the name when it is chosen is the only point at which
that is one sentence rather than a mystery.

A qualifier that is not the data schema name SHALL continue to fail validation, naming the
unresolvable qualifier.

A star qualified by the schema (`SELECT adas.dial_usage_log.* FROM adas.dial_usage_log`) SHALL expand to
exactly the fields the bare star expands to. The qualifier SHALL NOT change what a star selects: left to
the validator, a schema-qualified star expands against the entity's full row type and so would project
the heavy columns and the dotted enrichment columns that "Star projection expands to the non-heavy source
columns" excludes — reintroducing, for a statement differing only in a qualifier, both the payload the
heavy-column rule exists to keep off the wire and the per-enrichment `LEFT JOIN` that rule removed.

#### Scenario: Schema-qualified star expands like the bare star

- **WHEN** a client executes `SELECT adas.dial_usage_log.* FROM adas.dial_usage_log`
- **THEN** the projected columns are exactly those of `SELECT * FROM dial_usage_log` — heavy columns and
  dotted enrichment columns excluded

#### Scenario: A schema name cannot be taken as a table name

- **WHEN** a client creates a table named `adas` or `information_schema`
- **THEN** the request is refused, naming the reason, rather than creating an entity that discovery lists
  but no bare-name statement can reach

#### Scenario: Qualified and bare names agree

- **WHEN** a client executes `SELECT event_kind FROM adas.dial_usage_log LIMIT 5` and
  `SELECT event_kind FROM dial_usage_log LIMIT 5`
- **THEN** both translate to the same structured query

#### Scenario: Quoted qualifier resolves

- **WHEN** a client executes `SELECT event_kind FROM "adas"."dial_usage_log" LIMIT 5`
- **THEN** it resolves, matching the case-insensitive handling of every other identifier

#### Scenario: Unknown qualifier still fails

- **WHEN** a client executes `SELECT event_kind FROM warehouse.dial_usage_log LIMIT 5`
- **THEN** validation fails and the message names `warehouse` as not found

### Requirement: A trailing statement separator is accepted

A single trailing `;`, optionally followed by whitespace, SHALL be accepted and ignored on every
statement the SQL surface accepts. Clients that terminate generated statements — including the
catalog SQL a Dremio-compatible connector emits — SHALL NOT be rejected for the terminator alone.

Tolerating a terminator SHALL NOT be treated as accepting a batch: the "exactly one statement" screen
is unchanged, so a separator *between* two statements SHALL still fail, and so SHALL more than one
trailing separator.

#### Scenario: Trailing separator ignored

- **WHEN** a client executes `SELECT event_kind FROM dial_usage_log LIMIT 5;`
- **THEN** it translates exactly as the same statement without the separator

#### Scenario: Batch still rejected

- **WHEN** a client submits `SELECT 1 FROM dial_usage_log; SELECT 2 FROM dial_usage_log`
- **THEN** it is rejected as more than one statement

#### Scenario: A semicolon inside a literal is untouched

- **WHEN** a client executes a statement whose final token is the string literal `';'`
- **THEN** the literal is preserved and the statement translates normally

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
aliased. This covers `MAP_KEYS`, `SPLIT_STRING`, `JSON_EXTRACT_ARRAY` and `ARRAY_SLICE`, and holds for the same reason
it holds for a declared `array` field and for
`group_uniq_array`: the DSL has no array literal or comparison, so nothing else is expressible. Any
other use — as a `GROUP BY` key, an `ORDER BY` key, an operand of a predicate, or an argument to
another function — SHALL be rejected at translation time with an error naming the function and stating
that its array result can only be projected.

The restriction SHALL be recognised from the producing function's name rather than from a declared
column type, since a computed column has none. The derived result schema SHALL type the column as an
array with element kind string — except `ARRAY_SLICE`, whose element kind follows its own operand,
since it narrows an array rather than producing one from text.

The one exception is the array-operand carve-out: because `ARRAY_HAS`/`ARRAY_HAS_ANY`/
`ARRAY_HAS_ALL`/`ARRAY_LENGTH`/`ARRAY_JOIN`/`ARRAY_TO_STRING`/`ARRAY_SLICE` accept an array in argument 0, an array-returning scalar
call SHALL be legal there, so `ARRAY_HAS(SPLIT_STRING(topics, ','), 'python')`,
`ARRAY_LENGTH(MAP_KEYS(point_attributes))`, `ARRAY_JOIN(SPLIT_STRING(topics, ','))` and
`ARRAY_TO_STRING(ARRAY_SLICE(GROUP_UNIQ_ARRAY(deployment), 1, 6), ', ')` SHALL all
translate.

#### Scenario: split_string projects
- **WHEN** a client submits `SELECT SPLIT_STRING("conversation_insights.topics", ',') AS t FROM conversations LIMIT 10`
- **THEN** translation succeeds and the result schema types `t` as an array with element kind string

#### Scenario: An array-returning call is refused as a group key by its alias too
- **WHEN** a client groups by the alias of a `MAP_KEYS` or `SPLIT_STRING` projection
- **THEN** translation fails — resolving the key through its alias SHALL NOT bypass the restriction, which
  would otherwise group by whole arrays and return a plausible-looking answer to a question nobody asked

#### Scenario: split_string as a GROUP BY key rejected
- **WHEN** a client submits `SELECT SPLIT_STRING(topics, ',') AS t, count(*) FROM conversations GROUP BY t`
- **THEN** translation fails with an error naming the function and the projection-only restriction —
  grouping by whole arrays is not in the subset. Exploding the array into rows and grouping by the
  element is expressible, but only by wrapping the call in `ARRAY_JOIN`

#### Scenario: split_string nested in an array predicate translates
- **WHEN** a client submits `SELECT count(*) FROM conversations WHERE ARRAY_HAS(SPLIT_STRING(topics, ','), 'python')`
- **THEN** translation succeeds, the array-returning call sitting in the predicate's argument 0

#### Scenario: An array-returning call nested in ARRAY_JOIN translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(MAP_KEYS(point_attributes)) AS k FROM dial_usage_log`
- **THEN** translation succeeds and the result schema types `k` as a string, the map's key type,
  rather than as an array

### Requirement: Enum columns are typed as character data on the SQL surface

A catalog column of type `enum` SHALL be presented to the SQL surface as a character type: it SHALL
be typed `VARCHAR` in the row type inbound SQL is validated against, and `information_schema`
SHALL report its `DATA_TYPE` as `CHARACTER VARYING` with no declared length bound, exactly as a
`string` column is reported.

This is what keeps an enum column usable by clients that speak only SQL and its catalog — a BI tool
reading `information_schema` or a Flight SQL schema sees text, compares it with string literals,
and needs no knowledge of the catalog's `enum` type. The declared domain remains discoverable
through `GET /v1/queries/entities/schema/{name}`; it is deliberately not encoded into the SQL
type.

A projection of an `enum` column SHALL be returned as a string value in the result set and written
into a UTF-8 Arrow vector, the same as a `string` column. On the JSON surfaces this means a
**quoted JSON string**: the response body SHALL be valid JSON under a strict reader, on both
`POST /v1/queries/execute` and `POST /v1/queries/execute-sql`. This is stated explicitly because the
underlying driver represents an enum cell as a numeric type carrying its name, which a naive
pass-through serializes as a bare unquoted token — valid-looking output that no strict JSON parser
accepts.

`VARCHAR` is what the SQL *front end* validates against, not the last word: an inbound statement is
translated into the DSL and then executed through the ordinary DSL pipeline, so every DSL validation
rule applies to it too. A statement applying a string function to an `enum` column — including the
`LIKE`/`ILIKE` forms, which translate into the DSL's contains family — SHALL therefore be rejected
before execution, with the same validation error the DSL surface gives, naming `enum` as the
offending type, rather than being sent to ClickHouse and failing there. The two surfaces are not
asymmetric: the SQL type is widened to `VARCHAR` so a BI client can *read and compare* the column,
while the shared DSL validation is what keeps a string function over an enum from reaching the
server.

#### Scenario: A string function over an enum column is rejected on the SQL surface

- **WHEN** a `POST /v1/queries/execute-sql` statement applies a string function to an `enum` column
  (for example `WHERE status LIKE '%run%'`)
- **THEN** the request is rejected with a validation error naming `enum` as the offending type,
  the same rejection the DSL surface gives, rather than a ClickHouse execution failure

#### Scenario: Inbound SQL filters an enum column with a string literal

- **WHEN** a `POST /v1/queries/execute-sql` statement compares an `enum` column against a string
  literal
- **THEN** the statement validates and executes, returning the matching rows

#### Scenario: information_schema reports an enum column as character data

- **WHEN** a client reads `information_schema.columns` for a table holding an `enum` column
- **THEN** that column's `DATA_TYPE` is `CHARACTER VARYING`, with a null character-maximum-length,
  identical to a `string` column's row

#### Scenario: An enum column projects as a string

- **WHEN** a SQL or Flight query projects an `enum` column
- **THEN** the value is returned as its string form, in a UTF-8 result column

#### Scenario: An enum projection is valid JSON

- **WHEN** either JSON query endpoint projects an `enum` column
- **THEN** the value appears as a quoted JSON string and the whole response body parses under a
  strict JSON reader — never as a bare unquoted token

### Requirement: Joins compose queryable entities

The subset SHALL accept a statement that joins two or more queryable entities with `INNER JOIN`,
`LEFT [OUTER] JOIN`, `RIGHT [OUTER] JOIN`, `FULL [OUTER] JOIN`, `CROSS JOIN`, or a comma-separated `FROM`
list. Each join operand SHALL be a queryable entity (optionally aliased), a derived table, or a CTE reference;
the same entity MAY appear more than once under distinct aliases (a self-join). Every operand SHALL be
validated against the catalog exactly as a single-entity statement is: unknown entities and unknown columns
are rejected, a `sensitive` column stays unresolvable in every operand for a caller without
`TABLE_SENSITIVE` on the table that owns it, and
an operand's enrichment columns SHALL be addressable as its double-quoted flat identifiers qualified by the
operand's alias (e.g. `a."deployment_ref.display_name"`).

An `ON` condition SHALL be any predicate the subset accepts (comparisons, `IN` lists, `IS [NOT] NULL`,
boolean function calls, `AND`/`OR`/`NOT`) and, for every join kind other than `CROSS JOIN`, SHALL contain
at least one equality comparison between an expression of the left operand and an expression of the right
operand; a join whose `ON` has no such equality SHALL be rejected at translation time with an error saying
so, since the underlying engine cannot execute it. `USING (col, …)` SHALL be accepted as the equivalent
equality conjunction. A `CROSS JOIN` and a comma-joined `FROM` list SHALL be accepted without a condition.

Column references in the select list, `WHERE`, `GROUP BY`, `HAVING` and `ORDER BY` of a join SHALL be
resolved by the validator: a name that exists in more than one operand SHALL be rejected as ambiguous
unless qualified. Output column names SHALL be made unique when two projected columns would otherwise
share a name. A bare `*` SHALL expand to the concatenation of every operand's wildcard fields (the
non-heavy source columns of each entity, in operand order), and `alias.*` to that one operand's wildcard
fields; heavy and enrichment columns are never part of either expansion. This SHALL hold whatever the
operands are named: a derived-table operand the caller left unaliased SHALL be given a generated alias so
the bare star expands operand by operand as above, rather than falling back to a full-row-type expansion.

The joined result SHALL keep every other rule of the subset: `WHERE`, `GROUP BY`, `HAVING`, `DISTINCT`,
`ORDER BY` and `LIMIT`/`OFFSET` over the join behave as they do over one entity, and the projection-only
restrictions on `array`, `object` and `map` columns apply to every operand's columns alike. Every column
SHALL keep the type the catalog declares for it across an operand boundary — a join operand, a derived
table or a CTE — so the carve-outs keyed on a declared type (the map accessors over a `map` column, the
document comparisons over an `object` column, the `enum`/`string` affinity) hold for a column of an
operand exactly as they do for the same column in a single-entity statement.

An outer join (`LEFT`, `RIGHT`, `FULL`) SHALL yield **NULL** for every column of an unmatched side,
whatever the column's physical nullability — standard SQL semantics, not the engine's default-value
behaviour. Consequently, within a composite statement an enrichment column of a non-nullable type whose
enrichment row is absent also reads as NULL, whereas the same column in a single-entity statement keeps
reading as its type default (the documented behaviour of the enrichment join); this is the one place the
two statement shapes differ.

#### Scenario: Unmatched outer-join columns are NULL regardless of nullability
- **WHEN** a client submits `SELECT a.event_id, b.rate FROM dial_usage_log a LEFT JOIN rate_analytics b ON a.response_id = b.response_id` and `rate` is a non-nullable column
- **THEN** rows without a matching `rate_analytics` row carry a JSON `null` in `rate`, not `0`

#### Scenario: Inner join returns matched rows
- **WHEN** a client submits `SELECT a.project_id, b.rate FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id WHERE a.event_kind = 'chat'`
- **THEN** the statement executes and returns one row per matching pair, with columns `project_id` and `rate`

#### Scenario: Left join keeps unmatched rows
- **WHEN** a client submits `SELECT a.event_id, b.rate FROM dial_usage_log a LEFT JOIN rate_analytics b ON a.response_id = b.response_id`
- **THEN** every row of `dial_usage_log` is returned, with `rate` null where no `rate_analytics` row matches

#### Scenario: Self-join under aliases
- **WHEN** a client submits `SELECT p.event_id, c.event_id AS child FROM dial_usage_log p JOIN dial_usage_log c ON c.core_parent_span_id = p.core_span_id`
- **THEN** the statement executes and the two aliases resolve to two independent scans of the entity

#### Scenario: Join without an equality condition rejected
- **WHEN** a client submits `SELECT a.event_id FROM dial_usage_log a JOIN rate_analytics b ON a.total_tokens > b.rate`
- **THEN** translation fails with a 400 stating that a join condition must contain an equality between the two sides, and ClickHouse is not contacted

#### Scenario: Cross join accepted
- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log a CROSS JOIN rate_analytics b`
- **THEN** the statement executes under the shared query budget

#### Scenario: Enrichment columns resolve per operand
- **WHEN** a client submits `SELECT a."usage_client_identity.client_session_id", b.rate FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id`
- **THEN** the enrichment is joined for operand `a` only and the column is returned under its flat name

#### Scenario: Star over a join expands each operand's wildcard fields
- **WHEN** a client submits `SELECT * FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id`
- **THEN** the result columns are `a`'s non-heavy source columns followed by `b`'s, with no heavy or enrichment column among them, and duplicate names made unique

#### Scenario: Star over a join with an unaliased derived table still omits heavy columns
- **WHEN** a client submits `SELECT * FROM dial_usage_log a JOIN (SELECT user_id FROM events) ON a.user_hash = user_id`, or its comma-joined form
- **THEN** the result columns are `a`'s non-heavy source columns followed by the derived table's, with no heavy or enrichment column among them — the same expansion the aliased spelling produces

#### Scenario: Ambiguous unqualified column rejected
- **WHEN** a client submits `SELECT response_id FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id`
- **THEN** translation fails with a 400 validation error naming the ambiguous column

#### Scenario: Map accessors read a joined operand's map column
- **WHEN** a client submits `SELECT map_value(b.attributes, 'user.email') FROM dial_usage_log a JOIN otel_spans b ON a.trace_id = b.trace_id`
- **THEN** the statement executes and returns the attribute's value, exactly as `SELECT map_value(attributes, 'user.email') FROM otel_spans` does; a `json_extract_*` call over the same column stays rejected as a `map` field on both statement shapes

#### Scenario: Sensitive columns stay hidden in every operand
- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table references a `sensitive` column
  of either join operand
- **THEN** translation fails as for an unknown column, exactly as it does for a single-entity statement

### Requirement: Common table expressions are inlined

The subset SHALL accept a statement beginning with a `WITH` clause that declares one or more named,
non-recursive common table expressions whose body is a `SELECT` (with or without `ORDER BY`/`LIMIT`). A CTE
MAY reference a CTE declared before it. Each reference to a CTE — in `FROM`, as a join operand, or inside a
subquery — SHALL be treated exactly as the equivalent derived table written in place; a CTE referenced twice
is evaluated as two derived tables. `WITH RECURSIVE` SHALL be rejected at translation time with an error
naming it, and a CTE whose body is a set operation SHALL be rejected as a set operation. A trailing `ORDER BY`
on the outer statement SHALL not change any of this: `WITH … SELECT … ORDER BY x` is the same statement as
`WITH … SELECT …` plus a sort, so star expansion, output naming and the `WITH RECURSIVE` rejection SHALL apply
to it identically.

#### Scenario: CTE feeds an aggregate
- **WHEN** a client submits `WITH chats AS (SELECT project_id, total_tokens FROM dial_usage_log WHERE event_kind = 'chat') SELECT project_id, sum(total_tokens) AS t FROM chats GROUP BY project_id`
- **THEN** the statement executes and returns the per-project sums over chat rows only

#### Scenario: CTE joined to an entity
- **WHEN** a client submits `WITH top AS (SELECT response_id FROM rate_analytics ORDER BY rate DESC LIMIT 10) SELECT l.event_id FROM dial_usage_log l JOIN top ON l.response_id = top.response_id`
- **THEN** the statement executes; the CTE's `LIMIT 10` is honoured inside the join operand and the outer statement receives the default limit

#### Scenario: Recursive CTE rejected
- **WHEN** a client submits `WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT n FROM r`, with or without a trailing `ORDER BY n`
- **THEN** translation fails with a 400 naming recursive CTEs as unsupported

#### Scenario: Star under a CTE with a trailing ORDER BY still omits heavy columns
- **WHEN** a client submits `WITH c AS (SELECT * FROM dial_usage_log) SELECT * FROM c ORDER BY event_id` — the shape a BI tool generates when it appends its own sort
- **THEN** the result columns are exactly those of the same statement without the `ORDER BY`: the entity's non-heavy source columns, with no heavy or enrichment column among them

### Requirement: Derived tables nest query blocks

The subset SHALL accept a `FROM` item that is a parenthesised `SELECT` of any accepted shape, with or without
an alias. The inner block MAY carry `WHERE`, `GROUP BY`/`HAVING`, `DISTINCT`, `ORDER BY` and `LIMIT`/`OFFSET`,
and the outer block MAY filter, aggregate, sort and page over its columns; nesting depth is not limited by the
translator. This supersedes the earlier rejections of "a filter above ORDER BY/LIMIT", "aggregation above
ORDER BY/LIMIT", "multiple ORDER BY/LIMIT levels" and "nested aggregation (aggregates over aggregates, or
DISTINCT combined with grouping)": each of those shapes SHALL now be accepted, the inner block becoming a
derived table.

Only the **outermost** block is subject to the row governance (default limit, maximum limit, and on Flight
the `flight.max-rows` clamp): an inner `LIMIT`/`OFFSET` SHALL be executed exactly as written, including a
value above the shared maximum, because it bounds an intermediate result rather than the rows returned.
An inner block without a `LIMIT` receives no default limit. A derived table's column types are those the
subset would report for the inner block's result columns, so an outer expression over them is typed as if
it were written over the inner expression directly.

**A statement whose `SELECT` list does not carry its own `ORDER BY` expression SHALL be accepted**, and
SHALL be nested the same way: the ordered, bounded computation becomes the inner block and the
projection applies over it. This holds whether or not the statement itself is written with a derived
table, and whether the sort expression is an aggregate or an ordinary computed value — all four of
these SHALL execute and return the rows the SQL describes:

- `SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY count(*) DESC LIMIT 5`
- `SELECT deployment, sum(price) FROM dial_usage_log WHERE deployment IN (SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY sum(price) DESC LIMIT 8) GROUP BY deployment`
- `SELECT d FROM (SELECT deployment AS d, count(*) AS n FROM dial_usage_log GROUP BY deployment ORDER BY n DESC LIMIT 5) t`
- `SELECT event_id FROM dial_usage_log ORDER BY length(event_kind) LIMIT 5`

This **supersedes the rejection** `an ORDER BY expression must be a column or appear in the SELECT
list`, which SHALL no longer be reported for a statement of this shape. The refusal remains only where
the expression can be resolved neither against the projection nor against a source column.

The statement's own **row bound SHALL travel with the nesting**: the `LIMIT` it wrote SHALL bound the
rows returned, not only the inner computation, so the outer block SHALL NOT fall back to the default
limit and a `LIMIT` above the shared maximum SHALL be rejected exactly as it is for the same statement
without a computed sort key. An `OFFSET` SHALL be applied once — inside, where the ordering it skips
into is — and SHALL NOT be re-applied over the projection.

The statement's **ordering SHALL travel with the nesting too**: the rows the statement returns SHALL
arrive in the order its `ORDER BY` states, not merely in the order the inner block computed. The outer
block SHALL therefore carry an `ORDER BY` of its own, naming — for every key, in the statement's own
order and with its direction and null ordering unchanged — the column the sealed block exposes for that
key. This is not implied by the inner ordering: row order out of a derived table is not contractual,
and a database MAY remove an inner `ORDER BY` whose enclosing block does not order (ClickHouse does, by
default). The no-`LIMIT` form, where the inner `ORDER BY` has no row bound attached, SHALL be ordered on
this basis exactly as the `LIMIT` form is.

Where re-expressing a key would be **ambiguous** the statement SHALL be rejected with HTTP 400 rather
than executed with a different ordering: if a select output is named after the column a sort key names
while projecting a different expression, the DSL would resolve the key against that output, so the
translator SHALL refuse and say so, on the same footing as the same collision on a `GROUP BY` column.

**A statement that leaves a computed `GROUP BY` key unprojected SHALL be accepted**, and SHALL be
nested the same way and for the same reason: the DSL's `group_by` names a select output or a source
column and has no group-by-expression, so a key the projection does not carry has no name left to be
given. The grouped computation becomes the inner block — keeping the key in its own select list, where it
names the grouping — and the projection applies over it. Both shapes SHALL execute and return what the
SQL describes:

- `SELECT count(*) AS n FROM dial_usage_log GROUP BY lower(event_kind)` — the key is never projected, so
  the inner block names it after the expression, exactly as an unnamed select item is named
- `SELECT 1 AS one FROM dial_usage_log GROUP BY lower(event_kind)` — the projection carries no aggregate at
  all, which lowers to a deduplication over the block's keys rather than to an aggregation; the keys are
  the same thing to nest, and the statement SHALL still return one row per distinct lowered kind rather
  than the single row a `DISTINCT` over the projection would give
- `SELECT avg(c) AS a FROM (SELECT ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS tag, sum(total_price) AS c FROM dial_usage_log GROUP BY tag) t`
  — the key is projected by the inner block, and the enclosing statement needs only the aggregate

This **supersedes the rejection** `a computed GROUP BY expression must also appear in the SELECT list`,
which SHALL no longer be reported for a statement of this shape. That message was unactionable on the
second shape above: the key *was* written in the inner `SELECT` list, and it is the enclosing statement's
own projection — not the client's — that leaves it out. The refusal that remains is the **ambiguous** one,
on the footing the paragraph above states it: a computed key whose name is also a column of the source is
refused rather than executed grouped by that column.

A key that is a **plain column** SHALL keep translating with no extra level **where the projection carries
an aggregate**: such a key resolves against the source's columns, so the inner block of `SELECT avg(n) FROM
(SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id) t` SHALL group by `project_id`
without projecting it, and a projection that **keeps** its computed key SHALL stay one block. A projection
carrying **no** aggregate is the deduplication shape below and SHALL gain a level whatever its key is, bare
column included — `SELECT 1 AS one FROM dial_usage_log GROUP BY project_id` nests. That costs such a
statement its single-block document; the alternative is executing it as `SELECT DISTINCT 1`, which returns
one row for a statement that asks for one per distinct key.

The same nesting SHALL apply to a **deduplication key** a projection drops, for the same reason and with
the same consequence if it did not: `SELECT a FROM (SELECT DISTINCT a, b FROM t) x` deduplicates by the
pair `(a, b)` and then projects `a`, which is not `SELECT DISTINCT a FROM t` and SHALL NOT be executed as
it. The deduplicating block SHALL keep its own keys and the projection SHALL apply over it.

Keeping every key is **not** by itself enough to make a projection safe over a deduplication. A projection
that computes a **window function** or the row-expanding **`array_join`** in its own row scope above a
`DISTINCT` block SHALL be nested as well, because the engine evaluates both in the select stage that feeds
the deduplication rather than over its result. "In its own row scope" excludes a subquery: an `array_join`
or window inside an uncorrelated scalar subquery expands or ranks that subquery's rows and reaches the
projection as one value, so it SHALL NOT nest the block — `SELECT a, b, (SELECT ARRAY_JOIN(execution_path)
FROM dial_usage_log LIMIT 1) AS s FROM (SELECT DISTINCT a, b FROM t) x` stays one block. Folded into one block, `SELECT a, b, row_number() OVER () AS rn FROM (SELECT DISTINCT
a, b FROM t) x` would become `SELECT DISTINCT a, b, row_number() OVER () FROM t`, numbering every source
row and then deduplicating triples the numbering has already made unique — one row per source row where
the statement asks for one per distinct pair. `array_join` above a `DISTINCT` loses rows the other way, by
deduplicating after the expansion. An output that merely **passes a key through** is not such a
computation, so a block deduplicating over its own window or expansion SHALL NOT be nested twice.

A `DISTINCT` **over** a window is nested for the mirror-image reason and SHALL be: a window is computed
after grouping, so it cannot feed the deduplication in the same block, and `SELECT DISTINCT row_number()
OVER () AS rn FROM dial_usage_log` SHALL compute the numbering in an inner block and deduplicate over it.
That nesting predates the rules above and is correct; what it shares with them is that the statement, having
written no nesting of its own, SHALL be told a reason that fits it.

An ordinary `SELECT DISTINCT`, whose projection is its key list and computes neither of those, SHALL stay
one block and keep its document. Two shapes are nested although their rows would have been right either
way, and SHALL be: a dropped key **functionally determined** by a kept one (`SELECT a FROM (SELECT DISTINCT
a, lower(a) AS l FROM t) x`, where deduplicating by the pair is deduplicating by `a`), and a scalar of a
key whose own expansion or window the rule then finds again (`SELECT step, length(step) FROM (SELECT
DISTINCT ARRAY_JOIN(execution_path) AS step FROM dial_usage_log) x`, where the `array_join` is the key's,
already evaluated inside the block). Each returns the same rows nested or not, so each loses only its
single-block document — the price of a rule stated on the shape of the projection rather than on a
functional-dependency analysis this surface does not perform.

The grouped block's own **row bound SHALL travel with the nesting** on the terms stated above for a sort
key: a `LIMIT` the statement wrote SHALL bound the rows it returns, and the ordering that `LIMIT` selects
with SHALL be re-expressed over the derived table. A statement's own top-level `ORDER BY` SHALL travel
outward whether or not it carries a `LIMIT`, exactly as on the sort-key path. An `ORDER BY` written inside
a **sub-select with no row bound** SHALL be executed on the block that carries it and SHALL NOT be
discarded: `SELECT c FROM (SELECT lower(event_kind) AS k, sum(total_price) AS c FROM dial_usage_log
GROUP BY k ORDER BY c DESC) t` returns its rows in descending `c` order. Standard SQL guarantees no order
for that statement, because the clause orders an intermediate result — but ClickHouse honours it
(measured on 26.3.24, with `query_plan_remove_redundant_sorting` at its default `1`: `SELECT c FROM
(SELECT x % 3 AS k, sum(x) AS c FROM (SELECT arrayJoin([1,2,3,4,5,6,7]) AS x) GROUP BY k ORDER BY c DESC)
t` returns `12, 9, 7`), and this surface executes what the database executes rather than what the
standard permits. This **supersedes** the earlier statement that the planner discards such an `ORDER BY`
before translation and that neither block orders: that was Calcite's `removeSortInSubQuery` default
speaking, and it is disabled here, because the same drop silently removed the ordering of a set
operation's last branch — a clause the caller wrote that has nowhere else to go.

Because a nested statement has no single-block document, `POST /v1/queries/translate-sql` SHALL report
that the statement has no structured-query equivalent, as it does for every composite statement.

A projection containing the row-expanding `array_join` over a bounded block SHALL be nested like any
other, and the rows it returns SHALL be the rows the **engine** returns for the same statement written
as one block. ClickHouse applies an `ORDER BY … LIMIT` whose key does not depend on the expansion
*before* the expansion, so `SELECT ARRAY_JOIN(execution_path) AS step FROM dial_usage_log ORDER BY
length(trace_id) LIMIT 5` bounds source rows rather than expanded ones and returns nothing at all when
each of those five holds an empty array — measured, and measured to be the same answer with no nesting
involved (`SELECT ARRAY_JOIN(execution_path), length(trace_id) AS n … ORDER BY n LIMIT 5`, one block,
returns nothing too). The nesting is therefore faithful, and this surface SHALL NOT refuse the shape on
account of it: the divergence is the engine's, it predates the nesting, and refusing here would buy no
correctness. Without an `ORDER BY` the same `LIMIT` bounds expanded rows, as SQL describes.

A sort key that is a **plain column** SHALL keep translating as a single block, exactly as before: the
DSL resolves such a key against the source's columns, so `SELECT event_id FROM dial_usage_log ORDER BY
request_time DESC LIMIT 5` SHALL still yield a single-block `StructuredQuery` rather than a nested one.

Because a nested statement has no single-block document, `POST /v1/queries/translate-sql` SHALL report
that the statement has no structured-query equivalent — the refusal every composite statement gets —
rather than the `ORDER BY` message. It SHALL still execute through `POST /v1/queries/execute-sql`,
Flight SQL, and a saved query.

**A nested block cannot open with a parenthesis and carry a trailing clause.** A parenthesized block in a
`FROM` item or a CTE body SHALL NOT both begin with a `(` of its own and end with a trailing
`ORDER BY`/`LIMIT`/`OFFSET`/`FETCH`. This is a limitation of the parser's grammar, not a rule of this
surface: Calcite chooses between two readings of the text after a `(` on lookahead, and only the reading
that cannot itself start at a `(` accepts a trailing clause, so a block opening with a parenthesis is
committed to the reading with nowhere to put one. ClickHouse executes the statement, so the divergence is
ADAS's; it is recorded here because the shape is reachable by combining two things the surface documents —
parenthesizing a set operation's branch, and nesting the operation to bound its combined result.

The limitation is **not** specific to set operations (`FROM ((SELECT a FROM t) ORDER BY a)` and a
parenthesized join with a trailing `LIMIT` fail the same way) and reaches **only** those two positions:
the same text SHALL be accepted as the top-level statement (`(<A>) UNION ALL <B> ORDER BY a LIMIT 2`), as
an `IN`/`NOT IN`/`EXISTS`/scalar-subquery operand, and wherever the parenthesized branch is not the first
thing in the block (`FROM (<A> UNION ALL (<B>) UNION ALL <C> LIMIT 2)`).

Because the statement dies in the parser, it SHALL surface as a parse error rather than as a named
refusal — but the error SHALL NOT stop at the parser's own diagnosis, which points at the trailing
clause. It SHALL additionally name the **position of the block** that cannot carry the clause and state
both spellings that work: parenthesize the block's last query as well, or leave its first one
unparenthesized. Both return what the rejected text describes. Where this sentence applies it SHALL
replace the reserved-word sentence a parse failure otherwise carries, whose advice — quote the clause's
keyword as an identifier — describes a different statement that also does not parse.

#### Scenario: A nested block opening with a parenthesis rejects a trailing clause
- **WHEN** a client submits `SELECT k FROM ((SELECT event_kind AS k FROM dial_usage_log LIMIT 5) UNION ALL SELECT event_kind FROM dial_usage_log LIMIT 2)`
- **THEN** translation fails with 400 `bad_request` reporting a parse error whose message names the
  position of the `(` that opens the block and both working spellings, and does **not** claim that `LIMIT`
  is a reserved word wanting quotes

#### Scenario: The limitation is not about set operations
- **WHEN** a client submits `SELECT count(*) AS n FROM ((SELECT event_kind FROM dial_usage_log) ORDER BY event_kind)`
- **THEN** it fails the same way, and `SELECT count(*) AS n FROM (SELECT event_kind FROM dial_usage_log ORDER BY event_kind)` succeeds

#### Scenario: Parenthesizing every branch is the spelling that works
- **WHEN** a client submits `SELECT count(*) AS n FROM ((SELECT event_kind FROM dial_usage_log) UNION ALL (SELECT event_kind FROM dial_usage_log LIMIT 2))`
- **THEN** the statement executes, the `LIMIT 2` bounding the last branch alone — the same rows the
  rejected spelling describes

#### Scenario: A subquery operand takes the shape the nested positions refuse
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log WHERE event_kind IN ((SELECT event_kind FROM dial_usage_log) UNION ALL SELECT event_kind FROM dial_usage_log LIMIT 2)`
- **THEN** the statement executes, the `LIMIT 2` bounding the subquery's last branch

#### Scenario: Filter over a limited subquery
- **WHEN** a client submits `SELECT project_id FROM (SELECT project_id, total_tokens FROM dial_usage_log ORDER BY total_tokens DESC LIMIT 5) t WHERE total_tokens > 100`
- **THEN** the statement executes: the five largest rows are taken first and then filtered

#### Scenario: Aggregate over an aggregate
- **WHEN** a client submits `SELECT avg(n) FROM (SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id) t`
- **THEN** the statement executes and returns the average per-project count

#### Scenario: Inner limit above the shared maximum is executed as written
- **WHEN** a client submits `SELECT count(*) FROM (SELECT event_id FROM dial_usage_log LIMIT 5000) t` to `POST /v1/queries/execute-sql`
- **THEN** the statement is accepted — the 1000-row maximum governs the outer result only — and returns one row

#### Scenario: Inner block without a limit is not defaulted
- **WHEN** a client submits `SELECT count(*) FROM (SELECT event_id FROM dial_usage_log) t` over an entity holding 250 rows
- **THEN** the count is 250, not 100

#### Scenario: Top-N by an aggregate the projection does not carry
- **WHEN** a client submits `SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY count(*) DESC LIMIT 5`
- **THEN** the statement is accepted and returns the five deployments with the most rows, in descending
  order of that count — not a 400 naming `ORDER BY`

#### Scenario: The top-N idiom inside IN translates
- **WHEN** a client submits `SELECT deployment, sum(price) AS spend FROM dial_usage_log WHERE deployment IN (SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY sum(price) DESC LIMIT 8) GROUP BY deployment`
- **THEN** the statement is accepted and returns one row per deployment among the eight highest-spending

#### Scenario: A computed row-mode sort key the projection does not carry
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log ORDER BY length(event_kind) LIMIT 5`
- **THEN** the statement is accepted and returns five rows ordered by that length

#### Scenario: The ordering is on the returned rows, not only inside the nesting
- **WHEN** a client submits `SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY count(*) DESC` over an entity holding three deployments at three distinct row counts, with no `LIMIT`
- **THEN** the deployments come back in descending order of that count — the executed statement orders
  the projection over the derived table, not only the derived table itself

#### Scenario: An output shadowing the sort key's column is refused
- **WHEN** a client submits `SELECT length(event_kind) AS request_time FROM dial_usage_log ORDER BY dial_usage_log.request_time DESC LIMIT 5`
- **THEN** the statement is rejected with 400 `bad_request`, the message naming the alias and the
  `ORDER BY` column it shadows — rather than being executed ordered by the alias's expression

#### Scenario: The stated limit still bounds the rows returned
- **WHEN** a client submits `SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY count(*) DESC LIMIT 500` over an entity with more than 500 deployments
- **THEN** 500 rows are returned, not the default 100

#### Scenario: The shared maximum still applies to the stated limit
- **WHEN** a client submits the same statement with `LIMIT 1500`
- **THEN** the statement is rejected with the page-limit error naming the 1000-row maximum, exactly as
  a statement with no computed sort key is

#### Scenario: A plain-column sort key still yields one block
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log ORDER BY request_time DESC LIMIT 5` to `POST /v1/queries/translate-sql`
- **THEN** a single-block StructuredQuery is returned, sorted on `request_time`, with no derived table

#### Scenario: A nested sort key has no structured-query document
- **WHEN** a client submits `SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY count(*) DESC LIMIT 5` to `POST /v1/queries/translate-sql`
- **THEN** the response states that the statement executes but has no structured-query equivalent

#### Scenario: Aggregate over a grouped block whose projection drops the computed key
- **WHEN** a client submits `SELECT avg(c) AS a FROM (SELECT ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS tag, sum(total_price) AS c FROM dial_usage_log GROUP BY tag) t`
- **THEN** the statement is accepted and returns the average of the per-tag sums — not a 400 saying the
  computed `GROUP BY` expression must also appear in the `SELECT` list
- **AND** the inner block still groups by `tag` and still projects it, so the grouping is on the expanded
  tag rather than on anything else

#### Scenario: GROUP BY an expression the statement never projects
- **WHEN** a client submits `SELECT count(*) AS n FROM dial_usage_log GROUP BY lower(event_kind)`
- **THEN** the statement is accepted and returns one row per lower-cased kind, carrying only `n`

#### Scenario: A grouped block's own bound still travels with the nesting
- **WHEN** a client submits `SELECT c FROM (SELECT lower(event_kind) AS k, sum(total_price) AS c FROM dial_usage_log GROUP BY k ORDER BY c DESC LIMIT 5) t`
- **THEN** five rows are returned in descending order of `c`, not the default 100 in the order the engine
  found cheapest

#### Scenario: A plain-column group key gains no extra level
- **WHEN** a client submits `SELECT avg(n) FROM (SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id) t`
- **THEN** the inner block groups by the column `project_id` and projects only `n` — the key is named
  against the source, so no column is added to carry it

#### Scenario: A grouped statement whose projection carries no aggregate
- **WHEN** a client submits `SELECT 1 AS one FROM dial_usage_log GROUP BY lower(event_kind)`
- **THEN** one row is returned per distinct lower-cased kind — not the single row that reading the
  statement as `SELECT DISTINCT 1` would return

#### Scenario: A DISTINCT key an enclosing block projects away
- **WHEN** a client submits `SELECT project_id FROM (SELECT DISTINCT project_id, event_kind FROM dial_usage_log) x`
- **THEN** the rows are deduplicated by the pair inside the derived table and then projected, rather than
  deduplicated by `project_id` alone

#### Scenario: The refusal names the computation, not a key, when that is the reason
- **WHEN** a client submits `SELECT project_id, row_number() OVER () AS rn FROM dial_usage_log GROUP BY project_id` to `POST /v1/queries/translate-sql`
- **THEN** the refusal states that the statement executes but has no structured-query equivalent, and what
  it names fits the statement: this one projects every key it groups by and has no join, derived table,
  subquery or `ORDER BY`, so the reason given is the window being separated from the deduplication

#### Scenario: The same refusal fits a DISTINCT over a window
- **WHEN** a client submits `SELECT DISTINCT row_number() OVER () AS rn FROM dial_usage_log` to `POST /v1/queries/translate-sql`
- **THEN** the refusal fits this statement too, although the window is **below** the deduplication rather
  than above it and the statement projects its only key — which is why the clause names the separation and
  not a direction

#### Scenario: A window above a deduplication is computed over the deduplicated rows
- **WHEN** a client submits `SELECT project_id, event_kind, row_number() OVER () AS rn FROM (SELECT DISTINCT project_id, event_kind FROM dial_usage_log) x`
- **THEN** the deduplication stays inside a derived table and the window is computed over its rows —
  numbering the distinct pairs, not every source row as `SELECT DISTINCT a, b, row_number() OVER ()`
  would

#### Scenario: An expansion above a deduplication expands the deduplicated rows
- **WHEN** a client submits `SELECT project_id, ARRAY_JOIN(SPLIT_STRING(event_kind, ',')) AS part FROM (SELECT DISTINCT project_id, event_kind FROM dial_usage_log) x`
- **THEN** the pairs are deduplicated first and expanded afterwards, so repeated elements within one
  array are kept rather than collapsed by a deduplication applied after the expansion

#### Scenario: A plain-column group key with no aggregate in the projection
- **WHEN** a client submits `SELECT 1 AS one FROM dial_usage_log GROUP BY project_id`
- **THEN** one row is returned per distinct `project_id`, the deduplication nested in a derived table —
  not the single row `SELECT DISTINCT 1` returns

#### Scenario: An ordinary SELECT DISTINCT gains no level
- **WHEN** a client submits `SELECT DISTINCT project_id, event_kind FROM dial_usage_log` to `POST /v1/queries/translate-sql`
- **THEN** a single-block StructuredQuery with `distinct` set is returned, with no derived table

#### Scenario: An inner ORDER BY with no row bound is not re-expressed
- **WHEN** a client submits `SELECT c FROM (SELECT lower(event_kind) AS k, sum(total_price) AS c FROM dial_usage_log GROUP BY k ORDER BY c DESC) t`
- **THEN** the statement is accepted and the rows come back in descending `c` order, as ClickHouse returns
  them for that text — the sub-select's `ORDER BY` is not discarded for want of a row bound. (The title is
  the one this scenario shipped under, when the answer was that neither block orders; that reading is
  superseded by the paragraph above.)

#### Scenario: A computed group key the projection drops has no structured-query document
- **WHEN** a client submits `SELECT count(*) AS n FROM dial_usage_log GROUP BY lower(event_kind)` to `POST /v1/queries/translate-sql`
- **THEN** the response states that the statement executes but has no structured-query equivalent

### Requirement: Uncorrelated subqueries in predicates and expressions

The subset SHALL accept a `WHERE` or `HAVING` predicate of the form `<expr> [NOT] IN (<subquery>)`,
`(<expr>, <expr>) [NOT] IN (<subquery>)` with a matching column count, or `[NOT] EXISTS (<subquery>)`. An
expression MAY be a scalar subquery — a parenthesised `SELECT` producing exactly one column — as a comparison
operand or as a select item; it SHALL NOT be an argument of a function call. The subquery SHALL be any
accepted statement, including one that itself joins or nests, and SHALL be validated and governed like any
block (no default limit applies inside it).

The subquery SHALL be **uncorrelated**: a reference from inside the subquery to a column of an enclosing
block SHALL be rejected at translation time with an error stating that correlated subqueries are not
supported and suggesting a join. `IN`/`NOT IN` over null operands follow the engine's semantics; a scalar
subquery that yields more than one row fails at execution and is reported as a 400 `bad_request`. The
result column of a projected scalar subquery is typed as the subquery's single column.

#### Scenario: IN subquery filters rows
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log WHERE response_id IN (SELECT response_id FROM rate_analytics WHERE rate > 4)`
- **THEN** the statement executes and returns only rows whose response has a matching rate row

#### Scenario: NOT EXISTS over an entity
- **WHEN** a client submits `SELECT count(*) FROM dial_usage_log WHERE NOT EXISTS (SELECT 1 FROM rate_analytics WHERE rate > 4)`
- **THEN** the statement executes and returns the full count when no such rate row exists, and zero otherwise

#### Scenario: Scalar subquery as a comparison operand
- **WHEN** a client submits `SELECT event_id, total_tokens FROM dial_usage_log WHERE total_tokens > (SELECT avg(total_tokens) FROM dial_usage_log)`
- **THEN** the statement executes and returns rows above the entity-wide mean

#### Scenario: Scalar subquery projected
- **WHEN** a client submits `SELECT project_id, (SELECT count(*) FROM rate_analytics) AS rates FROM dial_usage_log`
- **THEN** every row carries the same count in `rates`, typed as a 64-bit integer in the result schema

#### Scenario: Correlated subquery rejected
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log l WHERE EXISTS (SELECT 1 FROM rate_analytics r WHERE r.response_id = l.response_id)`
- **THEN** translation fails with a 400 stating that correlated subqueries are not supported and that the statement should be written as a join

### Requirement: CASE expressions translate to the DSL case expression

The subset SHALL accept a searched `CASE WHEN <predicate> THEN <expr> [WHEN …] [ELSE <expr>] END` and a simple
`CASE <expr> WHEN <value> THEN <expr> … END` wherever an expression is, and both SHALL translate to the DSL
`case` expression kind (see [structured-query](../structured-query/spec.md), "Conditional case expression").
Each `WHEN` predicate SHALL be a predicate of the filter grammar — a comparison, `IS [NOT] NULL`, `IN`, `LIKE`
in its contains form, a boolean function call, or a boolean combination of those — so, unlike `if`, a
comparison IS a legal condition here. `THEN`/`ELSE` branches SHALL unify under the same rules as `if`'s
branches (numeric types widen, any other mismatch is rejected, a non-scalar branch is rejected); branches of
different type families SHALL NOT be silently coerced to text — the statement SHALL be rejected as a
validation error naming the mismatch. An omitted `ELSE` SHALL translate to an absent `else` and yield null for
unmatched rows. Unlike `if`, a `THEN` (or `ELSE`) branch that is a null literal SHALL be accepted as long as
some branch has a type — `NULLIF(a, b)` lowers to exactly that shape — and the standard `COALESCE` and
`NULLIF` functions SHALL therefore translate, through the `CASE` the validator lowers them to. A character
literal SHALL keep its own length whatever the other branches' lengths are:
`CASE … THEN 'large' … ELSE 'small' END` SHALL yield `small`, never a blank-padded `small `. The result column
kind SHALL be the widest branch kind.

#### Scenario: Searched CASE classifies rows
- **WHEN** a client submits `SELECT CASE WHEN total_tokens > 1000 THEN 'large' WHEN total_tokens > 100 THEN 'medium' ELSE 'small' END AS size, count(*) FROM dial_usage_log GROUP BY size`
- **THEN** the statement translates to an aggregate query grouped by a `case` expression aliased `size`, executes, and the DSL document round-trips through `POST /v1/queries/translate-sql`

#### Scenario: Simple CASE is accepted
- **WHEN** a client submits `SELECT CASE event_kind WHEN 'chat' THEN 1 ELSE 0 END AS is_chat FROM dial_usage_log`
- **THEN** it translates to the same `case` expression as the searched form `CASE WHEN event_kind = 'chat' THEN 1 ELSE 0 END`

#### Scenario: CASE without ELSE yields null
- **WHEN** a client submits `SELECT CASE WHEN success THEN total_tokens END AS ok_tokens FROM dial_usage_log`
- **THEN** rows where `success` is false carry a null `ok_tokens`

#### Scenario: Mismatched CASE branches rejected
- **WHEN** a client submits `SELECT CASE WHEN success THEN 'yes' ELSE 1 END FROM dial_usage_log`
- **THEN** translation fails with a 400 naming the incompatible branch types, rather than answering `'1'`

#### Scenario: COALESCE and NULLIF translate through CASE
- **WHEN** a client submits `SELECT coalesce(nullif(chat_id, ''), 'none') AS chat FROM dial_usage_log`
- **THEN** it translates to nested `case` expressions and executes, yielding `none` for an empty or null `chat_id`

#### Scenario: Ragged character literals keep their length
- **WHEN** a client submits `SELECT CASE WHEN success THEN 'ok' ELSE 'failed' END AS outcome FROM dial_usage_log` or `WHERE deployment IN ('gpt', 'claude')`
- **THEN** the literals translate as `ok`/`failed` and `gpt`/`claude` with no trailing blanks

### Requirement: Non-grouped select items are rejected in an aggregate query

In a statement with `GROUP BY`, a select item that is neither grouped nor aggregated SHALL be rejected as a
validation error naming the expression, as PostgreSQL does; it SHALL NOT be silently wrapped in an
arbitrary-value aggregate. `GROUP BY` on a select alias stays accepted.

#### Scenario: Non-grouped column rejected
- **WHEN** a client submits `SELECT project_id, total_tokens, count(*) FROM dial_usage_log GROUP BY project_id`
- **THEN** translation fails with a 400 validation error naming `total_tokens` as not grouped

### Requirement: Window functions translate to the DSL window expression

The subset SHALL accept a select item that is a window function call
`<fn> OVER ([PARTITION BY <expr>, …] [ORDER BY <expr> [ASC|DESC] [NULLS FIRST|LAST], …] [<frame>])`, where
`<fn>` is one of the ranking functions `row_number()`, `rank()`, `dense_rank()` or one of the aggregates
`count`, `sum`, `avg`, `min`, `max` over an expression, and `<frame>` is
`ROWS|RANGE BETWEEN <bound> AND <bound>` with bounds `UNBOUNDED PRECEDING`, `<n> PRECEDING`, `CURRENT ROW`,
`<n> FOLLOWING`, `UNBOUNDED FOLLOWING`. The call SHALL translate to the DSL `window` expression kind (see
[structured-query](../structured-query/spec.md), "Window expression"); the standard default frame
(`RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` when `ORDER BY` is present) SHALL be carried as an absent
frame. A window function SHALL be accepted only in the select list (and, through its alias, in `ORDER BY`) —
anywhere inside a select item counts, the condition of a select-list `CASE` included; the validator's
rejection of a window function in `WHERE`, `HAVING` or `GROUP BY` SHALL surface as a 400. In an aggregate
query the window's operands SHALL be group keys or aggregate calls (`sum(sum(x)) OVER (…)`), as standard SQL
requires. `DISTINCT` inside a window function and any other function name under `OVER` (`lag`, `lead`,
`ntile`, `first_value`, …) SHALL be rejected with an error naming the construct. The result column kind SHALL
be a 64-bit integer for the ranking functions and the aggregate's own kind otherwise.

#### Scenario: row_number partitions and orders
- **WHEN** a client submits `SELECT project_id, request_time, row_number() OVER (PARTITION BY project_id ORDER BY request_time) AS rn FROM dial_usage_log`
- **THEN** the statement executes and `rn` restarts at 1 for each project in request-time order

#### Scenario: Running total with a frame
- **WHEN** a client submits `SELECT request_time, sum(total_tokens) OVER (ORDER BY request_time ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running FROM dial_usage_log`
- **THEN** the statement executes and `running` is the cumulative token count

#### Scenario: Ranking over aggregated rows
- **WHEN** a client submits `SELECT project_id, sum(total_tokens) AS t, rank() OVER (ORDER BY sum(total_tokens) DESC) AS r FROM dial_usage_log GROUP BY project_id`
- **THEN** the statement translates to an aggregate query whose `r` is a window expression over the aggregate, and executes

#### Scenario: Window function in WHERE rejected
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log WHERE row_number() OVER (ORDER BY request_time) < 10`
- **THEN** translation fails with a 400 (windowed functions are not allowed in `WHERE`); the accepted spelling is a derived table filtered on the window's alias

#### Scenario: Window function inside a select-list CASE
- **WHEN** a client submits `SELECT CASE WHEN row_number() OVER (ORDER BY request_time) > 5 THEN 'late' ELSE 'early' END AS phase FROM dial_usage_log`
- **THEN** the statement translates to a `StructuredQuery` whose select item is a `case` over a window expression, executes, and the document is accepted unchanged by `POST /v1/queries/execute`

#### Scenario: Unsupported window function rejected
- **WHEN** a client submits `SELECT lag(total_tokens) OVER (ORDER BY request_time) FROM dial_usage_log`
- **THEN** translation fails with a 400 naming `LAG` as unsupported

### Requirement: Composite statements execute without a structured-query document

The translator SHALL produce an executable plan for a **composite statement** — a statement whose plan needs
more than one query block over one entity, because it joins, reads a derived table or CTE, carries a
subquery, or combines branches with a set operation. That plan's leaves SHALL be single-entity blocks and its composition SHALL mirror the statement; the
plan SHALL execute through the same pipeline as a single-entity statement (same read-only client, `final`
deduplication, `query.budget.*` settings and row governance on the outermost block) and SHALL yield the same
response envelope and result-column descriptors. The composite plan has no `StructuredQuery` equivalent:
`POST /v1/queries/translate-sql` SHALL reject it with 400 `bad_request` and the message
`statements with set operations, joins, derived tables or subqueries — including a statement nested by this surface to carry an ORDER BY, GROUP BY or DISTINCT key its SELECT list does not project, or to separate a window function or a row expansion from a DISTINCT — execute but have no structured-query equivalent`.
That message SHALL name **every** reason the translator nests, not only the nesting a client writes: a
statement told about a derived table it never wrote is being mis-described. Five reasons are reachable
without a client writing any nesting, and the message covers them in two clauses (all five are in "Derived
tables nest query blocks"):

- **a key the projection does not carry** — a computed `ORDER BY` expression, a `GROUP BY` key, or a
  `DISTINCT` key
- **a window function or row expansion that cannot share a block with a `DISTINCT`** — either of them
  above the deduplication, or a window below it. The fourth combination does not arise: an expansion
  below a `DISTINCT` shares the block, because `SELECT DISTINCT ARRAY_JOIN(x)` is what that statement
  says — the same reading as the passed-through key above

Three details of the wording are load-bearing. The message SHALL qualify only the **sort** key as computed,
because a group or deduplication key is nested whether it is computed or a bare column, so calling `SELECT 1
AS one FROM dial_usage_log GROUP BY project_id` a computed expression would mis-describe it. The second
clause SHALL be stated as a computation rather than as an unprojected key, because those statements project
every key they group by. And it SHALL say **separate ... from** rather than "over", because the two sit on
opposite sides of the `DISTINCT` in the two cases and only the separation is common to both: `SELECT
project_id, row_number() OVER () AS rn FROM dial_usage_log GROUP BY project_id` computes the window above
the deduplication, `SELECT DISTINCT row_number() OVER () AS rn FROM dial_usage_log` below it, and a message
naming only one direction is false of the other. A statement that is a single block over one entity — including one that uses `CASE`, arithmetic
or a window expression — is NOT composite and SHALL keep translating to a `StructuredQuery` document.

#### Scenario: Join executes over REST
- **WHEN** a client posts a join statement to `POST /v1/queries/execute-sql`
- **THEN** it executes and returns `{"rows": [...]}` with the default limit applied to the joined result

#### Scenario: Join has no DSL document
- **WHEN** a client posts the same join statement to `POST /v1/queries/translate-sql`
- **THEN** the response is 400 `bad_request` with the composite-statement message, and ClickHouse is not contacted

#### Scenario: CASE statement still has a DSL document
- **WHEN** a client posts `SELECT CASE WHEN success THEN 1 ELSE 0 END AS ok FROM dial_usage_log` to `POST /v1/queries/translate-sql`
- **THEN** the response is 200 with a `query` document whose select entry is a `case` expression

#### Scenario: Set operation has no DSL document
- **WHEN** a client posts `SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics` to `POST /v1/queries/translate-sql`
- **THEN** the response is 400 `bad_request` with the composite-statement message, whose first clause names
  set operations, and ClickHouse is not contacted

### Requirement: ARRAY_JOIN expands an array into rows on the SQL surface

The SQL subset SHALL accept `ARRAY_JOIN(array)` and SHALL translate it to the DSL `array_join` call,
so the row-expanding query is expressible identically on both surfaces and round-trips exactly. The
call SHALL take exactly one operand — an `array` column or a call to `MAP_KEYS`, `SPLIT_STRING`,
`JSON_EXTRACT_ARRAY` or `GROUP_UNIQ_ARRAY` — and a call with any other operand count or a non-array
operand SHALL be rejected at translation time, before any engine contact, with an error naming the
expected form.

The derived result schema SHALL type the produced column as the **element** type of the array operand
— an ordinary scalar, not an array — so the column carries no projection-only restriction of its own.
For an `array` column that is its catalog `element_type`; for a computed array it follows the producing
call, which is `string` for `MAP_KEYS`/`SPLIT_STRING`/`JSON_EXTRACT_ARRAY` and the operand's own type
for `GROUP_UNIQ_ARRAY`. The DSL surface SHALL derive the same type for the same document.

The subset SHALL NOT impose positional restrictions of its own on `ARRAY_JOIN`: it SHALL be accepted
in `WHERE`, in `HAVING`, inside an aggregate function's arguments and inside a window function's
operands, because expansion in each of those positions is the engine's own behaviour and a narrower
subset would refuse statements the engine accepts.

`GROUP BY` over the expanded element SHALL be accepted through the projection's **alias**, which is
the one place the array-returning functions' alias rule does not extend to: `ARRAY_JOIN`'s result is a
scalar, so grouping by its alias groups by single elements rather than by whole arrays.

#### Scenario: Expansion translates and round-trips
- **WHEN** a client submits `SELECT ARRAY_JOIN("conversation_insights.topics") AS topic FROM conversations LIMIT 10`
- **THEN** translation produces a row-mode StructuredQuery whose select entry is an `array_join`
  FnExpr over that field aliased `topic`, and the result schema types `topic` as a string rather than
  an array

#### Scenario: Grouping by an expanded element translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(topics) AS topic, count(*) AS n FROM conversations GROUP BY topic`
- **THEN** translation succeeds, producing an aggregate-mode StructuredQuery grouped by the `topic`
  output name

#### Scenario: Grouping by an expansion of a computed array translates as one block
- **WHEN** a client submits `SELECT ARRAY_JOIN(SPLIT_STRING(topics, ',')) AS t, sum(cost) AS c FROM conversations GROUP BY t`
- **THEN** translation succeeds as a single aggregate-mode block grouped by the `t` output name — the
  computed operand is no different from an `array` column here, whether the key is written as the alias
  or repeated as the expression

#### Scenario: Expansion over a computed array translates
- **WHEN** a client submits `SELECT ARRAY_JOIN(SPLIT_STRING(topics, ',')) AS t FROM conversations`
- **THEN** translation succeeds, the array-returning call sitting in `ARRAY_JOIN`'s operand position

#### Scenario: Expansion in a predicate translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE ARRAY_JOIN(execution_path) = 'x'`
- **THEN** translation succeeds, producing an `array_join` FnExpr as the comparison's operand — the
  subset adds no restriction the engine does not have

#### Scenario: A second operand is rejected
- **WHEN** a client submits `SELECT ARRAY_JOIN(topics, ',') AS t FROM conversations`
- **THEN** translation fails with an error naming the single-operand form, rather than treating the
  operand as a delimiter the way the same-named function of other dialects would

#### Scenario: A non-array operand is rejected
- **WHEN** a client submits `SELECT ARRAY_JOIN(model) AS m FROM dial_usage_log`
- **THEN** translation fails before execution with an error naming the expected array operand

### Requirement: Text composition and value rendering in the SQL subset

The subset SHALL accept `CONCAT(value, value[, value…])` (two operands minimum) and
`TO_STRING(value)`, translating them to the DSL `concat` and `to_string` calls with their operands
translated in place. Both SHALL accept operands of any **scalar** type — the operand is not restricted
to character data — and a projection-only operand (`array`, `object`, `map`, or an array-returning
call) SHALL be rejected under the classification that already governs every function's operands.

The **`||` operator SHALL translate to the same `concat` call.** It SHALL be re-bound to `CONCAT`
before validation, so that a mixed-type concatenation such as `'depth ' || 4` is accepted rather than
refused by the operator's string-only type check; `a || b || c` SHALL translate to nested `concat`
calls, which is the same expression. There SHALL be exactly one DSL spelling of the operation
(`concat`), so the DSL→SQL renderer's output and the `||` spelling read back to the same document.

**A `CAST(<expr> AS <character type>)` the caller wrote SHALL translate to `to_string(<expr>)`.** This
supersedes the earlier blanket rejection of an explicit `CAST` for this one target family, and only for
it: a cast to any other type SHALL stay unsupported. Generated SQL uses `CAST(x AS VARCHAR)` to put a
number into a label, and refusing it while accepting `TO_STRING(x)` would reject the spelling clients
actually emit. A declared length (`VARCHAR(50)`) SHALL be ignored rather than refused: the store has no
bounded string type, so there is nothing to truncate to.

Two limits on that rule are **normative**, because both are observable:

- The rule SHALL apply only to a cast **the caller wrote**, never to one the validator injects while
  coercing an operand. A non-text operand of a text function (`not_empty(total_tokens)`,
  `lower(<a long column>)`) SHALL therefore keep failing with HTTP 400 naming the operand's type, as the
  type rules already require — it SHALL NOT be silently accepted as
  `not_empty(to_string(total_tokens))`. A caller who wants that MAY write the cast, or the call,
  explicitly.
- The rule SHALL apply only where the rendering is **needed**: a cast whose operand is already text
  (which on this surface includes a `uuid`, an `enum`, and an `object`/`map` field, all typed
  `VARCHAR`) SHALL translate to the operand alone, as it did before `to_string` existed — **not** to a
  `to_string` call. The two answer the same values, so this is not a semantic choice but a
  document-shape one, and the bare column is the shape that was already accepted: wrapping it would
  change what `POST /v1/queries/translate` returns for a statement that translated before, and would
  put a computed expression where the caller wrote a column — `toString(project_id) = 'p1'` in a
  predicate, a group key on `String` instead of on the underlying `LowCardinality(String)` column —
  for no gain. Rendering a value that is *not* text is what the rule is for, and applies in every
  position the cast may appear (select list, `WHERE`, `GROUP BY`, `ORDER BY`, `HAVING`).

The internal marker the pre-validation rewrite uses to carry a caller-written cast to the typed plan
SHALL NOT become an inbound function name: a statement that spells `TO_STRING_CAST(<expr>)` itself SHALL
be rejected before execution, in any casing and in every position, as a function outside the catalog. The
accepted function surface therefore stays exactly what `GET /v1/queries/sql-functions` advertises — the
SQL surface's own catalog, which lists the `CAST(<expr> AS <character type>)` and `||` spellings named in
this requirement and omits the DSL-only arithmetic names of `GET /v1/queries/functions`.

Neither call SHALL accept a `DISTINCT` quantifier on this surface — the validator refuses it, as it does
for any scalar — and `CONCAT` with fewer than two operands SHALL be
rejected with an error naming the arity. The result column of either call SHALL be typed as text.

#### Scenario: Variadic concat translates
- **WHEN** a client submits `SELECT CONCAT(parent_deployment, ' -> ', deployment) AS edge FROM dial_usage_log`
- **THEN** translation succeeds, producing a row-mode StructuredQuery whose select entry is a `concat`
  FnExpr over the three operands aliased `edge`, and the result schema types `edge` as text

#### Scenario: The concatenation operator lowers onto concat
- **WHEN** a client submits `SELECT deployment || ' / ' || event_kind AS label FROM dial_usage_log`
- **THEN** translation succeeds and the select entry is a nested `concat` FnExpr — not an unsupported-construct
  refusal naming `||`

#### Scenario: A non-text operand of the concatenation operator is accepted
- **WHEN** a client submits `SELECT 'depth ' || total_tokens AS label FROM dial_usage_log`
- **THEN** translation succeeds, the numeric operand rendered as text by the same rule `to_string` states

#### Scenario: to_string renders a number as text
- **WHEN** a client submits `SELECT TO_STRING(total_tokens) AS t FROM dial_usage_log`
- **THEN** translation succeeds, producing a `to_string` FnExpr, and the result schema types `t` as text

#### Scenario: A cast to a character type becomes to_string
- **WHEN** a client submits `SELECT CAST(total_tokens AS VARCHAR) AS t FROM dial_usage_log`
- **THEN** translation succeeds and the select entry is a `to_string` FnExpr over `total_tokens`

#### Scenario: The internal cast marker is not an inbound function name
- **WHEN** a client submits `SELECT TO_STRING_CAST(total_tokens) AS t FROM dial_usage_log`, the same call
  over a text column (`to_string_cast(project_id)`), or the call in a `WHERE`
- **THEN** translation fails before execution with an unsupported-function error naming the spelling —
  the marker is internal to the rewrite, and the callable spellings are `CAST(x AS VARCHAR)` and
  `TO_STRING(x)`

#### Scenario: A cast to a non-character type is still rejected
- **WHEN** a client submits `SELECT CAST(deployment AS INTEGER) FROM dial_usage_log`
- **THEN** translation fails before execution with an error naming the unsupported `CAST`

#### Scenario: A non-text operand of a text function is still rejected
- **WHEN** a client submits `SELECT not_empty(total_tokens) FROM dial_usage_log`
- **THEN** translation fails before execution naming the operand's type — the coercion the validator
  would inject is not a caller-written cast and SHALL NOT make the call succeed; the same statement
  written `not_empty(CAST(total_tokens AS VARCHAR))` is accepted

#### Scenario: A cast of text to text keeps its meaning
- **WHEN** a client submits `SELECT CAST(project_id AS VARCHAR) AS p FROM dial_usage_log`
- **THEN** translation succeeds with the **bare `project_id` field** aliased `p` — no `to_string`
  wrapper, which is the document that statement translated to before this change — and the executed
  statement returns the column's values unchanged, null cells included

#### Scenario: A cast of text to text in a predicate or a group key is still the bare column
- **WHEN** a client submits
  `SELECT event_kind FROM dial_usage_log WHERE CAST(project_id AS VARCHAR) = 'p1'` or
  `SELECT CAST(deployment AS VARCHAR) AS d, count(*) AS n FROM dial_usage_log GROUP BY d`
- **THEN** the filter compares the bare `project_id` field, and the group key is the bare `deployment`
  field — the positions where a wrapper would land on a physically-ordered, low-cardinality column

#### Scenario: A projection-only operand is rejected
- **WHEN** a client submits `SELECT CONCAT(execution_path, 'x') FROM dial_usage_log` or
  `SELECT TO_STRING(point_attributes) FROM dial_usage_log`
- **THEN** translation fails before execution with an error naming the field and the projection-only
  restriction

#### Scenario: A single-operand concat is rejected
- **WHEN** a client submits `SELECT CONCAT(deployment) FROM dial_usage_log`
- **THEN** translation fails before execution with an error naming the function's minimum arity

### Requirement: Array text rendering and slicing in the SQL subset

The subset SHALL accept `ARRAY_TO_STRING(array, delimiter)` and `ARRAY_SLICE(array, offset, length)`,
translating them to the DSL `array_to_string` and `array_slice` calls. Argument 0 of each SHALL take an
array under the **array-operand carve-out** — a declared `array` field or an array-returning call —
exactly as `ARRAY_LENGTH` and `ARRAY_JOIN` do, so that
`ARRAY_TO_STRING(GROUP_UNIQ_ARRAY(deployment), ', ')` and
`ARRAY_TO_STRING(ARRAY_SLICE(GROUP_UNIQ_ARRAY(deployment), 1, 6), ', ')` translate. `delimiter` SHALL
be a string literal; `offset` and `length` SHALL be integer literals.

`ARRAY_TO_STRING`'s result is text, so its result column SHALL be typed as text and SHALL carry no
projection-only restriction — it is legal as a `GROUP BY` key, an `ORDER BY` key and a predicate
operand. `ARRAY_SLICE`'s result is an **array**, so it SHALL be governed by "Array-returning scalar
functions are projection-only in SQL" like the other array-returning calls, and its result column
SHALL be typed as an array whose element kind is its own operand's element kind.

#### Scenario: Array rendered as text translates
- **WHEN** a client submits `SELECT chat_id, ARRAY_TO_STRING(GROUP_UNIQ_ARRAY(deployment), ', ') AS models FROM dial_usage_log GROUP BY chat_id`
- **THEN** translation succeeds, producing an aggregate-mode StructuredQuery whose select entry is an
  `array_to_string` call over the `group_uniq_array` call, and the result schema types `models` as text

#### Scenario: A slice caps an array's length
- **WHEN** a client submits `SELECT ARRAY_TO_STRING(ARRAY_SLICE(GROUP_UNIQ_ARRAY(deployment), 1, 6), ', ') AS models FROM dial_usage_log GROUP BY chat_id`
- **THEN** translation succeeds with the `array_slice` call nested in argument 0 of `array_to_string`

#### Scenario: A slice of a declared array column projects
- **WHEN** a client submits `SELECT ARRAY_SLICE(execution_path, 1, 3) AS head FROM dial_usage_log`
- **THEN** translation succeeds and the result schema types `head` as an array with element kind string

#### Scenario: A slice is projection-only
- **WHEN** a client groups or orders by the alias of an `ARRAY_SLICE` projection
- **THEN** translation fails with an error naming the function and the projection-only restriction, the
  same refusal a `MAP_KEYS` alias receives

#### Scenario: The rendered text of a slice is groupable
- **WHEN** a client submits `SELECT ARRAY_TO_STRING(execution_path, '>') AS path, count(*) FROM dial_usage_log GROUP BY path`
- **THEN** translation succeeds — `array_to_string`'s result is an ordinary text scalar

#### Scenario: A computed delimiter or bound is rejected
- **WHEN** a client submits `ARRAY_TO_STRING(execution_path, deployment)` or `ARRAY_SLICE(execution_path, 1, total_tokens)`
- **THEN** translation fails before execution with an error stating that the argument must be a literal

#### Scenario: An expansion of a slice types as the element
- **WHEN** a client submits `SELECT ARRAY_JOIN(ARRAY_SLICE(execution_path, 1, 3)) AS step FROM dial_usage_log`
- **THEN** translation succeeds and the result schema types `step` as a string — the slice's element
  kind, not an array

### Requirement: Set operations combine query blocks

The subset SHALL accept a statement that combines query blocks with a set operation: `UNION ALL`,
`UNION DISTINCT`, `INTERSECT`, `INTERSECT ALL`, `INTERSECT DISTINCT`, `EXCEPT`, `EXCEPT ALL` and
`EXCEPT DISTINCT`. A branch SHALL be any query block the subset accepts — a single entity with
`WHERE`/`GROUP BY`/`HAVING`/`DISTINCT`, a join, a derived table, a CTE reference, a block carrying its own
`ORDER BY` and `LIMIT`/`OFFSET`, or a nested set operation — and a chain MAY carry any number of branches.

**The governing rule for every semantic question below is that a statement ADAS accepts SHALL mean what
ClickHouse means by the same text.** Where the SQL standard and ClickHouse disagree, ClickHouse decides;
where ADAS cannot reproduce ClickHouse's reading, the statement SHALL be rejected rather than executed
with the other one. The consequences are stated individually because each is a place a standard-SQL
reading would silently differ.

**Unqualified operators take ClickHouse's default modes.** A bare `INTERSECT` SHALL mean
`INTERSECT ALL` and a bare `EXCEPT` SHALL mean `EXCEPT ALL`, which is what ClickHouse's
`intersect_default_mode` and `except_default_mode` defaults (`ALL`) make them — **not** the `DISTINCT` the
SQL standard and Calcite read them as. A bare `UNION` SHALL be **rejected**, naming `ALL` or `DISTINCT` as
the required keyword, because ClickHouse's `union_default_mode` default is empty and ClickHouse refuses the
statement itself. The statement ADAS executes SHALL always carry the `ALL` or `DISTINCT` keyword
explicitly, so a server whose default modes were reconfigured cannot change the result of a statement ADAS
accepted. The bare `UNION` rejection is the one behaviour tied to that default rather than to the
statement: against a server configured `union_default_mode = 'ALL'` or `'DISTINCT'`, ClickHouse would
accept what ADAS refuses.

**`MINUS` SHALL be rejected**, with an error naming `EXCEPT` as the spelling to use. ClickHouse has no
`MINUS` keyword and answers a syntax error, so accepting Oracle's synonym would succeed where the same
text fails against the database.

**A trailing `ORDER BY`, `LIMIT` or `OFFSET` written after the last branch SHALL apply to that last
branch alone**, as it does in ClickHouse, and **not** to the combined result as in standard SQL. So
`SELECT a FROM t1 UNION ALL SELECT a FROM t2 ORDER BY a LIMIT 10` returns every row of `t1` followed by
the first ten of `t2` in `a` order — the same rows, in the same order, that ClickHouse returns for that
text. The last branch is the chain's **final `SELECT`**, which precedence does not move: in
`<A> UNION ALL <B> INTERSECT ALL <C> ORDER BY a LIMIT 10` the clause SHALL bound `<C>` alone, before the
intersection with `<B>` is taken — and **not** the `<B> INTERSECT ALL <C>` operation that binds tighter,
which is where a parse tree's last operand would put it. A `WITH` clause in front of the statement SHALL
NOT change this: the clause still binds to the final `SELECT` of the body, not to the combined result.
A caller wanting the combined result ordered or truncated SHALL wrap the set operation in a query
block (`SELECT * FROM (<set operation>) ORDER BY a LIMIT 10`), which means the same thing on both. The
surface's own row governance is unaffected by this and continues to bound the combined result (see
[rest-sql-endpoint](../rest-sql-endpoint/spec.md) and
[flight-sql-endpoint](../flight-sql-endpoint/spec.md)): a statement whose trailing `LIMIT` binds to a
branch has stated no bound on the combined result, so the default limit applies to it exactly as it does
to a statement with no `LIMIT` at all.

A trailing `ORDER BY` written **without** a `LIMIT` or `OFFSET` beside it SHALL sort the last branch all
the same, and SHALL NOT be discarded as an ordering of an intermediate result:
`SELECT a FROM t1 UNION ALL SELECT a FROM t2 ORDER BY a` returns every row of `t1` followed by every row
of `t2` in `a` order, which is what ClickHouse returns for that text (measured on 26.3.24:
`SELECT 5 AS x UNION ALL SELECT arrayJoin([3,1,2]) AS x ORDER BY x` returns `5, 1, 2, 3`). This is the
clause's ordinary case rather than a corner of it — a caller who wants the whole of both branches in the
last branch's order writes exactly this — and discarding the collation would change the **rows** the
statement returns and not only their order, because the combined result is then bounded by the default
limit with nothing ordering the rows that bound selects. The same holds for a branch the caller
parenthesized around an `ORDER BY` of its own with no row bound
(`(SELECT a FROM t1 ORDER BY a) UNION ALL SELECT a FROM t2`), which is one of the accepted branch shapes
listed above.

**A trailing clause that follows a closing parenthesis SHALL be rejected.** ClickHouse's grammar attaches
the clause to an unparenthesized final `SELECT`, so `<A> UNION ALL (<B>) ORDER BY a`,
`(<A> UNION ALL <B>) ORDER BY a` and `<A> UNION ALL (<B> INTERSECT ALL <C>) ORDER BY a` are each a syntax
error there; ADAS SHALL refuse all three with a message naming the wrapped spelling that works, rather
than invent a scope for text that has none. A parenthesized branch that is **not** the last one SHALL NOT
be refused: the clause still follows a bare final `SELECT`, so
`(<A> ORDER BY a LIMIT 2) UNION ALL <B> ORDER BY a LIMIT 3` executes on ClickHouse — each clause bounding
its own branch — and SHALL execute here. Where the operation is itself nested in a `FROM` item or a CTE
body, the parenthesized **first** branch is the one exception, and it is a parser limitation rather than a
rule of this surface: see "A nested block cannot open with a parenthesis and carry a trailing clause"
under "Derived tables nest query blocks".

Multiplicity SHALL follow the resolved keyword: `ALL` concatenates (`UNION ALL`), keeps the minimum
per-row multiplicity of the two sides (`INTERSECT ALL`), or subtracts the right side's multiplicity from
the left's (`EXCEPT ALL`); `DISTINCT` deduplicates the result of the operation.

**Precedence** SHALL be: `INTERSECT` binds tighter than `UNION` and `EXCEPT`, which bind equally and
associate left to right. Parentheses around a branch SHALL override it. This is both ClickHouse's
precedence and standard SQL's, so a parenthesis-free chain needs no rewriting to mean the same thing.

**Branch compatibility.** Corresponding columns SHALL be matched by position, not by name; every branch
SHALL project the same number of columns; each result column SHALL be named after the **first** branch's
column as that branch is executed, whatever the later branches call theirs. Where the first branch's item
is an unnamed expression, that name SHALL be the one the surface gives the expression — the same base name
a single-entity statement would return for it — and SHALL NOT be a planner-internal placeholder, at the
top level or nested. A branch list whose column counts differ SHALL be rejected with 400 `bad_request`
naming the mismatch, and ClickHouse SHALL NOT be contacted. Corresponding column types SHALL be unified,
and a pair this surface will not unify SHALL be rejected the same way, with a message naming the column
(by name, or by position where the statement left it unnamed) and the two branch types — including the
text-against-number pair, which the validator unifies only by casting the number to text and which SHALL
be reported as the branch mismatch it is rather than as a cast the caller never wrote. This is a narrower
rule than ClickHouse's own supertype search, and the one place the surface is deliberately stricter than
the database: rejecting a statement ClickHouse would have accepted keeps the no-divergence rule intact,
while executing it under a different unification would break it.

**Governance SHALL apply per branch and once over the result.** Every branch SHALL be validated against
the catalog exactly as a single-entity statement is — unknown entities and columns rejected, a
`sensitive` column unresolvable for a caller without `TABLE_SENSITIVE` on the table that owns it,
enrichment columns addressable as the
branch's double-quoted flat identifiers, `final` deduplication and the projection-only restrictions on
`array`, `object` and `map` columns in force. A bare `*` in a branch SHALL expand to that branch's
source's wildcard fields (its non-heavy source columns), never to heavy or enrichment columns. The
default and maximum row limits (or the Flight cap) SHALL govern the **combined** result only; a branch's
own `LIMIT` — whether the caller wrote it inside the branch or after the last one — SHALL be executed as
written, neither defaulted nor capped. The whole statement SHALL run under one set of `query.budget.*`
ceilings.

**Nesting.** A set operation SHALL be accepted wherever a query block is: as the top-level statement, as
a CTE body, as a derived table in `FROM`, as a join operand, and as the operand of `IN`, `NOT IN`,
`EXISTS`, `NOT EXISTS` or a scalar subquery — wherever such an operand may be written, a join's `ON`
predicate and a `CASE` branch included. The rules above SHALL hold at every level, so a trailing
`ORDER BY` inside a parenthesized derived table binds to that derived table's last branch, and a bare
`UNION` inside a subquery in an `ON` predicate or a `CASE` is rejected exactly as one at the top level is.

**Boundaries.** A set operation whose branches read `INFORMATION_SCHEMA` relations, and one mixing a
catalog relation with a queryable entity, SHALL be rejected — the in-process catalog evaluator has no
set-operation support. `WITH RECURSIVE` SHALL stay rejected even though its body is a `UNION ALL`.
ClickHouse's `SELECT * EXCEPT (col)` column-exclusion syntax is not this operator and SHALL be rejected
before execution, without contacting ClickHouse. It is the one rejection in this requirement whose
message does **not** name the construct: the statement parses and fails later in validation, reported
against the star rather than against the exclusion list.

A set-operation statement is a **composite** statement: it executes on every SQL surface and has no
`StructuredQuery` document (see "Composite statements execute without a structured-query document").

#### Scenario: UNION ALL concatenates two entities
- **WHEN** a client submits `SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics`
- **THEN** the statement executes and returns one row per row of either branch, under one output column `model`

#### Scenario: Bare UNION is rejected as ClickHouse rejects it
- **WHEN** a client submits `SELECT model FROM dial_usage_log UNION SELECT model FROM rate_analytics`
- **THEN** translation fails with 400 `bad_request` stating that `UNION` requires `ALL` or `DISTINCT`, and
  ClickHouse is not contacted — the same outcome ClickHouse gives the statement under its default
  `union_default_mode`

#### Scenario: UNION DISTINCT deduplicates
- **WHEN** a client submits `SELECT model FROM dial_usage_log UNION DISTINCT SELECT model FROM rate_analytics`
- **THEN** each distinct `model` appears once, and the executed statement spells `UNION DISTINCT` so the
  server's default mode cannot change the result

#### Scenario: Bare INTERSECT means ALL, not DISTINCT
- **WHEN** a client submits a set operation whose two branches each yield the same value twice, combined with a bare `INTERSECT`
- **THEN** the value appears **twice** — ClickHouse's `intersect_default_mode` default, not the standard's
  `DISTINCT` reading, which would have returned it once

#### Scenario: Bare EXCEPT means ALL, not DISTINCT
- **WHEN** a client submits a set operation whose left branch yields a value three times and whose right branch yields it once, combined with a bare `EXCEPT`
- **THEN** the value appears **twice** — `except_default_mode`'s `ALL`, where the standard's `DISTINCT`
  reading would have returned it not at all

#### Scenario: Explicit DISTINCT is honoured on INTERSECT and EXCEPT
- **WHEN** the two statements above are written `INTERSECT DISTINCT` and `EXCEPT DISTINCT`
- **THEN** the value appears once and not at all respectively — the keyword the caller wrote decides, and
  is never overridden by the default the bare spelling would have taken

#### Scenario: MINUS is rejected
- **WHEN** a client submits `SELECT model FROM dial_usage_log MINUS SELECT model FROM rate_analytics`
- **THEN** translation fails with 400 `bad_request` naming `EXCEPT` as the supported spelling, and
  ClickHouse is not contacted — ClickHouse has no `MINUS` keyword

#### Scenario: Trailing ORDER BY and LIMIT bind to the last branch
- **WHEN** a client submits `SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics ORDER BY model LIMIT 10`
- **THEN** the result is every `dial_usage_log` row followed by the first ten `rate_analytics` rows in
  `model` order — byte-for-byte what ClickHouse returns for that text — and **not** the first ten rows of
  the combined result

#### Scenario: A trailing ORDER BY with no row bound still sorts the last branch
- **WHEN** a client submits `SELECT a FROM t1 UNION ALL SELECT a FROM t2 ORDER BY a DESC`
- **THEN** the result is every row of `t1` followed by every row of `t2` in descending `a` order, as
  ClickHouse returns for that text — the collation is **not** discarded for want of a `LIMIT` beside it

#### Scenario: A trailing clause binds to the final SELECT of a mixed-precedence chain
- **WHEN** a client submits `<A> UNION ALL <B> INTERSECT ALL <C> ORDER BY a LIMIT 2`, where `<C>`'s first two rows in `a` order have no row in common with `<B>`
- **THEN** the result is `<A>`'s rows alone, because the clause bounded `<C>` and the intersection with
  `<B>` is then empty — what ClickHouse returns for that text, and not the rows that binding the clause to
  `<B> INTERSECT ALL <C>` would give

#### Scenario: A trailing clause under a WITH still binds to the last branch
- **WHEN** a client submits `WITH c AS (…) SELECT a FROM c WHERE … UNION ALL SELECT a FROM c ORDER BY a LIMIT 1`
- **THEN** the result is every row of the first branch followed by the single ordered row of the last, as
  ClickHouse returns for that text, and the statement states no bound on the combined result

#### Scenario: A trailing clause after a closing parenthesis is rejected
- **WHEN** a client submits `<A> UNION ALL (<B>) ORDER BY a`, `(<A> UNION ALL <B>) ORDER BY a` or `<A> UNION ALL (<B> INTERSECT ALL <C>) ORDER BY a`
- **THEN** each fails with 400 `bad_request` naming the last-branch reading and the
  `SELECT … FROM (<set operation>) ORDER BY …` spelling, and ClickHouse is not contacted — it answers a
  syntax error for all three

#### Scenario: A parenthesized branch before the last one keeps the clause on the last branch
- **WHEN** a client submits `(<A> ORDER BY a LIMIT 2) UNION ALL <B> ORDER BY a LIMIT 3`
- **THEN** the statement executes: `<A>` keeps its own two ordered rows and `<B>` its own three, exactly
  as ClickHouse executes that text

#### Scenario: A bare UNION in an ON predicate or a CASE is rejected
- **WHEN** a client submits a statement whose join `ON` predicate, or whose `CASE … WHEN`, carries a subquery combining branches with a bare `UNION`
- **THEN** translation fails with 400 `bad_request` stating that `UNION` requires `ALL` or `DISTINCT`, and
  ClickHouse is not contacted — it refuses a bare `UNION` in those positions as in any other

#### Scenario: Wrapping the set operation orders the combined result
- **WHEN** a client submits `SELECT model FROM (SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics) ORDER BY model LIMIT 10`
- **THEN** the ten rows are the first ten of the combined result in `model` order — the spelling that
  means the same thing to ADAS and to ClickHouse

#### Scenario: A trailing LIMIT does not bound the combined result
- **WHEN** a client submits a two-branch `UNION ALL` whose trailing `LIMIT 10` binds to the last branch, over REST
- **THEN** the combined result is bounded by the surface's default limit of 100, because the statement
  stated no bound on it — and the branch's own ten-row limit is executed as written

#### Scenario: Output names come from the first branch
- **WHEN** a client submits `SELECT model AS m FROM dial_usage_log UNION ALL SELECT model AS other FROM rate_analytics`
- **THEN** the single result column is named `m`

#### Scenario: Branch column types that do not unify are rejected
- **WHEN** a client submits a set operation whose corresponding columns this surface will not unify to a common type — `SELECT event_kind AS v FROM dial_usage_log UNION ALL SELECT total_tokens AS v FROM dial_usage_log` among them
- **THEN** translation fails with 400 `bad_request` naming the column and the incompatible types (`column
  'v'`, `string vs int64`), with no mention of a `CAST` the statement does not contain, and ClickHouse is
  not contacted, even where ClickHouse would have found a supertype of its own

#### Scenario: A nested set operation over an unnamed expression executes
- **WHEN** a client submits `SELECT * FROM (SELECT count(*) FROM dial_usage_log UNION ALL SELECT count(*) FROM dial_usage_log) t`, or the same operation as a CTE body or a subquery operand
- **THEN** the statement executes and its column is named after the expression (`count`), because the
  block above the operation references the name the branches themselves expose — the planner's own
  placeholder for an unnamed item reaches neither the database nor the response

#### Scenario: A parenthesized branch keeps its own ORDER BY and LIMIT
- **WHEN** a client submits `(SELECT model FROM dial_usage_log ORDER BY model LIMIT 5) UNION ALL (SELECT model FROM rate_analytics ORDER BY model LIMIT 5)`
- **THEN** each branch is truncated to its own five rows and the result carries up to ten rows; neither branch limit is capped or defaulted

#### Scenario: INTERSECT binds tighter than UNION
- **WHEN** a client submits `<A> UNION ALL <B> INTERSECT ALL <C>`
- **THEN** the result is `<A>` concatenated with the intersection of `<B>` and `<C>`, and the parenthesized form `(<A> UNION ALL <B>) INTERSECT ALL <C>` returns the other reading

#### Scenario: A set operation nests as a derived table
- **WHEN** a client submits `SELECT count(*) AS n FROM (SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics) t`
- **THEN** the statement executes and returns the combined row count

#### Scenario: A set operation nests as a CTE body and as a subquery
- **WHEN** a client submits a statement whose CTE body is a `UNION ALL`, or whose `IN (…)` operand is a `UNION DISTINCT`
- **THEN** both execute under the same governance as the top-level form

#### Scenario: Sensitive columns stay hidden in every branch
- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table references a `sensitive` column
  in any branch
- **THEN** translation fails as for an unknown column, exactly as it does for a single-entity statement

#### Scenario: Star in a branch omits heavy columns
- **WHEN** a client submits `SELECT * FROM rate_analytics UNION ALL SELECT * FROM rate_analytics`
- **THEN** each branch expands to the entity's non-heavy source columns, with no heavy or enrichment column among them

#### Scenario: A set operation over catalog metadata is rejected
- **WHEN** a client submits `SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES UNION ALL SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES`, or a statement combining a catalog relation with a queryable entity
- **THEN** translation fails with 400 `bad_request` naming the unsupported construct, and neither ClickHouse nor the evaluator produces rows

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

### Requirement: FINAL table modifier

The SQL surface SHALL accept ClickHouse's `FINAL` table modifier on an entity reference, and an entity
reference carrying it SHALL be read final while one without it is read raw (see
[structured-query](../structured-query/spec.md) "Read result deduplication" for what each reads, the
`query.force-final` migration flag, and the partition-local setting). The rule SHALL hold on every surface
that shares the SQL front end: `POST /v1/queries/execute-sql`, `POST /v1/queries/execute-sql-csv`,
`POST /v1/queries/translate-sql` and the Flight SQL / ADBC endpoint.

**Placement.** `FINAL` SHALL be accepted, case-insensitively, directly after an entity reference in a
`FROM` list (after `FROM` or a comma, including a comma that follows a join's `ON`/`USING` condition, as in
`FROM a JOIN b ON a.k = b.k, c FINAL`) or a `JOIN` — bare, double-quoted, or schema-qualified — and after
that reference's alias when it has one (`FROM dial_usage_log FINAL`, `FROM dial_usage_log AS l FINAL`,
`FROM dial_usage_log l FINAL`, `JOIN rate_analytics r FINAL ON …`). These are the placements ClickHouse
accepts (measured on 26.3 and 26.8); it refuses `FINAL` between a reference and its alias (`FROM t FINAL AS a`,
`FROM t FINAL a`), and so does this surface, with the parse failure it reports today (HTTP 400 `bad_request`). It SHALL be accepted wherever an entity reference may appear: the outer statement, a joined
entity, a derived table's body, a CTE body, a subquery and each branch of a set operation.

**Scope.** `FINAL` marks the one entity reference it follows. In a composite statement each reference SHALL
be read final or raw independently, so `FROM dial_usage_log l FINAL JOIN rate_analytics r ON …` reads
`dial_usage_log` final and `rate_analytics` raw, and the same entity referenced twice MAY be read final in
one reference and raw in the other. A final-marked reference SHALL deduplicate every table of its entity
that collapses versions — the source when it is `upsert_by_key`, and every enrichment the statement joins
for that reference.

That independence SHALL hold on every server in the supported range and SHALL NOT rest on how the server
scopes the keyword itself. ClickHouse's own scoping changed inside the range: through 26.5 a `FINAL` on the
`FROM` table also deduplicated the tables joined after it at the same level (measured on 26.3:
`FROM a FINAL JOIN b` collapses `b` too; `FROM a JOIN b FINAL` does not touch `a`), and from 26.6
(`analyzer_compatibility_apply_final_to_all_joined_tables` defaulting to `0`) the keyword reaches only the
reference it follows (measured on 26.8: `FROM a FINAL JOIN b` leaves `b` raw). The service SHALL therefore
place `FINAL` on each collapsing table of a reference itself — on the source and inside each enrichment
join — and SHALL keep each entity reference of a composite statement in its own scope, so that a `FINAL`
on one reference cannot spill onto another whichever way the server scopes the keyword. A `compatibility`
profile naming a version below 26.6 restores the server's older scoping and SHALL NOT change what the
surface reads.

**What is not a modifier.** `FINAL` SHALL be recognised only in the positions above. The word inside a
string literal (`'FINAL'`), inside a comment, as a double-quoted identifier (`"FINAL"`, which is an alias or
a name), or as a column or alias name in any other position SHALL keep meaning what it means today. A
`FINAL` in any other position — after a `WHERE`, `GROUP BY` or `ORDER BY` clause, in a select list — SHALL
be rejected with the parse failure the surface reports today (HTTP 400 `bad_request`).

**Rejections.**

- `FINAL` after an entity that is not final-capable — an `append` source with no enrichment — SHALL be
  rejected with HTTP 400, `error` = `illegal_final` (see the structured-query requirement), whether or not
  `query.force-final` is on.
- `FINAL` after a derived table, after a reference to a CTE name, or after a catalog metadata table
  (`INFORMATION_SCHEMA`) SHALL be rejected with HTTP 400 `bad_request` and a message stating that `FINAL`
  applies to an entity, not to a derived table, CTE or catalog table; the modifier belongs inside the body,
  on the entity it reads.

A single-block statement with `FINAL` translates to a structured query with `"final": true`, and one
without translates to `"final": false` (see
[query-translation-endpoint](../query-translation-endpoint/spec.md) "Translation carries FINAL").

#### Scenario: FINAL after an entity reads it final

- **WHEN** `query.force-final` is off and a client executes
  `SELECT trace_id FROM dial_usage_log FINAL WHERE trace_id = 'x'` over a key with two unmerged versions
- **THEN** the response holds one row for the key

#### Scenario: No FINAL reads the entity raw

- **WHEN** `query.force-final` is off and a client executes `SELECT trace_id FROM dial_usage_log WHERE
  trace_id = 'x'` over a key with two unmerged versions
- **THEN** the response holds two rows, as ClickHouse returns for the same statement

#### Scenario: FINAL after an alias

- **WHEN** a client executes `SELECT l.trace_id FROM dial_usage_log AS l FINAL`
- **THEN** the statement is accepted and `dial_usage_log` is read final

#### Scenario: FINAL between a reference and its alias is a parse failure

- **WHEN** a client executes `SELECT l.trace_id FROM dial_usage_log FINAL AS l`
- **THEN** the request is rejected with HTTP 400 `bad_request` as an unparseable statement, as ClickHouse
  refuses it

#### Scenario: FINAL is case-insensitive and follows a quoted or qualified name

- **WHEN** a client executes `SELECT trace_id FROM "dial_usage_log" final`, or the same with the entity
  schema-qualified
- **THEN** the statement is accepted and the entity is read final

#### Scenario: FINAL marks one reference of a join

- **WHEN** `query.force-final` is off and a client executes a join of `dial_usage_log l FINAL` with
  `rate_analytics r` on `l.response_id = r.response_id`
- **THEN** `dial_usage_log` and the enrichments joined for it are read final and `rate_analytics` is read
  raw

#### Scenario: FINAL in a set-operation branch marks that branch only

- **WHEN** `query.force-final` is off and a client executes
  `SELECT model FROM dial_usage_log FINAL UNION ALL SELECT model FROM dial_usage_log`
- **THEN** the first branch reads `dial_usage_log` final and the second reads it raw

#### Scenario: FINAL inside a CTE or derived table body

- **WHEN** a client executes `WITH t AS (SELECT trace_id FROM dial_usage_log FINAL) SELECT count(*) FROM t`
- **THEN** the statement is accepted and the CTE body reads `dial_usage_log` final

#### Scenario: FINAL after a CTE name or derived table is rejected

- **WHEN** a client executes `WITH t AS (SELECT trace_id FROM dial_usage_log) SELECT * FROM t FINAL`, or
  `SELECT * FROM (SELECT trace_id FROM dial_usage_log) FINAL`
- **THEN** the request is rejected with HTTP 400 `bad_request` stating that `FINAL` applies to an entity,
  and no statement is executed

#### Scenario: FINAL after an append entity is rejected

- **WHEN** a client executes `SELECT * FROM <append_source> FINAL` over an `append` source with no
  enrichment, with `query.force-final` on or off
- **THEN** the request is rejected with HTTP 400, `error` = `illegal_final`, naming the entity

#### Scenario: The word FINAL elsewhere is not a modifier

- **WHEN** a client executes `SELECT 'FINAL' AS "final" FROM dial_usage_log -- FINAL` with
  `query.force-final` off
- **THEN** the statement is accepted, the literal and the alias keep their meaning, and `dial_usage_log`
  is read raw

#### Scenario: FINAL in a clause position is a parse failure

- **WHEN** a client executes `SELECT trace_id FROM dial_usage_log WHERE trace_id = 'x' FINAL`
- **THEN** the request is rejected with HTTP 400 `bad_request` as an unparseable statement

#### Scenario: Flight SQL honours FINAL

- **WHEN** `query.force-final` is off and a Flight SQL client executes
  `SELECT trace_id FROM dial_usage_log FINAL WHERE trace_id = 'x'` over a key with two unmerged versions
- **THEN** the stream carries one row for the key

### Requirement: The clock has one DSL name and three SQL spellings

The SQL subset SHALL accept `now()`, `CURRENT_TIMESTAMP` and `LOCALTIMESTAMP` — the last two with or
without empty parentheses, as the grammar spells them — and SHALL lower all three to the single closed
catalog function `now` (see [structured-query](../structured-query/spec.md), "Relative time
expressions") in the resulting `StructuredQuery`. No new expression kind SHALL be introduced: the DSL
carries the clock as an ordinary zero-argument `fn` expression.

Three inbound spellings exist because three populations write them and none should have to learn the
others': `now()` is what ClickHouse and PostgreSQL users write, `CURRENT_TIMESTAMP` is what standard
SQL and most BI clients emit, and `LOCALTIMESTAMP` is what a client emits when it means "without a
zone" — which, on a surface whose one zone is UTC, is the same instant. They SHALL therefore produce
the **same** document, not three documents that behave alike.

Sharing a name SHALL NOT merge evaluations. Each occurrence of any of the three spellings SHALL lower
to its own `now` call, and each call is one call site evaluated on its own, with the semantics
"Relative time expressions" defines: one value across every row of the statement, independent of any
other call site. `now()` and `CURRENT_TIMESTAMP` in one statement are therefore two call sites that MAY
differ by a clock tick, exactly as two `now()` calls MAY — and exactly as the same statement does in
ClickHouse.

`CURRENT_DATE` SHALL remain rejected. It is not a spelling of the clock but a truncation of it, and
lowering it silently would answer a date-typed request with a timestamp; the subset's own
`date_trunc('day', now())` says it exactly.

The DSL → SQL renderer SHALL emit `now()` and never a second spelling, so a rendered statement reads
back to the same document it was rendered from.

#### Scenario: The three clock spellings produce one document

- **WHEN** a client submits `SELECT event_id FROM dial_usage_log WHERE request_time >= now()`, the
  same statement with `CURRENT_TIMESTAMP`, and the same with `LOCALTIMESTAMP`
- **THEN** all three translate successfully to the same `StructuredQuery`, whose filter compares
  `request_time` against a zero-argument `now` function expression

#### Scenario: Two spellings in one statement stay two calls

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 1 HOUR AND request_time < CURRENT_TIMESTAMP`
- **THEN** the resulting `StructuredQuery` carries two separate zero-argument `now` calls, one per
  bound, and no single shared instant

#### Scenario: The clock round-trips through the renderer

- **WHEN** a DSL document whose filter calls `now()` is rendered to SQL and the rendered statement is
  translated back
- **THEN** the rendered statement spells the call `now()` and the resulting document equals the
  original

#### Scenario: CURRENT_DATE stays rejected

- **WHEN** a client submits a statement using `CURRENT_DATE`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

### Requirement: Interval arithmetic translates to the offset functions

The SQL subset SHALL accept `<timestamp expression> + INTERVAL <amount> <unit>` and
`<timestamp expression> - INTERVAL <amount> <unit>`, and SHALL lower them to the closed-catalog
functions `date_add` and `date_sub` respectively. It SHALL additionally accept `date_add(unit, amount,
timestamp)` and `date_sub(unit, amount, timestamp)` as ordinary function calls, whose `unit` is a
string literal — so the two spellings a ClickHouse user already has both work and land on the same
document.

Recognition of the interval form SHALL take precedence over the numeric lowering of `+` and `-` (see
"Arithmetic operators translate to catalog functions"): those operators lower to `plus`/`minus` when
both operands are numeric, and to `date_add`/`date_sub` when the right operand is an interval.

**Both interval spellings SHALL be accepted.** The engine-native `INTERVAL 2 HOUR` and the standard
`INTERVAL '2' HOUR` SHALL produce the same document. An interval written with a leading sign SHALL be
normalized by flipping the operator, so `+ INTERVAL -2 HOUR` and `- INTERVAL 2 HOUR` both yield
`date_sub('hour', 2, …)`: the DSL carries direction in the function name, so there is exactly one
document for one meaning and the round trip is single-valued.

**The unit's case SHALL NOT distinguish two documents.** The operator form carries the unit as a SQL
keyword and the function form as a string literal, so `date_add('HOUR', 2, now())` and
`now() + INTERVAL 2 HOUR` are the same query written two ways; the function form's unit literal SHALL
therefore be canonicalised to the catalog's lower-case spelling. Without it the DSL → SQL → DSL cycle
would not settle, because the renderer emits the keyword upper case while the reader lowers it.

**Units SHALL normalize onto the catalog's six.** `SECOND`, `MINUTE`, `HOUR`, `DAY`, `MONTH` and
`YEAR` map to the identically named unit. `WEEK` SHALL normalize to `7 day` and `QUARTER` to
`3 month` — both are exact identities for an offset, so nothing is lost, and the DSL is spared two
units that would each have a second spelling. A client writing `INTERVAL 1 WEEK` SHALL therefore read
back `date_sub('day', 7, …)`, which is the same query; the normalization is stated here so that
result is not read as a translation defect.

**What SHALL be rejected**, each with HTTP 400 and `error` `bad_request`:

- a **multi-field** interval qualifier (`INTERVAL '1-2' YEAR TO MONTH`, `INTERVAL '2:30' HOUR TO
  MINUTE`) — it names two offsets at once, which no single catalog call carries, and silently keeping
  one field would answer a different question than was asked;
- an interval whose amount is **not a whole number** of its own unit (`INTERVAL 2.5 HOUR`) or is
  **zero**;
- an interval operand that is not a literal, including one computed from a column;
- an interval as an operand of anything else — `*`, `/`, a comparison, an aggregate, or a projected
  select item. An interval is not a value this surface has a type for; only the two datetime
  operators consume one.

**Subtracting one timestamp from another SHALL be rejected**, in every spelling. A bare
`ts1 - ts2` is already refused by type checking, but the standard datetime-difference form
`(ts1 - ts2) HOUR` is not: it currently translates to the numeric `minus` of the two operands, which
renders as a difference in **seconds** and so answers a question about hours with a number of
seconds. The interval qualifier SHALL therefore be honoured by rejecting the form, since the subset
has no interval result type to carry it — a caller wanting an elapsed count gets a correct refusal
instead of a silently mis-scaled number.

The DSL → SQL renderer SHALL emit the **infix interval form** for `date_add`/`date_sub` (for example
`(request_time - INTERVAL 2 HOUR)`), not the function form: that is the portable spelling for the
external BI clients the rendered SQL is written for, and it reads back to the same document because
this requirement lowers it onto the same two functions.

#### Scenario: The last two hours, written the engine's way

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 2 HOUR`
- **THEN** translation succeeds, producing a filter comparing `request_time` against
  `date_sub('hour', 2, now())`

#### Scenario: Quoted and unquoted intervals agree

- **WHEN** a client submits the previous statement with `INTERVAL '2' HOUR`
- **THEN** it produces the identical `StructuredQuery`

#### Scenario: The function spelling agrees with the operator spelling

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= date_sub('hour', 2, now())`
- **THEN** it produces the same `StructuredQuery` as the `- INTERVAL 2 HOUR` form

#### Scenario: An upper-case unit literal is the same document, and round-trips

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= date_add('HOUR', 2, now())`
- **THEN** the document carries `date_add('hour', 2, now())`, and rendering it back to SQL
  (`(now() + INTERVAL 2 HOUR)`) and translating that again yields the identical document

#### Scenario: A signed interval normalizes to one document

- **WHEN** a client submits `... WHERE request_time >= now() + INTERVAL -2 HOUR`
- **THEN** translation succeeds and the document carries `date_sub('hour', 2, now())`, identical to the
  subtraction form's

#### Scenario: Week and quarter normalize to exact equivalents

- **WHEN** a client submits `... WHERE request_time >= now() - INTERVAL 1 WEEK` and
  `... WHERE request_time >= now() - INTERVAL 1 QUARTER`
- **THEN** the documents carry `date_sub('day', 7, now())` and `date_sub('month', 3, now())`
  respectively

#### Scenario: Interval arithmetic over a column and in projection

- **WHEN** a client submits `SELECT request_time + INTERVAL 1 DAY AS due FROM dial_usage_log`
- **THEN** translation succeeds, producing `date_add('day', 1, request_time)` aliased `due`

#### Scenario: A compound interval qualifier is rejected

- **WHEN** a client submits `... WHERE request_time >= now() - INTERVAL '1-2' YEAR TO MONTH`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`, naming the multi-field qualifier

#### Scenario: A fractional or zero interval is rejected

- **WHEN** a client submits `... - INTERVAL 2.5 HOUR` or `... - INTERVAL 0 HOUR`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: A computed interval is rejected

- **WHEN** a client submits a statement whose interval amount is a column reference
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: An interval reaching a non-datetime operator is rejected

- **WHEN** a client submits `SELECT INTERVAL 2 HOUR FROM dial_usage_log`, or a statement multiplying
  an interval by a number
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: Interval arithmetic round-trips through the renderer

- **WHEN** a DSL document carrying `date_sub('hour', 2, now())` is rendered to SQL and translated back
- **THEN** the rendered statement spells the offset as `- INTERVAL 2 HOUR` and the resulting document
  equals the original

#### Scenario: A timestamp difference stays out of the subset

- **WHEN** a client submits `SELECT request_time - _ingested_at AS d FROM dial_usage_log`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: A qualified timestamp difference is rejected rather than mis-scaled

- **WHEN** a client submits `SELECT (request_time - _ingested_at) HOUR AS d FROM dial_usage_log`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`, rather than succeeding with a
  difference expressed in seconds

## Implementation notes

- Front end and translator: `com.epam.aidial.analytics.service.query.sql` — `SqlQueryFrontEnd`
  (screen → Babel parse → validate → RelNode; validator conformance BABEL, no optimizer rules),
  `CalciteQueryCatalog` (per-request schema snapshot from `CatalogQueryService`),
  `RelToStructuredQueryTranslator` (RelNode-stack walker), `RexToExprTranslator` (Rex → Expr,
  cast tolerance, LIKE/ILIKE rule (case-sensitive co/nc vs case-insensitive ico/inc), literal retyping against declared field types — e.g. string→uuid,
  integer→long), `SargExpander` (SEARCH/Sarg → eq/in/range conjunctions), `SqlQueryOperators`
  (custom `date_bin`/`width_bucket` operator signatures chained before the std table).
- Calcite realities handled: select items equal to a group key arrive as `ANY_VALUE(<key>)`
  aggregate calls and are unwrapped; `SELECT DISTINCT` lowers to a full-width group-by with no
  aggregate calls and maps back to `distinct=true`; global aggregates emit `group_by = null`.
- Result schema derivation (`SqlTranslation.columns()`/`ResultColumnKind`) follows ClickHouse
  runtime semantics (`count`/`length`/`width_bucket` → INT64, `avg`/`percentile_*` → FLOAT64),
  not Calcite's validated types.
- Errors surface as `SqlTranslationException` (mapped to gRPC INVALID_ARGUMENT).
- Coverage: `service/query/sql/SqlQueryFrontEndTest` (offline) plus the Flight
  integration suite against real ClickHouse.
