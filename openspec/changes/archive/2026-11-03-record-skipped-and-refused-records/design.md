## Context

The first change of this series (`data-sync-failures`) built the failure ledger. `_dial_internal_failed_ingest` is an
append-only event log of `blob` and `day` subjects. Their state (`pending`, `parked`, `resolved`) is derived on read.
A retry sweep backs off and parks, and `GET /v1/data-sync/failures` and `POST .../retry` sit on top. Two losses at
record level still bypass it:

- **Skipped records.** `LogParser` skips malformed and oversized lines, and `BlobIngestor` drops keyless rows per
  target. Both are counted in `IngestResult` and logged at WARN. The blob is then recorded in the ingest ledger as
  done, so nothing durable shows that it lost records.
- **A refused row.** `RowBatchSink.flush` turns any insert failure into `FlushException`, which fails the poll cycle
  (the watermark stalls on every cycle) or the day (`write_failed`, retried into the same refusal). A batch spans
  many blobs, so one row costs all of them.

Rows in the sink carry no provenance today. `RunSink` tracks which *tables* a blob still has buffered rows in, not
which rows are whose. The request-delta `FlushStage` rewrites the batch's bodies in place before the insert, and its
callback caches every row's path once the insert succeeds.

## Goals / Non-Goals

**Goals:**
- Every blob that lost records leaves a durable, listable entry, written before the blob is recorded as ingested.
- One refused row costs its own blob, which is parked and visible, not a batch, a cycle or a day.
- No new table, endpoint, configuration or privilege. Everything rides the failure ledger and its surface.

**Non-Goals:**
- Storing record content. The entry names the blob and line numbers, and the blob is the content.
- Record-level isolation. A mapping fault keeps parking its blob, and a refusal costs its whole blob.
- An acknowledge action or alerting rules for parked entries.

## Decisions

### 1. A third subject kind, `records`, instead of a `blob` reason

Skipped records are recorded as `kind = 'records'`, `subject = <blob key>`, `reason = 'skipped_records'`,
`failure_class = 'deterministic'`.

Why not `kind = 'blob'` with that reason: the `blob` derivation resolves a subject through the ingest ledger, and
the same ingest that skipped the records also records the blob there. Every such entry would resolve the moment it
was written. A blob can also have a real `blob` failure and skipped records at once, for example when a backfill
that includes already-ingested blobs fails a blob that was earlier ingested with skips. Separate subjects keep the
two independent.

`kind` is a `LowCardinality(String)` column, so this needs no migration. The listing, filters, requeue endpoint
and the `sync.failures.outstanding{kind,state}` gauge pick the new value up through the `kind` enum.

The message is built from per-cause counts plus the first line numbers of each cause, filled until the 512-character
cap. For example: `malformed_line: 2 (lines 14, 87); oversized_line: 1 (line 5); missing_dedup_key: 3 (lines 203,
204, 210)`. Line numbers are cheap to collect:
- `LogParser` already counts lines, and now hands `(lineNumber, node)` to the consumer and reports skipped lines;
- `IngestResult` gains a small per-cause summary that keeps the counts and at most the first 10 line numbers.

A keyless record that two targets drop counts once, so the count matches records rather than rows.

Rejected:
- one event per skipped record. The volume grows with the producer's defect rate, and the line numbers in the
  blob-level message already point at every record that matters.
- a separate table holding each skipped record's content. It needs its own endpoints, caps and a gate for the
  sensitive bodies it would copy, all to show what reading the blob at the given lines already shows.

### 2. Written in the ingest-ledger hand-over

The ingest paths used to pass `RunSink` an `onBlobsWritten` callback that recorded blobs in the ingest ledger. They
now pass a hand-over listener instead. Each hand-over carries the written blobs, the skip summaries of those that
skipped anything, and the blobs refused since the last hand-over. `IngestFailureService.recordHandOver` then:
- turns the summaries into `records` failure events, and the refused blobs into `rejected_by_server` events;
- writes those events in one failure-ledger insert;
- only then calls `ledger.record(blobs)`.

`RunSink.handOver` already wraps a callback failure as `FlushException`, so a failed write fails the cycle or day
with the blobs unrecorded. That is the same semantics as an ingest-ledger write failure, and needs no new code path.

`BlobIngestor` passes the blob's summary to `RunSink.finishBlob`, and the sink keeps it until the hand-over that writes
the blob.
Hand-overs follow flushes, so this adds at most one small insert per flush, and only when some blob in it skipped
records.

Why not the existing end-of-cycle insert used for blob failures: blobs are recorded in the ingest ledger mid-run,
at each flush. Evidence written only at the end would leave a crash window where a blob is recorded and its skips
are not.

### 3. Retrying a `records` subject

`IngestRetrySweep` gains a branch for the new kind:
1. list the blob;
2. skip the ingest-ledger shortcut;
3. ingest the blob;
4. record it in the ingest ledger;
5. write `resolved` if the result has no skips, or a fresh `skipped_records` failure (path `retry`) if it has.

A subject only becomes pending through a requeue, so this runs only on an operator's request. The usual reason is
a rewritten blob. A requeue of a blob whose bad lines are still there costs one re-ingest and parks again with the
current counts.

### 4. Classifying a data-class refusal

`UsageLogWriter.insert` raises a new `ClickHouseDataRejectedException(code, name)` when the `ServerException` code
is in the closed set listed in the `data-sync` delta. Disk exhaustion keeps `ClickHouseStorageFullException`, and
anything else keeps the generic failure. The set SHALL be checked against the error codes of the supported
ClickHouse server version while implementing, and pinned by a unit test.

### 5. The split works by blob, by halving

`RowBatchSink` keeps a parallel list of the `BlobListing` each buffered row came from. That is one reference per
row. On a `ClickHouseDataRejectedException`, `flush` groups the batch's row indices by blob and splits:

```
split(blobs):
  try writer.insert(table, rowsOf(blobs))           -> stored
  catch DataRejected e:
    if blobs.size == 1: refused += (blob, e)
    else: split(first half); split(second half)
  catch other: FlushException
```

Sub-inserts call the writer directly and never re-run the stage. A sub-batch is a list of row references, not a
copy of the rows.

**Cost.** Isolating `k` refused blobs among `b` blobs takes at most about `2k·log2(b)` sub-inserts. Refused inserts
create no parts. Accepted sub-inserts create at most about `k·log2(b)` parts, against a `parts_to_throw_insert`
threshold in the thousands per partition. The split was considered one blob at a time, and rejected: a poller batch
holds hundreds of small blobs, so that would be hundreds of parts for one bad row.

**No abort cap.** A change in the table that refuses every row isolates every blob of the batch, in about `2b`
refused inserts, and parks each one as `rejected_by_server`. That is the right outcome. Every affected blob is
visible, and `POST /failures/retry {"reason":"rejected_by_server"}` recovers them all after the fix. The
alternative, failing the flush, was the stall this change removes.

Rejected:
- splitting down to single rows. This needs line-level provenance, record-level withholding across tables, and
  a separate store for the isolated rows. Costing the whole blob keeps the unit of
  recovery the unit the ledger already tracks.
- `input_format_allow_errors_num` / `_ratio`. The server drops the bad rows and reports them only in a server-local
  file, which moves the silent loss to the server.

### 6. A refused blob leaves the run

`RunSink` learns the refused blobs from the split and does three things:
- it removes their remaining buffered rows from **every** table's sink, so a source row whose payload rows were
  refused is not written in this run;
- it marks them refused, so `finishBlob` and any later `add` for a blob still being read drop its rows and never
  hand it over;
- it hands them over with the next hand-over, which follows the flush that refused them.

The path records each one's `blob` failure with the reason `rejected_by_server` in that hand-over, in the same insert
as the `records` failures and ahead of the ingest-ledger write. The poller, the day path and the sweep then share one
recording step (`IngestFailureService.recordHandOver`) and no per-path collection.

Rows of the blob that earlier flushes already wrote stay written. This matches a blob that throws mid-read (see
`openspec/specs/data-sync/spec.md`, the requirement that a failure to ingest a single blob does not abort the run,
introduced by `2026-11-01-durable-ingest-failure-ledger`). They collapse on the dedup key only if the blob is ingested
again; a refused blob is parked, so until an operator requeues it they stay as written.

### 7. The flush stage learns which rows were not stored

`FlushStage.prepare` returns a `Written` callback that takes the indices of rows not stored, instead of a
`Runnable`. `RequestDeltaEncoder.Encoding.written(excluded)` leaves those rows' paths out of the `PathCache`.

Re-encoding is impossible because the bodies are already cut, and keeping a copy of the batch before the stage
would double its memory for a rare case. A stored child whose in-batch parent was refused is rebuildable once the
parent's blob is retried. That is the same state as a parent lost to TTL.

## Risks / Trade-offs

- **A producer that writes one bad line per blob floods the parked list** → The entries stay one per blob, and they
  are deterministic, so they are never retried. The list is filterable by `kind` and `reason`, and entries expire
  after 13 months. Alerting on the parked total is not a goal of this change.
- **A refusal costs the whole blob until it is fixed** → The blob is parked and visible. Its good rows are delayed,
  not lost.
- **A misclassified code splits a non-data failure** → Each sub-insert re-sends rows to a server that is failing.
  Transport and availability codes are not in the set. A sub-insert failing outside the set fails the flush as
  before.
- **A schema drift parks many blobs** → It parks every blob of the affected batches. One retry by reason recovers
  them after the fix.
- **Source rows written before their blob's payload rows were refused** → This is the existing part-way-failure
  window (Decision 6). The retry repairs it.

## Migration Plan

1. Deploy. There is no migration and no new grant: `_dial_internal_failed_ingest` already exists, and the writer
   already has `INSERT` and `SELECT` on it.
2. Blobs ingested with skips before the deploy have no `records` entries. A backfill that includes already-ingested
   blobs over a range records them.
3. Rollback is safe. The previous build's failure-ledger reader skips rows whose `kind` it does not know, so the
   `records` subjects become invisible to it. Their `rejected_by_server` blobs read as ordinary parked blob
   failures, with an unfamiliar reason string. Nothing needs deleting.

## Open Questions

None.
