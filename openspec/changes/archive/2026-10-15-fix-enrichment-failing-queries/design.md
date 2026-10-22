## Context

See proposal.md for why. The current state of the code this design changes:

- `StructuredQueryBuilder.render` and `count`:
  - They call `EnrichmentJoins.addReferenced(…, ObjectPathUsage.none(), …)` and **discard** the model it returns.
  - They translate the filter before the joins exist and render the select, group, sort and having clauses
    against the original bindings.
  - So the query surface can never prune. A pruned path exists only as an alias of the derived table, and only
    the returned model's bindings (`QueryFieldBinding.prunedPaths`) make `ExprTranslator.subcolumn` address it.
- `ExprTranslator.collectObjectPaths` is the conservative path walker, and `ObjectPathUsage.Builder.markWholeObjects`
  covers bare names. Only `MaterializeService.objectPaths` uses them today.
- `CompositeQueryBuilder`:
  - It renders a join block's entity operands through `operand()` as leaves with no filter
    (`SELECT <needed columns> FROM entity`; design D4 of the composite change).
  - The join block's filter is rendered above the join against the synthetic join model.
  - The SQL front end (`RelToStructuredQueryTranslator`) runs no Calcite planner rules. A `Filter` above a `Join`
    folds into the join block's filter, and a `Filter` below a `Join` seals the operand into a derived block
    projecting every column of the entity.

## Goals / Non-Goals

**Goals:**

- p3 and j5 pass inside the 2 GiB / 20 s budget.
- Every rewrite returns exactly the rows the current rendering returns, proved by ClickHouse integration tests that
  run both renderings side by side.

**Non-Goals:**

- The i6 identity-keying trade-off and the related cost regressions.
- Pushdown or reduction through nested joins, derived-table operands or set operations (one hop, entity operands
  only).
- A general cost-based planner. Every rule here is a syntactic, always-sound rewrite.

## Decisions

### D1. The query surface derives its path usage per rendered block, and renders against the returned model

`render()` collects an `ObjectPathUsage`:

- `collectObjectPaths` over the select expressions, the filter and the having;
- `markWholeObjects` over the sort keys and group_by keys.

It passes the usage to `addReferenced` and then renders every clause against the model it gets back:

- the outer `WHERE`, translated a second time against the effective bindings;
- the select, group, having and sort clauses, through the effective bindings.

The key predicates handed to `addReferenced` stay translated against the original model. They render inside the
key subquery, over the source table, where pruned aliases do not exist and no enrichment column is named anyway.

Pushed conjuncts and calls need no special handling, because the two halves already render in two scopes:

- **Inside the derived table:** the `WHERE` copy and the computed column are translated once, in `pushdown()`,
  against the original bindings. So they address the enrichment's own column, which is in scope inside its own
  derived table.
- **In the outer statement:** the outer copy of a pushed conjunct goes through the effective bindings and
  addresses the alias.

A heavy field that is read only through pushed forms is `omitted` and never projected, so whether it was
pruned is irrelevant. Because the usage walk still covers the conjunct in the filter, the path the outer copy
addresses is always projected.

Late materialization renders three blocks, each declaring its own usage:

- **The page block:** rendered from `pageQuery`, through `render`.
- **The outer statement:** also through `render`. Its late enrichments are joined bare through `pageKeys`, so
  `addReferenced` never prunes them.
- **The `__adas_lm` page-key subquery:** projects only source keys.

*Alternative rejected:* computing the usage once in `shaped()` and threading it. The page block, the outer
statement and the count each render a different set of clauses. One shared usage would either mark too little
(a pruned path that a clause still reads whole) or too much.

### D2. Only the user-facing call sites declare usage; the pipeline overload does not

The existing spec names the aggregate refresh, the enrichment scan and the group-fetch projection as non-declaring.
`AggregateSqlCompiler` reaches `render` through the public `build(…, LeafRestriction)` overload. Its preview path
passes `LeafRestriction.NONE` and then appends `select` items of its own after `build` returns, which no usage
could have accounted for. So:

- `render` takes an explicit `declarePaths` flag;
- the `LeafRestriction` overload passes `false`;
- the single-entity read, the count and the composite `build(…, LimitPolicy)` overload pass `true`.

*Alternative rejected:* deciding by `extra == NONE`. That is the preview path's own argument, so the preview would
start pruning.

### D3. Operand predicate pushdown is hand-written in `CompositeQueryBuilder`, not a Calcite rule

The renderer cannot rely on the planner here, for these reasons:

- **The cost of a planner rule:** Calcite's `FilterJoinRule` (`CoreRules.FILTER_INTO_JOIN`) would put a `Filter`
  under the `Join`. The translator then seals that operand into a derived block projecting **every** column of
  the entity: `asOperand` keeps only a *bare* scan as an `EntityRelation`. The renderer's per-operand column
  pruning (composite design D3) would be lost, and a heavy enrichment column would be joined into a block that
  never reads it.
- **Outer joins:** `FilterJoinRule` also strengthens an outer join to inner when a filter on the null-supplying
  side is null-rejecting. That is sound, but it is a second rewrite whose join-kind change the outer-join tests
  would also have to cover.
- **No planner today:** the translator runs no planner rules, so adopting one is a cross-cutting change for a
  narrow gain.

In `Rendering.render`, when the block's source is a `JoinRelation`:

1. Split `block.filter` into its top-level conjuncts (a flattened `AND`).
2. Map each conjunct's field names through `join.joinColumns()` to `(side, sourceName)`.
3. Move a conjunct into side S when all of the following hold:
   - all of its names map to S;
   - S is an `EntityRelation`;
   - the join kind admits S (D5);
   - the conjunct contains no `SubqueryNode`, `ScalarSubqueryExpr` or `LambdaExpr`. A lambda's parameter
     shadowing makes renaming its body unsafe, and subqueries are rare enough here not to be worth the scope
     reasoning.
4. Rename the moved conjunct's `FieldExpr`s from the join's column names (`trace_id0`) to the operand's own names
   (`trace_id`).
5. Pass the conjunct to the leaf as its `filter`. The leaf's ordinary `keyPredicates` then performs the RC1
   source-only split.
6. Render the remaining conjuncts above the join. The outer block's referenced set is computed from the remaining
   filter, so a column that only a moved conjunct read is no longer projected by the operand.

Moving the conjunct rather than copying it: the conjunct holds over the same row on either side of the join for
an admitted side (D5), so the outer copy would be redundant work and an extra projected column.

### D4. Semi-join reduction is a raw key subquery handed to the leaf as a key conjunct

The reduction mirrors RC1's `EnrichmentJoins.selects`:

```
DSL.condition("{0} IN ({1})", reducedKeyField,
    DSL.select(restrictedKeyField).from(restrictedModel.table()).where(<restricted side's moved source-only conjuncts, translated against its model>))
```

**Inputs:**

- Both key fields are the operands' own source bindings (`binding.field()`), the same values the operands project
  and the `ON` condition compares.
- The key is the first top-level `EQ` of the `ON` condition between a bare field of each side, where both are
  **source** columns of their entities. A key on an enrichment column would make the reduction an enrichment
  conjunct, which cannot key anything.

**Where it goes:**

- The reduction is not a `FilterNode`: it needs the raw source table in a subquery, which the DSL cannot express.
  So it reaches the leaf as a new package-private build parameter, `keyConjuncts`, a list of jOOQ conditions over
  the source alone.
- `render` ANDs `keyConjuncts` into the leaf's `WHERE` and appends them to the leaf's `keyPredicates`. That is
  what keys the reduced operand's identity join, and it is the measured win: the move alone still OOMs.

**Why one key and no `FINAL`:**

- One key column (the first equality), not the tuple. It is the measured shape, and it stays correct whatever the
  key types: a tuple `IN` across differently-typed or `Nullable` columns has subtler coercion than a scalar one.
  A future change can widen it to a tuple if that measures cheaper.
- No `FINAL` modifier on the key subquery, for the same superset argument as RC1. The query-level `final`
  setting, when present, still applies to it.

### D5. Which sides admit a move and a reduction

| join | move into | reduce |
|---|---|---|
| INNER | left, right | each side, by the other's own moved source-only conjuncts |
| CROSS | left, right | none (no `ON` equality) |
| LEFT | left | right, by left |
| RIGHT | right | left, by right |
| FULL | none | none |

A reduction is built only from a side's own moved conjuncts (never from another reduction), and only for the join
the block reads directly. That makes "one hop" structural rather than a counter.

### D6. Composite blocks get Part 1 for free

The leaf of an entity operand and an entity-rooted composite block both render through `render` with
`declarePaths = true`. A synthetic model (a join, derived table or set operation) has no enrichments, so nothing
there can prune. The SQL surface's single-entity statement goes through `build(dsl, model, query, maxLimit)`, the
shaped path.

### D7. Part 3 (p4) is dropped by decision: no fix, no refusal

p4 (`request_tags ILIKE '%claude%'` over all time) is a full scan that serializes every document. It was measured
on a throwaway local ClickHouse 26.3 against a few hundred thousand payload rows, calibrated to a running
deployment:

- `toJSONString(request_tags) ILIKE` comes to tens of seconds all-time, which matches the observed timeout;
- `JSONAllPaths` alone takes seconds;
- a typed path read (`request_tags.user-agent ILIKE …`) takes a fraction of a second.

**Rejected after measurement:**

- **3a, a stored canonical-text column** (`MATERIALIZED toJSONString(request_tags)`, about 1–2 s, hundreds of MB):
  text copies of a column are not wanted. A substring search over a whole JSON document is a
  query-authoring problem. The caller should address the exact path, or bound the window.
- **3b, an up-front 422:** not wanted either. The statement keeps running under the ordinary budget and is
  refused by it (`query_budget_exceeded` at the 20 s ceiling), exactly as today.
- **Constraint substitution and skip indexes:** ClickHouse's `optimize_substitute_columns` does not fire for the
  keyed derived-table shape. `ngrambf`/`tokenbf` indexes are never used by ILIKE, and FINAL re-expands what LIKE
  prunes.

Acceptance for p4 is therefore the budget refusal it returns today, not a pass.

## Risks / Trade-offs

- [The usage walk misses a clause that renders a pruned column's base name, so the statement names a column
  nothing projects] → The walk covers exactly the clauses `render` translates. It is conservative (`markWhole` on
  anything unrecognised: a subquery, a scalar subquery, a bare field). An integration test runs every
  `json_extract_*` shape, plus mixed whole and path references, with pruning on and off against ClickHouse.
- [The double translation of the filter (key predicates against the original model, `WHERE` against the effective
  model) diverges] → The key predicates are source-only by construction and reference no enrichment binding,
  which is the only kind pruning changes.
- [The reduction subquery costs more than it saves when the restricted side is wide] → It is evaluated once per
  scope, as RC1's key set is, and it only exists when the restricted side carries a source predicate. An
  unrestricted side never builds one.
- [Pushdown changes semantics under `join_use_nulls=1`] → Only preserved sides receive moves, and only discarded
  sides are reduced. The integration test runs every join kind under `join_use_nulls=1` with NULL keys, duplicate
  keys and partnerless preserved rows, and compares against the unrewritten statement.
- [Statement-text snapshot tests change] → The rendered SQL of the query surface changes for object-path reads
  and for filtered joins. Those tests are updated alongside new render tests that pin the new shapes.

## Migration Plan

Rendering only: deploying and rolling back are code-only. No migration, no config key.
