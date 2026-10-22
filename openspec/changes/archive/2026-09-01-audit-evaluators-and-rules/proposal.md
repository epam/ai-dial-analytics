## Why

`audit-trail` currently covers only `table_definition` and `column_mapping`. Its own spec
deliberately deferred the enrichment control plane, aggregate pipelines, and saved queries as later
increments once those surfaces stabilized, and the mapper (`AuditResourceMapper`) was built as an
exhaustive switch specifically so extending coverage would be cheap. The rule registry has
stabilized and is the reference implementation this change follows for the two remaining registries:
aggregate pipelines and saved queries. Mutations to rules, pipelines, and saved queries — who changed
a governed rule, who repointed a pipeline's target, who edited or deleted a saved query — are
currently unattributable (for rules and pipelines) or not yet built (for saved queries), which is the
same blind spot `audit-trail` was built to close for the catalog.

The evaluator registry (`EvaluatorEntity`, `EvaluatorVersionEntity`) is explicitly **not** part of
this increment. An earlier draft of this change added evaluator auditing alongside rule auditing;
that scope is reverted here before anything ships. Evaluators remain deferred, alongside aggregate
pipelines and saved queries, in the original spec's sense — this change narrows to exactly the
registries that are ready now: rules, pipelines, and saved queries.

## What Changes

- Add Hibernate Envers auditing (`@Audited`) to `RuleEntity`, `PipelineEntity`, and `SavedQueryEntity`,
  following the existing `table_definition`/`column_mapping` pattern: a full-state snapshot per
  revision plus a feed entry, inside the caller's transaction. `EvaluatorEntity`/`EvaluatorVersionEntity`
  are explicitly **not** annotated — see the note above.
- Add a migration creating `rule_aud`, `pipeline_aud`, and `saved_query_aud` shadow tables, mirroring
  each entity's current columns (including its JSON columns as `jsonb`), the same physical shape as
  `column_mapping_aud`.
- Extend `AuditResourceMapper` and `ActivityResourceType` with `Rule`, `Pipeline`, and `SavedQuery`
  resource types.
- Generalize parent-activity linking to cover a `Rule` activity produced by a source table's
  schema-driven generation bump, parented to that source table's activity in the same revision.
- Close two dependent-delete gaps: `rule.target_enrichment` and `pipeline.target` both cascade on
  delete from `table_definition` at the database level, which today silently removes an unaudited
  `Rule`/`PipelineEntity` row when its target table is deleted. Once both are audited, deleting a
  table must delete its dependent rule/pipeline explicitly through their own repositories first
  (mirroring the existing `column_mapping` fix in `TableCatalogService.delete()`), so the deletion is
  captured rather than lost to an un-tracked cascade. `saved_query` has no such FK — no cascade fix
  needed there.
- Add three point-in-time read endpoints, mirroring `GET /v1/tables/{name}/revision/{revision}`:
  `GET /v1/rules/{id}/revision/{revision}`, `GET /v1/pipelines/{name}/revision/{revision}`, and
  `GET /v1/saved-queries/{id}/revision/{revision}`, each returning the resource's own stored fields
  (no cross-entity resolution) as they stood at that revision.
- Narrow the activity feed and the two new snapshot endpoints per resource type, rather than assuming
  every resource shares the catalog's `FULL_ADMIN`-or-`READ_ONLY_ADMIN` read bar: `Pipeline` activity
  and snapshots require `FULL_ADMIN` specifically, matching `PipelineController`'s own read routes;
  `SavedQuery` activity and snapshots follow the same owner/`common`-scope rule
  `SavedQueryRepository.findVisible` already applies to the live read, with a stated exception for a
  deleted saved query (visible to `FULL_ADMIN` only, including to its former owner — see design.md).
  `Rule`'s bar is unchanged (matches the catalog's existing bar, since `RuleController`'s reads are
  not more restricted than that).
- No change to rule/pipeline/saved-query mutation semantics, endpoints, or request/response shapes for
  their existing routes — this is purely additive/observational auditing layered on existing write
  paths, plus the three new read-only snapshot routes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `audit-trail`: add `rule`, `pipeline`, and `saved_query` to the set of audited resources (rule
  unchanged from the prior draft of this change); add scenarios for pipeline and saved-query
  create/update/delete, the pipeline dependent-deletion-on-table-delete case, and the three new
  point-in-time read endpoints; add resource-specific authorization narrowing for `Pipeline` (full
  admin only) and `SavedQuery` (owner/common-scope/full-admin, matching its live read) rather than
  assuming every resource inherits the catalog's own bar. The evaluator registry remains outside this
  capability's coverage, alongside aggregate pipelines' and saved queries' now-removed blanket
  exclusion — narrowed to name only evaluators.

## Impact

- **Code**: `RuleEntity` (`@Audited`, unchanged from the prior draft), `PipelineEntity`,
  `SavedQueryEntity` (new `@Audited`); `EvaluatorEntity`/`EvaluatorVersionEntity` (`@Audited` removed);
  `AuditResourceMapper`, `ActivityResourceType` (resource cases updated: `Evaluator`/`EvaluatorVersion`
  removed, `Pipeline`/`SavedQuery` added); new `PipelineTargetCascade` interface (`service/table/`,
  mirroring `RuleTargetEnrichmentCascade`), implemented by `PipelineService`; `TableCatalogService.delete()`
  (wires the new cascade call); new `AuditPipelineSnapshotService`/`AuditSavedQuerySnapshotService`
  (`service/audit/`, alongside the existing `AuditSnapshotService`/`AuditRuleSnapshotService`, sharing
  `AuditSnapshotGuards`); `AuditEvaluatorSnapshotService` removed; `PipelineController`/
  `SavedQueryController` (new snapshot routes); `EvaluatorController` (snapshot route removed);
  `AuditActivityService` (new resource-specific authorization narrowing for `Pipeline`/`SavedQuery`).
- **Schema**: `V32` narrowed to `rule_aud` only (evaluator/evaluator-version tables removed before
  anything shipped); a new migration adds `pipeline_aud` and `saved_query_aud`.
- **APIs**: three new `GET .../revision/{revision}` routes. `POST /v1/activities` and
  `GET /v1/activities/{activityId}` begin surfacing entries for `Rule`/`Pipeline`/`SavedQuery` with no
  contract change to the request/response shape, though `Pipeline`/`SavedQuery` results are now
  narrowed per caller as described above.
- **Out of scope**: `EvaluatorEntity`/`EvaluatorVersionEntity` remain unaudited (reverted from an
  earlier draft of this change); evaluator/evaluator-version deletion remains unreachable (no code
  path exists today) and is not being added; reconstructing a deleted saved query's historical
  visibility from `saved_query_aud` is explicitly not attempted.
