## 1. Pre-flight

- [x] 1.1 Re-read each MODIFIED requirement in this change's deltas against its current `openspec/specs/<capability>/spec.md` block. Re-extract any that moved since this change was drafted so the delta does not revert shipped text at archive
- [x] 1.2 (from an earlier snapshot of one deployment; re-check every deployment before deploy) List enrich pipelines on every reachable deployment and record each `trigger.kind` / `advanced.scan_every`. Confirm the rounding table covers them, or raise it before writing V44

## 2. Default cron (write path)

- [x] 2.1 Add a name-derived second helper (MD5 of the UTF-8 name, first four bytes unsigned big-endian, mod 60) and the default cron `S * * * * *`, with a unit test pinning fixed names to fixed seconds
- [x] 2.2 In `PipelineService`, resolve a blank or absent `trigger.cron` on an enrich `schedule` trigger to the default on every write (draft included) before `requireValidCron`. Aggregate behaviour is unchanged
- [x] 2.3 Tests: POST/PATCH of an enrich schedule pipeline without a cron stores and echoes the default; a declared cron is stored as sent; an aggregate without a cron is still refused at arming; delete + re-register yields the same cron

## 3. Retire `on_ingest`

- [x] 3.1 Drop `ON_INGEST` from `service/enrichment/model/TriggerKind`. In `PipelineMapper.triggerKindOf`, refuse `on_ingest` with a dedicated 422 `pipeline_validation_failed` naming `schedule` as the replacement, and list only `schedule, group` as allowed
- [x] 3.2 Update `PipelineService` messages and constraint-translation text (`pipeline_trigger_kind_check`, "trigger.kind is required") to the two-kind set, and fix the `requireValidCron` / `EnrichSpecValidator` / `RunSink` comments that name `on_ingest`
- [x] 3.3 Update OpenAPI descriptions on `PipelineRequests.trigger` and `TriggerRequest.kind` (two kinds; enrich `schedule` cron defaults when omitted)
- [x] 3.4 Move every test fixture and integration test that registers `on_ingest` to `schedule`, and add the refusal test (armed and draft, POST and PATCH)

## 4. Retire `advanced.scan_every`

- [x] 4.1 Remove `scanEvery` from `PipelineRequests.AdvancedRequest` and from the advanced-member list in `PipelineMapper`, so the strict web mapper answers 400 naming `advanced.scan_every`. Remove the read-side mapping that echoes it
- [x] 4.2 Delete `EnrichSpec.cadence` (`scan_every` / `@JsonAlias("cadence")`) and `EnrichDeclaration.cadence()`, and remove any compiled-projection member that carried it
- [x] 4.3 Tests: `advanced.scan_every` is 400 on enrich and on aggregate (POST and PATCH); the aggregate cross-kind scenario uses `advanced.rows_per_scan`; a stored spec with a stray `scan_every` key still parses

## 5. Migration `V44__retire_on_ingest_trigger.sql`

- [x] 5.1 Write V44: for each `ON_INGEST` row, set `trigger_kind = 'SCHEDULE'` and `trigger_cron` from the spec's rounding table using the MD5-derived second. Remove `scan_every` from every enrich `spec`, group included. Do not touch `generation`, `updated_at` or any state column. `RAISE NOTICE` each rewrite. Then replace `pipeline_trigger_kind_check` with `IN ('SCHEDULE', 'GROUP')`. Header documents the no-bump and no-audit-revision decisions
- [x] 5.2 Migration integration test (pattern of `FoldEvaluatorMigrationTest`), seeded at V43 with:
  - an `ON_INGEST` sql pipeline at `60s` with a materialized position;
  - an `ON_INGEST` llm pipeline at `90s`;
  - one at `7m`;
  - one with no `scan_every`;
  - one unparseable;
  - a `GROUP` pipeline at `5m`;
  - an aggregate.

  Assert the crons, removed keys, unchanged `generation` / `updated_at` / position, untouched aggregate spec md5, and that the narrowed CHECK rejects `ON_INGEST`
- [x] 5.3 Assert the migration's SQL second equals the Java helper's second for the same names

- [x] 5.4 `TriggerKindConverter` maps `trigger_kind` (writes the constant name, reads a historical `ON_INGEST` as `SCHEDULE`). Test that an audit revision recorded as `ON_INGEST` loads through `GET /v1/pipelines/{name}/revision/{revision}` (design D7)

## 6. Runner contract

- [x] 6.1 Raise `RunnerContract.VERSION_VALUE` from `"2"` to `"3"` and update the `RunnerContractShapeTest` golden list for the compiled projection (no `on_ingest`, no `advanced.scan_every`)
- [x] 6.2 (proposed 2026-10-05 at `analytics-enrichment-runner/openspec/changes/retire-on-ingest-trigger/`; applied in a separate session) Create the paired runner OpenSpec change `retire-on-ingest-trigger` in `analytics-enrichment-runner`:
  - contract version 3;
  - drop the fixed-delay `ScheduleFactory` branch;
  - add `GROUP_SCAN_CADENCE` (default `1m`, one row in the runner's `docs/configuration.md`) for the group mark scan;
  - remove `SCAN_DEFAULT_CADENCE` and `CLASS1_DEFAULT_CADENCE` (rows deleted from the same file);
  - keep `TriggerKind.ON_INGEST` modelled but refused by the admission gate;
  - drop `AdvancedDto.scanEvery`
  - (review follow-up, mirrored in the Enrichment Runner) reschedule when the stored db-scheduler schedule differs from the desired one, or at least do not cache a pipeline whose reconcile threw: a scan row still `picked` from the scale-to-0 step refuses `reschedule()` and would otherwise keep its persisted fixed delay. Refuse a `schedule` pipeline with a blank cron at the admission gate (`PipelineRouting.unsupportedReason`) rather than throwing in reconcile. Also cover the leftover mentions: `openspec/config.yaml`, `JsonMapperConfiguration`/`PipelineScanTask` comments, `incremental-scan` spec, and the scan/feed/status tests

## 7. Docs and cross-repo

- [x] 7.1 (done in `docs/release-0.3-upgrade.md` §2–§4.1; `docs/release-0.2-upgrade.md` is not edited) In the release-0.3 upgrade doc, add `on_ingest` and `advanced.scan_every` to the retired members, and describe V44's rewrite and the contract-3 paired deploy
- [x] 7.2 `docs/column-mapping.md`: replace "re-fire `on_ingest` enrichments" with "are picked up by row-grain enrichment scans"
- [x] 7.3 At archive, hand-edit the `enrichment-registry` Purpose line (row grain `schedule` only), since a delta cannot change it
- [ ] 7.4 adas-mcp: update pipeline tool docs (two trigger kinds, no `scan_every`, default cron; changing a Class-1 pipeline's cron is a re-declaration and bumps `generation`) and refresh the OpenAPI snapshot (`--sort-keys`)
- [ ] 7.5 Hand the UI note to its owner: remove *On ingest*, default *Schedule* to "Every minute" (send no cron, or the derived one), and show the ingest-lag hint

## 8. Verification

- [ ] 8.1 Local end to end, with ADAS and the runner both on contract 3:
  - migrated sql pipelines keep `materialized_through_*` across the runner restart;
  - a pipeline whose scan row is still `picked` at the first poll also ends up on its cron;
  - they fire on their derived second;
  - the group pipeline keeps labelling
- [x] 8.2 `openspec validate retire-on-ingest-trigger --strict` passes
- [x] 8.3 `./gradlew clean build` passes
