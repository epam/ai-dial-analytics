## Context

A keyed grain join emits, inside the enrichment's derived table:

```
WHERE e.event_id     IN (SELECT s.event_id     FROM s WHERE <source-only conjuncts>)
  AND e.request_time IN (SELECT s.request_time FROM s WHERE <source-only conjuncts>)   -- aligned joins only
  AND <pushed enrichment conjuncts>
```

and the outer join compares `(event_id, request_time)` for an aligned pair. Both sets are materialized in full
before the scan. Over a month of `dial_usage_log` each holds millions of values. The first set comes from keying
grain joins by the source-only conjuncts; the second from "A keyed grain join is bounded by an aligned partition
column". Since "Align row grain enrichments" both apply to `usage_client_identity` as well as to
`dial_usage_log_payload`.

`ReadShaping.partitionBounds` already computes, for an aligned join, the statement's top-level source-only
comparisons that read the source's partition column and nothing else, re-rendered on the enrichment's alignment
column. The partition pre-selection uses it to bound its own read. Its soundness argument applies here unchanged:
an admitted source row's enrichment row carries the same partition value, so the image holds for it whenever the
conjunct holds for the row.

## Goals / Non-Goals

**Goals:**
- Remove both key sets from an aligned join when the predicates that would build them are partition bounds only.
- Leave every other statement byte-identical.

**Non-Goals:**
- The pipeline statements: the enrichment scan, group fetch, materialize and aggregate refresh. They key their
  joins by the same mechanism, but their windows are cursor-bounded and short, and nothing was measured for them.
- A query-level `use_index_for_in_with_subqueries_max_values`, or a single-key join for aligned enrichments. See
  the proposal for why these are out of scope.

## Decisions

**Transfer only when every keying conjunct is a partition bound.** "Every keying conjunct" means every
source-only top-level conjunct of the block's filter, with no extra key conjunct handed in by a composite
reduction. The rule compares the count of source-only conjuncts with the count of `partitionBounds`, which
admits only source-only comparisons reading the partition column alone and holding no subquery.
- *Alternative, measured:* transfer the bounds and keep the grain-key set whenever any other source conjunct
  exists. Rejected because a point lookup (`trace_id = X`, `event_id = X`) gets its pruning from the grain-key
  set: one granule instead of a whole day of the payload table. The window itself never narrows the grain-key
  set below the window's rows.
- *Alternative:* transfer the bounds beside the sets in the mixed case as well. Not done: the `request_time IN`
  set already prunes partitions there, so the extra conjunct buys nothing measurable.

**Same derived table, different `WHERE`.** The join keeps its derived-table form, so the projection pruning,
pushed conjuncts, computed columns and `FINAL` placement are untouched. Only the key restrictions `keys()` would
add are replaced by the transferred bounds.
- *Alternative:* the existing `restrictions` input. Rejected because it is defined as the derived table's *only*
  conjunct and refuses a shaping, which would drop a pushed `user_email = X` that keeps the build at a few rows.

**Decided by the builder, applied by `EnrichmentJoins`.** The builder holds the filter tree, which the
decision needs: it reads which fields a conjunct references. `EnrichmentJoins` receives only rendered
conditions. So the builder computes `Map<enrichment table, transferred bounds>` and passes it to
`addReferenced`'s general form. Only the row/page-block statement and the count statement pass it. Every other
caller passes an empty map and renders as before. The aggregate pipeline's `build` overload (refresh and preview)
shares the private render path, so it renders through `withoutWindowTransfer()` to keep that guarantee.

**The composite path needs nothing extra.** An operand's moved conjuncts become its leaf query's own
`filter`, so the leaf's keying sees them as ordinary source-only conjuncts. A one-hop reduction arrives as an
extra key conjunct. It is not a partition bound, so by the first decision the join keeps its sets.

## Risks / Trade-offs

- [The build holds every enrichment row of the window, not just the rows the other filters admit.] This only
  happens when the bounds are the *only* source predicates, and then the two coincide: alignment applies only to
  row-grain enrichments, one enrichment row per source row. Over a month, memory stays close to the keyed form's
  for the full-window aggregates and is far lower wherever a pushed conjunct narrows the build.
- [A false alignment claim.] The rule is exactly as sound as the existing `request_time IN` restriction, which
  already relies on the same claim. A wrong claim reads as an unmatched row in both forms.
- [Bounds with a non-deterministic call.] Each bound is evaluated in the derived table and again in the outer
  `WHERE`, exactly as today's key subquery evaluates the same conjunct a second time. `now()` is fixed per
  statement. No catalog function the window takes varies per row.

## Migration Plan

None. The change is render-only and reverts with the commit. Acceptance is rerunning the enrichment query corpus
after deploy and comparing the changed SQL with the previous build's back to back.
