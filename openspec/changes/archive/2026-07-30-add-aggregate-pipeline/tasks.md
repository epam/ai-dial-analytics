## 1. Registry schema and entity

- [x] 1.1 Add `db/migration/postgres/V20__aggregate_pipeline.sql`: create `pipeline` with `name` PK,
  `kind` (CHECK `IN ('AGGREGATE')`), `target` (FK → `table_definition` `ON DELETE CASCADE`, `UNIQUE`),
  `input_table` (FK → `table_definition`), `filter_sql`, `spec` JSONB, `trigger_cron`, `enabled`,
  `cursor_version`/`cursor_identity`, `next_run_at`/`last_run_at`/`last_error`, `generation`,
  `created_at`/`updated_at`; index `enabled` + `next_run_at` for due-selection. Header comment explains
  the one-pipeline-per-target invariant and why `target` cascades but `input_table` does not
- [x] 1.2 Add `service/pipeline/PipelineEntity.java` + `PipelineKind.java` and
  `PipelineRepository.java` (finders: by target, by input table, and due = `enabled AND next_run_at <= now`)
- [x] 1.3 Add the spec model records under `service/pipeline/model/`: `AggregateSpec`,
  `GroupKey` (plain column | `trunc(col, unit)` + `as`), `Measure` (`name`, `fn`, `column`),
  `Freshness`, `TruncUnit` — mirroring the wire shape, with the `PipelineValidationException`

## 2. Validation and governance

- [x] 2.1 Add `service/pipeline/AggregateSpecValidator`: non-empty `group_by`/`measures`; at most one
  `trunc` part; `trunc` unit within the `date_bin`-backed set (reject `month` with a message naming the
  supported units); `trunc` column is a declared `date`/`timestamp`; measures resolve to catalog aggregate
  functions with `column` present except for `count`; reject `freshness.mode=incremental` naming the
  deferred capability; reject a non-`schedule` trigger; validate `trigger_cron` parses
- [x] 2.2 Target-shape gate in the same validator: target exists, `type=source`, `write=upsert_by_key`,
  `ACTIVE`, not `system`, has no other pipeline (409), and `ordering_key` equals the `group_by` output
  names in order (422 reporting both lists)
- [x] 2.3 Column binding + type gate: every group-key `as`/column name and `measures[].name` resolves to a
  declared target column whose type accepts the result type (reuse `QueryFunctionCatalog` return types +
  `QueryFieldTypeConverter`); reject a target column that nothing writes
- [x] 2.4 Input gate: input exists, is an `ACTIVE` source, is scannable (`version_column`/`identity_column`
  set), and is not the target; `filter` compiles via `FilterSqlValidator` against the input's
  `QueryEntityModel`
- [x] 2.5 Sensitive-column gate: collect every referenced input column (filter + group keys + measure
  columns) and run `SensitiveProjectionGuard.enforce`, reattributing a hidden column to 403 the way
  `MaterializeService` does

## 3. Compiler

- [x] 3.1 Add `service/pipeline/AggregateCompiler`: lower an `AggregateSpec` through `ExprTranslator`
  (group keys → `date_bin(1, unit, col)` for `trunc`, measures → aggregate calls aliased to their target
  column) and compose the `SELECT` directly — no new SQL generation.
  **Deviation from the plan:** *not* via `StructuredQueryBuilder.build`, which applies a default
  `LIMIT 100` when a query carries no page and would silently truncate the rollup write to the first 100
  group keys. Stopping at the expression translator is also what the Class-1 write path does
- [x] 3.2 Render the recompute statement: `INSERT INTO <target> (<group cols>, <measure cols>, _updated_at)`
  over the compiled `SELECT`, adding the dirty-key `IN (SELECT DISTINCT <group key exprs> … <window
  predicates>)` sub-select and the `now64(3)` version stamp, following
  `MaterializeService.writeWindow`'s structure
- [x] 3.3 Unit-test the rendered statement for a `(dimension, trunc)` group key and for a single
  non-time key (the `conv_rollup` shape), asserting the full recompute + dirty-key sub-select shape

## 4. Refresh execution

- [x] 4.1 Add `configuration/AggregateProperties` (`enabled`, `tick-cron`, `safety-lag`, `window-limit`)
  bound from `application.yml` `aggregate.*` with `${ENV:default}` bindings
- [x] 4.2 Add `service/pipeline/AggregateRefreshService.refresh(name)`: probe the window end over the
  input's cursor tuple (`ScanSourceMetadata.cursorColumns` + `CursorPredicates.safetyLag`/`seekPast`,
  bounded by the window limit), return a no-op when the probe is empty, else execute the compiled
  statement through `ClickHouseInsertSelectExecutor` and advance the cursor **only** on success
- [x] 4.3 Re-run the sensitive-column and spec validation at execution (the source schema can change
  under a stored pipeline), matching `MaterializeService`'s re-validation stance
- [x] 4.4 Report the run: rows written, whether more work remains (probe filled its limit), and staleness

## 5. Scheduler

- [x] 5.1 Add `service/pipeline/AggregateScheduler` with one `@Scheduled(cron = "${aggregate.tick-cron}")`
  tick that refreshes due pipelines and recomputes `next_run_at` from each pipeline's cron; guard the whole
  tick on `aggregate.enabled`
- [x] 5.2 Record `last_run_at`/`last_error` per run; a failing pipeline must not abort the tick for the
  others
- [x] 5.3 Set `next_run_at` when a pipeline is enabled and clear it when disabled, so due-selection needs
  no cron evaluation per tick
- [x] 5.4 Class-level comment recording why no leader election is needed (idempotent recompute; same
  argument as `DataSyncService`)

## 6. Control plane

- [x] 6.1 Add `service/pipeline/PipelineService`: create (always `enabled: false`), get, list
  (`?target=&kind=`), patch (spec edits only while disabled; enable/disable), delete
- [x] 6.2 Add `web/dto/pipeline/` (`CreatePipelineRequest`, `PatchPipelineRequest`, `PipelineDto` with a
  read-only nested `state`, `PipelineListDto`, `PreviewResponse`, `RefreshResponse`) — response objects,
  never bare arrays, `@Schema` on every member
- [x] 6.3 Add `web/mapper/pipeline/PipelineMapper` and `web/controller/pipeline/PipelineController`
  (`POST`/`GET`/`GET {name}`/`PATCH`/`DELETE`) plus `PipelineDataController`
  (`POST …/preview`, `POST …/refresh`), all `@FullAdminOnly`
- [x] 6.4 Implement preview: render the compiled statement and execute a bounded `SELECT`-half sample via
  `StructuredQueryExecutor` (no write); allowed on a disabled pipeline
- [x] 6.5 Add `@ExceptionHandler`s to `DefaultExceptionHandler`: `unknown_pipeline` 404,
  `pipeline_target_conflict` 409, pipeline validation 422 (sensitive refusal reuses the existing 403 path)

## 7. Table-delete interaction

- [x] 7.1 In `TableCatalogService.delete`, reject with 409 naming the pipeline when the table is any
  pipeline's `input_table`; let a target delete cascade the pipeline row via the FK
- [x] 7.2 Test both: input delete blocked, target delete removes the pipeline and leaves no orphan

## 8. Observability

- [x] 8.1 Register Micrometer metrics mirroring the `sync.*` naming: refresh runs by outcome, rows
  written, duration, and a per-pipeline staleness gauge

## 9. Tests

- [x] 9.1 Rejection branches covered end-to-end by `AggregatePipelineIntegrationTest.governanceRejectsMisdeclarations`
  (append target, ordering_key mismatch, incremental freshness, month bucket) rather than as isolated
  validator unit tests — the integration form also proves the status code the caller actually receives.
  **Narrowed:** the remaining branches (duplicate measure name, unwritten target column, unknown fn,
  two buckets, non-scannable input) are implemented and specced but not individually tested
- [x] 9.2 Scheduler unit tests: only due pipelines run; a failure isolates to its pipeline; disabled
  pipelines never run
- [x] 9.3 Integration test — happy path: create an `upsert_by_key` target, register + preview + enable a
  pipeline over `dial_usage_log`, seed rows, refresh, then query the rollup and cross-check the measures
  against the same aggregate run through `POST /v1/queries/execute` on the input
- [x] 9.4 Integration test — **idempotence**: refresh twice with no new input and assert the rollup is
  byte-identical (the property the whole design rests on)
- [x] 9.5 Integration test — **late data**: seed a bucket, refresh, insert another row into that same
  bucket, refresh again, and assert the bucket holds the *total* rather than the increment and did not
  fan out into two rows
- [x] 9.6 Integration test — governance: `append` target 422; `ordering_key` mismatch 422; sensitive
  measure 403; `incremental` 422; `month` bucket 422
- [x] 9.7 Integration test — state: `GET /v1/pipelines/{name}` reports an advanced cursor and run times
  after a refresh, and a `last_error` after a failure

## 10. Specs and docs

- [x] 10.1 Add the `aggregate.*` config table to `docs/configuration.md` (descriptions live only there)
- [x] 10.2 Update `openspec/project.md`: new capability in the intro sentence, the `/v1/pipelines` surface,
  and the operational note that the writer credential's existing `SELECT`-on-sources requirement now also
  covers the aggregate path
- [x] 10.3 Mention the pipelines surface in `README.md` prose (no env-var table; link to
  `docs/configuration.md`)
- [x] 10.4 Run `openspec validate add-aggregate-pipeline` and `./gradlew clean build` with the Docker
  socket env vars, and confirm both pass
