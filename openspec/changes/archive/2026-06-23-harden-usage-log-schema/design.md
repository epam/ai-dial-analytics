## Context

`dial_usage_log` is the full-fidelity ClickHouse sink for DIAL usage logs produced by ai-dial-core's
`GfLogStore`. The ingest path is: list blobs under a date prefix → parse gzip NDJSON →
`MappingEngine.toRow()` (driven by `dial-usage-log.yaml`) → `UsageLogWriter.insert()` (JSONEachRow).
`SchemaValidator` reconciles the mapping against the live table at startup. Today the schema mirrors
every producer field, but several columns lose meaning once flattened, and dedup is keyed on
`_source_file` (whole-blob granularity) via `UsageLogWriter.existsBySourceFile()` and
`BlobIngestor`'s `alreadyIngested()` short-circuit.

Two classes of problem motivate this change:
1. **Semantic consistency** — naive-UTC timestamp, `Float64` cost rounding, and the fact that
   `MappingEngine.coerce()` substitutes a type-default for any absent field (so "no usage reported"
   reads as zero tokens / zero cost).
2. **Dedup granularity** — file-level dedup cannot collapse a record re-delivered under a different
   blob name and forces the daily safety-net run to skip whole files instead of reconciling rows.

Constraints (from `openspec/project.md` / CLAUDE.md): the service issues no DDL (operator-provisioned
table); config descriptions live only in `docs/configuration.md`; a change is done only when
`./gradlew clean build` passes.

## Goals / Non-Goals

**Goals:**
- Make the stored row self-describing: pinned UTC time, exact decimal costs, `null` for genuinely
  absent optional fields, numeric `response_status`.
- Replace file-level dedup with engine-level record dedup on `(trace_id, core_span_id)`.
- Drop and account for records that lack the dedup key.
- Keep `SchemaValidator` coherent with the new column types and nullability.
- Keep `_source_file` as provenance.

**Non-Goals:**
- The service still issues no DDL; operators apply the migration. We do not add online schema
  migration.
- No masking/redaction of payload columns is added (governance stays operational: TTL + access
  controls).
- No new configuration property. No change to the read/discovery path or `/v1/tables`.
- We do not promote streaming-only fields (`model`, `usage`, …) buried in `assembled_response` into
  columns — out of scope.

## Decisions

### D1. Engine: `ReplacingMergeTree(_ingested_at)` with query-friendly sort key

`ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)`,
`PARTITION BY toYYYYMMDD(request_time)`.

ClickHouse engine dedup collapses rows sharing the full `ORDER BY` tuple, so the sort key *is* the
dedup key. Re-ingesting the same record yields identical leading columns (`project_id`, `deployment`,
`request_time` are stable for a given span), so ending the sort key with `(trace_id, core_span_id)`
deduplicates correctly while keeping the leading columns optimal for analytics filters
(project/deployment/time). The version column `_ingested_at` makes the engine keep the latest-ingested
copy. Re-ingests land in the same partition (same `request_time`), so merges can collapse them.

- *Alternative — `ORDER BY (trace_id, core_span_id)` only*: simplest dedup but destroys query
  locality for analytics scans. Rejected.
- *Alternative — app-side existence check per row on plain `MergeTree`*: immediate consistency but
  adds a read per batch and cannot self-heal historical duplicates. Rejected.

Trade-off: dedup is **eventual** (visible after background merge). Documented; exact reads use `FINAL`
or `GROUP BY`/`argMax`.

### D2. Drop-and-log records missing `trace_id`/`core_span_id`

Under D1 these would collapse into a single row (silent data loss). The ingest path filters such
records out *before* insert, logs them, and increments `sync.rows.dropped`. The check lives in the
ingest/mapping path (`BlobIngestor`), evaluated on the parsed record, alongside the existing
malformed-line skip. Keyless records are rare but real; counting them keeps the loss observable.

### D3. Absent ≠ zero via a `nullable` mapping flag

Add `nullable` (default `false`) to `ColumnMapping`. In `MappingEngine.resolve()`, when the resolved
source node is missing/blank: use the configured default if present; else `null` if the column is
nullable; else the kind's zero value (preserving safe inserts for non-nullable columns that the
producer may omit, e.g. `deployment`). This keeps `toRow()` emitting every column key, but a nullable
column now carries JSON `null`, which JSONEachRow stores as ClickHouse NULL.

Nullable columns: `prompt_tokens`, `completion_tokens`, `total_tokens`, `cached_prompt_tokens`,
`total_price`, `deployment_price`, `parent_deployment`, `response_upstream_uri`, `core_parent_span_id`,
`request_body`, `response_body`, `assembled_response`. Non-nullable (kept `NOT NULL`): the dedup/sort
key columns and all always-present producer fields.

### D4. Exact costs: `Decimal(38,12)`, keep `kind: double`, fix coercion

Keep the cost columns' mapping `kind: double` so `SchemaValidator` stays coherent —
`expectedFamily(DOUBLE) = FLOAT` and ClickHouse `Decimal` already classifies as `FLOAT`, so no
validator special-case is needed. The fix is in `MappingEngine` coercion: for the `DOUBLE` kind, read
the source as an exact decimal (parse the node's text / `decimalValue()` `BigDecimal`) instead of
`node.asDouble()`, so JSONEachRow serializes the full-precision decimal into the `Decimal(38,12)`
column. Only the two cost columns use `DOUBLE`, so this is contained.

- *Alternative — a new `decimal` kind*: cleaner typing but adds a `ColumnKind` and a
  `SchemaValidator` family mapping for no behavioral gain over fixing `DOUBLE` precision. Deferred.

### D5. `response_status` → `UInt16`, mapping `kind: long`

The producer always writes `Integer.toString(statusCode)`, so the value is numeric. Jackson's
`TextNode.asLong()` parses the numeric string, so `kind: long` (family `INTEGER`) reconciles with
`UInt16` (matched by the validator's `U?Int\d+`). This reverses the previous deliberate `String`
choice.

### D6. UTC pin, codecs, TTL — schema-only

`request_time DateTime64(3, 'UTC')`, `_ingested_at DateTime('UTC') DEFAULT now()`. The
`iso-to-ch-datetime` transform is unchanged (it only swaps `T`→space); the producer already emits
naive UTC, so pinning the column type makes interpretation server-timezone-independent.
`CODEC(ZSTD(3))` on `request_body`/`response_body`/`assembled_response`.
`TTL request_time + INTERVAL 1 YEAR` (uniform, whole-row; no PII/non-PII split per decision).

### D7. `price` → `total_price` rename

`price` is `getAggCost()` (aggregate), `deployment_price` is `getCost()` (per-deployment); the pairing
reads confusingly. Rename the ClickHouse column to `total_price`. The JSON source key stays
`token_usage.price` (that is the producer's key) — only the `column:` name changes in the mapping and
the DDL. Breaking for any downstream consumer of the old column name.

### D9. Physical tuning ported from the `events` design

The Analytics 2.0 `events` table design applies physical optimizations to the same
columns we carry. They port directly and are **DDL-only** — `DESCRIBE TABLE` returns neither codecs
nor skip indexes, so `SchemaValidator` (which reconciles name + type family + nullability) is
unaffected; only the two columns whose *type* changes need the test's `liveSchema()` updated.

- **Per-column codecs**: `DoubleDelta, ZSTD(1)` on `request_time` (monotonic), `Delta, ZSTD(1)` on
  `_ingested_at`, `T64, ZSTD(1)` on the `UInt32` token columns, `ZSTD(1)` on the high-cardinality id
  strings (`user_id`, `chat_id`, `trace_id`, `core_span_id`, `core_parent_span_id`). The cost
  `Decimal` columns are left uncodeced (matching `events`).
- **`execution_path` → `Array(LowCardinality(String))`**: path segments are deployment names that
  repeat heavily. Still classifies as the `ARRAY` family, so mapping/validation are unchanged.
- **`response_upstream_uri` → `LowCardinality(Nullable(String))`**: the upstream endpoint set is
  small. `request_uri` stays a plain `String` — full URIs are high-cardinality and would regress
  under `LowCardinality` (`events`' low-cardinality `route_path` is a *normalized* route we don't
  have).
- **Bloom-filter skip indexes** on `trace_id`, `core_span_id`, `chat_id`, `user_id`: the
  `ORDER BY` only accelerates `project_id`-led queries, leaving point lookups by these keys to full
  scans. Unlike the engine/sort-key/partition, indexes are addable to a live table via
  `ALTER TABLE … ADD INDEX` + `MATERIALIZE INDEX`.
- **AES-256 (`AES_256_GCM_SIV`) at rest on the body columns** (`request_body`, `response_body`,
  `assembled_response`): the same full-fidelity PII `events` encrypts. Decision: adopt it (use
  `AES_256_GCM` on FIPS builds). Trade-off recorded in Risks.

Decisions deliberately **not** taken: monthly partitioning (kept **daily** per the partition
trade-off and an explicit call to retain day-grain pruning/TTL), narrowing the cost `Decimal(38,12)`
to `events`' `Decimal(18,8)` (kept wider to avoid irreversible precision loss on per-token
micro-costs), and adding an explicit `version UInt64` column (kept `_ingested_at` as the
ReplacingMergeTree version, matching `events`' second-resolution `ingested_at` and our append-only
re-ingest model).

### D8. Remove the file-level dedup gate

Delete `UsageLogWriter.existsBySourceFile()` and `BlobIngestor`'s `alreadyIngested()` short-circuit.
`BlobIngestor.IngestResult` drops `alreadyIngested`; `DataSyncService` stops counting files-skipped as
already-ingested (the `sync.files.skipped` metric is removed or repurposed) and adds `sync.rows.dropped`.
`RunSummary` is updated accordingly, and the `DataSyncService`/`BlobIngestor` javadoc that describes
`_source_file` dedup is corrected.

## Risks / Trade-offs

- **Eventual dedup surprises consumers** → Document `FINAL`/`GROUP BY` for exact reads in the SQL
  header and spec; the sort key keeps `FINAL` reasonably cheap within a partition.
- **Operator migration is not in-place** → Engine and sort key cannot be `ALTER`ed. Mitigation: the
  SQL doc gives the rebuild path (create new table, `INSERT … SELECT` backfill, swap), and
  `SchemaValidator` fails fast at startup if the live table still has incompatible column types.
- **Re-reading every file each run costs more I/O than the old skip** → Acceptable: the daily run is a
  bounded safety net and inserts are idempotent under the engine; the near-real-time path remains the
  primary low-latency channel.
- **Dropping keyless records loses data** → Chosen over silent collapse; the `sync.rows.dropped`
  metric and warning logs keep the loss visible and quantified.
- **`Nullable` adds per-column overhead and excludes those columns from efficient sort keys** → Only
  applied to non-key, genuinely-optional columns; key columns stay `NOT NULL`.
- **Decimal-passthrough relies on JSONEachRow accepting a JSON number for `Decimal`** → Verify with a
  writer/serialization test; fall back to emitting the decimal as a quoted string if the client
  requires it.
- **AES-256 body encryption adds a server-side key dependency** → `CREATE TABLE` fails if the
  ClickHouse `encryption_codecs` keys are not configured. Mitigation: document the prerequisite in the
  DDL header and the operator migration; the service itself is unaffected (it issues no DDL and the
  codec is transparent on read/write).
- **Daily partitioning over a 1-year TTL yields ~365 active partitions** → Accepted (kept daily per
  decision); revisit monthly partitioning if part-count/merge pressure shows up operationally.

## Migration Plan

1. Ship code + mapping + DDL doc together; `./gradlew clean build` green.
2. Operator provisions the new table (new name or shadow), backfills from the old table via
   `INSERT … SELECT` (mapping `Float64`→`Decimal`, `String` status→`UInt16`, renaming `price`→
   `total_price`, leaving absent optionals as NULL where applicable), then swaps names.
3. Deploy the service; `SchemaValidator` reconciles against the migrated table at startup and refuses
   to start on drift.
4. Rollback: point the service back at the old table (old jar + old mapping) — both remain
   operator-provisioned, no DDL is issued by the service.

## Open Questions

- Confirm the ClickHouse Java client serializes a `BigDecimal`/JSON-number into a `Decimal(38,12)`
  column without quoting (D4); decide string-vs-number at implementation time via a writer test.
- `Decimal(38,12)` scale/precision is a reasonable default for cost; confirm it covers the producer's
  `getAggCost()`/`getCost()` ranges or adjust.
