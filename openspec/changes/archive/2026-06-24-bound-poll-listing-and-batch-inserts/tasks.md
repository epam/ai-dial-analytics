## 1. Configuration

- [x] 1.1 Add `finalizationLag` (Duration) and a listing-mode field (e.g. `listingMode`: `day` |
  `band`, default `band`) to `configuration/PollProperties`, with record-level javadoc.
- [x] 1.2 Wire the new `poll.*` keys into `application.yml` as bare `key: ${ENV:default}` lines
  (default mode `band`, `finalization-lag` 15m); no explanatory comments in the yaml.
- [x] 1.3 Document the new `poll.*` properties in `docs/configuration.md` (the only place
  descriptions live) — band vs day mode and the finalization-lag SLA tradeoff.

## 2. Cross-blob insert batching

- [x] 2.1 Introduce a shared row-sink abstraction (caller-owned buffer that flushes to
  `UsageLogWriter` at `sync.batchSize` and on explicit flush), surfacing flush failures distinctly.
- [x] 2.2 Refactor `service/BlobIngestor.ingest(...)` to append parsed/mapped rows into the supplied
  sink instead of owning a per-blob batch and flushing at EOF; keep the dedup-key drop + counts.
- [x] 2.3 Update `service/BlobPollingService.runOnce()` to open one sink per cycle, ingest all blobs
  into it, flush at cycle end, and keep per-blob failure isolation (skip + count, retain buffered
  rows); treat a flush failure as a cycle-level failure that holds the watermark (seen-set committed
  only after a successful final flush).
- [x] 2.4 Update `service/DataSyncService` to use the same shared sink across the run's blobs.

## 3. Bounded poll listing

- [x] 3.1 Add band-prefix derivation (`PollPrefixResolver`): from `cutoff = watermark − overlap` and
  `now`, compute the start-epoch lower bound `cutoff − finalizationLag` and the `date=<date><leadingDigits>`
  prefixes covering `[cutoff − finalizationLag, now]` — one per band the range touches (both days when a
  band straddles midnight). Band width is a fixed 1000s (~16.7m, a power of ten), decoupled from the lag.
- [x] 3.2 In `band` mode, `BlobPollingService.activePrefixes` lists those narrowed prefixes via
  `BlobClient.listWithMetadata`; in `day` mode it keeps listing the full day prefix(es) unchanged.
- [x] 3.3 Keep the in-memory `lastModified` watermark filter as the exact selection within the listed
  set (narrowing only reduces candidates, never changes selection semantics).

## 4. Tests

- [x] 4.1 Unit test band-prefix derivation (`PollPrefixResolverTest`): range within one band → one
  prefix; range straddling a decimal boundary → two; wider range → one prefix per band touched;
  midnight straddle → both days; blank path prefix; empty when hi < lo.
- [x] 4.2 Poller test (filesystem provider): a blob with an old start epoch but fresh `lastModified`
  within `finalizationLag` is still listed and ingested under `band` mode.
- [x] 4.3 Poller test: a blob whose start epoch precedes the cutoff by more than `finalizationLag` is
  not listed by `band` mode (and a full-day listing — the reconciliation path — still finds it).
- [x] 4.4 Default-mode test: `day` mode lists the full day prefix regardless of start epoch.
- [x] 4.5 Batching test: a cycle of five 1-row blobs at batch size 2 produces 3 inserts
  (`ceil(5/2)`), not one per blob.
- [x] 4.6 Failure-isolation tests: a failing blob after an earlier good one is skipped/counted while
  the buffered row is retained; a flush failure holds the watermark.

## 5. Done gate

- [x] 5.1 Compile + `checkstyleMain`/`checkstyleTest` clean; all data-sync unit tests green
  (`BlobPollingServiceTest`, `DataSyncServiceTest`, `PollPrefixResolverTest`). NOTE: the full
  `./gradlew clean build` also runs the query **integration** tests, which require Docker
  (Testcontainers ClickHouse) and fail in this sandbox with "Could not find a valid Docker
  environment" — pre-existing/environmental, unrelated to this change. Re-run on a Docker-enabled host.
- [x] 5.2 `openspec validate bound-poll-listing-and-batch-inserts` passes and the delta matches the
  shipped behavior.
