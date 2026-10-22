## 1. Stream the insert batch row-by-row

- [x] 1.1 In `UsageLogWriter`, add a lazy pull-based `InputStream` that serializes buffered rows to
  `JSONEachRow` bytes one row at a time (row JSON + trailing `\n`), advancing to the next row only when
  the current row's bytes are drained; no whole-batch `byte[]` is ever held.
- [x] 1.2 Replace `insert(...)`'s `serializeBatch → ByteArrayInputStream` with the streaming
  `InputStream` passed to `client.insert(table, in, JSONEachRow)`; remove `serializeBatch`/`toByteArray`
  materialization.
- [x] 1.3 Propagate a mid-stream Jackson serialization failure out of `read(...)` as an `IOException`
  so `client.insert` aborts and the flush surfaces `RowBatchSink.FlushException` (never sends a
  truncated payload); preserve the existing `InterruptedException`/`IllegalStateException` handling.

## 2. Fix the byte-size estimate

- [x] 2.1 In `RowBatchSink.estimateBytes`, change the `CharSequence` branch to approximate escaped
  UTF-8 size (UTF-8 byte length plus headroom for JSON escaping) so the estimate over-approximates the
  flushed bytes rather than undercounting; keep it a cheap estimate (no real serialization).
- [x] 2.2 Update the `RowBatchSink`/`estimateBytes` Javadoc to state the estimate is a conservative
  over-approximation of the escaped serialized size.

## 3. Heap-relative default byte bound

- [x] 3.1 Change the default in `application.yml`: `sync.max-batch-bytes: ${SYNC_MAX_BATCH_BYTES:33554432}`
  (32 MiB); keep the `@Min`/`@Max` validation range in `SyncProperties`.
- [x] 3.2 Update the `sync.max-batch-bytes` row in `docs/configuration.md` for the new default (256 MiB
  → 32 MiB); leave the env var and validation-range wording otherwise intact.

## 4. Tests

- [x] 4.1 Unit-test that the streamed bytes for a representative multi-row batch exactly equal the
  previous `JSONEachRow` output (correctness of the streaming serializer), including multi-byte and
  quote-heavy string values.
- [x] 4.2 Unit-test that a row whose serialization throws aborts the stream with an exception rather
  than yielding a partial payload.
- [x] 4.3 Unit-test `estimateBytes` for a quote-heavy / multi-byte string row: assert the estimate is
  greater than or equal to the actual serialized byte length (over-approximation), and that the byte
  bound triggers a flush at/before the real payload reaches `sync.max-batch-bytes`.

## 5. Spec sync & verification

- [x] 5.1 Run `openspec validate stream-insert-batch-serialization` and resolve any issues.
- [x] 5.2 Run `./gradlew clean build` (checkstyle + tests) and confirm green.
- [x] 5.3 Confirm the `data-sync` delta matches shipped behavior; ready to archive via `/opsx:archive`.
