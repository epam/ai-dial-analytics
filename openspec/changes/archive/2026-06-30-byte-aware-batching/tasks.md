## 1. Configuration

- [x] 1.1 Add a `maxBatchBytes` (long, bytes) field to `SyncProperties` and document it in the record's Javadoc `@param` list.
- [x] 1.2 Add `max-batch-bytes: ${SYNC_MAX_BATCH_BYTES:268435456}` to the `sync:` block in `application.yml` (bare `key: ${ENV:default}` line, no comment).
- [x] 1.3 Add a row to the `sync.*` table in `docs/configuration.md` describing `sync.max-batch-bytes` / `SYNC_MAX_BATCH_BYTES` (default `268435456` / 256 MiB): "Max accumulated serialized bytes per ClickHouse INSERT batch; a batch flushes when it reaches this size or `sync.batch-size` rows, whichever comes first."

## 2. Byte-aware serialization in the writer

- [x] 2.1 Rewrite `UsageLogWriter.insert` to serialize row-by-row directly to a `ByteArrayOutputStream` (extracted into a package-private `serializeBatch`, writing `jsonMapper.writeValueAsBytes(row)` plus a `'\n'` byte separator), replacing the `StringBuilder` + `toString()` + `getBytes()` chain; feed the resulting `byte[]` to the existing `ByteArrayInputStream`/`JSONEachRow` insert. Keep the empty-batch no-op and all existing exception handling.

## 3. Byte-aware flush in the sink

- [x] 3.1 Add a `maxBatchBytes` constructor arg and a running `long bufferedBytes` field to `RowBatchSink`.
- [x] 3.2 In `add(Map)`, estimate the row's serialized byte size once, add it to `bufferedBytes`, and flush when `buffer.size() >= batchSize || bufferedBytes >= maxBatchBytes`. Ensure the per-row JSON is serialized only once across the sink + writer (the sink only *estimates* via a cheap `estimateBytes`; the row is serialized for real once, by the writer at flush).
- [x] 3.3 Reset `bufferedBytes` to 0 wherever the buffer is cleared (in `flush()`'s `finally`), so the estimate tracks the live buffer.
- [x] 3.4 Update `BlobPollingService.runOnce` (and any scheduled-sync caller) to construct `RowBatchSink` with `syncProperties.maxBatchBytes()` alongside `batchSize()`.
- [x] 3.5 Update the `RowBatchSink` class Javadoc to describe the dual (count-or-bytes) flush trigger.

## 4. Tests

- [x] 4.1 Add a `RowBatchSink` unit test: feeding rows whose cumulative estimated bytes cross `maxBatchBytes` before the row count triggers a flush at the byte boundary (assert via the writer mock's invocation count / batch sizes).
- [x] 4.2 Add a `RowBatchSink` unit test confirming the existing row-count flush and end-of-run `flush()` still behave when rows are small (byte bound never reached).
- [x] 4.3 Add a `UsageLogWriter` unit test asserting the serialized `JSONEachRow` payload is byte-identical to the previous `StringBuilder` output for a representative multi-row batch (one `\n`-delimited line per row, no trailing/ordering change).

## 5. Verify

- [x] 5.1 Run `./gradlew checkstyleMain checkstyleTest` and the targeted new unit tests; confirm they pass.
- [x] 5.2 Confirm the `data-sync` delta spec matches the shipped behavior (byte-or-count flush), then the change is ready to archive once `./gradlew clean build` passes.
