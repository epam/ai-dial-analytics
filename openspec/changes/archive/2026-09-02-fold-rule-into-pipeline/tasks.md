## 0. Pre-flight

- [x] 0.1 Re-check the migration number: `V33` is the head when this change is authored, so `V34` is free — but an
      in-flight branch may have claimed it. Confirm against `src/main/resources/db/migration/postgres/`
      before writing the file.
- [x] 0.2 Confirm `pipeline_kind_check` is PostgreSQL's auto-generated name for `V20`'s inline column
      CHECK, against a migrated catalog (`\d pipeline`), before writing a `DROP CONSTRAINT` that relies on
      it. `V15` already drops `rule_trigger_kind_check` the same way, so the precedent holds.
- [ ] 0.3 Confirm the existing rows the migration has to survive: AGGREGATE pipelines (snake_case names,
      all with a cron) and rules (hyphenated names, all `on_ingest` or `group`, so all with a NULL
      cron), no name collisions between them. Take a dump for verification step 9.2.
- [x] 0.4 **Never edit `V11`, `V20` or `V32`.** Their comments are checksummed; editing an applied
      migration aborts startup everywhere and a green local build does not catch it.

## 1. Persistence

- [x] 1.1 `V34__fold_rule_into_pipeline.sql` **Part A — widen `pipeline`**: replace
      `pipeline_kind_check` with `CHECK (kind IN ('AGGREGATE','ENRICH'))`; drop `NOT NULL` from
      `input_table` and `trigger_cron`; add `trigger_kind`, `trigger_group_by`, `ready_when`,
      `member_select`, `evaluator_name` (FK → `evaluator(name)`), `evaluator_version`, the five Class-1
      position columns (`materialized_through_version`, `materialized_through_identity`, `drained_at`,
      `history_rederived_at`, `materialized_generation`) and `legacy_rule_id`; backfill
      `trigger_kind = 'SCHEDULE'` for every existing row, then `SET NOT NULL`.
- [x] 1.2 Part A guards, carried over from `rule` verbatim because the five trigger fields stay real
      columns: `pipeline_input_required_unless_enrich` (one-way, `input_table IS NOT NULL OR kind =
      'ENRICH'`), `pipeline_trigger_kind_check`, `pipeline_cron_iff_schedule` (the biconditional as
      `V11` spells it), `pipeline_group_by_iff_group` (as `V15` spells it),
      `pipeline_member_select_only_group` (the one-way form as `V28` spells it — a group pipeline need
      not narrow its members).
- [x] 1.3 Part A's **two new** guards, which have no counterpart on `rule` to copy because they exist
      there as plain `NOT NULL`s that only make sense per-kind (design D-5):
      `pipeline_evaluator_iff_enrich` — `(kind = 'ENRICH') = (evaluator_name IS NOT NULL)`, without which
      an ENRICH pipeline with no evaluator is storable; and `pipeline_aggregate_is_scheduled` —
      `kind <> 'AGGREGATE' OR trigger_kind = 'SCHEDULE'`.
- [x] 1.4 Part B — move the four rules, keyed on `rule.name`, behind a `DO $$ … RAISE EXCEPTION $$`
      collision guard so a name clash aborts the migration rather than violating the PK.
      `target_enrichment` → `target`, `source_table` → `input_table`, `group_by` → `trigger_group_by`,
      `id` → `legacy_rule_id`, `kind` = `'ENRICH'`; `input_bindings`, `output_bindings`, `sampling`,
      `cadence`, `batch_scan_limit`, `batch_chunk`, `rate_rpm`, `priority` assembled into one
      `EnrichSpec` JSONB via `jsonb_build_object`. `enabled`, `generation`, `created_at`, `updated_at`
      and all five Class-1 position columns carry over **unchanged** — `generation` is the runner's only
      change token and resetting the position would unclamp every dependent rollup. `next_run_at`,
      `cursor_*`, `last_run_at`, `last_error`, `has_more` and `clamped_*` are left NULL/default;
      `rebuilt_at` takes the migration instant, as `V32` did.
- [x] 1.5 Part B must touch **no existing row's `spec` value** (design D-3). Assert it: the existing
      rollups' `spec` reads back byte-identically after the migration.
- [x] 1.6 Part C — `ALTER TABLE rule RENAME TO rule_legacy;` keeping its indexes and constraints, which
      are inert once nothing maps to it. Do **not** drop it: it is the only source of the cut-over's
      id→name mapping and the only in-database rollback reference.
- [x] 1.7 `PipelineEntity` absorbs `RuleEntity`'s fields. Relax `@Column(nullable = false)` on
      `inputTable` and `triggerCron` — Hibernate enforces it **at flush**, not only in generated DDL, so
      an ENRICH insert fails before it reaches PostgreSQL otherwise. `spec`, `target`, `kind`, `hasMore`,
      `rebuiltAt`, `generation`, `createdAt` and `updatedAt` stay non-null. Delete `RuleEntity`.
- [x] 1.8 `PipelineEntity.spec` becomes a `String` (still `@JdbcTypeCode(SqlTypes.JSON)`); add
      `PipelineSpecCodec` reading/writing `AggregateSpec` or `EnrichSpec` by `kind`, and
      `sealed interface PipelineSpec permits AggregateSpec, EnrichSpec` as the service-layer type. Both
      specs keep `@JsonIgnoreProperties(ignoreUnknown = true)`. Add `EnrichSpec`.
- [x] 1.9 `PipelineRepository` absorbs `RuleRepository`'s queries; delete `RuleRepository` and
      `RuleDeclaration` (its queries move to `PipelineDeclaration`). `findByName` collapses into
      `findById` (name *is* the id); `findByTargetEnrichmentIn` → `findByTargetIn`;
      `findBySourceTable` → `findByInputTable`.
- [x] 1.10 **`findDue` is kind-scoped to `AGGREGATE`** — the single most dangerous line in the change
      (design D-6). Belt and braces: `next_run_at` left NULL for ENRICH by 1.4, the kind filter in the
      query, **and** a kind assertion in `AggregateRefreshService.refresh`.
- [x] 1.11 Keep `findAllDeclarations()` a JPQL constructor projection into a **detached** record, and
      keep its javadoc: a managed entity inside a delete transaction flushes bogus `UPDATE`s.
- [x] 1.12 Carry the sync feed's ordering over deliberately: the enabled/`updated_since` finder orders
      `updated_at ASC, name ASC` (design D-4).
- [x] 1.13 The four `@Modifying` Class-1 position writes (`advanceMaterializedThrough`, `recordDrained`,
      `recordHistoryRederived`, `discardMarkOfOtherGeneration`) change only their entity name. Do **not**
      restructure them into entity mutations: loading the entity in a transaction that bulk-updates the
      same row silently loses the bulk write.

## 2. DTO and mapper

- [x] 2.1 Merge `CreateRuleRequest`/`RuleDto` into `PipelineRequests`/`PipelineDto` per the flat union:
      every existing AGGREGATE field keeps its spelling and position; `id` removed, `target_enrichment`
      → `target`, `source` → `inputs` (list of one, `null` meaning "follow my target's parent", response
      echoing the resolved value), `filter_sql` → `filter`, and `trigger_kind`/`trigger_cron`/`group_by`/
      `ready_when`/`member_select` nested under `trigger{}`. Top-level `group_by` stays the aggregate
      grain. Delete `CreateRuleRequest`, `RuleDto`, `RuleMapper`.
- [x] 2.2 `state` merges both projections with `@JsonInclude(NON_NULL)` hiding the other kind's members.
      `generation`, `created_at`, `updated_at` stay **top-level** — declaration facts, not runtime state.
- [x] 2.3 `unclamped_reads[].reason`: `no_rule` → `no_pipeline`, and the merged field documents **all
      four** (`no_pipeline`, `group_trigger`, `not_class1`, `foreign_axis`). `RuleDto` lists four today
      and `PipelineDto` only two; `EnrichmentFreshness.Clampability` has four, so `PipelineDto`'s list is
      a pre-existing documentation bug this closes.
- [x] 2.4 Remove **three** `@NotNull`s from `CreatePipelineRequest`, not two: `group_by` and `measures`
      (AGGREGATE-only) and **`inputs`** (a null input is load-bearing on the ENRICH side). `name`, `kind`
      and `target` keep theirs. The "required for AGGREGATE" half of `inputs` then lives only in the
      kind-dispatched validator and in `pipeline_input_required_unless_enrich`, so test both.
- [x] 2.5 Cross-kind field rejection: a field belonging to the other kind is **422**, never silently
      ignored.
- [x] 2.6 `Identifiers.requirePipelineName` — `^[a-z][a-z0-9_-]{0,63}$`, throwing
      `PipelineValidationException` (design D-1). This also fixes the exception type:
      `requireUserIdentifier` throws `TableValidationException` → `table_validation_failed`, so the error
      contract does not hold until the pipeline path throws its own. Leave the eight
      `requireUserIdentifier` call sites whose argument *is* a ClickHouse identifier alone.
- [x] 2.7 `trigger.kind` defaults to `SCHEDULE` when a body omits `trigger` or sends it with only
      `cron`, or every existing aggregate registration body becomes a 422 (design D-7). Keep `kind` a
      `String` on the wire so an unknown trigger kind stays a 422 governance failure rather than a 400
      binding error.

## 3. Service layer

- [x] 3.1 `PipelineKind.ENRICH.implemented = true`; `EXTERNAL` stays refused via `implementedCodes()`.
      `kind` stays immutable on `PATCH`.
- [x] 3.2 `PipelineService` absorbs `RuleService` and dispatches `validate` on kind to
      `AggregateSpecValidator` or `EnrichSpecValidator`. `PipelineFilters.OPERATION` becomes a parameter
      so the shared filter analysis reports `"aggregate"` or `"enrich"`.
- [x] 3.3 `requireValidCron` becomes conditional on the trigger kind, in step with the DDL dropping
      `trigger_cron NOT NULL` — it is called unconditionally today and throws on a blank value.
- [x] 3.4 ENRICH is exempt from the disabled-only declaration patch; AGGREGATE keeps its 409
      (design D-2).
- [x] 3.5 `resetCursor` gets kind dispatch in the same breath: for ENRICH, clearing `cursor_*`/`has_more`
      is a no-op and re-stamping **`rebuilt_at`** would write the rollup rebuild anchor on a row that is
      not a rollup. The Class-1 position is *not* reset here — that is
      `discardMarkOfOtherGeneration`, keyed on `generation`, which the bump handles on its own.
- [x] 3.6 One ordered list query with four optional predicates (`target`, `kind`, `enabled`,
      `updated_since`), replacing `findAll(Sort.by("name"))` plus the in-memory `kind` filter
      (design D-4).
- [x] 3.7 Move `EnrichmentFreshness` from `service/enrichment/support` into `service/pipeline` and
      repoint it at `PipelineRepository` (design D-8) — leaving it in place would create a
      `service/pipeline` ⇄ `service/enrichment/support` package cycle that exists in neither direction
      today. `EntityFieldResolver` and `EntityFields` stay put. Rename
      `EnrichmentFreshness.Clampability.NO_RULE` → `NO_PIPELINE`.
- [x] 3.8 Rename by symbol, per design's risk note — **do not run a blanket `rule` → `pipeline`
      replacement**, and read every prose hit: `RuleWriteService` → `EnrichWriteService` (it stays a
      service of its own, symmetric with `ScanService`), `RuleRegistration` → `EnrichRegistration`,
      `ResolvedRule` → `ResolvedEnrichPipeline`, `RuleGovernanceValidator` → `EnrichSpecValidator`,
      `RuleWriteValidator` → `EnrichWriteValidator`, `RuleAuthorizer` → `PipelineAuthorizer`,
      `RuleGenerationService` → `PipelineGenerationService`, `RuleGenerationBump` →
      `PipelineGenerationBump`, `RuleReadSourceDependents` → `PipelineInputDependents`,
      `RuleTimestamps` → `PipelineTimestamps`, `RuleMaterializePositionWriter` →
      `MaterializePositionWriter`, `RulePriority` → `PipelinePriority`, `RuleFilter` →
      **`PipelineEnabledFilter`** (*not* `PipelineFilter` — `PipelineFilters` already exists as the
      SQL-filter helper), `RuleFreshnessDiagnostics` merged into `PipelineFreshnessDiagnostics`,
      `EntityFieldResolver.ruleEntityOf` → `enrichEntityOf`.
- [x] 3.9 Exceptions: `RuleNotFoundException`/`RuleValidationException`/`RuleConflictException` →
      the existing `Pipeline*` equivalents; add `PipelineAccessDeniedException` and delete
      `RuleAccessDeniedException`. Update `DefaultExceptionHandler`.
- [x] 3.10 `AggregateSpecValidator`'s operator-facing clamp messages name "a rule" in its `GROUP_TRIGGER`
      and `NOT_CLASS1` cases, and its javadoc describes the rule registry. Its *logic* is untouched; its
      types and its prose are not. Leave the ~15 uses of "rule" that mean *validation rule* alone.
- [x] 3.11 `service/enrichment/` **keeps its name and its files** — the name is accurate and moving 52
      files would be tidying against a structural-only scope. `service/pipeline/PipelineService`
      dispatches into it.
- [x] 3.12 `RuleGenerationBump`/`RuleReadSourceDependents`' javadocs both justify the interface with
      "`service.enrichment` depends on `service.table`, never the reverse", which
      `TableCatalogService`'s direct import of `PipelineEntity`/`PipelineRepository` already disproves.
      Either delete both and call `service/pipeline` directly, or keep them and rewrite the rationale —
      do not carry over a justification the next reader can disprove in one grep.

## 4. Controllers and security

- [x] 4.1 Delete `RuleController` and `RuleDataController`.
- [x] 4.2 `PipelineController` gains `enabled` and `updated_since` on the list endpoint, with
      `RuleController`'s strict `@InitBinder` carried over **verbatim** — Spring's lenient `Boolean`
      binding accepts `1`/`yes`/`on` and maps an empty value to `null`, which for a parameter selecting
      which subset is returned silently answers a different question than was asked.
- [x] 4.3 Rename `PipelineController.list()` to `listPipelines()`. springdoc derives `operationId` from
      the method name and `list()` is one of six in `web/controller/`, told apart today by a
      scan-order-dependent `list_N` suffix; deleting `RuleController.listRules` would otherwise remove
      the one list operation with a stable id, and the `adas-mcp` OpenAPI snapshot already churns ~1400
      lines of hash-order noise without help.
- [x] 4.4 `PipelineDataController` gains `scan`, `group-rows`, `materialize` and `rows` alongside
      `preview` and `refresh`. Bodies and success responses are unchanged, including
      `ScanResponse.__signal`, the permitted provenance set, opaque-cursor semantics, and the 422 on
      `scan` for a `sql` evaluator / on `materialize` for an `llm` one.
- [x] 4.5 `OidcSecurityConfiguration`: pin `POST /v1/pipelines/*/scan`, `*/group-rows`, `*/materialize`
      and `*/rows` as `.authenticated()` **before** the admin rule, or the runner gets 403 on every
      call. `preview` and `refresh` must **stay** `@FullAdminOnly` — they are operator surfaces, not
      runner surfaces. Authorization itself is unchanged: `PipelineAuthorizer` on the target
      enrichment's WRITE grant, with `security.mode=none` fail-open untouched.

## 5. Table catalog guards

- [x] 5.1 Collapse the SOURCE branch of `TableCatalogService.delete`: `rulesDeclaringReadSource` was
      backed by `findBySourceTable`, so `pipelines.findByInputTable` now answers it for both kinds and
      the branch's two lookups become one. Behavior-preserving.
- [x] 5.2 The other two guards do **not** collapse, and treating this as "collapse them all" would
      delete a guard: `rulesReadingEnrichment` and `TableSchemaService.requireNoRuleReads`'
      `rulesReadingEnrichmentColumn` are **declaration-parsing** queries — `findAllDeclarations()` piped
      through `governance.referencedEnrichments`/`declaredFieldsOf` — asking which declarations name a
      column of an enrichment through the grain join. `findByInputTable` cannot answer that. They survive
      as ENRICH-scoped queries here; broadening them to AGGREGATE declarations is a behavior change and
      ships as `guard-aggregate-enrichment-reads`.
- [x] 5.3 Reword the 409 messages: "…is the read source of rule(s) […]" becomes one pipeline-shaped
      message.
- [x] 5.4 Scope `PipelineGenerationService.onVersionColumnRepointed` to **ENRICH** (design D-6).
      Cross-kind, `findByInputTable('dial_usage_log')` would bump the `generation` *and `updated_at`* of
      `conversations_rollup`, `sessions_rollup` and `turns_rollup` on every version-column repoint, for a
      signal no aggregate consumer reads. `findByTargetInAndInputTableIsNull` needs no kind filter — an
      AGGREGATE row's `input_table` is never null — but say so in the query's javadoc rather than leaving
      it to be rediscovered.

## 6. Tests

- [x] 6.1 Rename: `RuleRegistryIntegrationTest` → `EnrichPipelineRegistryIntegrationTest`,
      `RuleDataAuthorizationIntegrationTest` → `PipelineDataAuthorizationIntegrationTest`,
      `RuleReadsEntityIntegrationTest` → `EnrichReadsEntityIntegrationTest`, `RuleServiceTest` →
      `PipelineServiceTest`, `RuleWriteValidatorTest` → `EnrichWriteValidatorTest`, `RuleTimestampsTest`
      → `PipelineTimestampsTest`. Checkstyle traps on renamed test methods: no `aFooBar` (the second
      character must be lower-case) and no two consecutive capitals — both fail `build`, not just the
      lint task.
      *(Completed in a follow-up sweep: the first three were still on their old names when this was first marked
      done — the class rename is verifiable by `find`, so state the target names rather than only the
      sources. The same sweep carried the entity vocabulary through the five files 6.2 retargeted
      — `Class1MaterializeIntegrationTest`, `GroupEnrichmentIntegrationTest`,
      `EnrichmentScanIntegrationTest`, `AggregateEnrichmentIntegrationTest`,
      `EnrichmentWriteBackIntegrationTest` — which had the new URLs but still called the entity a rule,
      and corrected `EnrichReadsEntityIntegrationTest`'s class javadoc, which named the plain grain key
      as the spelling the runner addresses the group-key column by. It is the qualified one; the file's
      own method-level javadoc and `enrichment-runtime`'s spec both already said so.)*
- [x] 6.2 Retarget `POST /v1/rules` → `POST /v1/pipelines`: `Class1MaterializeIntegrationTest`,
      `EnrichmentScanIntegrationTest`, `EnrichmentWriteBackIntegrationTest`,
      `GroupEnrichmentIntegrationTest`, `AggregateEnrichmentIntegrationTest`,
      `TableManagementIntegrationTest`, `EnumColumnIntegrationTest`, `IntegrationTables`.
- [x] 6.3 Update assertions: `EnrichmentFreshnessTest` (`NO_PIPELINE`), `AuditResourceMapperTest`,
      `TableCatalogServiceTest`, `TableSchemaServiceTest`, `CatalogSchemaWriterTest`, `PipelineMapperTest`,
      `PipelineServiceTest`.
- [x] 6.4 One test per settled decision. **D-1**: the name grammar in *both* directions — a hyphenated
      name is accepted, and one carrying `#`, an upper-case letter or a leading `_` is refused with
      `pipeline_validation_failed` (**not** `table_validation_failed`). **D-2**: a declaration `PATCH` on
      an enabled ENRICH pipeline succeeds, the same patch on an enabled AGGREGATE one is 409, and the
      ENRICH patch leaves `rebuilt_at` untouched. **D-3**: `PipelineSpecCodec` round-trips both kinds and
      a row written before the fold reads back unchanged. **D-4**: one listing under all four filters
      asserting `updated_at ASC, name ASC`, including the tie case (two rows sharing a truncated
      `updated_at`).
- [x] 6.5 Cross-kind field rejection: 422 for `measures` on an ENRICH declaration and for
      `evaluator_name` on an AGGREGATE one.
- [x] 6.6 `findDue` must not return an ENRICH row; `onVersionColumnRepointed` must not bump an AGGREGATE
      row's `generation`.
- [x] 6.7 An AGGREGATE registration that omits `trigger` still resolves to `SCHEDULE` and stores a cron,
      while an `on_ingest` ENRICH registration is accepted with no cron.
- [x] 6.8 An evaluator-less ENRICH insert is refused by `pipeline_evaluator_iff_enrich`, and an
      AGGREGATE row claiming `trigger_kind='GROUP'` by `pipeline_aggregate_is_scheduled`. **Needs a
      repository-level test that bypasses the service**, since both are database backstops for a state the
      API already refuses — the point of the guard is the path that does not go through the API.
- [x] 6.9 The four data-plane paths are reachable by a non-admin authenticated identity holding the
      target's WRITE grant, while `preview` and `refresh` are not.
- [x] 6.10 `V34` applies both from empty and over seeded rows, and the four hyphenated names survive
      Part B with `generation` and the Class-1 position intact.
- [x] 6.11 Close a gap while here: there is **no** `@WebMvcTest` slice for the rule controllers today.
      Add one for `PipelineController`'s new params, where a slice test is cheap.

- [x] 6.12 Three decisions the suite surfaced, each now covered: `enabled` required for ENRICH and
      reported as 422 (design D-12), `?kind=enrich` binding by its wire spelling (D-13), and the gate's
      normalization surviving the move of the transform into a JSON column (D-14).

## 7. Specs

Budget this as the largest section, not a final rename pass. A requirement whose title and every
scenario's prose both change is a full-text transcription in practice. Rules to respect throughout: a
MODIFIED requirement must carry **every** scenario it keeps — dropping one silently loses shipped
behavior at archive; a requirement's `SHALL` must be on its **first line**, because the validator reads
only that line; and the heading is the lookup key, so `MODIFIED` alone cannot retitle anything —
a title *and* body change is `RENAMED` **plus** `MODIFIED`. Note that neither this repo nor the runner
has ever used `RENAMED` (zero occurrences across every archived change in both), so these are its first
exercise.

- [x] 7.1 Found `pipeline-registry` (done): ADDED — "Pipeline registry object", "A pipeline name is an
      identity, not a SQL identifier", "Register and enable lifecycle", "A declaration field belonging to
      another kind is rejected", "Pipeline listing carries the registry's filters, envelope and
      ordering", "Pipeline runtime state is readable", "Pipeline errors use the shared error contract".
- [x] 7.2 `aggregate-pipeline` REMOVED, each carrying `**Reason**` and `**Migration**` pointing at the
      `pipeline-registry` requirement that replaces it: "Pipeline registry object", "Pipeline runtime
      state is readable", "Aggregate pipeline errors use the shared error contract".
- [x] 7.3 `aggregate-pipeline` RENAMED + MODIFIED: "Register, preview, enable lifecycle" →
      "Dry-run preview of an aggregate declaration", reduced to the preview half (the two artefacts, the
      disabled-pipeline permission, and the refresh-on-disabled 409 that belongs to the aggregate
      execution path), with the registry half deleted from it.
- [x] 7.4 `aggregate-pipeline` MODIFIED for the registry vocabulary the clamp reasoning carries: "A
      refresh window is clamped to its referenced enrichments' materialized positions", "An enrichment
      whose materialized position cannot be established is not aggregatable", "A rollup whose enrichment
      had history re-derived is flagged", "Re-running an enrichment over history invalidates its
      dependent rollups", "An aggregate pipeline reads its input entity, not only its input table",
      "Target shape governance" (the two references to an enrichment *rule* sourcing a rollup), and
      "Aggregate spec vocabulary" (the filter grammar's shared-user reference). Leave every use of "rule"
      that means *validation rule* untouched.
- [ ] 7.5 **Three Purpose sections** are not requirements, so no delta touches them: edit them at sync
      time. `aggregate-pipeline`'s stops claiming the pipeline registry and the `/v1/pipelines` CRUD
      surface; `enrichment-registry`'s stops describing rules and the bare-array `GET /v1/rules`; and
      `enrichment-runtime`'s "rule-scoped" framing becomes pipeline-scoped.
- [x] 7.6 `enrichment-registry` REMOVED: "One rule per target enrichment" (moved to
      `pipeline-registry`'s "Pipeline registry object", where it becomes cross-kind and DB-enforced).
- [x] 7.7 `enrichment-registry` RENAMED + MODIFIED — 13 of its 16 requirements are rule-worded: "Rule CRUD
      binding an evaluator to a target enrichment" → the ENRICH pipeline declaration; "Rule listing and
      runner sync feed with resolved evaluator and change detection" → the `?kind=enrich` feed, deferring
      the envelope and ordering to `pipeline-registry`; "A rule's read source is declarable"; "Static
      governance gate on register and enable"; both sensitive-column entitlement requirements; "Runner
      identity and derived-column sensitivity posture"; "A rule reads its read source's entity, not only
      its read source table"; "An enrichment read requires an establishable materialized position"; "A
      rule's enrichment references must not form a dependency cycle"; "A rule's enrichment references are
      protected against drop and rename"; "A string output binds to an enum target column"; and the
      `enrichment-sourced value` requirement. The two evaluator requirements are unchanged.
- [x] 7.8 `enrichment-runtime` RENAMED + MODIFIED — all four endpoints re-path and "rule-scoped" becomes
      "pipeline-scoped": "Rule-scoped incremental scan endpoint", "Rule-scoped versioned write-back
      endpoint", "Rule-scoped scan and write authorization", "Rule-scoped group-input assembly endpoint",
      "A rule's read window is clamped to its referenced enrichments' positions", "A rule's state reports
      its freshness exposure"; the remaining ten need vocabulary only.
- [x] 7.9 `class1-enrichment` MODIFIED — `materialize` re-paths and all seven requirements are
      rule-worded in their scenarios; "A materialize records the position it wrote through" is renamed.
- [x] 7.10 `table-management` MODIFIED — "Delete a table definition" and "Modify table schema via PATCH"
      take the 409 rewording. The drop/rename protection is **not** in this capability — it is
      `enrichment-registry`'s "A rule's enrichment references are protected against drop and rename",
      already counted in 7.7 — and "Grain-based enrichment join with enforced cardinality" is about the
      join, not the guard, so it needs vocabulary only.
- [x] 7.11 `openspec/config.yaml`: drop `/v1/rules` from the frozen bare-array list (three remain);
      re-path `/v1/rules/*` → `/v1/pipelines/*` in the credential-split invariant and in "Keep Class-1
      derivation inside the database", including the `/v1/rules/{id}/rows` carve-out; point the
      strict-boolean-binder convention at `PipelineController`; change the auditing paragraph's "Rules,
      pipelines and saved queries" to "pipelines and saved queries"; rename `RuleAuthorizer` in the
      403-vs-404 paragraph and "rule authorization" in the `security.mode` fail-open list. Grep the file
      rather than working from this list, and leave the top-level `rules:` key alone.
- [x] 7.12 **Record one code change the plan's table did not cover:** `EvaluatorService` raises the same
      validation exception the rule gate did, so `POST /v1/evaluators` answers `rule_validation_failed`
      today and `pipeline_validation_failed` after the fold. It is a pre-existing coupling rather than a
      new one, and giving evaluators a code of their own would be new API surface — so the honest move is
      to state it in `enrichment-registry`'s evaluator requirements rather than leave the two capabilities
      disagreeing. (`enrichment-registry`'s two evaluator requirements are otherwise unchanged, which is
      why this is easy to miss.)
- [ ] 7.13 `openspec validate fold-rule-into-pipeline --strict`.

## 8. Docs

- [x] 8.1 `README.md` — 3 hits: the audit-scope sentence and two in the enrichment-clamp section.
- [x] 8.2 `docs/configuration.md` — 24 hits, all paths in the credential and preview sections. **No
      config key renames**: `enrichment.*` and `aggregate.*` are named for the domain and the kind, not
      for "rule", so no table churn and no new rows.
- [x] 8.3 `docs/enum-columns.md` — its "Enrichment rules" section describes the output → enum-column
      binding and pairs with `enrichment-registry`'s "A string output binds to an enum target column".
      Leave its `## Rules` heading alone.
- [x] 8.4 Confirmed **not** needing a change: `docs/heavy-columns.md` (no occurrence of "rule" at all),
      `docs/column-tags.md` (its one hit is the English word), and `CLAUDE.md` (none — its capability
      paragraph enumerates only the query surfaces).

## 9. Verification

- [ ] 9.1 `./gradlew clean build`, then
      `openspec validate fold-rule-into-pipeline --strict`.
- [ ] 9.2 `bootRun` on an **empty** local PostgreSQL — `V34` applies from scratch.
- [ ] 9.3 `bootRun` on a database seeded with a representative pre-fold dump — the fold moves every row, the four
      hyphenated names survive, `generation` and the Class-1 position survive, `spec` deserializes for
      both kinds, and the collision guard does not fire.
- [ ] 9.4 Register an ENRICH pipeline through the new API **with a hyphenated name**; confirm a
      cross-kind field is 422; confirm `GET /v1/pipelines?kind=enrich&enabled=true` returns the envelope
      carrying `generation`; confirm `unknown_pipeline` and `pipeline_access_denied`; confirm `/v1/rules`
      is **404, not 401** — a 401 would mean the path still matches a security rule.
- [ ] 9.5 Drive one `scan` → `rows` round trip and one `materialize` with a non-admin authenticated
      identity holding the target's WRITE grant.
- [ ] 9.6 Rehearse the migration plan's steps 1–7 end to end locally, against a copy of a representative catalog
      and a local runner, **before** the window is booked. Develop and verify locally throughout — do not
      gate any of this on a remote backfill or drain; verify against a running deployment only after the deploy.
