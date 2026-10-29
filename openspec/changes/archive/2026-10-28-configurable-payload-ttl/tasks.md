## 1. Measure the TTL-change mechanism (design D5)

- [x] 1.1 On a throwaway local ClickHouse at the `local_env` version, create copies of the V10 payload table shape, seed day partitions on both sides of a shorter limit, try plain `MODIFY TTL`, `materialize_ttl_recalculate_only = 1`, and `materialize_ttl_after_modify = 0`, and confirm from `system.parts` / `system.part_log` which drop expired partitions without rewriting surviving parts' data; also check `REMOVE TTL` and raising the TTL
- [x] 1.2 Record the measured outcome in design.md D5 (adjust the mechanism there if the measurement contradicts it)

## 2. Configuration

- [x] 2.1 Add `PayloadRetentionProperties` (`sync.payload`: `ttlDays`, `table`); reject `0` and values below `-1` in the compact constructor with a message naming `sync.payload.ttl-days`; document both in the record Javadoc
- [x] 2.2 Add `sync.payload.ttl-days: ${SYNC_PAYLOAD_TTL_DAYS:90}` and `sync.payload.table: ${SYNC_PAYLOAD_TABLE:dial_usage_log_payload}` to `application.yml` (bare lines, no comments)
- [x] 2.3 Unit-test the binding: default `90`, `-1` accepted, `1` accepted, `0` / `-2` rejected naming the property, `30d` fails binding

## 3. Reconciler

- [x] 3.1 Implement `PayloadTtlReconciler` per design D4/D5: read `engine_full`, skip with an INFO log when the table is absent, compare via `InheritedTtl.parse`, otherwise issue a plain `MODIFY TTL` / `REMOVE TTL` with the table quoted as an identifier, and log at WARN with table, previous and new retention
- [x] 3.2 Call it from `ClickHouseMigrationRunner.run` right after `flyway.migrate()`; a rejected `ALTER` propagates and fails startup
- [x] 3.3 Unit-test the decision logic against `engine_full` strings: matching 90 → no statement; 90 vs configured 30 → MODIFY; `-1` vs a TTL → REMOVE; `-1` vs no TTL (settings carry only `ttl_only_drop_parts`) → no statement; an unparseable TTL shape → replaced; absent table → skipped
- [x] 3.4 Integration test on the Testcontainers ClickHouse: after migrations, reconciling with 30 leaves `engine_full` carrying `toIntervalDay(30)` and `ttl_only_drop_parts = 1`; reconciling again issues nothing; `-1` leaves no TTL clause; a hand-set 45 is reverted to the configured value

## 4. Docs and spec

- [x] 4.1 `docs/configuration.md`: add the `SYNC_PAYLOAD_TTL_DAYS` row (one line, `-1` = none, `0` and below `-1` fail startup, ignored when migrations are disabled) and drop "under a 90-day retention" from the `SYNC_PAYLOAD_TABLE` row
- [x] 4.2 Update other text that states a fixed, non-configurable 90-day payload retention or says to change it by hand (`openspec/specs/data-sync/spec.md` outside the modified requirement, `mapping/dial-usage-log-payload.yaml` comments, README/docs); leave frozen Flyway migrations untouched
- [x] 4.3 Confirm the delta spec in this change matches the shipped behaviour

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes
