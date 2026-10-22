## Why

`dial_usage_log_payload` is a `ReplacingMergeTree(_updated_at)` keyed on `event_id`, and every row is
written twice: once by the near-real-time poller into the current day's partition, and again by the
daily reconciliation run (`sync.cron`, 01:00 UTC, `sync.day-offset` 1) into yesterday's. A
`ReplacingMergeTree` collapses the two only inside a merge, and ClickHouse's automatic merge selector
stops selecting a partition once its parts are a handful of similar-sized pieces — which is exactly what
a day partition looks like once the day is over. Observed on ClickHouse 26.3:
partitions with several parts sit at a ~50% duplicate share indefinitely (about two raw rows per key, in a
partition of tens of GiB), no merge touches them after the day's last write, and `min_age_to_force_merge_seconds`
is `0` so nothing forces one. Merges themselves run and fail on nothing; the table is simply never
merged into one part per day.

`dial_usage_log` is the same shape (`ReplacingMergeTree(_ingested_at)`, daily partitions, written by the
same two writers) and shows the same effect: its settled days with 2–3 parts also sit at ~50% duplicates
(about two raw rows per key). Its partitions are small (tens of MiB a day at most),
so the cost there is negligible; the payload table is where a day reaches tens of GiB.

Reads are correct throughout, because every ADAS read carries `final=1`. The cost is paid instead in
storage (roughly double for a settled day of a 90-day, encrypted, body-carrying table) and in `FINAL`
read work on the partitions users query most.

## What Changes

- A new ClickHouse migration `V12__force_merge_settled_partitions.sql` sets, on `dial_usage_log_payload`
  **and** `dial_usage_log`:
  `ALTER TABLE <table> MODIFY SETTING min_age_to_force_merge_seconds = 172800,
  min_age_to_force_merge_on_partition_only = 1` — 2 days, applied to the whole partition only. The
  migration's own comment is a pointer to the spec; the detail lives in the `data-sync` requirement.
- On `dial_usage_log_payload` only, the same statement also sets
  `vertical_merge_algorithm_min_columns_to_activate = 1, vertical_merge_algorithm_min_rows_to_activate = 1`,
  so its heavy rows merge one column at a time; measured, that cuts a whole-partition merge's peak memory by
  about 65%.
- Once every part of a day partition is older than 2 days, ClickHouse merges the partition into a single
  part, which deduplicates it. The 2 days is chosen to exceed the write gaps the shipped pipeline produces
  (the poller finishes a day at midnight, the reconciliation run rewrites it at 01:00 the next day, and the
  rare straggler lands a day after that), so a partition is not merged and then reopened by the routine
  writers. An operator-triggered backfill of an older day restarts that day's interval.
- No service behaviour, endpoint, catalog field or configuration property changes. The interval is not
  configurable through the service, on the same terms as the table's retention; an operator changes it
  with `ALTER TABLE … MODIFY SETTING` / `RESET SETTING`.
- `docs/clickhouse-server.md` records the new setting, the fact that it takes effect on parts that already
  exist, and its one rollout cost.

Not in scope: `rate_analytics` (typically hundreds of KiB; it can adopt the setting later on the same pattern), the
unpartitioned enrichment/aggregate tables (their single partition is written continuously, so "every part
older than N" never holds), and any scheduled `OPTIMIZE`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: the ingest-table requirements gain one — `dial_usage_log_payload` and `dial_usage_log`
  force-merge a day partition once all its parts are 2 days old, and the settings are provisioned by a
  migration and not configurable.

## Impact

- **Schema/DDL**: new `src/main/resources/db/migration/clickhouse/V12__force_merge_settled_partitions.sql`
  (two metadata-only `MODIFY SETTING` statements; rewrite nothing, need no free space). Applied by the existing
  `ClickHouseMigrationRunner`; an operator with `clickhouse.migrations.enabled=false` applies it out of
  band in version order.
- **Grants**: `dial_usage_log` already needed `ALTER SETTINGS` (`V9`); this is the first migration to `ALTER`
  the payload table — `V10` set its settings inside `CREATE TABLE`. A writer credential granted
  `ALTER` sub-privileges individually therefore needs `ALTER SETTINGS ON dial_usage_log_payload`, or startup
  fails at this migration (by design: a migration failure aborts startup). `docs/clickhouse-server.md`
  already lists that grant; the change makes it required rather than optional. A wholesale `ALTER` grant is
  unaffected.
- **Runtime cost on first application**: the setting applies to parts that already exist, so every
  existing multi-part partition older than 2 days becomes a merge candidate at once (potentially dozens of
  partitions, each up to tens of GiB). Merges run on the shared background pool, need roughly twice a partition's size in free
  disk while they run, and are bounded by the server's merge memory share.
- **Docs**: `docs/clickhouse-server.md`. No `docs/configuration.md` change — no property is added.
- **Tests**: `ClickHouse` integration assertions that each migrated table carries both settings without
  losing its retention clauses, and one that a multi-part partition older than the threshold collapses to one
  part and one row per key (mechanics proven on a scratch table of the payload table's shape).
