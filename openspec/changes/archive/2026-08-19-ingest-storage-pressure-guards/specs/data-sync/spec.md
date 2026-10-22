## ADDED Requirements

### Requirement: Retention expiry drops whole daily partitions

The target table's `TTL` expiry SHALL drop whole parts rather than rewrite them
(`ttl_only_drop_parts = 1`), so that deleting expired rows requires no free disk space. A row-level TTL
rewrite writes its output before releasing its input, so it needs headroom equal to the part it
rewrites — on a volume that has filled up, the mechanism meant to reclaim space is the one thing that
cannot run.

Because the table is partitioned by `toYYYYMMDD(request_time)` and its `TTL` is expressed on
`request_time`, a part holds one day's rows and they expire within the same day of each other. Expiry is
therefore quantized to a part rather than exact to the row: a part is dropped once its newest row has
expired, so a row MAY outlive its `TTL` by up to the span its part covers (at most one day). Against a
one-year retention this is immaterial, and it is the deliberate trade for expiry that can run on a full
volume.

The setting SHALL be applied by a versioned ClickHouse migration, so that it is provisioned identically
whether Flyway applies it or an operator applies the DDL out of band in version order.

#### Scenario: Provisioned table declares whole-part expiry

- **WHEN** the target table is provisioned (by Flyway or by an operator applying the migrations in
  version order)
- **THEN** its declaration carries `ttl_only_drop_parts = 1`, so a later `TTL` expiry drops expired
  daily parts instead of rewriting them

#### Scenario: Expiry needs no free space

- **WHEN** the retention `TTL` becomes due for a day's partition while the volume has no headroom for a
  part rewrite
- **THEN** the expired parts are dropped rather than rewritten, so expiry proceeds and returns space
  instead of failing for lack of it

### Requirement: An insert refused for lack of disk space is classified distinctly

The service SHALL recognise a ClickHouse write refused for lack of disk space (server error code 243,
`NOT_ENOUGH_SPACE`) as its own failure mode, distinct from an otherwise-failed insert, and SHALL report
it as such in logs and metrics, naming the target table. Recognition SHALL be by the numeric server
error code rather than by matching message text.

A failed batch insert is a **run-level** fault, not the blob-scoped failure the poller isolates (a
corrupt blob is counted, skipped, and does not hold the watermark): whatever the reason, the run stops
writing, and a poll cycle leaves both its watermark and its record of the blobs it ingested uncommitted,
so those blobs stay eligible for re-ingest and the next cycle retries. Daily ingest therefore resumes on
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
- **THEN** the cycle fails without advancing the watermark or committing the blobs it ingested, and a
  later cycle re-ingests them successfully once the write path is healthy again

#### Scenario: Another insert failure keeps its generic treatment

- **WHEN** a batch insert fails for a reason other than error code 243
- **THEN** the failure is surfaced as an ordinary run-level insert failure, not as disk exhaustion

## MODIFIED Requirements

### Requirement: Ingest observability

The service SHALL expose ingest observability through logs and Micrometer metrics, including counts
of files processed, rows inserted, rows skipped (unparseable lines), rows dropped (records lacking the
deduplication key), run outcome, run duration, and the timestamp of the last successful run. The
service SHALL additionally expose poller metrics: poll cycles executed, blobs discovered and ingested
per cycle, poll failures, and the current watermark lag (how far the watermark trails the current
time). Because deduplication is performed by the table engine rather than by skipping whole files, the
files-skipped metric SHALL NOT denote already-ingested files. No HTTP status endpoint is provided in
this phase.

The ingest write path SHALL additionally expose, per target table, the number of batch inserts it
issued and the number that failed, the latter tagged by reason so that a write refused for lack of disk
space is countable separately from other insert failures. One batch insert creates one part per
partition it touches, so the insert count is the service's own measure of the part pressure it imposes
on the store — a number it cannot obtain by querying, because the query surface reaches only
catalog-registered tables and ClickHouse's parts metadata is not among them. These two counts are
attributed to the target table rather than to the ingest path that produced them, unlike the per-path
row counts above: the store's part pressure is a property of the table, and every ingest path writes
through the same write path.

#### Scenario: Metrics reflect a completed run

- **WHEN** a sync run or poll cycle completes
- **THEN** the corresponding metrics (files processed, rows inserted/skipped/dropped, run/poll
  outcome, duration, last-success timestamp, and poller discovery/lag counters) are updated to
  reflect that run

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
