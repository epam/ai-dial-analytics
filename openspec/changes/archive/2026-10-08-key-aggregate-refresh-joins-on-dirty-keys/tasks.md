## 1. Join emission

- [x] 1.1 Add a per-enrichment restriction parameter (enrichment table → `Condition` over that enrichment's
      columns) to the most general `EnrichmentJoins.addReferenced` overload only, plus one pipeline-facing
      overload that passes it. Every existing overload keeps its signature and passes none
- [x] 1.2 In `EnrichmentJoins`, emit a restricted enrichment as a derived table whose `WHERE` is the restriction
      **alone**: no grain-key `IN (SELECT … FROM source …)` and no partition restriction, whether or not source
      predicates were passed. An unrestricted enrichment is emitted exactly as today (design D3)
- [x] 1.3 Throw `IllegalStateException` when a restriction is passed for a join that is not `keyable()`, rather
      than falling back to the bare join; the compiler requests a restriction only for a keyable join (design D3)
- [x] 1.4 Update the `EnrichmentJoins` javadocs:
      - the class note's third shape, by the enrichment's own columns, which is not keying;
      - "with no predicates … the bare LEFT JOIN" (:49-51);
      - `addReferenced` (:138-140);
      - `keyed` (:180-183);
      - `selects` (:230-232), "the one restriction shape"

## 2. Refresh compilation

- [x] 2.1 In `AggregateCompiler`, extract the dirty-key sub-select into one helper used both for the outer tuple
      `IN` and for the restriction, so the two render the same text
- [x] 2.2 In `compileRefresh`, restrict an enrichment when every group key (plain or time bucket) reads a column
      of that one enrichment and no group key's binding is an `array`, `map` or `object` type: restriction =
      `(<group fields>) IN (<dirty-key sub-select>)`. Mixed, two-enrichment and source-only grains pass no
      restriction (design D2, D5). The probe, the dirty-key sub-select, the bucket bounds and the preview
      compile unchanged
- [x] 2.3 Add `transform_null_in = 0` to `FinalSettings.pipeline` (design D4)
- [x] 2.4 Rewrite the comment at `AggregateCompiler.java:132-137` and the class javadoc (:69-73) to state what
      the recompute's joins are restricted or keyed on now, and why the window predicates still are not
- [x] 2.5 Fix stale comments in code this change touches:
      - `AggregateSpecValidator.java:599`: "none of the three can be a group key" is false for `array`;
      - `ClickHouseBudget.java:73-80`: the aggregate refresh write is budgeted too, and it groups

## 3. Unit tests

- [x] 3.1 Add a keyable enrichment test model (e.g. `DialUsageLogTestModel.modelWithKeyedEnrichment()`).
      `modelWithEnrichment()` uses the opaque-condition constructor, is not keyable, and is shared by five test
      classes, so leave it as it is
- [x] 3.2 `AggregateCompilerTest`: a grain on one enrichment column compiles the restriction inside that
      enrichment's derived table, with the same dirty-key sub-select text as the outer `IN`, no grain-key or
      partition `IN`, and every referenced measure column projected
- [x] 3.3 `AggregateCompilerTest`: a time bucket over an enrichment column restricts by the bucket expression
- [x] 3.4 `AggregateCompilerTest`: an enrichment grain with a source-only filter carries the restriction alone,
      with no filter-keyed `IN`
- [x] 3.5 `AggregateCompilerTest`, each on the keyed model, compiles byte-identical SQL to before:
      - a mixed grain;
      - a source-only grain with an enrichment filter;
      - an `array` enrichment key;
      - a second enrichment read only by a measure, joined as before beside a restricted one;
      - the probe, the dirty-key sub-select, the bounds and the preview sample, for every grain
- [x] 3.6 `EnrichmentJoinsPruningTest` (or a sibling):
      - a restriction alone emits the derived table with only that conjunct;
      - a restriction plus source predicates still emits only the restriction;
      - no restriction leaves every existing statement byte-identical;
      - a restriction on a non-keyable join throws
- [x] 3.7 `ClickHouseInsertSelectExecutorTest` and a `FinalSettings` test: pipeline settings carry
      `transform_null_in = 0`

## 4. Integration test

- [x] 4.1 Extend `AggregateEnrichmentIntegrationTest` with a rollup grouped by an enrichment column. Its fixture
      holds:
      - rows of dirty keys;
      - rows whose enrichment key is not dirty;
      - source rows with no enrichment row;
      - an enrichment row with a NULL key;
      - an enrichment `ReplacingMergeTree` pair whose superseded version carries a different key, kept unmerged
        with `SYSTEM STOP MERGES` as `PayloadForceMergeIntegrationTest` does
- [x] 4.2 Assert the refreshed target equals a brute-force full recompute over the same fixture
- [x] 4.3 Assert the join is bounded. After `SYSTEM FLUSH LOGS`, the refresh write's
      `ProfileEvents['JoinBuildTableRowCount']` must be far below the enrichment's row count, and below the same
      statement compiled unrestricted. The event sums every join in the statement, including the dirty-key
      sub-selects' own joins, so do not assert equality with the dirty rows
- [x] 4.4 Show the pin is load-bearing. Use a second declaration whose `filter` admits NULL keys: no filter, or
      a source-only one. The existing fixture filter `length(...) > 0` removes NULL from the dirty set. Strip the
      `INSERT INTO … (…)` prefix from the compiled SQL and run the SELECT with `final = 1` and
      `transform_null_in = 1`: it must diverge from the brute-force result. Run it with the pipeline's own
      settings and assert it matches

## 5. Spec and docs

- [x] 5.1 Confirm the delta spec matches the shipped behaviour (restriction rule, `transform_null_in`, cost
      guidance), and run `openspec validate key-aggregate-refresh-joins-on-dirty-keys --strict`
- [x] 5.2 Update the ClickHouse memory plan:
      - mark F1 implemented;
      - drop its non-nullable-key branch and its "preview and bound sub-selects should get the same keying"
        line;
      - leave the source-scan and cursor-seek follow-ups open.

      No property is added, so `docs/configuration.md` is unchanged

## 6. Verification

- [x] 6.1 Verify locally first on `local_env`. It runs ClickHouse 26.8, the same as the integration tests, while
      a deployment may run 26.3, so repeat the checks on a throwaway `clickhouse/clickhouse-server:26.3` container:
      - run `sessions_rollup`-shaped refreshes;
      - compare the target's rows and the refresh write's `memory_usage` before and after;
      - repeat the superseded-version case by hand
- [x] 6.2 `./gradlew clean build` (Docker required for the integration tests)
      is green
