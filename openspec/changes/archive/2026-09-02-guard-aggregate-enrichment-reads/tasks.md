## 1. The aggregate side's answer to the catalog's two questions

- [x] 1.1 Extract `QualifiedColumnTokens` from `EnrichSpecValidator` — the `table.column` scanner and its
      pattern — so both kinds' guards read stored fragments with one implementation rather than two copies
      that could drift. `EnrichSpecValidator` delegates to it; behaviour unchanged.
- [x] 1.2 Add `AggregateSpecValidator.declaredFieldsOf(PipelineDeclaration)`: the group keys' input columns
      (a `trunc` key names its column in the same place a plain one does), the measures' columns, and the
      qualified tokens in the membership filter and in each measure's own `where`.
- [x] 1.3 Add `AggregateSpecValidator.referencedEnrichments(PipelineDeclaration)` — those fields filtered
      through the input entity's `isEnrichmentSourced`. **Not** the existing overload that runs the SQL
      front end (design D-2); a declaration whose input no longer resolves contributes nothing rather than
      throwing.
- [x] 1.4 Inject `PipelineSpecCodec` so the stored declaration can be decoded from the projection's raw
      `spec`.

## 2. The guard

- [x] 2.1 `PipelineGenerationService.declarations()` becomes `findAllDeclarations()` — every kind — and its
      javadoc records the live gap this closes rather than the scoping it had.
- [x] 2.2 `pipelinesReadingEnrichment` and `pipelinesReadingEnrichmentColumn` dispatch on
      `declaration.kind()` to the gate that knows where that kind names its columns.
- [x] 2.3 No message change: both 409s already name the pipelines and say "through their input's grain
      join", which is true of either kind.

## 3. Tests

- [x] 3.1 Deleting an enrichment a rollup **groups on** is refused, naming the pipeline — the case
      `sessions_rollup` / `usage_client_identity` shows is unguarded today.
- [x] 3.2 The enrichment becomes deletable once the rollup is deleted: the guard is a dependency, not a
      lock.
- [x] 3.3 Dropping **and** renaming the enrichment column the rollup names are both refused.
- [x] 3.4 An unreferenced column of the same enrichment stays droppable (design D-3) — the guard that is
      too broad is as real a failure as the one that is too narrow.
- [x] 3.5 A measure's `where` and the pipeline `filter` are scanned too, not only the group keys: add a
      declaration that reads an enrichment column **only** from a measure's `where` and assert the same
      refusal. Without it the extraction could cover the grain and quietly miss the predicate.
- [x] 3.6 A declaration whose input has drifted contributes no references rather than failing the delete —
      the isolation half of design D-2, and the one a future refactor is most likely to break.

## 4. Specs

- [x] 4.1 `table-management` MODIFIED: "Delete a table definition" and "Modify table schema via PATCH" —
      the enrichment-read guard becomes declaration-kind-independent. Both are MODIFIED by
      `fold-rule-into-pipeline` first, so this delta's text builds on **that** version, not on the
      current main spec.
- [x] 4.2 `aggregate-pipeline` MODIFIED: "An aggregate pipeline reads its input entity, not only its input
      table" gains the protection half — the enrichments a declaration reads are dependencies, protected
      against drop and rename, which the enrich declaration's equivalent requirement has always had.
- [x] 4.3 `openspec validate guard-aggregate-enrichment-reads --strict`.

## 5. Verification

- [x] 5.1 `./gradlew clean build`.
- [ ] 5.2 On each deployment, before deploying: list the enrichments that would become undeletable, so a 409 on a
      table an operator was about to drop is expected rather than a surprise.
