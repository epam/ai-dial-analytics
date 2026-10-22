## MODIFIED Requirements

### Requirement: Catalog-configured scan cursor columns

The scan's total-order columns SHALL be resolved from the catalog rather than hardcoded per source.
Each `table_definition` SHALL carry an optional `version_column` and `identity_column`; together they
define the source's unique total order `(version_column, identity_column)` used for keyset paging,
the safety-lag predicate, and the cursor tuple. `dial_usage_log` SHALL be configured with
`(_ingested_at, event_id)`, preserving the originally shipped behaviour exactly, and `rate_analytics`
SHALL be configured with its own `(_ingested_at, event_id)` so ratings are scannable on the same
terms. A source's `identity_column` SHALL also be registered as a column of that source: an
enrichment keyed on it resolves the key's type from the source's catalog columns when its schema is
materialized, so an unregistered identity column would leave every enrichment on that source
unbuildable. A scan of a source
with either column unconfigured SHALL be rejected with HTTP 400 `bad_request` — the same status and
code the hardcoded branch produced — and a message naming the missing scan-metadata configuration
rather than a phase limitation.

The two columns SHALL be **caller-declared** on the table-management API: a source MAY declare both at
`POST /v1/tables/{name}/schema` (see [table-management](../table-management/spec.md)), alongside the
migration-seeded values the built-in sources carry — which is what makes a source created through the API
scannable with no migration. Declaring them SHALL remain optional: a source whose rows have no single
identifying column (a rollup keyed on several columns, whose identity is its whole `ordering_key`) has no
truthful `identity_column` to name, and the scan's rejection above is the correct answer for it. A declared
pair that does not hold is worse than none, because a repeated cursor tuple makes the keyset seek skip rows
silently instead of failing.

The table API SHALL verify that a declared pair is **shaped** for this scan — each name a non-nullable,
non-`sensitive` column of that table, and the `version_column` a `timestamp` (or, on an `upsert_by_key`
table, the engine version `_updated_at`) — because those are exactly the properties the SQL above requires:
a non-temporal version cannot be read as a millisecond timestamp, a `NULL` in either half fails every
cursor comparison, and a sensitive cursor column bars any caller without the sensitive entitlement from
operating the rule.

The exactly-once guarantee additionally depends on `version_column` being assigned at ingest, monotonic,
and never backdated, and on `identity_column` being unique per row — physical properties of ingest that no
layer can verify, an operator migration no better than a caller's schema request. Those therefore remain
the **declaring caller's promise**, and the table API SHALL NOT be read as validating them.

#### Scenario: A caller-declared source scans on its own order

- **WHEN** a caller declares `identity_column` and `version_column` for a source at
  `POST /v1/tables/{name}/schema`, and a rule over that source is scanned
- **THEN** the scan pages the source on that `(version, identity)` tuple, with no migration in between,
  exactly as it does for a migration-seeded source

#### Scenario: A pair the scan could not page by never reaches the scan

- **WHEN** a caller declares a `version_column` that is not a `timestamp`, or a nullable or `sensitive`
  member, at `POST /v1/tables/{name}/schema`
- **THEN** the schema request is rejected with HTTP 422 (see
  [table-management](../table-management/spec.md)), so the scan never renders SQL that ClickHouse would
  refuse, and never silently omits rows whose cursor value is `NULL`

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
