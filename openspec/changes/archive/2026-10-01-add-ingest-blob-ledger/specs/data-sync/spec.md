## ADDED Requirements

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
- **THEN** the ledger holds no entry for that blob; the poller retries it while it stays within its overlap
  window, a reconciliation run that lists it ingests it, and otherwise an operator backfill recovers it

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

## MODIFIED Requirements

### Requirement: Scheduled daily ingestion

The service SHALL run a data-sync job automatically on a daily schedule driven by a configurable
cron expression. This scheduled run serves as a **reconciliation safety net** that complements the
near-real-time polling path: it lists the target prefix once a day, ingests the blobs the ingest ledger
does not record — those the poller missed (e.g. during downtime longer than the poller's restart lookback
window, or finalization beyond the poller's band) — and skips, and counts, the blobs it does record. It
records each blob it ingests as soon as that blob's rows are written. The job MUST run in-process and MUST
NOT be triggerable via an HTTP endpoint in this phase. The scheduler MUST be configured such that a new run
cannot start while a previous run is still in progress. A run that fails is not retried by a later scheduled
run, which targets its own day; a failed day is recovered by an operator backfill, which ingests only the
blobs the failed run did not finish.

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
blob, continue ingesting the remaining blobs, and still advance the watermark, so that one bad blob
cannot wedge the poller — the skipped blob is not recorded, remains eligible for retry within the overlap
window and is otherwise recovered by the reconciliation path. A failure to list blobs (e.g. the store is
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
- **THEN** the service counts and skips the failing blob without recording it, ingests the other blobs,
  and advances the watermark, so the failing blob does not block subsequent ingestion

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

### Requirement: Date-derived prefix file discovery

The service SHALL determine which files to ingest by resolving a date-derived blob prefix and listing
all blobs under that prefix. The prefix SHALL take the form `<path-prefix>/<date-path>/`, where
`<path-prefix>` is the configurable base path within the container and `<date-path>` is the target date
rendered through a **configurable date-directory layout** whose default is `{yyyy}/{MM}/{dd}`. The
layout SHALL be expressible as a single value covering both the three-segment form
(`{yyyy}/{MM}/{dd}`, e.g. `2026/06/18`) and a single-segment form (e.g. `2026-06-18`), since different
DIAL deployments bucket their log blobs by either.

The date-directory layout SHALL additionally be permitted to be **blank**, which SHALL mean the
deployment stores no date directory at all. In that mode the whole-day prefix SHALL absorb the
configured file-name head and SHALL take the form `<path-prefix>/<file-name-head>` — the head rendered
for the target date, with **no trailing separator** — e.g. `logs/date=2025-10-22` for a base path of
`logs` and a head layout of `'date='yyyy-MM-dd`. The head is what pins the day in that mode, so the
prefix remains date-derived and each whole-day listing still covers exactly one date. A blank layout
SHALL be admitted only where that holds; see *Date-directory layout is validated at startup*.

An empty date segment SHALL NOT be read as "a date directory rendering to nothing", i.e. the prefix
SHALL NOT degrade to `<path-prefix>/`. Such a prefix is independent of the date, so every whole-day
listing — the scheduled run, the operator backfill and the poller's full-day fallback alike — would
cover the entire base path and re-ingest every day it holds.

The target date SHALL be the current date minus a configurable day offset (default 1, i.e. the previous
day). The service MUST NOT require an exact blob key, since blob names contain an unpredictable suffix.
Every blob listed under the prefix SHALL be considered; which of them are then ingested is decided by the
ingest ledger (see "Ingested blobs are recorded in a persisted ledger").

#### Scenario: Prefix is resolved from the day offset

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `logs` and the default
  date-directory layout
- **THEN** the service lists blobs under the prefix `logs/2026/06/18/`

#### Scenario: Prefix is resolved with a single-segment date layout

- **WHEN** a sync run starts with day offset 1 on 2026-06-19, with base path `your_dir` and a
  date-directory layout of `yyyy-MM-dd`
- **THEN** the service lists blobs under the prefix `your_dir/2026-06-18/`

#### Scenario: Prefix is resolved with no date directory

- **WHEN** a sync run starts with day offset 1 on 2025-10-23, with base path `logs`, a blank
  date-directory layout and a file-name head layout of `'date='yyyy-MM-dd`
- **THEN** the service lists blobs under the prefix `logs/date=2025-10-22`, carrying no trailing
  separator, and ingests the flat keys `logs/date=2025-10-22<epoch>-<uuid>.log.gz`

#### Scenario: A blank date layout does not widen the listing to the base path

- **WHEN** the date-directory layout is blank
- **THEN** no discovery path lists `<path-prefix>/` on its own, and a whole-day listing for one date
  does not return the keys of another date stored under the same base path

#### Scenario: All files under the prefix are processed

- **WHEN** the resolved prefix contains multiple blobs
- **THEN** the service considers every blob found under that prefix, ingesting each one the ledger does not
  record and skipping each one it does

#### Scenario: Empty prefix completes cleanly

- **WHEN** the resolved prefix contains no blobs
- **THEN** the run completes successfully having ingested zero rows and reports zero files processed

### Requirement: An insert refused for lack of disk space is classified distinctly

The service SHALL recognise a ClickHouse write refused for lack of disk space (server error code 243,
`NOT_ENOUGH_SPACE`) as its own failure mode, distinct from an otherwise-failed insert, and SHALL report
it as such in logs and metrics, naming the target table. Recognition SHALL be by the numeric server
error code rather than by matching message text.

A failed batch insert is a **run-level** fault, not the blob-scoped failure the poller isolates (a
corrupt blob is counted, skipped, and does not hold the watermark): whatever the reason, the run stops
writing, and a poll cycle leaves its watermark unchanged and records in the ingest ledger only the blobs
whose rows were all written before the failure, so every other blob it read stays eligible for re-ingest and
the next cycle retries. Daily ingest therefore resumes on
its own once space is available, with no operator action and no rows lost. Rows an earlier attempt
already flushed and a retry writes again SHALL collapse on the table's deduplication key rather than
double-count, exactly as any other re-ingest does.

A failure the service cannot attribute to disk exhaustion SHALL keep its existing generic treatment, so
a driver or server change that stops surfacing code 243 costs the specific classification and nothing
more.

#### Scenario: A disk-full insert is named in logs and metrics

- **WHEN** ClickHouse refuses a batch insert with error code 243 (`NOT_ENOUGH_SPACE`)
- **THEN** the service logs the failure as disk exhaustion, naming the target table, and increments the
  insert-failure metric with a reason distinguishing it from other insert failures

#### Scenario: The poller holds its watermark and recovers unattended

- **WHEN** a poll cycle's batch insert fails — whether refused for lack of disk space or for any other
  reason — after one or more blobs have contributed rows
- **THEN** the cycle fails without advancing the watermark, the blobs whose rows the failed insert carried
  are not recorded in the ledger, and a later cycle re-ingests them successfully once the write path is
  healthy again

#### Scenario: Another insert failure keeps its generic treatment

- **WHEN** a batch insert fails for a reason other than error code 243
- **THEN** the failure is surfaced as an ordinary run-level insert failure, not as disk exhaustion

### Requirement: Primary-key deduplication

The service SHALL rely on the target table's `ReplacingMergeTree` engine to deduplicate records by
their primary key, the combination of `trace_id` and `core_span_id` (one proxy request maps to one
span and one log record). The engine is the correctness guarantee: the service SHALL NOT assume that a
blob is ingested only once, and SHALL NOT delete or update rows to remove duplicates. To avoid writing
duplicates in the first place, every ingest path SHALL skip a blob the ingest ledger records — except an
operator backfill that asks to include already-ingested blobs; no path SHALL pre-check individual rows or
query the target tables for existing files. A blob without a matching ledger entry — and every blob such a
backfill ingests — is re-parsed and re-inserted, and the engine collapses duplicate spans on merge,
retaining the copy with the greatest `_ingested_at`. Each ingested row SHALL continue to record the
originating blob name in `_source_file` as provenance. Because engine deduplication is eventual,
documentation SHALL state that reads requiring exact, duplicate-free results use `FINAL` or
`GROUP BY`/`argMax`.

#### Scenario: Re-ingested span collapses to one row

- **WHEN** the same usage record (identical `trace_id` and `core_span_id`) is ingested more than once
- **THEN** after merge the table retains a single row for that span, keeping the latest-ingested copy

#### Scenario: A blob recorded in the ledger is not re-inserted

- **WHEN** a reconciliation run, a poll cycle or a default backfill meets a blob the ledger records
- **THEN** the service neither parses nor inserts it, without querying any target table

#### Scenario: No file or row is skipped as already-ingested

- **WHEN** a sync run processes a blob whose records were ingested earlier but which the ledger does not
  record
- **THEN** the service re-parses and re-inserts the records without an existence pre-check of rows or
  files, and the engine collapses the duplicates

#### Scenario: Provenance is still recorded

- **WHEN** a record is ingested
- **THEN** its row records the originating blob name in `_source_file`, even though that column is not
  the deduplication key

### Requirement: Ingest observability

The service SHALL expose ingest observability through logs and Micrometer metrics, including counts
of files processed, rows inserted, rows skipped (unparseable lines), rows dropped (records lacking the
deduplication key), run outcome, run duration, and the timestamp of the last successful run. The
service SHALL additionally expose poller metrics: poll cycles executed, blobs discovered and ingested
per cycle, poll failures, and the current watermark lag (how far the watermark trails the current
time). It SHALL expose, for each ingest path (reconciliation run, poller and backfill), the number of blobs
skipped because the ingest ledger records them. The files-processed count and the poller's blobs-discovered
count SHALL count only blobs selected for ingestion, never blobs skipped by the ledger. No HTTP status
endpoint is provided in this phase.

The ingest write path SHALL additionally expose, per target table, the number of batch inserts it
issued and the number that failed, the latter tagged by reason so that a write refused for lack of disk
space is countable separately from other insert failures. One batch insert creates one part per
partition it touches, so the insert count is the service's own measure of the part pressure it imposes
on the store — a number it cannot obtain by querying, because the query surface reaches only
catalog-registered tables and ClickHouse's parts metadata is not among them. These two counts are
attributed to the target table rather than to the ingest path that produced them, unlike the per-path
row counts above: the store's part pressure is a property of the table, and every ingest path writes
through the same write path. The ledger's own inserts SHALL be counted the same way, under the ledger
table's name, so the parts the ledger adds are observable beside those of the target tables.

#### Scenario: Metrics reflect a completed run

- **WHEN** a sync run or poll cycle completes
- **THEN** the corresponding metrics (files processed, rows inserted/skipped/dropped, run/poll
  outcome, duration, last-success timestamp, and poller discovery/lag counters) are updated to
  reflect that run

#### Scenario: Ledger skips are counted per path

- **WHEN** a reconciliation run, poll cycle or backfill skips blobs because the ledger records them
- **THEN** that path's skipped-by-ledger count is incremented by the number of such blobs, and neither its
  files-processed nor its blobs-discovered count is

#### Scenario: Ledger inserts are counted under the ledger table

- **WHEN** a path records blobs in the ledger
- **THEN** the per-table batch-insert count for the ledger table is incremented, separately from the target
  tables' counts

#### Scenario: Dropped records are counted

- **WHEN** a run encounters records lacking a deduplication key
- **THEN** the dropped-rows metric is incremented by the number of such records

#### Scenario: Batch inserts are counted per table

- **WHEN** an ingestion run flushes batches to more than one target table
- **THEN** each table's insert count reflects the batch inserts issued for that table, so the
  part-creation rate per table is observable independently of the row counts

#### Scenario: A refused insert is counted by reason

- **WHEN** a batch insert fails because ClickHouse refused it for lack of disk space
- **THEN** the insert-failure metric for that table is incremented with a reason distinguishing disk
  exhaustion from other insert failures

### Requirement: Settled ingest day partitions are force-merged so their duplicates collapse

`dial_usage_log_payload` and `dial_usage_log` SHALL each carry `min_age_to_force_merge_seconds = 172800`
(2 days) together with `min_age_to_force_merge_on_partition_only = 1`. Once every active part of a day
partition is older than that interval, ClickHouse SHALL merge the partition into a single part, and because
both tables are `ReplacingMergeTree` tables that merge SHALL leave exactly one row per key — for
`dial_usage_log_payload` the row for each `event_id` with the greatest `_updated_at`, and for `dial_usage_log`
the row for each full sort key with the greatest `_ingested_at`. A partition with any part younger than the
interval SHALL NOT be force-merged, and the settings SHALL NOT alter which merges the automatic selector
already performs on it.

The settings exist because a settled day can still hold more than one version of a row — a blob re-ingested
because the service stopped, or the ledger write failed, between writing its rows and recording it; a blob
rewritten by its producer; or an operator backfill that includes already-ingested blobs — and a settled day is
otherwise never merged into one part: the automatic selector stops picking a partition once it is down to a
few similar-sized parts, so the stale versions stay until the partition expires. Since the ingest ledger stops
the reconciliation run from re-writing what the poller already wrote, these duplicates are the exception
rather than half the partition; the force-merge remains the backstop that collapses them. `min_age_to_force_merge_on_partition_only`
is what makes the result one part: without it a partition holding a newer part could be merged piecemeal and
still be left with several.

`dial_usage_log_payload` SHALL additionally carry `vertical_merge_algorithm_min_columns_to_activate = 1` and
`vertical_merge_algorithm_min_rows_to_activate = 1`, so every merge of it that writes a wide part, forced or
chosen by the automatic selector, runs with the vertical algorithm. ClickHouse merges vertically only into a
wide part (one above `min_bytes_for_wide_part`, 10 MiB by default), which on these rows is any merge of more
than a few dozen of them; a merge of tiny parts into a compact part stays horizontal and is cheap either
way. Its rows are heavy: on the order of 100 KiB of bodies per row in typical
usage, with single bodies of several MiB, plus two JSON documents with some 100 paths between them.
ClickHouse picks the algorithm by column count, not width, and with 8 columns outside the sort key and version
against the default threshold of 11 every merge would otherwise be horizontal, reading every column of every
source part at once. The vertical algorithm merges the key columns first and then streams the other columns one
at a time, so a merge's memory follows its widest column. Measured on 26.3 against a partition shaped like a
busy day (8 source parts, realistic row widths), the whole-partition merge peaked at roughly a third of the
horizontal algorithm's memory (a few hundred MiB against several hundred), also with twice the rows, and ran faster either
way; the extra pass reads only the key columns, a UUID and a timestamp. `dial_usage_log` SHALL NOT carry
them: its rows are light and it already reaches the default column threshold.

The interval SHALL exceed the gaps between writes that the routine writers produce into one partition — the
poller during the day, the reconciliation run early on the day after for the blobs the poller missed, and a
rare straggler a day later — so a partition is not collapsed and then reopened by them. Each write restarts the partition's age. A write into a
collapsed partition, for example an operator-triggered backfill of an older day or a reconciliation run
delayed by more than the interval, is not an error; it restarts the interval, and the partition is merged
again once it has settled.

The settings SHALL be provisioned by a ClickHouse migration, in the tables' own settings so that Flyway and an
operator applying the migrations out of band provision them identically; the migration has no dependency on
the build, so an out-of-band operator MAY apply it before or after. They SHALL NOT be configurable through the
service: neither an environment property nor a `/v1/tables` field sets them, the catalog does not mirror
them, and an operator changes or removes them with `ALTER TABLE … MODIFY SETTING …` or `RESET SETTING …`.
Applying them SHALL be metadata-only — no mutation is scheduled and no part is rewritten by the migration
itself — and re-applying the migration SHALL succeed and change nothing.

Applying the settings requires the `ALTER SETTINGS` privilege on each table. `dial_usage_log` already needed
it for its retention setting; `dial_usage_log_payload` needs it newly, so a writer credential granted `ALTER`
sub-privileges individually fails startup at this migration until it is granted — by design, a migration
failure aborts startup rather than serving against a half-provisioned table. A wholesale `ALTER` grant is
unaffected.

The settings SHALL take effect on parts that already exist. On the deployment that first applies the
migration, every existing partition whose parts are all older than the interval becomes a merge candidate at
once. Such merges run on the shared background pool, need free disk of about twice the partition's size while
they write the new part, and are bounded by the server's merge memory share; a merge that exceeds the memory
share fails and is retried. `dial_usage_log`'s partitions are small, so the cost is concentrated in
`dial_usage_log_payload`, whose partitions reach tens of GiB. A deployment short of disk or memory MAY stage
the rollout by applying a longer interval by hand, with migrations disabled, and lowering it step by step to
the shipped value.

Deduplicating on disk SHALL NOT change the result of any final read. A final read — every pipeline read, which runs under `final = 1`, and every user-facing read that is read final (see
[structured-query](../structured-query/spec.md) "Read result deduplication") — continues to deduplicate at query time,
so it returns one row per key whether or not the partition has been merged; a raw user-facing read sees
exactly the rows the parts hold, fewer once a force-merge has collapsed them. Row expiry
is unaffected: each table's `TTL` and `ttl_only_drop_parts = 1` are unchanged, and a force-merged partition is
dropped as a whole exactly as before.

#### Scenario: A settled multi-part payload partition collapses to one part

- **WHEN** a day partition of `dial_usage_log_payload` holds several parts containing several versions of the
  same `event_id`, and every part is older than 2 days
- **THEN** the partition is merged into one part holding one row per `event_id`, the row with the greatest
  `_updated_at`

#### Scenario: A settled multi-part usage-log partition collapses to one part

- **WHEN** a day partition of `dial_usage_log` holds several parts containing several versions of the same
  sort key, and every part is older than 2 days
- **THEN** the partition is merged into one part holding one row per sort key, the row with the greatest
  `_ingested_at`

#### Scenario: A partition with a recent part is left to the automatic selector

- **WHEN** a day partition holds parts older than 2 days and one part younger than 2 days
- **THEN** the force-merge does not select the partition, and the partition keeps its parts until either the
  automatic selector merges them or the youngest part reaches 2 days

#### Scenario: A late write into a collapsed partition restarts the interval

- **WHEN** a backfill inserts rows into a day partition that was already collapsed to one part
- **THEN** the insert succeeds, the partition now holds the old part and the new one, and it is force-merged
  again only after both are older than 2 days

#### Scenario: The migration provisions the settings on both tables without rewriting data

- **WHEN** the migration is applied to `dial_usage_log_payload` and `dial_usage_log` holding data
- **THEN** each table's declaration lists `min_age_to_force_merge_seconds = 172800` and
  `min_age_to_force_merge_on_partition_only = 1` among its settings, `dial_usage_log_payload`'s also lists
  `vertical_merge_algorithm_min_columns_to_activate = 1` and `vertical_merge_algorithm_min_rows_to_activate = 1`
  while `dial_usage_log`'s lists neither, no part is rewritten by the statements themselves, and each table's
  sort key, partitioning, `TTL` and `ttl_only_drop_parts = 1` are unchanged

#### Scenario: A payload merge runs with the vertical algorithm

- **WHEN** a day partition of `dial_usage_log_payload` is merged into a wide part, by the force merge or the
  automatic selector
- **THEN** `system.part_log` records the merge with `merge_algorithm = 'Vertical'`

#### Scenario: Re-applying the migration is harmless

- **WHEN** the migration statements are run again against tables that already carry the settings
- **THEN** they succeed and the settings are unchanged

#### Scenario: Removing the settings restores the previous behaviour

- **WHEN** an operator runs `ALTER TABLE <table> RESET SETTING min_age_to_force_merge_seconds,
  min_age_to_force_merge_on_partition_only` on `dial_usage_log_payload` or on `dial_usage_log`
- **THEN** the statement succeeds, that table's declaration no longer lists either setting, and its merges
  return to the automatic selector alone

#### Scenario: A read is identical before and after the collapse

- **WHEN** the same final read naming a column of either table is run before and after a partition is
  force-merged
- **THEN** both return one row per key with the same values

#### Scenario: A writer without the settings grant fails startup at the migration

- **WHEN** migrations are enabled and the writer credential lacks the privilege to alter the settings of one of
  the two tables
- **THEN** startup fails at this migration rather than serving against a table that lacks the settings

### Requirement: Target tables are provisioned by versioned ClickHouse migrations

By default the service SHALL provision the target ClickHouse tables (`dial_usage_log` and
`dial_usage_log_payload`) and the ingest ledger table (`_dial_internal_ingested_blob`) on startup by running Flyway migrations under `classpath:db/migration` over
the ClickHouse JDBC driver, using the writer credential (which must therefore additionally hold
`CREATE TABLE` grants, and `ALTER DROP COLUMN` on `dial_usage_log`). Provisioning SHALL be gated by
`clickhouse.migrations.enabled` (default true) and SHALL run before any sync run or startup schema
validation; a migration failure SHALL abort startup. When migrations are disabled, the service SHALL
issue no DDL and the operator SHALL provision the tables out of band by applying the same migration
DDL, in version order: the `CREATE TABLE`
(`src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql`) followed by every later
migration that alters it or creates a sibling table. An operator who skips a later migration SHALL be
stopped by startup schema validation rather than ingesting against a stale table. The ingest ledger table is
not an ingest target, but startup schema validation SHALL cover it as well, so a deployment that skipped its
migration does not start.

Whether applied by Flyway or by an operator, the `dial_usage_log` definition SHALL declare the `events`
column set: a synthetic `event_id UUID` and an `event_kind` classifier; the direct columns carried from
the source (timestamps, ids, `user_hash`, tokens — including the producer's reported cache-write count
in `cache_creation_tokens` — prices, the producer's reported `operation_duration_ms`, etc.); and the
calculated columns (`response_id`, `reasoning_tokens`, `number_request_messages`, `success`,
`mcp_method`, `mcp_tool_call_name`, `request_body_bytes`, `response_body_bytes`). It SHALL NOT declare
the five verbatim-content columns — the three bodies and the two JSON documents. They were created by
`V1`/`V7`, moved to `dial_usage_log_payload` by `V10` and the mapping cutover, and dropped from
`dial_usage_log` by `V11`; the migration set as a whole therefore leaves them on the payload table
only. The two `*_bytes` columns SHALL survive that drop, because they size a payload without holding
one and must remain answerable past the payload table's retention.

On `dial_usage_log_payload`, the JSON columns use ClickHouse's `JSON` type, which is generally
available across the supported server range (ClickHouse **25.3 LTS or newer**, verified through
**26.3 LTS**) and SHALL require no experimental enablement setting; the body columns use the
`AES_256_GCM_SIV` codec, which requires a server-side encryption key or `CREATE TABLE` fails. That key
SHALL also be required to provision `dial_usage_log` from scratch, because `V1` creates its body
columns under the same codec before `V11` drops them. For record-level deduplication to function,
`dial_usage_log` SHALL use the `ReplacingMergeTree` engine versioned by `_ingested_at`, with a sort key
(`ORDER BY`) ending in the deduplication key columns `trace_id` and `core_span_id`, partitioned by
`toYYYYMMDD(request_time)`. The definition SHALL also pin UTC on its timestamp columns, store monetary
columns as `Decimal`, store `response_status` as `UInt16`, declare the producer-optional columns as
`Nullable` — including every producer-reported token count — and apply a row-level retention `TTL`.

Each table SHALL leave exactly one column to a table-side `DEFAULT` and populate every other column its
mapping declares: `_ingested_at` on `dial_usage_log`, `_updated_at` on `dial_usage_log_payload`.

#### Scenario: Service provisions the table on startup

- **WHEN** migrations are enabled (the default) and the service starts
- **THEN** Flyway applies the `db/migration` DDL with the writer credential, creating `dial_usage_log`,
  `dial_usage_log_payload` and `_dial_internal_ingested_blob` if they do not exist, before any sync run or schema
  validation

#### Scenario: JSON columns provision without an experimental flag

- **WHEN** the table is provisioned on a supported server version (ClickHouse 25.3 LTS or newer,
  verified through 26.3 LTS) with no `allow_experimental_json_type` setting configured
- **THEN** the `CREATE TABLE` for the `jwt_claims`/`request_tags` `JSON` columns succeeds, because the
  `JSON` type is generally available on that version

#### Scenario: Migration failure aborts startup

- **WHEN** a startup migration fails (e.g. the writer credential lacks `CREATE TABLE` or a required
  server-side feature is unavailable)
- **THEN** the service fails to start rather than proceeding to run sync against an unprovisioned table

#### Scenario: Migrations disabled — operator provisions out of band

- **WHEN** `clickhouse.migrations.enabled` is false
- **THEN** the service issues no DDL and inserts into the tables the operator provisioned out of band
  from the same migration DDL

#### Scenario: Operator misses a later migration

- **WHEN** `clickhouse.migrations.enabled` is false and the operator provisioned `dial_usage_log` from
  the `CREATE TABLE` alone, omitting the later migration that creates `dial_usage_log_payload`
- **THEN** startup schema validation fails, because the payload mapping cannot be reconciled against a
  table that does not exist, and the service does not start

#### Scenario: Table definition supports record-level dedup

- **WHEN** the tables are provisioned (by Flyway or by an operator) from the migration DDL
- **THEN** `dial_usage_log` is a `ReplacingMergeTree` whose sort key ends in `trace_id` and
  `core_span_id`, and `dial_usage_log_payload` is a `ReplacingMergeTree` ordered by `event_id`, so
  re-ingested rows for the same span collapse on merge in both

#### Scenario: Every user column is populated

- **WHEN** the tables are provisioned and ingestion runs with the shipped mappings
- **THEN** every column of each table that its mapping declares is populated by that mapping, and the
  only columns left to a value the mapping did not supply are the two table-side defaults
  (`_ingested_at`, `_updated_at`)

#### Scenario: Operator misses the ledger migration

- **WHEN** `clickhouse.migrations.enabled` is false, schema validation is enabled, and the operator
  provisioned every ingest table but not `_dial_internal_ingested_blob`
- **THEN** startup schema validation fails and the service does not start
