## 1. ClickHouse schema

- [x] 1.1 Add `V10__create_dial_usage_log_payload.sql`: `event_id UUID`, `request_time`,
  `_updated_at DEFAULT now64(3)`, `trace_id`, `core_span_id`, the `JSON` columns `jwt_claims` and
  `request_tags` (carried verbatim, no codec), and the three
  `Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)` body columns;
  `ReplacingMergeTree(_updated_at)`, `PARTITION BY toYYYYMMDD(request_time)`, `ORDER BY (event_id)`,
  `TTL toDateTime(request_time) + INTERVAL 90 DAY`, `SETTINGS ttl_only_drop_parts = 1`.
- [x] 1.2 Header carries the reasoning: the encryption-key prerequisite; why the table is partitioned;
  why the shape matches what `TableDdlRenderer` emits for an enrichment; which five columns move and
  the two separate arguments (storage for the bodies, disclosure for the documents); why non-nullable
  `JSON` is safe on an enrichment; and the three-migration sequence with its measured transient cost.
- [x] 1.3 Add `V11__backfill_dial_usage_log_payload.sql`: one server-side `INSERT … SELECT` copying the
  90-day window, with a header covering why the window equals the TTL, the transient duplicate and its
  measured size, the idempotence that makes `flyway repair` a correct recovery, and why
  `request_time` is carried across rather than restamped.
- [x] 1.4 Add `V12__drop_dial_usage_log_payload_columns.sql` dropping the five columns, with a
  header stating what survives (the copied window), what does not (anything older), and that this is
  the step that releases the duplicated space.

## 2. Catalog cutover

- [x] 2.1 Add `V38__split_payload_columns.sql`: insert the `dial_usage_log_payload` definition
  (`ENRICHMENT`, `source_table=dial_usage_log`, `ACTIVE`, `system=TRUE`, `grain_key=event_id`) and
  re-key the five `column_mapping` rows onto it with ordinals 0–4, in V10's physical column order.
- [x] 2.2 Header explains the re-key (attribute preservation), the ordinal gaps, why `cardinality`,
  `partition_column` and the `ttl_*` fields are not set, and why non-nullable `JSON` columns are safe
  as enrichment columns.

## 3. Ingest mapping and configuration

- [x] 3.1 Add `mapping/dial-usage-log-payload.yaml` — `event_id` (`hash-uuid`), `request_time`
  (no default), `trace_id`, `core_span_id`, the two `object` columns and the three nullable body
  columns, in V10's physical order; match-all.
- [x] 3.2 Remove all five verbatim-content columns from `mapping/dial-usage-log.yaml`, keeping both
  `*_bytes` columns and every deriver that reads a body off the parsed record.
- [x] 3.3 Add the third `sync.targets` entry with `SYNC_PAYLOAD_TABLE` / `SYNC_PAYLOAD_MAPPING_FILE`.
- [x] 3.4 Confirmed no Java change is needed: `SyncProperties.targets` is a validated list,
  `MappingConfiguration` iterates it, and `UsageLogWriter` has no catalog coupling on the write path.

## 4. Documentation

- [x] 4.1 `docs/configuration.md`: the `sync.targets[1]` row for the payload target (encryption key,
  90-day retention owned by the migration, the qualified query names, that removing the target stops
  capturing raw content).
- [x] 4.2 `docs/heavy-columns.md`: seeded-heavy table renamed to the payload columns, wildcard
  examples requalified, and a section on the split — why, the qualified names, that the bare names no
  longer resolve, why the claim/header columns stayed, and why the `*_bytes` pair stayed.
- [x] 4.3 `docs/sql-reference.md`, `docs/column-tags.md`, `README.md`: requalified, and README's
  seeded-system-tables list extended to three.

## 5. Tests

- [x] 5.1 `MappingFileLoaderTest`: the payload mapping loads with its nine columns in physical order
  and no `match:` block; the `dial_usage_log` mapping is down to 33.
- [x] 5.2 `MappingEngineTest`: the payload row carries exactly its nine columns; both mappings derive
  the same `event_id`; every verbatim-content column is written by exactly one target; the
  object-kind transform cases run against the payload mapping.
- [x] 5.3 `SchemaValidatorTest`: the payload mapping reconciles with `_updated_at` unmapped; a missing
  or non-nullable body column aborts startup; the object-kind reconcile/mismatch cases moved to the
  payload table.
- [x] 5.4 `BlobIngestorTest`: one record produces a row in each table under the same `event_id`, with
  every verbatim-content column on the payload side only; a record with a blank `trace_id` lands in
  neither and is counted per target.
- [x] 5.5 `DialUsageLogTestModel`: all five columns become a `dial_usage_log_payload` enrichment
  carrying the grain join, the two documents declared non-nullable to match the catalog.
- [x] 5.6 Requalified every query/translation/Flight test to the quoted qualified names, and updated
  the star, schema-count and catalog-metadata expectations.
- [x] 5.7 `AbstractClickHouseIntegrationTest`: fixture rows carry an explicit distinct `event_id`,
  payload seeds are stamped `now()` so they stay inside the 90-day TTL, both tables truncate, and a
  row that sets a claim/header document gets a second insert on the payload table — a row that does
  not is deliberately left unmatched, which renders the empty document, the same value the old
  non-nullable column defaulted to.
- [x] 5.8 `IngestTableRetentionSettingsIntegrationTest`: pins the payload table's DDL — the 90-day
  TTL, `ttl_only_drop_parts`, daily partitioning, `ORDER BY event_id` and
  `ReplacingMergeTree(_updated_at)` — so the shape the grain join depends on cannot drift.
- [x] 5.9 `PayloadBackfillMigrationIntegrationTest`: pins the copy's idempotence against real
  ClickHouse (the property `flyway repair` depends on) and asserts the shipped statement keeps its
  retention-window bound. Notes why the copy itself cannot be run end to end after V12.
- [x] 5.10 `TableManagementIntegrationTest`: the payload columns' carried-over `heavy`/`sensitive`/
  description/display-name values are asserted on the payload table (5 columns; bodies heavy and
  sensitive, documents sensitive but not heavy), and `dial_usage_log`'s column count drops to 34.
- [x] 5.11 `FlightMetadataAuthIntegrationTest`: the `GetTables` sensitivity case is repointed to
  `rate_analytics.comment`, the last sensitive SOURCE column — a wildcard schema excludes every
  enrichment column for every caller, so `dial_usage_log` can no longer demonstrate the filtering.
  `INFORMATION_SCHEMA.COLUMNS` still covers the payload columns and keeps that guard.

## 6. Verify

- [x] 6.1 Delta specs updated to match the shipped behavior, including the two findings that changed
  the design (the non-nullable enrichment-read rule, and `json_extract_array` over a nullable column).
- [x] 6.2 `./gradlew clean build` passes — 2539 tests, checkstyle clean.

## 7. Enrichment nullability

- [x] 7.1 `EntityFields.absenceIsUnrepresentable` exempts `OBJECT` alongside `ARRAY` and `MAP`, with
  the measured ClickHouse 26.3 behaviour recorded: an unmatched join yields the empty document, and
  every accessor reads it identically to a present document lacking the key.
- [x] 7.2 `TableColumnRules.resolveNullable` forces `OBJECT` non-nullable on every table kind, and
  `requireValidObjectColumn` refuses an explicit `nullable: true` — closing the divergence where the
  catalog could record a flag `ClickHouseTypeMapper` never renders.
- [x] 7.3 Verified against a live ClickHouse before committing to the shape: the unmatched-join value,
  the four accessor results over the empty document, and the `Code 43` failure that rules out
  `Nullable(String)`.

## 8. Runtime verification

Run against the local `local_env` stack on throwaway databases (`adas_verify` on both ClickHouse and
PostgreSQL), so the migration sequence — including V12's `DROP COLUMN` — could be exercised for real
without touching the developer's existing local data. Confirmed afterwards that `default`.`dial_usage_log`
still carries all five columns.

- [x] 8.1 Applied V1-V9 by hand, seeded three rows spanning the retention boundary (2 days, 30 days
  and 200 days old) with bodies, claims and headers, then applied V10 / V11 / V12 in order.
- [x] 8.2 V11 copied exactly the two in-window rows, all five columns including both JSON documents,
  with `request_time` carried verbatim. The 200-day-old row was correctly left behind.
- [x] 8.3 Re-ran V11 to simulate `flyway repair`: 4 raw rows collapse to 2 under `FINAL`, confirming
  the idempotence the recovery path depends on.
- [x] 8.4 V12 dropped all five columns; only `request_body_bytes` / `response_body_bytes` remain.
- [x] 8.5 Booted the service against the migrated schema. Startup implies Postgres V1-V38 applied and
  `SchemaValidator` reconciled both edited mappings against the physical tables.
- [x] 8.6 Endpoint cases: `/v1/tables/dial_usage_log_payload` reports 5 columns with every carried
  attribute intact (bodies heavy+sensitive, documents sensitive and non-heavy, descriptions and
  display names preserved); `/v1/tables/dial_usage_log` reports 34; discovery advertises the five
  qualified names and no bare one; `execute-sql` and the DSL both resolve the qualified names and
  emit the grain join; `SELECT *` excludes all five; every object accessor
  (`json_extract_array`, `json_extract_string`, `json_extract_string_ci`) works over the moved JSON
  columns.
- [x] 8.7 The expired-payload row behaves exactly as D2 predicts: body reads `NULL`, claims read
  `{}`, `json_extract_array` reads `[]`, `json_extract_string` reads `''`.
- [x] 8.8 Registered a Class-1 `sql` evaluator reading
  `json_extract_array("dial_usage_log_payload.jwt_claims", 'roles')` and a pipeline binding it to an
  `array` column. The pipeline was ACCEPTED — the exact case `EnrichSpecValidator` refused before
  this change — materialized 3 rows, and read back `["admin","dev"]` for the seeded row and `[]` for
  the expired one.
- [x] 8.9 Teardown: service stopped, port 5000 released, both throwaway databases dropped, docker
  stack left running as found (it predated the run).

**Found, not fixed (pre-existing, out of scope):** an unknown column on the REST SQL surface reports
Calcite's "depends on itself" message rather than an unknown-column error. `SELECT no_such_column
FROM rate_analytics` reproduces it identically, so it is not caused by this change. Recorded in
design.md's risk list so the breaking-change mitigation is not overstated.
