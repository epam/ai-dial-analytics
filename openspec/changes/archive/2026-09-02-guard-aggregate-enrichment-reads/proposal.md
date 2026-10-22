## Why

`DELETE /v1/tables/{name}` and `PATCH /v1/tables/{name}/schema` guard an enrichment against being
dropped or re-shaped while something still reads it. Both guards consult **only enrichment
declarations**, because when they were written the enrichment registry was the only registry whose
declarations could name a column of an enrichment through the grain join. Since
`2026-08-27-aggregate-reads-entity`, an aggregate pipeline can too — and the guards never caught up.

The gap is live. `sessions_rollup` groups on `usage_client_identity.client_session_id`, yet
`DELETE /v1/tables/usage_client_identity` consults no aggregate declaration, and
`findByInputTable('usage_client_identity')` returns nothing because that rollup's input is
`dial_usage_log`. **The delete is blocked today purely by coincidence** — the
`session-insights-live` enrichment happens to reference the same enrichment. Retire that one
declaration and an enrichment three rollups read becomes droppable, with the failure arriving at the
next refresh instead of at the delete.

`fold-rule-into-pipeline` is what makes the fix small: once `findAllDeclarations()` returns both
kinds, the aggregate side's referenced-enrichment set is already computed by `AggregateSpecValidator`
and the guard is a projection widened to carry `group_by` and `measures` alongside the bindings. It
is deliberately **not** folded into that change, because it is a behavior change and that one is
structural.

## What Changes

- **BREAKING** `DELETE /v1/tables/{name}` on an ENRICHMENT read by an `aggregate` pipeline's
  declaration now answers **409** instead of succeeding. A delete that works today starts being
  refused — which is the point, but it is a contract change and not a bug fix.
- **BREAKING** `PATCH /v1/tables/{name}/schema` dropping or renaming a column of an ENRICHMENT that
  an `aggregate` declaration names now answers **409** on the same basis.
- The 409 message names the pipelines that read the enrichment without distinguishing their kind — a
  caller's remedy is the same either way: retire or re-declare the reader first.

## Capabilities

### Modified Capabilities
- `table-management`: "Delete a table definition" — the enrichment-read guard is restated as
  declaration-kind-independent, with scenarios for an enrichment only an aggregate pipeline reads and for
  the guard releasing once nothing reads it. ("Modify table schema via PATCH" is untouched: the
  drop/rename protection is not stated there — it lives in the declaration capabilities, which is why
  `aggregate-pipeline` gains its own.)
- `aggregate-pipeline`: **ADDED** "An aggregate declaration's enrichment references are protected against
  drop and rename" — the requirement the enrich declaration has always had, stated for the aggregate side
  because it did not have it. An addition rather than a modification: the aggregate path gains a
  protection, it does not change one.

## Impact

- **API**: two `/v1/tables` endpoints gain a refusal case. No new endpoint, no new error code — the
  existing 409 and its code are reused.
- **Code**: `PipelineDeclaration`'s projection widens to carry `group_by` and `measures`;
  `TableCatalogService.rulesReadingEnrichment` and `TableSchemaService`'s
  `rulesReadingEnrichmentColumn` stop being ENRICH-scoped.
- **Depends on** `fold-rule-into-pipeline`: it is what makes `findAllDeclarations()` cross-kind.
  Sequenced immediately after it, in the same release or the next.
