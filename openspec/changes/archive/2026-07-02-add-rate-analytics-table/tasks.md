## 1. ClickHouse table migration

- [x] 1.1 Add `src/main/resources/db/migration/clickhouse/V2__create_rate_analytics.sql` creating `rate_analytics` with columns `response_id String`, `rate Nullable(Int8)`, `comment Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)`, `deployment LowCardinality(String)`, `user_hash String`, `chat_id String`, `project_id LowCardinality(String)`, `request_time DateTime64(3,'UTC') CODEC(DoubleDelta, ZSTD(1))`, plus hidden physical `trace_id String`, `core_span_id String`, `_ingested_at DateTime64(3,'UTC') DEFAULT now64(3) CODEC(Delta, ZSTD(1))`.
- [x] 1.2 Set `ENGINE = ReplacingMergeTree(_ingested_at)`, `PARTITION BY toYYYYMMDD(request_time)`, `ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)`, `TTL toDateTime(request_time) + INTERVAL 1 YEAR`; add `INDEX idx_response_id response_id TYPE bloom_filter(0.01)`. Use `CREATE TABLE IF NOT EXISTS` and mirror the V1 header comment (AES codec requires the server-side key).
- [x] 1.3 Verified: DDL applied against a CLI-started `clickhouse/clickhouse-server:25.3` container with a test encryption key mounted — CREATE succeeded, engine/partition/order/TTL correct, a mapping-shaped JSONEachRow row round-tripped, and dedup confirmed (re-ingested span collapses; distinct spans of one `response_id` both kept).

## 2. Query catalog seed (Postgres)

- [x] 2.1 Add `src/main/resources/db/migration/postgres/V2__seed_rate_analytics_catalog.sql` inserting `table_definition('rate_analytics', …, type='SOURCE', source_table=NULL, status='ACTIVE', partition_column='request_time', partition_granularity='DAY')`.
- [x] 2.2 Seed `table_ordering_key` with the full physical sort key in order: `project_id, deployment, request_time, trace_id, core_span_id`.
- [x] 2.3 Seed `column_mapping` with ONLY the 8 exposed columns (`response_id, rate, comment, deployment, user_hash, chat_id, project_id, request_time`) with correct catalog types (`rate`→`integer` nullable, `comment`→`string` nullable, `request_time`→`timestamp`); omit `trace_id`, `core_span_id`, `_ingested_at`. Follow the `dial_usage_log` backfill block pattern; optionally set `tag`s.

## 3. Rate normalizer deriver

- [x] 3.1 Add a `json-extract-rate` deriver to `DeriverRegistry`: read `args.path` from `request.body`; number → its int value; boolean `true`→1, `false`→0; absent/blank → null.
- [x] 3.2 Register `json-extract-rate` in `DeriverRegistry` and add it to the known-deriver set validated by `MappingFileLoader`.
- [x] 3.3 Add `DeriverRegistryTest` cases: `true`→1, `false`→0, `1`→1, `-1`→-1, absent→null, non-numeric/blank→null.

## 4. Match predicate (conditional emit)

- [x] 4.1 Add a `RecordMatcher` type: an ordered list of `{path, op, value}` conditions ANDed together; ops `ends-with`, `equals`, `contains`; a matcher with no conditions matches all records.
- [x] 4.2 Extend `MappingConfig` to carry an optional compiled `RecordMatcher`, and `MappingFileLoader` to parse a top-level `match:` block and fail-fast on unknown operators.
- [x] 4.3 Add `RecordMatcherTest` covering each operator, AND semantics, missing source path (no match), and empty/no `match` (matches all).

## 5. Multi-target pipeline wiring

- [x] 5.1 Introduce a `SyncTarget` (tableName, `MappingEngine`, `RecordMatcher`) abstraction.
- [x] 5.2 Extend `SyncProperties` with a `targets` list (env-driven), backward-compatible so an absent/legacy config yields the single default `dial_usage_log` target; document the new `sync.targets` group ONLY in `docs/configuration.md`.
- [x] 5.3 Change `MappingConfiguration` to build a `List<SyncTarget>` (one `MappingEngine` per target), defaulting to `dial_usage_log` (no match) + `rate_analytics` (rate match).
- [x] 5.4 Parametrize `UsageLogWriter.insert(table, rows)` and `describeTable(table)` on table name instead of reading `sync.table()`.
- [x] 5.5 Bind `RowBatchSink` to `(writer, tableName)`; update `DataSyncService` and `BlobPollingService` to create one sink per target for the run and flush all sinks at end of run.
- [x] 5.6 Update `BlobIngestor.ingest` to iterate targets: for each parsed record, for each target whose matcher passes, `engine.toRow` → that target's sink; keep the per-target `trace_id/core_span_id/request_time` dedup-key drop guard; aggregate per-target counts in `IngestResult`.
- [x] 5.7 Update `SchemaValidator` to validate each target's mapping against its own table at startup.

## 6. Rate mapping file

- [x] 6.1 Add `src/main/resources/mapping/rate-analytics.yaml` with a `match:` block (`request.uri` ends-with `/rate` AND `request.method` equals `POST`) and columns: `response_id` (`json-extract-string`, `request.body`, path `responseId`), `rate` (`json-extract-rate`, path `rate`, nullable), `comment` (`json-extract-string`, path `comment`, nullable), plus `deployment`, `user_hash`(`user.id`), `chat_id`, `project_id`, `request_time` (with `iso-to-ch-datetime` transform), and hidden `trace_id`, `core_span_id`.
- [x] 6.2 Confirm `dial-usage-log.yaml` is unchanged (no `match` block → matches all records).

## 7. Tests

- [x] 7.1 `MappingEngineTest`: rate mapping yields expected row incl. `response_id` from body, `rate` normalization, `comment`, and hidden columns populated.
- [x] 7.2 `BlobIngestorTest`: a mixed blob writes every record to `dial_usage_log` and only `/rate` POST records to `rate_analytics`; assert `dial_usage_log` output is byte-for-byte unchanged vs. the pre-refactor single-target path.
- [x] 7.3 `SchemaValidatorTest`: per-target validation passes for both targets and fails fast on a mapping/table divergence.
- [x] 7.4 Added `RateAnalyticsIntegrationTest` (schema exposes exactly the 8 columns; a row query executes against the provisioned table; `trace_id` is rejected as an unknown field) and updated `QuerySchemaDiscoveryIntegrationTest` to expect both seeded SOURCE entities. NOTE: cannot be executed in this environment — Testcontainers' Ryuk reaper (`testcontainers/ryuk:0.14.0`) times out here, so ALL integration tests (pre-existing ones too) fail to launch the container; this is the known environmental limitation, not a code defect.

## 8. Specs, docs, and build

- [x] 8.1 Ensure `docs/configuration.md` documents the `sync.targets` config group (authoritative descriptions live only here; no yaml comments, no README table).
- [x] 8.2 Run `openspec validate add-rate-analytics-table` and fix any issues.
- [x] 8.3 `./gradlew checkstyleMain checkstyleTest` and all unit tests pass locally; the DDL was validated on a real ClickHouse 25.3 container. The full `./gradlew clean build` (including all Testcontainers integration tests) passed in a Testcontainers-capable environment — the sandbox here could not run it because the Ryuk reaper won't start.
