## REMOVED Requirements

### Requirement: Target table provisioning and schema

**Reason**: This requirement described `dial_usage_log`'s physical schema as no longer declaring
the five verbatim-content columns, and included an "operator applies the split migrations out of
order" scenario about `V11` (copy) vs `V12` (drop) sequencing. Both migrations are removed (see
proposal.md - Why), so the physical schema and the provisioning scenarios change. Replaced by
"Target tables are provisioned by versioned ClickHouse migrations" below, which keeps every
surviving scenario and drops only the one that described `V11`/`V12` ordering.

**Migration**: No operator action. A fresh deployment still provisions both tables from
`db/migration/clickhouse` in version order; there is no longer a copy-then-drop pair to sequence,
so the removed scenario has no replacement — there is nothing left to get "out of order".

### Requirement: Body columns are ingested into a separate retention-scoped table

**Reason**: This requirement's "the body columns are absent from dial_usage_log" scenario claimed
the physical table "no longer declares" the five columns, which was true only once `V12` had run.
`V12` is removed, so the columns remain physically declared, merely unpopulated by ingest.
Replaced by "Body columns are captured only by the payload table's mapping" below, which keeps
every other scenario unchanged and restates only that one to match reality.

**Migration**: No operator or consumer action. `dial_usage_log_payload` still receives the five
columns exactly as before; a consumer reading `dial_usage_log` directly (bypassing the catalog's
grain join) may now see the five columns in `DESCRIBE TABLE` / `INFORMATION_SCHEMA.COLUMNS` output
where it previously would not have (on a deployment that had already run `V12`) — they read as
`NULL`/`{}` and carry no content.

### Requirement: The payload table carries a shorter retention than its parent

**Reason**: This requirement mixed the payload table's own retention behavior (which is unchanged
and comes from `V10` alone) with `V11`'s backfill mechanics — its memory-safety design, its
version-carrying idempotence, and three scenarios that exercise the backfill directly. `V11` is
removed (see proposal.md - Why), so that mechanics text and those scenarios describe a migration
that no longer exists. Replaced by "The payload table's retention is shorter than its parent's"
below, which keeps every scenario about the table's retention behavior and drops only the three
that were specifically about `V11`.

**Migration**: No operator or consumer action. `dial_usage_log_payload`'s TTL, expiry quantization,
and null/empty-document behavior past retention are all unchanged; only the backfill-specific
scenarios are gone because there is no backfill to exercise them against.

### Requirement: The existing bodies are copied before the columns are dropped

**Reason**: A one-shot historical backfill is not worth carrying: it is a risky,
hard-to-rehearse startup-time data migration (it can exhaust ClickHouse server memory) whose only
payoff is freeing disk that ages out on `dial_usage_log`'s existing one-year `TTL` regardless. The
migrations that implemented it (`V11__backfill_dial_usage_log_payload.sql` and
`V12__drop_dial_usage_log_payload_columns.sql`) are removed; `dial_usage_log` keeps its five
verbatim-content columns indefinitely, unpopulated by ingest going forward (see "Body columns are
captured only by the payload table's mapping").

**Migration**: No action for new deployments — `V10` alone provisions `dial_usage_log_payload` and
the dual-write mapping already governs new ingest. A deployment that already applied `V11`/`V12`
(or the equivalent out-of-band DDL) before this change ships is unaffected going forward: this
change deletes the migration files, not any table state a prior run already produced, and Flyway
does not re-run or un-apply a migration once its history row exists. An operator who has not yet
applied them, and was planning to, should not: the historical backfill is no longer part of the
shipped migration set, so there is nothing to run and nothing to record in the Flyway baseline for
it.

## ADDED Requirements

### Requirement: Target tables are provisioned by versioned ClickHouse migrations

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants). Provisioning SHALL be gated by `clickhouse.migrations.enabled` (default true)
and SHALL run before any sync run or startup schema validation; a migration failure SHALL abort
startup. When migrations are disabled, the service SHALL issue no DDL and the operator SHALL provision
the tables out of band by applying the same migration DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, etc.); the
calculated columns (`response_id`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`); and the five
verbatim-content columns carried from `V1`/`V7` (the three bodies and the two JSON documents), which
remain physically present but are governed only by `dial_usage_log_payload`'s mapping — see "Body
columns are captured only by the payload table's mapping". On `dial_usage_log_payload`, the JSON
columns use ClickHouse's `JSON` type, which is generally available across the supported server range
(ClickHouse **25.3 LTS or newer**, verified through **26.3 LTS**) and SHALL require no experimental
enablement setting; the body columns use the `AES_256_GCM_SIV` codec, which requires a server-side
encryption key or `CREATE TABLE` fails. For record-level deduplication to function, `dial_usage_log`
SHALL use the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key (`ORDER BY`)
ending in the deduplication key columns `trace_id` and `core_span_id`, partitioned by
`toYYYYMMDD(request_time)`. The definition SHALL also pin UTC on its timestamp columns, store monetary
columns as `Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as
`Nullable` — including every producer-reported token count — and apply a row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other column its
mapping declares: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`. The
five verbatim-content columns on `dial_usage_log` are neither: they carry no table-side default and are
simply absent from the mapping's column list, so an insert leaves them at their type default (`NULL`
for the three `Nullable(String)` bodies, the empty document `{}` for the two non-nullable `JSON`
columns).

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
  (`_ingested_at`, `_updated_at`) and `dial_usage_log`'s five unmapped verbatim-content columns, which
  read as their type default (`NULL` or `{}`)

### Requirement: Body columns are captured only by the payload table's mapping

The three unbounded body columns — `request_body`, `response_body`, `assembled_response` — SHALL be
persisted to their own ClickHouse table, `dial_usage_log_payload`, and SHALL NOT be persisted to
`dial_usage_log`. This is a mapping-level allowlist decision, not a physical one: `dial_usage_log`
still declares the five columns (they were never dropped), but no mapping targeting that table
captures a value for any of them, so every inserted row leaves them at their type default.

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
  `assembled_response`, `jwt_claims` or `request_tags`, because the mapping for that table no longer
  captures them — even though the physical table still declares all five columns, each reading back at
  its type default (`NULL` or `{}`)

#### Scenario: The claim and header documents move with the bodies

- **WHEN** a record carrying `claims` and `headers` is ingested
- **THEN** the `dial_usage_log_payload` row carries `jwt_claims` and `request_tags`, and the
  `dial_usage_log` row carries neither

### Requirement: The payload table's retention is shorter than its parent's

`dial_usage_log_payload` SHALL declare `TTL toDateTime(request_time) + INTERVAL 90 DAY` together with
`ttl_only_drop_parts = 1`, while `dial_usage_log` retains its one-year `TTL`. Retention SHALL be
declared in the table's own `CREATE TABLE` so that Flyway and an out-of-band operator provision it
identically.

The payload retention SHALL NOT be configurable through the service: it is neither an environment
property nor a `/v1/tables` field, and an operator changes it with
`ALTER TABLE dial_usage_log_payload MODIFY TTL …`. The catalog SHALL NOT mirror it.

Expiry SHALL be quantized to a daily part exactly as the parent's is: a part is dropped once its newest
row has expired, so a payload row MAY outlive its `TTL` by at most the rest of its day.

A payload row SHALL expire independently of the `dial_usage_log` row it belongs to. A query naming a
body column for an event older than the payload retention SHALL return the parent's columns with the
body columns `NULL` — the outcome of a grain `LEFT JOIN` that finds no row — and SHALL NOT fail. The
two document columns (`jwt_claims`, `request_tags`) are non-nullable `JSON` and SHALL read as the empty
document `{}` for such an event — indistinguishable from a request that carried no claims or headers —
so a consumer distinguishing "expired" from "empty" SHALL test a body column's nullness or bound the
query to the payload window; the query surface documents this asymmetry rather than papering over it.

#### Scenario: Provisioned payload table declares the shorter retention

- **WHEN** `dial_usage_log_payload` is provisioned (by Flyway or by an operator applying the migrations
  in version order)
- **THEN** its declaration carries `TTL toDateTime(request_time) + INTERVAL 90 DAY` and
  `ttl_only_drop_parts = 1`, while `dial_usage_log` still carries its one-year `TTL`

#### Scenario: An expired payload leaves its parent row intact

- **WHEN** a day's payload partition expires and is dropped
- **THEN** the corresponding `dial_usage_log` rows remain, with every analytic column intact —
  including `request_body_bytes` and `response_body_bytes`, which outlive the bodies they measure

#### Scenario: Querying a body column past its retention yields null

- **WHEN** a query names a body column for an event whose payload partition has been dropped
- **THEN** the row is returned with that column `NULL`, and the query does not fail

#### Scenario: Querying a document column past its retention yields the empty document

- **WHEN** a query names `jwt_claims` or `request_tags` for an event whose payload partition has been
  dropped
- **THEN** the row is returned with that column `{}`, the same value a request with no claims or headers
  carries, and the query does not fail
