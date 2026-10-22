## MODIFIED Requirements

### Requirement: Operator-triggered date-range backfill

The service SHALL provide an explicitly-triggered backfill that ingests DIAL usage logs for an
inclusive range of UTC dates through the same parse → map → batch-insert pipeline used by the
scheduled and poller paths. The backfill SHALL be exposed over HTTP at `POST /v1/data-sync/backfill`,
accepting an inclusive UTC date range with fields `from` and `to` (ISO `yyyy-MM-dd`). Unlike the
scheduled reconciliation and poller paths — which run in-process with no HTTP trigger — the backfill
is deliberately operator-triggered. The backfill MUST be gated by a configurable enable flag; when
disabled the endpoints SHALL NOT initiate runs.

The backfill SHALL iterate the range one day at a time, and for each day resolve the date-derived
blob prefix `<path-prefix>/<date-path>/` using the same discovery logic — and therefore the same
configured date-directory layout — as the scheduled path, list every blob under it, and ingest the
records. The backfill SHALL NOT carry a layout setting of its own: a deployment whose log blobs use a
non-default date directory SHALL be backfillable with the same configuration that makes its scheduled
and poller paths work. Because deduplication is performed by the target table's `ReplacingMergeTree`
engine, a backfill that overlaps dates already ingested by the schedule or poller SHALL NOT produce
surviving duplicate rows, and the service SHALL NOT pre-check for already-ingested files or rows.

#### Scenario: Backfill ingests every day in the range

- **WHEN** an enabled backfill is triggered with `from` = 2026-05-26 and `to` = 2026-06-24
- **THEN** the service ingests blobs for each date from 2026-05-26 through 2026-06-24 inclusive, each
  via its own date-derived prefix

#### Scenario: Backfill follows the configured date-directory layout

- **WHEN** an enabled backfill is triggered on a deployment configured with a non-default
  date-directory layout (e.g. `yyyy-MM-dd`)
- **THEN** each day of the range is listed under a prefix carrying that layout, matching what the
  scheduled path would list for the same date

#### Scenario: Overlap with prior ingestion does not duplicate rows

- **WHEN** a backfill range covers dates whose blobs were already ingested by the scheduled or poller
  path
- **THEN** the service re-inserts the records without an existence pre-check and the table's engine
  collapses the duplicates, so no duplicate row survives

#### Scenario: Backfill disabled

- **WHEN** the backfill enable flag is not set and a trigger is received
- **THEN** the service does not start a backfill run and reports that the feature is disabled
