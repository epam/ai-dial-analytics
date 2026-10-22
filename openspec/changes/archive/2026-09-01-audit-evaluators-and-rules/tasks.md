## 1. Revert: Evaluator / EvaluatorVersion audit scope

- [x] 1.1 Remove `@Audited` from `EvaluatorEntity` and `EvaluatorVersionEntity`. Do not touch any other
      member of either class, `EvaluatorService`, `EvaluatorRepository`/`EvaluatorVersionRepository`,
      `EvaluatorMapper`, or any evaluator DTO — only the audit annotation goes.
- [x] 1.2 Remove the `Evaluator`/`EvaluatorVersion` constants from `ActivityResourceType`.
- [x] 1.3 Remove the `EvaluatorEntity`/`EvaluatorVersionEntity` cases from `AuditResourceMapper.resolve()`,
      and the `EvaluatorVersion` id-prefix case from `parentOf()`'s `switch`.
- [x] 1.4 Delete `AuditEvaluatorSnapshotService` entirely.
- [x] 1.5 Remove `EvaluatorController`'s `GET /{name}/revision/{revision}` route, its `parseRevision`
      helper, and the `AuditEvaluatorSnapshotService` field/constructor dependency. Every other route on
      this controller is unchanged.
- [x] 1.6 Edit `V32__audit_evaluators_and_rules.sql` to drop the `evaluator_aud`/`evaluator_version_aud`
      `CREATE TABLE`/index statements, keeping only `rule_aud`; rename the file to
      `V32__audit_rule.sql` and correct its header comment. Nothing has shipped, so this is an in-place
      edit, not a new "down" migration.
- [x] 1.7 Remove the evaluator-specific unit test cases from `AuditResourceMapperTest` (the
      `mapsEvaluatorToEvaluatorResource`/`mapsEvaluatorVersionToCompoundResourceId`/
      `readsTheOwningEvaluatorOffVersionResource`/`evaluatorResourceHasNoOwningEvaluator`-style tests)
      and `AuditRevisionListenerTest` (the `Evaluator`/`EvaluatorVersion` parent-linking cases). Add
      `EvaluatorEntity` back to `AuditResourceMapperTest.dropsEntitiesOutsideTheAuditedSurface`'s
      excluded-case list.
- [x] 1.8 Remove `recordsCreateUpdateAndDeleteOfEvaluator` and
      `evaluatorVersionIsCreateOnlyAndLinksToItsEvaluator` from `AuditTrailIntegrationTest`.
- [x] 1.9 Remove the evaluator create/re-register/snapshot tests from `AuditActivityFeedIntegrationTest`
      (`evaluatorCreateRecordsOneEvaluatorAndOneVersionActivity`,
      `reRegisteringAnEvaluatorRecordsAnUpdateAndNewVersionCreate`,
      `evaluatorSnapshotReturnsTheLatestVersionAsItStoodAtThatRevision`,
      `evaluatorSnapshotBeforeItExistedIsNotFound`).
- [x] 1.10 Remove `theEvaluatorSnapshotRouteMatchesTheLiveReadsAuthorization` from
      `AuditTrailAuthIntegrationTest`.
- [x] 1.11 Confirm `EvaluatorRegistryIntegrationTest` needs no change and still passes unmodified —
      it exercises no audit surface.

## 2. Add: Pipeline entity auditing

- [x] 2.1 Add `@Audited` to `PipelineEntity`.
- [x] 2.2 Add `Pipeline` constant to `ActivityResourceType`.
- [x] 2.3 Add `AuditResourceMapper.resolve()` case: `PipelineEntity` → `(Pipeline, name)`. No
      `parentOf()` case — a pipeline has no same-transaction parent shape (design.md §3).

## 3. Add: Pipeline dependent-delete cascade fix

- [x] 3.1 Add `PipelineTargetCascade` interface (`service/table/`) with
      `deletePipelineTargetingTable(String target)`, mirroring `RuleTargetEnrichmentCascade` exactly
      (including the "why an interface" reasoning — keeps `service.table` from depending on
      `service.pipeline`).
- [x] 3.2 Implement it on `PipelineService`: `PipelineRepository.findByTarget` (already exists) +
      explicit `pipelines.delete(...)` + flush, `@Transactional(propagation = Propagation.MANDATORY)`.
- [x] 3.3 Wire it into `TableCatalogService.delete()`, inside the existing
      `if (entity.getType() == TableType.SOURCE)` block, before the table is dropped. Update the
      method's stale comment that currently states the cascade is relied upon for a pipeline's target.
- [x] 3.4 Confirm the existing `SOURCE`-typed pre-checks (dependents, rule read-sources, pipeline
      inputs) are unaffected by this addition.

## 4. Add: SavedQuery entity auditing

- [x] 4.1 Add `@Audited` to `SavedQueryEntity`.
- [x] 4.2 Add `SavedQuery` constant to `ActivityResourceType`.
- [x] 4.3 Add `AuditResourceMapper.resolve()` case: `SavedQueryEntity` → `(SavedQuery, id)`. No
      `parentOf()` case and no cascade fix (`saved_query` has no FK to `table_definition`).

## 5. Migration

- [x] 5.1 Confirm `V32` still exists after task 1.6's edit and now creates only `rule_aud`. Check for a
      migration-number collision on the *next* number before claiming it — a sibling branch may have
      taken it since.
- [x] 5.2 Add a new migration (`pipeline_aud`, `saved_query_aud`) per design.md §1a: every current
      column of each entity mirrored, nullable, PK `(rev, <live pk>)`, JSON columns as `jsonb`
      (`spec` for pipeline; `query`, `time_intent`, `chart` for saved query), matching indices to the
      existing `V27`/(narrowed) `V32` pattern.

## 6. Add: Rule, Pipeline, SavedQuery snapshot endpoints

- [x] 6.1 Factor `AuditSnapshotService`'s two private guard methods (`requireExistingRevision`,
      `requireCreationWithinTheTrail`) into a shared package-private `AuditSnapshotGuards`
      (`service/audit/`), and update `AuditSnapshotService` itself to use it — per design.md §6, this
      collapses what would otherwise be four near-identical copies into one.
- [x] 6.2 Add `RuleMapper.toRawDto(RuleEntity)`, ignoring `evaluator`/`grainKey`/`versionColumn` and
      mapping `source` from `sourceTable` — needed because `RuleMapper.toDto` expects a resolved
      `ResolvedRule`, unlike Pipeline/SavedQuery's mappers.
- [x] 6.3 Add `AuditRuleSnapshotService` (`service/audit/`), using `AuditSnapshotGuards`, returning
      `RuleEntity`. Add `GET /v1/rules/{id}/revision/{revision}` to `RuleController`
      (`@Operation`-documented like `TableController.snapshot()`), mapping via `RuleMapper.toRawDto`.
      No authorization annotation of its own — inherits the catch-all (Rule's bar is unchanged).
- [x] 6.4 Add `AuditPipelineSnapshotService` (`service/audit/`), using `AuditSnapshotGuards`, returning
      `PipelineEntity`. Add `GET /v1/pipelines/{name}/revision/{revision}` to `PipelineController`,
      mapping via the existing `PipelineMapper.toDto`. Gate it to `FULL_ADMIN` (design.md §7) —
      matching this controller's other routes' `@FullAdminOnly`.
- [x] 6.5 Add `AuditSavedQuerySnapshotService` (`service/audit/`), using `AuditSnapshotGuards`,
      returning `SavedQueryEntity`. Add `GET /v1/saved-queries/{id}/revision/{revision}` to
      `SavedQueryController`, mapping via the existing `SavedQueryMapper.toDto`. Gate it per
      design.md §7's owner/common/full-admin rule, resolved against the resource's *current* stored
      state.

## 7. Add: resource-specific authorization narrowing in the feed

- [x] 7.1 `AuditActivityService`: add the `Pipeline` full-admin-only content filter (design.md §7) —
      a `Pipeline`-typed row appears in a feed result only for a `FULL_ADMIN` caller; every other
      caller's request still succeeds with those rows simply absent.
- [x] 7.2 `AuditActivityService`: add the `SavedQuery` visibility predicate — a correlated `EXISTS`
      subquery against `SavedQueryEntity` (current `scope = 'common'` OR current `owner_id = caller`),
      bypassed for `FULL_ADMIN`, composed with the existing filter-derived `Specification` so
      `total`/`totalPages` stay correct. Confirm the `none`-security-mode no-op path (reuse
      `SavedQueryAccess`'s "fails open" pattern rather than re-deriving it).
- [x] 7.3 Confirm neither narrowing affects `Table`/`TableColumn`/`Rule` results.

## 8. Tests

- [x] 8.1 Unit test `AuditResourceMapper.resolve()` for `PipelineEntity`/`SavedQueryEntity`.
- [x] 8.2 Integration test (`AuditTrailIntegrationTest`): Create/Update/Delete of a pipeline and of a
      saved query each record the corresponding activity with no parent, mirroring the existing Rule
      tests.
- [x] 8.3 Integration test: deleting a `SOURCE` table that is a pipeline's target records a `Delete`
      for the table, its columns, and the pipeline, all sharing one revision (mirroring
      `deletingTableTargetEnrichmentRecordsItsRule`).
- [x] 8.4 Integration test (`AuditActivityFeedIntegrationTest`): pipeline and saved-query
      create/update/delete appear in the feed; the three new snapshot endpoints return the resource as
      it stood at a past revision, and 404 the same way the table snapshot route does (revision never
      issued, resource predates the trail, revision non-numeric/non-positive).
- [x] 8.5 Integration test: a `READ_ONLY_ADMIN` caller's unfiltered feed request succeeds with
      `Pipeline` activities absent; a `FULL_ADMIN` caller sees them; the pipeline snapshot route
      answers `403` for `READ_ONLY_ADMIN` and `200` for `FULL_ADMIN`.
- [x] 8.6 Integration test: a saved query's owner and `FULL_ADMIN` see its `personal`-scope activity in
      the feed and via its snapshot; a different `READ_ONLY_ADMIN` caller does not (feed returns `200`
      with the activity absent; snapshot returns `403` or `404` per design — confirm which against the
      live read's own contract); any role-holding caller sees a `common`-scope saved query's activity;
      after deletion, only `FULL_ADMIN` sees the deleted saved query's activity, including its former
      owner.
- [x] 8.7 Remove the evaluator-specific tests listed in section 1 and confirm the remaining suite is
      internally consistent (no leftover references to removed classes/constants).

## 9. Documentation and spec

- [x] 9.1 `openspec/config.yaml`'s "## Auditing" paragraph: update the audited-entity list to
      table/column/rule/pipeline/saved-query (drop evaluator/evaluator-version, which this change
      re-adds to the *unaudited* side alongside the evaluator registry generally).
- [x] 9.2 `README.md`'s "Audit trail" bullet: update the described coverage and the three new
      `.../revision/{revision}` endpoints; state the `Pipeline`/`SavedQuery` authorization narrowing
      inline or via a link to `docs/configuration.md` if it grows too long for a bullet.
- [x] 9.3 Confirm no `docs/configuration.md` update is needed — this change adds no configuration
      property.

## 10. Verification

- [x] 10.1 `openspec validate audit-evaluators-and-rules --strict` after every artifact edit.
- [x] 10.2 `./gradlew compileJava compileTestJava checkstyleMain checkstyleTest -q` before running
      tests.
- [x] 10.3 `./gradlew clean build` passes (compile, checkstyle, full test suite) — retry through this
      environment's known intermittent Docker/Testcontainers flakiness rather than treating a hang or
      transport error as a code signal, but do not skip this step.
