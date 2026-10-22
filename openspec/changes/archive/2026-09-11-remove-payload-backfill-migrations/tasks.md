## 1. Remove the migrations and their dedicated test

- [x] 1.1 Delete `src/main/resources/db/migration/clickhouse/V11__backfill_dial_usage_log_payload.sql`
- [x] 1.2 Delete `src/main/resources/db/migration/clickhouse/V12__drop_dial_usage_log_payload_columns.sql`
- [x] 1.3 Delete `src/test/java/com/epam/aidial/analytics/integration/PayloadBackfillMigrationIntegrationTest.java`

## 2. Fix stale references to V11/V12 in surviving tests and mapping comments

- [x] 2.1 `ClickHouseMigrationConnectionIntegrationTest.java`: reword the class doc-comment's
      "as V11's payload backfill" example so it no longer cites a migration that does not exist,
      while keeping the point about `final=1` applying to any data-moving migration. Also fixed the
      same example in `ClickHouseMigrationRunner.java`'s class doc-comment.
- [x] 2.2 `mapping/dial-usage-log.yaml`: update the comments that say the five columns are "gone"
      because "ClickHouse V12" dropped them — they are gone from the mapping, not from the table;
      reword to say they remain physically present, unpopulated, since V12 was removed
- [x] 2.3 `mapping/dial-usage-log-payload.yaml`: fix the comment citing "ClickHouse V12" as having
      dropped the physical columns
- [x] 2.4 `SchemaValidatorTest.java`: reword the "gone from this table as of V12" comment; verified
      the test's fixtures/assertions are unaffected (see 2.7)
- [x] 2.5 `MappingFileLoaderTest.java`: reword the "since V12 dropped..." comment
- [x] 2.6 `MappingEngineTest.java`: reword the "V12 dropped them from dial_usage_log" comment.
      Also reworded the equivalent comment in `DialUsageLogTestModel.java`, found during the sweep.
- [x] 2.7 Confirmed: `SchemaValidatorTest`'s `liveSchema()`/`payloadLiveSchema()` fixtures model the
      *mapped* column set, and `SchemaValidator` only requires mapped columns to exist in the table
      (unmapped extra columns are explicitly permitted — see "Fail-fast startup validation against
      the table schema" / "Unmapped table-default column is permitted"), so none of
      `SchemaValidatorTest`, `MappingFileLoaderTest`, `MappingEngineTest`, `DialUsageLogTestModel`
      needed an assertion change — comments only.
- [x] 2.8 (discovered during apply, not in the original plan) Left
      `V10__create_dial_usage_log_payload.sql` (ClickHouse) and
      `V38__split_payload_columns.sql` (Postgres) untouched, including their now-stale mentions of
      `V11`/`V12`. Both are already-shipped, already-applied migrations (V10 is already
      applied on existing deployments; V38 shipped in the same archived change) and
      this repo's convention (stated in `V7`'s own header comment) is that an applied migration's
      checksum is fixed — editing even a comment changes the file's Flyway checksum and would fail
      validation on the next real deploy. Their comments now describe a three-migration design that
      no longer matches reality; that mismatch is accepted as the cost of not touching applied
      migration history. `mapping/*.yaml` and `docs/heavy-columns.md`/`README.md` are not migrations
      and were corrected instead.

## 3. Update operator-facing docs

- [x] 3.1 `docs/heavy-columns.md` "Upgrading an existing deployment" section: removed the
      disk-headroom/startup-time/out-of-band-order/rolling-deploy guidance that assumed
      `V11`→`V12` ran, replaced with a short note that the payload split (`V10`) provisions
      cleanly on its own and that `dial_usage_log`'s legacy columns are left in place
- [x] 3.2 `README.md`: replaced the "Disk headroom for the payload split (V10–V12)" bullet with a
      short "Payload split (V10)" bullet with no V11/V12-specific guidance

## 4. Sync the delta spec into the main spec (handled by /opsx:archive, not a manual task)

- [x] 4.1 Verified `openspec/changes/remove-payload-backfill-migrations/specs/data-sync/spec.md`
      reflects the intended end state; `openspec validate remove-payload-backfill-migrations`
      passes (no separate action needed here beyond review — archive performs the merge)

## 5. Verify

- [x] 5.1 `./gradlew clean build` passes with the migrations and test removed (BUILD SUCCESSFUL)
- [x] 5.2 Grepped the repo for remaining `V11`/`V12` ClickHouse-migration references (excluding
      archived changes): only `V10.sql`/`V38.sql` (deliberately untouched, see 2.8) and unrelated
      Postgres `V11`/`V12` migrations remain
