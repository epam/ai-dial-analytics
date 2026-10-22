## 1. Schema DDL (operator artifact)

- [x] 1.1 Rewrite `docs/clickhouse/dial_usage_log.sql`: engine `ReplacingMergeTree(_ingested_at)`, `ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)`, `PARTITION BY toYYYYMMDD(request_time)` unchanged
- [x] 1.2 Pin UTC: `request_time DateTime64(3, 'UTC')`, `_ingested_at DateTime('UTC') DEFAULT now()`
- [x] 1.3 Change `price` → `total_price` and type `total_price`/`deployment_price` to `Decimal(38, 12)`
- [x] 1.4 Change `response_status` to `UInt16`
- [x] 1.5 Make optional columns `Nullable`: `prompt_tokens`, `completion_tokens`, `total_tokens`, `cached_prompt_tokens`, `total_price`, `deployment_price`, `parent_deployment`, `response_upstream_uri`, `core_parent_span_id`, `request_body`, `response_body`, `assembled_response`; keep key/always-present columns `NOT NULL`
- [x] 1.6 Add `CODEC(ZSTD(3))` to `request_body`, `response_body`, `assembled_response`
- [x] 1.7 Add `TTL request_time + INTERVAL 1 YEAR`
- [x] 1.8 Update the header comment: retention now implemented; `response_status` is numeric; `_source_file` is provenance (not the dedup key); record dedup is via the engine and is eventual (use `FINAL`/`GROUP BY` for exact reads); include the rebuild/backfill migration note (create new table, `INSERT … SELECT`, swap)

## 1b. Physical tuning ported from `events` (DDL only)

- [x] 1b.1 Add per-column codecs: `DoubleDelta, ZSTD(1)` on `request_time`; `Delta, ZSTD(1)` on `_ingested_at`; `T64, ZSTD(1)` on `prompt_tokens`/`completion_tokens`/`total_tokens`/`cached_prompt_tokens`; `ZSTD(1)` on `user_id`/`chat_id`/`trace_id`/`core_span_id`/`core_parent_span_id`
- [x] 1b.2 Change `execution_path` inner element to `Array(LowCardinality(String))`
- [x] 1b.3 Change `response_upstream_uri` to `LowCardinality(Nullable(String))` (leave `request_uri` a plain `String`)
- [x] 1b.4 Add bloom-filter skip indexes on `trace_id`, `core_span_id`, `chat_id`, `user_id`
- [x] 1b.5 Add AES-256 at-rest encryption (`AES_256_GCM_SIV`) alongside `ZSTD(3)` on `request_body`/`response_body`/`assembled_response`; document the server-side key prerequisite (and the FIPS `AES_256_GCM` variant) in the DDL header
- [x] 1b.6 Keep daily partitioning and `Decimal(38, 12)` cost precision (explicitly not aligned to `events`' monthly / `Decimal(18,8)`); update `SchemaValidatorTest.liveSchema()` for the `execution_path` and `response_upstream_uri` type changes (codecs/indexes do not appear in `DESCRIBE`)

## 2. Mapping configuration

- [x] 2.1 In `src/main/resources/mapping/dial-usage-log.yaml`: rename column `price` → `total_price` (keep `source: token_usage.price`)
- [x] 2.2 Change `response_status` to `kind: long`
- [x] 2.3 Add `nullable: true` to the optional columns listed in 1.5; keep `_source_file` as a non-nullable provenance column
- [x] 2.4 Update the mapping header comment (nullability semantics, `total_price`, numeric status, `_source_file` provenance-only)

## 3. Mapping engine & model

- [x] 3.1 Add a `nullable` boolean field (default `false`) to `ColumnMapping` and wire it through mapping-file parsing
- [x] 3.2 In `MappingEngine.resolve()`: on absent/blank source, apply configured default → else `null` if the column is nullable → else the kind's zero value
- [x] 3.3 Fix `DOUBLE`-kind coercion to preserve exact decimal precision (read `BigDecimal` / node text, not `node.asDouble()`) for `total_price`/`deployment_price`
- [x] 3.4 Unit-test `MappingEngine`: absent nullable column → `null`; absent non-nullable column → zero; absent column with default → default; decimal value preserved exactly

## 4. Dedup rework (ingest path)

- [x] 4.1 Remove `UsageLogWriter.existsBySourceFile()` and its callers
- [x] 4.2 Remove `BlobIngestor`'s `alreadyIngested()` short-circuit and the `alreadyIngested` field from `IngestResult`; update its javadoc
- [x] 4.3 Add drop-and-log of records with absent/blank `trace_id` or `core_span_id` in the ingest path, before insert
- [x] 4.4 Update `DataSyncService`: remove/repurpose files-skipped, add `sync.rows.dropped` metric and `rowsDropped` in `RunSummary`; correct the class javadoc that describes `_source_file` dedup
- [x] 4.5 Unit-test: keyless record is dropped, logged, counted, and does not abort the run; re-ingest no longer pre-skips

## 5. Startup schema validation

- [x] 5.1 Confirm `SchemaValidator` normalize/classify handles `Decimal`, `UInt16`, and `DateTime64(3, 'UTC')` (already strips `Nullable(...)`/`LowCardinality(...)`); add cases if needed
- [x] 5.2 Add nullability reconciliation: a mapping column flagged `nullable` MUST be `Nullable(...)` in the table and vice-versa; report offenders
- [x] 5.3 Update/extend `SchemaValidatorTest` for the renamed/retyped/nullable columns and the nullability check

## 6. Docs & verification

- [x] 6.1 Update `docs/configuration.md` to document the `sync.rows.dropped` metric (and any metric removed/redefined) in the metrics section
- [x] 6.2 Update any prose in `README.md` referencing source-file dedup (inline only; no env-var tables)
- [x] 6.3 `openspec validate harden-usage-log-schema --strict` passes
- [x] 6.4 `./gradlew clean build` passes (compile + checkstyle + tests)
