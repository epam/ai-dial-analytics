## Context

`fold-rule-into-pipeline` merges the two registries and collapses the *input* lookup that guards a table
against deletion — one query now answers for both kinds, where the two registries each asked their own.
It deliberately leaves the other two guards ENRICH-scoped, because widening them changes which deletes
succeed.

Those two are **declaration-parsing** questions, and that is why the collapse does not reach them:

| Question | Answered by | Cross-kind after the fold? |
|---|---|---|
| Which pipelines declare this table as their input? | `pipelines.findByInputTable` | **Yes** — one column, one query |
| Which declarations name a column of this *enrichment* through the grain join? | parse every stored declaration | No — the reader's own input is a different table |
| Which declarations name *this column* of this enrichment? | as above, narrowed to one name | No |

The gap is live, and it is worth stating concretely because it is the whole justification for the change.
`sessions_rollup` groups on `usage_client_identity.client_session_id`, so dropping that enrichment breaks
it. Yet `DELETE /v1/tables/usage_client_identity` consults no aggregate declaration, and
`findByInputTable('usage_client_identity')` returns nothing — that rollup's input is `dial_usage_log`. The
delete is blocked today **only because `session-insights-live` happens to read the same enrichment**.
Retire that one declaration and an enrichment three rollups read becomes droppable, with the failure
arriving at the next refresh instead of at the delete.

## Goals / Non-Goals

**Goal.** The two enrichment-read guards consult every stored declaration, whatever its kind, and report
the same 409 they already report.

**Non-Goals.** No new endpoint, no new error code, no change to the input guard (the fold already collapsed
it), and no lineage query — "which pipelines read this enrichment?" remains a thing an operator computes by
reading declarations rather than a surface ADAS exposes.

## Decisions

### D-1: Two gates answer one question, dispatched on kind

The two kinds name their columns in different places, so one extraction cannot serve both: an `enrich`
declaration names them in its bindings, filter, readiness signal and its **evaluator's** output
expressions; an `aggregate` one in its group keys, its measures, its filter and each measure's own
`where`. So `AggregateSpecValidator` gains the pair `EnrichSpecValidator` already has —
`referencedEnrichments(PipelineDeclaration)` and `declaredFieldsOf(PipelineDeclaration)` — and the catalog's
guard dispatches on `declaration.kind()`.

*Rejected:* a single extraction over the raw `spec` JSON. It would find the tokens, but it would also find
them in members that are not column references (a measure's output `name`, a binding's `var`), and the
guard's precision is what keeps it from freezing an enrichment's whole schema.

### D-2: The aggregate extraction scans stored fragments; it does not re-run the SQL front end

`AggregateSpecValidator` already has a `referencedEnrichments(inputTable, filterSql, spec)` that resolves
through `PipelineFilters` — the real front end. The guard must **not** use it, for two reasons that both
bite at scale:

- **Cost.** The question is asked over *every* stored declaration, from inside a table delete or a schema
  patch. A front-end parse per declaration per question is a translation per node of the walk.
- **Isolation.** A fragment that no longer parses would throw, so one drifted declaration would fail an
  operation about an unrelated table. The enrich side already avoids this by scanning for
  `table.column` tokens, and the aggregate side has to make the same trade.

The precision lost is bounded in the safe direction: an enrichment column has exactly one spelling in a
declaration's namespace — the qualified flat name — so a false negative is impossible for the shape the
guard exists for, and a false positive costs a spurious 409 on a table nobody is reading.

`QualifiedColumnTokens` is extracted from `EnrichSpecValidator` so both sides scan with one pattern rather
than two copies that could drift.

### D-3: The guard stays per-column, not per-table

The column check is narrower than the table check on purpose. A guard that refused any schema change to an
enrichment because *some* column of it was read would freeze that enrichment's schema the moment one
column entered a rollup — which is the failure mode of a guard that is too broad, and as real as the one
being fixed. A test pins it: an unreferenced column of the same enrichment stays droppable.

## Risks / Trade-offs

- **A delete that succeeds today starts answering 409.** That is the change, not a side effect. It is
  detectable rather than silent — the 409 names the pipelines — and the remedy is the one the enrich path
  already documents: retire or re-declare the reader first.
- **A stored declaration whose input has drifted contributes no references**, so it does not protect its
  enrichment. Deliberate, and the same posture the enrich guard takes: the alternative is a delete that
  fails because of an unrelated broken declaration.
- **Cost on the delete path** is one `findAllDeclarations()` plus one entity resolution per declaration.
  The registry is eight objects; if it grows, the entity resolution is what to cache, not the scan.

## Migration Plan

None. No schema change, no config, no coordinated deploy: the guard reads declarations that are already
stored. It ships after `fold-rule-into-pipeline` because it depends on `findAllDeclarations()` being
cross-kind and on `PipelineDeclaration` carrying the raw `spec`.

**Before deploying**, list the enrichments that would become undeletable — the declarations naming one are
readable from the registry — so an operator is not surprised by a 409 on a table they were about to drop.

## Open Questions

None.
