## 1. Failure ledger: the `records` kind and the new reasons

- [x] 1.1 Add `RECORDS` to `IngestFailureKind`, and `SKIPPED_RECORDS` and `REJECTED_BY_SERVER` (both deterministic)
  to the reason set. Accept `records` in the `kind` parameter of `GET /v1/data-sync/failures` and in the body of
  `POST /v1/data-sync/failures/retry`, and include it in the DTOs.
- [x] 1.2 `ClickHouseIngestFailureLedger`: leave `records` subjects out of the ingest-ledger resolution, both in the
  open-state derivation and in the sweep's "recovered on its own" compaction. Extend the integration tests:
  - a `records` subject written in the same hand-over as its ledger entry stays parked;
  - a `blob` subject and a `records` subject on the same key stay independent.

## 2. Skipped records

- [x] 2.1 `LogParser`: hand `(lineNumber, node)` to the consumer, and report each skipped line's number and cause
  (`malformed_line` or `oversized_line`). Unit-test the numbering with blank lines and CRLF.
- [x] 2.2 `BlobIngestor`: collect a per-cause summary (count, plus at most the first 10 line numbers) in
  `IngestResult`. Count a keyless record once, not once per target.
- [x] 2.3 Build the `records` failure message from the summary: causes with counts and line numbers, cut to 512
  characters, no content. Unit-test the format and the cut.
- [x] 2.4 On the poller, reconciliation, backfill and sweep paths, pass a hand-over listener
  (`IngestFailureService.recordHandOver`) so the `records` failures of the handed-over blobs are written in one
  failure-ledger insert before `ledger.record(blobs)`. `RunSink` keeps each blob's summary from `finishBlob` until its
  hand-over. Test that:
  - a skipped line yields one `records` failure written before the ledger entry;
  - a failed write fails the cycle with the blob unrecorded;
  - a blob whose read failed writes no `records` failure.

## 3. Refused batches split by blob

- [x] 3.1 `UsageLogWriter`: raise `ClickHouseDataRejectedException(code, name)` for the data-class codes. Pin the
  list in a unit test checked against the supported server version's error codes, and fix any code that does not
  match.
- [x] 3.2 `RowBatchSink`: keep the `BlobListing` of each buffered row. On a data rejection, split by blob, halving
  recursively, through the writer without re-running the stage. Return the refused blobs with their error. Fail
  with `FlushException` on any non-data error.
- [x] 3.3 `FlushStage`: `prepare` returns a `Written` callback that receives the indices of the rows not stored.
  `RequestDeltaStages` and `RequestDeltaEncoder.Encoding.written` leave those rows out of the `PathCache`.
- [x] 3.4 `RunSink`, for the refused blobs:
  - drop their buffered rows from every table's sink;
  - mark them so that later rows are dropped and they are never handed over;
  - report them to the path.

  The paths record each blob as a `blob` failure with the reason `rejected_by_server`, with the table and the code
  and name in the message, in the hand-over that follows the refusing flush (`IngestFailureService.recordHandOver`).
- [x] 3.5 Unit tests:
  - one refused blob among many;
  - two refused blobs in different halves;
  - every blob refused;
  - a non-data error is not split;
  - stage rows are not re-prepared, and refused paths are not cached;
  - a refused payload row withholds its blob's buffered usage-log rows.
- [x] 3.6 Refusal against real behaviour:
  - Poll cycle (`BlobPollingServiceTest`): one blob whose rows the writer refuses with a data-class error is parked
    as `rejected_by_server`, the other blob of the batch is written and recorded, and the watermark advances.
  - ClickHouse (`IngestFailureLedgerIntegrationTest`): an unparseable `DateTime64` is refused with a code in the
    data-class set, and every pinned code matches `errorCodeToName` on the supported server.

## 4. Retry sweep

- [x] 4.1 `IngestRetrySweep`: retry a `records` subject as follows:
  - list the blob, recording `blob_missing` when it is gone;
  - re-ingest it without the ingest-ledger shortcut, and record it in the ingest ledger;
  - write `resolved` when nothing was skipped, or a fresh `skipped_records` failure with the path `retry` otherwise.
- [x] 4.2 Sweep tests (`IngestRetrySweepTest`, filesystem blob store with in-memory ledgers):
  - a requeued `records` subject whose blob was rewritten without bad lines is re-read despite its ingest-ledger
    entry, and resolves;
  - an unchanged blob parks again under a fresh `skipped_records` failure from `retry`;
  - a requeued `rejected_by_server` blob is ingested after the cause is removed.

## 5. Specs and docs

- [x] 5.1 Confirm that `openspec/specs/data-sync` and `data-sync-failures` match the shipped behaviour. No
  `docs/configuration.md` change is needed: this change adds no property.
- [x] 5.2 Run a public-repo hygiene sweep over the change with `git grep -nIP` and the multiline pass from
  `CLAUDE.md`.

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes.
