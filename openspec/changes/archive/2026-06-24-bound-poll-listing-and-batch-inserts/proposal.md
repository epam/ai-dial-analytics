## Why

The near-real-time poller re-lists the **entire growing day prefix** (`logs/{yyyy}/{MM}/{dd}/`)
to completion on every cycle, then filters in memory for the few blobs newer than its watermark.
Production logs measure this at **~4.5s of pure listing per cycle today**, run every ~60s, and it
grows monotonically as the day fills (toward ~10–15k tiny one-record blobs/day) — work that is
~entirely wasted, since a cycle typically ingests a handful of new blobs. Separately, the ingest
path flushes a partial INSERT **per blob** (~1 row each), so a cycle of N blobs produces ~N tiny
ClickHouse parts, creating avoidable merge pressure on the `ReplacingMergeTree` target.

Both are addressable without new infrastructure, by exploiting the existing key naming
(`date=<YYYY-MM-DD><startEpoch><endEpoch>-<uuid>.log.gz`) and by batching across blobs.

## What Changes

- **Bounded poll listing (default).** The poller narrows the listed prefix to a recent time band
  derived from the blob key's leading epoch, instead of listing the whole day. Because the key
  sorts lexicographically by the **start** epoch while the watermark tracks the **end**
  (finalization) epoch, and the two diverge by the request/stream duration, the band is sized to
  reach back by a configurable **finalization lag** so no fresh blob is missed. A full-day (`day`)
  fallback retains the original listing for stores that don't follow the epoch key format.
- **New config** `poll.finalization-lag` (max start→finalization divergence to tolerate) and a
  listing-mode knob (`band` default, `day` fallback). The selection of blobs is identical in both
  modes — band only changes how candidates are listed, not which are ingested.
- **Documented SLA tradeoff.** Blobs whose finalization lags beyond `poll.finalization-lag` are not
  discovered by the narrowed poll and are instead recovered by the daily reconciliation sweep.
- **Cross-blob insert batching.** Within a poll cycle, mapped rows accumulate across blobs and flush
  by `sync.batchSize` (and once at cycle end), replacing the per-blob partial flush — cutting the
  number of INSERTs/parts roughly to `total_rows / batchSize`. Per-blob failure isolation and the
  existing metrics are preserved.
- Provider-agnostic: narrowing uses only the jclouds `prefix` lever (Azure exposes no
  `start-after`/offset), so it works across stores.

## Capabilities

### New Capabilities
<!-- none; this extends existing data-sync behavior -->

### Modified Capabilities
- `data-sync`: the near-real-time polling requirement gains an optional bounded-listing window with
  a finalization-lag guarantee; the batched-insert requirement gains cross-blob batching within a
  poll cycle.

## Impact

- **Code:** `service/BlobPollingService` (band-prefix derivation, finalization-lag sizing,
  per-cycle row buffering), `service/BlobIngestor` (accept a shared batch/sink rather than
  self-flushing per blob), `blob/BlobClient` (unchanged listing API — narrower prefixes only),
  `configuration/PollProperties` (new `finalization-lag` + listing-mode fields).
- **Config:** new `poll.*` env vars; descriptions added to `docs/configuration.md` only.
- **Behavior:** `band` listing is on by default (one-line `day` fallback): fewer/larger ClickHouse
  INSERTs and a bounded per-cycle listing cost. A blob finalizing later than `finalization-lag` is
  recovered by the daily reconciliation run. No change to dedup, the writer credential, or read path.
- **Spec/tests:** `data-sync` delta spec; new poller tests for band coverage across the
  start/end-epoch divergence boundary and for cross-blob batch flushing with failure isolation.
