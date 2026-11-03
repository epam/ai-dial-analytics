# data-sync-failures Specification

## Purpose

Keep a durable, queryable record of every log blob and every day that failed to ingest on any data-sync path,
so that no record reaching blob storage goes missing without evidence. Each failure is classified as transient
or deterministic. Transient failures are retried automatically by a scheduled sweep with exponential backoff,
across days, until they succeed or park. Deterministic failures park at once for an operator. Outstanding
failures are listed and requeued over HTTP, and counted by gauges an operator can alert on.
## Requirements
### Requirement: Failed ingests are recorded in a persisted failure ledger

The service SHALL record every blob that fails to ingest, on any ingest path (the poller, the reconciliation
run, the backfill and the retry sweep), and every day whose reconciliation run, backfill day or retry fails as
a whole. It SHALL also record every blob that is ingested but whose records were not all written, because some
lines were skipped or some records dropped. The record SHALL be kept in a ClickHouse table
`_dial_internal_failed_ingest` in the same database as the ingest ledger, so the record of what failed shares a
failure domain with the record of what was ingested.

The table SHALL be an append-only log of events. Each event names a **subject** of one of three kinds:
- `blob`: a blob that failed to ingest, identified by its full key in the container and the last-modified time
  the store listed for it;
- `day`: identified by its ISO `yyyy-MM-dd` UTC date;
- `records`: the skipped records of a blob that was otherwise ingested, identified like a blob. A blob can have a
  `blob` subject and a `records` subject at the same time; they are independent.

Each event SHALL be one of three kinds:
- `failed`: an attempt failed. It carries the failure class, a stable reason code, a message and the ingest
  path that made the attempt.
- `resolved`: the subject no longer needs recovery.
- `requeued`: an operator asked for the subject to be attempted again.

An event SHALL also carry the UTC date it belongs to, and the time it was written. For a day that date is the
day itself. For a blob or a `records` subject it is the UTC date of the blob's listed last-modified time, or the
date of the event when the store listed none.

A message SHALL be at most 512 characters. It SHALL be taken from the failure's exception chain and SHALL NOT
contain record content. For a mapping fault it SHALL therefore name only the target table and the exception
types, because the messages of a mapping fault can quote the record. For a `records` subject it SHALL list each
cause that occurred (`malformed_line`, `oversized_line`, `missing_dedup_key`), with its count and its first line
numbers, as many as fit.

Events SHALL be kept for 13 months after the date they belong to and then expire. For a blob that date is the
blob's last-modified date, so its failure events expire with the ingest-ledger entry that can resolve them. A
failure whose events outlived that entry would otherwise come back as outstanding. Like the ingest ledger,
removal MAY lag by up to one month. A failure of a blob or day already older than 13 months, which only a
backfill reaches, therefore expires as soon as it is written, like the ingest-ledger entry such a backfill
writes; it shows on the `sync.failures.recorded` counter and in the logs only. The table SHALL NOT be registered in the query catalog and SHALL NOT be reachable
through any query surface.

A blob failure SHALL be recorded once per failed attempt. There is one exception: the poller retries a failing
blob on every cycle while it stays inside the overlap window, and it SHALL record that blob's failure only once
per listed version. Its own retries therefore do not use up `sync.retry.max-attempts` before the retry sweep's
backoff begins.

The blob failures of one poll cycle, or of one reconciliation or backfill day, SHALL be written together in one
insert before the cycle's or day's final flush. Writing them one at a time would add a part to the table per
failing blob, and a fault such as a mapping regression fails every blob.

A `records` failure SHALL be written in the hand-over that records its blob in the ingest ledger, before the
ingest-ledger entry is written. The `rejected_by_server` failure of a blob refused by a flush SHALL be written in the
hand-over that follows that flush. The `records` and `rejected_by_server` failures of one hand-over SHALL be written in
a single insert, which is issued only when the hand-over has any. A blob recorded as ingested therefore always has its skipped records on record, even if the service stops
right after recording it. A failure to write them SHALL fail the poll cycle or day, exactly as a failed
ingest-ledger write does, and the blobs of that hand-over SHALL NOT be recorded. A blob whose ingest failed records
no `records` failure: its `blob` failure covers it, and the attempt that ingests it records its skipped records
then.

The poller SHALL NOT record day failures. A failed poll cycle holds its watermark and is retried by the next
cycle, so no part of it is left for a later path.

#### Scenario: A blob failure is recorded

- **WHEN** a reconciliation run reads a blob whose gzip stream is corrupt
- **THEN** the failure ledger holds a `failed` event for that blob carrying its key, its listed
  last-modified time, the class `deterministic`, the reason `corrupt_gzip`, the path `reconcile` and a
  message

#### Scenario: Skipped records are recorded before the blob is

- **WHEN** a poll cycle ingests a blob with two malformed lines and one oversized line
- **THEN** the failure ledger holds a `records` failure for the blob with the class `deterministic`, the reason
  `skipped_records`, the path `poll`, and a message naming two `malformed_line` and one `oversized_line` with their
  line numbers. That failure is written before the blob's ingest-ledger entry

#### Scenario: A failed write of skipped records leaves the blob unrecorded

- **WHEN** writing a blob's `records` failure fails during a poll cycle
- **THEN** the cycle fails, the blob is not recorded in the ingest ledger, and the watermark holds

#### Scenario: A day failure is recorded

- **WHEN** a backfill day fails because listing its prefix fails
- **THEN** the failure ledger holds a `failed` event for that day with the class `transient`, the reason
  `listing_failed` and the path `backfill`

#### Scenario: A failed poll cycle records no day failure

- **WHEN** a poll cycle fails because its final flush fails
- **THEN** no day event is written, the watermark holds, and the next cycle retries the window

#### Scenario: The failure ledger is not queryable

- **WHEN** a caller lists entities or runs a query through any query surface
- **THEN** `_dial_internal_failed_ingest` is neither listed nor readable

#### Scenario: Old events expire

- **WHEN** an event belongs to a date more than 13 months ago
- **THEN** it is removed within one further month, and events of later dates are kept

### Requirement: Failures are classified as transient or deterministic

Every recorded failure SHALL carry one class, `transient` or `deterministic`, and one stable reason code.

The service SHALL classify a failure as `deterministic` only when repeating the attempt cannot succeed with
the same blob and the same configuration:

| Reason code          | Failure |
|----------------------|---------|
| `corrupt_gzip`       | The blob's gzip stream is invalid: a bad header, a bad CRC or an invalid deflate block. |
| `mapping_error`      | Mapping a record, or a deriver or transform applied to it, raised an error. |
| `blob_missing`       | The blob is no longer listed by the store, so it was deleted or has expired. |
| `rejected_by_server` | ClickHouse refused the blob's rows with a data-class error, isolated by splitting the batch. |
| `skipped_records`    | The blob was ingested, but some of its lines were skipped or records dropped. It applies only to `records` subjects. |

Every other failure SHALL be `transient`. In particular, a gzip stream that ends early (an end-of-input
`EOF`) SHALL be `transient`, because a read that was cut short cannot be told apart from a truncated blob. The
transient reason codes are:

| Reason code      | Failure |
|------------------|---------|
| `read_error`     | Opening or reading a blob failed, or another blob-level failure occurred. |
| `listing_failed` | Listing a day's prefix failed. |
| `prepare_failed` | Preparing a day failed: resolving its prefix, or reading the catalog for its write path. |
| `write_failed`   | During the blob loop, a batch insert that was not split by blob, a read or write of the ingest ledger, or a failure-event write failed. |
| `storage_full`   | ClickHouse refused a write for lack of disk space. |

The last four apply only to days. A failed retry of a `records` subject is classified as a blob's would be.

#### Scenario: A mapping fault is deterministic

- **WHEN** a deriver raises an error while a record of a blob is mapped
- **THEN** the blob's failure is recorded with the class `deterministic` and the reason `mapping_error`

#### Scenario: A refused blob is deterministic

- **WHEN** a batch split isolates a blob whose rows ClickHouse still refuses with `TYPE_MISMATCH`
- **THEN** the blob's failure is recorded with the class `deterministic`, the reason `rejected_by_server`, and a
  message naming the target table and `53 TYPE_MISMATCH`

#### Scenario: An early end of the gzip stream is transient

- **WHEN** reading a blob ends with an unexpected end of the gzip input
- **THEN** the failure is recorded with the class `transient` and the reason `read_error`

#### Scenario: Disk exhaustion fails a day transiently

- **WHEN** a reconciliation day fails because ClickHouse refused a batch insert for lack of disk space
- **THEN** the day's failure is recorded with the class `transient` and the reason `storage_full`

### Requirement: Failure state is derived from the events and the ingest ledger

The service SHALL derive each subject's state from its events, and for a blob also from the ingest ledger. The
state SHALL be exactly one of `pending`, `parked` or `resolved`.

The derivation SHALL work as follows.

- **Reset point.** A subject's reset point is its latest `requeued` or `resolved` event.
- **Attempts.** A subject's attempt count is the number of `failed` events written after its reset point.
- **Resolved.** A subject is `resolved` when its latest event is `resolved`. A blob is also `resolved` when the
  ingest ledger holds an entry for its key that was written after the blob's latest failure, with a last-modified
  time at or after the one that failure carried. A blob that is rewritten and then ingested therefore resolves its
  earlier failure. An entry written before the failure does not resolve it: that is a blob ingested once and
  failing again, for example in a backfill that includes already-ingested blobs. The ingest ledger SHALL NOT
  resolve a `records` subject, because the very ingest that skipped its records also records its blob there.
- **Pending after a requeue.** An unresolved subject with no `failed` event after its reset point, which means
  it was requeued, is `pending`.
- **Parked.** Any other unresolved subject is `parked` when the class of its latest failure is
  `deterministic`, or when its attempt count has reached `sync.retry.max-attempts`.
- **Pending.** Every other unresolved subject is `pending`.
- **Next attempt.** A pending subject's next attempt is due at its latest failure time plus a backoff of
  `sync.retry.initial-backoff × 2^(attempts − 1)`, capped at `sync.retry.max-backoff`. A pending subject whose
  latest event is `requeued` is due at once.

The service SHALL write a `resolved` event in five cases:
- a run of a day that has an unresolved day failure completes its day without a day-level failure;
- a retry of a blob succeeds;
- a retry finds the blob already recorded in the ingest ledger;
- a sweep finds an unresolved blob that the ingest ledger has resolved since it failed. This keeps the set of
  unresolved subjects to what is actually missing, rather than letting it grow with every blob that recovered on
  its own path;
- a retry of a `records` subject ingests its blob with no skipped line and no dropped record.

A day run with failed blobs still resolves its day failure. Its blobs stay outstanding on their own entries.

A subject that is `pending` or `parked` is **outstanding**.

Because the derivation reads the backoff and attempt settings when it runs, changing them applies to existing
entries.

#### Scenario: An earlier ingest does not resolve a later failure

- **WHEN** a blob was ingested and recorded, and a later backfill that includes already-ingested blobs fails it
  with a mapping error
- **THEN** the blob is `parked` and listed, rather than resolved by the entry written before it failed

#### Scenario: Skipped records stay parked although the blob is ingested

- **WHEN** a blob is ingested and recorded in the ingest ledger, and its `records` failure was written in the same
  hand-over
- **THEN** the `records` subject is `parked`, and a later sweep does not resolve it through the ingest ledger

#### Scenario: A blob ingested later resolves its failure

- **WHEN** a blob failed on the poller and a later poll cycle or reconciliation run ingests it and records it
  in the ingest ledger
- **THEN** the blob's state is `resolved` without any further write to the failure ledger

#### Scenario: A rewritten blob that is ingested resolves its failure

- **WHEN** a blob failed with one last-modified time, the producer rewrote it, and the rewritten blob was
  ingested and recorded with a later last-modified time
- **THEN** the earlier failure is `resolved`

#### Scenario: A successful day run resolves its day failure

- **WHEN** a day has an unresolved day failure and a later reconciliation run, backfill day or retry of that
  day lists and processes it without a day-level failure
- **THEN** a `resolved` event is written for the day and its state is `resolved`

#### Scenario: Transient failures back off

- **WHEN** a blob has three transient failures since its reset point, `sync.retry.initial-backoff` is
  `15m` and `sync.retry.max-backoff` is `24h`
- **THEN** it is `pending` and its next attempt is due 60 minutes after its latest failure

#### Scenario: A deterministic failure parks at once

- **WHEN** a blob's first and only failure is `corrupt_gzip`
- **THEN** its state is `parked` and no retry is scheduled for it

#### Scenario: Reaching the attempt limit parks a transient failure

- **WHEN** a blob's transient failures since its reset point reach `sync.retry.max-attempts`
- **THEN** its state is `parked`

### Requirement: Outstanding failures are retried automatically

The service SHALL run a retry sweep on the schedule `sync.retry.cron`, in-process and with no HTTP trigger. It
SHALL NOT start a sweep while the previous one is still running.

Each sweep SHALL take the `pending` subjects whose next attempt is due, oldest latest failure first, and
attempt at most `sync.retry.max-per-sweep` of them. A blob, a day and a `records` subject each count as one
attempt.

A blob attempt SHALL list the blob by its key. Then:
- If the listing fails, or the store no longer lists the blob, the new `failed` event SHALL carry the version
  and day of the failure it retries. Recording no version would let any earlier ingest of the blob count as
  resolving it.
- If the store no longer lists the blob, the sweep SHALL record a `failed` event with the reason
  `blob_missing`, which parks it.
- If the ingest ledger records the blob as listed, in an entry written after the blob's latest failure, the
  sweep SHALL write `resolved` without reading it. An entry written before the failure says nothing about the
  failed attempt, so the blob is read again.
- Otherwise the sweep SHALL ingest the blob through the shared parse → map → batch-insert pipeline, record it
  in the ingest ledger once its rows are written, and write `resolved`. Each blob SHALL be written before the
  next is read, so a failed batch insert or ingest-ledger write is that blob's failed attempt, not a fault of
  the sweep.

A `records` attempt is pending only after an operator requeued it, because its failures are deterministic. It
SHALL list the blob as a blob attempt does, with the same `blob_missing` outcome. It SHALL NOT consult the ingest
ledger, which always records the blob. It SHALL ingest the blob through the same pipeline and record it in the
ingest ledger. Then:
- when ClickHouse refuses the blob's rows, it records a `rejected_by_server` failure with the path `retry` on the
  `records` subject as well as on the blob, which parks both; without it the `records` subject would stay pending
  with no attempts and be re-read by every sweep;
- otherwise, with no skipped line and no dropped record, it writes `resolved`;
- otherwise it records a new `skipped_records` failure with the path `retry`, which parks the subject again with
  the current counts.

A failed blob attempt SHALL record a new `failed` event with the path `retry`. A write failure is classified as
any other blob-level failure, so the blob backs off and eventually parks rather than failing every sweep ahead
of the other due subjects. A `records` attempt that fails to read or write its blob is recorded the same way, on
its `records` subject.

A day attempt SHALL re-run the day exactly as a reconciliation run does. It skips the blobs the ingest ledger
records, even when the day's original failure happened in a backfill that included already-ingested blobs. A
blob that fails within that day SHALL record its own failure, as on every path.

With `sync.retry.enabled` set to `false`:
- the sweep SHALL attempt nothing;
- failures SHALL still be recorded;
- the outstanding-failure gauges SHALL still be refreshed on the sweep's schedule;
- the endpoints SHALL keep working.

Parked subjects SHALL NOT be attempted until an operator requeues them.

#### Scenario: A transient blob failure is recovered after the overlap window

- **WHEN** a blob failed on the poller with a transient error, aged out of the poller's overlap window, and
  its next attempt is due
- **THEN** the next sweep ingests it, records it in the ingest ledger and writes `resolved`

#### Scenario: A requeued blob is read again despite an older ingest-ledger entry

- **WHEN** a blob was ingested and recorded, a later backfill that includes already-ingested blobs parks it with
  a mapping error, and an operator requeues it after the mapping is fixed
- **THEN** the next sweep reads and ingests the blob again before writing `resolved`

#### Scenario: A requeued blob refused by the server is ingested after a fix

- **WHEN** a blob is parked with `rejected_by_server`, the cause is fixed, and an operator requeues it
- **THEN** the next sweep ingests it, records it in the ingest ledger and writes `resolved`

#### Scenario: A rewritten blob resolves its skipped records

- **WHEN** a blob's `records` subject is parked, the producer rewrote the blob without the bad lines, and an
  operator requeues the subject
- **THEN** the next sweep re-ingests the blob although the ingest ledger records it, finds nothing skipped, and
  writes `resolved`

#### Scenario: Unchanged skipped records park again

- **WHEN** an operator requeues a `records` subject whose blob still holds the same malformed line
- **THEN** the next sweep re-ingests the blob and records a new `skipped_records` failure, and the subject is
  `parked`

#### Scenario: Requeued records of a refused blob park again

- **WHEN** an operator requeues a `records` subject and ClickHouse now refuses its blob's rows with a data-class error
- **THEN** the next sweep records a `rejected_by_server` failure on both the blob and its `records` subject, both are
  `parked`, and later sweeps do not attempt them

#### Scenario: A write failure during a sweep is the blob's attempt

- **WHEN** the batch insert of a retried blob fails
- **THEN** the blob records a `failed` event with the path `retry` and its attempts grow, and the sweep goes on
  to the next due subject

#### Scenario: A failed reconciliation day is retried

- **WHEN** a reconciliation run failed its day because the store could not be listed, and its next attempt
  is due
- **THEN** the next sweep re-runs that day, ingests the blobs the ingest ledger does not record, and writes
  `resolved` for the day

#### Scenario: A blob that recovered on its own leaves the unresolved set

- **WHEN** a blob failed on the poller and the next poll cycle ingested it, and a sweep then runs
- **THEN** the sweep writes `resolved` for the blob, so later reads no longer aggregate it

#### Scenario: A blob that no longer exists is parked

- **WHEN** a sweep attempts a pending blob that the store no longer lists
- **THEN** a `failed` event with the reason `blob_missing` is recorded and the blob is `parked`

#### Scenario: The sweep is bounded

- **WHEN** more due pending subjects exist than `sync.retry.max-per-sweep`
- **THEN** the sweep attempts that many, the oldest first, and leaves the rest for later sweeps

#### Scenario: Retries disabled

- **WHEN** `sync.retry.enabled` is `false` and a blob fails
- **THEN** the failure is recorded and listed, the gauges reflect it, and no sweep attempts it

### Requirement: A failure that cannot be recorded is not lost silently

The service SHALL NOT lose a failure event silently. Writing a failure event is a ClickHouse statement on the
ingest path. For a **blob** failure, a write that fails SHALL fail the poll cycle or the day exactly as a failed ingest-ledger write does: the poller's watermark
holds and the blob is attempted again.

A **day** failure is usually caused by ClickHouse being unavailable, so writing it can fail for the same
reason. The service SHALL then hold the event in memory and write it at the next sweep. The event SHALL keep
its original failure time, class and reason. At most 1000 such events SHALL be held; past that the oldest are
discarded and an error is logged naming each discarded subject. An event held in memory is lost if the service
restarts before it is written, and that loss SHALL be logged at error level when the event is first held.

#### Scenario: A day failure during a ClickHouse outage is written later

- **WHEN** a reconciliation day fails because ClickHouse is unavailable and writing its failure event also
  fails
- **THEN** the event is held in memory, the unwritten-failures gauge counts it, and the first sweep after
  ClickHouse returns writes it with its original failure time

#### Scenario: A blob failure that cannot be recorded fails the cycle

- **WHEN** a blob fails on the poller and writing its failure event fails
- **THEN** the poll cycle fails and its watermark holds

### Requirement: Outstanding failures are listable

The service SHALL expose `GET /v1/data-sync/failures`. It returns the outstanding subjects as an object with:
- a `failures` member: an array of entries;
- a `truncated` member: whether more subjects matched than were returned.

Each entry SHALL carry:
- `kind`: `blob`, `day` or `records`;
- `subject`: the blob key or the ISO date;
- `day`;
- `blob_last_modified`: for blobs and `records` subjects only;
- `state`: `pending` or `parked`;
- `failure_class`, `reason` and `message`: from the latest failure;
- `path`: the path of the latest failure;
- `attempts`;
- `first_failed_at` and `last_failed_at`;
- `next_attempt_at`: for pending subjects only.

Entries SHALL be ordered by `last_failed_at` descending, newest failure first. `resolved` subjects SHALL NOT be
listed.

The endpoint SHALL accept these optional query parameters:
- `state` (`pending` or `parked`, case-insensitive);
- `kind` (`blob`, `day` or `records`, case-insensitive);
- `reason` (a reason code);
- `from` and `to` (inclusive ISO dates on `day`);
- `limit` (1 to 1000, default 100).

A value outside its accepted set, an unparseable date, an inverted range, an empty parameter, or a `limit`
outside its range SHALL be rejected with `400 bad_request` naming the parameter. No match is a `200` with an
empty `failures` array and `truncated` set to `false`. A failure to read ClickHouse SHALL answer
`503 clickhouse_unavailable`.

#### Scenario: Outstanding failures are listed newest first

- **WHEN** one blob is pending, one blob is parked, one day is pending and one earlier blob failure is
  resolved, and the caller requests `GET /v1/data-sync/failures`
- **THEN** the response lists the three outstanding subjects ordered by latest failure, newest first, and does
  not list the resolved one

#### Scenario: Blobs with skipped records are listed

- **WHEN** two blobs have parked `records` subjects and the caller requests
  `GET /v1/data-sync/failures?kind=records`
- **THEN** both are listed with `kind` `records`, `state` `parked`, `reason` `skipped_records`, and the counts and
  line numbers in `message`

#### Scenario: Parked failures are filtered

- **WHEN** the caller requests `GET /v1/data-sync/failures?state=parked&reason=mapping_error`
- **THEN** only parked subjects whose latest failure reason is `mapping_error` are listed

#### Scenario: A limited list reports truncation

- **WHEN** 150 subjects are outstanding and the caller requests `limit=100`
- **THEN** the 100 newest are listed and `truncated` is `true`

#### Scenario: An unknown state is a bad request

- **WHEN** the caller requests `GET /v1/data-sync/failures?state=resolved`
- **THEN** the service responds `400` with `error` `bad_request`, and the message names `pending` and `parked`

### Requirement: Outstanding failures can be requeued

The service SHALL expose `POST /v1/data-sync/failures/retry`. It writes a `requeued` event for every
outstanding subject that matches the request body, and answers `200` with an object whose `requeued` member is
the number of subjects requeued. A requeued subject is `pending`, its attempt count is reset to zero, and the
next sweep that runs after the request is due to attempt it.

The body SHALL accept these optional members, combined with AND:
- `kind`, `state`, `reason`, `from` and `to`, with the same meaning and validation as the list parameters;
- `subjects`: an array of at most 1000 blob keys or ISO dates; a null or blank entry is rejected with
  `400 bad_request`.

A body with no member present SHALL be rejected with `400 bad_request`, so that an empty body cannot requeue
everything by accident. An unknown member is also rejected with `400 bad_request`. Subjects that match nothing
outstanding, resolved subjects included, SHALL be ignored and not counted. Requeueing SHALL work whether or not
`sync.retry.enabled` is on. With retries disabled a requeued subject stays `pending` until retries are enabled.

#### Scenario: Parked mapping failures are requeued after a fix

- **WHEN** three blobs are parked with the reason `mapping_error`, the mapping is corrected and redeployed,
  and the operator posts `{"state": "parked", "reason": "mapping_error"}`
- **THEN** the response reports `requeued` `3`, the three blobs are `pending` with zero attempts, and the
  next sweep ingests them

#### Scenario: An empty retry body is refused

- **WHEN** the operator posts `{}` to `/v1/data-sync/failures/retry`
- **THEN** the service responds `400` with `error` `bad_request` and requeues nothing

#### Scenario: A resolved subject is not requeued

- **WHEN** the operator posts a `subjects` list naming one parked blob and one resolved blob
- **THEN** `requeued` is `1` and only the parked blob is requeued

### Requirement: Failure endpoints are authorized

`GET /v1/data-sync/failures` SHALL require `REGISTRY_READ`. `POST /v1/data-sync/failures/retry` SHALL require
`DATA_SYNC_OPERATE`, as triggering a backfill does.

#### Scenario: A read-only admin can list but not requeue

- **WHEN** a caller holding `REGISTRY_READ` but not `DATA_SYNC_OPERATE` lists failures and then posts a
  retry
- **THEN** the list is served with `200`, and the retry is refused with `403` and `error` `forbidden`

### Requirement: Failure observability

The service SHALL expose these Micrometer meters:
- **`sync.failures.recorded`**: a counter of recorded failures, tagged by `kind`, `path` and `reason`.
- **`sync.retry.attempts`**: a counter of sweep attempts, tagged by `kind` and `outcome`. The outcome is
  `success`, `failure`, or `resolved` when a blob was found already recorded.
- **`sync.failures.outstanding`**: a gauge of outstanding subjects, tagged by `kind` and `state`, refreshed by
  every sweep and once at startup.
- **`sync.failures.unwritten`**: a gauge of the failure events held in memory because they could not be
  written.

The reconciliation run, the poller and the backfill SHALL each also count the blobs that failed on their path:
`sync.blobs.failed`, `poll.blobs.failed` and `backfill.blobs.failed`. The poller already exposes
`poll.blobs.failed`.

The outstanding gauge is the alerting signal: a non-zero parked count means data is missing until an operator
acts on it.

#### Scenario: The gauge reflects parked failures

- **WHEN** two blobs are parked and one day is pending, and a sweep runs
- **THEN** `sync.failures.outstanding{kind=blob,state=parked}` reads `2` and
  `sync.failures.outstanding{kind=day,state=pending}` reads `1`

#### Scenario: A reconciliation blob failure is counted

- **WHEN** a reconciliation run fails to ingest one blob of its day
- **THEN** `sync.blobs.failed` is incremented by one, and `sync.failures.recorded` is incremented with
  `kind=blob`, `path=reconcile` and the failure's reason
