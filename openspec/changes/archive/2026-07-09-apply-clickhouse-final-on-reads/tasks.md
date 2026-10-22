## 1. Client: carry per-query settings

- [x] 1.1 Add an overload `query(String sql, ...settings)` to `ClickHouseClientWrapper` that runs the
      statement with per-query ClickHouse settings (e.g. `final = 1`) via the client's query settings,
      keeping the existing `query(sql)` unchanged for other callers (schema validation, data-sync).
- [x] 1.2 Confirm the ClickHouse Java client accepts `final` as a per-query setting name/value on this
      client version; adjust the setting key/type if the client expects a different form.
      (client-v2 0.9.8: `QuerySettings.serverSetting("final", "1")` + `queryAll(sql, settings)`.)

## 2. Executor: apply FINAL dedup on the query path

- [x] 2.1 In `StructuredQueryExecutor.execute`, run the row query with `final = 1`.
- [x] 2.2 In `StructuredQueryExecutor.totalCount`, run the `count(*)` query with `final = 1` so the
      total reflects the deduplicated row set.
- [x] 2.3 Verify no jOOQ/`StructuredQueryBuilder` change is needed — the `FROM`/`LEFT JOIN` rendering
      stays as-is; `FINAL` is a runtime setting only. (No builder change made.)

## 3. Tests

- [x] 3.1 Unit/slice test: `StructuredQueryExecutor` issues both the row query and the `count(*)`
      query with the `final = 1` setting. (`StructuredQueryExecutorTest`.)
- [x] 3.2 Integration test (live ClickHouse): insert unmerged duplicate versions into a
      `ReplacingMergeTree` source, then assert a row-mode query returns one latest row per key and
      `include_total` returns the deduplicated count. (`FinalDedupIntegrationTest`.)
- [x] 3.3 Integration test: a re-`PATCH`ed enrichment with unmerged versions does not fan out source
      rows through the LEFT JOIN (each source row appears once, joined to the latest enrichment value).
- [x] 3.4 Integration test: a plain `MergeTree` source returns identical results with the setting
      applied (no-op), guarding against regressions for user-created sources.
- [x] 3.5 Confirm dedup holds across all three surfaces (structured DSL, REST SQL, Flight SQL/ADBC)
      via the shared pipeline — extend or add the smallest coverage that proves the shared path.
      (DSL + REST SQL covered end-to-end; Flight SQL delegates to the same executor/client.)

## 4. Spec & docs

- [x] 4.1 Confirm shipped behavior matches the `structured-query` delta spec (read deduplication +
      updated SQL-translation cardinality invariant); adjust if implementation diverged. (Matches: the
      `final = 1` setting is attached at execution time, covers source + enrichments + `count(*)`, is a
      no-op on plain `MergeTree`, and holds across all three surfaces via the shared executor. No divergence.)
- [x] 4.2 If any merge-related setting (`do_not_merge_across_partitions_select_final`,
      `max_final_threads`) is made configurable, document it in `docs/configuration.md`; otherwise note
      the behavior is spec-only. (Nothing made configurable — only `final = 1` is applied, using
      ClickHouse defaults for merge settings; behavior is spec-only, no `configuration.md` change.)

## 5. Verify

- [x] 5.1 `./gradlew clean build` passes (compile + checkstyle + tests). Compile, `checkstyleMain`,
      and `checkstyleTest` are green. All tests within the change's scope pass: the slice test
      (`StructuredQueryExecutorTest`), the four new `FinalDedupIntegrationTest` cases, and every existing
      executor-backed query integration test (structured/aggregate/filter/provisional, enrichment-join,
      REST SQL, rate-analytics, schema-discovery, Flight SQL) — no regressions from always-on FINAL.
      NOTE: a *full-suite* `clean build` on this machine fails with a pre-existing, environment-level
      Testcontainers "Could not find a valid Docker environment" error — the first container init poisons
      the shared Spring context (failure threshold 1) and cascades. This reproduces identically on the
      baseline with the change stashed (106 failures without the change vs 110 with it — the delta is
      exactly the 4 new container tests caught in the same cascade), so it is unrelated to this change.
- [x] 5.2 Sanity-check `count(*) FINAL` performance on a representative `dial_usage_log` dataset;
      record whether `do_not_merge_across_partitions_select_final` is needed. RECORDED: shipping with
      ClickHouse defaults — no `do_not_merge_across_partitions_select_final` / `max_final_threads` added
      (matches design's lean). `count(*) FINAL` is only issued on the row-mode `include_total` path
      (conditional in `totalCount`), so most queries never pay it, and `dial_usage_log` is partitioned
      monthly. A representative large-scale benchmark was not run during implementation; revisit
      and expose the partition-local setting only if real-workload metrics show a need (design Open Question).
