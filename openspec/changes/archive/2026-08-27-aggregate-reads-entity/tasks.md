## 1. Measure the join cost before building on it

**Done — the change proceeds.** Measured against a populated deployment: a source of millions of rows and
an enrichment covering a minority of them (event grain, both growing daily). The motivating rollup is
unbucketed, so its input is already fully scanned every tick; the join adds a fraction more rows to a scan
that was happening anyway, at `cron */15`. The reasoning is in `design.md` → Risks.

- [x] 1.1 Size both sides of the join and establish the enrichment's grain and growth rate.
- [x] 1.2 Establish whether the window bounds the right side. It does not — but the motivating grain has
      no time bucket, so the left side is unbounded too and the join's marginal cost is a fraction of a
      full scan the capability already documents and accepts.
- [x] 1.3 Record the measurement in `design.md` and decide. **Proceed as specified**, with one change of
      aim: the cost guidance targets *bucketed* enrichment-fed rollups, where the bound prunes the source
      but not the enrichment, rather than the unbucketed shape measured here.

## 2. Entity resolution in validation

- [x] 2.1 Add an entity-model accessor to `ScanSourceMetadata` that returns the catalog model with its
      enrichments intact, leaving `sourceModel` and its enrichment-stripping contract untouched
      (design D-1). Assert in a test that `sourceModel` still returns an empty enrichment list, so the
      Class-1 scan, group-assembly and materialize paths are provably unaffected.
- [x] 2.2 Rebuild `AggregateSpecValidator.Columns` as an entity-wide view keyed by flat name — source
      columns under their bare exposed name, enrichment columns under `"<enrichment>.<column>"` — each
      entry carrying its owning table alongside its `ColumnMappingEntity` (design D-2).
- [x] 2.3 Make the view report an enrichment-sourced column as nullable regardless of its declared flag
      (design D-3), so the measure and group-key null rules inherit the behaviour with no
      enrichment-specific branch of their own.
- [x] 2.3a **Refuse a value-producing reference to a non-nullable enrichment column** (design D-3a). Found
      by review: `join_use_nulls` defaults to 0, so an unmatched join renders such a column as its type
      default, not NULL — which defeats 2.3 and every rule built on it in exactly the case they were written
      for. Predicates stay exempt; `array`/`map` stay exempt. The test fixture that declared enrichment
      columns non-nullable "deliberately, because the join makes them nullable regardless" is corrected.
- [x] 2.3b **Refuse a plain enrichment group key with no `as`** (design D-3b), since its qualified flat name
      can never be a target column name and the downstream refusal blamed the target instead.
- [x] 2.4 Resolve `group_by[].column`, `group_by[].trunc.col` and `measures[].column` against the view
      instead of the input table's declared columns, keeping the "input must be a source" rule and its
      message exactly as they are.
- [x] 2.5 Resolve `filter` and `measures[].where` against the same field set, so a predicate can name an
      enrichment column by its qualified flat name.
- [x] 2.6 Make the unknown-field refusals name the enrichment as well as the column when a qualified
      name resolves to a known enrichment but an unknown column, so a typo in either half is
      diagnosable.
- [x] 2.7 Confirm the target-binding checks that read through the view — type match, array element type,
      bucketability — answer correctly for an enrichment-sourced column rather than silently answering
      `null`.

## 3. Governance across the grain join

Prerequisite, not polish: `SystemIdentity`'s `FULL_ADMIN` elevation is justified by rule 3.2 being total
(design D-4).

- [x] 3.1 Call `SensitiveProjectionGuard.enforce` per owning table over the referenced field set, so a
      non-entitled caller referencing a sensitive enrichment column is refused with 403 exactly as for a
      sensitive source column.
- [x] 3.1a Extend `SensitiveProjectionGuard.reattribute` to consider the source's enrichments' columns for
      the pipeline path. Found by review: a sensitive enrichment column named in a `filter` fails first as
      an unknown column (the model is role-filtered), and the re-attribution asked only about the input
      source — so the refusal surfaced as 422 whenever the source declared no sensitive column of its own,
      where the contract promises 403. Rule-scoped callers keep the old behaviour, for which 422 is correct.
- [x] 3.1b Name the owning table's *kind* in the 403, so a refusal over an enrichment column stops calling
      the enrichment a "source".
- [x] 3.2 Extend the sensitive-target rule: a measure or group key reading a `sensitive` enrichment
      column into a target column not itself declared `sensitive` is rejected with 422 naming both
      columns.
- [x] 3.3 Re-read `SystemIdentity`'s javadoc and confirm its stated justification is still true of the
      shipped code; update its wording if the rule it names has moved.

## 4. Join emission

- [x] 4.1 Extract `StructuredQueryBuilder.addReferencedJoins` into a shared helper and have the read
      path call it, unchanged in behaviour (design D-5).
- [x] 4.2 Derive the referenced-field set **per statement** rather than once per compile, per the table
      in design D-5 — a `filter` reaches every statement, a group key reaches the recompute, the
      dirty-key select and the bounds, a measure reaches only the recompute.
- [x] 4.3 Apply the helper in `AggregateCompiler.compileRefresh` to the recompute select, the
      `dirtyKeys` sub-select and both `dirtyBucketBound` sub-queries.
- [x] 4.4 Apply the helper in `AggregateCompiler.compilePreview`, so preview shows the joins a refresh
      would run.
- [x] 4.5 Apply the helper in `AggregateRefreshService.probe`, which composes its statement outside the
      compiler and joins only when the `filter` references an enrichment.
- [x] 4.6 Switch the pipeline path from `sourceModel` to the entity accessor from 2.1, so the
      enrichments the helper reads are actually present.

## 5. Freshness clamp

**MOVED to `aggregate-enrichment-freshness` — not dropped, and no longer blocked.** The
blocker was that ADAS stores no enrichment writer cursor: the scan cursor is opaque, ADAS-issued and
persisted by the *runner* (`analytics-enrichment-runner`, table `rule_cursor`), marking what was enqueued
for evaluation rather than written back. The successor change dissolves it — a Class-1 materialize is
synchronous inside ADAS, so ADAS records the position it wrote through itself. Groups 5, 8.9 and 8.10 are
that change's groups 2, 3, 5 and 7; the clamp requirement and the state requirement's clamp clause have
been removed from this change's delta and now live in its delta instead. See design D-6.


- [~] 5.1 (moved) Resolve the writer cursor of every enrichment a declaration references, at refresh time.
- [~] 5.2 (moved) Clamp the probed window's end to the slowest referenced enrichment's position, per refresh
      rather than per enrichment (design Q-3), and let the refresh proceed over the clamped window
      instead of skipping it.
- [~] 5.3 (moved) Leave a refresh that joins nothing entirely unaffected — same window, same predicates, same
      SQL.
- [~] 5.4 (moved) Surface the clamp in pipeline state: that the window was clamped, and which enrichment bounded
      it (design D-7). Extend the state DTO and `PipelineStateWriter` accordingly.

## 6. The mutation obligation

- [x] 6.1 Confirm the two rebuild shapes work against existing API and add no code (design D-8): delete
      + re-register resets the cursor, and recreating the target clears orphaned keys. An integration
      test asserting a re-registered pipeline starts with a null cursor is the whole of this task.
- [x] 6.2 Document, against the enrichment surface, that re-running or backfilling an enrichment
      invalidates every rollup reading it until rebuilt, and which of the two rebuilds the re-run calls
      for — orphaned keys only arise where the re-run changed values that were already aggregated.

## 7. Documentation

- [x] 7.1 Add the measured enrichment-join cost from task 1 to the capability's cost guidance, alongside
      the existing unbucketed-grain cost. Aim it at the **bucketed** case: a time bucket prunes the input
      but not the joined enrichment, so it makes a rollup cheap on one side only — which inverts what the
      existing guidance leads a declarer to expect.
- [x] 7.2 Record in `README.md` prose, linking to the guidance, that an aggregate pipeline may read its
      input's enrichments — no env-var table, no `application.yml` comment. No config property is added
      by this change, so `docs/configuration.md` gains only the cost guidance, not a property row.
- [x] 7.3 Note the operational grant: the writer credential needs `SELECT` on enrichment tables a
      pipeline references, not only on its input source.

## 8. Tests

- [x] 8.1 `AggregateCompilerTest`: the rendered recompute statement carries the `LEFT JOIN` when a group
      key or measure references an enrichment column, and carries none when nothing does.
- [x] 8.2 `AggregateCompilerTest`: the dirty-key sub-select and both bucket bounds carry the join when
      the group key or `filter` needs it — asserted on the rendered SQL, since this is the case that
      fails at execution rather than at compile.
- [x] 8.3 `AggregateCompilerTest`: a declaration referencing an enrichment only in a measure leaves the
      probe unjoined, so the cheapest statement does not pay for a reference it does not carry.
- [x] 8.4 Regression: an existing rollup referencing no enrichment compiles identical SQL whether or not
      its model carries enrichments. (Note this is model-independence under the new code, which is the
      strongest form testable in-tree — it is a proxy for "identical to what it compiled before", not that
      claim itself.)
- [x] 8.5 `AggregatePipelineIntegrationTest`: register → preview → enable → refresh a rollup grouped by
      an enrichment column, and assert the rows land keyed by the enrichment's values.
- [x] 8.6 Integration: the pipeline path and the query path report the same value for the same
      enrichment column over the same rows.
- [x] 8.7 Negative: non-nullable target for an enrichment-sourced measure → 422; enrichment-sourced
      group key with no `filter` → 422; sensitive enrichment column into a plain target column → 422
      naming both; enrichment named in `inputs` → 422.
- [x] 8.7a Negative (design D-3a/D-3b): a non-nullable enrichment column as a group key → 422, as a measure
      column → 422, but in a `filter` → accepted; an enrichment group key with no `as` → 422.
- [x] 8.7b `AggregateRefreshServiceTest`: the probe **does** carry the join when the membership filter reads
      an enrichment. Only the negative half was covered; the positive half existed solely in an integration
      test that cannot run without Docker.
- [x] 8.8 Negative: a non-entitled caller referencing a sensitive enrichment column → 403, and no
      pipeline stored.
- [~] 8.9 (moved with group 5) Freshness: source rows ingested ahead of their enrichment rows produce **no** wrong row — the
      window clamps, and the group is written correctly on a later tick once the enrichment catches up.
- [~] 8.10 (moved with group 5) Freshness: the clamp is reported in `GET /v1/pipelines/{name}`, naming the enrichment that
      bounded the window.

## 9. Verification

- [x] 9.1 Confirm the delta spec in `openspec/changes/aggregate-reads-entity/specs/` matches the shipped
      behaviour, and that no requirement was implemented differently from how it is written. **Done, and
      the delta now carries no unimplemented requirement:** the freshness-clamp requirement and the state
      requirement's clamp clause were *removed* and moved to `aggregate-enrichment-freshness`, which
      specifies and builds them against a materialized position. Everything else was checked, and three
      mismatches were fixed rather than documented (3.1a; the nullability premise in 2.3a; and the
      overstated row-loss framing corrected against a live ClickHouse — see the note on 9.2).
- [x] 9.2 `./gradlew clean build` passes — compile, checkstyle and the full test suite. **Green:
      0 failures, 0 errors, 0 skipped**, integration suite
      included (`AggregateEnrichmentIntegrationTest` 6/6, `AggregatePipelineIntegrationTest` 21/21,
      `FinalDedupIntegrationTest` 4/4, `AggregatePreviewBudgetIntegrationTest` 2/2). The new refusals from
      2.3a/2.3b do not affect the existing integration declarations, which already declare their enrichment
      columns nullable and alias their group key. `inputRowTheEnrichmentHasNotReachedIsExcludedByTheFilter`
      still passes and now carries a comment saying it pins *current* behaviour, not desired behaviour, and
      must be rewritten when the clamp lands (task 8.9).
