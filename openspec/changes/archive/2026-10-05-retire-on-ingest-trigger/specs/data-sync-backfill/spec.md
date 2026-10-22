## MODIFIED Requirements

### Requirement: Operator-triggered date-range backfill

The service SHALL provide an explicitly-triggered backfill that ingests DIAL usage logs for an
inclusive range of UTC dates through the same parse → map → batch-insert pipeline used by the
scheduled and poller paths. The backfill SHALL be exposed over HTTP at `POST /v1/data-sync/backfill`,
accepting an inclusive UTC date range with fields `from` and `to` (ISO `yyyy-MM-dd`), and an optional
boolean `includeIngested`; an absent or `null` value means `false`, and a value that cannot be read as a
boolean SHALL be rejected with `400 bad_request` and start no run. Unlike the
scheduled reconciliation and poller paths — which run in-process with no HTTP trigger — the backfill
is deliberately operator-triggered. The backfill MUST be gated by a configurable enable flag; when
disabled the endpoints SHALL NOT initiate runs.

The backfill SHALL iterate the range one day at a time, and for each day resolve the date-derived
blob prefix `<path-prefix>/<date-path>/` using the same discovery logic — and therefore the same
configured date-directory layout — as the scheduled path, list every blob under it, and ingest the
records. The backfill SHALL NOT carry a layout setting of its own: a deployment whose log blobs use a
non-default date directory SHALL be backfillable with the same configuration that makes its scheduled
and poller paths work.

By default the backfill SHALL skip, and count, every blob the ingest ledger records, and ingest only the
rest, so a backfill over days already ingested — or a re-trigger of a range a previous run left part-done —
writes only what is missing. With `includeIngested` set to `true` it SHALL ingest every blob it lists whether
or not the ledger records it; this is how an operator repairs what the ledger cannot know is missing (rows
lost to a dropped or restored partition) or applies a changed mapping to history. Re-ingesting a day this way
re-writes its rows, so everything that reacts to new rows (row-grain enrichment scans, aggregate refreshes) reacts
to them again. In both modes the backfill SHALL record each blob it ingests, as soon as that blob's rows are
written. Because deduplication is performed by the target table's `ReplacingMergeTree` engine, a backfill
that re-ingests dates already ingested SHALL NOT produce surviving duplicate rows, and the service SHALL NOT
pre-check for already-ingested rows.

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
  path and the ledger does not record them, or the backfill includes already-ingested blobs
- **THEN** the service re-inserts the records without an existence pre-check and the table's engine
  collapses the duplicates, so no duplicate row survives

#### Scenario: A default backfill skips what is already ingested

- **WHEN** an operator backfills a range without `includeIngested` and the ledger records some of its blobs
- **THEN** those blobs are neither read nor inserted, they are counted as skipped, and only the rest are
  ingested

#### Scenario: A re-triggered backfill finishes what a stopped run left

- **WHEN** a backfill stopped part-way through a range (for example on disk exhaustion) and the operator
  triggers the same range again without `includeIngested`
- **THEN** the new run ingests only the blobs the stopped run did not finish

#### Scenario: Including ingested blobs re-ingests the whole range

- **WHEN** an operator backfills a range with `includeIngested: true` and the ledger records its blobs
- **THEN** every blob of the range is ingested again, the rows collapse in the target table, and the ledger
  entries are refreshed

#### Scenario: A boolean given as a string is accepted

- **WHEN** a backfill is triggered with `includeIngested` set to `"true"`
- **THEN** the run starts and includes already-ingested blobs

#### Scenario: An unreadable includeIngested is rejected

- **WHEN** a backfill is triggered with `includeIngested` set to a value that cannot be read as a boolean,
  such as `"maybe"` or an object
- **THEN** the service returns `400 bad_request` and starts no run

#### Scenario: Backfill disabled

- **WHEN** the backfill enable flag is not set and a trigger is received
- **THEN** the service does not start a backfill run and reports that the feature is disabled
