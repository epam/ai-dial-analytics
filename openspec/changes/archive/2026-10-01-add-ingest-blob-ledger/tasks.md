## 1. Storage and table-name reservation

- [x] 1.1 Add ClickHouse migration `V13__create_dial_internal_ingested_blob.sql` with the DDL in design D1 (short comment; rationale lives in the spec); confirm V13 is still the next free ClickHouse slot
- [x] 1.2 Reserve the `_dial_internal_` table-name prefix: in `TableCatalogService.create`, before the identifier-grammar check, reject such a name with `422` and a message saying the prefix is reserved for service-internal tables
- [x] 1.3 Extend startup schema validation (`SchemaValidator`, gated by `SYNC_VALIDATE_SCHEMA`) to describe `_dial_internal_ingested_blob` and check its three columns, so a deployment that skipped `V13` does not start

## 2. Ledger component

- [x] 2.1 Add the `IngestLedger` interface (`recorded(prefix)` returning key → recorded `blob_last_modified`, `record(listings)`) plus one helper applying the "same key and same last-modified to the millisecond; `Instant.EPOCH` never matches" rule for all paths
- [x] 2.2 Add the ClickHouse implementation on the writer client: the parameterised `startsWith` + `argMax` read of design D2; `record` through `UsageLogWriter.insert`, filtering out `Instant.EPOCH` listings and rendering `blob_last_modified` as `yyyy-MM-dd HH:mm:ss.SSS` UTC; failures propagate (no fail-open, no switch)
- [x] 2.3 Document `INSERT`/`SELECT`/`DESCRIBE` on `_dial_internal_ingested_blob` in the writer-credential table (data-sync ingest row) and the narrowed-grant notes of `docs/clickhouse-server.md`

## 3. Per-blob write tracking

- [x] 3.1 `BlobIngestor`: tell the sink which blob each row belongs to and when a blob has been read to the end
- [x] 3.2 `RunSink`: track, per run, each pending blob and the tables still holding its unflushed rows; a blob completing with no pending table (including one with no rows) is written at once; after every successful table flush hand the newly written blobs, as one batch, to an `onBlobsWritten` callback; `flush()` always performs the hand-over even when every buffer is empty; a failed flush hands over nothing; a callback failure surfaces as a cycle/day-level fault (`FlushException`-equivalent), never as a failure of the blob being read; no extra flushes (design D4, D5)

## 4. Ingest paths and API

- [x] 4.1 `BlobPollingService`: per listed prefix, read `recorded(prefix)`; skip recorded candidates (add to seen-set, count `poll.blobs.skipped{reason=ledger}`, do not count them in `poll.blobs.discovered`); record through `onBlobsWritten`; a ledger failure fails the cycle with the watermark held
- [x] 4.2 `DataSyncService`: add `IngestLedger.Mode` (`SKIP_RECORDED`, `INGEST_ALL`) and a `runForDate(date, mode, listener)` overload; switch `ingestDate` from `list` to `listWithMetadata` (keep passing every name to `checkBandShape`); in `SKIP_RECORDED` read `recorded(dayPrefix)`, skip and count the recorded blobs in `RunSummary.blobsSkipped` (emitted by `runOnce` as `sync.blobs.skipped{reason=ledger}` and by the backfill as `backfill.blobs.skipped{reason=ledger}`), keep `filesProcessed` for ingested blobs only; in both modes record through `onBlobsWritten`; a ledger failure fails the day
- [x] 4.3 `runOnce` passes `SKIP_RECORDED`; the existing `runForDate` overloads default to `SKIP_RECORDED`
- [x] 4.4 Backfill API: add optional `Boolean includeIngested` to `BackfillRequest` (absent/`null` = `false`); carry it through `DataBackfillService.start` to `runForDate` as the `IngestLedger.Mode`; log it at run start; update the `BackfillRequest` class and field `@Schema` (default skips already-ingested blobs; `true` re-ingests the range to repair lost rows or apply a changed mapping, and re-fires on-ingest enrichments and aggregate refreshes) and the controller's `400` `@ApiResponse` description

## 5. Tests

- [x] 5.1 `RunSinkTest`: a blob is reported written only after every table holding its rows flushed (interleaved per-table flushes, enrichment-first order); a blob with no rows is reported at once, including as the last blob; `flush()` with empty buffers still hands over pending complete blobs; a failed flush reports nothing; a callback failure surfaces as a flush-level fault
- [x] 5.2 Ledger integration test against ClickHouse (Testcontainers): record/recorded round-trip exact to the millisecond; a re-record with a newer time wins by `argMax` before any merge; a version in another month's partition; prefix isolation (a sibling prefix is not returned); keys of 250+ characters and a prefix holding 5000 keys; EPOCH listings are not recorded; schema validation fails startup when the table is missing
- [x] 5.3 `DataSyncServiceTest`: a second scheduled run over the same day inserts nothing and counts skips; a blob not in the ledger is ingested and recorded; a run failing after some flushes records exactly the blobs fully written; a rewritten blob (new last-modified) is ingested again; `INGEST_ALL` re-ingests recorded blobs and refreshes entries; a mapping change alone does not re-ingest recorded blobs; a ledger read failure fails the day
- [x] 5.4 Backfill service/controller tests: a default backfill skips recorded blobs and a fully recorded day reports zero files processed; a re-trigger after a stopped run ingests only the rest; `includeIngested: true` and `"true"` re-ingest everything; a request without the field behaves as `false`; `"maybe"` and an object are `400 bad_request`
- [x] 5.5 `BlobPollingServiceTest`: restart (new service instance, same ledger) does not replay the lookback window; a failed final flush records only fully written blobs; a ledger read or write failure fails the cycle and holds the watermark; skipped-by-ledger blobs are counted and not counted as discovered, seen-set skips are not counted
- [x] 5.6 End-to-end check against ClickHouse (Testcontainers as existing tests do): poller then reconciliation of the same day leaves the target tables holding each row once, before any merge
- [x] 5.7 Assert `_dial_internal_ingested_blob` is not listed or queryable through the query surfaces (catalog-gated)
- [x] 5.8 Table API tests: `POST /v1/tables` with `_dial_internal_ingested_blob` (and another `_dial_internal_` name) is `422` with the reserved-prefix message, writes no catalog row and issues no ClickHouse statement (the existing internal table survives); GET/PATCH/schema/DELETE on it answer as for an unknown table

## 6. Specs, docs and wrap-up

- [x] 6.1 Update the Purpose sections of `openspec/specs/data-sync/spec.md` ("does not skip already-ingested files") and `openspec/specs/data-sync-backfill/spec.md` (engine-level dedup only) to mention the ledger — Purpose is not reachable from a delta, so edit the main specs directly
- [x] 6.2 Fix stale docs: `docs/clickhouse-server.md` ("every ingested row is written twice"), `docs/configuration.md` data-sync intro ("there is no file-level skip"), `docs/release-0.2-upgrade.md` backfill passages (re-ingesting a recorded day now needs `includeIngested: true`)
- [x] 6.3 Note in `docs/column-mapping.md` that a mapping change applies to new data and that history is re-ingested by a backfill with `includeIngested: true`
- [x] 6.4 Confirm the delta specs match shipped behaviour and metric names; run `openspec validate add-ingest-blob-ledger`
- [x] 6.5 Run `./gradlew clean build` and make it pass
