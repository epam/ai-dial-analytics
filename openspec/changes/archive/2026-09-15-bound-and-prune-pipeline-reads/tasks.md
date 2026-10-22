# Tasks

Three independent changes, in shipping order. **2 is the one that makes restarting the runner safe** and
can ship alone; 1 is the fix that makes the pipeline work again; 3 stops valid work being discarded.

## 1. A resource budget on every pipeline-internal read

- [x] 1.1 Add `PipelineBudgetProperties` (`pipeline.budget.*`, `ResourceBudget`), and
      `PipelineReadBudgetExceededException` with its `@ExceptionHandler` mapping to HTTP 422
      `pipeline_read_budget_exceeded`.
- [x] 1.2 Add `PipelineReadBudget` with a single `run(executor, select, what, budget)` that attaches the
      settings *and* translates the refusal — the pairing lives in one place so a site cannot attach one
      without the other.
- [x] 1.3 Route all six unbudgeted `executeScan` sites through it: the enrichment scan page, both
      group-fetch phases, the materialize window probe, the Class-1 preview sample, and the aggregate
      refresh window probe. Leave the materialize `INSERT … SELECT` alone.
- [x] 1.4 Correct the javadocs in `QueryBudget`, `PreviewBudget` and `StructuredQueryExecutor` that state
      these paths are bounded by their own limits and need no ceiling.
- [x] 1.5 `application.yml` defaults (`30s` / `2GB`) and a `docs/configuration.md` section under
      **Pipelines**. `max-memory` is sized from an observed healthy baseline, not from the ceiling times the
      runner's thread pool: a scan tick reading `request_tags` can take a couple of GiB unaligned, and a
      heavy healthy tick can still need over 1 GiB with the alignment of section 2 — so `1GB` would refuse
      a large share of a current, healthy pipeline's ticks. `timeout` stays at `30s` because raising it is
      not a remedy: healthy pipeline reads (member selection, window probes, payload scans) stay well under
      30 s, so a read exceeding it signals a failure rather than normal load, and a value above
      the runner's own read timeout would make the wall-clock half of the ceiling inert.

## 2. Partition-aligned grain joins

- [x] 2.1 `EnrichmentJoin`: carry `sourcePartition`/`enrichmentPartition` beside the key pair, with
      `partitionAligned()`; keep the join condition the single grain-key equality.
- [x] 2.2 `EnrichmentJoins.keyed(...)`: emit the partition restriction in the derived table's `WHERE`,
      through one shared `selects(...)` helper used for both restrictions.
- [x] 2.3 `CatalogQueryService.alignedPartitionColumn(...)`: both definitions must declare a partition
      column and it must be the same name. Granularity deliberately not compared.
- [x] 2.4 `V40__payload_partition_alignment.sql`: declare `request_time` / `DAY` on
      `dial_usage_log_payload`, with a guard that fails the migration if the alignment is not in place.
- [x] 2.5 `GroupFetchService.projectMembers`: build the member lookup before the joins and hand it plus the
      safety lag over as source predicates, guarded by `EnrichmentJoins.referencesSourceOnly`. Group key
      stays out.
- [x] 2.6 `TableMapper.partitionBy`: report it for either kind. Refuse it explicitly on an enrichment's
      `POST /schema` (`CatalogSchemaWriter`), where it was previously ignored.
- [x] 2.7 Tests: four render tests in `EnrichmentJoinRenderTest` (aligned bound emitted; inside the derived
      table not the join condition; unaligned unchanged; no source predicates stays unkeyed) and three in
      `CatalogQueryServiceTest` (matching names align; different names do not; unpartitioned source aligns
      nothing), plus the existing co-stored-column test now asserting `partitionAligned()` is false.

## 3. ClickHouse resource failures reported as unavailable

- [x] 3.1 Add `ClickHouseFailures.isUnavailable(...)`: the transport half unchanged, plus the server-side
      resource codes (159, 173, 202, 203, 209, 210, 236, 241, 243, 252, 394) matched on the numeric code.
- [x] 3.2 Replace the four byte-identical `isConnectivityFailure` copies (`ClickHouseClientWrapper`,
      `UsageLogWriter`, `CatalogRowWriter`, `ClickHouseWriterClient`) and rewire `ClickHouseDdlExecutor`
      and `ClickHouseInsertSelectExecutor`.
- [x] 3.3 Correct the javadocs that describe the old line, including `ClickHouseWriterClient`'s claim that
      the classifier lives there.
- [x] 3.4 `ClickHouseFailuresTest`: resource codes unavailable, statement defects not, wrapped chains,
      driver timeout types by name, self-referential chain, null.

## 4. Verification

- [x] 4.1 `./gradlew clean build` green (Docker up for the integration tests).
- [ ] 4.2 Against a running deployment: confirm a `session-insights-live` group fetch returns instead of
      being refused, and that the phase-2 statement in `system.query_log` reads one part rather than nearly
      all of them. Cannot be verified from the build alone — the fix is in the SQL ADAS emits, and only a
      running deployment exercises it against real part layout.

## Notes

- **Not done here, by design:** the runner's DLQ circuit-breaker auto-reset and operator-visible state, and
  its dropping of queued group work across a restart. Those are Enrichment Runner changes; see the
  proposal's non-goals.
- **Open, and the residual risk of the wall-clock half:** `TIMEOUT_EXCEEDED` is translated into the same
  `422` as the memory ceiling, which the runner is meant to read as deterministic and dead-letter. A memory
  refusal is deterministic; a timeout is load-dependent, so the first sign of ClickHouse pressure now turns
  slow work into permanently dropped work — the failure mode this change answers. Two ways
  out, neither taken here: classify 159 as retryable (`503`) and keep the `422` for the memory ceiling, or
  spend one budget across a whole request via `ClickHouseBudget.remainingTime` so a group fetch's two
  phases share the ceiling instead of each getting a fresh one.
- **Deploy order:** ADAS-only, and it can ship before or after anything else in flight. None of the four
  data-plane request or response shapes move, so there is no runner contract change to coordinate; the one
  new response is a refusal (`422 pipeline_read_budget_exceeded`) on paths that could already refuse.
