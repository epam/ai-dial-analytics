## ADDED Requirements

### Requirement: A record is written for all of its targets or for none

Each parsed record SHALL be mapped by every target before any of its rows is handed to the write path. When the
match predicate, a transform or a deriver of any target raises an error, no row of that record SHALL be
written, and the blob fails with a mapping fault.

Writing the rows of the targets mapped before the fault would write a source row without the enrichment row its
target maps after it. Every reader of the grain join treats the missing row as final. Records of the same blob
that were mapped before the failing one are complete, and are written as usual.

#### Scenario: A payload mapping fault writes no usage-log row for the record

- **WHEN** the usage-log target maps a record and the payload target's transform then raises an error on it
- **THEN** neither the usage-log row nor the payload row of that record is written, and the blob's failure is
  recorded with the reason `mapping_error`

## MODIFIED Requirements

### Requirement: Scheduled daily ingestion

The service SHALL run a data-sync job automatically on a daily schedule driven by a configurable
cron expression. This scheduled run serves as a **reconciliation safety net** that complements the
near-real-time polling path: it lists the target prefix once a day, ingests the blobs the ingest ledger
does not record — those the poller missed (e.g. during downtime longer than the poller's restart lookback
window, or finalization beyond the poller's band) — and skips, and counts, the blobs it does record. It
records each blob it ingests as soon as that blob's rows are written. The job MUST run in-process and MUST
NOT be triggerable via an HTTP endpoint in this phase. The scheduler MUST be configured such that a new run
cannot start while a previous run is still in progress.

A failure to ingest a single blob SHALL NOT abort the run. Such failures include:
- the blob cannot be opened or read;
- its gzip stream is invalid;
- mapping one of its records raises an error.

The run SHALL record the blob's failure in the failure ledger (see `data-sync-failures`), count it, and continue
with the day's remaining blobs. Rows that the failing blob had already buffered MAY still be written. They are
collapsed when the blob is ingested again, because the blob is not recorded in the ingest ledger.

The run SHALL fail its day only on a day-level fault:
- listing the prefix fails;
- preparing the day fails;
- a batch insert or ingest-ledger write fails;
- writing a blob's failure event fails.

The run's outcome SHALL be reported as a failure when the day failed or when any of its blobs failed. A day
that failed SHALL be recorded in the failure ledger. A later scheduled run does not retry it, since that run
targets its own day. Instead the retry sweep retries the failed day, and an operator backfill can also recover
it; either way only the blobs the failed run did not finish are ingested.

#### Scenario: Job runs on schedule

- **WHEN** the configured cron time is reached and no sync is currently running
- **THEN** the service starts a reconciliation sync run without any external trigger

#### Scenario: Overlapping runs are prevented

- **WHEN** the cron time is reached while a previous sync run is still in progress
- **THEN** the service does not start a second concurrent run

#### Scenario: Reconciliation skips what the poller already ingested

- **WHEN** the daily run lists the target prefix and the ledger records some of its blobs
- **THEN** the run does not read or insert those blobs, counts them as skipped, and ingests only the
  remaining blobs

#### Scenario: Reconciliation re-ingests what the poller missed

- **WHEN** the daily run lists a blob the ledger does not record, for example one finalized beyond the
  poller's band or written while the service was down
- **THEN** the run ingests it and records it, so no blob is lost to a poller gap

#### Scenario: A failed run keeps what it wrote

- **WHEN** a reconciliation run fails after some of its writes succeeded
- **THEN** the blobs whose rows were all written are recorded and the rest are not

#### Scenario: A bad blob does not stop the day

- **WHEN** the third of five blobs under the day's prefix has a corrupt gzip stream
- **THEN** the run records that blob's failure, ingests and records the other four blobs, reports its outcome
  as a failure, and does not record a day failure

#### Scenario: A failed day is recorded for retry

- **WHEN** the run cannot list the day's prefix because the store is unreachable
- **THEN** the day fails and is recorded in the failure ledger as a pending day failure, which the retry sweep
  attempts once its backoff has elapsed

### Requirement: Near-real-time polling ingestion

The service SHALL ingest new log blobs in near-real-time by polling blob storage on a short,
configurable interval, using ONLY the provider-agnostic blob-listing abstraction and WITHOUT
depending on any cloud-provider-specific notification service (e.g. Azure Event Grid, AWS S3 event
notifications, GCS Pub/Sub) or any externally provisioned infrastructure. On each poll cycle the
service SHALL list the active date-derived prefix(es) — the current day, and within a configurable
post-midnight grace window also the previous day — and SHALL select for ingestion every blob whose
last-modified time is at or after a moving watermark less a configurable overlap, and that the ingest
ledger does not record.

To bound listing cost, the service SHALL by default restrict each listed prefix to a recent time band
derived from the blob key's leading (start) timestamp instead of listing the full day, and SHALL
offer a configurable fallback that lists the full active day prefix(es). Because the blob key orders
lexicographically by its embedded start time while the selection watermark tracks the blob's
last-modified (finalization) time — and finalization can lag the start time by the request/stream
duration — a bounded band SHALL extend its lower bound back from the cutoff (watermark less overlap)
by at least a configurable **finalization lag**, so that the band still lists every blob whose
last-modified time is at or after the cutoff. Because a band is a time slice while a prefix is bound
to one date directory, a band spanning a UTC date boundary SHALL be listed under every date directory
it spans, so that narrowing the listing never narrows what is discoverable within the requested range.
A blob whose finalization lags beyond the configured finalization lag MAY be missed by the poller and
SHALL remain recoverable by the scheduled reconciliation path. The selection of blobs from whatever
is listed SHALL be identical regardless of which listing mode is in effect.

Each selected blob
SHALL be ingested through the same parse → map → batch-insert pipeline used by the scheduled path,
and the watermark SHALL then advance to the greatest last-modified time observed. The service MUST
read each blob's last-modified time from the listing itself and MUST NOT require a per-blob metadata
fetch. The poller SHALL record each blob it ingested in the ledger as soon as that blob's rows are written —
normally at the cycle's final flush, earlier when a batch fills during the cycle. The service SHALL keep an
in-memory watermark (initialized on startup to the current time less a configurable lookback) and an
in-memory set of the blobs, within the overlap window, that it ingested or found recorded, and MUST NOT
require a persisted cursor: the ledger, not a cursor, is what stops a restart from re-ingesting the lookback
window, and where the ledger has no entry for a blob, correctness under overlap, last-modified ties, clock
skew, and restarts SHALL still rest on the target table's primary-key deduplication. A failure to ingest a
single blob (e.g. a corrupt
file) MUST NOT abort the poll cycle or hold back the watermark: the service SHALL count and skip that
blob, record its failure in the failure ledger (see `data-sync-failures`), continue ingesting the remaining
blobs, and still advance the watermark, so that one bad blob cannot wedge the poller. The skipped blob is not
recorded in the ingest ledger. It stays eligible for retry within the overlap window, and is otherwise
recovered by the reconciliation path or by the retry sweep, which keeps retrying it after both of those. A
failure to write the blob's failure event SHALL fail the cycle like a failed ingest-ledger write, so the
watermark holds. A failure to list blobs (e.g. the store is
unreachable) SHALL instead leave the watermark unchanged so the next cycle re-covers the window. The
poller MUST be controlled by an enable flag, MUST run in-process with no HTTP trigger, and MUST NOT
allow a new poll cycle to start while a previous one is still in progress.

#### Scenario: New blob within the window is ingested

- **WHEN** the poller is enabled and a not-yet-ingested log blob under the active prefix has a
  last-modified time at or after the watermark
- **THEN** the service ingests that blob's rows, records the blob in the ledger, and advances the
  watermark to that blob's last-modified time

#### Scenario: Blob older than the watermark is not ingested

- **WHEN** a blob under the active prefix has a last-modified time before the watermark less the
  configured overlap
- **THEN** the service does not ingest that blob

#### Scenario: Re-observed blob does not duplicate rows

- **WHEN** a blob that was ingested is observed again within the watermark overlap, on a clock-skew
  boundary, or after a restart re-scans the lookback window, and the ledger records it
- **THEN** the service does not read or insert it again; a blob skipped because the ledger records it is
  counted as skipped by the ledger, and one skipped because the in-memory set already holds it is not counted

#### Scenario: Re-observed blob without a ledger entry collapses in the table

- **WHEN** a blob that was ingested is observed again and the ledger has no entry for it, because the
  service stopped, or the ledger write failed, between the write of its rows and the entry
- **THEN** the blob is re-inserted at most as duplicate rows that the target table's `ReplacingMergeTree`
  engine collapses on merge, so no duplicate row survives

#### Scenario: A bad blob does not wedge the poller

- **WHEN** one blob in the active prefix fails to ingest (e.g. it is corrupt) while other newer blobs
  are present
- **THEN** the service counts and skips the failing blob without recording it in the ingest ledger, records
  its failure in the failure ledger, ingests the other blobs, and advances the watermark, so the failing blob
  does not block subsequent ingestion

#### Scenario: A blob that keeps failing outlives the overlap window

- **WHEN** a blob fails on the poller with a transient error on every cycle until it leaves the overlap
  window
- **THEN** the failure ledger holds it as pending, and the retry sweep keeps attempting it with backoff
  after the poller has stopped considering it

#### Scenario: A listing failure holds the watermark

- **WHEN** a poll cycle cannot list the active prefix (e.g. the store is unreachable)
- **THEN** the service leaves the watermark unchanged so the next cycle re-covers the same window

#### Scenario: A failed write holds the ledger

- **WHEN** the cycle's final flush of rows to a target table fails
- **THEN** the cycle's blobs whose rows were not all written are not recorded in the ledger, the watermark
  holds, and the next cycle ingests them again

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

### Requirement: Ingested blobs are recorded in a persisted ledger

The service SHALL keep a ledger of the log blobs it has fully ingested, so that the scheduled reconciliation
run, the poller and — unless the operator asks otherwise — the backfill do not ingest a blob a second time.
The ledger SHALL live in ClickHouse, in the same database as the ingest tables, so that it shares their
failure domain: a restore or re-creation of that database cannot leave the ledger claiming rows the tables no
longer hold. It SHALL be a table `_dial_internal_ingested_blob` with one row per recorded blob, keyed and ordered by
`blob_key` (the blob's full key inside the container — the same string the ingested rows carry in
`_source_file`), holding `blob_last_modified` (the blob's last-modified time as listed by the store,
millisecond precision, UTC) and `ingested_at` (the time the entry was written, a table-side default).
Recording a blob again SHALL supersede its earlier entry: the entry a reader sees for a key is the one with
the greatest `ingested_at`, whether or not older entries have been merged away.

A blob SHALL count as **recorded** only while its entry's `blob_last_modified` equals, to the millisecond, the
last-modified time the store currently lists for it. Log blobs are expected to be write-once, but that is a
property of the producer that the service cannot enforce: a blob rewritten under the same key carries a newer
last-modified time, so its entry stops matching and the next reconciliation run, backfill or poll cycle that
consults the ledger for it ingests it again, the overlap collapsed by the target table's engine. (Within its
overlap window the poller skips a blob it ingested itself by name, before consulting the ledger, so a rewrite
inside that window is caught by the next reconciliation run instead.) A blob the store lists without a
last-modified time SHALL never be recorded. The ledger does not track the ingest configuration: a change to a
target or a mapping applies to blobs ingested from then on, and applying it to earlier blobs is done by an
operator backfill that includes already-ingested blobs.

A blob SHALL be recorded as soon as every row it produced has been written to its target tables — at the
write that carries its last buffered row, or as soon as it has been read if none of its rows is still
buffered — not at the end of the poll cycle or the day, so that a run that stops part-way leaves every blob it
finished recorded, and a re-triggered backfill of the same range ingests only the rest. Rows are written in
batches that span blobs, so a blob is not recorded merely because it has been read while its rows are still
buffered. A blob whose ingest failed, or whose rows were not all written, SHALL NOT be recorded. A blob that
was read successfully but produced no rows (every record malformed, dropped, or matched by no target) SHALL
be recorded, since reading it again would produce nothing.

Recording happens after the write to the target tables, and the two are not atomic. If the service stops, or
the ledger write fails, after the rows are written but before the entry is, the blob is not recorded; a later
poll cycle, reconciliation run or backfill that lists it ingests it again and the target table's engine
collapses the copy. The ledger therefore reduces duplicates to that window rather than guaranteeing
exactly-once ingestion.

Reading and writing the ledger are ClickHouse statements on the ingest path like the inserts into the target
tables, and SHALL fail the same way: a ledger read or write that fails SHALL fail the poll cycle (the
watermark holds and the next cycle retries) or the day of a reconciliation run or backfill, exactly as a
failed insert does. A ledger write failure SHALL be treated as a fault of the cycle or day, not of the blob
being read when it occurred.

Entries SHALL be kept for 13 months after their blob's last-modified time and then expire, so the ledger does
not grow without bound; removal MAY lag the 13 months by up to one month, because entries expire a month at a
time. A blob older than that has no entry, so a backfill reaching back further ingests it as before the ledger
existed. The ledger SHALL NOT be registered in the query catalog and SHALL NOT be reachable through any query
surface.

#### Scenario: A fully ingested blob is recorded

- **WHEN** a blob has been parsed and all rows it produced have been written to their target tables
- **THEN** the ledger holds an entry for that blob with its key, its listed last-modified time and the ingest
  time

#### Scenario: A blob is recorded when its rows are written, not when it is read

- **WHEN** a day's run has read a blob whose rows are still buffered, and a later write carries the last of
  them
- **THEN** the blob is not recorded before that write and is recorded right after it, while the run goes on

#### Scenario: A run that stops part-way keeps what it wrote

- **WHEN** a reconciliation run or backfill day fails after some of its writes succeeded
- **THEN** every blob whose rows were all written by then is recorded, the blobs whose rows were not are
  not, and a backfill of that day afterwards ingests only the unrecorded ones

#### Scenario: A blob that failed to ingest is not recorded

- **WHEN** reading or parsing a blob fails, or a write of rows from the same cycle or day fails before the
  blob's rows are all written
- **THEN** the ledger holds no entry for that blob. The poller retries it while it stays within its overlap
  window, and a reconciliation run that lists it ingests it. If reading or parsing failed, the failure ledger
  holds the blob's failure, and the retry sweep retries it or parks it for an operator (see
  `data-sync-failures`). An operator backfill can also recover it

#### Scenario: An empty blob is recorded

- **WHEN** a blob is read successfully but every record in it is malformed, dropped for lacking a
  deduplication key, or matched by no target — including when it is the last blob of the cycle or day
- **THEN** the blob is recorded and is not read again by the reconciliation run, the poller or a default
  backfill

#### Scenario: A blob listed without a last-modified time is never recorded

- **WHEN** the store lists a blob without a last-modified time and a reconciliation run or backfill ingests it
- **THEN** no ledger entry is written for it, so the next reconciliation run or backfill that lists it
  ingests it again

#### Scenario: A rewritten blob is ingested again

- **WHEN** a blob has a ledger entry and the reconciliation run lists it with a different last-modified time
  than the entry recorded
- **THEN** the entry does not count as recorded, the blob is ingested again, and a new entry with the new
  last-modified time supersedes the old one

#### Scenario: A newer entry supersedes an older one before any merge

- **WHEN** a blob is recorded twice with different last-modified times and the two entries have not been
  merged
- **THEN** a reader sees only the entry recorded last

#### Scenario: A changed mapping does not re-ingest earlier blobs

- **WHEN** the contents of a target's mapping file, or the set of target tables, changes between two
  deployments
- **THEN** blobs the ledger already records are still skipped by every ingest path, the new mapping applies to
  blobs ingested from then on, and an operator backfill of a date range that includes already-ingested blobs
  applies it to that range

#### Scenario: A ledger failure fails the cycle like an insert failure

- **WHEN** a ledger read or write fails during a poll cycle
- **THEN** the cycle fails, the watermark holds, and the next cycle ingests the blobs it did not record, their
  duplicate rows collapsed by the target table's engine

#### Scenario: A ledger failure fails the day like an insert failure

- **WHEN** a ledger read or write fails during a reconciliation run or a backfill day
- **THEN** that day fails as it would on a failed insert, and the blobs recorded before the failure stay
  recorded

#### Scenario: Old entries expire

- **WHEN** an entry's blob was last modified more than 13 months ago
- **THEN** the entry is removed within one further month, and entries younger than 13 months are kept

#### Scenario: The ledger is not queryable

- **WHEN** a caller lists entities or runs a query through any query surface
- **THEN** `_dial_internal_ingested_blob` is neither listed nor readable
