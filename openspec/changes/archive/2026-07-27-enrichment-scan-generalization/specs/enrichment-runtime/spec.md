## ADDED Requirements

### Requirement: Catalog-configured scan cursor columns

The scan's total-order columns SHALL be resolved from the catalog rather than hardcoded per source.
Each `table_definition` SHALL carry an optional `version_column` and `identity_column`; together they
define the source's unique total order `(version_column, identity_column)` used for keyset paging,
the safety-lag predicate, and the cursor tuple. `dial_usage_log` SHALL be configured with
`(_ingested_at, event_id)`, preserving the originally shipped behaviour exactly. A scan of a source
with either column unconfigured SHALL be rejected with HTTP 400 `bad_request` — the same status and
code the hardcoded branch produced — and a message naming the missing scan-metadata configuration
rather than a phase limitation.

The two columns SHALL be catalog-level configuration set by operators (migration-seeded); they are
NOT accepted as caller input on the table-management API in this change, because the exactly-once
guarantee depends on `version_column` being assigned at ingest, monotonic, and never backdated —
a physical property the table API cannot verify.

#### Scenario: A configured source scans using its catalog-declared order

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule whose source declares `version_column` and
  `identity_column`
- **THEN** the scan orders, seeks, safety-lags, and builds its cursor on that `(version, identity)`
  tuple, and `dial_usage_log` behaves exactly as it did before the columns were catalog-driven

#### Scenario: An unconfigured source is rejected with a catalog-driven message

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule whose source has no `version_column` or no
  `identity_column` configured
- **THEN** the request is rejected with HTTP 400 `bad_request` and a message identifying the missing
  scan-metadata configuration for that source

## MODIFIED Requirements

### Requirement: Rule-scoped incremental scan endpoint

The system SHALL expose `POST /v1/rules/{id}/scan` accepting a body `{cursor?, limit?, from?, to?}`
and returning `{rows[], nextCursor, hasMore, lagSeconds}`. The system SHALL resolve the rule's
stored, already-validated source, filter, referenced columns, and grain from the `rule` /
`enrichment_grain` records; the caller SHALL NOT supply a table name, filter, or column list.
The scan SHALL require the rule to **exist** but NOT to be `enabled` (a paused rule can still be
backfilled). An unknown rule SHALL return HTTP 404 `unknown_rule`. When `limit` is omitted the
system SHALL apply a configured default; an over-limit request SHALL be clamped or rejected
consistent with the existing query-limit policy.

`from` and `to` are optional ISO-8601 instants bounding the source's version column as the half-open
range `(from, to]`. They are an additional predicate only: they compose with `cursor`, the keyset
seek, and the safety-lag rather than replacing any of them, and they do not change ordering,
projection, `final=1`, or cursor encoding. A request where `from` is greater than or equal to `to`
SHALL be rejected with HTTP 400, so an inverted window is never mistaken for an exhausted one.

#### Scenario: Scan resolves the rule and returns a bounded page

- **WHEN** `POST /v1/rules/{id}/scan` is called for an existing rule with a valid or omitted `cursor`
- **THEN** the system runs the rule's governed scan and returns up to `limit` rows plus
  `nextCursor`, `hasMore`, and `lagSeconds`

#### Scenario: Scan of a disabled-but-existing rule succeeds

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule that exists but is `enabled=false`
- **THEN** the scan proceeds normally (enablement gates only the live recurring schedule, not backfill)

#### Scenario: Scan of an unknown rule is rejected

- **WHEN** `POST /v1/rules/{id}/scan` is called with an `id` that does not resolve to a rule
- **THEN** the request is rejected with HTTP 404 and error code `unknown_rule`

#### Scenario: A bounded window returns only rows inside it

- **WHEN** `POST /v1/rules/{id}/scan` is called with `from` and `to`
- **THEN** only rows whose version column is greater than `from` and less than or equal to `to` are
  returned, and rows on either side of the window are excluded regardless of the cursor position

#### Scenario: A window composes with the cursor across pages

- **WHEN** a caller pages through a window by echoing each `nextCursor` while re-supplying the same
  `from` and `to`
- **THEN** each row inside the window is returned exactly once and paging stops with `hasMore=false`
  at the window's end rather than continuing to the source head

#### Scenario: An inverted window is rejected

- **WHEN** `POST /v1/rules/{id}/scan` is called with `from` greater than or equal to `to`
- **THEN** the request is rejected with HTTP 400 rather than returning an empty page

### Requirement: Exactly-once keyset scan semantics

The scan SHALL return each source row **at most once** across pages by advancing an opaque
cursor over a **unique total order** — the tuple `(version_column, identity_column)` declared for the
source in the catalog, which for `dial_usage_log` is `(_ingested_at, event_id)`. The scan SHALL order
by that tuple and apply a keyset (seek) predicate `(version, identity) > (:c1, :c2)` rather than
offset pagination, so concurrent inserts never shift a page boundary. The scan SHALL advance by
**ingest/version order**, not the source sort key, so late-arriving rows are never skipped.
`nextCursor` SHALL encode the last returned row's tuple; an **empty page** SHALL return the input
cursor unchanged (or a start token when none was supplied) with `hasMore=false`; a full page SHALL
report `hasMore=true`. When `cursor` is omitted the scan SHALL start from the source's beginning, or
from `from` when a bounded window is supplied (enabling both full-replay and windowed backfill); the
server SHALL be stateless about cursor position (it signs and decodes only — the caller persists the
cursor).

#### Scenario: Each row is returned exactly once across pages

- **WHEN** a source is scanned across multiple pages, echoing each `nextCursor` as the next request's `cursor`
- **THEN** every matching row appears in exactly one page, with no gaps and no duplicates

#### Scenario: Colliding version values are disambiguated by the tuple tiebreaker

- **WHEN** multiple rows share the same version value at a page boundary
- **THEN** the identity component of the cursor tuple orders them deterministically so none is skipped or repeated

#### Scenario: Omitted cursor scans from the source start

- **WHEN** `POST /v1/rules/{id}/scan` is called with no `cursor`
- **THEN** the scan begins at the source's earliest rows (full-replay), and the server holds no per-caller cursor state

#### Scenario: Empty page returns the input cursor unchanged

- **WHEN** a scan matches no rows above the current cursor below the safety-lag horizon
- **THEN** the response has an empty `rows` array, `hasMore=false`, and `nextCursor` equal to the input cursor

#### Scenario: Adjacent windows do not overlap at their shared boundary

- **WHEN** a caller scans `(t1, t2]` and then `(t2, t3]`
- **THEN** a row whose version is exactly `t2` is returned by the first window only

### Requirement: Scan safety-lag

The scan SHALL exclude rows whose version column exceeds `now64(3) − lag` (configurable, default
5 seconds) so a scan never enters the still-mutating recent window. This relies on the source's
catalog-declared `version_column` being assigned at ingest and never backdated. `lagSeconds` in the
response SHALL be `server_now − max(version)` among rows available below the safety-lag horizon, so a
caller can emit a lag metric without decoding the cursor. The safety-lag SHALL always apply, including
when a bounded window is supplied: a `to` above the horizon SHALL be clamped by the horizon rather
than overriding it, and the scan SHALL continue to report `hasMore`/`nextCursor` relative to the
window so a caller can resume once the horizon advances.

#### Scenario: Too-recent rows are hidden by the safety-lag

- **WHEN** a row's version value is newer than `now64(3) − lag`
- **THEN** the scan does not return that row until it falls below the safety-lag horizon

#### Scenario: A window reaching past the horizon is clamped, not honoured

- **WHEN** a scan supplies a `to` newer than the safety-lag horizon
- **THEN** rows above the horizon are still excluded, and the caller can resume the same window later
  to pick them up
