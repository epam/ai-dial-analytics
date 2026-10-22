## Context

See proposal.md — Why. The relevant current state:

- `StructuredQueryExecutor.FINAL_SETTINGS` is the one holder of `final = 1` for every read; callers merge
  their own settings (budgets, `join_use_nulls`) *over* it through `withFinal(extra)`. Four entry points
  share it: `execute(model, query, maxLimit, extra)` (row + count), `executeScan(select, extra)`,
  `executeScan(select)` (no production caller) and `executeComposite(select, extra)`.
- `ClickHouseInsertSelectExecutor.insertSelect(target, sql[, budget])` sets `final = 1` a second time for
  the write side (Class-1 materialize, aggregate refresh). Both are `serverSetting` calls on the
  ClickHouse Java client's `QuerySettings` / `CommandSettings`, sent as HTTP query parameters.
- `EnrichmentJoins.addReferenced` is the single place that emits enrichment joins, for every builder
  (structured/SQL/Flight, scan, group fetch, materialize, aggregate compiler and probe). Which joins a
  statement carries is known only there, at build time; the executor receives a rendered `SelectQuery`
  or a SQL string.
- The catalog already holds every fact needed: `TableDefinitionEntity.orderingKey`, `partitionColumn`,
  `type`/`sourceTable`, `effectiveWriteMode()`, and `CatalogQueryService.alignedPartitionColumn`
  computes the partition-alignment claim when it builds `EnrichmentJoin`s.
- Composite (multi-entity SQL) statements resolve each entity through `StructuredQueryService.resolve`,
  passed as a function into `CompositeQueryBuilder`; synthetic join/derived models are built with empty
  enrichment lists.
- ClickHouse mechanics, verified on 26.3.24.4: `defer_partition_pruning_after_final` (new in 26.3,
  default 1) is what disables the Partition/MinMax indexes under FINAL for tables partitioned outside
  their sorting key; `enable_automatic_decision_for_merging_across_partitions_for_final` (26.2+, default
  1) already merges per partition for tables whose partition columns are in the primary key.
  `do_not_merge_across_partitions_select_final = 1` restores pruning for the payload table on 26.3 and is
  accepted by every supported server version.

## Goals / Non-Goals

**Goals:**

- One place decides "partition-local or not" for an entity, from catalog facts, and every executor path
  that sets `final = 1` also sends (or deliberately withholds) the second setting from that decision.
- Safe by default: a path that has no entity in hand keeps today's cross-partition behaviour.
- Coverage on the settings actually sent, mirroring the existing `StructuredQueryExecutorTest` style.

**Non-Goals:**

- Per-statement precision (qualifying by the enrichments a statement *references* rather than all active
  enrichments of the entity). See D1.
- Creation-time validation or warnings for `upsert_by_key` tables whose partition column is not in the
  ordering key. Such a table simply does not qualify.
- Re-keying the payload table to `ORDER BY (event_id, request_time)`, which would make ClickHouse infer
  the rule itself. Measured and rejected for now: `ALTER … MODIFY ORDER BY` refuses an existing column
  (`BAD_ARGUMENTS`), so it is a full rewrite — on the order of 100 GiB on disk, which can exceed the free space, so it must go
  partition by partition with drop-as-you-go, about an hour at the throttled rate that stayed under 700
  MB of memory, with ADAS and the runner scaled to zero, and a guarded Java Flyway migration so startup
  neither re-copies nor rewrites a live table. The setting gives the same plan for free; the re-key
  remains the right move if the table is ever rebuilt anyway.
- Sending `defer_partition_pruning_after_final = 0` instead. Same effect on 26.3, but the setting does
  not exist before 26.3 and ClickHouse rejects unknown settings, which would break every read on the
  25.x/26.2 servers `docs/clickhouse-server.md` supports.
- Any change to how ADAS maps ClickHouse code 241 to `query_budget_exceeded` (the "(total)" vs
  "(for query)" distinction). Worth its own change.
- Forcing background merges to one part per partition.

## Decisions

### D1 — Decide per entity, carried on `QueryEntityModel`, not per statement

`QueryEntityModel` gains a boolean component, `partitionLocal`: true when the source and **every** active
enrichment in the model are partition-local. `CatalogQueryService.buildModel` computes it from the
`TableDefinitionEntity`s it already iterates. Existing constructors default it to `false` so the ~26 test
construction sites and the synthetic composite models keep compiling with the safe value; the canonical
constructor takes it explicitly, and `withPrunedPaths` copies it through.

*Alternative rejected — per statement.* Precise qualification (only the enrichments a statement joins)
needs the joined set to travel from `EnrichmentJoins.addReferenced` to the executor, across ten call
sites in six classes, or a second return value from every `addReferenced` overload. Today the two rules
give the same answer on every table in the standard catalog (all partitioned upsert tables qualify, all
other enrichments are unpartitioned); the only case where they differ is a hypothetical non-qualifying
user enrichment attached to `dial_usage_log`, which costs that entity the optimisation but never a wrong
row. The spec is written for the entity-level rule so a later refinement to per-statement is a pure
relaxation with no spec break.

*Alternative rejected — send unconditionally and forbid non-qualifying declarations.* Turns a
performance knob into a schema restriction on user tables for no benefit today.

*Alternative rejected — a ClickHouse settings profile for the ADAS users.* Environment-specific, invisible
to the spec and tests, and it would be unconditional.

### D2 — Qualification predicate lives in one small type

A `PartitionLocality` helper (package `service.query`, beside `EnrichmentJoin(s)`) with
`isPartitionLocal(TableDefinitionEntity table, TableDefinitionEntity source)`:

1. `table.effectiveWriteMode() == APPEND` → true (plain `MergeTree`, nothing to collapse);
2. `partitionColumn == null` → true;
3. `orderingKey.contains(partitionColumn)` → true;
4. `type == ENRICHMENT && partitionColumn.equals(source.partitionColumn) && isPartitionLocal(source, null)`
   → true (the alignment claim, same equality `alignedPartitionColumn` already uses);
5. otherwise false.

Pure, static, unit-testable in isolation with entities built by hand. `CatalogQueryService` calls it for
the source and each enrichment, ANDs the results, and logs at INFO naming the first disqualifying table
when an entity is not partition-local.

### D3 — Both executors derive the two settings from one shared holder

A `clickhouse/FinalSettings` class owns the two setting names and builds them for both driver types:
`QuerySettings query(boolean partitionLocal)` and `CommandSettings apply(CommandSettings, boolean
partitionLocal)`. `StructuredQueryExecutor` drops its `FINAL_SETTINGS` constant; `withFinal(extra)`
becomes `withFinal(extra, partitionLocal)`; `execute(model, …)` reads `model.partitionLocal()`. The scan
and composite entry points gain overloads — `executeScan(select, extra, QueryEntityModel)` and
`executeComposite(select, extra, boolean partitionLocal)` — while the existing signatures keep meaning
"cross-partition" for call sites with no model.

`ClickHouseInsertSelectExecutor.insertSelect` gains a `boolean partitionLocal` parameter on both
overloads.

*Alternative rejected — callers add the setting via `extra`.* It works mechanically because `extra`
merges over the standing settings, but it moves the FINAL decision out of the executor, which the spec
and the current Javadoc deliberately keep there ("a query that silently dropped `final = 1` would change
its numbers").

### D4 — Pipeline and composite call sites pass what they already hold

`PipelineReadBudget.run(executor, select, what, budget)` gains a `QueryEntityModel model` parameter and
forwards it to the model-aware `executeScan`. Its seven call sites (`ScanService`, `GroupFetchService`
×2, `MaterializeService` ×3, `AggregateRefreshService`) all hold the model they called
`EnrichmentJoins.addReferenced` with. `AggregateRefreshService`'s preview sample calls the model-aware
`executeScan`; `MaterializeService.materialize` and `AggregateRefreshService.refresh` pass
`model.partitionLocal()` to `insertSelect`.

`StructuredQueryService.execute(CompositeQuery, …)` wraps the resolver it hands the composite builder so
it ANDs `partitionLocal()` over every entity resolved for the plan, and passes the result to
`executeComposite`.

### D5 — Tests assert on the settings map, as the existing suite does

`StructuredQueryExecutorTest` already captures `QuerySettings` and asserts on
`ClientConfigProperties.serverSetting("final")`. Add the sibling key and:

- row + count both carry the setting for a partition-local model, both withhold it for a
  non-partition-local one;
- `executeScan(select, extra, model)` and `executeComposite` follow the flag; the model-less
  `executeScan` overloads withhold it;
- `ClickHouseInsertSelectExecutor` carries/withholds it (capture `CommandSettings` on a mocked writer
  `Client`, as `UsageLogWriterTest` mocks it);
- `PartitionLocality` unit tests: the five rules with hand-built entities, including the
  `dial_usage_log` + `dial_usage_log_payload` pair and a non-aligned enrichment;
- `CatalogQueryServiceTest`: the model flag is true for the aligned fixture and false once a
  non-qualifying enrichment is added;
- `DialUsageLogTestModel.model()` builds a partition-local model so builder/executor tests exercise the
  positive path by default; `AggregateRefreshServiceTest` and `GroupFetchServiceTest` stubs move to the
  new `executeScan`/`insertSelect` arities.

## Risks / Trade-offs

- [The invariant is asserted by the catalog, not checked against data] → It is the same invariant the
  keyed join already depends on for correctness (a misaligned enrichment silently drops joined rows there
  too), so this change adds no new trust assumption; the spec now states it explicitly. The operator
  check is `SELECT count() FROM (SELECT key, uniqExact(_partition_id) p FROM t GROUP BY key HAVING p > 1)`,
  which returned 0 for both partitioned upsert tables on a running deployment.
- [A future non-qualifying enrichment on `dial_usage_log` silently reinstates the 1.6 GiB reads] →
  The INFO log in `CatalogQueryService` names the disqualifying table; per-statement precision
  (Non-Goal) is the follow-up if it ever bites.
- [ClickHouse semantics drift] → The setting is documented and stable since 20.x; 26.2 added the
  automatic decision and 26.3 the deferred pruning on top of it rather than replacing it. No ADAS table
  uses `is_deleted`, the one documented interaction.
- [Sending the setting on unpartitioned tables] → A no-op by definition (one partition). Verified the
  within-partition dedup on a partition holding thousands of duplicate versions returns one row per key.
- [Signature churn] → `PipelineReadBudget.run`, `insertSelect` and two executor entry points gain a
  parameter; ~12 call sites, all mechanical, all compile-checked. No behaviour change where
  `partitionLocal` is false.

## Migration Plan

No migration, config or data step. Deploy as an ordinary ADAS release; the effect is immediate on the
next read. Rollback is the previous image. Verification against a running deployment after deploy: replay the six-hour
`project_id` range query on `dial_usage_log_payload.request_body` through `execute-sql` (was
`query_budget_exceeded`, should return 10 rows), and watch `system.query_log` for code 241 from
`adas_reader`/`adas_writer` dropping to zero during a runner group-fetch burst.
