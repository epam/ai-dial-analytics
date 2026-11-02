## Context

Three ingest paths share one per-blob primitive, `BlobIngestor.ingest`, and one per-day primitive,
`DataSyncService.runForDate`:
- the poller (`BlobPollingService`);
- the daily reconciliation run (`DataSyncService.runOnce`, for `today − dayOffset`);
- the operator backfill (`DataBackfillService`, which calls `runForDate` per day).

What each path does with failures today:
- **Poller.** It already isolates a failed blob: it counts the blob, skips it and advances the watermark.
  Nothing remembers the blob once it leaves the overlap window.
- **Day paths.** In `runForDate` the per-blob loop sits inside a single `try`, so the first blob that throws
  ends the day. The blobs already written are recorded in the ingest ledger, so a re-trigger skips them. The
  failing blob and everything after it are re-attempted, and a deterministic failure blocks the same spot on
  every re-trigger.
- **Failed days.** A failed reconciliation day is never retried automatically. A backfill's `failedDates` is
  lost on restart.

The ingest ledger (`_dial_internal_ingested_blob`, ClickHouse `V13`) records what *succeeded*. All ingest is
idempotent (ledger skip plus `ReplacingMergeTree` collapse), so any blob can be re-ingested safely. The only
thing missing is a durable record of what *failed*, and something that acts on it.

The shared Spring scheduler has a pool of 4 (`SCHEDULING_POOL_SIZE`) and runs the poller and the reconciliation
run. The backfill has its own single-thread worker.

## Goals / Non-Goals

**Goals:**
- No blob or day failure leaves only a log line. Every failure is either retried until it succeeds or parked
  where an operator can see it.
- One bad blob never stops its day on any path.
- Recovery needs no operator for transient faults, and one call for deterministic ones once their cause is
  fixed.
- One alertable signal: the outstanding gauges.

**Non-Goals:**
- Rows ClickHouse rejects inside a batch. They are still a cycle- or day-level write failure. A poison row
  still stalls the poller until the second change isolates it by splitting the batch.
- Recording malformed, oversized or keyless records, which belongs to the second change. Value-coercion
  metrics belong to the third.
- Persisting backfill run history, or a per-day coverage report. The failure ledger replaces both as the
  durable answer to "what is missing".
- Exactly-once retries across instances. During a rolling-update overlap two sweeps can attempt the same
  subject. This is idempotent and costs only duplicate work.

## Decisions

### 1. One append-only event table, state derived on read

`_dial_internal_failed_ingest` is a plain `MergeTree` holding the columns `kind`, `subject`, `day`,
`blob_last_modified`, `event`, `path`, `failure_class`, `reason`, `message` and `event_at`. It is ordered by
`(kind, subject, event_at)`, partitioned by `toYYYYMM(day)`, with `TTL day + 13 MONTH` and
`ttl_only_drop_parts`. For a blob, `day` is its last-modified date, so the expiry matches `V13`'s
`blob_last_modified + 13 MONTH`. If it expired on `event_at` instead, an old blob's failure would outlive the
ingest-ledger entry that resolves it and come back as outstanding. State is derived by a `GROUP BY (kind, subject)` query, as the `data-sync-failures`
spec defines.

Why: the failure path never has to read before it writes. Recording a failure is a single insert, which matters
because it runs at the moment something has just gone wrong. Attempt counts, first and last times, and backoff
all come from the events. Because the backoff settings are applied when the state is read, changing them
re-schedules existing entries.

Rejected:
- a `ReplacingMergeTree` row per subject, updated by read-modify-write. It needs a read on every failure, and
  two concurrent paths racing on the attempt count.
- PostgreSQL. It is transactional, but the state derivation joins against the ingest ledger, which lives in
  ClickHouse. Splitting the two stores loses that join and their shared failure domain.

Volume is small by construction, because failures are exceptional. Even a mass-corruption day yields one row
per blob per attempt, so the full-table `GROUP BY` stays cheap. No index or materialized state is added.

### 2. A blob resolves through the ingest ledger, a day through an explicit event

A blob is resolved when the ingest ledger holds an entry for it, written (`ingested_at`) after its latest
failure, with a last-modified time at or after the failed one. The write time matters: an entry from before the
failure, such as a blob ingested once and failed again by an `includeIngested` backfill, says nothing about the
failed attempt. The check is a second read, `IngestLedger.recordedAmong(keys)`, over just the open blob keys.
It is split into reads of at most 1,000 keys and about 64 KiB of parameter, because the HTTP interface refuses a
form field larger than `http_max_field_value_size`, which defaults to 128 KiB.

Each sweep writes `resolved` for the open blobs this check finds resolved. That compaction keeps the open set,
and with it the aggregation and the key list, bounded by what is actually missing. It uses the same
`argMax` read shape as the ingest ledger and `blob_key IN {keys:Array(String)}` on its `ORDER BY` key. The
client sends parameters as text, so the array is rendered as a quoted ClickHouse literal. Two reads, rather
than one join, keep each ledger's SQL independent and let the in-memory test doubles mirror them exactly.

Why: a blob that failed on the poller and succeeded on the next cycle, the common case, then resolves with no
write at all. The ingest ledger stays the single truth for "ingested".

A day has no such ledger, so a successful day run writes `resolved`, but only after a point read on
`(kind='day', subject=<date>)` finds an unresolved failure. That is one cheap read per day run.

The sweep also writes `resolved` after a blob retry succeeds. This covers blobs listed without a last-modified
time, which the ingest ledger never records.

### 3. Classification lives in one classifier, boundaries chosen for safety

A new `IngestFailureClassifier` maps a throwable to a class and a reason.

- `BlobIngestor` wraps any `RuntimeException` raised from the record consumer, meaning mapping, a deriver or a
  transform, in a new `RecordMappingException`. That is what makes mapping faults distinguishable from read
  faults. `RowBatchSink.FlushException` and `ClickHouseStorageFullException` are passed through unwrapped:
  they are write faults, not mapping faults, and must keep failing the cycle or day.
- `java.util.zip.ZipException` in the cause chain means `corrupt_gzip`, which is deterministic.
- `EOFException` is transient, because the maintainers chose not to park a blob over a read that may simply
  have been cut short.
- In the sweep, an empty listing for the key means `blob_missing`.
- Everything else at blob level is `read_error`, transient.

Day reasons come from where the failure arose in `ingestDate`. Disk-full is already detectable through
`ClickHouseStorageFullException.isIn`.

Rejected: uniform backoff with no classification. It would retry a corrupt gzip for days before anyone saw it
parked.

### 4. The retry sweep is a separate scheduled service on the shared scheduler

`IngestRetrySweep`, `@Scheduled(cron = "${sync.retry.cron}")`, runs on the shared pool. Spring does not overlap
runs of the same `@Scheduled` method. Each sweep:
1. Writes any failure events held in memory.
2. Reads the due pending subjects, limited to `max-per-sweep`.
3. Attempts each one:
   - a blob through `listWithMetadata(key)`, an ingest-ledger check, then `BlobIngestor` with a sweep-scoped
     `RunSink` whose callback is `ledger::record`;
   - a day through `runForDate(day, SKIP_RECORDED, NO_OP)`, which records and resolves on its own.
4. Refreshes the gauges from one outstanding-count query.

Blob attempts share one sink, flushed at the end of the sweep. A flush failure is a sweep-level fault. The
sweep then records no `resolved` for the unflushed blobs, which therefore stay pending.

Why the shared pool: a day retry can take a long time, but it occupies only one of the four threads, and the
poller keeps its own. `max-per-sweep` bounds how much one sweep can do.

Rejected:
- the backfill worker. Its single-in-flight guard would refuse operator backfills while a retry runs.
- a dedicated executor. It is not needed given the pool size.

`listWithMetadata(key)` lists using the full key as the prefix. That reuses the provider-agnostic call and
needs no new `BlobClient` method. It can also return keys that extend this one, so the sweep keeps only the
listing whose name equals the key.

### 5. The day loop catches per blob; the summary separates blob and day failure

In `ingestDate`, each `blobIngestor.ingest` call gets its own `try`:
- `FlushException` is rethrown, so the day fails as it does today.
- Any other exception is classified into an event, collected, and counted in `blobsFailed`, and the loop
  continues. The collected events are written in one append just before the final flush, so a mapping
  regression that fails every blob adds one part, not one per blob. The poller does the same per cycle, and
  records each blob version's failure once per overlap window.

`RunSummary` gains `blobsFailed`. Its `success` keeps the meaning "no day-level fault", and a new `complete()`
returns `success && blobsFailed == 0`.

Effects:
- `runOnce` reports `sync.runs{outcome}` and `lastSuccessTimestamp` from `complete()`.
- The backfill counts a day as failed when it is not `complete()`. `BackfillRun` gains `failedBlobs`, which
  advances through `IngestProgressListener`. The listener gets a new `onBlobFailed()` callback with a no-op
  default.
- The day-failure event is written only when `!success`. A blob-only failure must not trigger a retry of the
  whole day, because the blob has its own entry.

`runForDate` takes the ingest path (`reconcile`, `backfill` or `retry`) as a parameter, so its events carry
it.

A blob that throws mid-read has already buffered some rows, and `RunSink` still writes them. That is unchanged
and harmless: the blob is not recorded, so its retry re-writes those rows and `ReplacingMergeTree` collapses
them.

### 6. An unwritten day failure is held in memory

Recording a blob failure happens while ClickHouse is reachable, since blob-level faults are not write faults.
If that write fails anyway, the cycle or day fails, exactly as an ingest-ledger write failure does, and the
watermark or day retry covers it.

A day failure is different. Its usual cause is that ClickHouse is down, so writing the event fails for the same
reason. A bounded in-memory queue of 1000 events, drained first by each sweep, preserves these events across a
short outage. A restart during the outage loses them, and an ERROR log line says so.

Rejected:
- a local file spool. It brings disk and volume concerns into a stateless service.
- widening reconciliation to cover several days back. That adds a daily listing cost forever, to cover a
  rare restart-during-outage case.

### 7. A surface of two endpoints

`DataSyncFailuresController` serves `/v1/data-sync/failures`, with `GET` and `POST /retry`. Its DTOs are in
`web/dto/datasync/`, snake_case per the project convention. The camelCase backfill DTOs are an exception that
is not copied.

The retry body is a single shape of filters ANDed together, and at least one member is required. This avoids a
second shape for "by keys" versus "by filter": `subjects` is just one more filter.

A ClickHouse read failure maps to the existing `503 clickhouse_unavailable` handler.

### 8. New configuration and the migration

`SyncRetryProperties` holds `sync.retry.*`:

| Property          | Default          |
|-------------------|------------------|
| `enabled`         | `true`           |
| `cron`            | `0 */15 * * * *` |
| `initial-backoff` | `15m`            |
| `max-backoff`     | `24h`            |
| `max-attempts`    | `10`             |
| `max-per-sweep`   | `20`             |

With these defaults a transient subject is attempted for about three days before it parks, well inside any
blob retention period. The backoffs before the tenth attempt are 15m, 30m, 1h, 2h, 4h, 8h, 16h, 24h and 24h. Each property is validated at startup:
- durations must be positive;
- `max-backoff` must be at least `initial-backoff`;
- `max-attempts` and `max-per-sweep` must be at least 1.

The migration `V17__create_dial_internal_failed_ingest.sql` has a one-line header that links
`openspec/specs/data-sync-failures/spec.md`. It runs on the writer credential, which also needs `INSERT` and
`SELECT` on the new table, as for `V13`. Startup schema validation covers the table through a `COLUMNS` map,
the same way the ingest ledger does.

## Risks / Trade-offs

- **A misclassified deterministic failure parks a recoverable blob.** The classifier parks only the three
  unambiguous classes. The parked gauge plus one `POST /retry` recovers a misclassified blob.
- **A long day retry delays other scheduled jobs.** Only if the shared pool were exhausted, and the default
  pool is 4. If it shows in practice, `max-per-sweep` and `cron` can be tuned.
- **The poller records a failing blob once per version.** It does so even though it retries the blob every cycle
  in the overlap window. Counting each of those retries would use up most of `max-attempts` within a few
  minutes, before any backoff. The in-memory set that tracks this is pruned with the seen-set, so after a
  restart a blob can be recorded once more.
- **Duplicate attempts across instances during a rolling update.** These are idempotent. Accepted.
- **Day failures held in memory are lost on a restart during a ClickHouse outage.** This is bounded and
  logged. The operator still has the logs and `backfill`. Accepted as the rare residual case.
- **The poison-row stall remains.** It is the explicit scope of the second change. Until then, a rejected
  batch is still a cycle- or day-level fault. On day paths it is now at least recorded as a `write_failed`
  day failure.

## Migration Plan

1. Deploy. `V17` creates the empty table on startup. The writer grants must include `INSERT` and `SELECT` on
   it beforehand, or migration fails startup, as with `V13`.
2. No backfill of history is needed. Failures from before the deploy are not in the ledger. An operator
   recovers them as before, with a backfill over the affected range.
3. Rollback: the previous build ignores the table. Dropping it is optional and loses only failure history.

## Open Questions

None blocking. The admin MCP server that wraps the REST surface lives outside this repository. Adding tools for
the two endpoints there is a separate follow-up.
