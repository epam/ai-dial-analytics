## Why

`SYNC_PAYLOAD_TTL_DAYS` makes the payload table's retention configurable, but `dial_usage_log` keeps the
one-year `TTL` fixed by the V1 migration. Deployments differ here too: some must not keep usage records a
full year, and others need more than a year of history for year-over-year reporting. The only lever is
still a manual `ALTER TABLE … MODIFY TTL`, which nothing supports and a fresh environment does not
reproduce.

## What Changes

- New environment property `SYNC_USAGE_LOG_TTL_DAYS` (`sync.usage-log.ttl-days`) sets `dial_usage_log`'s
  retention in days. Unset keeps the provisioned one year (`INTERVAL 1 YEAR`); `-1` removes the TTL. `0`,
  any value below `-1`, and anything that is not a whole number fail startup, as for the payload property.
- The startup reconciliation that already aligns the payload table's live `TTL` also aligns
  `dial_usage_log`'s: `MODIFY TTL` or `REMOVE TTL` when they differ, logged at WARN, no statement when they
  match. Same gate (`CLICKHOUSE_MIGRATIONS_ENABLED=true`), same no-rewrite guarantee.
- The payload retention must not exceed the usage log's: startup fails when `SYNC_PAYLOAD_TTL_DAYS` is
  longer than `SYNC_USAGE_LOG_TTL_DAYS` (one year counting as 365 days, `-1` as unlimited). A payload row
  that outlives its parent is no longer reachable through the parent. **BREAKING** relative to the
  unreleased payload property: `SYNC_PAYLOAD_TTL_DAYS=-1` now also needs `SYNC_USAGE_LOG_TTL_DAYS=-1`.
- The environment property becomes the single source of truth for the usage-log retention. **BREAKING**
  for a deployment that changed `dial_usage_log`'s `TTL` with a manual `ALTER`: it must set
  `SYNC_USAGE_LOG_TTL_DAYS` to that value before upgrading, or the restart restores one year.
- `rate_analytics` keeps its migration-defined one year.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `data-sync`: "The payload table's retention is shorter than its parent's", renamed "…does not exceed its
  parent's", gains the configurable usage-log retention, its startup reconciliation, and the rule that the
  payload retention does not exceed it.
- `table-management`: "An aligned enrichment inherits its source's TTL" and "Table TTL" stop describing
  `dial_usage_log`'s retention as a fixed, migration-managed year.

## Impact

- **Config**: new key `sync.usage-log.ttl-days` / `SYNC_USAGE_LOG_TTL_DAYS`, and `sync.usage-log.table`
  reading the existing `SYNC_TABLE`; one new row in `docs/configuration.md`.
- **Code**: the payload `TTL` reconciler generalises to both ingest tables; the cross-property check runs
  at startup whether or not migrations run.
- **Data**: lowering the value permanently deletes usage rows older than the new limit soon after the
  restart. Aligned enrichments created afterwards inherit the new `TTL`; existing ones keep theirs.
- **No** API, catalog or Flyway migration changes.
