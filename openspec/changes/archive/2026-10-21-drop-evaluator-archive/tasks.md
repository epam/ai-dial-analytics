## 1. Migration

- [x] 1.1 Confirm the highest Postgres migration on `development` (currently V47) and pick the next free number for `V48__drop_evaluator_archive.sql`
- [x] 1.2 On a local database at V47, list every dependent of `evaluator`, `evaluator_version`, `rule_legacy`, `rule_legacy_aud` and the four `pipeline` / `pipeline_aud` evaluator columns (`pg_depend`, `\d+`); confirm nothing beyond the FKs and `pipeline_evaluator_name_idx` named in design D2
- [x] 1.3 Write V48: drop `pipeline.evaluator_name` / `evaluator_version`, then the `pipeline_aud` mirrors, then `rule_legacy_aud`, `rule_legacy`, `evaluator_version`, `evaluator`. Use no `CASCADE` and no `IF EXISTS`. Put a header comment saying why, and that `pipeline.legacy_rule_id` stays because the previous release maps it and the next release drops it. Follow the public-repo rules: no environment names, no real figures

## 2. Delete the archive code

- [x] 2.1 Delete `EvaluatorController`, `EvaluatorService`, `EvaluatorRepository`, `EvaluatorVersionRepository`, `EvaluatorEntity`, `EvaluatorVersionEntity`, `EvaluatorVersionId`, `EvaluatorMapper`, `EvaluatorVersionDto`, `EvaluatorSummaryDto`, `EvaluatorOutputDto`, `EvaluatorNotFoundException`, `EvaluatorImmutableException`
- [x] 2.2 Remove the `unknown_evaluator` and `evaluator_immutable` handlers and their imports from `DefaultExceptionHandler`
- [x] 2.3 Remove the `legacyRuleId` field and its Javadoc from `PipelineEntity`; check that nothing else reads it
- [x] 2.4 Reword the Javadoc that describes the archive as existing: `OutputShapes`, `ProvenanceColumns`, `MaterializeService`, `Privilege`, `PipelineJsonReadNotes`, `ActivityResourceType`, `AuditResourceMapper`, `AuditPipelineSnapshotService`, `TableController`'s provenance description. Keep `ProvenanceColumns.EVALUATOR_VERSION` in `NAMES`, and keep the `EnrichWriteValidator` / `TableSchemaService` guards unchanged

## 3. Tests for the deletion

- [x] 3.1 Delete `EvaluatorArchiveIntegrationTest`, `EvaluatorServiceTest` and `FoldEvaluatorMigrationTest`
- [x] 3.2 `AuditTrailIntegrationTest`: drop the evaluator repository, the seeding helpers and the `save` calls, and drop the `pipeline_aud.evaluator_*` assertions; the tests keep asserting their pipeline revisions
- [x] 3.3 `PipelineRegistryConstraintIntegrationTest`: stop writing `evaluator_name` in the insert helper and drop `DELETE FROM evaluator`. Rework `anEnrichPipelineWithNoEvaluatorIsStorable`. Turn the retained-columns test into one asserting that the `pipeline` / `pipeline_aud` evaluator columns, the `evaluator` / `evaluator_version` tables and `rule_legacy` / `rule_legacy_aud` are absent and `legacy_rule_id` is present. Update the column list in `theFoldedRuleColumnsAreAllPresent` and replace `theRetiredRuleTableSurvivesUnderItsLegacyName`
- [x] 3.4 `AuditResourceMapperTest.dropsEntitiesOutsideTheAuditedSurface`: use another unaudited entity in place of `EvaluatorEntity`
- [x] 3.5 Replace `CompiledProjectionIntegrationTest.anUnauthoredBindingFailureStillGetsTheGenericMessage` with a `DefaultExceptionHandler` unit test that feeds `handleBadRequest` a constructed `MethodArgumentTypeMismatchException` and asserts the generic message (design D7)
- [x] 3.6 `StrictRequestBodyIntegrationTest`: remove the stray `"evaluator_name"` from the body that tests `order`; `RunnerContractShapeTest`: reword the Javadoc that cites the archive's DTOs
- [x] 3.7 Add an integration test for the spec's "No evaluator surface remains": `GET /v1/evaluators` and `GET /v1/evaluators/{name}/versions/{v}` answer `404 not_found`, and `PUT` / `DELETE` on a version answer neither `evaluator_immutable` nor `unknown_evaluator`

## 4. Rename the live transform types

- [x] 4.1 Rename `EvaluatorType` → `TransformType`, `EvaluatorPreset` → `TransformPreset`, `EvaluatorOutput` → `TransformOutput`, `EvaluatorVar` → `TransformVar` (`service/enrichment/model/`) and `EvaluatorVarDto` → `TransformVarDto` (`web/dto/enrichment/`) across main and test code; enum constants and JSON property names stay unchanged
- [x] 4.2 Reword local variables, comments and user-facing messages that say "evaluator" for "transform" (e.g. `PipelineGenerationService`, `PipelineKind`, `VarSpecDeserializer`, `OutputSpecDeserializer`, the comment-only list in design D6); leave `TableAccessEvaluator` and the Calcite evaluator alone
- [x] 4.3 Rename `src/test/resources/fixtures/evaluator-versions/` → `fixtures/transforms/` and update `ResponseSchemaComposerTest`, `TemplatePlaceholderRuleTest` and `fixtures/README.md`
- [x] 4.4 Confirm that the `GET /v1/pipelines?kind=enrich&view=compiled` and `view=source` JSON is byte-identical before and after the rename on a local instance, and that `/v3/api-docs` differs only in the `Evaluators` tag, the archive schemas and the `EvaluatorVarDto` → `TransformVarDto` component

## 5. Specs, conventions and docs

- [x] 5.1 When archiving, also hand-edit the `enrichment-registry` Purpose (the "`/v1/evaluators` survives only as a read-only archive" sentence), which a delta cannot reach
- [x] 5.2 `openspec/config.yaml` context: two frozen bare-array endpoints, not three; drop the `/v1/evaluators` typed-path-variable example; reword the "evaluator tables are not audited" paragraph
- [x] 5.3 `README.md`: drop the "retained evaluator archive" sentence; `docs/catalog-promotion.md`: drop "evaluators" from the list of resources that are never promoted
- [x] 5.4 `docs/release-0.4-upgrade.md`: add an optional `pg_dump -t evaluator -t evaluator_version` step to §1, a V48 row to §4, a §6 breaking-change row (`/v1/evaluators*` → 404), and a note on the OpenAPI component rename and the `legacy_rule_id` drop due in the next release
- [x] 5.5 No `docs/configuration.md` change: the change adds and renames no property

## 6. Verification

- [x] 6.1 Run the sweep `git grep -nIP '\bEvaluator(Entity|Service|Controller|Mapper|Repository|VersionDto|SummaryDto|OutputDto|Immutable|NotFound|Type|Preset|Output|Var)\b|/v1/evaluators|unknown_evaluator|evaluator_immutable|rule_legacy|legacyRuleId' -- ':!openspec/changes/archive' ':!src/main/resources/db/migration'`; the only allowed hits are V48-adjacent explanatory text and the new 404 test
- [x] 6.2 Run the public-repo hygiene sweeps from `CLAUDE.md` (line-based PCRE and multiline) over the new and changed files
- [x] 6.3 Run a local `bootRun` against a database restored at V47 that holds evaluator rows: V48 applies, `GET /v1/evaluators` answers `404 not_found`, and an enrich pipeline `PATCH` and its revision read still work
- [x] 6.4 `openspec validate drop-evaluator-archive --strict` passes
- [x] 6.5 `./gradlew clean build` passes
