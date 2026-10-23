## ADDED Requirements

### Requirement: Reported route is persisted

The service SHALL persist the route a producer reports as having served a request in two columns of
`dial_usage_log`:

- `route_name` holds the route's name as reported. The service SHALL store it verbatim and SHALL NOT
  normalize, lower-case or resolve it against any configuration.
- `route_type` holds the kind of route as reported. The current producer reports `global` (a route
  configured in its `routes` section) or `application` (a route configured under an application). The
  service SHALL store the reported value verbatim and SHALL NOT reject a value outside that pair, so that
  a new route kind ingests without a service change.

Both columns are `Nullable`. A record that carries neither field, which the producer emits for every
request that no route served, SHALL store NULL in both columns and SHALL be ingested normally rather than
dropped. The two fields are independent: a record that reports one and omits the other SHALL store the
reported value and NULL for the other. A blank value is stored as NULL, like an absent one. NULL is
therefore the "not served by a route" signal, so it never collides with a reported value.

A route name is unique only within its kind and, for an application route, within its application. The
service SHALL NOT combine the two columns, or the route columns with `deployment`, into a derived key; a
consumer that needs a unique route identity groups on the columns it needs.

The route columns SHALL NOT change how `event_kind` is classified. `event_kind` stays derived from the
request URI.

This is a behavioral guarantee about the ingested values. The specific JSON source paths remain an
implementation detail of the (overridable) default column mapping and are not pinned here.

#### Scenario: A request served by a global route

- **WHEN** a record reports `route_name` `vector-store` and `route_type` `global`
- **THEN** the ingested row's `route_name` is `vector-store` and its `route_type` is `global`

#### Scenario: A request served by an application route

- **WHEN** a record reports `deployment` `my-app`, `route_name` `index-search` and `route_type`
  `application`
- **THEN** the ingested row's `deployment` is `my-app`, its `route_name` is `index-search` and its
  `route_type` is `application`

#### Scenario: A request no route served

- **WHEN** a record carries neither `route_name` nor `route_type`
- **THEN** the ingested row's `route_name` and `route_type` are both NULL, and the row is ingested
  normally

#### Scenario: Only one of the two fields is reported

- **WHEN** a record reports `route_name` `vector-store` and carries no `route_type`
- **THEN** the ingested row's `route_name` is `vector-store` and its `route_type` is NULL

#### Scenario: A blank route field reads as absent

- **WHEN** a record reports `route_name` as an empty string
- **THEN** the ingested row's `route_name` is NULL

#### Scenario: An unrecognized route kind

- **WHEN** a record reports a `route_type` other than `global` or `application`
- **THEN** the ingested row stores that value as reported, and the record is not rejected

#### Scenario: Rows ingested before the columns existed

- **WHEN** the route columns are added to a table that already holds rows
- **THEN** those rows read NULL in both columns until their source blobs are re-ingested

#### Scenario: Route columns do not reclassify the event

- **WHEN** a record reports `route_type` `global` for a request URI that the `event_kind` classifier does
  not recognize as a route
- **THEN** the ingested row's `event_kind` is whatever the URI classifier yields, regardless of the route
  columns

## MODIFIED Requirements

### Requirement: Target tables are provisioned by versioned ClickHouse migrations

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) and the ingest ledger table (`_dial_internal_ingested_blob`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants, and `ALTER DROP COLUMN` on `dial_usage_log`). Provisioning SHALL be gated by
`clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup schema
validation; a migration failure SHALL abort startup. When migrations are disabled, the service SHALL
issue no DDL and the operator SHALL provision the tables out of band by applying the same migration
DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table. The ingest ledger table is
not an ingest target, but startup schema validation SHALL cover it as well, so a deployment that skipped its
migration does not start.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, the producer's
reported route in `route_name` and `route_type`, etc.); and the calculated columns (`response_id`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`). It SHALL NOT declare
the five verbatim-content columns — the three bodies and the two JSON documents. They were created by
`V1`/`V7`, moved to `dial_usage_log_payload` by `V10` and the mapping cutover, and dropped from
`dial_usage_log` by `V11`; the migration set as a whole therefore leaves them on the payload table
only. The two `*_bytes` columns SHALL survive that drop, because they size a payload without holding
one and must remain answerable past the payload table's retention.

On `dial_usage_log_payload`, the JSON columns use ClickHouse's `JSON` type, which is generally
available across the supported server range (ClickHouse **25.3 LTS or newer**, verified through
**26.8 LTS**) and SHALL require no experimental enablement setting; the body columns use the
`AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE` fails. That key
SHALL also be required to provision `dial_usage_log` from scratch, because `V1` creates its body
columns under the same codec before `V11` drops them. For record-level deduplication to function,
`dial_usage_log` SHALL use the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key
(`ORDER BY`) ending in the deduplication key columns `trace_id` and `core_span_id`, partitioned by
`toYYYYMMDD(request_time)`. The definition SHALL also pin UTC on its timestamp columns, store monetary
columns as `Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as
`Nullable` — including every producer-reported token count and the two route columns — and apply a row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other column its
mapping declares: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`,
  `dial_usage_log_payload` and `_dial_internal_ingested_blob` if they do not exist, before any sync run or schema
  validation

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer,
  verified through 26.8 LTS) with no `allow_experimental_json_type` setting configured
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

#### Scenario: Operator misses the ledger migration

- **WHEN** `clickhouse.migrations.enabled` is false, schema validation is enabled, and the operator
  provisioned every ingest table but not `_dial_internal_ingested_blob`
- **THEN** startup schema validation fails and the service does not start
