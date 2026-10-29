## Why

`SYNC_USAGE_LOG_TTL_DAYS` and `SYNC_PAYLOAD_TTL_DAYS` treat "unset" as "the default": an unset property
reverts the live `TTL` to one year or 90 days at every start. So removing a property that held a longer
retention deletes data, and a deployment that changed a `TTL` with a manual `ALTER` loses it on upgrade.
Removing configuration should not trigger a destructive action.

## What Changes

- An unset retention property leaves its table's live `TTL` untouched; it is logged at INFO with the
  live clause. Only a set value (`1` or more, or `-1`) is applied. Removing a property keeps the `TTL` it
  last applied.
- `SYNC_PAYLOAD_TTL_DAYS` loses its `90` default; both properties default to unset. Fresh installs keep the
  provisioned one year and 90 days, which the migrations create.
- Returning the payload table to its provisioned retention takes an explicit `90`; no day count equals the
  usage log's provisioned `INTERVAL 1 YEAR`, and `365` is the nearest.
- The payload-within-usage-log bound is checked on effective retentions: the configured value, else the
  live `TTL`. Two set values are checked at startup whatever the migrations setting; with one or both unset
  the check runs in the reconciliation, against the live `TTL`, before any `ALTER`. With both unset nothing
  is altered, and a live violation is logged at WARN.
- No longer **BREAKING** for hand-set `TTL`s: an upgrade with both properties unset changes no table.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `data-sync`: "The payload table's retention does not exceed its parent's" — unset means "leave the live
  `TTL` alone" instead of "the provisioned default", and the bound uses effective retentions.
- `table-management`: "An aligned enrichment inherits its source's TTL" — the source's retention is its live
  `TTL`, not a configured default.

## Impact

- **Config**: `SYNC_PAYLOAD_TTL_DAYS` default changes from `90` to unset; the two `configuration.md` rows.
- **Code**: `IngestTtlReconciler` and the two retention records.
- **Docs**: the release-0.4 upgrade note drops its hand-set-`TTL` warning.
- **No** API, catalog or migration change.
