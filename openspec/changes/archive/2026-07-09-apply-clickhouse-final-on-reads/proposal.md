## Why

Consumers querying over ADAS (structured DSL, REST SQL, or ADBC/Flight SQL) see duplicate rows.
The underlying source and enrichment tables use the `ReplacingMergeTree` engine, which collapses
rows sharing a sort key only during asynchronous background merges that are never guaranteed to
complete. Until a merge runs, every not-yet-collapsed version is visible in query results. The
`data-sync` path makes this routine: re-ingestion deliberately re-inserts records and relies on the
engine to collapse them later, so duplicates are the normal steady state, not a rare edge case. The
querying surface currently emits plain `SELECT`s with no deduplication, so consumers get a raw,
partially-merged view instead of the "latest version wins" result they expect.

## What Changes

- Query reads SHALL return the deduplicated, latest-version-wins view of `ReplacingMergeTree` data
  by applying ClickHouse `FINAL` semantics to every read the query pipeline issues, via the
  query-level `final = 1` setting rather than per-table SQL rewriting.
- Because the setting is applied at execution time (not rendered into the jOOQ SQL), it covers the
  source table, every LEFT-JOINed enrichment table, and the `count(*)` total-count query uniformly,
  and it is a no-op on plain `MergeTree` tables (user-created sources) — so no per-table engine
  metadata is needed in the catalog.
- The enrichment LEFT-JOIN's documented "0..1 cardinality, never inflates source rows" invariant is
  now actually upheld under load: a re-`PATCH`ed enrichment key that has not yet merged no longer
  fans out the source rows, because the enrichment side is deduplicated by `FINAL` too.
- Dedup is global and always-on; there is no per-query opt-out in this change.
- **BREAKING** (behavioral): `count(*)` totals and returned row sets now reflect deduplicated data,
  so counts and row totals for tables with unmerged duplicates will drop relative to today's output.
  This is the intended correction, not a regression.

## Capabilities

### New Capabilities
<!-- none: this is a behavioral correction to existing query execution -->

### Modified Capabilities
- `structured-query`: the shared query execution and SQL-translation requirements gain a
  deduplication guarantee — reads apply ClickHouse `FINAL` (latest-version-wins) semantics to the
  source and all joined enrichments, and the enrichment-join cardinality invariant is stated as
  holding regardless of pending merges. This is the pipeline that the `rest-sql-endpoint` and
  `flight-sql-endpoint` surfaces both delegate to, so the guarantee applies to all three surfaces.

## Impact

- **Code**: `ClickHouseClientWrapper` (carry the `final = 1` setting on query-path executions);
  `StructuredQueryExecutor` (row query + `count(*)` both run under the setting). No change to the
  jOOQ rendering in `StructuredQueryBuilder` — the FROM clause stays as-is; `FINAL` is a runtime
  setting, not rendered SQL.
- **Surfaces**: `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`, and the Flight SQL /
  ADBC endpoint all return deduplicated results, since they share the executor and client.
- **Performance**: `FINAL` forces query-time merge of the touched parts. Cost is bounded by
  partitioning (`dial_usage_log` is partitioned monthly) and `do_not_merge_across_partitions_select_final`;
  the `count(*)` total-count query is the most expensive case. Acceptable for correctness; measured
  and documented in design.
- **Docs**: `docs/configuration.md` if the `FINAL`/merge-related settings become configurable;
  otherwise the behavior is documented in the spec only.
