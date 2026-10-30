## Context

The payload retention is already configurable (`SYNC_PAYLOAD_TTL_DAYS`, archived change
`configurable-payload-ttl`): `PayloadTtlReconciler`, called by `ClickHouseMigrationRunner` right after
Flyway, reads the payload table's `engine_full` and issues `MODIFY TTL` or `REMOVE TTL` when the live clause
differs. `dial_usage_log` has the same physical shape where it matters — `PARTITION BY
toYYYYMMDD(request_time)`, `TTL toDateTime(request_time) + INTERVAL 1 YEAR` (V1) and
`ttl_only_drop_parts = 1` (V9) — so the same mechanism applies. Two things differ:

- Its provisioned retention is in years. The server renders it `toIntervalYear(1)`, and one year is not a
  whole number of days.
- It is the payload table's parent. A payload row is read through the grain `LEFT JOIN` from its usage row;
  the old fixed values (90 days under one year) made "payload outlives parent" impossible, a configurable
  parent makes it possible.

## Goals / Non-Goals

**Goals:**

- One env property sets `dial_usage_log`'s retention; startup makes the live table match it.
- An upgrade with the property unset issues no `ALTER`, and aligned enrichments keep inheriting one year.
- A configuration where bodies outlive their events fails startup.

**Non-Goals:**

- `rate_analytics` retention.
- Re-aligning the TTL of aligned enrichments created under the previous usage-log retention; they keep
  the TTL they inherited at creation, exactly as for the payload property.
- Units other than days in the property.

## Decisions

### D1. Unset means the provisioned year, a value means days

`sync.usage-log.ttl-days: ${SYNC_USAGE_LOG_TTL_DAYS:}`. An empty value binds to `null`, which the record
reads as "the provisioned retention", `INTERVAL 1 YEAR`; `1` or more means that many days; `-1` removes the
`TTL`; `0` and values below `-1` are rejected in the compact constructor, as for the payload record.

Why not a default of `365`: the live clause is `toIntervalYear(1)`, and `INTERVAL 365 DAY` is a different
expression (and a different length across a leap day). Every existing deployment would get an unrequested
`MODIFY TTL` on its largest analytic table at first start, a WARN line it did not cause, and every aligned
enrichment created afterwards would inherit `365 DAY` instead of `1 YEAR`. Treating `365` days as matching
`1 YEAR` would hide a real difference. So the default is the migration's own clause, and an explicit `365`
is honoured as 365 days.

*Alternatives:* a unit-bearing value (`1y`, `365d`) — inconsistent with `SYNC_PAYLOAD_TTL_DAYS` and invites
sub-day units the daily partitioning cannot honour; a separate `SYNC_USAGE_LOG_TTL_YEARS` — two knobs that
can disagree.

### D2. Naming the table: `sync.usage-log.table` on `SYNC_TABLE`

Same pattern as the payload record (`sync.payload.table` on `SYNC_PAYLOAD_TABLE`): the target list carries
no roles, so the reconciler gets its own key reading the same env var as `sync.targets[0].table`.

### D3. One reconciler for both tables

`PayloadTtlReconciler` becomes `IngestTtlReconciler`, which reconciles the usage log, then the payload
table, through the same `command(engineFull, target)` decision. The target is an `Optional<TtlDdl>`: empty
for `-1`, `request_time + 1 YEAR` for an unset usage-log value, `request_time + n DAY` otherwise. A missing
table is skipped per table, so a deployment without a payload target still has its usage log reconciled.
The WARN line keeps its shape and names the year as `1 year(s)`.

*Alternative:* a second reconciler class — duplicated decision code and a second call site in the runner.

### D4. Payload retention must not exceed the usage-log retention, checked at construction

The reconciler's constructor compares the two configured retentions — unset usage log as 365 days (the
shortest a year can be, so the check never admits a payload that outlives its parent), `-1` as unlimited —
and throws `IllegalStateException` naming both properties when the payload's is longer. The reconciler is
an unconditional component, so the check fails startup whether or not migrations run, matching "still
validated". It compares configuration only, never the live tables: an operator-provisioned schema stays
the operator's.

Failing rather than warning: the only effect of such a configuration is bodies kept past the point any
query joins them to their event — disk spent on data the service will not serve, and content retained
longer than the analytic record that justified keeping it.

*Alternatives:* warn only (the misconfiguration persists silently in logs); clamp the payload retention
to the parent's (a silent override of an explicit setting).

### D5. No part rewrite on `dial_usage_log`

The payload measurement (`2026-10-28-configurable-payload-ttl` design, D5) covered the payload table's shape. `dial_usage_log` adds
skip indexes and force-merge settings, so it was measured separately.

Measured on ClickHouse 26.8 on a copy of the migrated shape (V1 through V15: five skip indexes,
`ttl_only_drop_parts = 1`, the two force-merge settings, no projection), with one day partition at 10, 200
and 300 days old, each a single part of order 100 MB of invented rows:

| Variant | Outcome |
|---|---|
| `MODIFY TTL … + INTERVAL 180 DAY` | one `MutatePart` per part reading only `request_time` (8 bytes per row), tens of ms each; the 200- and 300-day partitions became empty parts at once; the surviving part kept its size |
| `MODIFY TTL … + INTERVAL 365 DAY` / `500 DAY` | same reads, nothing dropped, sizes unchanged |
| `REMOVE TTL` | no mutation; every file of every part kept its inode |

Comparing inodes before and after a `MODIFY TTL`: every data column and the packed skip-index file are hard
links; only `request_time`'s own few-KB column files, the packed statistics file (26.8 materializes
statistics on merge by default) and small metadata files are rewritten. The `SETTINGS` clause survives every
variant. After `REMOVE TTL` the parts keep their old per-part TTL info, which was checked to be inert: a
table without a `TTL` dropped no rows on a forced TTL merge, while a control table with the old `TTL` did.

Decision: the same plain `MODIFY TTL` / `REMOVE TTL` as for the payload table, with no setting changed.

### D6. Failure handling

Unchanged from the payload property: a rejected `ALTER` fails startup like a failed migration; replicas
starting together may each issue the same idempotent `ALTER`.

## Risks / Trade-offs

- [A mistyped value deletes a year of usage data] → validation rejects `0` and negatives other than `-1`;
  the WARN line records the old and new retention.
- [A deployment that set the usage-log TTL by hand loses it on upgrade] → BREAKING in the proposal and the
  spec; set `SYNC_USAGE_LOG_TTL_DAYS` first.
- [`SYNC_PAYLOAD_TTL_DAYS=-1` alone now fails startup] → the property is unreleased; the error names both
  properties and the fix.
- [Raising the usage-log retention past the blob ledger's 13 months] → blobs older than 13 months have no
  ledger entry, so a backfill reaching that far ingests them as before the ledger existed and the engine
  collapses the copies (data-sync spec, ingest ledger). No coupling is added.
- [Aligned enrichments keep an inherited year after the parent is shortened] → their rows past the
  parent's retention are unreadable and expire on their own clock; out of scope (Non-Goals).

## Migration Plan

No Flyway migration. With `SYNC_USAGE_LOG_TTL_DAYS` unset the first start compares `1 YEAR` with `1 YEAR`
and does nothing, unless the TTL was changed by hand. Rollback: redeploy the previous version; the table
keeps whatever TTL the last start set, and the older version never touches it.
