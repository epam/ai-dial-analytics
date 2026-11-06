## Why

Two record-level losses on the ingest path still leave no durable trace an operator can find.

- **Skipped records.** A blob can hold malformed NDJSON lines, lines over `sync.max-line-bytes`, or records with no
  deduplication key. Its other records are ingested and the blob is recorded as done. The skips leave only a WARN
  line and an aggregate counter. Nothing durable says which blob lost records, how many, or where.
- **A row ClickHouse refuses.** One such row fails its whole batch, and a batch spans many blobs. On the poller this
  stalls the watermark on every cycle. On the day paths the day fails as `write_failed` and is retried into the
  same refusal.

This is the second of three changes that close silent data loss on the ingest path. The first (`data-sync-failures`)
made blob and day failures durable and retried. The third made value coercion observable. This change sends both
losses above to that same failure ledger, so the ledger becomes the one place that answers "what is missing". It adds
no new table and no new endpoint.

## What Changes

- **A new failure-ledger subject kind, `records`.** A blob that is ingested but skipped records gets a `failed`
  event of kind `records` for its key. The event carries:
  - the reason `skipped_records`, class `deterministic`, so the blob is parked at once;
  - a message with the count per cause (`malformed_line`, `oversized_line`, `missing_dedup_key`) and the first
    line numbers of each cause. The message holds no record content.

  The event is written before the blob is recorded in the ingest ledger, so a crash cannot leave the blob recorded
  without it. It lists, filters and requeues through the existing `/v1/data-sync/failures` endpoints and shows on the
  existing gauges.
- **Requeueing a `records` subject re-reads its blob.** The sweep re-ingests the blob even though the ingest ledger
  records it. The subject resolves if the blob now ingests with no skipped records, which is the case after the
  producer rewrote the blob. Otherwise it records a fresh failure and parks again. Ingest-ledger entries never
  resolve a `records` subject.
- **A batch refused for its data is split by blob.** When ClickHouse refuses a batch with a data-class error code (a
  parse, type, range or constraint error), the service splits the batch's blobs in halves, recursively, and writes the
  accepted halves. A blob whose rows are still refused on its own is not recorded in the ingest ledger. It gets a
  `failed` blob event with the new reason `rejected_by_server`, class `deterministic`, so it is parked. An operator
  requeues it after fixing the cause. Any other insert failure fails the flush as today.
  - Rows of a refused blob still buffered in other tables are dropped with it. A usage-log row is therefore not
    written for a blob whose payload rows were refused.
  - Rows already rewritten by the request-delta stage are re-sent as rewritten. The request paths of refused rows
    are not offered as parents to later flushes.
- **No new configuration.**

Nothing here is a breaking change:
- the `kind` member and parameter gain the value `records`;
- `reason` gains `skipped_records` and `rejected_by_server`;
- a refused batch now writes its good blobs where the flush used to fail.

Out of scope:
- storing the content of skipped or refused records. The blob key and line numbers point at the blob, which is the
  source of truth until its retention expires;
- isolating single records:
  - a record-level mapping fault keeps parking its blob as `mapping_error`;
  - a refusal costs its whole blob until it is fixed;
- an acknowledge action for parked entries. They stay listed until they expire after 13 months;
- alerting on new entries. The `sync.failures.recorded` counter already supports a rate alert.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `data-sync`:
  - "Malformed lines are skipped": a blob with skipped lines is recorded in the failure ledger.
  - "Records without a deduplication key are dropped": the same applies to dropped records.
  - "An insert refused for lack of disk space is classified distinctly": a data-class refusal is the one exception
    to "a failed insert is a run-level fault".
  - Added: "A batch ClickHouse refuses for its data is split by blob".
- `data-sync-failures`:
  - "Failed ingests are recorded in a persisted failure ledger": adds the `records` subject kind, written before the
    ingest-ledger entry.
  - "Failures are classified as transient or deterministic": adds `skipped_records` and `rejected_by_server`.
  - "Failure state is derived from the events and the ingest ledger": the ingest ledger never resolves a `records`
    subject.
  - "Outstanding failures are retried automatically": how a `records` subject is retried.
  - "Outstanding failures are listable": `kind` accepts `records`.

## Impact

- **Code**:
  - `sync/LogParser` reports the line numbers of skipped lines.
  - `service/BlobIngestor` reports the line numbers of dropped records in `IngestResult`.
  - The ingest paths write the `records` event in the ingest-ledger hand-over.
  - `RunSink` and `RowBatchSink` keep each buffered row's blob and split by blob.
  - `FlushStage` and `RequestDeltaStages`: the written callback learns which rows were not stored.
  - `clickhouse/UsageLogWriter` classifies data-class refusals.
  - `IngestFailureClassifier`, `ClickHouseIngestFailureLedger` and `IngestRetrySweep`: the new kind and reasons.
- **API**: no new endpoint. New enum values in `GET /v1/data-sync/failures` and `POST /v1/data-sync/failures/retry`.
- **Schema**: none. `_dial_internal_failed_ingest.kind` is a `LowCardinality(String)`.
- **Config**: none, so `docs/configuration.md` is unchanged.
