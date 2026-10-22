## 1. Migration

- [x] 1.1 Confirm the next free ClickHouse slot: list `src/main/resources/db/migration/clickhouse/` on
  the main line (V11 is the highest at the time of writing) and number against merged history only.
  (Note: an earlier `V12__drop_dial_usage_log_payload_columns.sql` existed briefly and was renumbered to
  V11; a deployment whose Flyway history still records that old V12 would fail validation, which is not
  this change's to fix.)
- [x] 1.2 Add `V12__force_merge_settled_partitions.sql` with one statement per table,
  `ALTER TABLE dial_usage_log_payload MODIFY SETTING min_age_to_force_merge_seconds = 172800,
  min_age_to_force_merge_on_partition_only = 1;` and the same for `dial_usage_log`. (Revised after the first
  version: `dial_usage_log` added, and the header comment cut to a few lines
  pointing at the `data-sync` spec, which now carries the rationale, the 2-day reasoning, the rollout cost and
  the grant note.)
- [x] 1.3 Do not touch `V9`, `V10` or `V11` (applied migrations have fixed checksums).
- [x] 1.4 Measure horizontal against vertical merge memory on a payload-shaped partition, then add
  `vertical_merge_algorithm_min_columns_to_activate = 1, vertical_merge_algorithm_min_rows_to_activate = 1` to the
  payload table's `V12` statement. (Added during review; numbers in design decision 5a. The
  integration tests assert the settings on the payload table, their absence on `dial_usage_log`, and that the
  scratch table's force merge is recorded as `Vertical` in `system.part_log`.)

## 2. Tests

- [x] 2.1 Extend `IngestTableRetentionSettingsIntegrationTest` with a test asserting `SHOW CREATE TABLE
  dial_usage_log_payload` contains `min_age_to_force_merge_seconds = 172800` and
  `min_age_to_force_merge_on_partition_only = 1`, and still contains `ttl_only_drop_parts = 1`, the
  `PARTITION BY toYYYYMMDD(request_time)` clause and the 90-day `TTL`. (Revised: the same assertion added for
  `dial_usage_log` with its one-year `TTL`.)
- [x] 2.2 Add an integration test (extending `AbstractClickHouseIntegrationTest`) that proves the mechanics on
  a scratch table of the same shape: create it without the settings, `SYSTEM STOP MERGES`, insert the same
  keys in several inserts with different `_updated_at`, assert more than one part and duplicates, then
  `ALTER … MODIFY SETTING` with a threshold of a few seconds plus `on_partition_only = 1`, `SYSTEM START MERGES`,
  and poll (bounded, no fixed sleep) until the partition is one part with one row per key holding the latest
  version. A real 2-day wait is impossible in a test, which is why the mechanics are proven on a scratch table
  and the shipped value by 2.1.
  (`PayloadForceMergeIntegrationTest`. The threshold is 15 s rather than "a few" so the negative case in 2.3
  has a window to be observed in. The parts are lopsided (one large, small rewrites) because that is the
  shape the automatic selector leaves alone; checked once by removing the `MODIFY SETTING`, after which the
  partition did not collapse within 45 s.)
- [x] 2.3 In the same test add the negative case: a partition with a part younger than the threshold is not
  force-merged, and `RESET SETTING` afterwards leaves the table's declaration without either setting.
  (A second partition of the same table gets a late write just before the setting lands; it is asserted
  unmerged at the moment the settled partition collapses, with a guard that its young part is still under the
  threshold. After `RESET SETTING` the declaration equals the one captured at create. A separate test covers
  re-applying the settings being a no-op.)
- [x] 2.4 Confirm the Testcontainers ClickHouse image the suite pins accepts both settings and the
  `RESET SETTING` form, and record the oldest supported server version on which the two settings were
  verified (or note that only 26.3.x was) in `docs/clickhouse-server.md`.
  (The pinned `clickhouse/clickhouse-server:26.3` image accepts both, `SYSTEM STOP/START MERGES` and
  `RESET SETTING`. Only 26.3.x is verified; the doc says 25.3 is unverified.)

## 3. Docs and specs

- [x] 3.1 Update `docs/clickhouse-server.md`: a short section stating the setting, that it applies to parts that
  already exist (so the first application on a populated table starts many merges), the disk (≈2× partition
  size) and merge-memory cost, how to stage it with a longer interval, and how to remove it. In the
  "Narrowing `ALTER` to sub-privileges" section, say that `ALTER SETTINGS` on `dial_usage_log_payload` is now
  exercised by `V12` (today it names only `dial_usage_log` and `rate_analytics` as users of the grant). (Revised:
  the section also covers `dial_usage_log`, in the heading, the SQL, the staging and the removal steps.)
  (Also corrected the stale "before V12 drops them" in the encryption section to V11, since V12 now names
  this migration, and extended the server-wide `config.d` fallback bullet to the two new settings.)
- [x] 3.2 Confirm the delta spec `specs/data-sync/spec.md` matches what shipped (interval, both settings,
  not configurable, metadata-only, effect on existing parts) and that `openspec validate
  force-merge-cold-payload-partitions` passes. The pre-existing `openspec validate --specs` failure is not
  this change's.
- [x] 3.3 No `docs/configuration.md` change: this change adds no property. Record that in the summary rather
  than adding a row.

## 4. Verification

- [x] 4.1 `./gradlew clean build` passes (compile, checkstyle, tests).
