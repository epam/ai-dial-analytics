## 1. Response shape

- [x] 1.1 `web/dto/catalog/ImportAction`: add `UPDATE`, with Javadoc ("exists and is changed under `OVERWRITE`").
  - The maintainers confirmed `UPDATE` as the spelling (design, Open Questions).
  - Reword the `SKIP` Javadoc to cover a converged object under `OVERWRITE`.
- [x] 1.2 Add `PlannedChange` (`op`, `name`, `fields`), `NotApplied` (`kind`, `name`, `reason`) and `ImportEffect` (`kind`,
  `pipeline`) records under `web/dto/catalog/`.
  - Each `op` / `kind` is a lower-case enum with `@JsonValue`.
  - Each record has `@Schema` descriptions.
- [x] 1.3 `ImportEntry`: add `changes`, `not_applied` and `effects`. Each is always present, empty when there is nothing.
  - Keep `withStatus` carrying them.
  - Add `updated` to the documented `status` values.
- [x] 1.4 `EnvSpecificValue`: add `target_value` (nullable) and `kept` (boolean), with `@Schema` descriptions.
- [x] 1.5 `ResolutionPolicy` Javadoc and `CatalogTransferController.POLICY_DESCRIPTION`: describe `OVERWRITE` as "update where
  safe" (spec "Resolution policy"). Drop "import never mutates an existing object".
  - Update the `CatalogImportService` class Javadoc, which states that invariant (around line 56).

## 2. Target-owned fields and the diff

- [x] 2.1 Add `service/catalog/transfer/TargetOwnedFields`. It holds:
  - the pipeline fields: every `advanced` member, `trigger.cron`, and the `{preset, model, route}` binding as one unit;
  - the table field `ttl`;
  - for each field, a reader for its value from a `BundlePipeline.Declaration` / `BundleTable`, and a way to copy the target's
    value onto the bundle's declaration.
  - The binding is copied only when the presets are equal. An unset target `advanced` member is copied as unset.
- [x] 2.2 Refactor `CatalogImportPlanner.collectEnvSpecific` and the table `ttl` item to read `TargetOwnedFields`.
  - Keep the date-literal scan as is, with `kept: false`.
  - On an existing object, fill `target_value` and `kept` as the spec's "Environment-specific values reported" defines.
  - Also list a field the target sets and the bundle does not.
- [x] 2.3 Add `service/catalog/transfer/ObjectDiff`, which diffs a table's full `existing.bundle()` against `next`, with columns
  matched by exposed `name`. It returns changes, not-applied items, effects, and the refusing problems.
  - **Changes:** `update_table` (`description`, `tag_order`), `add_column`, and `update_column` (`description`, `display_name`,
    `tag`, `heavy`, and `sensitive` false→true).
  - **Not applied:** `extra_column`, `column_order`, `sensitive_loosening`, and `target_owned` for `ttl`.
  - **Refusing problems:** `source_name`, `type`, `element_type`, `nullable` and enum values; table `type` and `write`;
    `ordering_key`, `partition_by`, `grain_key`, `cardinality`, `source_table`, `identity_column` and `version_column`.
  - **Effects:** when the table is an enrichment and a column change or a column description change reaches it, a
    `generation_bump` effect naming the pipeline that targets it (that pipeline comes from `ImportTarget`).
- [x] 2.4 `ObjectDiff` for pipelines: substitute the target-owned members from the target, then compare the declarations.
  - `kind` or `target` differing is a refusing problem.
  - Any remaining difference is a `redeclare`, listing the top-level declaration members that differ (e.g. `filter`,
    `transform.outputs`), preceded by `disable` for an enabled `aggregate`.
  - Effects:
    - always `generation_bump`;
    - for an `aggregate`, `rebuild_from_zero`, plus `rearm_required` when it was enabled;
    - for an `enrich`, `existing_rows_not_rederived`.
  - Each kept target-owned difference is a `not_applied` `target_owned` item.
- [x] 2.5 `PlannedObject`: add `update(changes, notApplied, effects)`, `updates()` and `diff(changes, notApplied, effects)`.
  - `diff(…)` records the lists on a `SKIP` entry without changing its action.
  - `toEntry()` emits the lists.
  - Keep `existing` reachable for compensation.
- [x] 2.6 `CatalogImportPlanner.planTable` / `planPipeline`: for an existing ACTIVE user table or a pipeline, under `OVERWRITE` and
  `SKIP_IF_EXISTS`, run `ObjectDiff` and record the result as follows.
  - **Under `OVERWRITE`:**
    - a refusing problem makes the entry `fail(...)`;
    - otherwise the entry becomes `update(...)` when there are changes, or `skip()` with the lists when there are none.
    - An entry with changes on a table that has a column hidden from the caller fails. Use `ImportTargetReader`'s full-versus-
      visible pair.
  - **Under `SKIP_IF_EXISTS`:** `skip()` and `diff(...)`.
  - **Under `FAIL_IF_EXISTS`:** unchanged.
  - Keep the system-table, non-ACTIVE and refused-dependency rules ahead of the diff.

## 3. Services used by the update

- [x] 3.1 `service/table/TableSchemaService`: add `patchSchema(name, patch, DdlExecutor)`, mirroring
  `TableSchemaCreationService.defineSchema(name, input, executor)`.
  - The two-argument form keeps the real executor.
  - Under `DdlExecutor.NO_OP`, skip the `alterTookEffect` probe.
  - Confirm that `CatalogSchemaWriter`'s pipeline generation bump still runs in the pass, so `effects` and arming see it.
- [x] 3.2 Build the `PatchInput` for a table update from the `add_column` and `update_column` changes, through the same
  `TableMapper` path `PATCH /v1/tables/{name}/schema` uses, so its validation is the endpoint's.
- [x] 3.3 `web/mapper/pipeline/PipelineMapper`: add a re-declaration built from a `BundlePipeline` and the stored pipeline, next to
  `toRegistration(BundlePipeline)`.
  - It names `kind` and `target` explicitly and carries the target-owned members from the target.
  - It keeps legacy `output_vars` and an `aggregate`'s `sql` verbatim, as registration does.
  - It passes through `PipelineService.patch(name, registration, enabled)`.

## 4. Validation pass

- [x] 4.1 `CatalogImportValidator.Pass`: for `updates()` tables, call `updateMetadata` and the no-op `patchSchema` in the
  rolled-back transaction, in the same order as creates.
  - Count added columns toward `max-columns-per-table`, which `patchSchema` enforces.
  - Report a refusal on the entry, as create refusals are.
- [x] 4.2 For `updates()` pipelines: run the disable patch when the plan has `disable`, then the re-declaration.
  - A refusal, including the arm gate on an `enrich` that stays enabled, fails the entry.
- [x] 4.3 Arming:
  - Arm-check updated pipelines that end disabled, as created ones are checked.
  - Keep the `exported_enabled` shortcut (around line 235) for `SKIP` pipelines only.
  - An updated `enrich` that stays enabled gets `armable` from its re-declaration's outcome.

## 5. Authorization and apply

- [x] 5.1 `CatalogImportService.prepare`: remove the `OVERWRITE` refusal. Delete `ResolutionPolicyUnsupportedException` and its
  `DefaultExceptionHandler` handler.
  - Check the error-code list in `docs/` and the OpenAPI descriptions for `resolution_policy_unsupported`.
- [x] 5.2 `CatalogImportService.authorize`, run only when security is enforced:
  - `TABLE_MODIFY` on each `updates()` table, global or granted, through the same check `PATCH .../schema` uses;
  - `REGISTRY_ADMIN` when any pipeline `updates()`;
  - `TABLE_SENSITIVE` when an update adds a sensitive column or sets `sensitive: true`.
- [x] 5.3 `applySteps`: keep the dependency order, and in each slot apply either the create or the update as committed steps.
  - **Table:** `updateMetadata`, then one `patchSchema` with real DDL.
  - **Pipeline:** the disable patch, then the re-declaration.
  - Record `updatedTables` / `updatedPipelines`, and give each entry `status` `updated`.
- [x] 5.4 `compensate`: revert updated objects in reverse order, interleaved with the existing deletes, pipelines first.
  - **Pipeline:** re-declare from `existing`, then restore `enabled`.
  - **Table:** restore metadata and column metadata, and drop the columns this import added, in one patch.
  - A reverted entry is `rolled_back`; a refused revert is `rollback_failed`.
  - All of it runs inside the open `AuditImportScope`.
- [x] 5.5 Extend the `catalog_import` log event's object list with each object's action.

## 6. Tests

- [x] 6.1 `CatalogImportPlannerTest`. One test for each of:
  - a column added; a metadata update; a re-declaration;
  - an extra target column kept; an order-only difference is `SKIP`;
  - `sensitive` true→false kept and false→true applied;
  - each refusing field `FAIL`s; a `kind` or `target` change `FAIL`s; a hidden column `FAIL`s only when there are changes;
  - every `advanced` member kept, an unset `advanced` member kept unset, `trigger.cron` kept, `trigger.group_by` and
    `transform.params` from the bundle;
  - the binding kept when the presets are equal and taken when the preset changed;
  - `ttl` kept;
  - `env_specific` `target_value` / `kept`, plus the wider list on a create;
  - `SKIP_IF_EXISTS` lists the `changes`;
  - the effects, per kind and enabled state.
- [x] 6.2 `CatalogTransferIntegrationTest`: replace `OVERWRITE refused` (around lines 239-247) with the round trip. Mutate the
  exported fixture: add a nullable column to `cx_sent`, change a description, change a `cx-sent-live` output expression, change
  `cx-rollup`'s filter while it is enabled, and set a local cron on the target. Then import with `OVERWRITE` and assert:
  - the `GET`s match the bundle, apart from the kept items;
  - `cx-rollup` is disabled; `cx-sent-live` is still enabled, one or more generations higher;
  - the local cron is kept;
  - `assertImportTaggedEverythingItWrote`;
  - a second `OVERWRITE` run is all `SKIP` with no new revision.
- [x] 6.3 `CatalogTransferIntegrationTest`: a stray ClickHouse column blocks the second table's `ADD`. The first table's update is
  reverted (column gone, description restored) and `outcome` is `rolled_back`. A rerun after removing the stray column updates both.
  Reuse the pattern of the DDL-failure test (around line 292).
- [x] 6.4 `CatalogTransferAuthIntegrationTest`: `403` without `TABLE_MODIFY` on the updated table, and success with a `modify`
  grant; `403` setting `sensitive` without `TABLE_SENSITIVE`; `403` re-declaring without `REGISTRY_ADMIN`; the hidden-column
  `FAIL`.
- [x] 6.5 Fix the tests that pin `resolution_policy_unsupported` or an all-`SKIP` `OVERWRITE` expectation:
  `AlignedEnrichmentIntegrationTest`, `AppRoutePipelineIntegrationTest`, `GroupEnrichmentIntegrationTest` and
  `DefaultExceptionHandlerBadRequestTest`.
- [x] 6.6 A test for each new spec scenario that has none yet. Check every `#### Scenario` of the three delta specs against a test.

## 7. Docs and specs

- [x] 7.1 `docs/catalog-promotion.md`: add step 8, "Re-promoting a changed bundle". It covers:
  - preview with `OVERWRITE`;
  - read `changes`, `not_applied` and `effects`;
  - re-arm the disabled aggregates in dependency order;
  - backfill when `existing_rows_not_rederived` appears;
  - target-owned fields are kept.
  - Also reword the places that say an import never changes an existing object.
- [x] 7.2 `docs/recommended-catalog/README.md`: add an "Updating an installation" section (re-import with `OVERWRITE`, read the
  preview, re-arm, backfill if the effects say so). Reword "objects that already exist are kept unchanged".
- [x] 7.3 Regenerate `docs/openapi.json` and check that the new fields and enum values appear.
- [x] 7.4 At archive, update the Purpose of `openspec/specs/catalog-export-import/spec.md`, which says import never changes an
  existing object. A delta spec cannot carry it.
- [x] 7.5 Confirm that no config property is added, so `docs/configuration.md` is unchanged.
  - Confirm that `docs/*.md` agree with the spec scenarios: the `changes` / `not_applied` / `effects` vocabulary, the `updated`
    status, and the removed error code.
  - Run `.claude/skills/pr-ready/hygiene-sweep.pl`.

## 8. Verification

- [x] 8.1 End-to-end OVERWRITE round trip against real ClickHouse and Postgres, covered by
  `CatalogTransferIntegrationTest.overwriteUpdatesWhatChangedKeepsTargetOwnedFieldsAndConverges`: a new column, changed
  descriptions, a changed enrich expression and an enabled aggregate are updated, and a second run is all `SKIP`.
- [x] 8.2 `openspec validate add-catalog-import-overwrite --strict`.
- [x] 8.3 `./gradlew clean build` passes.
