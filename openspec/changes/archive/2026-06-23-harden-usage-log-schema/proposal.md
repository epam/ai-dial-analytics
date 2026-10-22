## Why

The `dial_usage_log` schema faithfully captures every field the ai-dial-core producer
(`GfLogStore`) writes, but it has semantic-consistency gaps that mislead downstream analytics:
the timestamp's UTC interpretation is unpinned (depends on server timezone), costs round through
`Float64`, and — because the mapping engine substitutes a type-default (`0`/`""`) for any field
the producer omits — a missing `token_usage` block is indistinguishable from a genuine zero.

Separately, deduplication is keyed on `_source_file` (whole-file granularity), which cannot
deduplicate at the record level: a record re-delivered in a different blob, or a partially-ingested
file, produces duplicates. The natural uniqueness key for a usage record is the producer's
`trace_id` + `core_span_id` (one proxy request = one span = one log entry).

## What Changes

- **BREAKING (operator migration): dedup moves from file-level to row-level.** The target table
  becomes `ReplacingMergeTree(_ingested_at)` keyed on `(project_id, deployment, request_time,
  trace_id, core_span_id)`, collapsing duplicate spans on merge and keeping the latest-ingested
  copy. `_source_file` is **retained** as ingest provenance but is no longer the dedup key and is
  not part of the sort key. The file-level skip (`existsBySourceFile`) is removed; every run
  re-parses and re-inserts, and the engine collapses duplicates. Dedup is **eventual** — exact-count
  reads must use `FINAL` or `GROUP BY`/`argMax`.
- **Records missing `trace_id` or `core_span_id` are dropped and logged** (they would otherwise
  collapse together under the key), counted in a new `sync.rows.dropped` metric.
- **Pin UTC**: `request_time` → `DateTime64(3, 'UTC')`, `_ingested_at` → `DateTime('UTC')`.
- **Exact costs**: `price`/`deployment_price` → `Decimal(38, 12)` (no `Float64` rounding), and the
  mapping stops routing them through a lossy double conversion.
- **Rename `price` → `total_price`** to disambiguate it (the aggregate cost, `getAggCost()`) from
  `deployment_price` (the per-deployment cost, `getCost()`). **BREAKING** for any consumer reading
  the old column name.
- **Absent ≠ zero**: the genuinely-optional columns become `Nullable` and the mapping engine emits
  JSON `null` (instead of a type-default) when the producer omits the source field. Key/always-present
  columns stay `NOT NULL`.
- **`response_status` → `UInt16`** (it is always numeric), enabling range queries; reverses the
  prior deliberate `String` choice.
- **Storage/governance**: `CODEC(ZSTD(3))` on the heavy text columns, and a uniform
  `TTL request_time + INTERVAL 1 YEAR` on the whole row (the policy the DDL header already promises).
- **Physical tuning ported from the Analytics 2.0 `events` design** (operator DDL only, no service
  change): per-column codecs (`DoubleDelta`/`Delta` on the timestamps, `T64` on the token integers,
  `ZSTD(1)` on the id strings); `execution_path` inner element → `LowCardinality(String)`;
  `response_upstream_uri` → `LowCardinality(Nullable(String))`; four bloom-filter skip indexes
  (`trace_id`, `core_span_id`, `chat_id`, `user_id`) for point lookups off the sort key; and
  AES-256 (`AES_256_GCM_SIV`) at-rest encryption on the body columns. Partitioning stays **daily**.

## Capabilities

### New Capabilities
<!-- None. This refines existing data-sync behavior. -->

### Modified Capabilities
- `data-sync`: deduplication changes from source-file granularity to primary-key (`trace_id` +
  `core_span_id`) granularity with engine-level (`ReplacingMergeTree`) collapse and eventual-consistency
  reads; records lacking the key are dropped and logged; the mapping/transformation requirement gains
  null-on-absent semantics for optional columns, exact-decimal cost passthrough, numeric
  `response_status`, and the `total_price` rename; startup schema validation reconciles the new
  `Nullable`/`Decimal`/`UInt16` column types; observability adds a dropped-rows count and redefines the
  files-skipped metric.

## Impact

- **DDL (operator-applied, requires table rebuild)**: `docs/clickhouse/dial_usage_log.sql` — new
  engine, sort key, TTL, codecs, `Nullable`/`Decimal`/`UInt16` types, `total_price` rename, pinned UTC.
  The engine and sort key cannot be `ALTER`ed in place; operators must create a new table, backfill via
  `INSERT … SELECT`, and swap.
- **Mapping**: `src/main/resources/mapping/dial-usage-log.yaml` — `nullable` flags, `total_price`
  rename, `response_status` kind, exact-decimal cost handling; `_source_file` stays as provenance.
- **Code**: `MappingEngine` (null-on-absent + nullable flag), `ColumnMapping` (new `nullable` field),
  `SchemaValidator` (reconcile new types; ensure cost columns no longer mis-classify), `UsageLogWriter`
  (remove `existsBySourceFile` dedup gate), `BlobIngestor` (drop the already-ingested short-circuit; add
  drop-and-log of keyless records), `DataSyncService` (`RunSummary`/metrics: dropped-rows counter,
  redefined files-skipped).
- **Docs**: `docs/configuration.md` (metrics surface), the `data-sync` delta spec, and the SQL header
  comment (retention now implemented; `response_status` no longer kept as a string).
- **No new configuration property is expected.** The service continues to issue no DDL.
