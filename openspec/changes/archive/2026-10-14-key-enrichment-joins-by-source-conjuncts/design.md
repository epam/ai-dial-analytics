## Context

`StructuredQueryBuilder.build` and `buildCount` translate `query.filter()` into one jOOQ `Condition`. They pass
that condition to `EnrichmentJoins.addReferenced` as the key predicate list, but only when
`EnrichmentJoins.referencesSourceOnly(model, referencedFields(filter))` holds; otherwise they pass `List.of()`.
`EnrichmentJoins` already takes a *list* of source-only predicates and builds both the grain-key `IN` and the
aligned-partition `IN` from it. So the fix lies entirely in how the builder chooses that list. See proposal.md
for the measured motivation.

The pipeline paths (`AggregateCompiler`, `MaterializeService`, `ScanService`, `GroupFetchService`) apply the same
whole-filter rule, but they always key on their window/cursor predicates as well, so they never lose the window.

## Goals / Non-Goals

**Goals:**
- Key every referenced enrichment join on the subset of the filter's top-level conjuncts that are source-only.
- Keep the rendered SQL byte-identical for every query that was already keyed (a fully source-only filter) or
  is still unkeyed (no source-only conjunct).

**Non-Goals:**
- Moving enrichment conjuncts into the derived table (RC2, which needs null-rejection rules).
- Splitting `or`/`not`, or doing any boolean normalisation (CNF, De Morgan).
- Changing the pipeline-side keying rules.

## Decisions

**D1: Split at the DSL level, then translate each kept conjunct.** A new static
`StructuredQueryBuilder.sourceOnlyConjuncts(QueryEntityModel, FilterNode) → List<FilterNode>` flattens
`LogicalNode(AND, …)` recursively. It does not descend into `OR`/`NOT`, comparisons or subquery predicates, and
it keeps each conjunct whose `referencedFields(node)` passes `EnrichmentJoins.referencesSourceOnly`. Each kept
node is translated with the same `filterTranslator` and `model.bindings()` as the whole filter.
- *Rejected:* taking the jOOQ `Condition` apart. jOOQ's combined conditions are opaque (`CombinedCondition` is
  internal API), and the field set a condition references cannot be recovered reliably from it. The DSL tree
  already has `referencedFields`, which also covers lambda captures and subquery operands.

**D2: A fully source-only filter keeps the whole translated condition.** When the whole filter is source-only,
`keyPredicates` returns `List.of(filter)` exactly as today. It splits only when some conjunct reaches into an
enrichment. This keeps existing renders (and the composite render tests that pin them) unchanged, and in the
common case it avoids translating the filter twice.
- *Rejected:* always splitting. That is semantically identical, but every keyed statement's text would change
  from `where (a and b)` to the list form, for no gain.

**D3: Re-translating a conjunct is safe.** `FilterTranslator.toCondition` has no side effects beyond building
jOOQ objects. Translating a subquery conjunct a second time does call the composite renderer again, but its
only side channel is the `finalReads` consumer, and that consumer only sets flags. Translating again also raises
no validation error the full-filter translation would not already have raised, because the full filter is
translated first and every conjunct is a subtree of it.

**D4: The pipeline copies stay as they are.** `MaterializeService.keyPredicates` and `ScanService.base` hold a
`FilterNode` plus a `filterTranslator`, so the splitter would drop in with a one-line change. `AggregateCompiler`
works on a pre-compiled `CompiledFilter` (a `Condition` plus a column set), so it would need the split carried
through its compile step. `GroupFetchService` keys on its lookup key and has no filter in play. All four are left
untouched, as the request scopes, and recorded here as follow-ups.

## Risks / Trade-offs

- [The key sub-query now repeats part of the filter, which costs an extra source scan per keyed join] → This is
  the same cost a source-only filter already pays today, and the measured fix shapes show it is far smaller than
  hashing the enrichment.
- [A weak source conjunct, such as `request_time >= <years ago>`, keys the join on a huge key set] → The
  `IN`-set is still no larger than the unkeyed hash build, and it gains partition pruning on aligned
  enrichments. Results are correct either way.
- [Soundness hinges on every key predicate being a conjunct of the outer `WHERE`] → The splitter only ever
  returns subtrees of top-level `and`s, and the integration test compares the keyed result with the unkeyed
  result, including an `IS NULL` enrichment conjunct and source rows that have no enrichment row.

## Migration Plan

Code-only. Deploying the change switches the affected statements to keyed joins, and reverting the commit
restores the old rendering. There is no data, schema or configuration change.
