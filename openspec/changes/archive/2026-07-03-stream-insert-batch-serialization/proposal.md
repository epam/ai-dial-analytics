## Why

The `data-sync` write path can OOM (`java.lang.OutOfMemoryError: Java heap space`) when
flushing an insert batch. `UsageLogWriter.serializeBatch` materializes the whole batch as a single
in-memory `byte[]` (a `ByteArrayOutputStream` that doubles as it grows, then `toByteArray()`, then a
wrapping `ByteArrayInputStream`) while the row buffer is still retained — so peak transient memory is
3–4× the logical payload. The default `sync.max-batch-bytes` is 256 MiB — a value sized against the
ClickHouse int-array ceiling, not the JVM heap — so on a constrained container heap a single
materialized copy is already a large fraction of the heap and the 3–4× serialization peak exceeds it.
The `estimateBytes` bound compounds this: it
counts unescaped characters while DIAL rows embed full request/response JSON bodies that expand 2–4×
on re-serialization, so the real payload is far larger than the bound assumes.

## What Changes

- Change the writer to **stream** the batch to ClickHouse row-by-row rather than building the entire
  batch as one in-memory `byte[]`, so peak insert memory is bounded by roughly a single row plus the
  retained buffer — not the whole serialized batch plus its `ByteArrayOutputStream` doubling and
  `toByteArray()` copy.
- Improve the per-row byte-size estimate (`RowBatchSink.estimateBytes`) so it approximates the
  **escaped UTF-8** serialized size (accounting for JSON quote/backslash escaping and multi-byte
  characters), so the `sync.max-batch-bytes` bound reflects the real payload rather than undercounting
  it.
- Lower the default `sync.max-batch-bytes` from 256 MiB to a heap-relative value (32–64 MiB) so the
  shipped default is safe under a typical container heap; keep the existing validation range and env
  override.
- Update `docs/configuration.md` for the new default (descriptions stay single-sourced there).

No API/contract change to any endpoint; behavior change is confined to the internal ingest write path.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `data-sync`: The "Batched inserts via a dedicated writer credential" requirement is strengthened —
  the writer MUST NOT materialize the entire batch as one in-memory buffer (not just not as one
  `String`) and MUST stream rows to ClickHouse; the accumulated-byte bound MUST reflect the escaped
  serialized size; and the shipped default byte bound is heap-relative.

## Impact

- **Code**: `clickhouse/UsageLogWriter` (streaming serialization into the insert),
  `service/RowBatchSink` (`estimateBytes`), `resources/application.yml`
  (`sync.max-batch-bytes` default).
- **Config**: `SYNC_MAX_BATCH_BYTES` default changes (env override unchanged); `docs/configuration.md`
  updated.
- **Tests**: `serializeBatch`/streaming serialization unit tests, `RowBatchSink` byte-estimate tests.
- **Ops**: None required — the OOM is caused by the oversized batch bound + whole-batch
  materialization, not by heap under-sizing, so the fix is the code + default change.
