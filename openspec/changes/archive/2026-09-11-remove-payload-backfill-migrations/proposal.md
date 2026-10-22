## Why

`V11__backfill_dial_usage_log_payload.sql` could exhaust ClickHouse server memory and had already
been reworked once into a memory-safe design. The one-shot historical backfill (and the column drop
that depends on it having run) is not worth carrying: it is a risky, hard-to-rehearse startup-time data migration whose only payoff is
freeing disk on `dial_usage_log` for data that will age out on its own one-year `TTL` anyway.
`V10` (the `dial_usage_log_payload` table and the dual-write mapping split) stays — new ingest
already writes bodies/claims/headers to the retention-scoped payload table. Only the historical
copy (`V11`) and the subsequent `dial_usage_log` column drop (`V12`) are being dropped from the
plan.

## What Changes

- **BREAKING** (schema-shape, not API): remove `V11__backfill_dial_usage_log_payload.sql` and
  `V12__drop_dial_usage_log_payload_columns.sql`. `dial_usage_log` keeps its five verbatim-content
  columns (`jwt_claims`, `request_tags`, `request_body`, `response_body`, `assembled_response`)
  physically forever — they are simply never populated by the shipped mapping going forward (the
  mapping already stopped declaring them when the split shipped) and age out under the table's
  existing one-year `TTL` for whatever historical data is already there.
- Remove `PayloadBackfillMigrationIntegrationTest.java`, which exists solely to pin `V11`'s SQL
  shape and idempotence.
- Update the doc comment in `ClickHouseMigrationConnectionIntegrationTest` that cites `V11` as an
  example of a data-moving migration (the example no longer exists; the general `final=1` point
  it illustrates still holds for any future data-moving migration).
- Update `docs/heavy-columns.md` and `README.md` sections that describe the V10-V12 upgrade
  procedure (disk headroom, startup time, out-of-band application order, rolling-deploy/rollback
  notes) — those all assumed the copy-then-drop sequence that no longer exists.
- Update `openspec/specs/data-sync/spec.md`:
  - Remove the requirement "The existing bodies are copied before the columns are dropped" and
    its scenarios (wholly about V11/V12).
  - Remove the backfill-mechanics paragraphs and backfill-specific scenarios from "The payload
    table carries a shorter retention than its parent" (V11's memory-safety/idempotence design),
    keeping the parts that describe the payload table's own retention behavior, which V10 alone
    already provides.
  - Correct "Scenario: The body columns are absent from dial_usage_log" — the columns are no
    longer *dropped*, only unpopulated; the inserted row carries no value for them, but the table
    still declares them.
  - Remove the "Operator applies the split migrations out of order" scenario under "Target table
    provisioning and schema" (there is no longer a drop-after-copy ordering hazard).

## Capabilities

### Modified Capabilities
- `data-sync`: the ClickHouse migration set for the payload split no longer includes a historical
  backfill or a column drop; `dial_usage_log` keeps the five legacy columns indefinitely,
  unpopulated by new ingest.

## Impact

- `src/main/resources/db/migration/clickhouse/V11__backfill_dial_usage_log_payload.sql` — deleted.
- `src/main/resources/db/migration/clickhouse/V12__drop_dial_usage_log_payload_columns.sql` —
  deleted.
- `src/test/java/com/epam/aidial/analytics/integration/PayloadBackfillMigrationIntegrationTest.java`
  — deleted.
- `src/test/java/com/epam/aidial/analytics/integration/ClickHouseMigrationConnectionIntegrationTest.java`
  — doc comment only.
- `docs/heavy-columns.md`, `README.md` — upgrade-procedure sections rewritten.
- `openspec/specs/data-sync/spec.md` — requirements/scenarios updated per above.
- No production code changes: no Java class exists solely to run V11/V12, and no controller,
  service, or mapping references either file by name.
