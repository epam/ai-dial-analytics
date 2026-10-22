## Context

The near-real-time poller (`BlobPollingService`) lists the active day prefix
(`logs/{yyyy}/{MM}/{dd}/`) to completion every cycle via the jclouds `BlobClient`, then linearly
filters listings whose `lastModified` is at/after `watermark − overlap`. Two costs measured from a
production run (cron every 60s):

- **Listing dominates and grows.** Each cycle spent **~4.5s** just listing the partial day
  (~00:00–10:43), independent of how few new blobs existed (cycles found 3 and 16 new blobs but
  still paid the full list). The prefix only grows through the day toward ~10–15k tiny blobs.
- **One INSERT per blob.** Files are ~1 record each (`BlobIngestor` flushes its leftover batch at
  EOF per blob), so a 316-blob startup cycle issued ~316 tiny inserts → ~316 ClickHouse parts.

The blob key encodes time: `date=<YYYY-MM-DD><startEpoch><endEpoch>-<uuid>.log.gz`, where the two
10-digit values are epoch-seconds. Decoding a real key: `…17822970301782297630…` → start
`1782297030` = 2026-06-24 10:30:30Z, end `1782297630` = 10:40:30Z. The cycle's watermark was
exactly `10:40:30Z` — i.e. **the watermark tracks the END epoch (≈ `lastModified`/finalization),
while the key sorts lexicographically by the START epoch.** Observed start→end divergence is up to
~600s, and many blobs finalize in batches at the same end-instant despite spread-out starts.

Constraints (from `openspec/project.md` and existing `data-sync` spec): provider-agnostic via
jclouds (the only server-side listing lever is `prefix` — Azure's continuation marker is opaque, no
`start-after`/offset; jclouds exposes no offset either), Spring Boot 4 / Java 21, env-driven config
with descriptions only in `docs/configuration.md`, dedup stays on the table's `ReplacingMergeTree`,
and `./gradlew clean build` gates done.

## Goals / Non-Goals

**Goals:**
- Bound the poller's per-cycle listing cost so it does not scale with the full day's blob count.
- Preserve correctness: never silently miss a blob the unbounded poller would have ingested, within
  a declared finalization-lag bound.
- Cut ClickHouse parts per cycle from ~one-per-blob to ~`total_rows / batchSize`.
- Keep a safe fallback: a `day` mode that reproduces today's whole-day listing.

**Non-Goals:**
- Event-driven ingestion (Event Grid / S3 notifications) — explicitly rejected by the existing
  poller design (no provider-specific notification service / external infra).
- Server-side `start-after`/range listing — unavailable on Azure and unexposed by jclouds.
- Changing the blob key layout at the producer (would make narrowing tight and safe; tracked as a
  future cross-service ask, out of scope here).
- Parallelizing blob downloads, persisting the watermark, or changing dedup.

## Decisions

### D1 — Bounded listing via start-epoch prefix bands (default)
Narrow each listed prefix from `logs/{date}/` to `logs/{date}/date={date}<leadingEpochDigits>` so
the store returns only blobs whose START epoch falls in a recent band, in lexicographic (=
chronological-by-start) order. Implemented purely with the jclouds `prefix` lever, so it is
provider-agnostic. A listing-mode knob selects `band` (default) or the `day` fallback (the original
whole-day listing, no key-format assumption). Band is the default because the DIAL key format is the
expected one and the bounded listing is the point of the change; `day` exists as a safe escape hatch.
**Alternatives:** server-side `start-after` at the watermark key (perfect, but Azure/jclouds can't);
finer producer-side path segment like `…/HH/` (clean, but needs a producer change).

### D2 — Finalization-lag margin makes narrowing safe
The poller selects on `lastModified` (≈ END epoch), but the prefix keys on the START epoch. For any
blob, `start ≤ end` and `end − start ≤ L` (max finalization lag). Therefore **listing all blobs with
`start ≥ cutoff − L` is guaranteed to include every blob with `end ≥ cutoff`** (cutoff =
`watermark − overlap`). We add config `poll.finalization-lag` (`L`, default 15m, ≥ the platform's
max request/stream duration) and size the band's lower bound at `cutoff − L`. The in-memory
watermark filter is unchanged and still provides exact END-time selection within the listed set.

### D3 — Band → prefix mechanics
The poller lists the set of `date=<date><digits>` prefixes covering the numeric start-epoch range
`[cutoff − L, now]`, one prefix per band the range touches (and one per day when a band straddles UTC
midnight). Band width is a **fixed 1000s (~16.7 min)** — a power of ten, which the decimal-prefix
scheme requires — and is deliberately **decoupled from L**. A key insight: a prefix returns the
*whole* band, so a band wide enough to span the lookback (e.g. deriving width from `overlap + L`) would
make each cycle transfer hours of listings even though only ~`overlap` minutes are new. Keeping the
band narrow and letting `PollPrefixResolver` emit as many adjacent bands as the range needs (2–3 at the
15m default) trades a couple more — individually cheap — list calls for a far smaller volume listed.
The previous-day grace window composes unchanged (it applies to the `day` fallback only).

### D4 — Cross-blob batching within an ingestion run
Refactor `BlobIngestor` to append into a **shared row sink** owned by the caller (poll cycle /
scheduled run) rather than owning and flushing its own per-blob batch. The sink flushes to
`UsageLogWriter` when it reaches `sync.batchSize` and once at end of run. A cycle of many 1-row
blobs thus produces few large inserts. `sync.batchSize` is reused unchanged.
**Alternatives:** keep per-blob batching but raise batchSize (no effect — the problem is the partial
flush per blob, not the size); coalesce parts only in ClickHouse (relies on merge, doesn't remove
the part-creation storm).

### D5 — Failure isolation under a shared sink
A single blob's parse/decompress failure stays isolated: it is counted and skipped, the cycle
continues, and rows already appended from other blobs (and any partial rows from the failing blob)
remain in the sink — duplicates collapse in the engine, and the skipped blob stays eligible for
retry within the overlap window. A **flush** failure (ClickHouse unreachable) is treated as a
cycle-level failure like a listing failure: it aborts the cycle and leaves the watermark unchanged
so the next cycle re-covers the window. The seen-set is committed only after a successful final
flush, so a flush failure leaves the cycle's blobs re-ingestable.

## Risks / Trade-offs

- **A call finalizing later than `L` is missed by the narrowed poll** → recovered by the daily
  reconciliation sweep (which still lists the full day). Documented SLA: NRT latency for such
  outliers degrades to the reconciliation cadence. Mitigation: default `L` = 15m ≥ platform max
  request duration; `L` is operator-tunable.
- **Decimal bands don't align to wall-clock minutes** → handled by listing the set of covering
  prefixes (one per band touched); band width fixed at 1000s keeps each slice tight.
- **Shared-sink flush failure spans multiple blobs' rows** → acceptable: a flush failure means
  ClickHouse is unavailable, which is a cycle-level fault; the watermark holds and the whole window
  is retried next cycle (dedup absorbs any rows that did land).
- **Key-format assumption** (leading `date=<date><10-digit-epoch>`) → because `band` is now the
  default, the default path depends on this layout; if the producer changes it, the poller could
  under-discover. Mitigation: the `day` fallback (one env var) has no such dependency, the
  reconciliation path is format-agnostic and recovers anything the poller misses, and band tests
  assert the format. Revisit the default if a non-DIAL store is targeted.
- **Band on by default changes behavior on deploy** → the poller immediately relies on
  `finalization-lag`; a too-small value silently under-discovers until reconciliation. Mitigation:
  default `finalization-lag` is 15m (≥ the observed ~10m max), and the daily sweep bounds any miss to
  "eventually consistent within a day."

## Migration Plan

1. Deploy: `band` listing is active by default with `poll.finalization-lag` defaulted to 15m. Set
   `POLL_FINALIZATION_LAG` to at least the platform's longest request/stream duration if it exceeds
   the default; descriptions live in `docs/configuration.md`.
2. **Fallback / rollback:** set `POLL_LISTING_MODE=day` (env-only, no artifact redeploy) to restore
   the original unbounded whole-day listing. Cross-blob batching is internal and has no toggle; it is
   covered by the reconciliation path and dedup, so no data migration is involved.

## Open Questions

- `poll.finalization-lag` default is set to **15m** (covers the observed ~10m plus headroom); raise
  `POLL_FINALIZATION_LAG` if the platform's max request/stream duration exceeds it.
- Band-width granularity vs. lag: **resolved** — band width is fixed at 1000s (~16.7 min), decoupled
  from `finalization-lag` (see D3). The 15m default therefore lists ~33–50 min of blobs per cycle (a
  few 16.7m bands), not the ~2.8–5.6 h a lag-derived width produced. If a future need arises, the
  width could be made configurable, but it MUST remain a power of ten.
