## Why

`usage_client_identity` (UCI) is laid out `ORDER BY event_id` with no partitioning and no skip index, so the
three statements that read it — the `session-insights-live` group-select (millions of rows per call, hundreds
of calls a day), the scan (millions of rows per call, about a thousand calls a day) and the `sessions_rollup`
refresh (tens of millions of rows per call) — read the whole of it, and two of them also read `dial_usage_log`
back to the pipeline's floor. The cost grows with history and is independent of the session's size. It is
negligible while history is short; the mechanism is certain and the cost is linear in history, so it is
cheaper to fix before a deployment accumulates a year.

## What Changes

- **Aligned layout, decided by ADAS (F1).** At define-schema, an enrichment whose `grain_key` is its
  source's `identity_column`, over a partitioned, partition-local source, is created `PARTITION BY` the
  source's partition expression with an ADAS-owned column of the source partition column's name and type
  (`request_time`). No user declaration; a caller-supplied `partition_by` stays a 422; a user column with
  that name is a 422.
- **Tuple join.** An aligned enrichment joins on `(grain_key, partition column)`, not the grain key alone.
  Applies to `dial_usage_log_payload` too.
- **Writers.** Class-1 materialize writes `source.<partition column>`; `POST /v1/tables/{name}/rows` on an
  aligned enrichment requires it; for a row-grain LLM pipeline the scan returns the source row's value as
  `__partition_v` and the runner echoes it on the write-back (paired runner change, contract version 3 → 4).
- **Partition pre-selection (F2).** A top-level lookup conjunct (`col = lit`, `col IN (lits)` on the read
  path; also the pipelines' own `IN (subquery)` lookups; false on an unmatched row, never a heavy column) over
  one aligned enrichment adds
  `partitionExpr(source.p) IN (SELECT DISTINCT partitionExpr(p) FROM E WHERE C SETTINGS final=0)` on the
  source, which the tuple join carries into `E`. Applied on the group-select, the aggregate recompute's dirty-key
  restriction (form and SQL modes) and the user DSL path. A `string` group key is rendered on the bare column,
  never through `toString`.
- **Lookup index (F3).** ADAS adds `bloom_filter(0.001)` on an aligned enrichment column once a group
  trigger's or aggregate's `group_by` names it, via `ADD INDEX` only — never `MATERIALIZE INDEX`.
- **TTL inheritance.** An aligned enrichment inherits its source's TTL unless it declares its own.
- **Automatic relayout (F4).** After readiness (plus a fixed grace delay), a single background job per table
  copies each existing eligible, Class-1-only enrichment into an aligned table, fences its writers with a
  short **503**, swaps with `EXCHANGE TABLES`, repairs deploy-skew rows, and drops the old table. Resumable,
  advisory-locked, engine-checked; `GET /v1/tables/{name}` reports `layout: migrating | aligned |
  unsupported`.

No breaking change to any request a client makes today, except a rows-API write to an enrichment that becomes
aligned (it must now carry `request_time`) and a pipeline onto an aligned target that reads another table or has a
group trigger (refused at registration). New response member: `layout`. New 503 error code
for the writer hold.

## Capabilities

### New Capabilities

None — every behavior lands in an existing capability.

### Modified Capabilities

- `table-management`: aligned layout of eligible enrichments (ADAS-set `partition_by`, alignment column, adopted
  from a matching declared column and a 422 for a mismatched one, DDL renderer emits `PARTITION BY` for one); tuple join for aligned enrichments; lookup
  index; TTL inheritance; automatic relayout with its `layout` status and writer hold; the rows API
  requires the alignment column.
- `structured-query`: partition pre-selection from an enrichment lookup conjunct on the read path.
- `enrichment-runtime`: group-select pre-selection and bare-column group key; the scan returns
  `__partition_v` for an aligned target and the write-back requires it back; write-back hold returns 503.
- `aggregate-pipeline`: recompute pre-selection on the restricted grain enrichment.
- `class1-enrichment`: materialize writes the alignment column, superseding any output bound to it;
  materialize returns 503 during the hold.

`catalog-export-import` is unchanged: columns still travel as stored, and import's define-schema adopts
the exported `request_time` as the alignment column and re-derives the layout from the target's source.

## Impact

- **Code:** `TableDdlRenderer`, the define-schema path and `TableDefinitionService` validation;
  `CatalogQueryService` / `EnrichmentJoins` join build; `StructuredQueryBuilder` and `ReadShaping`
  (pre-selection); `GroupFetchService.selectMembers`; `AggregateCompiler.compileRefresh`,
  `AggregateSqlCompiler`; `MaterializeService.writeWindow`; pipeline registration validation; a new
  relayout job + writer-hold guard; `DefaultExceptionHandler` (503 code).
- **Persistence:** one Postgres Flyway migration (relayout progress + writer-hold flag); catalog layout
  state. No ClickHouse migration — the relayout is runtime DDL.
- **API:** `GET /v1/tables/{name}` gains `layout`; new 503 error code on materialize and the rows writes.
  OpenAPI, adas-mcp tools and the snapshot refresh follow.
- **Runner:** paired change — carry `__partition_v` from the scanned row to the write row; contract version
  3 → 4 on both sides, so the two deploy together (exact-match check). The runner already retries a 503 from its committed cursor.
- **Config:** none (grace delay and chunking are constants).
- **Docs:** `docs/release-0.4-upgrade.md` gains a note that the migration runs by itself, what it logs and
  the ~3 expected Class-1 tick failures.
