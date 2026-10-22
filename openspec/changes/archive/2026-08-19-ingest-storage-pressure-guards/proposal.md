## Why

A ClickHouse volume can effectively run out of disk: bulk historical loads fail with
`Code: 243 NOT_ENOUGH_SPACE` and daily ingest survives only inside the sliver of space background
merges hand back. Capacity is an operator concern, but
three of the contributing factors are ADAS's own: whole-day ingest runs create far more parts than
they need to, the shipped one-year retention `TTL` would need free disk to fire at all because it
rewrites parts rather than dropping them, and a disk-exhaustion failure is indistinguishable from any
other insert failure — so a backfill keeps marching through every remaining day, hammering a full disk
and reporting nothing more useful than "ClickHouse insert failed".

## What Changes

- **Fewer, larger parts per whole-day ingest run.** Raise the shipped `sync.max-batch-bytes` default
  from 32 MiB (`33554432`) to 128 MiB (`134217728`). The byte bound governs the accumulated-byte
  *estimate*, which over-counts strings ~2× for escaping headroom, so on DIAL rows of ~86 KB a run
  flushes roughly every 760 rows instead of every 190 — a ~50k-row day drops from ~260 parts to ~65
  in a single daily partition. The validated range (1 MiB … `Integer.MAX_VALUE / 6`) is unchanged, so
  this is a default move, not a contract move.
- **Part creation becomes measurable.** The ingest write path counts the batch inserts it issues, per
  target table (`clickhouse.inserts{table}`). One insert creates one part per partition it touches, so
  this is the service's own measure of the part pressure it imposes; the query surface reaches only
  catalog-registered tables and `system.parts` is not one of them, so this counter is the only route to
  that number from inside ADAS.
- **Retention drops whole partitions instead of rewriting parts.** A new ClickHouse migration sets
  `ttl_only_drop_parts = 1` on `dial_usage_log` and `rate_analytics`. Both are partitioned by
  `toYYYYMMDD(request_time)` with a one-year row-level `TTL`, so expiry stays exact to within one
  partition (a row may outlive its TTL by up to the day its part covers) while needing no free disk to
  delete anything. `MODIFY SETTING` is metadata-only: no mutation, no rewrite. Verified against
  ClickHouse 25.3.14.14, where the server default is `0`.
- **A disk-full insert is classified and stops the work.** ClickHouse error 243 (`NOT_ENOUGH_SPACE`)
  is recognised on the ingest write path by its numeric code, surfaced as its own exception, logged
  distinctly and counted (`clickhouse.insert.failures{table, reason}`). A backfill run whose day fails
  that way **stops immediately**, reaching a failed terminal state with its remaining days
  unattempted, rather than attempting every one of them. The poller is unchanged in behavior — the
  watermark still holds and the cycle still retries — so daily ingest resumes on its own once space
  returns.
- Backfill stays enabled. Nothing here disables a capability; the guard makes an already-doomed run
  fail in one day's work instead of the whole range.

Not breaking: no endpoint, request, or response *shape* changes (the backfill status response gains no
field); no column, sort-key, or partitioning change; no data migration. One status *value* changes in a
situation that could not previously arise: a run that stops early reports `failed` rather than
`completed`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: the ingest-observability requirement gains the per-table batch-insert count and a
  reason-tagged insert-failure count; a new requirement names the disk-exhaustion failure mode
  (distinct classification, watermark held, cycle retried); a new requirement puts whole-partition
  `TTL` expiry on the target table.
- `data-sync-backfill`: a run SHALL abort its remaining days when a day fails because ClickHouse
  refused the write for lack of disk space, instead of continuing through the range, and SHALL be
  distinguishable from a run that covered its whole range.
- `rate-analytics`: its target table carries the same whole-partition-drop retention requirement as
  `dial_usage_log`.

## Impact

- **Config**: `sync.max-batch-bytes` default in `src/main/resources/application.yml`; description and
  default in `docs/configuration.md` (the single source for property descriptions). No new property,
  no env-var rename.
- **Schema/DDL**: new `src/main/resources/db/migration/clickhouse/V8__ttl_only_drop_parts.sql`
  (`ALTER TABLE … MODIFY SETTING ttl_only_drop_parts = 1` for both ingest targets). Applied by the
  programmatic Flyway runner, or out of band in file order where `clickhouse.migrations.enabled=false`.
- **Credential**: the migration needs the `ALTER SETTINGS` privilege on both tables (`MODIFY SETTING`
  is one of its aliases). That is a *different* `ALTER TABLE` sub-privilege from the ones earlier
  migrations exercise (`ALTER ADD COLUMN`, `ALTER MODIFY COLUMN`, `ALTER ADD INDEX`,
  `ALTER MATERIALIZE INDEX`), so a deployment that grants ALTER sub-privileges individually rather than
  wholesale must add it or startup fails on V8.
- **Code**: new `exception/ClickHouseStorageFullException`; error-code classification and the two new
  meters in `clickhouse/UsageLogWriter` (which gains a `MeterRegistry`); a `storageFull` flag on
  `DataSyncService.RunSummary` set in `ingestDate`; an early-stop terminal transition on
  `service/BackfillRun` and the loop break in `service/DataBackfillService`; distinct failure logging in
  `service/BlobPollingService`.
- **Observability**: two new meters (`clickhouse.inserts`, `clickhouse.insert.failures`); README's
  ingest metric list and the per-table metric note in `docs/configuration.md`.
- **Out of scope**: the table-management write path (`CatalogRowWriter`) is request-scoped and would
  still surface a 243 as a generic 500 — no `ErrorView` mapping is added. Poll cadence
  (`poll.poll-cron`) is deliberately left alone; see design.md.
- **Operational**: the expected part reduction is 4× on whole-day runs, not 8× on daily ingest.
