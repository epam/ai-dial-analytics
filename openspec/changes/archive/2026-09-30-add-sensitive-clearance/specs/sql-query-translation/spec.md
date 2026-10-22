## MODIFIED Requirements

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
