## MODIFIED Requirements

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
query, an enrichment scan, an aggregate refresh — treats an unmatched payload as absent and final, and
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

