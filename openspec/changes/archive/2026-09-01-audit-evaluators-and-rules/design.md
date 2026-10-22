## Context

`audit-trail` (see proposal.md for motivation) is built around three pieces that this change extends
rather than replaces:

- `AuditResourceMapper` (`service/audit/AuditResourceMapper.java`) — an exhaustive, non-reflective
  switch from `(entityClass, entityId)` to `(ActivityResourceType, resourceId)`, plus
  `activityType(RevisionType)` and `parentOf(AuditResource)` for parent linkage. Unmapped entities are
  silently dropped, not thrown, by design.
- `AuditRevisionListener` (`service/audit/AuditRevisionListener.java`) — an
  `EntityTrackingRevisionListener` that stamps author identity on `newRevision`, and on
  `entityChanged` calls the mapper, deduplicates within the revision's `AuditActivityEntity`
  collection, and links parent/child activities. It only knows the changed entity's **class and id**,
  never its full state — Envers does not pass the entity instance to this callback.
- `TableCatalogService.delete()` (`service/table/TableCatalogService.java`) — already deletes
  `column_mapping` rows explicitly through `ColumnMappingRepository.deleteByTableName`, and (from this
  change's Rule work) the target-enrichment rule through `RuleTargetEnrichmentCascade`, before
  deleting the table itself, specifically so each removal fires a Hibernate event Envers can see. The
  same method currently has **no** equivalent step for a pipeline whose `target` is the table being
  deleted (a `SOURCE`-typed table): that path relies on `pipeline.target REFERENCES
  table_definition(name) ON DELETE CASCADE` (`V20__aggregate_pipeline.sql`), and the method's own
  comment states the assumption this change makes wrong: *"A pipeline whose TARGET this is goes with
  the table (its FK cascades): it exists only to fill it."*

This revision of the change **reverts** the evaluator-registry scope an earlier draft added
(`EvaluatorEntity`/`EvaluatorVersionEntity` audited, with an `Evaluator`↔`EvaluatorVersion`
parent-linking shape and an `AuditEvaluatorSnapshotService`) and **adds** `PipelineEntity` and
`SavedQueryEntity`. Rule's own decisions (§2, §3 for the bulk-bump parent shape, §4 for the cascade
fix) are unchanged from the prior draft and kept here for continuity; only the evaluator-specific
decisions are removed, and Pipeline/SavedQuery decisions are new (§1a, §4a, §6, §7).

`PipelineMapper.toDto(PipelineEntity)` and `SavedQueryMapper.toDto(SavedQueryEntity)` already convert
directly from the raw entity — unlike `RuleDto`, which needed a new `toRawDto` mapping because
`RuleMapper.toDto` expects a resolved `ResolvedRule`. Both new snapshot endpoints reuse the existing
`toDto()` methods as-is.

`PipelineController`'s `GET` routes are `@FullAdminOnly` — unlike `Table`/`Rule`, whose reads fall to
the generic `/v1/**` catch-all (`FULL_ADMIN` or `READ_ONLY_ADMIN`). `SavedQueryEntity` has per-owner
visibility (`SavedQueryRepository.findVisible`: a `common` row is visible to any role-holding caller,
a `personal` row only to its owner). The generic feed and snapshot machinery has no per-resource-type
or per-row authorization narrowing today — extending it to cover both is the largest and riskiest
part of this change (§7).

## Goals / Non-Goals

**Goals:**
- Extend the mapper/listener/migration triad to cover `PipelineEntity` and `SavedQueryEntity` with
  the same guarantees `table_definition`/`column_mapping`/`RuleEntity` already have: full-state
  snapshots, transaction-consistent revisions, and no `ON DELETE CASCADE` producing an invisible
  removal.
- Add point-in-time read endpoints for rule, pipeline, and saved query, mirroring
  `GET /v1/tables/{name}/revision/{revision}`.
- Ensure the audit surface for `Pipeline` and `SavedQuery` never exposes more than their own live
  reads do — the exact principle the original design stated and that a blanket "same bar as the
  catalog" assumption would violate for both.
- Revert `EvaluatorEntity`/`EvaluatorVersionEntity` auditing cleanly, leaving zero trace in code,
  migration, or spec, while leaving every non-audit evaluator behavior untouched.

**Non-Goals:**
- No change to rule, pipeline, or saved-query mutation semantics, validation, or existing endpoints —
  only the three new read-only snapshot routes are added.
- No audit coverage for the evaluator registry (`EvaluatorEntity`/`EvaluatorVersionEntity`) — reverted
  from an earlier draft, deferred alongside its original "natural second increment" framing.
- No reconstruction of a deleted saved query's historical visibility from `saved_query_aud` — see §7.
- No new delete capability for evaluators, pipelines, or saved queries — existing delete paths are
  unchanged; only their *audit visibility* changes for pipeline and saved query.

## Decisions

### 1. Entity annotations and migration — Rule (unchanged from the prior draft)

`@Audited` on `RuleEntity`, no `@NotAudited` on any field, matching `TableDefinitionEntity`'s
all-or-nothing precedent.

`rule_aud(rev, revtype, id, name, evaluator_name, evaluator_version, target_enrichment, source_table, trigger_kind, trigger_cron, group_by, ready_when, member_select, filter_sql, input_bindings, output_bindings, sampling, cadence, batch_scan_limit, batch_chunk, rate_rpm, priority, enabled, generation, created_at, updated_at, PRIMARY KEY(rev, id))`
— `ready_when`, `member_select`, `input_bindings`, `output_bindings` as `jsonb`.

### 1a. Entity annotations and migration — Pipeline, SavedQuery (new)

`@Audited` on `PipelineEntity` and `SavedQueryEntity`, same all-or-nothing precedent.

- `pipeline_aud(rev, revtype, name, kind, target, input_table, filter_sql, spec, trigger_cron, enabled, cursor_version, cursor_identity, next_run_at, last_run_at, last_error, has_more, generation, created_at, updated_at, PRIMARY KEY(rev, name))`
  — `spec` as `jsonb`.
- `saved_query_aud(rev, revtype, id, name, description, tag, scope, owner_id, owner_email, source, query, sql_text, time_intent, result_view, chart, generation, created_at, updated_at, PRIMARY KEY(rev, id))`
  — `query`, `time_intent`, `chart` as `jsonb`, matching the live columns' `SqlTypes.JSON`-typed `TEXT`
  storage (the entity holds these as JSON text rather than a mapped object, per `SavedQueryEntity`'s
  own Javadoc on Jackson 2/3 incompatibility — the `_aud` mirror stores the same physical shape).

**Revert**: the `evaluator_aud`/`evaluator_version_aud` tables an earlier draft of this change added
to `V32` are removed from that migration entirely — not dropped by a follow-up migration, since
nothing has shipped yet. `V32` is renamed to reflect its narrowed scope (`rule_aud` only); a new
migration adds `pipeline_aud`/`saved_query_aud`.

### 2. Resource mapping and id formatting

Extend `ActivityResourceType` with `Rule`, `Pipeline`, `SavedQuery` (the `Evaluator`/`EvaluatorVersion`
constants an earlier draft added are removed). Extend `AuditResourceMapper.resolve()`:

- `RuleEntity` → `(Rule, id)`.
- `PipelineEntity` → `(Pipeline, name)`.
- `SavedQueryEntity` → `(SavedQuery, id)`.

None of the three needs composite-id formatting (unlike the reverted `EvaluatorVersionEntity` case) —
all three have a plain `@Id` field.

### 3. Parent-activity linkage (unchanged from the prior draft; the evaluator shape is removed)

`Rule` (only when the change is an `Update` produced by a source's version-column repoint) → `Table`
of that source. The parent's identity is not encoded in the rule's id, so `AuditResourceMapper` loads
the rule (`RuleRepository.findById`, `@Lazy` — see below) and computes its effective source the same
way `RuleService#sourceOf` does. Neither `Pipeline` nor `SavedQuery` has an analogous same-transaction
parent shape — a pipeline is never bumped by another entity's change, and a saved query has no
external trigger for its own mutation — so `parentOf()` gains no new case for either.

**Load-bearing fix carried forward**: `AuditResourceMapper`'s `RuleRepository`/`TableDefinitionRepository`
constructor parameters are `@Lazy` (`org.springframework.context.annotation.Lazy`), because the mapper
is reached from the Envers listener chain *while* Hibernate is still building the
`entityManagerFactory` bean, and a Spring Data JPA repository is itself backed by that same factory —
an eager dependency here is a circular-dependency startup failure, not a style concern. This applies
regardless of the evaluator revert; it is purely about `Rule`'s parent lookup, which is unchanged.

`AuditRevisionListener.link()`'s backward pass (an entity's own activity back-filling
already-recorded parentless children) stays generalized to ask the mapper's `parentOf` for any
resource type rather than special-casing `Table` — the reverted `Evaluator` backward-pass case simply
never fires now that nothing maps to it, with no code change needed beyond removing the mapper case
itself.

### 4. Closing the `rule.target_enrichment` cascade gap (unchanged from the prior draft)

`RuleTargetEnrichmentCascade` (`service/table/`), implemented by `RuleGenerationService`, called from
`TableCatalogService.delete()` when the table being deleted is an `ENRICHMENT`. See the prior draft's
reasoning: `service.table` never depends on `service.enrichment`, so this is reached through an
interface rather than a direct `RuleRepository` import.

### 4a. Closing the `pipeline.target` cascade gap (new)

The same problem, for the same reason: `pipeline.target REFERENCES table_definition(name) ON DELETE
CASCADE` (`V20__aggregate_pipeline.sql`) would silently remove a (now-audited) `PipelineEntity` row
with no Hibernate event once `PipelineEntity` is `@Audited`.

Add `PipelineTargetCascade` (`service/table/`), one method: `deletePipelineTargetingTable(String
target)` — deletes the pipeline whose `target` is `target`, if one exists, through
`PipelineRepository.findByTarget` (already exists — no new repository method needed) +
`pipelines.delete(...)` + flush, `@Transactional(propagation = Propagation.MANDATORY)` so it joins
the deleting transaction's revision rather than opening its own. `PipelineService` implements it.

`TableCatalogService.delete()` calls it inside the existing `if (entity.getType() ==
TableType.SOURCE)` block — a pipeline's target is documented as always a `SOURCE`-typed table
(`PipelineEntity`'s own Javadoc: "a `source` written `upsert_by_key`") — alongside the block's other
`SOURCE`-only pre-checks, and updates the stale comment that currently states the cascade is relied
upon.

**Note, not a decision**: unlike the rule case (where an `ENRICHMENT` can have at most one rule via a
`UNIQUE` constraint the same as a pipeline's `target`), no additional governance gate exists here to
prevent the delete from proceeding — the pipeline is simply removed alongside the table, exactly as
the (now-corrected) comment already described the intended end state, just routed through the
repository instead of the raw FK.

### 5. Rule authorization (unchanged): matches the catalog's existing bar

`RuleController`'s `GET` routes carry no `@FullAdminOnly`/`@PreAuthorize` and fall through to the same
`/v1/**` catch-all (`FULL_ADMIN` or `READ_ONLY_ADMIN`) that already gates `/v1/activities` and table
reads — no change needed for `Rule`'s feed or snapshot authorization.

### 6. New snapshot services for Rule, Pipeline, SavedQuery, and a shared guard helper

Three new services in `service/audit/`, one per newly-audited entity with a real case for
point-in-time reads: `AuditRuleSnapshotService`, `AuditPipelineSnapshotService`,
`AuditSavedQuerySnapshotService`. Each follows `AuditSnapshotService`'s existing shape:
`AuditReader.find(EntityClass, id, revision)`, a "creation within the trail" guard, a
revision-existence guard, returning the raw entity (not a resolved view) for the controller to map.

The two revision-validity checks (`requireExistingRevision`, `requireCreationWithinTheTrail`) were
originally private methods on `AuditSnapshotService` alone; with three more services needing them,
they are factored into a shared `AuditSnapshotGuards` (`service/audit/`, package-private), which
`AuditSnapshotService` itself is also updated to use — collapsing four near-identical copies into one.

`Pipeline`'s and `SavedQuery`'s snapshot controllers reuse the existing `PipelineMapper.toDto`/
`SavedQueryMapper.toDto` directly (see Context — both already take the raw entity). `Rule`'s
controller uses a new `RuleMapper.toRawDto(RuleEntity)` method (`@Mapping` ignoring `evaluator`,
`grainKey`, `versionColumn`, which have no analog on the raw entity, and mapping `source` from
`sourceTable`), since `RuleMapper.toDto` expects a resolved `ResolvedRule`.

**Evaluator revert**: `AuditEvaluatorSnapshotService` and `EvaluatorController`'s
`GET /{name}/revision/{revision}` route are removed entirely, along with their tests. Every other
`EvaluatorController` route, `EvaluatorService`, and the evaluator DTOs/mapper are untouched.

### 7. Resource-specific authorization narrowing (new — the highest-risk part of this change)

The activity feed (`AuditActivityService`) and the snapshot endpoints were, before this change,
uniformly gated at the `/v1/**` catch-all with no resource-type or per-row awareness. Two of the newly
audited resources need narrower visibility than that catch-all:

- **Pipeline**: its own live reads require `FULL_ADMIN` specifically. `AuditActivityService` gains a
  role check (reusing `SecurityClaimsExtractor`/the same role-lookup `SavedQueryAccess.requireWritable`
  already uses) applied as an additional predicate: a `Pipeline`-typed row is included in the feed
  result only for a `FULL_ADMIN` caller. This is a **content filter within an otherwise-successful
  response**, not an authorization denial — a `READ_ONLY_ADMIN` caller's unfiltered feed request still
  returns `200` with `Pipeline` rows simply absent, mirroring how the feed already silently omits rows
  that don't match a filter rather than erroring. The two new snapshot routes apply the same check
  directly (an explicit check or `@PreAuthorize`, since there is exactly one resource per request there
  rather than a page to filter), refusing with `403` when it fails — matching how the *existing* table
  snapshot route already requires exactly the live read's own bar.

- **SavedQuery**: visible to `FULL_ADMIN` unconditionally, to any caller when the *current*
  `saved_query.scope = 'common'`, or to the caller recorded as the *current* `saved_query.owner_id`.
  Implemented as an additional conjunctive `Specification<AuditActivityEntity>` predicate — a
  correlated `EXISTS` subquery against `SavedQueryEntity` keyed on `resourceId` — composed with the
  existing filter-derived specification `PageEntityMapper` already builds, so pagination/`total`/
  `totalPages` stay correct (a post-fetch filter would not: discarding rows after the DB has already
  computed `total`/`totalPages` for the wrong row count would make paging silently wrong). The two new
  snapshot routes apply the equivalent single-row check directly.

  **Alternative considered and rejected**: post-fetch filtering (load a page, discard rows the caller
  can't see). Rejected because it breaks `total`/`totalPages`/effective page size — the fields the
  existing "Total and page count returned" requirement already promises are correct, and a caller
  requesting `pageSize=20` could receive fewer rows than `total` implies with no way to page to the
  rest.

  **Stated trade-off, not an oversight**: the subquery reads the saved query's *current* state. A
  **deleted** saved query has no current row, so the `EXISTS` fails for every caller except
  `FULL_ADMIN` — including the query's own former owner, who could see its live history right up until
  deletion but loses feed/snapshot visibility the moment it's gone. Reconstructing "who could see this
  at each point in its history" from `saved_query_aud` (whose `scope`/`owner_id` could themselves have
  changed over the resource's lifetime) was considered and rejected: it would require resolving
  visibility per-activity against that activity's own revision's `saved_query_aud` row rather than one
  current-state check, a materially larger and slower query for a narrowing that only affects
  already-deleted resources' history.

- **`none` security mode**: both checks no-op (fail open) — `SavedQueryAccess` already documents this
  exact pattern ("the whole capability fails open... there is no owner to filter by and no role to
  gate on") and is reused rather than re-derived.

- **Rule and Table are unaffected**: neither gains a narrowing, since neither's own read bar is
  stricter than the feed's existing catch-all.

## Risks / Trade-offs

- **[Risk]** A future rule/pipeline/saved-query mutation path that bypasses the owning service (a bulk
  import, a maintenance script) would silently produce no activity, the same way an unmapped entity is
  silently dropped today. → **Mitigation**: none beyond what `table_definition` already relies on —
  the mapper's coverage is enforced by tests, and this change adds the same kind for the two new
  entities and their cascade paths.
- **[Risk]** The generic activity feed and snapshot machinery, previously resource-agnostic, now
  contains per-resource-type authorization logic (§7). This is new architectural surface with real
  security consequences if it drifts from the resources' own live-read rules as those evolve. →
  **Mitigation**: both narrowings are covered by dedicated authorization-parity tests (mirroring the
  existing table/rule ones), and the design deliberately keeps the two checks next to each other in
  `AuditActivityService` rather than scattered, so a future change to `PipelineController`'s or
  `SavedQueryAccess`'s own authorization is more likely to be noticed as needing a matching feed update.
- **[Risk]** The `saved_query`-current-state subquery means a deleted saved query's history becomes
  `FULL_ADMIN`-only, including to its former owner (§7's stated trade-off). → **Mitigation**: accepted
  and documented in the spec as intentional narrowing, not silently absorbed.
- **[Risk]** `pipeline_aud`/`rule_aud`/`saved_query_aud`'s JSON columns grow the audited snapshot size
  per revision compared to the flat `table_definition_aud`/`column_mapping_aud` rows. → **Mitigation**:
  none needed at current scale — `table_definition_aud.access` already establishes this shape, and
  mutation volume for these three registries is far lower than row-level ClickHouse writes (excluded
  from auditing entirely for exactly this kind of volume reason).

## Migration Plan

Additive only for the new coverage: one migration narrowed to `rule_aud`, a new migration adding
`pipeline_aud`/`saved_query_aud`, `@Audited` annotations, the two cascade-fix interfaces, the three new
snapshot services/routes, and the `AuditActivityService` authorization narrowing. The evaluator revert
removes code and migration content that had not shipped, so it carries no data-migration concern of
its own. No backfill for the newly audited entities — consistent with the original `audit-trail`
rollout and the existing "Historical table snapshots"/new "Point-in-time reads" requirements' treatment
of pre-existing resources. No rollback step beyond a standard migration revert.
