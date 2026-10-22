## Why

A data-sync run crashed with `java.lang.OutOfMemoryError: Required array length 2147483638 + 4917112 is too large` in `UsageLogWriter.insert`. The insert path concatenates an entire batch into a single `StringBuilder`, and the batch boundary is **row count only** (`sync.batch-size`, default 10000) with no bound on serialized bytes. DIAL usage-log rows vary wildly in size (they can embed full request/response bodies), so a batch of large rows serializes past the ~2 GB JVM array ceiling — a hard limit no heap size can lift, because no single `String`/`StringBuilder` can exceed `Integer.MAX_VALUE` chars. The crash is data-dependent, which is why it surfaced for one operator's window and not others.

## What Changes

- Add a **byte-size cap** to the run-scoped row buffer so a batch flushes when it reaches **either** the configured row count **or** an accumulated serialized-byte threshold, whichever comes first. This bounds the payload regardless of individual row size and is the load-bearing fix.
- Add a new configuration property (`sync.max-batch-bytes`, env `SYNC_MAX_BATCH_BYTES`) for the byte threshold, with a safe default well under the array limit.
- Change the insert serialization to write rows directly to a UTF-8 byte buffer rather than building one giant `String` and then copying it twice (`StringBuilder` → `toString()` → `getBytes()`). This removes the char-array 2 GB ceiling and cuts peak memory ~3×.
- The single-batch insert contract (one `JSONEachRow` request per flush, writer credential isolation, failure semantics) is unchanged.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `data-sync`: the "Batched inserts via a dedicated writer credential" requirement gains a byte-size bound on the batch boundary — a batch flushes when it reaches the configured row count **or** a configured serialized-byte size, so a run of large rows cannot accumulate an unbounded in-memory payload.

## Impact

- **Code**: `service/RowBatchSink` (track and threshold accumulated bytes), `clickhouse/UsageLogWriter#insert` (stream rows to a byte buffer instead of one `String`), `configuration/SyncProperties` (new `maxBatchBytes` field).
- **Config**: new `sync.max-batch-bytes` / `SYNC_MAX_BATCH_BYTES` property in `application.yml`; description added to `docs/configuration.md`.
- **Tests**: unit coverage for byte-triggered flush and for a large-row batch that the old count-only path would have over-buffered.
- **Behavior**: more, smaller inserts when rows are large; no change for typical small-row workloads. No API or wire-format change.
