## MODIFIED Requirements

### Requirement: Target tables are provisioned by versioned ClickHouse migrations

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants, and `ALTER DROP COLUMN` on `dial_usage_log`). Provisioning SHALL be gated by
`clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup schema
validation; a migration failure SHALL abort startup. When migrations are disabled, the service SHALL
issue no DDL and the operator SHALL provision the tables out of band by applying the same migration
DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, etc.); and the
calculated columns (`response_id`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`). It SHALL NOT declare
the five verbatim-content columns — the three bodies and the two JSON documents. They were created by
`V1`/`V7`, moved to `dial_usage_log_payload` by `V10` and the mapping cutover, and dropped from
`dial_usage_log` by `V11`; the migration set as a whole therefore leaves them on the payload table
only. The two `*_bytes` columns SHALL survive that drop, because they size a payload without holding
one and must remain answerable past the payload table's retention.

On `dial_usage_log_payload`, the JSON columns use ClickHouse's `JSON` type, which is generally
available across the supported server range (ClickHouse **25.3 LTS or newer**, verified through
**26.3 LTS**) and SHALL require no experimental enablement setting; the body columns use the
`AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE` fails. That key
SHALL also be required to provision `dial_usage_log` from scratch, because `V1` creates its body
columns under the same codec before `V11` drops them. For record-level deduplication to function,
`dial_usage_log` SHALL use the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key
(`ORDER BY`) ending in the deduplication key columns `trace_id` and `core_span_id`, partitioned by
`toYYYYMMDD(request_time)`. The definition SHALL also pin UTC on its timestamp columns, store monetary
columns as `Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as
`Nullable` — including every producer-reported token count — and apply a row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other column its
mapping declares: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`
  and `dial_usage_log_payload` if they do not exist, before any sync run or schema validation

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer,
  verified through 26.3 LTS) with no `allow_experimental_json_type` setting configured
- **THEN** the `CREATE TABLE` for the `jwt_claims`/`request_tags` `JSON` columns succeeds, because the
  `JSON` type is generally available on that version

#### Scenario: Migration failure aborts startup

- **WHEN** a startup migration fails (e.g. the writer credential lacks `CREATE TABLE` or a required
  server-side feature is unavailable)
- **THEN** the service fails to start rather than proceeding to run sync against an unprovisioned table

#### Scenario: Migrations disabled — operator provisions out of band

- **WHEN** `clickhouse.migrations.enabled` is false
- **THEN** the service issues no DDL and inserts into the tables the operator provisioned out of band
  from the same migration DDL

#### Scenario: Operator misses a later migration

- **WHEN** `clickhouse.migrations.enabled` is false and the operator provisioned `dial_usage_log` from
  the `CREATE TABLE` alone, omitting the later migration that creates `dial_usage_log_payload`
- **THEN** startup schema validation fails, because the payload mapping cannot be reconciled against a
  table that does not exist, and the service does not start

#### Scenario: Table definition supports record-level dedup

- **WHEN** the tables are provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** `dial_usage_log` is a `ReplacingMergeTree` whose sort key ends in `trace_id` and
  `core_span_id`, and `dial_usage_log_payload` is a `ReplacingMergeTree` ordered by `event_id`, so
  re-ingested rows for the same span collapse on merge in both

#### Scenario: Every user column is populated

- **WHEN** the tables are provisioned and ingestion runs with the shipped mappings
- **THEN** every column of each table that its mapping declares is populated by that mapping, and the
  only columns left to a value the mapping did not supply are the two table-side defaults
  (`_ingested_at`, `_updated_at`)

### Requirement: Body columns are captured only by the payload table's mapping

The three unbounded body columns — `request_body`, `response_body`, `assembled_response` — SHALL be
persisted to their own ClickHouse table, `dial_usage_log_payload`, and SHALL NOT be persisted to
`dial_usage_log`. This is enforced at both levels: no mapping targeting `dial_usage_log` captures a
value for any of them, and `V11` has dropped the columns from that table, so there is nothing there to
write to.

The table SHALL be provisioned by a versioned ClickHouse migration (never by the `/v1/tables` API) and
SHALL declare:

- the grain column `event_id UUID`, which is also its sole `ORDER BY` column and therefore its unique
  key;
- `request_time`, which drives both `PARTITION BY toYYYYMMDD(request_time)` and the retention `TTL`;
- `trace_id` and `core_span_id`, so the deduplication-key drop rule is decidable on a payload row;
- the three body columns as `Nullable(String)` under the `AES_256_GCM_SIV` codec (which requires a
  server-side encryption key, or `CREATE TABLE` fails);
- the claim and header documents `jwt_claims` and `request_tags` as `JSON`, carried across verbatim
  from `dial_usage_log` — same type, no codec. They are necessarily NON-nullable, because ClickHouse
  has no `Nullable(JSON)`;
- the `ReplacingMergeTree` engine versioned by a table-side `_updated_at` default, so a re-ingested
  record replaces its predecessor for the same `event_id` in the same daily partition.

`_updated_at` SHALL be the only column of this table the shipped mapping leaves unpopulated.

One parsed record SHALL produce a `dial_usage_log` row **and** a `dial_usage_log_payload` row, from two
mappings evaluated over the same record under the same match-all predicate, carrying the same
`event_id`. Because both targets apply the same deduplication-key drop rule to the same record, the two
rows SHALL be either both emitted or both dropped — so an ingested `dial_usage_log` row SHALL never
lack its payload row for a reason internal to ingest.

**The payload row SHALL become visible no later than its `dial_usage_log` row.** The two targets batch
independently, so the run sink SHALL flush in dependency order: when the `dial_usage_log` sink reaches
its threshold, or at end of run, the sinks holding its enrichments (the payload sink) SHALL be written
first and the source sink after; the payload sink reaching its own threshold MAY flush alone. A failed
payload flush SHALL propagate before the source rows of the same batch are written, so a run faults
with both tables equally behind rather than with usage-log rows whose payloads never arrive. Which
targets enrich which SHALL be read from the catalog (`table_definition.type = ENRICHMENT` and
`source_table`), once per run. The reason is the read side: every consumer of the grain join — a
query, an `on_ingest` scan, an aggregate refresh — treats an unmatched payload as absent and final, and
a scan that read a source row while its payload row was still buffered would derive a value from a
`NULL` body and never revisit the row. The freshness classification for such an enrichment is
`ingest_written` (see the pipeline-registry capability), not `no_pipeline`.

`jwt_claims` and `request_tags` SHALL move with the bodies. They are small, so storage is not the
argument; they can carry bearer tokens, cookies and forwarded client IPs, so disclosure is. Because
they are non-nullable, an unmatched grain join renders them as the empty document rather than as
`NULL` — which the `enrichment-runtime` capability's nullability rule permits for `object` columns
specifically, since no accessor can distinguish `{}` from a present document lacking the key.

#### Scenario: One record produces a row in both tables

- **WHEN** a run ingests a record carrying `request.body`, `response.body`, `assembled_response`,
  `claims` and `headers`
- **THEN** `dial_usage_log` receives a row with the analytic columns, and `dial_usage_log_payload`
  receives a row keyed on the same `event_id` carrying all five verbatim values

#### Scenario: The payload row keys on the parent's event id

- **WHEN** a payload row is written for a record
- **THEN** its `event_id` is the same deterministic hash of (`trace_id`, `core_span_id`) that the
  record's `dial_usage_log` row carries, so the two rows join one-to-one

#### Scenario: A re-ingested record replaces its payload row

- **WHEN** the same record is ingested more than once
- **THEN** a deduplicated read of `dial_usage_log_payload` returns a single row for that `event_id`,
  the latest-written copy

#### Scenario: The payload batch is written before the usage-log batch

- **WHEN** the `dial_usage_log` sink reaches its batch threshold while payload rows for the same records
  are buffered, or the run ends with rows buffered in both sinks
- **THEN** the payload sink is flushed first and the `dial_usage_log` sink after, so no usage-log row is
  visible before its payload row

#### Scenario: A failed payload flush withholds the usage-log rows of the same batch

- **WHEN** writing the payload batch fails
- **THEN** the run faults before the `dial_usage_log` batch is written, and neither table receives the
  batch's rows until a later run re-ingests them

#### Scenario: The payload sink filling on its own flushes alone

- **WHEN** the payload sink reaches its threshold before the `dial_usage_log` sink does
- **THEN** only the payload batch is written; the usage-log sink keeps batching to its own threshold

#### Scenario: A record without a dedup key is dropped from both tables

- **WHEN** a record's `trace_id` or `core_span_id` is absent or blank
- **THEN** neither a `dial_usage_log` row nor a `dial_usage_log_payload` row is inserted for it, and the
  drop is logged and counted for each target independently

#### Scenario: The body columns are unpopulated on dial_usage_log

- **WHEN** the shipped mapping ingests a record
- **THEN** the inserted `dial_usage_log` row carries no value for `request_body`, `response_body`,
  `assembled_response`, `jwt_claims` or `request_tags`, because the mapping for that table does not
  capture them and the physical table no longer declares them

#### Scenario: The claim and header documents move with the bodies

- **WHEN** a record carrying `claims` and `headers` is ingested
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims` and `request_tags`, and the
  `dial_usage_log` row carries neither

## ADDED Requirements

### Requirement: The verbatim-content columns are dropped from dial_usage_log

A versioned ClickHouse migration (`V11__drop_dial_usage_log_payload_columns.sql`) SHALL drop
`jwt_claims`, `request_tags`, `request_body`, `response_body` and `assembled_response` from
`dial_usage_log`, so that the five verbatim-content columns exist on `dial_usage_log_payload` alone.
Every clause SHALL use `IF EXISTS`, so re-running the migration, or applying it to an environment that
already performed the equivalent DDL out of band, SHALL be a no-op rather than an error.

**No backfill SHALL precede the drop.** The body, claim and header content of rows ingested before the
mapping cutover is deleted with the columns and SHALL NOT be recoverable from the database. This is a
deliberate acceptance, not an oversight: since the catalog re-pointed those five column names at
`dial_usage_log_payload`, that content has resolved on no query surface the service exposes, so the
drop removes storage the service could not read. An operator who requires the history SHALL copy it out
of ClickHouse before deploying; the service SHALL NOT offer a migration that preserves it.

`request_body_bytes` and `response_body_bytes` SHALL NOT be dropped. They are four bytes each, they are
the only way to size a payload before fetching it, and they must outlive the payload they describe so
that questions about historical body sizes remain answerable past the payload table's retention.

The drop SHALL disturb nothing else about the table: none of the five columns appears in
`dial_usage_log`'s sort key, carries a `DEFAULT` expression, or backs a skip index, so deduplication
and every indexed scan SHALL behave identically after the migration.

Because the shipped mapping stopped capturing these columns before this migration existed, a rolling
deploy across it SHALL be safe in both directions for any build at or after the mapping cutover: a
replica that has not yet been replaced writes neither the dropped columns nor anything that depends on
them. Rolling back to a build from before the cutover SHALL require restoring the column shape with
`ALTER TABLE ... ADD COLUMN` first, and SHALL restore them empty.

#### Scenario: The columns are absent from dial_usage_log after provisioning

- **WHEN** the ClickHouse migrations have been applied, by Flyway at startup or by an operator out of
  band
- **THEN** `dial_usage_log`'s physical schema declares none of `jwt_claims`, `request_tags`,
  `request_body`, `response_body` or `assembled_response`, while `dial_usage_log_payload` declares all
  five

#### Scenario: The body size columns survive the drop

- **WHEN** the drop migration has been applied
- **THEN** `dial_usage_log` still declares `request_body_bytes` and `response_body_bytes`, and rows
  written before the drop retain their values for both

#### Scenario: Re-applying the drop is a no-op

- **WHEN** the migration is applied to a `dial_usage_log` that no longer has the five columns
- **THEN** it succeeds without error and changes nothing, because every clause is guarded by
  `IF EXISTS`

#### Scenario: Pre-split content is not preserved

- **WHEN** `dial_usage_log` holds rows ingested before the mapping cutover, still carrying body, claim
  and header content
- **THEN** applying the drop deletes that content permanently, and no migration in the shipped set
  copies it to `dial_usage_log_payload` first
