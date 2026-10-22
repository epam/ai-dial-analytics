## Context

Every read surface — the structured DSL (`POST /v1/queries/execute`), REST SQL
(`POST /v1/queries/execute-sql`), and ADBC/Flight SQL — funnels through one pipeline:
`StructuredQueryBuilder.build()` renders a jOOQ `SelectQuery` against a connection-less
`SQLDialect.CLICKHOUSE` context, `StructuredQueryExecutor` renders it to an inlined SQL string, and
`ClickHouseClientWrapper.query(sql)` runs it over the ClickHouse HTTP client. There is exactly one
`FROM` chokepoint (`StructuredQueryBuilder:78`) and one client method.

The source tables (`dial_usage_log`, `rate_analytics`) and every enrichment are `ReplacingMergeTree`.
That engine collapses rows sharing the sort key only on asynchronous background merges, so plain
`SELECT`s expose not-yet-merged duplicate versions. The catalog (`TableDefinitionEntity`) does **not**
record a table's engine, so the query layer cannot today tell which tables dedup.

Two duplication modes exist, and both are in scope (confirmed with stakeholder):
1. **Source-side**: re-ingested `dial_usage_log` / `rate_analytics` rows before merge.
2. **Enrichment-side fan-out**: a re-`PATCH`ed enrichment grain key with unmerged versions makes the
   LEFT JOIN emit >1 row per source row, violating the "0..1 cardinality never inflates rows"
   invariant that `StructuredQueryBuilder` and `CatalogQueryService` both rely on.

## Goals / Non-Goals

**Goals:**
- Reads return the deduplicated, latest-version-wins view for `ReplacingMergeTree` data.
- One mechanism covers the source table, all joined enrichments, and the `count(*)` total query.
- No new per-table engine metadata required in the catalog.
- No-op (no behavior/format change) for plain `MergeTree` user-created sources.

**Non-Goals:**
- No per-query opt-out / "raw rows" mode (may revisit later; out of scope now).
- No change to write/ingestion or to the ordering-key/version semantics of any table.
- No jOOQ SQL-rendering change (the `FROM` clause and the experimental-dialect renderings stay as-is).
- No forcing of physical merges; this is query-time collapse only.

## Decisions

### Decision 1: Apply `FINAL` via the query-level `final = 1` setting, not `FROM t FINAL` SQL

ClickHouse exposes a query setting `final` that, when set to `1`, applies the `FINAL` modifier to
every table in the statement whose engine supports it, and is ignored for engines that do not.

**Why over the alternatives:**
- **vs. rendering `FROM t FINAL` per table**: would require the catalog to know each table's engine
  (it does not today) and depends on jOOQ's *experimental* ClickHouse dialect rendering the `FINAL`
  keyword correctly (uncertain, per our own `QueryDslConfiguration` note). The setting sidesteps both.
- **Uniform coverage for free**: one setting deduplicates the source **and** every LEFT-JOINed
  enrichment **and** the separate `count(*)` query — no need to thread `FINAL` onto each table
  reference in `StructuredQueryBuilder`.
- **Safe on plain MergeTree**: the setting is a no-op there, so user-created sources are unaffected
  and we never mis-apply `FINAL` to a non-collapsing engine.
- **vs. manual `argMax(...) GROUP BY sort_key` rewrite**: far too complex to synthesize generically
  in the builder across arbitrary projections, joins, and aggregates; rejected.

### Decision 2: Carry the setting on the query path, not baked into the client globally

The `final = 1` setting is attached to the query-execution calls made by `StructuredQueryExecutor`
(both the row query and the `count(*)` query), rather than set once in `ClickHouseClientWrapper.buildClient()`.

**Why:** `ClickHouseClientWrapper` is the single component that talks to ClickHouse and is also used
by non-query paths (e.g. schema validation / `DESCRIBE`, data-sync reads). Scoping the setting to the
structured-query execution path keeps the deduplication guarantee where it is specified (the
`structured-query` capability) and avoids surprising unrelated system/DDL queries. Mechanically,
`query(...)` gains an overload accepting per-call query settings (e.g. `Map<String,Object>` or the
client's `QuerySettings`), and the executor passes `{final: 1}` on both calls. The default `query(sql)`
keeps today's behavior for other callers.

### Decision 3: Global, always-on; no opt-out

Per stakeholder: "latest wins" is always the desired contract for consumers, and global-on "looks good
for now and may be forever." So there is no DSL field, query param, or ADBC statement option to
disable dedup in this change. If a raw-rows use case emerges, a future change adds an explicit opt-in.

## Risks / Trade-offs

- **`count(*) FINAL` cost** → It must collapse all parts matching the filter, unlike the row query
  which reads a bounded `LIMIT` slice. Mitigation: the total count is only computed for row-mode
  offset paging with `include_total` (already conditional in `StructuredQueryExecutor.totalCount`),
  so most queries never pay it. Measure on `dial_usage_log`; consider
  `do_not_merge_across_partitions_select_final` to keep it partition-local.
- **General FINAL overhead on large, poorly-merged, unpartitioned tables** → Mitigation: the heavy
  tables in practice are partitioned (`dial_usage_log` monthly); plain-MergeTree user sources no-op;
  `max_final_threads` parallelizes. Document expected ~1.3–3× on healthy data.
- **Behavioral BREAKING change** → Counts/row totals drop for tables with unmerged duplicates.
  Mitigation: this is the intended correction; call it out in the spec and release notes so
  downstream consumers expect lower-but-correct numbers.
- **Setting name/type mismatch with the client version** → Mitigation: verify the ClickHouse Java
  client accepts `final` as a per-query setting; add an integration test (below) that proves dedup
  end-to-end against a live ClickHouse rather than trusting the setting silently.

## Migration Plan

Pure runtime behavior change — no schema migration, no data backfill. Deploy the service; reads begin
returning deduplicated results immediately. Rollback is a redeploy of the prior build (or, if the
setting is made configurable, disabling it). No persisted state changes, so rollback is safe at any
time.

## Open Questions

- Should the merge-related ClickHouse settings (`do_not_merge_across_partitions_select_final`,
  `max_final_threads`) be exposed as ADAS config in `docs/configuration.md`, or hard-coded? Lean:
  start hard-coded/defaults, expose only if performance testing shows a need.
