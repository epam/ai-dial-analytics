## Context

`UsageLogWriter.insert(List<Map<String,Object>>)` builds one `StringBuilder`, appending every row's
JSON plus a newline, then `payload.toString()` and `.getBytes(UTF_8)` before handing a
`ByteArrayInputStream` to the ClickHouse client in `JSONEachRow` format. The run-scoped buffer
(`RowBatchSink`) flushes only on **row count** (`sync.batch-size`, default 10000), with no bound on the
serialized byte size of a batch.

A production run died with `OutOfMemoryError: Required array length 2147483638 + 4917112 is too large`
inside `StringBuilder.append` at `UsageLogWriter.java:61`. `2147483638` is ~`Integer.MAX_VALUE`: the
`StringBuilder`'s backing array hit the JVM's maximum array length. This is **not** a heap-size problem —
no single `String`/`StringBuilder` can hold more than ~2³¹ chars, so raising `-Xmx` cannot help. Because
the batch is bounded by row count only, a batch of large DIAL usage-log rows (which can embed full
request/response bodies) serializes past the ceiling. It is data-dependent, hence intermittent.

The insert also copies the payload three times at peak: the `StringBuilder` char buffer, the
`toString()` copy, and the `getBytes()` byte array.

## Goals / Non-Goals

**Goals:**
- Bound the in-memory serialized payload of any single batch so the array-length ceiling can never be
  reached, regardless of individual row size. (Load-bearing fix.)
- Remove the `String`-materialization step in `UsageLogWriter.insert` so serialization writes UTF-8
  bytes directly, eliminating the char-array ceiling and cutting peak insert memory ~3×.
- Preserve every existing guarantee: one `JSONEachRow` request per flush, writer-credential isolation,
  cross-blob batching, and the terminal single-use failure semantics of `RowBatchSink`.

**Non-Goals:**
- Streaming a lazily-serialized `InputStream` into the ClickHouse client (a larger refactor). A
  byte-bounded batch makes a fully materialized `byte[]` safe, so streaming is unnecessary for this fix.
- Per-row hard caps / dropping pathologically large single rows. Out of scope here; the byte bound
  flushes early but still inserts the oversized row.
- Changing `sync.batch-size` semantics or its default.

## Decisions

**1. Add a byte-size flush trigger to `RowBatchSink`, not to `UsageLogWriter`.**
The sink already owns the batch boundary (`add()` flushes at `buffer.size() >= batchSize`). Add a
running `long bufferedBytes` and flush when `buffer.size() >= batchSize || bufferedBytes >= maxBatchBytes`.
The byte size of a row is estimated cheaply at `add()` time. Rationale: keeping the boundary decision in
one place keeps `UsageLogWriter` a pure "insert this batch" component and avoids a second flush path.
*Alternative considered:* enforce the cap inside `insert` by chunking the list — rejected because it
splits the batch-boundary logic across two classes and complicates the metrics/`totalInserted` accounting.

**2. Estimate row byte size without serializing twice.**
Serializing each row once in `add()` purely to measure it, then again in `insert()`, doubles
serialization cost. Instead estimate via a cheap proxy at `add()` time. Two viable options:
(a) serialize once in the sink and pass bytes through to the writer; (b) use an approximation. Given the
sink currently passes `Map` rows (not bytes) to the writer and the writer is the JSON owner, the simplest
correct option is to have the writer expose/return the serialized size, or to compute a conservative
estimate in the sink. **Decision:** serialize each row to UTF-8 bytes once in `UsageLogWriter` and have
the sink track an estimate; to avoid double serialization, move per-row JSON serialization so it happens
once. Concretely, `UsageLogWriter.insert` serializes row-by-row into a `ByteArrayOutputStream`
(`jsonMapper.writeValue(out, row)` + `'\n'`), and `RowBatchSink` maintains a byte estimate using the
same mapper (or a lightweight length probe) so the threshold is enforced before the buffer grows
unbounded. The estimate need only be conservative enough to keep batches well under the array limit —
exactness is not required.

**3. Default `sync.max-batch-bytes` conservatively.**
Default to **256 MB** (`SYNC_MAX_BATCH_BYTES`, parsed as bytes). This is an order of magnitude under the
~2 GB array ceiling, leaving headroom for the (now single) byte copy and concurrent heap use, while still
producing large, part-efficient inserts. Operators can tune it. Rationale: the value must protect against
the ceiling with margin, not merely sit below it.

**4. Serialize directly to bytes in `UsageLogWriter`.**
Replace `StringBuilder` + `toString()` + `getBytes()` with a `ByteArrayOutputStream` written via
`jsonMapper.writeValue(out, row)` per row, appending a `'\n'` byte between rows. This removes the
char-array 2 GB ceiling entirely (the result is a `byte[]`, and the byte bound from decision #1 keeps it
small) and removes two of the three full-payload copies.

## Risks / Trade-offs

- **[Double serialization if the sink serializes to measure]** → Serialize each row exactly once. Either
  the sink serializes and forwards bytes, or the writer serializes and the sink uses a cheap estimate; do
  not serialize in both. Confirm the chosen path serializes once during implementation.
- **[Estimate drift — a too-loose estimate lets the byte bound be exceeded]** → Keep the default
  (256 MB) far enough under the ceiling that estimation error cannot bridge the gap; a conservative
  over-estimate only flushes slightly early, which is harmless.
- **[More, smaller ClickHouse parts when rows are large]** → Acceptable and bounded: the row-count cap
  still applies, and `ReplacingMergeTree` merges parts. Large-row runs are the exact case that was
  previously crashing, so smaller inserts are strictly better there.
- **[Config description placement]** → Per project convention, the new var's description goes ONLY in
  `docs/configuration.md`; `application.yml` stays a bare `key: ${ENV:default}` line and README adds no
  table.

## Migration Plan

Pure additive config + internal refactor; no schema, API, or wire-format change. Deploy normally. The
new `sync.max-batch-bytes` defaults to 256 MB, so existing deployments get the protection without any
config change. Rollback is reverting the deploy; no data migration is involved.
