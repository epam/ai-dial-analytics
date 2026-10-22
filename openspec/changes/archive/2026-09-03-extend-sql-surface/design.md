## Context

See proposal.md — Why. The SQL front end (`SqlQueryFrontEnd`) parses with Calcite Babel, validates against
a catalog-derived schema (`CalciteQueryCatalog`), lowers to a `RelNode` with **no planner rules**, and
`RelToStructuredQueryTranslator` folds the plan bottom-up into one mutable `Ctx` that becomes a
`StructuredQuery`. Expressions go through `RexToExprTranslator` into the DSL `Expr`/`FilterNode` trees, and
execution renders those trees with jOOQ (`StructuredQueryBuilder` → `ExprTranslator`/`FilterTranslator`,
enrichment `LEFT JOIN`s via `EnrichmentJoins`), then runs the SQL on the read-only client with `final = 1`
and the `query.budget.*` settings. Every governance rule of the read path is a property of that rendering
pipeline: sensitive columns are absent from the model bindings, entity resolution goes through
`CatalogQueryService`, function semantics live in `ExprTranslator`'s templates.

Constraints that shape the approach:

- The DSL is a public contract (`POST /v1/queries/execute`, saved queries) and is single-entity by design.
- `ExprTranslator` is the single place where function semantics are rendered (the `div` null-guard, the
  `toBool` wrap, JSON subcolumn access…). A second renderer for the same functions is a divergence risk.
- Because no planner rules run, Calcite leaves subqueries as `RexSubQuery`, windows as `RexOver` inside
  `LogicalProject`, and CTEs already inlined by `SqlToRelConverter`. That is exactly the shape a
  structure-preserving translation wants.
- ClickHouse 26.3 (local stack and Testcontainers) supports joins, `EXISTS`, `IN (subquery)`, scalar
  subqueries and window functions; correlated subqueries are still gated behind an experimental setting.

## Goals / Non-Goals

**Goals:**

- Accept joins, CTEs, derived tables, uncorrelated subquery predicates/scalar subqueries, `CASE` and
  window functions on all three SQL surfaces with the existing governance intact.
- Route every leaf entity read through the existing `StructuredQueryBuilder` rendering so masking,
  enrichment joins and function semantics cannot diverge between single-entity and composite statements.
- Keep the single-entity path byte-identical: a statement accepted today produces the same
  `StructuredQuery`, the same SQL and the same result.
- Make `CASE` and windows first-class DSL expressions so `translate`/`translate-sql` stay inverse.

**Non-Goals:**

- Set operations, `WITH RECURSIVE`, correlated subqueries, `LATERAL`, general `CAST`, `lag`/`lead`/`ntile`,
  named windows (`WINDOW w AS …`) — each is rejected with a construct-naming error and can be a later change.
- A DSL representation of joins or subqueries. The DSL stays single-entity; composite statements are an
  SQL-only capability.
- Cost-based planning or predicate pushdown of our own; ClickHouse's analyzer does that, and the query
  budget bounds the rest.

## Decisions

### D1. Composition in jOOQ over the existing DSL leaf, not Calcite `RelToSqlConverter`

Two ways to execute a multi-block plan were considered.

*Rejected:* lower the validated `RelNode` straight to ClickHouse SQL with Calcite's `RelToSqlConverter` +
`ClickHouseSqlDialect`. It handles every relational shape for free, but every ADAS function would need a
second rendering (a dialect `unparseCall` mirroring `ExprTranslator`'s templates), each `TableScan` would
need rewriting into the physical source + enrichment joins, and the result-kind derivation would have to be
redone over `RexNode`s. Two renderers for one function catalog is the divergence the project's "single
chokepoint" invariants exist to prevent.

*Chosen:* generalise the fold so that a **query block** (`SELECT … FROM <source> [WHERE] [GROUP BY] [HAVING]
[ORDER BY] [LIMIT]`) is the unit, and the source of a block is either an entity, a derived block, or a join
of two sources. A block over an entity is still a `StructuredQuery` and is rendered by
`StructuredQueryBuilder` exactly as today. A block over a derived or joined source is rendered by the same
builder against a **synthetic `QueryEntityModel`** whose `table` is the jOOQ derived table / join table and
whose `bindings` map the block's visible column names to alias-qualified jOOQ fields. `ExprTranslator` and
`FilterTranslator` therefore render every expression of every block, with their type rules, unchanged.

### D2. Internal plan model beside the DSL, with two internal node kinds inside the sealed hierarchies

New records in `service/query/model/`:

- `Relation` (sealed): `EntityRelation(entity, columns)` — a leaf entity and the columns it exposes;
  `DerivedRelation(CompositeQuery)`; `JoinRelation(left, right, JoinKind, FilterNode on, List<JoinColumn>
  joinColumns)`, each join column naming its side and source column.
- `CompositeQuery(Relation source, StructuredQuery block, List<RelationColumn> columns)` — one block over a
  source. `block.entity()` is the leaf entity for an `EntityRelation` source and the synthetic alias
  otherwise. `columns` carry each output's name, `ResultColumnKind` (+ element kind) and the derived
  `QueryFieldType`, so an enclosing block can type its references without re-deriving them.
- `SubqueryNode(kind IN|NOT_IN|EXISTS|NOT_EXISTS, List<Expr> operands, CompositeQuery subquery)` implements
  `FilterNode`; `ScalarSubqueryExpr(CompositeQuery subquery)` implements `Expr`.

The last two are **internal-only**: they are listed in the sealed `permits` so every switch stays
exhaustive, but they carry no Jackson type id (deserialising them fails as an unknown `type`/`op` → 400),
`StructuredQueryToSqlRenderer` refuses them, and only the SQL fold constructs them. The alternative — a
parallel internal predicate/expression hierarchy — would have forced a second `FilterTranslator`.
`ExprTranslator`/`FilterTranslator` gain an overload taking a `SubqueryRenderer` callback
(`Select<?> render(CompositeQuery)`); the two-argument overloads pass a renderer that throws, which is what
keeps `POST /v1/queries/execute` unable to reach a subquery even by construction.

### D3. Fold: seal-and-wrap instead of reject

`RelToStructuredQueryTranslator` keeps its linear `Ctx` fold and its absorption rules. Where it used to
throw for a shape the single block cannot absorb (`Filter`/`Aggregate` above a `Sort`, a second `Sort`,
`Aggregate` above `Aggregate`/`DISTINCT`), it now **seals** the current `Ctx` into a `CompositeQuery` and
starts a fresh `Ctx` over a `DerivedRelation` of it, whose outputs are `FieldExpr`s over the derived column
names. `LogicalJoin` seals both inputs and starts a `Ctx` over a `JoinRelation`; the join row type's field
names (Calcite already de-duplicates them) become the block's column names, each bound to
`<side alias>.<inner name>`. Only the outermost block turns `fetch == null` into "apply the default limit";
sealed blocks pass `LimitPolicy.NESTED` so `StructuredQueryBuilder` renders no `LIMIT` for them and does
not cap an explicit one (`validateLimit` is bypassed for inner blocks). A plan whose root block sits
directly on an `EntityRelation` with no subquery nodes is emitted as today's `SqlTranslation`; anything
else becomes `SqlCompositeTranslation(CompositeQuery root, columns, explicitLimit)`, a third member of the
sealed `SqlStatementPlan`.

Column pruning happens at render time: `CompositeQueryBuilder` computes the names a block and its `ON`
condition reference, maps them through the join's columns to each operand, and renders an entity operand
with exactly those fields (the first wildcard field when none is read, as in `SELECT count(*) FROM a CROSS
JOIN b`), so a joined entity's derived subquery never projects heavy columns nobody asked for. Derived
blocks' outputs are **not** pruned (a `DISTINCT` block's row count depends on them). Doing it at render
rather than in the fold means a subquery nested inside a filter is pruned by the same code path.

### D4. Every leaf of a composite plan is a derived table

A join operand that is an entity renders as `(SELECT <fields> FROM <entity> [LEFT JOIN <enrichment>…]) AS
t<n>`, built by `StructuredQueryBuilder` from a row-mode `StructuredQuery` over the referenced fields. This
keeps enrichment joins, source-column names and masking inside the existing code path and makes
self-joins and enrichment-bearing operands uniform. The alternative — aliasing the bare table and
re-qualifying bindings — is faster to read but needs its own enrichment-join re-qualification and a second
rendering mode; ClickHouse's analyzer pushes `WHERE` predicates into join operands, and the query budget
bounds what it does not.

Right-deep joins are avoided by construction: a `JoinRelation` renders left-deep (`t0 JOIN t1 ON … JOIN t2
ON …`); a join appearing as the *right* operand of another join is sealed into a derived table first.

### D5. Join condition must carry an equality

ClickHouse's hash join requires at least one equi-conjunct in `ON` (inequality-only conditions are behind
an experimental setting) and otherwise fails with an opaque server error that would surface as a generic
400. The fold therefore checks, after translating `ON` to a `FilterNode` over the join's bindings, that at
least one top-level `AND` conjunct is an `eq` whose two operands reference columns from different sides;
`CROSS JOIN`/comma joins (condition `TRUE`) skip the check. `USING` arrives from Calcite as that equality
already.

### D6. Correlated subqueries are rejected

`RexSubQuery` is translated by folding its `rel` into a `CompositeQuery` with the same snapshot. A
`RexFieldAccess` over a `RexCorrelVariable` inside it is rejected with a message naming the join rewrite,
rather than rendered: ClickHouse 26.3 gates correlated subqueries behind `allow_experimental_correlated_
subqueries`, and the outer-scope binding plumbing is not worth carrying for a feature the engine may
refuse. Scalar subqueries are admitted as comparison operands and select items only; as a function
argument they are rejected (the `ExprTranslator` template code paths assume field-like operands).

### D7. `CASE` and windows are DSL expressions

`CaseExpr(List<CaseBranch(FilterNode when, Expr then)>, Expr else)` and `WindowExpr(FnExpr fn,
List<Expr> partitionBy, List<WindowOrder> orderBy, WindowFrame frame)` join the public `Expr` grammar
(`type: "case"` / `"window"`). Rationale: both are expression-level, both are useful to DSL clients, and
putting them in the DSL is what lets `translate-sql` keep returning a document for statements that use
them. `case` deliberately admits the **filter grammar** as its condition — the one expression position
that does — because SQL `CASE WHEN a > b` has no `if`-style boolean-function spelling. Branch unification
reuses `ExprTranslator`'s `if` rules; a missing `ELSE` is an absent `else` (Calcite's trailing `NULL`
literal is folded away), while a null `THEN` is rejected exactly as `if`'s null branch is.

Window rendering uses a jOOQ template `{0} OVER (PARTITION BY {1} ORDER BY {2} <frame>)` around the
function field `ExprTranslator` already produces, so an aggregate under `OVER` renders with the same
template as the aggregate itself. Ranking functions (`row_number`, `rank`, `dense_rank`) join
`QueryFunctionCatalog` in a new `QueryFunctionGroup.WINDOW`, are rendered only inside a window, and are
rejected as plain `fn` calls. Position rules (select only; no window inside filter/having/aggregate/window;
aggregate-mode operands must be group keys or aggregates) are enforced in `StructuredQueryBuilder`, so the
DSL and the SQL surfaces fail the same way.

### D8. Star expansion over composite sources

`SqlStarExpander` is extended rather than bypassed: for a join `FROM`, a bare `*` expands to each operand's
wildcard fields in operand order (qualified by the operand's alias so validation cannot see an ambiguity),
`alias.*` to that operand's; for a derived table or CTE reference the star is left for Calcite, whose
expansion is the inner select list — already governed where the base entity is named. CTE bodies are
walked so a star inside a CTE expands at the entity.

### D9. Execution and transport plumbing

`StructuredQueryService.execute(SqlCompositeTranslation | CompositeQuery, maxLimit)` resolves every entity
of the plan through the catalog, renders via a new `CompositeQueryBuilder` (in `translate/`, owning alias
allocation and the synthetic models), and runs through `StructuredQueryExecutor` with the same
`withFinal(budget)` settings and the same materialisation; `totalCount` is never computed (SQL has no
opt-in). `SqlQueryController` and `AdasFlightSqlProducer` add the third `switch` arm; Flight's
`honorsStatedLimit`/`effectiveQuery`/`rejectIfTruncated` are generalised over an interface both
translations implement (`columns()`, `explicitLimit()`, `page()`, `withPage(OffsetPage)`), so the
clamp-and-probe logic is written once. `QueryTranslationController.translateSql` maps the composite arm to
the fixed 400 message via `SqlQueryFrontEnd.translate`.

### D9a. Composite statements run with `join_use_nulls = 1`

ClickHouse's `join_use_nulls` defaults to `0`: an unmatched side of an outer join yields each non-nullable
column's **type default** (`''`, `0`, `false`, the epoch) rather than NULL, and many source columns are
non-nullable. A caller writing standard SQL expects NULL, and every downstream rule (`IS NULL`, `count(col)`,
`avg`) is written against that expectation. Composite statements are therefore executed with the server
setting `join_use_nulls = 1`, merged under the caller settings exactly as `final = 1` is, so
user-written outer joins behave as in PostgreSQL. Single-entity statements and the DSL path keep the
default — the enrichment machinery (`EntityFields`, `RuleGovernanceValidator`) documents and relies on
default-valued unmatched enrichment columns, and changing that under every existing query is out of scope.
The visible consequence, stated in the spec: inside a composite statement an unmatched enrichment column of
a non-nullable type reads as NULL where the same column in a single-entity statement reads as its default.

*Rejected:* wrapping every non-nullable projected column of an outer join's inner side in `toNullable(...)`.
It reaches the same result for projected columns only, misses `ON`/`WHERE` references, and would have to
know each column's physical nullability, which the catalog model does not carry into the query layer.

### D10a. Calcite settings the composite surface needs

Three validator behaviours surfaced while implementing and each is pinned in `SqlQueryFrontEnd`:

- **Strict `GROUP BY`.** Babel conformance is non-strict: a non-grouped select item is wrapped in `ANY_VALUE`
  instead of rejected, and the check that decides which items to wrap dereferences a searched `CASE`'s null
  first operand — every `CASE … END AS k … GROUP BY k` failed with a `NullPointerException`. The validator
  now runs under Babel with `isNonStrictGroupBy()` false (PostgreSQL's behaviour). `PlannerImpl` takes the
  validator conformance from its *connection config* and the parser config only carries an enum, so the
  override travels in a `CalciteConnectionConfigImpl` subclass passed through the framework `Context`.
- **No CASE branch coercion.** Calcite's implicit type coercion widens `CASE WHEN c THEN 'yes' ELSE 1 END` to
  text and folds the cast into the literal, so nothing downstream could see the mismatch. A
  `TypeCoercionImpl` subclass with `caseWhenCoercion` disabled makes the validator report the mismatch;
  every other coercion (string literal against a timestamp column) stays.
- **Ragged character types unify as varying.** The default type system unifies `CHAR(5)` and `CHAR(6)` as
  `CHAR(6)` and pads the shorter literal — `'small'` became `'small '`, and `IN ('gpt', 'claude')` compared
  against `'gpt   '` (a pre-existing defect the CASE work exposed). `shouldConvertRaggedUnionTypesToVarying`
  is on, so the unified type is `VARCHAR` and literals keep their length.

*Rejected:* building the validator and converter by hand instead of through `Frameworks.getPlanner` — full
control, but a second copy of the planner pipeline for three switches.

### D10b. Calcite's own window rewrites are undone

`SqlToRelConverter` lowers a nullable windowed `SUM(x) OVER w` into `CASE WHEN COUNT(x) OVER w > 0 THEN
SUM(x) OVER w ELSE NULL END`, and the standard convertlet expands `AVG(x) OVER w` into `SUM(x) OVER w /
COUNT(x) OVER w`. Both are recognised by shape in `RexToExprTranslator` and translated back to the single
`window` expression the caller wrote: ClickHouse's `sum` and `avg` already yield null over an empty frame, and
a DSL document carrying the expansion would render (and round-trip) something the caller never wrote.
`COALESCE` and `NULLIF`, which the same converter lowers onto `CASE`, are *kept* as their lowering: the
`case` expression carries them exactly, so both standard functions now translate with no catalog entry.

### D10. Screen accepts `WITH`

`SqlQueryFrontEnd.screen` admits `SqlKind.WITH` when the body is `SELECT`/`ORDER_BY` and no item is
recursive; set-operation kinds keep the existing "only SELECT statements" rejection but the message now
names set operations, and `LogicalUnion` reaching the fold (from a subquery) is rejected with the same
wording.

## Risks / Trade-offs

- [Join fan-out or cross join blows memory/time] → the existing `query.budget.*` ceilings apply to the whole
  statement; the 422 message already names them. Documented in README as the bound for joins.
- [Derived-table leaves defeat ClickHouse predicate pushdown for some shapes] → analyzer pushes `WHERE`
  into subqueries in the common cases; budget bounds the rest; revisit with bare-table aliasing if a real
  workload shows it (D4 keeps that change local to `CompositeQueryBuilder`).
- [ClickHouse alias/column shadowing inside nested blocks (`SELECT x + 1 AS x … WHERE x > 3`)] → same
  exposure the single-entity path has today; inner blocks use the validator's own unique names and
  alias-qualified references, which ClickHouse resolves before select aliases. Integration tests cover a
  same-named inner/outer column.
- [Sealed-hierarchy internal nodes leak onto the wire] → no Jackson type id, deserialiser rejects, renderer
  rejects, unit test asserts `"type":"subquery"` is a 400.
- [Result-kind typing across blocks loses precision (FLOAT64 vs DECIMAL both map to `decimal`)] →
  `RelationColumn` carries the `ResultColumnKind` alongside the `QueryFieldType`; the fold consults the kind
  first for derived names, so Arrow schemas stay exact.
- [`NOT IN` with nulls follows ClickHouse, not the standard] → stated in the spec; no rewrite attempted.
- [Calcite de-duplicated join field names (`event_id0`) become user-visible output names] → they are the
  validator's names, identical to what Calcite would report for any SQL client; a caller aliasing the
  columns gets their alias.

## Migration Plan

No migration, config or credential change. Deploy as a normal release; rollback is a redeploy of the
previous image. Previously rejected statements start succeeding; no accepted statement changes result.

## Open Questions

None that change the specs or tasks. `lag`/`lead` and set operations are deliberate follow-ups.
