## 1. Re-point the mapping

- [x] 1.1 In `src/main/resources/mapping/dial-usage-log.yaml`, replace the `cache_creation_tokens`
  entry's `derive: json-extract-uint` / `sources: [assembled_response, response.body]` /
  `args: { path: usage.cache_creation_input_tokens }` with
  `source: token_usage.prompt_tokens_details.cache_write_tokens`, keeping `kind: long` and adding
  `nullable: true`. Keep the entry in its current position so the column order (and
  `writesOnlyDeclaredColumnsInOrder`) is unchanged.
- [x] 1.2 Update the file's header comment where it describes which columns are derived from
  `assembled_response`/`response.body`, so it no longer lists this column among the body-derived ones.

## 2. Make the column nullable in ClickHouse

- [x] 2.1 Add `src/main/resources/db/migration/clickhouse/V6__dial_usage_log_nullable_cache_creation_tokens.sql`
  with `ALTER TABLE dial_usage_log MODIFY COLUMN cache_creation_tokens Nullable(UInt32) CODEC(T64, ZSTD(1));`
  — restating the codec deliberately, since `MODIFY COLUMN` replaces the whole declaration.
- [x] 2.2 Write the migration's header comment in the style of `V3`/`V5`: why `V1` is not edited (applied
  checksums are fixed), what the mutation actually costs (measured, not assumed — see 2.3), and that
  operators with `clickhouse.migrations.enabled=false` must apply it before deploying or startup
  validation will stop them.
- [x] 2.3 Confirm against the live 25.3 server that the `ALTER` returns without waiting for the part
  rewrite and that reads of not-yet-mutated parts return correctly-typed values during the mutation.
  Record the finding in the migration comment; if it blocks instead, say so there.

## 3. Align the catalog

- [x] 3.1 Add `src/main/resources/db/migration/postgres/V23__dial_usage_log_cache_creation_nullable.sql`
  setting `column_mapping.nullable = TRUE` for `(dial_usage_log, cache_creation_tokens)`, in the shape of
  `V21__backfill_dial_usage_log_nullability.sql`, with a comment pointing at the ClickHouse `V6` that
  makes it true.

## 4. Tests

- [x] 4.1 In `MappingEngineTest`, extend the `SAMPLE` fixture's `token_usage.prompt_tokens_details` with
  a `cache_write_tokens` value that differs from the `cache_creation_input_tokens: 7` already in
  `assembled_response` (e.g. `9`), and assert the row carries the `token_usage` value — pinning that the
  count comes from the producer's accounting, not the response payload.
- [x] 4.2 Move the `cache_creation_tokens` assertion out of `computesCalculatedColumns` (it is no longer
  a calculated column) into the mapped-column assertions next to `cached_prompt_tokens`.
- [x] 4.3 In `absentNullableColumnsBecomeNullWhileNonNullableFallBackToZero`, move
  `cache_creation_tokens` from the zero-fallback group to the null group, next to `cached_prompt_tokens`.
- [x] 4.4 Add a test for the streamed shape: a record whose `response.body` and `assembled_response` are
  an SSE transcript (`event: message_start\ndata: {...}`) and whose `token_usage` reports a cache-write
  count — assert the column carries that count, not `0`. This is the regression guard for the bug.
- [x] 4.5 In `SchemaValidatorTest.liveSchema()`, change `cache_creation_tokens` to `Nullable(UInt32)`,
  and confirm the existing nullability-mismatch test still covers the bare-`UInt32` case (add one if the
  guard would otherwise not fire for this column).
- [x] 4.6 Check `src/test/java/com/epam/aidial/analytics/service/query/DialUsageLogTestModel.java` and any
  catalog/integration test that enumerates `dial_usage_log`'s nullable columns, and update them to include
  `cache_creation_tokens`.

## 5. Docs

- [x] 5.1 In `docs/configuration.md` (default-mapping bullet, ~line 410), move `cache_creation_tokens`
  out of the calculated-column list into the direct columns.
- [x] 5.2 Add a release note to the change describing the two operator-visible effects: startup fails
  until `V6` is applied when migrations are disabled, and the column steps from historical `0`s to real
  values/NULLs at deploy (backfill is separate).

## 6. Verify

- [x] 6.1 Run `./gradlew clean build` and confirm compile,
  checkstyle, and tests pass.
- [x] 6.2 Search the repo for remaining references to `usage.cache_creation_input_tokens` and confirm
  none of them still claims to populate `cache_creation_tokens`.
- [x] 6.3 Verify against the real records: run the shipped mapping over both raw DIAL log files and
  confirm the streamed Anthropic-native span carries the producer's count, the non-streamed one is
  unchanged, a reported 0 stays 0, and a record with no `prompt_tokens_details` yields NULL. Confirm
  `V6` + `V23` + `SchemaValidator` + insert against real ClickHouse/PostgreSQL via the integration suite.
- [x] 6.4 Confirm `openspec validate fix-cache-creation-tokens-mapping --strict` passes and the shipped
  behavior matches the `data-sync` and `table-management` deltas.
- [ ] 6.5 Ingest into a running instance and read the columns back. Deferred: this applies `V6` to a
  shared ClickHouse and writes rows, so it needs the operator's go-ahead rather than being done as part
  of implementation.
