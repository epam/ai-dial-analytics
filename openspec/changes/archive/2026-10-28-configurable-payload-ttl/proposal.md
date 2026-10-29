## Why

`dial_usage_log_payload` holds the verbatim request and response bodies and is the largest table ADAS
writes. Its 90-day retention is fixed in the V10 migration, which suits no deployment exactly: some cannot
afford 90 days of bodies on disk, others want to keep them indefinitely. Today the only lever is a manual
`ALTER TABLE … MODIFY TTL`, which nothing documents as supported and which a fresh environment does not
reproduce.

## What Changes

- New environment property `SYNC_PAYLOAD_TTL_DAYS` (default `90`) sets the payload table's retention in
  days. `-1` removes the TTL, so payloads are kept indefinitely. `0`, any value below `-1`, and anything that
  is not a whole number fail startup.
- At startup, right after the ClickHouse migrations, ADAS reads the payload table's live `TTL` and, when it
  differs from the configured one, alters the table to match (`MODIFY TTL` for a number of days, `REMOVE TTL`
  for `-1`). Each change is logged at WARN with the old and new retention. A matching TTL is left alone.
- The change takes effect without rewriting the table's existing parts, so it needs no free-disk headroom.
  A shortened retention applies to data already stored: day partitions past the new limit are dropped.
- The environment property becomes the single source of truth for payload retention. A TTL set by hand
  is reverted to the configured value on the next start. **BREAKING** for any deployment that changed the
  payload TTL with a manual `ALTER`: it must set `SYNC_PAYLOAD_TTL_DAYS` to that value before upgrading,
  or the restart restores 90 days.
- The reconciliation runs only where ADAS runs the ClickHouse migrations
  (`CLICKHOUSE_MIGRATIONS_ENABLED=true`); an operator-provisioned schema stays the operator's to change.
- `dial_usage_log` and every other table keep their migration-defined TTLs; this property covers the
  payload table only.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `data-sync`: the requirement "The payload table's retention is shorter than its parent's" changes from a
  fixed, non-configurable 90 days to a configurable retention (default 90 days, `-1` for none) that the
  service reconciles against the live table at startup.

## Impact

- **Config**: new key `sync.payload.ttl-days` / `SYNC_PAYLOAD_TTL_DAYS`; one row in `docs/configuration.md`,
  and the `SYNC_PAYLOAD_TABLE` row stops naming a fixed 90-day retention.
- **Code**: a startup step run by the ClickHouse migration runner after Flyway, using the existing
  `engine_full` inspection and the writer credential (which already holds DDL rights for migrations).
- **Data**: lowering the value permanently deletes payload rows older than the new limit, soon after the
  restart. Raising it or setting `-1` deletes nothing but cannot bring back data already dropped.
- **No** API, catalog or Flyway migration changes; no change to `dial_usage_log`'s one-year TTL.
