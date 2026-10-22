## MODIFIED Requirements

### Requirement: Rule-scoped incremental scan endpoint

The system SHALL expose `POST /v1/rules/{id}/scan` accepting a body `{cursor?, limit?, from?, to?}`
and returning `{rows[], nextCursor, hasMore, lagSeconds}`. The system SHALL resolve the rule's
stored, already-validated source, filter, referenced columns, and grain from the `rule` record and
the target enrichment's catalog row; the caller SHALL NOT supply a table name, filter, or column list.
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
