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

The two columns SHALL be **caller-declared** on the table-management API: a source SHALL declare both at
`POST /v1/tables/{name}/schema` (see [table-management](../table-management/spec.md)), which is what makes
every source created through that API scannable by construction, alongside the migration-seeded values the
built-in sources carry. The scan's rejection above therefore covers a definition that predates the
requirement rather than one a caller can create.

The exactly-once guarantee still depends on `version_column` being assigned at ingest, monotonic, and
never backdated, and on `identity_column` being unique per row — physical properties of ingest that no
layer can verify, an operator migration no better than a caller's schema request. They therefore remain
the **declaring caller's promise**, and the table API SHALL NOT be read as validating them: what it
checks is that each name is a declared column of the table.

#### Scenario: A caller-declared source scans on its own order

- **WHEN** a caller declares `identity_column` and `version_column` for a source at
  `POST /v1/tables/{name}/schema`, and a rule over that source is scanned
- **THEN** the scan pages the source on that `(version, identity)` tuple, with no migration in between,
  exactly as it does for a migration-seeded source

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
