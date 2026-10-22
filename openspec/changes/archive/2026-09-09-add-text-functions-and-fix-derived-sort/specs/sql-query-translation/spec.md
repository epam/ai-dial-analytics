## ADDED Requirements

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
accepted function surface therefore stays exactly what `GET /v1/queries/functions` advertises, plus the
`CAST(<expr> AS <character type>)` and `||` spellings named in this requirement.

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
  date_bin, date_trunc, if, not_empty, starts_with, contains, equals, concat, to_string,
  array_to_string, array_slice, json_extract_string,
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
- **THEN** translation now **succeeds**: neither name is a catalog function, but the validator lowers both onto
  `CASE` — `CASE WHEN chat_id IS NOT NULL THEN chat_id ELSE trace_id END` and `CASE WHEN chat_id = '' THEN
  NULL ELSE chat_id END` — which the `case` expression carries (see "CASE expressions translate to the DSL
  case expression"). This supersedes the earlier rejection; the scenario keeps its historical title so the
  archive can match it

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
(non-exhaustively): multi-statement input; non-`SELECT` statements; set operations (`UNION`, `UNION ALL`,
`INTERSECT`, `EXCEPT`/`MINUS`), at the top level, inside a CTE body or inside a subquery alike;
`WITH RECURSIVE`; correlated subqueries; `LATERAL`/`APPLY`; quantified comparisons (`= SOME`, `> ALL`,
`UNIQUE`); ROLLUP/CUBE/GROUPING SETS; explicit `CAST` other than the identity/widening,
literal-to-timestamp and to-character forms the translator tolerates; `BETWEEN` (unless expressible — see range expansion
below); `FILTER (WHERE …)` on aggregates; functions outside the DSL catalog, including window functions
other than the ranking functions and aggregates the "Window functions" requirement names; a join whose
`ON` carries no equality between its sides; `IN` with non-literal, non-subquery elements. Joins, CTEs,
derived tables, uncorrelated subqueries, `CASE`, arithmetic operators and window functions are **no
longer** rejected — each is governed by its own requirement above. Range predicates that Calcite lowers
into `SEARCH`/Sarg form SHALL be expanded back into supported comparison conjunctions or `in` lists where
the Sarg consists of point values and/or simple bounds; Sargs that cannot be expanded losslessly SHALL be
rejected.

#### Scenario: Set operation rejected
- **WHEN** a client submits `SELECT event_id FROM dial_usage_log UNION ALL SELECT response_id FROM rate_analytics`
- **THEN** translation fails with an error naming the set operation and ClickHouse is not contacted

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
aliased) **or as the first argument of an `ARRAY_HAS`/`ARRAY_HAS_ANY`/`ARRAY_HAS_ALL`/`ARRAY_LENGTH`/
`ARRAY_JOIN`/`ARRAY_TO_STRING`/`ARRAY_SLICE` call** — the array-operand functions. Any other use SHALL
be rejected at translation time — before any ClickHouse contact —
with an error naming the array field and stating that array fields can only be projected.
Rejected uses include: as an operand of any predicate or comparison (including `IS NULL` /
`IS NOT NULL` and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under `SELECT DISTINCT`
(row-mode distinct deduplicates over the projected array), and as an argument to any scalar or
aggregate function other than the seven named above. The resulting `StructuredQuery` SHALL carry the
array field as an ordinary field projection, and the derived result schema SHALL type the column as an
array of its catalog `element_type`.

The carve-out SHALL be **positional and function-specific**, matching the treatment `object` fields
receive for `JSON_EXTRACT_*`: only argument 0, only those seven functions. An array field supplied as
`ARRAY_HAS`'s *second* argument SHALL be rejected under the rule above. Five of the seven answer with a
scalar — `ARRAY_HAS`/`ARRAY_HAS_ANY`/`ARRAY_HAS_ALL` a boolean, `ARRAY_LENGTH` an integer,
`ARRAY_TO_STRING` text — so their results SHALL carry no projection-only restriction and
SHALL be legal in `WHERE`, `GROUP BY`, `ORDER BY`, `HAVING` and as an `IF` condition. `ARRAY_JOIN`
likewise returns a scalar — the array's element type — and is row-expanding; its contract is stated by
"ARRAY_JOIN expands an array into rows on the SQL surface". `ARRAY_SLICE` is the one member whose own
result is an array, and it therefore carries the projection-only restriction of the array-returning
calls in its turn.

Testing, rendering, slicing and expanding are the whole of the carve-out. An `array` field SHALL remain
rejected as a `GROUP BY` key: only a scalar result — `ARRAY_JOIN`'s element or `ARRAY_TO_STRING`'s text
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

A sort key that is a **plain column** SHALL keep translating as a single block, exactly as before: the
DSL resolves such a key against the source's columns, so `SELECT event_id FROM dial_usage_log ORDER BY
request_time DESC LIMIT 5` SHALL still yield a single-block `StructuredQuery` rather than a nested one.

Because a nested statement has no single-block document, `POST /v1/queries/translate-sql` SHALL report
that the statement has no structured-query equivalent — the refusal every composite statement gets —
rather than the `ORDER BY` message. It SHALL still execute through `POST /v1/queries/execute-sql`,
Flight SQL, and a saved query.

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

### Requirement: Composite statements execute without a structured-query document

The translator SHALL produce an executable plan for a **composite statement** — a statement whose plan needs
more than one query block over one entity, because it joins, reads a derived table or CTE, or carries a
subquery. That plan's leaves SHALL be single-entity blocks and its composition SHALL mirror the statement; the
plan SHALL execute through the same pipeline as a single-entity statement (same read-only client, `final`
deduplication, `query.budget.*` settings and row governance on the outermost block) and SHALL yield the same
response envelope and result-column descriptors. The composite plan has no `StructuredQuery` equivalent:
`POST /v1/queries/translate-sql` SHALL reject it with 400 `bad_request` and the message
`statements with joins, derived tables or subqueries — including a statement nested to carry an ORDER BY expression its SELECT list does not project — execute but have no structured-query equivalent`.
That message names the nesting the **translator** performs as well as the nesting a client writes, because a
statement whose `SELECT` list does not carry its own computed `ORDER BY` expression is nested by the
translator (see "Derived tables nest query blocks") and would otherwise be told it has a derived table it
never wrote. A statement that is a single block over one entity — including one that uses `CASE`, arithmetic
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
