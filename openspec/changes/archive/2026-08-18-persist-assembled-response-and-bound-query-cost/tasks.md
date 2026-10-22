## 1. Persist the merged response — schema and ingest

- [x] 1.1 Add `src/main/resources/db/migration/clickhouse/V7__dial_usage_log_assembled_response.sql`:
      `ALTER TABLE dial_usage_log ADD COLUMN assembled_response Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)`,
      with a header comment covering why it complements `V1` rather than editing it, that `ADD COLUMN`
      is metadata-only (no mutation), and that the codec requires the server-side encryption key
- [x] 1.2 Add the `assembled_response` entry to `src/main/resources/mapping/dial-usage-log.yaml`
      (`source: assembled_response`, `kind: string`, `nullable: true`), placed in the file's DDL column
      order, and update the file's header PII warning to name three captured payload columns
- [x] 1.3 Confirm no change is needed in `DeriverRegistry` / `JsonPaths` / `MappingEngine` — the column
      is mapped, not derived — and that the existing `response_id` deriver still reads
      `assembled_response` as a source unchanged
- [x] 1.4 Verify `SchemaValidator` accepts the mapping against the migrated table, and that it still
      fails fast when the column is declared but the DDL has not been applied

## 2. Persist the merged response — catalog

- [x] 2.1 Add `src/main/resources/db/migration/postgres/V29__seed_assembled_response_column.sql`
      registering the column on `dial_usage_log` with its type, nullability, description, tag, and
      `sensitive = true` / `heavy = true`
- [x] 2.2 Write the column description so it states what the value is (the producer's merged response),
      that it is stored verbatim, and that on an upstream-native stream it is an SSE transcript rather
      than JSON
- [x] 2.3 Check the seeded `tag` places it with the other response-side columns in `tag_order`

## 3. Bound query cost — execution

- [x] 3.1 Add a `query.budget.*` properties record (`timeout`, `max-memory`, `spill-threshold`) bound
      from `application.yml` with `QUERY_BUDGET_TIMEOUT:20s`, `QUERY_BUDGET_MAX_MEMORY:2GB`,
      `QUERY_BUDGET_SPILL_THRESHOLD:1GB`, following `AggregateProperties.Preview`'s shape
- [x] 3.2 Build the `QuerySettings` (`max_execution_time`, `max_memory_usage`,
      `max_bytes_before_external_group_by`) and attach them at the `StructuredQueryService` boundary
      only, through the executor's existing per-call settings seam
- [x] 3.3 Confirm `ScanService`, `GroupFetchService`, `MaterializeService`, and
      `AggregateRefreshService` do not pick the budget up

## 4. Bound query cost — error contract

- [x] 4.1 Add `QueryBudgetExceededException` and a translator matching `ServerException` codes 159
      (`TIMEOUT_EXCEEDED`) and 241 (`MEMORY_LIMIT_EXCEEDED`) on the cause chain, modelled on
      `PreviewBudget.translate`; everything else rethrows untouched
- [x] 4.2 Add the `@ExceptionHandler` on `DefaultExceptionHandler` returning `422` /
      `query_budget_exceeded`, with a message naming the ceiling, its configured value, and what to
      narrow — and a comment recording why 422 rather than 503
- [x] 4.3 Confirm the mapping applies identically on `/v1/queries/execute`,
      `/v1/queries/execute-sql`, and Flight SQL, all of which run through `StructuredQueryService`

## 5. Tests

- [x] 5.1 Ingest test: a record carrying `assembled_response` stores it verbatim; a record without one
      stores `NULL`; an SSE-transcript value is stored unchanged and does not fail the row
- [x] 5.2 Ingest test: a column derived from `assembled_response` (`response_id`) is unaffected by the
      column also being stored
- [x] 5.3 Ingest test: a mapping without the `assembled_response` entry writes no such column
- [x] 5.4 Catalog test: `GET /v1/tables/dial_usage_log` reports the column with `sensitive: true` and
      `heavy: true`; a wildcard projection omits it; naming it explicitly returns it in full
- [x] 5.5 Authorization test: a non-`FULL_ADMIN` caller cannot see or reference the column
- [x] 5.6 Budget test: a query inside the ceilings is unaffected; a query breaching the time ceiling and
      one breaching the memory ceiling each return `422` / `query_budget_exceeded` naming the ceiling
- [x] 5.7 Budget test: an unrelated ClickHouse failure keeps its existing status and error code
- [x] 5.8 Budget test: an enrichment scan or aggregate refresh over the same executor is not bounded by
      the query budget

## 6. Documentation

- [x] 6.1 Add the `query.budget.*` properties to `docs/configuration.md` as their own group, covering
      why the wall-clock default sits below a typical client read timeout, what a refusal looks like,
      and when to raise the ceilings — descriptions live here only, never in `application.yml`
- [x] 6.2 Update `docs/heavy-columns.md` and `docs/column-tags.md` for the new column
- [x] 6.3 Update the `README.md` PII note (currently "`assembled_response`, `request_body`,
      `response_body`" as the fields ingest sees) to say the merged response is now persisted
- [x] 6.4 Note in `docs/configuration.md`'s data-sync mapping section that the shipped mapping now
      captures three payload columns, and that removing the entry is the off switch

## 7. Rollout

- [ ] 7.1 Apply the migrations on a running deployment and confirm the column reads back `NULL` on existing rows and
      populated on newly ingested ones
- [ ] 7.2 Run a bounded `data-sync-backfill` pass over a small window, verify the values land, then
      schedule the full pass deliberately against the `conversation_insights` rule's state
- [ ] 7.3 Re-run the reproduction (a heavy
      projection with no `request_time` bound) and confirm it now returns `query_budget_exceeded`
      rather than an empty transport error

## 8. Verification

- [x] 8.1 Confirm the delta specs match the shipped behavior, and run
      `openspec validate persist-assembled-response-and-bound-query-cost --strict`
- [x] 8.2 `./gradlew clean build` passes (checkstyle + tests)
