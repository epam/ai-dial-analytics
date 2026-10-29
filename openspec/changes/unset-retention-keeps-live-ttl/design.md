## Context

`configurable-usage-log-ttl` (archived) made both ingest-table retentions configurable, with "unset" meaning
the provisioned default: `INTERVAL 1 YEAR` for `dial_usage_log`, `90` days for the payload table. The
startup reconciliation made the live table match every time, so the properties were the single source of
truth. The cost: removing a property that held a longer retention deletes data on the next start, and an
upgrade reverts any `TTL` an operator changed by hand. Neither property has shipped in a release yet, so the
default can change without a deprecation path.

## Goals / Non-Goals

**Goals:**

- Removing or never setting a property never alters a table.
- Keep the payload-within-usage-log bound wherever ADAS itself would create a violation.

**Non-Goals:**

- A "reset to provisioned" switch. Setting `90` restores the payload table's; `365` days is the nearest to,
  but not the same as, the usage log's provisioned year (see
  `openspec/changes/archive/2026-10-28-configurable-usage-log-ttl/design.md`).
- Enforcing the bound on TTLs ADAS does not manage.

## Decisions

### D1. Unset means "leave the live TTL alone"

Both records keep `ttlDays` as a nullable `Integer`; `null` (unset) means the reconciler skips the table and
logs its live clause at INFO. `SYNC_PAYLOAD_TTL_DAYS` becomes `${SYNC_PAYLOAD_TTL_DAYS:}`, like the usage-log
property. With nothing to default, the usage-log record's provisioned-year target and the 365-day
equivalence for an unset value go away.

*Alternative:* keep defaults and add a confirmation switch before a destructive change. Rejected: a second
setting, set once and left on, and an upgrade still alters every hand-set table.

### D2. The bound is judged on effective retentions

The bound protects against ADAS creating a payload that outlives its parent. With unset meaning "live",
the effective retention of a table is its set value, or else its live `TTL`:

- **Both set.** Checked in the reconciler's constructor from the values alone, so it fails startup even
  with migrations disabled, as before.
- **Exactly one set.** Checked in `reconcile()` after reading both `engine_full`s and before any `ALTER`,
  against the unset side's live `TTL`. It needs the live table, so it runs only with migrations enabled.
- **Neither set.** ADAS alters nothing, so it creates nothing; a live violation is logged at WARN rather
  than failing startup on a state the operator owns.

A live `TTL` becomes days through `InheritedTtl.parse`. Calendar units have no fixed length, so each side
takes the reading that keeps the check conservative: the payload's widest (a month as 31 days, a year as
366, hours rounded up) against the usage log's narrowest (28, 365, rounded down). A live `TTL` the parser cannot read
skips the bound with a WARN: failing startup over a shape ADAS did not write would block the set side for
no protection. A missing table skips it quietly, because then no payload row can outlive an event row.

Like units compare their intervals directly, so `1 YEAR` against `1 YEAR` passes; the day conversion applies
only across units. The effective value is carried as `Optional<Optional<TtlDdl>>` (outer: known, inner: has a
`TTL`), which is local to the bound and never leaves the class.

*Alternative:* compare like units exactly and refuse mixed ones. Rejected: `SYNC_PAYLOAD_TTL_DAYS=90`
against the provisioned `1 YEAR` is the common case, and it mixes units.

### D3. Logging

Unset: INFO, "retention is not set; keeping its live TTL <clause>". Applied: the existing WARN with the old
and new retention. Live violation with both unset: WARN naming both tables and both clauses.

### D4. ALTER order

Both final states honour the bound, but the two `ALTER`s are separate statements, and the second can fail
(for example a writer granted `ALTER TTL` on one table only). The usage log goes first only when the
payload's live `TTL` fits under the new usage-log value, which proves the intermediate state (old payload,
new usage log) safe without reading the usage log's live `TTL`; a new usage-log value of `-1` always counts as
fitting. Otherwise the payload goes first, which is safe whenever the new usage-log value is no longer than
the live one's narrowest reading, since the new payload fits under the new, and so the old, usage log.

Some states have no provably safe order: both live `TTL`s in the same calendar unit with the set values inside
its day spread (live `1 MONTH` on both, `30` set on both: either intermediate state lets a payload outlive its
event in some month), or an unreadable live `TTL`. The payload goes first there, and the spec says so rather
than promising more.

*Alternative:* "payload first when the usage log shrinks". Rejected: with calendar units "shrinks" has no
exact answer — `30` days against a live `1 MONTH` may or may not be shorter — and the conservative reading
put the payload first in a case where only the other order was provably safe.

## Risks / Trade-offs

- [Drift is invisible] → the INFO line records the live `TTL` on every start.
- [The bound against a live TTL needs migrations enabled] → with migrations disabled ADAS alters nothing, so
  it cannot create the violation either.
- [An operator expects removal to restore the default] → the configuration rows and the upgrade note say
  removal keeps the last `TTL`, that `90` restores the payload's, and that `365` is the nearest to the year.

## Migration Plan

No migration. An upgrade with both properties unset issues no `ALTER`, whatever the tables carry. The
release-0.4 upgrade note loses its hand-set-`TTL` warning.
