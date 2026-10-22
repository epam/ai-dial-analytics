## 1. Batch byte bound default

- [x] 1.1 Change the default in `src/main/resources/application.yml` to
  `max-batch-bytes: ${SYNC_MAX_BATCH_BYTES:134217728}` (bare `key: ${ENV:default}` line, no comment).
- [x] 1.2 Update the `sync.max-batch-bytes` row in `docs/configuration.md` for the new default: 128 MiB,
  why it is sized there (a whole-day run flushes ~4× fewer, larger batches — roughly every 760 of these
  86 KB rows instead of 190 — and the retained row buffer is the whole memory cost because the writer
  streams), with the validated range unchanged.
- [x] 1.3 Confirm `SyncPropertiesTest` still passes unchanged — 128 MiB sits inside 1 MiB …
  `Integer.MAX_VALUE / 6`, so the bounds test needs no edit (verify; do not adjust it).

## 2. TTL drops whole partitions

- [x] 2.1 Add `src/main/resources/db/migration/clickhouse/V8__ttl_only_drop_parts.sql` with
  `ALTER TABLE dial_usage_log MODIFY SETTING ttl_only_drop_parts = 1;` and the same statement for
  `rate_analytics` (multi-statement migration files are already established — see V3).
- [x] 2.2 Write the migration's header comment in the house style (see V7): why a row-level TTL rewrite
  needs headroom equal to the part it rewrites; that daily partitioning keeps expiry exact to within one
  part, so a row may outlive its TTL by at most a day; that `MODIFY SETTING` is metadata-only and
  idempotent and the server default on 25.3 is `0`; that it complements V1/V2 rather than editing an
  applied migration, so an operator running with `clickhouse.migrations.enabled=false` applies it in
  version order; and that it requires the `ALTER SETTINGS` privilege — a different `ALTER TABLE`
  sub-privilege from the column/index ALTERs in V3/V6/V7.
- [x] 2.3 Add a ClickHouse integration test asserting both tables carry the setting, using the existing
  `showCreateTable(String)` helper on `AbstractClickHouseIntegrationTest` (line ~116). Assert the output
  *contains* `ttl_only_drop_parts = 1` (it appears inside a `SETTINGS` clause alongside
  `index_granularity`, verified on 25.3.14.14) rather than matching the whole clause. Keep test method
  names checkstyle-safe: second character lowercase, no two consecutive capitals — e.g.
  `dialUsageLogDeclaresTtlOnlyDropParts` (`Ttl`, never `TTL`).

## 3. Write-path metrics

- [x] 3.1 Add a `MeterRegistry` to `clickhouse/UsageLogWriter` (Lombok `@RequiredArgsConstructor`, so it
  is one field) and update the hand-rolled constructor call in `UsageLogWriterTest` accordingly.
- [x] 3.2 Emit `clickhouse.inserts{table}` once per successful batch insert, and
  `clickhouse.insert.failures{table, reason}` on failure with `reason` = `storage_full` when the failure
  is classified as disk exhaustion (task 4.2) and `other` otherwise. Do not count an empty batch — the
  method already returns early for one.
- [x] 3.3 Document both meters: extend the per-table metric note in `docs/configuration.md` (around the
  existing `sync.rows.inserted` / `poll.rows.inserted` / `backfill.rows.inserted` sentence) and the
  ingest metric list in `README.md`, noting that these two are attributed to the target table rather
  than to an ingest path, and that the insert count is the service's own measure of part pressure.

## 4. Disk-exhaustion classification

- [x] 4.1 Add `exception/ClickHouseStorageFullException` (a `RuntimeException` whose message names the
  target table and the refusal), following the package's existing exception style.
- [x] 4.2 In `clickhouse/UsageLogWriter`, add a package-private `static boolean` predicate that walks the
  cause chain for a `com.clickhouse.client.api.ServerException` whose `getCode()` is 243, mirroring the
  existing private `isConnectivityFailure` walk, with a documented `NOT_ENOUGH_SPACE = 243` constant in
  the style of `ClickHouseBudget`'s 159/241 constants. Match on the numeric code only. In `insert`'s
  catch, throw `ClickHouseStorageFullException` (cause preserved) instead of the generic
  `IllegalStateException` when the predicate matches.
- [x] 4.3 Unit-test the predicate in `UsageLogWriterTest` (it is testable without a live client, which
  `insert` is not): a 243 `ServerException` anywhere in the chain matches; another server error code does
  not; a chain with no `ServerException` does not; a self-referential cause chain terminates.

## 5. Ingest paths act on it

- [x] 5.1 Add a `storageFull` component to `DataSyncService.RunSummary`, set in `ingestDate`'s catch when
  the caught exception's cause chain carries `ClickHouseStorageFullException`, with a distinct ERROR log
  naming disk exhaustion and the target. Keep `runForDate`'s documented never-throws contract. Update the
  `RunSummary` construction sites and the record's Javadoc.
- [x] 5.2 In `service/BlobPollingService`, log the cycle failure distinctly when its cause chain carries
  `ClickHouseStorageFullException`. Put the chain check on the exception class itself as a `public static
  boolean isIn(Throwable)` (guarding against a self-referential cause chain) so `DataSyncService` (5.1)
  and the poller share one implementation — note this is a *different* predicate from 4.2's, which looks
  for a `ServerException` code and lives in the writer. Behavior is otherwise unchanged: watermark held,
  ingested-blob record uncommitted, next cycle retries.
- [x] 5.3 In `service/BackfillRun`, add a terminal transition for an early stop that lands in `FAILED`
  regardless of the per-day tallies, and amend the `State` enum's javadoc (which currently says `FAILED`
  only when every day failed) to cover it.
- [x] 5.4 In `service/DataBackfillService.execute`, break the day loop when a day's summary reports
  `storageFull`, use the early-stop transition, and log ERROR naming the day and disk exhaustion. Do not
  touch `backfill.enabled` and do not add a status-response field.
- [x] 5.5 Test in `DataBackfillServiceTest`: a `storageFull` day stops the loop with later days
  unattempted, the run terminal state is `FAILED` even though an earlier day succeeded, and
  `daysCompleted + daysFailed < daysTotal`; an ordinary failed day still continues through the range
  (existing behavior, assert it still holds).
- [x] 5.6 Test in `DataSyncServiceTest` that a storage-full flush failure surfaces as
  `RunSummary.storageFull()` with `success=false`, and in `BlobPollingServiceTest` that such a cycle
  leaves the watermark unadvanced (extend the existing flush-failure coverage rather than duplicating it).

## 6. Docs and verification

- [x] 6.1 Record the operator-facing storage notes accurately: the byte
  bound governs whole-day runs (~65 parts, 4×, not ~33/8× on daily ingest), the poller emits one part per
  cycle-with-rows per table and is deliberately left at its cadence with the part rate now measured,
  `ttl_only_drop_parts = 1` ships as migration V8 (server default on 25.3 is `0`; needs the
  `ALTER SETTINGS` grant), and bulk loads are held by choice — backfill stays enabled and now stops on a
  243 instead of working through the range.
- [x] 6.2 Run `openspec validate ingest-storage-pressure-guards --strict`.
- [x] 6.3 Run `./gradlew clean build` and confirm it is
  green (checkstyle + unit + integration, including the real Flyway run of V8 against ClickHouse 25.3).
