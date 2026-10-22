## Context

`UsageLogWriter.insert` (the sole `data-sync` write path) serializes a batch via
`serializeBatch`: it appends every row's bytes into a `ByteArrayOutputStream`, calls `toByteArray()`,
and wraps the result in a `ByteArrayInputStream` handed to `client.insert(table, in, JSONEachRow)`
(clickhouse `client-v2` 0.9.8). At flush the following are live simultaneously:

- the `RowBatchSink.buffer` list of row `Map`s (retained until `writer.insert` returns),
- the `ByteArrayOutputStream` internal array, which **doubles** on growth — at the last resize
  `Arrays.copyOf` transiently holds ~1.5–2× the payload (this is exactly the frame in the OOM stack),
- the `toByteArray()` copy (another full payload),
- each per-row `jsonMapper.writeValueAsBytes(row)` transient array.

So peak transient heap is ~3–4× the logical payload. The batch is bounded by `sync.max-batch-bytes`,
whose default is **256 MiB** — a value sized against the ClickHouse int-array ceiling
(`@Max(Integer.MAX_VALUE / 6)` on `SyncProperties.maxBatchBytes`), not the JVM heap. On a constrained
container heap a single 256 MiB materialized batch is already a large fraction of the heap; the 3–4×
serialization peak exceeds it and OOMs at the `ByteArrayOutputStream` `Arrays.copyOf` frame seen in the
stack. This is not heap under-sizing (the deployment already sets a container-relative
`MaxRAMPercentage`) — the bound is simply too large relative to the heap, and the fix belongs in the
serialization + default, not in JVM flags.

Compounding it, `RowBatchSink.estimateBytes` counts **unescaped characters** (`text.length() + 2`).
DIAL usage rows embed full request/response JSON bodies; when Jackson re-serializes those, embedded
quotes/backslashes are escaped and multi-byte UTF-8 expands, so the real payload is 2–4× the estimate.
The buffer believes it is at the bound while the actual serialized size is much larger.

The existing `data-sync` spec already requires that the writer "MUST NOT first materialize the entire
batch as one in-memory `String`" and that the byte bound keep peak insert memory bounded — the current
code satisfies the letter (it uses `byte[]`, not `String`) but not the intent.

## Goals / Non-Goals

**Goals:**
- Bound peak insert memory to ~one row's serialized form (plus the already-retained row buffer),
  independent of batch size, by streaming the batch to ClickHouse row by row.
- Make the accumulated-byte estimate reflect the escaped UTF-8 serialized size so the
  `sync.max-batch-bytes` bound governs the real payload.
- Ship a heap-safe default `sync.max-batch-bytes` while keeping the env override and validation range.
- Keep the fix inside the `data-sync` write path; no endpoint/contract change.

**Non-Goals:**
- Changing JVM heap sizing / `JAVA_TOOL_OPTIONS` — the deployment already sets a container-relative
  `MaxRAMPercentage`, so heap tuning is not the fix and is out of scope.
- Refactoring `CatalogRowWriter` / `TableDataService` (a separate table-management write path with
  small catalog rows); it shares the materialize pattern but is not the OOM source and is out of scope.
- Changing batching semantics (row-count/byte thresholds, per-run accumulation, blob-failure
  isolation) beyond the serialization mechanism.

## Decisions

### 1. Stream rows into the insert via a lazy pull-based `InputStream`

Replace `serializeBatch(...) → ByteArrayInputStream` with an `InputStream` implementation that
serializes rows on demand: it holds one row's JSON bytes (plus the trailing `\n`) at a time, serving
them through `read(...)`, and advances to the next row only when the current row's bytes are drained.
`client.insert(table, in, JSONEachRow)` pulls from it, so no whole-batch `byte[]` ever exists. Peak
extra memory is one row's serialized bytes.

- **Why not `PipedInputStream`/`PipedOutputStream` + a writer thread?** Also avoids the whole-batch
  buffer, but introduces a second thread, cross-thread exception plumbing, and lifecycle complexity for
  no benefit — the client reads synchronously on the calling thread, so a single-threaded pull stream is
  simpler and equally bounded.
- **Why not the client's POJO/typed insert?** Rows are dynamic `Map<String, Object>` produced by the
  mapping engine, not a fixed POJO; `JSONEachRow` over a byte stream stays aligned with the current
  format and the schema-validation path.
- The row buffer itself remains in memory (it always did) — this change removes the *duplicate*
  serialized copies, which is where the 3–4× blowup lives.

### 2. Improve `estimateBytes` to approximate escaped UTF-8 size

Keep it a cheap estimate (no real serialization), but make it conservative in the right direction: for
`CharSequence`, account for JSON escaping and multi-byte expansion (e.g. use the UTF-8 byte length and
add headroom for escaped characters) rather than the raw char count. Result: the estimate is an
over- (not under-) approximation of the flushed bytes, so the byte bound triggers a flush at or before
the real payload reaches the configured size.

- **Why not serialize once and reuse the exact bytes?** That would re-introduce a retained serialized
  copy per row across the whole batch — the opposite of Decision 1. The estimate stays an estimate;
  only its accuracy direction is fixed.

### 3. Lower the default `sync.max-batch-bytes` to a heap-relative value

Change the shipped default from 256 MiB to **32 MiB** (`33554432`). The env var (`SYNC_MAX_BATCH_BYTES`)
and the validation range (1 MiB … `Integer.MAX_VALUE / 6`) are unchanged, so operators with large heaps
can raise it. With streaming (Decision 1) the byte bound no longer gates a multi-copy blowup, but a
smaller default still limits the retained row buffer and keeps a single ClickHouse part reasonably
sized. `docs/configuration.md` is updated (descriptions single-sourced there per project convention).

## Risks / Trade-offs

- **Smaller default batch → more, smaller ClickHouse inserts/parts.** → Streaming removes the memory
  reason for a huge batch; 32 MiB still yields large batches relative to typical row sizes, and
  `sync.batch-size` (10000 rows) usually gates first. Operators can raise the byte bound if part count
  matters.
- **Lazy `InputStream` must surface serialization errors correctly.** A row that fails to serialize
  mid-stream must abort the insert with a clear exception rather than send a truncated payload. →
  Propagate the Jackson exception out of `read(...)` (wrapped in `IOException`) so `client.insert`
  fails and the flush surfaces `FlushException`, preserving current failure semantics.
- **Estimate over-approximation could flush slightly earlier than strictly necessary.** → Acceptable and
  intended; erring toward earlier flush is the safe direction and the row-count threshold usually
  dominates anyway.
- **Regression risk in a hot ingest path.** → Covered by unit tests asserting the streamed bytes equal
  the previous `JSONEachRow` output for representative batches, plus the existing ingest integration
  tests.

## Migration Plan

1. Implement the streaming serializer + estimate fix + default change; `./gradlew clean build` green.
2. Deploy — no schema/DDL migration, no data migration, no config action required (env override still
   honored; unset falls to the new 32 MiB default).
3. No heap/`JAVA_TOOL_OPTIONS` change needed — the deployment already sets a container-relative
   `MaxRAMPercentage`; the code + default change is the remedy.
4. **Rollback:** revert the commit. Note that raising `SYNC_MAX_BATCH_BYTES` back up would *reintroduce*
   the OOM (the oversized bound is the trigger), so rollback is a code revert, not a config toggle.

## Open Questions

- Confirm 32 MiB as the default vs 64 MiB — pick based on typical deployment heap and desired part size; 32 MiB
  is the conservative starting point and is trivially overridable.
