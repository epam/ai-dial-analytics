## ADDED Requirements

### Requirement: Joins compose queryable entities

A statement MAY join two or more queryable entities with `INNER JOIN`, `LEFT [OUTER] JOIN`,
`RIGHT [OUTER] JOIN`, `FULL [OUTER] JOIN`, `CROSS JOIN`, or a comma-separated `FROM` list. Each join
operand SHALL be a queryable entity (optionally aliased), a derived table, or a CTE reference; the same
entity MAY appear more than once under distinct aliases (a self-join). Every operand SHALL be validated
against the catalog exactly as a single-entity statement is: unknown entities and unknown columns are
rejected, a `sensitive` column stays unresolvable for a caller without `FULL_ADMIN` in every operand, and
an operand's enrichment columns SHALL be addressable as its double-quoted flat identifiers qualified by
the operand's alias (e.g. `a."deployment_ref.display_name"`).

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
- **WHEN** a caller without `FULL_ADMIN` references a `sensitive` column of either join operand
- **THEN** translation fails as for an unknown column, exactly as it does for a single-entity statement

### Requirement: Common table expressions are inlined

A statement MAY begin with a `WITH` clause declaring one or more named, non-recursive common table
expressions whose body is a `SELECT` (with or without `ORDER BY`/`LIMIT`). A CTE MAY reference a CTE
declared before it. Each reference to a CTE — in `FROM`, as a join operand, or inside a subquery — SHALL be
treated exactly as the equivalent derived table written in place; a CTE referenced twice is evaluated as
two derived tables. `WITH RECURSIVE` SHALL be rejected at translation time with an error naming it, and a
CTE whose body is a set operation SHALL be rejected as a set operation. A trailing `ORDER BY` on the
outer statement SHALL not change any of this: `WITH … SELECT … ORDER BY x` is the same statement as
`WITH … SELECT …` plus a sort, so star expansion, output naming and the `WITH RECURSIVE` rejection SHALL
apply to it identically.

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

A `FROM` item MAY be a parenthesised `SELECT` of any accepted shape, with or without an alias. The inner
block MAY carry `WHERE`, `GROUP BY`/`HAVING`, `DISTINCT`, `ORDER BY` and `LIMIT`/`OFFSET`, and the outer
block MAY filter, aggregate, sort and page over its columns; nesting depth is not limited by the
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

### Requirement: Uncorrelated subqueries in predicates and expressions

A `WHERE` or `HAVING` predicate MAY be `<expr> [NOT] IN (<subquery>)`, `(<expr>, <expr>) [NOT] IN
(<subquery>)` with a matching column count, or `[NOT] EXISTS (<subquery>)`. An expression MAY be a
scalar subquery — a parenthesised `SELECT` producing exactly one column — as a comparison operand or as a
select item; it SHALL NOT be an argument of a function call. The subquery SHALL be any accepted statement,
including one that itself joins or nests, and SHALL be validated and governed like any block (no default
limit applies inside it).

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

Searched `CASE WHEN <predicate> THEN <expr> [WHEN …] [ELSE <expr>] END` and simple `CASE <expr> WHEN
<value> THEN <expr> … END` SHALL be accepted wherever an expression is, and SHALL translate to the DSL
`case` expression kind (see [structured-query](../structured-query/spec.md), "Conditional case
expression"). Each `WHEN` predicate SHALL be a predicate of the filter grammar — a comparison, `IS [NOT]
NULL`, `IN`, `LIKE` in its contains form, a boolean function call, or a boolean combination of those — so,
unlike `if`, a comparison IS a legal condition here. `THEN`/`ELSE` branches SHALL unify under the same
rules as `if`'s branches (numeric types widen, any other mismatch is rejected, a non-scalar branch is
rejected); branches of different type families SHALL NOT be silently coerced to text — the statement SHALL
be rejected as a validation error naming the mismatch. An omitted `ELSE` SHALL translate to an absent `else`
and yield null for unmatched rows. Unlike `if`, a `THEN` (or `ELSE`) branch that is a null literal SHALL be
accepted as long as some branch has a type — `NULLIF(a, b)` lowers to exactly that shape — and the standard
`COALESCE` and `NULLIF` functions SHALL therefore translate, through the `CASE` the validator lowers them to.
A character literal SHALL keep its own length whatever the other branches' lengths are: `CASE … THEN
'large' … ELSE 'small' END` SHALL yield `small`, never a blank-padded `small `. The result column kind SHALL
be the widest branch kind.

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

A select item MAY be a window function call `<fn> OVER ([PARTITION BY <expr>, …] [ORDER BY <expr> [ASC|DESC]
[NULLS FIRST|LAST], …] [<frame>])`, where `<fn>` is one of the ranking functions `row_number()`, `rank()`,
`dense_rank()` or one of the aggregates `count`, `sum`, `avg`, `min`, `max` over an expression, and
`<frame>` is `ROWS|RANGE BETWEEN <bound> AND <bound>` with bounds `UNBOUNDED PRECEDING`, `<n> PRECEDING`,
`CURRENT ROW`, `<n> FOLLOWING`, `UNBOUNDED FOLLOWING`. The call SHALL translate to the DSL `window`
expression kind (see [structured-query](../structured-query/spec.md), "Window expression"); the standard
default frame (`RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` when `ORDER BY` is present) SHALL be
carried as an absent frame. A window function SHALL be accepted only in the select list (and, through its
alias, in `ORDER BY`) — anywhere inside a select item counts, the condition of a select-list `CASE`
included; the validator's rejection of a window function in `WHERE`, `HAVING` or `GROUP BY`
SHALL surface as a 400. In an aggregate query the window's operands SHALL be group keys or aggregate calls
(`sum(sum(x)) OVER (…)`), as standard SQL requires. `DISTINCT` inside a window function and any other
function name under `OVER` (`lag`, `lead`, `ntile`, `first_value`, …) SHALL be rejected with an error
naming the construct. The result column kind SHALL be a 64-bit integer for the ranking functions and the
aggregate's own kind otherwise.

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

A statement whose plan needs more than one query block over one entity — because it joins, reads a derived
table or CTE, or carries a subquery — is a **composite statement**. The translator SHALL produce for it an
executable plan whose leaves are single-entity blocks and whose composition mirrors the statement; the
plan SHALL execute through the same pipeline as a single-entity statement (same read-only client, `final`
deduplication, `query.budget.*` settings and row governance on the outermost block) and SHALL yield the
same response envelope and result-column descriptors. The composite plan has no `StructuredQuery`
equivalent: `POST /v1/queries/translate-sql` SHALL reject it with 400 `bad_request` and the message
`statements with joins, derived tables or subqueries execute but have no structured-query equivalent`.
A statement that is a single block over one entity — including one that uses `CASE`, arithmetic or a
window expression — is NOT composite and SHALL keep translating to a `StructuredQuery` document.

#### Scenario: Join executes over REST
- **WHEN** a client posts a join statement to `POST /v1/queries/execute-sql`
- **THEN** it executes and returns `{"rows": [...]}` with the default limit applied to the joined result

#### Scenario: Join has no DSL document
- **WHEN** a client posts the same join statement to `POST /v1/queries/translate-sql`
- **THEN** the response is 400 `bad_request` with the composite-statement message, and ClickHouse is not contacted

#### Scenario: CASE statement still has a DSL document
- **WHEN** a client posts `SELECT CASE WHEN success THEN 1 ELSE 0 END AS ok FROM dial_usage_log` to `POST /v1/queries/translate-sql`
- **THEN** the response is 200 with a `query` document whose select entry is a `case` expression

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
- **THEN** translation now **succeeds**: neither name is a catalog function, but the validator lowers both onto
  `CASE` — `CASE WHEN chat_id IS NOT NULL THEN chat_id ELSE trace_id END` and `CASE WHEN chat_id = '' THEN
  NULL ELSE chat_id END` — which the `case` expression carries (see "CASE expressions translate to the DSL
  case expression"). This supersedes the earlier rejection; the scenario keeps its historical title so the
  archive can match it

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
`UNIQUE`); ROLLUP/CUBE/GROUPING SETS; explicit `CAST` other than the identity/widening and
literal-to-timestamp forms the translator tolerates; `BETWEEN` (unless expressible — see range expansion
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
