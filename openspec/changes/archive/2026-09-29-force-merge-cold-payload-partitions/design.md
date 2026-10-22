## Context

See `proposal.md` for the motivation. The facts the design rests on, as observed on a populated
ClickHouse 26.3 deployment unless noted:

- `dial_usage_log_payload` is `ReplacingMergeTree(_updated_at) PARTITION BY toYYYYMMDD(request_time) ORDER BY
  event_id`, 90-day `TTL`, `ttl_only_drop_parts = 1`, hundreds of GiB across its daily partitions.
- `part_log` over a couple of weeks shows the only writers into a partition: the poller into "today" (0 days
  late, the bulk of all parts) and the daily reconciliation into "yesterday" (1 day late, all within minutes
  of 01:00 UTC), plus a rare handful of straggler rows two days late. A partition is finished by roughly D+1 01:10 unless the
  operator-triggered backfill reopens it.
- Merges are healthy (many successful, no errors on this table). A settled day nonetheless stops
  at 3–9 parts and a ~50% duplicate share — the automatic selector stops picking a partition whose few parts
  are similar in size. Partitions that happened to collapse hold zero duplicates.
- `min_age_to_force_merge_seconds` and `min_age_to_force_merge_on_partition_only` are both `0` server-wide;
  the table declares neither. `enable_max_bytes_limit_for_min_age_to_force_merge` is `1` on this build, so a
  force merge respects `max_bytes_to_merge_at_max_space_in_pool` (150 GiB), well above the largest partition
  (tens of GiB).
- Verified on a throwaway 26.3.27.3 container: adding both settings by `ALTER … MODIFY SETTING` collapsed
  existing parts of a table that had never carried them (3 parts, 202,000 rows, 200,000 keys → 1 part,
  200,000 rows) within 30 s at a 5 s threshold, a control table without the setting did not merge, and
  `RESET SETTING` restored the original `SETTINGS` clause and effective values of `0`.

## Goals / Non-Goals

**Goals:**
- A settled ingest day (payload or usage log) ends up as one part with one row per key, without an operator
  running anything.
- The change is reversible with one statement per table and touches no data when applied.

**Non-Goals:**
- Changing what ADAS reads or how: `final = 1` stays on every read, and this is not a substitute for it.
- Fixing the ReplacingMergeTree tables other than `dial_usage_log_payload` and `dial_usage_log`
  (`rate_analytics` is named in the proposal as a follow-up; the unpartitioned tables cannot use it).
- Guaranteeing a merge time. The setting is a lower bound on age, not a schedule.

## Decisions

### 1. A table setting applied by migration, not a server config, not a scheduled `OPTIMIZE`

The setting goes on the table by `ALTER TABLE … MODIFY SETTING` in a new ClickHouse migration, following
`V9__ttl_only_drop_parts`. Alternatives rejected:

- **Server-wide `<merge_tree>` config.** Needs no SQL privilege, but applies to every MergeTree table on the
  server including system tables and other tenants, and does not show in `SHOW CREATE TABLE`. `docs/clickhouse-server.md`
  already offers this route for `ttl_only_drop_parts` as a fallback for deployments that will not grant
  `ALTER SETTINGS`; the same fallback is documented here.
- **Scheduled `OPTIMIZE TABLE … PARTITION … FINAL`.** Deterministic, but it needs a scheduler inside ADAS (a new
  writer-side DDL surface and a new failure mode), it ignores `max_bytes_to_merge_at_max_space_in_pool`, and it
  cannot be expressed in the table's own definition, so an operator provisioning out of band would miss it.
  Kept as an operator tool, not a shipped mechanism.

### 2. 2 days, `172800` seconds

The interval must exceed the gaps between writes into one partition, or a collapsed partition is reopened and
its whole merged part (up to tens of GiB) is rewritten again. Under the shipped pipeline a day D is written by the
poller during D, by the reconciliation run at D+1 01:00–01:09, and — rarely, a handful of rows — a straggler
at D+2 01:00. Each write restarts the age of that partition, so with 2 days the last routine write is at most
about D+2 01:00 and the force merge fires from about D+4. 2 days was chosen as sufficient; it is the smallest
value in the range the measurements support, and it leaves room for the reconciliation to be a few hours late.

The cost of a short interval is exposure to the exceptions, not the routine: a reconciliation that runs more
than 2 days late after an outage, or an operator-triggered backfill of an older day, reopens a collapsed
partition and that day is merged again (up to tens of GiB of rewrite). Both are rare and neither is an error. A
longer interval (the design considered 8 days for a hypothetical weekly job that re-ingests up to 7 days
back) would avoid that at the price of carrying ~50% duplicates for over a week on the partitions users
query most; no such job exists in the shipped pipeline, so it is not designed for.

The value is adjustable in place with `ALTER … MODIFY SETTING`, so revisiting it costs no migration.

### 3. `min_age_to_force_merge_on_partition_only = 1`

Without it the force merge may take any run of old adjacent parts, so a partition with a newer part is merged
piecemeal and can still be left with several. With it, the partition is merged as one range only when every
part is old — which is exactly the "settled day" condition and yields one part. The trade is that one write
into a day restarts the clock for the whole day.

### 4. The payload table and `dial_usage_log`; not `rate_analytics`

The payload table is where the cost is (a partition reaches tens of GiB). `dial_usage_log` is included too:
it has the same engine family, daily partitioning and writers, and its settled days show
the same ~50% duplicates. It costs
almost nothing to include, since its partitions are tens of MiB a day at most, and the same 2 days is right for it
because the writers are the same. Its version column is `_ingested_at` and its key is the five-column sort key,
so "one row per key, latest version" reads differently from the payload table's `event_id` / `_updated_at`; the
spec states both. `rate_analytics` (hundreds of KiB) stays out: at that size neither storage nor `FINAL` cost matters, and
adopting it later is one more `MODIFY SETTING`.

The mechanics test uses a scratch table of the payload table's shape only; `dial_usage_log`'s DDL is pinned by
an assertion and the collapse on its real shape is checked in the local runtime verification, because the
force-merge behaviour is engine-level and does not depend on the key or the version column's name.

### 5. Leave `enable_max_bytes_limit_for_min_age_to_force_merge` alone

It is `1` on 26.3, so the 150 GiB cap bounds a force merge, well above the largest partition. It is not set by
the migration: setting it would pin behaviour that the server's own default already provides, and an older
server's descriptions say the opposite ("ignores `max_bytes_to_merge_at_max_space_in_pool` by default"), so the
default is version-dependent and the migration does not rely on it either way.

### 5a. Vertical merges for the payload table only

Added during review. The payload table has 8 columns outside its sort
key and version, below the default `vertical_merge_algorithm_min_columns_to_activate` of 11, so all of
its merges are horizontal (peaks approaching 1 GiB). Its rows are heavy: tens of KiB each of
`request_body`, `response_body` and `assembled_response` per row on average,
single bodies of several MB, and two JSON columns with dozens of paths. Measured on a throwaway 26.3.27.3
against a synthetic partition of realistic shape (8 parts, bodies drawn to realistic
length quantiles, ~145 KiB a row), `OPTIMIZE … FINAL` of the whole partition, each run twice:

| rows in | horizontal peak | vertical peak | horizontal | vertical |
|---|---|---|---|---|
| 26,348 | 657.66 MiB | 225.90 MiB | 4.3–4.6 s | 3.3–3.8 s |
| 52,696 | 770.77 MiB | 279.78 MiB | 8.0 s | 6.0 s |

Peak memory barely follows row count, so the full-size force merge does not approach the earlier estimate of
~2.5 GiB. The vertical algorithm's extra pass reads only the key columns (a UUID and a timestamp). Both
thresholds are set to `1`: `min_columns` is the one that switches the table over (any value up to 8 would),
and `min_rows = 1` keeps the automatic selector's merges under 131,072 rows vertical too, since they carry the
same heavy rows. ClickHouse merges vertically only into a wide part (above `min_bytes_for_wide_part`, 10 MiB),
so a merge of a few tiny parts into a compact one stays horizontal whatever the thresholds; the first
integration test run caught this on its small scratch table, which now sets `min_bytes_for_wide_part = 0`. `dial_usage_log` is left at the defaults: light rows, and its column count already reaches 11.

### 6. No configuration property

Retention (90 days) is deliberately not configurable through the service; this interval is a property of the
same table for the same reason — it is tied to the table's partitioning and the writers' cadence, not to a
deployment's preference. No `docs/configuration.md` row is added.

## Risks / Trade-offs

- **[A burst of merges on first application]** The setting applies to existing parts, so most existing
  partitions (up to tens of GiB each) become candidates together → merges are throttled by the background pool
  (16 threads) and the server's merge memory share (50% of the server memory limit); a merge that exceeds
  memory fails and retries. Mitigation: apply on a non-critical deployment first and watch `system.merges` and `part_log`; a
  deployment with a small memory limit can start with a longer interval (for example 30 days), then lower it
  step by step with `ALTER … MODIFY SETTING`.
- **[Disk]** A merge writes the new part before dropping the old ones, so it needs about twice the partition's
  size free. → A partition of tens of GiB needs twice that free; running several
  large ones in parallel could exhaust the disk. Watch free space during the first application.
- **[A background merge resets a part's age]** A merge creates a part with a fresh timestamp, so a partition
  merged automatically restarts the 2-day clock. → Acceptable; it only delays the collapse and is a reading of
  the server behaviour, not something the specs promise.
- **[Grant]** The migration issues `ALTER SETTINGS` on the payload table for the first time, so a writer with
  individually granted `ALTER` sub-privileges fails startup until it is granted. → Documented in
  `docs/clickhouse-server.md`, which already lists the grant; a wholesale `ALTER` grant is unaffected, and an
  operator can apply the DDL out of band with `CLICKHOUSE_MIGRATIONS_ENABLED=false`.
- **[Server version]** Verified on 26.3.x only. The two settings are long-standing MergeTree settings, but the
  supported range also names 25.3 (`V9` was verified there). → The integration test runs on the Testcontainers
  image the suite pins, and the task list includes checking the settings exist on the oldest supported server.
- **[Not a guarantee]** The setting is a floor on age. It does not make a stale row disappear on a schedule and
  is not a data-correctness mechanism; `final = 1` remains the correctness mechanism.

## Migration Plan

1. Ship `V12__force_merge_settled_partitions.sql`, whose comment is a short pointer to the `data-sync` spec.
   Flyway applies it at startup; it is metadata-only and returns immediately.
2. On a running deployment, record before/after per-partition part counts and duplicate share (`system.parts`, raw vs unique
   `event_id`) and watch `system.merges` and free disk for the first hour.
3. Rollback: `ALTER TABLE <table> RESET SETTING min_age_to_force_merge_seconds,
   min_age_to_force_merge_on_partition_only` on each of the two tables. It stops further merges being selected; a merge already running
   completes, and rows already collapsed stay collapsed (the operation is not reversible and does not need
   to be).
4. Rollout ordering: the migration has no dependency on the build (no mapping or code reads the setting), so
   an out-of-band operator may apply it before or after the new build.
