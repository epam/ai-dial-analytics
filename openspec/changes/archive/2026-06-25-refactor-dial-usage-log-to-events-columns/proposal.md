## Why

`dial_usage_log` is today a 1:1 landing mirror of the DIAL usage-log emission. The Analytics 2.0
`events` design defines an enriched, query-friendly column set (a stable `event_id`, an `event_kind`
classifier, per-call enrichment like cache/reasoning tokens, MCP fields, body sizes, and a `success`
flag). Adopting that column set turns `dial_usage_log` into the GenAI-analytics surface consumers
actually want, rather than the raw producer shape. The mapping was already specified in
the upstream blob-event-to-table structure mapping design.

## What Changes

- **BREAKING** — `dial_usage_log` column set is replaced with the `events` column set (table keeps its name). The DDL is rewritten in place (clean replace; no existing data to preserve).
- **Renamed**: `user_id` → `user_hash` (and its bloom index).
- **Dropped**: `api_type`, `user_title`, `request_protocol`, `assembled_response` (the last is retained at ingest time only, as a parse source).
- **Added — direct**: carried 1:1 from the source record (timestamps, ids, tokens, prices, bodies, etc.).
- **Added — calculated (derived at ingest)**: `event_id`, `event_kind`, `response_id`, `cache_creation_tokens`, `reasoning_tokens`, `number_request_messages`, `success`, `mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`.
- **Added — blocked**: `operation_duration_ms`, `jwt_claims` (JSON), `request_tags` (JSON) — created now, left unmapped/unpopulated until DIAL Core emits them.
- **New ingest capability — derivers**: a closed, code-defined, name-keyed set of derivation functions (`DeriverRegistry`, analogous to the existing `TransformRegistry`) referenced from the mapping YAML via `derive` + `sources` + optional `args`. Column names and source paths stay in config; only reusable derivation logic lives in code.
- **Physical layout unchanged**: `ReplacingMergeTree(_ingested_at)`, daily `PARTITION BY toYYYYMMDD(request_time)`, `ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)`, 1-year TTL. `event_id` is added as a column only — it is deterministic from the existing dedup key, so dedup semantics are unchanged.
- **Query surface** (`dial_usage_log` entity) updated: `api_type` → `event_kind`, `user_id` → `user_hash`, plus the new scalar enrichment dimensions/metrics; bodies, JSON, and price columns remain non-queryable (mirroring current behavior).
- **Operational**: the ClickHouse `JSON` type (for the two blocked JSON columns) requires `allow_experimental_json_type=1` on the pinned 24.8 image — enabled via the existing config-mount mechanism.

## Capabilities

### New Capabilities
- _None._ (No new capability is introduced; existing capabilities' requirements change.)

### Modified Capabilities
- `data-sync`: the default mapping now produces the `events` column set (no longer "reproduces the existing columns unchanged"); a new requirement defines the **deriver** mechanism (closed named set, declared via `derive`/`sources`/`args`); the operator-provisioned table definition is updated to the new column set (layout unchanged).
- `query-schema-discovery`: the `dial_usage_log` base-schema field list changes (renames, drops, additions).
- `structured-query`: the `dial_usage_log` queryable entity fields change accordingly.

## Impact

- **DDL / operator artifact**: `src/main/resources/db/migration/V1__create_dial_usage_log.sql` (also the canonical operator DDL).
- **Mapping**: `src/main/resources/mapping/dial-usage-log.yaml`.
- **Ingest code** (`com.epam.aidial.analytics.sync.mapping`): `ColumnMapping`, `MappingEngine`, `MappingFileLoader`, `SchemaValidator`, new `Deriver`/`DeriverRegistry`; bean wiring in `configuration/MappingConfiguration`.
- **Query code**: `service/query/DialUsageLogSchema`.
- **ClickHouse config**: new experimental-flag XML for Testcontainers (`src/test/resources/clickhouse/`) and `local_env` Docker Compose; `clickhouse/clickhouse-server:24.8` pinned image.
- **Docs**: `docs/configuration.md` (deriver syntax + JSON flag note).
- **Tests**: `DeriverRegistryTest` (new), `MappingEngineTest`, `MappingFileLoaderTest`, `SchemaValidatorTest`, `DataSyncServiceTest`, integration fixtures (`AbstractClickHouseIntegrationTest`) and the structured-query integration / render / validation tests that reference `api_type` / `user_id`.
- **Build gate**: full `./gradlew clean build` needs Docker (Testcontainers ClickHouse).
