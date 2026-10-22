## 1. Qualification predicate and entity flag

- [x] 1.1 Add `PartitionLocality` (package `service.query`) with `isPartitionLocal(TableDefinitionEntity table, TableDefinitionEntity source)` implementing the five rules in design D2, with Javadoc stating the invariant it rests on and the ClickHouse behaviour it neutralises
- [x] 1.2 Add the `partitionLocal` boolean component to `QueryEntityModel` (canonical constructor explicit, existing constructors default to `false`), and carry it through `withPrunedPaths`
- [x] 1.3 In `CatalogQueryService.buildModel`, compute `partitionLocal` as the source's locality AND every active enrichment's, pass it into the model, and log at INFO naming the first disqualifying table when an entity is not partition-local
- [x] 1.4 Unit tests for `PartitionLocality`: each rule with hand-built entities, the `dial_usage_log` + `dial_usage_log_payload` pair (aligned `request_time`), an unpartitioned enrichment, a non-aligned partitioned enrichment, an append source, and a partitioned upsert source whose partition column is outside its ordering key
- [x] 1.5 Extend `CatalogQueryServiceTest`: flag true for the aligned source+enrichment fixture; false once a non-qualifying enrichment is attached

## 2. Executors send the setting from the flag

- [x] 2.1 Add `clickhouse/FinalSettings` exposing the two setting names and their `getAllSettings()` keys, `QuerySettings query(boolean partitionLocal)` and `CommandSettings apply(CommandSettings, boolean partitionLocal)`
- [x] 2.2 `StructuredQueryExecutor`: replace `FINAL_SETTINGS`/`withFinal(extra)` with the shared holder keyed on `partitionLocal`; `execute(model, …)` uses `model.partitionLocal()` for the row and count statements; add `executeScan(select, extra, QueryEntityModel)` and `executeComposite(select, extra, boolean partitionLocal)`; keep the model-less overloads as cross-partition and update the class and method Javadoc to describe the rule
- [x] 2.3 `ClickHouseInsertSelectExecutor`: add `boolean partitionLocal` to both `insertSelect` overloads and build settings through the shared holder; update Javadoc
- [x] 2.4 `StructuredQueryExecutorTest`: assert the setting is present on row + count for a partition-local model and absent for a non-partition-local one; model-aware scan/composite overloads follow the flag; model-less scan withholds it; keep the existing final-key assertions
- [x] 2.5 New `ClickHouseInsertSelectExecutorTest`: mock the writer `Client`, capture `CommandSettings`, assert presence/absence of the setting for `partitionLocal` true/false alongside `final=1`
- [x] 2.6 `DialUsageLogTestModel.model()` (and siblings) build a partition-local model so the default test path is the positive one

## 3. Call sites hand over the flag

- [x] 3.1 `PipelineReadBudget.run(executor, select, what, budget)` gains a `QueryEntityModel model` parameter and forwards to the model-aware `executeScan`; update the seven call sites in `ScanService`, `GroupFetchService`, `MaterializeService`, `AggregateRefreshService`
- [x] 3.2 `AggregateRefreshService`: preview sample calls the model-aware `executeScan`; `refresh` passes `model.partitionLocal()` to `insertSelect`
- [x] 3.3 `MaterializeService.materialize`: pass `model.partitionLocal()` to `insertSelect`
- [x] 3.4 `StructuredQueryService.execute(CompositeQuery, int)`: wrap the entity resolver to AND `partitionLocal()` over every resolved entity and pass the result to `executeComposite`
- [x] 3.5 Adjust `AggregateRefreshServiceTest` and `GroupFetchServiceTest` stubs/verifications to the new `executeScan`/`insertSelect` arities

## 4. Spec and docs

- [x] 4.1 Confirm the delta spec at `openspec/changes/prune-partitions-under-final/specs/structured-query/spec.md` matches the shipped rule (entity-level, composite AND, five new scenarios) and that the SQL-translation paragraph no longer claims the setting stays at its default; `openspec validate prune-partitions-under-final --strict`
- [x] 4.2 `docs/clickhouse-server.md`: in the `compatibility` note that names `defer_partition_pruning_after_final`, add one sentence that ADAS sends `do_not_merge_across_partitions_select_final=1` for partition-local entities, so the payload table's pruning no longer depends on that default
- [x] 4.3 No `docs/configuration.md` change: this change adds no property (verify nothing was added)

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes (compile, checkstyle, tests)
- [ ] 5.2 Runtime check against a running deployment (or a local ClickHouse 26.3 with the payload table): with the change running, the six-hour `project_id` range query on `dial_usage_log_payload.request_body` returns rows through `POST /v1/queries/execute-sql` instead of `query_budget_exceeded`, and `system.query_log` shows the ADAS statements carrying `do_not_merge_across_partitions_select_final=1` in their `Settings`
