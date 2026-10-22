## MODIFIED Requirements

### Requirement: Target table provisioning and schema

By default the service SHALL provision the target ClickHouse table (`dial_usage_log`) on startup by
running Flyway migrations under `classpath:db/migration` over the ClickHouse JDBC driver, using the
writer credential (which must therefore additionally hold `CREATE TABLE` grants). Provisioning SHALL be
gated by `clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup
schema validation; a migration failure SHALL abort startup. When migrations are disabled, the service
SHALL issue no DDL and the operator SHALL provision the table out of band by applying the same migration
DDL (`src/main/resources/db/migration/V1__create_dial_usage_log.sql`).

Whether applied by Flyway or by an operator, the table definition SHALL declare the `events` column set:
a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from the source
(timestamps, ids, `user_hash`, tokens, prices, request/response bodies, etc.); the calculated columns
(`response_id`, `cache_creation_tokens`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`); and the blocked columns
(`operation_duration_ms`, `jwt_claims`, `request_tags`) which exist in the table but are not yet
populated. The two JSON columns use ClickHouse's `JSON` type, which is generally available on the
supported server version (ClickHouse **25.3 LTS or newer**) and SHALL require no experimental
enablement setting; the body columns use the `AES_256_GCM_SIV` codec, which requires a server-side
encryption key or `CREATE TABLE` fails. For record-level deduplication to function, the table SHALL use
the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key (`ORDER BY`) ending in the
deduplication key columns `trace_id` and `core_span_id`, partitioned by `toYYYYMMDD(request_time)`. The
definition SHALL also pin UTC on its timestamp columns, store monetary columns as `Decimal`, store
`response_status` as `UInt16`, declare the producer-optional columns as `Nullable`, and apply a
row-level retention `TTL`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`
  if it does not exist, before any sync run or schema validation

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer) with
  no `allow_experimental_json_type` setting configured
- **THEN** the `CREATE TABLE` for the `jwt_claims`/`request_tags` `JSON` columns succeeds, because the
  `JSON` type is generally available on that version

#### Scenario: Migration failure aborts startup

- **WHEN** a startup migration fails (e.g. the writer credential lacks `CREATE TABLE` or a required
  server-side feature is unavailable)
- **THEN** the service fails to start rather than proceeding to run sync against an unprovisioned table

#### Scenario: Migrations disabled — operator provisions out of band

- **WHEN** `clickhouse.migrations.enabled` is false
- **THEN** the service issues no DDL and inserts into the table the operator provisioned out of band
  from the same migration DDL

#### Scenario: Table definition supports record-level dedup

- **WHEN** the table is provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** the table is a `ReplacingMergeTree` whose sort key ends in `trace_id` and `core_span_id`, so
  re-ingested rows for the same span collapse on merge

#### Scenario: Blocked columns exist but are unpopulated

- **WHEN** the table is provisioned and ingestion runs
- **THEN** the `operation_duration_ms`, `jwt_claims`, and `request_tags` columns exist in the table and
  are left at their column defaults, because no mapping entry populates them
