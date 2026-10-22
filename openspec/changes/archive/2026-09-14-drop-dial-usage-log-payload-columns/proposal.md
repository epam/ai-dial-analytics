## Why

The payload split shipped as a **catalog and mapping cutover only**. Postgres
`V38__split_payload_columns.sql` re-pointed the five `column_mapping` rows onto
`dial_usage_log_payload` and `mapping/dial-usage-log.yaml` stopped capturing them, but the physical
columns were never removed from `dial_usage_log`: the drop migration depended on a historical
backfill, that backfill could exhaust ClickHouse memory, and the archived change
`2026-09-11-remove-payload-backfill-migrations` deleted both. Its design parked a drop-only
migration as a possible future change, should the columns need to be physically gone. This is
that change.

What the columns cost today is not theoretical. They are almost every byte of `dial_usage_log` —
on the order of hundreds of GB uncompressed for a few million rows, essentially all of it
bodies — and they are the reason `FINAL` over that table is expensive: `Code: 241`
`MEMORY_LIMIT_EXCEEDED` failures of reader queries cluster on queries touching a
blob column under `FINAL`. And nothing can read them: since `V38` the bare names resolve on no ADAS
surface at all.

## What Changes

- **BREAKING** (schema-shape and data, not API): add
  `V11__drop_dial_usage_log_payload_columns.sql`, dropping `jwt_claims`, `request_tags`,
  `request_body`, `response_body` and `assembled_response` from `dial_usage_log`.
- **There is deliberately no backfill.** The body, claim and header content of every row ingested
  before the mapping cutover is deleted with the columns and is not recoverable from the database.
  That content was already unreachable through every ADAS surface; an operator who wants it copies
  it out before deploying. This is the trade the archived design flagged as needing its own
  decision, and it has been taken explicitly.
- `request_body_bytes` and `response_body_bytes` stay: 4 bytes each, and they must outlive the
  payload they describe.
- `local_env/seed/seed_dial_usage_log.py` stops putting `jwt_claims` / `request_tags` into the row
  it POSTs to `dial_usage_log`. The insert does not break — `input_format_skip_unknown_fields`
  defaults to 1, verified on 26.3, so ClickHouse silently discards them — which is exactly why the
  keys have to go: left in, they are seed values that appear to be written and are not.
- Add `PayloadColumnDropMigrationIntegrationTest`, which reads the live DDL. Nothing in the suite
  currently enumerates `dial_usage_log`'s physical column list, and `SchemaValidator` tolerates
  physical columns no mapping names, so without it the drop would ship unasserted.

## Capabilities

### Modified Capabilities
- `data-sync`: `dial_usage_log`'s physical column set no longer includes the five verbatim-content
  columns; they exist only on `dial_usage_log_payload`.

## Impact

- `src/main/resources/db/migration/clickhouse/V11__drop_dial_usage_log_payload_columns.sql` — new.
- `src/test/java/com/epam/aidial/analytics/integration/PayloadColumnDropMigrationIntegrationTest.java`
  — new.
- `local_env/seed/seed_dial_usage_log.py` — stops seeding the two document columns.
- `docs/heavy-columns.md`, `docs/clickhouse-server.md`, `README.md` — upgrade procedure, the
  `ALTER DROP COLUMN` grant's migration number, and the split's version list.
- `src/main/resources/mapping/dial-usage-log.yaml`,
  `local_env/clickhouse/config.d/encryption.xml`, `SchemaValidatorTest`, `DialUsageLogTestModel` —
  comments that asserted the columns were still physically present.
- No production Java changes: no class names these columns, and the catalog already publishes them
  from the payload table.
- `V10__create_dial_usage_log_payload.sql` and Postgres `V38__split_payload_columns.sql` are **not**
  touched, not even their comments — both are applied migrations whose Flyway checksum is fixed.
