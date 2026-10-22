## 1. Aligned layout (F1)

- [x] 1.1 Eligibility check (grain = source identity, source partitioned and partition-local) in the define-schema path
- [x] 1.2 Add the ADAS-owned alignment column and set `partition_by` on the definition; adopt a declared same-name, same-type, non-nullable column (keeping its metadata); 422 `validation_error` on a mismatched one
- [x] 1.3 `TableDdlRenderer.renderCreate` emits `PARTITION BY` for an aligned enrichment (still refused from the caller)
- [x] 1.4 Inherit the source's physical TTL clause and `ttl_only_drop_parts` from `system.tables` unless `ttl` is declared
- [x] 1.5 Tests: eligible enrichment created aligned; group-grain and API-source ones unchanged; adoption keeps metadata; mismatch 422; inherited and overridden TTL

## 2. Tuple join and writers

- [x] 2.1 Aligned join condition `grain AND partition` in `CatalogQueryService` / `EnrichmentJoins`, payload included
- [x] 2.2 `MaterializeService.writeWindow` writes `source.<partition column>` into an aligned target, superseding (not rendering) any output bound to the alignment column; preview shows the written value
- [x] 2.3 `POST /v1/tables/{name}/rows` requires the alignment column on an aligned enrichment (422)
- [x] 2.4 Scan returns `__partition_v` (source row's alignment value) for a row-trigger pipeline with an aligned target; the write-back accepts it and writes it into the alignment column, 422 `validation_error` when missing; unaligned targets unchanged
- [x] 2.6 Raise `RunnerContract.VERSION` 3 → 4, and write the paired runner brief (carry the value in `ChunkRow`, put it in the write row in `WorkerService.enrichRow`, AdasContract 3 → 4, old queued/DLQ payloads need no migration because no pre-upgrade target is aligned)
- [x] 2.5 Tests: scan carries `__partition_v` and an echoed write-back lands and joins; a write-back without it is 422; materialized `request_time` equals the source's; no fan-out for one `event_id` on two days; wrong value only orphans its row; `usage-request-baggage-live`-shaped pipeline writes identical rows before and after alignment; a non-source binding is superseded; re-run RC2 late-materialize tests

## 3. Partition pre-selection (F2)

- [x] 3.1 Qualification in `ReadShaping`: top-level lookup conjunct over one aligned enrichment, non-default literals, null-rejecting
- [x] 3.2 Emit `PS` on the source only (the tuple join carries it into the enrichment), bounded by the statement's own partition-column bound, `final=0`; key only unaligned joins with it
- [x] 3.3 Wire into the DSL path (`StructuredQueryBuilder`)
- [x] 3.4 Wire into group-select (`GroupFetchService.selectMembers`): bare-column group key, filter's source-only conjuncts as key predicates, bare column for a `string` key only; a blank key is a 400
- [x] 3.5 Wire into the aggregate recompute (`AggregateCompiler.compileRefresh`, `AggregateSqlCompiler` / `LeafRestriction`)
- [x] 3.6 Equivalence tests: rewritten == unrewritten, incl. late-materialized rows, `IN (subquery)`, multi-day session; `= ''` and `NOT IN` do not qualify; EXPLAIN shows both tables pruned to `PS`

## 4. Lookup index (F3)

- [x] 4.1 On pipeline register/patch, `ALTER TABLE … ADD INDEX … bloom_filter(0.001) GRANULARITY 1` on a grouped-by aligned column if absent
- [x] 4.2 Tests: index added only for a group key on an aligned table; no `MATERIALIZE INDEX` mutation; not re-added; survives pipeline delete

## 5. Automatic relayout (F4)

- [x] 5.1 Postgres Flyway migration (V47) for relayout state: table, step, resume key, catch-up marks, hold flag, error, timestamps
- [x] 5.2 Job scheduling: after `ApplicationReadyEvent` + 10-minute grace constant, own executor, advisory lock per table, resume from persisted step
- [x] 5.3 Candidate selection: eligible ∧ unaligned ∧ only Class-1 (or no) writer; WARN and skip otherwise
- [x] 5.4 Engine check (`Atomic`/`Replicated` → `EXCHANGE TABLES`; else `layout: unsupported`)
- [x] 5.5 Copy steps: copy with `_updated_at` kept and `request_time` from the source, DDL carrying TTL + needed indexes; in-flight copy; catch-up copy
- [x] 5.6 Writer hold: 503 `table_relayout_in_progress` on materialize, pipeline rows and table rows, read from Postgres per write; handler in `DefaultExceptionHandler`
- [x] 5.7 Swap, epoch-partition repair, catalog update, hold release; pre-drop re-copy, second repair, count check, drop; WARN with counts when repairs find rows
- [x] 5.8 Failure handling: any failure before the swap keeps the old table, releases the hold, records the error, never fails startup
- [x] 5.9 `layout` (`migrating | aligned | unsupported`) on `GET /v1/tables/{name}`
- [x] 5.10 Tests (Atomic): counts and `_updated_at` preserved, resume after a kill at each step, writes during copy and hold, one instance only, starts after readiness, LLM-written table skipped
- [x] 5.11 Replicated engine, verified locally rather than in test code (2026-10-16): ClickHouse 26.3.21 with embedded Keeper, `lab` database `ENGINE = Replicated`, ADAS `bootRun` against it — migrations, aligned create, both relayouts (hidden column and adopted `request_time`) via `EXCHANGE TABLES`, data checksum unchanged, `idx_session` in the copy's DDL, TTL applied last, 5 × 503 `table_relayout_in_progress` to a materialize loop during the hold; unsupported-engine skip covered by `EnrichmentRelayoutServiceTest`
- [x] 5.12 Deploy-skew tests: old-table writes after the catch-up mark and epoch rows after the swap both survive, each with one WARN
- [x] 5.13 Bounded statements: every copy, catch-up, epoch repair and pre-drop check runs one slice of grain keys (~256 MiB uncompressed, upper key read on the table's sort key, last slice open) with an `ANY` join restricted to the slice's keys, capped at `pipeline.budget.max-memory`; resume position is the last copied key; measured at 10x a large deployment's scale (about 1 GiB per slice) and at small scale (read side a few hundred MiB)
- [x] 5.14 Tests: one key per slice copies the same rows; a copy resumed after its second slice finishes with every row; slice bounds cast to the key type and escaped

## 6. Catalog export/import

- [x] 6.1 Tests (no code change expected): export → import onto an environment with only system tables of `usage_client_identity`, `usage_request_baggage` and their pipelines lands both aligned, one `request_time` each, descriptions preserved
- [x] 6.2 Test: relayout of a `usage_request_baggage`-shaped table adopts its `request_time` and keeps its `ttl`; a mismatched same-named column leaves the table `unsupported`

## 7. Specs, API surface and docs

- [x] 7.1 Confirm the delta specs match the shipped behavior (table-management, structured-query, enrichment-runtime, aggregate-pipeline, class1-enrichment)
- [x] 7.2 OpenAPI: `layout` member and the 503 `table_relayout_in_progress` response; adas-mcp tool docs and snapshot refresh
- [x] 7.3 `docs/release-0.4-upgrade.md`: migration runs by itself, what it logs, ~3 expected Class-1 tick failures per table
- [x] 7.4 No `docs/configuration.md` change (no property added)

## 8. Verification

- [x] 8.1 `./gradlew clean build` green
