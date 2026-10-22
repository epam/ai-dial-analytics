## ADDED Requirements

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
its own branch — and SHALL execute here.

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
`sensitive` column unresolvable for a caller without `FULL_ADMIN`, enrichment columns addressable as the
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
- **WHEN** a caller without `FULL_ADMIN` references a `sensitive` column in any branch
- **THEN** translation fails as for an unknown column, exactly as it does for a single-entity statement

#### Scenario: Star in a branch omits heavy columns
- **WHEN** a client submits `SELECT * FROM rate_analytics UNION ALL SELECT * FROM rate_analytics`
- **THEN** each branch expands to the entity's non-heavy source columns, with no heavy or enrichment column among them

#### Scenario: A set operation over catalog metadata is rejected
- **WHEN** a client submits `SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES UNION ALL SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES`, or a statement combining a catalog relation with a queryable entity
- **THEN** translation fails with 400 `bad_request` naming the unsupported construct, and neither ClickHouse nor the evaluator produces rows

## MODIFIED Requirements

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
