## 1. The migration

- [x] 1.1 Add `src/main/resources/db/migration/clickhouse/V11__drop_dial_usage_log_payload_columns.sql`
      dropping `jwt_claims`, `request_tags`, `request_body`, `response_body`, `assembled_response`
      from `dial_usage_log`, `IF EXISTS` on every clause
- [x] 1.2 Keep the file's comment to a pointer at the capability spec and `docs/heavy-columns.md`,
      plus a two-line destructive-operation warning. The rationale (no backfill, the surviving
      `*_bytes` columns, nothing depending on the five, the async reclaim, rolling-deploy direction,
      the required grant) goes to the spec and the docs, not into SQL comments where it cannot be
      validated and goes stale unnoticed — as `V10`'s header already has
- [x] 1.3 Leave `V10__create_dial_usage_log_payload.sql` and Postgres `V38__split_payload_columns.sql`
      untouched, comments included — both are applied migrations whose Flyway checksum is fixed

## 2. Local tooling that the drop makes wrong

- [x] 2.1 `local_env/seed/seed_dial_usage_log.py`: remove `jwt_claims` and `request_tags` from the row
      dict. Note the insert does NOT fail without this — `input_format_skip_unknown_fields` defaults to
      1 (verified on 26.3), so ClickHouse silently drops the two fields. That silence is the reason to
      fix it: the keys would stay in the script looking like seeded values that no longer land
- [x] 2.2 Note in its module docstring that the verbatim-content columns are not seeded at all, so a
      local query naming one of their qualified names reads `NULL` / `{}`
- [x] 2.3 `local_env/clickhouse/config.d/encryption.xml`: the comment says the AES key is for
      "dial_usage_log body columns". Correct it to `dial_usage_log_payload`, keeping the point that
      `V1` still needs the key to provision `dial_usage_log` from scratch before `V11` drops its copies

## 3. Comments that asserted the columns were still physically present

These were all reworded by `2026-09-11-remove-payload-backfill-migrations` (its tasks 2.2-2.6) to say
the columns remain in place. They flip back.

- [x] 3.1 `src/main/resources/mapping/dial-usage-log.yaml` — "the physical dial_usage_log columns stay
      in place, unpopulated by this mapping"
- [x] 3.2 `src/test/java/com/epam/aidial/analytics/sync/mapping/SchemaValidatorTest.java` — "The
      physical dial_usage_log table still declares them (nothing drops them)"
- [x] 3.3 `src/test/java/com/epam/aidial/analytics/service/query/DialUsageLogTestModel.java` — "(the
      physical columns remain, simply unpopulated)"
- [x] 3.4 Confirm no assertion changes are needed in any of them: `SchemaValidator` requires only that
      every mapped column exist on the table and explicitly permits unmapped physical columns, so the
      fixtures model the mapped set and are unaffected by what the physical table drops

## 4. Operator-facing docs

- [x] 4.1 `docs/clickhouse-server.md`: the `ALTER DROP COLUMN` grant is already documented but cites
      `V12__drop_dial_usage_log_payload_columns`, a file that does not exist — renumber both mentions
      to `V11`
- [x] 4.2 `docs/heavy-columns.md` "Upgrading an existing deployment": rewrite for `V10` + `V11`. Must
      state the permanent loss of pre-split content and tell an operator to copy it out first; must
      give the out-of-band order and the per-migration grant; must replace the old rolling-deploy note
      (rolling forward is now safe, only a rollback past the cutover needs `ADD COLUMN`)
- [x] 4.3 `docs/heavy-columns.md` "The payload columns live on their own table": "are **not** columns
      of `dial_usage_log`" was true only at the catalog layer — say it is now true physically too
- [x] 4.4 `README.md`: retitle the "Payload split (V10)" bullet to `V10, V11` and replace "keeps those
      five columns indefinitely, unpopulated by new ingest" with the drop and its data loss

## 5. Test

- [x] 5.1 Add `PayloadColumnDropMigrationIntegrationTest` extending `AbstractClickHouseIntegrationTest`
      (which leaves migrations enabled, so the real Flyway runner provisions the schema under test),
      reusing its `showCreateTable(table)` helper
- [x] 5.2 Assert: the five are absent from `dial_usage_log`; `request_body_bytes` /
      `response_body_bytes` survive (the substring trap — `request_body` is a prefix of
      `request_body_bytes`, and a careless replace has eaten the wrong one before); all five are still
      on `dial_usage_log_payload`; the sort key and skip indexes are intact
- [x] 5.3 Confirm nothing else in the suite inserts the five into `dial_usage_log`

## 6. Spec

- [x] 6.1 Delta spec under `specs/data-sync/`: MODIFY "Target tables are provisioned by versioned
      ClickHouse migrations" and "Body columns are captured only by the payload table's mapping",
      retaining every scenario name (`openspec validate` requires it of a MODIFIED requirement); ADD
      "The verbatim-content columns are dropped from dial_usage_log"
- [x] 6.2 `openspec validate drop-dial-usage-log-payload-columns` passes

## 7. Verify

- [x] 7.1 `./gradlew clean build` passes
- [x] 7.2 Runtime verification against **throwaway** databases — this migration is destructive and must
      not touch the shared local ClickHouse. Create `adas_verify` on both containers, apply V1-V10 by
      hand, seed rows carrying values in all five columns, then apply V11 and confirm the columns are
      gone while `request_body_bytes` / `response_body_bytes` keep their values. Stopping between the
      steps to seed is the thing an integration test structurally cannot do
- [x] 7.3 Boot against those databases with `CLICKHOUSE_MIGRATIONS_ENABLED=false` and read a payload
      column through the grain join (`SELECT event_id, "dial_usage_log_payload.request_body" FROM
      dial_usage_log LIMIT 5` over `execute-sql`) to confirm the read path is intact; drop both
      throwaway databases afterwards and re-check that `default.dial_usage_log` still has its columns
- [x] 7.4 Run the seed script against the throwaway database to confirm task 2.1
