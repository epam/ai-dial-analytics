## 1. Configuration

- [x] 1.1 Add `Boolean sensitiveColumnsEnabled` to `SyncProperties` (Javadoc `@param`), normalised `null → true` like `inlineFilesDropTruncated`, and update every test constructing `SyncProperties`
- [x] 1.2 Add `sensitive-columns-enabled: ${SYNC_SENSITIVE_COLUMNS_ENABLED:true}` under `sync:` in `application.yml` (bare line, no comment)
- [x] 1.3 Add one row for `SYNC_SENSITIVE_COLUMNS_ENABLED` / `sync.sensitive-columns-enabled` to the data-sync table in `docs/configuration.md` (one-line description, `Required: No`)
- [x] 1.4 `SyncPropertiesTest`: the default binds to `true`, and `false` binds

## 2. Exclusion resolution

- [x] 2.1 Add a small resolver in `sync/mapping` (e.g. `SensitiveColumnExclusion`). Given the target tables, it batch-reads `column_mapping` through `ColumnMappingRepository` and the table definitions through `TableDefinitionRepository`, and returns per table the `source_name`s flagged `sensitive`
- [x] 2.2 In the resolver, fail startup (an `IllegalStateException` naming table and column) when an excluded column is `trace_id`, `core_span_id`, `request_time`, or the table's `grain_key`, `identity_column` or `ordering_key` column
- [x] 2.3 In the resolver, log a `WARN` for a target table with no catalog definition (nothing excluded), and an `INFO` per target listing its excluded columns
- [x] 2.4 Unit-test the resolver: seeded-shape catalog → the six payload columns and `rate_analytics.comment`, nothing for `dial_usage_log`; a sensitive key or grain column fails; an uncatalogued table warns and excludes nothing

## 3. Target compilation

- [x] 3.1 In `MappingConfiguration.syncTargets`, when the switch is off, drop the excluded columns from each loaded `MappingConfig` (keeping its matcher) before building the `MappingEngine`; when it is on, never call the resolver or the repositories
- [x] 3.2 Update the `MappingConfiguration` and `SyncTarget` Javadoc to describe the exclusion
- [x] 3.3 In `RequestDeltaStages`, when the mode is not `whole` and a target's mapping lacks `request_body` because it was excluded, log a `WARN` naming the target and saying the mode has no effect on it because its request body is excluded; keep the existing "no target qualifies" warning
- [x] 3.4 `RequestDeltaStagesTest`: a payload-shaped target without `request_body` under `delta` gets no stage and the warning is logged

## 4. Integration tests

- [x] 4.1 ClickHouse + Postgres integration test with `SYNC_SENSITIVE_COLUMNS_ENABLED=false`: ingest a chat completion record and a `/rate` record with a comment. Assert that the payload row exists with its keys, `project_id` and `user_hash`; `request_body`, `response_body`, `assembled_response` and `request_cache_control` are `NULL`; `jwt_claims` and `request_tags` are `{}`; the `dial_usage_log` row is complete and joins on `event_id`; and `rate_analytics.comment` is `NULL` with `rate` / `rate_raw` / `event_id` populated
- [x] 4.2 The same test with `SYNC_REQUEST_BODY_MODE=delta` as well: all chain columns are at their defaults
- [x] 4.3 Confirm an existing ingest integration test still covers the default (`true`) writing every sensitive column unchanged

## 5. Spec and verification

- [x] 5.1 Confirm the shipped behaviour matches the delta in `specs/data-sync/spec.md` (new requirement and the modified request-body-mode requirement), and adjust the delta if implementation forced a difference
- [x] 5.2 Run the public-repo hygiene sweep (`git grep -nIP` and the multiline pass from `CLAUDE.md`) over the changed files
- [x] 5.3 `./gradlew clean build` passes
