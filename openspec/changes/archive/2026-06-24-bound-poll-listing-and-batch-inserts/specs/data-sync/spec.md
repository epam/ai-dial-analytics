## MODIFIED Requirements

### Requirement: Near-real-time polling ingestion

The service SHALL ingest new log blobs in near-real-time by polling blob storage on a short,
configurable interval, using ONLY the provider-agnostic blob-listing abstraction and WITHOUT
depending on any cloud-provider-specific notification service (e.g. Azure Event Grid, AWS S3 event
notifications, GCS Pub/Sub) or any externally provisioned infrastructure. On each poll cycle the
service SHALL list the active date-derived prefix(es) — the current day, and within a configurable
post-midnight grace window also the previous day — and SHALL select for ingestion every blob whose
last-modified time is at or after a moving watermark less a configurable overlap.

To bound listing cost, the service SHALL by default restrict each listed prefix to a recent time band
derived from the blob key's leading (start) timestamp instead of listing the full day, and SHALL
offer a configurable fallback that lists the full active day prefix(es). Because the blob key orders
lexicographically by its embedded start time while the selection watermark tracks the blob's
last-modified (finalization) time — and finalization can lag the start time by the request/stream
duration — a bounded band SHALL extend its lower bound back from the cutoff (watermark less overlap)
by at least a configurable **finalization lag**, so that the band still lists every blob whose
last-modified time is at or after the cutoff. A blob whose finalization lags beyond the configured
finalization lag MAY be missed by the poller and SHALL remain recoverable by the scheduled
reconciliation path. The selection of blobs from whatever is listed SHALL be identical regardless of
which listing mode is in effect.

Each selected blob SHALL be ingested through the same parse → map → batch-insert pipeline used by the
scheduled path, and the watermark SHALL then advance to the greatest last-modified time observed. The
service MUST read each blob's last-modified time from the listing itself and MUST NOT require a
per-blob metadata fetch. Correctness under overlap, last-modified ties, clock skew, and restarts
SHALL rest on the target table's primary-key deduplication rather than on exactly-once discovery: the
service SHALL keep only an in-memory watermark (initialized on startup to the current time less a
configurable lookback) and MUST NOT require a persisted cursor. A failure to ingest a single blob
(e.g. a corrupt file) MUST NOT abort the poll cycle or hold back the watermark: the service SHALL
count and skip that blob, continue ingesting the remaining blobs, and still advance the watermark, so
that one bad blob cannot wedge the poller — the skipped blob remains eligible for retry within the
overlap window and is otherwise recovered by the reconciliation path. A failure to list blobs (e.g.
the store is unreachable) SHALL instead leave the watermark unchanged so the next cycle re-covers the
window. The poller MUST be controlled by an enable flag, MUST run in-process with no HTTP trigger,
and MUST NOT allow a new poll cycle to start while a previous one is still in progress.

#### Scenario: New blob within the window is ingested

- **WHEN** the poller is enabled and a not-yet-ingested log blob under the active prefix has a
  last-modified time at or after the watermark
- **THEN** the service ingests that blob's rows and advances the watermark to that blob's
  last-modified time

#### Scenario: Blob older than the watermark is not ingested

- **WHEN** a blob under the active prefix has a last-modified time before the watermark less the
  configured overlap
- **THEN** the service does not ingest that blob

#### Scenario: Re-observed blob does not duplicate rows

- **WHEN** a blob is observed again within the watermark overlap, on a clock-skew boundary, or after
  a restart re-scans the lookback window
- **THEN** the blob is re-inserted at most as duplicate rows that the target table's
  `ReplacingMergeTree` engine collapses on merge, so no duplicate row survives

#### Scenario: A bad blob does not wedge the poller

- **WHEN** one blob in the active prefix fails to ingest (e.g. it is corrupt) while other newer blobs
  are present
- **THEN** the service counts and skips the failing blob, ingests the other blobs, and advances the
  watermark, so the failing blob does not block subsequent ingestion

#### Scenario: A listing failure holds the watermark

- **WHEN** a poll cycle cannot list the active prefix (e.g. the store is unreachable)
- **THEN** the service leaves the watermark unchanged so the next cycle re-covers the same window

#### Scenario: Poller disabled

- **WHEN** the poller enable flag is not set
- **THEN** the service does not poll for new blobs and ingestion proceeds only via the scheduled
  reconciliation path

#### Scenario: Bounded listing discovers a blob finalized within the finalization lag

- **WHEN** bounded (band) listing is in effect (the default) and a freshly written blob has a
  last-modified time at or after the cutoff but an embedded start time older than the cutoff by no more
  than the configured finalization lag
- **THEN** the narrowed prefix band still lists that blob and the service ingests it

#### Scenario: A blob finalizing beyond the finalization lag is left to reconciliation

- **WHEN** bounded (band) listing is in effect and a blob's last-modified time is at or after the
  cutoff but its embedded start time precedes the cutoff by more than the configured finalization lag
- **THEN** the narrowed poll cycle MAY not list that blob, and the blob is recovered by the scheduled
  reconciliation path

#### Scenario: Full-day fallback lists the full day

- **WHEN** the operator selects the full-day (`day`) listing fallback
- **THEN** the poller lists the full active day prefix(es) and selects blobs exactly as it did before
  bounded listing existed

### Requirement: Batched inserts via a dedicated writer credential

The service SHALL insert rows into ClickHouse in batches of a configurable size, using a ClickHouse
credential that is separate from the read-only credential used by the query/discovery path. The
read path's ClickHouse access MUST remain read-only.

Within a single ingestion run (a poll cycle or a scheduled reconciliation run), the service SHALL
accumulate mapped rows across the run's blobs into shared batches and flush a batch when it reaches
the configured size and once more at the end of the run, rather than flushing a separate partial
batch per blob — so that a run of many small blobs produces few large inserts rather than one insert
per blob. The isolation of a single blob's ingest failure (counted and skipped, the run continuing
and the watermark still advancing) SHALL be preserved, and such a failure MUST NOT discard rows
already accumulated from other blobs in the same run.

#### Scenario: Rows are inserted in batches

- **WHEN** a file yields more rows than the configured batch size
- **THEN** the service inserts the rows in multiple batches of at most the configured size

#### Scenario: A run's small blobs share batches

- **WHEN** an ingestion run processes many blobs that each yield fewer rows than the configured batch
  size
- **THEN** their rows accumulate into shared batches flushed when the configured size is reached and
  once at the end of the run, rather than one insert per blob

#### Scenario: A failed blob does not discard the run's buffered rows

- **WHEN** one blob fails to ingest after earlier blobs in the same run have already contributed rows
  to the shared batch
- **THEN** the service counts and skips the failing blob and retains the rows already accumulated from
  the other blobs

#### Scenario: Writer credential is isolated from the read path

- **WHEN** the sync job writes to ClickHouse
- **THEN** it uses the dedicated writer credential, and the read/discovery path continues to use its
  own read-only credential
