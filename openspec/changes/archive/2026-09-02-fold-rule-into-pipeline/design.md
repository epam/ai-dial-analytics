## Context

Two registries, one concept. The table below is the duplication being removed; the proposal argues
why, this document decides how.

| | `pipeline` | `rule` |
|---|---|---|
| Surface | `/v1/pipelines`, admin-only | `/v1/rules` + a 4-endpoint runner data plane |
| Identity | `name` (TEXT PK) | `id` (TEXT PK, UUID) with `name` UNIQUE |
| Kinds | `AGGREGATE` implemented; `EXTERNAL`, `ENRICH` declared and 422'd | — |
| One writer per target | `UNIQUE (target)` | `UNIQUE (target_enrichment)` |
| Executed by | ADAS's own `AggregateScheduler` | the Enrichment Runner, except Class-1 which ADAS materializes |
| Declaration blob | `spec JSONB` (`AggregateSpec`) | flat columns + `input_bindings`/`output_bindings` JSONB |
| Change token | `generation` (no consumer) | `generation` (the runner's only consumer) |
| Freshness projection | `state.clamp`, `state.unclamped_reads`, `state.rebuild_required` | top-level `unclamped_reads`, `rebuild_required` — same reason codes |

Seams that already point the right way: both spell the membership predicate `filter_sql` and validate
it through the same `FilterSqlValidator`; both resolve names through `EntityFieldResolver`; both clamp
through `EnrichmentFreshness` and report the clamp with the same reason codes;
`V32__enrichment_materialized_position.sql` already alters `rule` **and** `pipeline` in one file.

Constraints that shape every decision below:

- **Existing aggregate pipelines must keep working.** Rollups such as `conversations_rollup`, `sessions_rollup`,
  `turns_rollup` and `response_ratings_rollup` must read back byte-identically after the fold.
- **The runner keys five state tables on the identity ADAS is changing**, and `DeterministicId`
  embeds it in every db-scheduler `task_instance` — in the prefix *and* in the sha1 basis. Losing
  that state means re-derivation, and on the two group LLM lanes it means real DIAL spend.
- **`V11`, `V20` and `V32` must never be edited.** Their comments are checksummed; editing an
  applied migration aborts startup in every environment and a green local build does not catch it.

## Goals / Non-Goals

**Goals.** One registry object, one identity, one DTO family, one set of error codes, one freshness
projection. Same behavior everywhere the fold does not force a change. The registry object
documented in a capability of its own, so the shared contract is not filed inside one of its kinds.

**Non-Goals.** Dry-run preview for ENRICH; pipeline backfill as a bounded run of one definition;
table lifecycle `status`; the `EXTERNAL` kind; a runner-reported materialized position. Also
non-goals: moving `service/enrichment/`'s 52 files (its name is accurate), renaming any config key,
and tidying the enrichment vocabulary where it is still correct — an ENRICH pipeline enriches.

## Decisions

### D-1: A pipeline `name` is an identity, and the grammar widens to admit the hyphen

`PipelineService.create` calls `Identifiers.requireUserIdentifier` — `^[a-z][a-z0-9_]*$`, ≤64 chars,
no leading `_`, **no hyphen**. The existing pipeline names are snake_case and pass. The existing
rule names are hyphenated (`turn-feedback-live`, `usage-client-identity-live`,
`conversation-insights-live`, `session-insights-live`) and **every one is rejected by the validator
the merged path would inherit.** `V34` moves rows in SQL and bypasses it, so left alone the database
would hold four ENRICH pipelines the API can read and patch but could never have created.

**Decided: widen.** Add `Identifiers.requirePipelineName` — `^[a-z][a-z0-9_-]{0,63}$`, throwing
`PipelineValidationException`. Three facts decide it, and none of them is "renaming is expensive":

- **The strict grammar is currently guarding nothing.** `AggregateCompiler` applies
  `Identifiers.require` to `target`; the only other strings reaching rendered SQL are measure and
  column names. A pipeline `name` never becomes a ClickHouse identifier, so the grammar is neither
  injection-safety nor engine compatibility — it is a copy-paste from the table path.
- **`PipelineService` is the only one of nine `requireUserIdentifier` call sites whose argument is
  not a ClickHouse identifier.** The other eight are table names, column names, a grain key and an
  otel table name; the method's own javadoc says it is "the spec grammar for user-declared *table
  and column* names". Using it for an identity is the anomaly.
- **Renaming the four enrichments would move the seam, not close it.** Evaluator names are guarded
  only by `@NotBlank` and are all kebab-case (`feedback-rollup`, `usage-client-identity`,
  `conversation-insights`, `session-insights`). Snake-casing the pipelines leaves
  `usage_client_identity_live` pointing at evaluator `usage-client-identity`. Kebab-case is the
  enrichment sub-domain's convention; snake_case is the catalog's, *because* catalog names become
  SQL.

The exclusion that *is* load-bearing is `#`, plus uppercase and a leading `_`: the runner builds
`#`-delimited instance ids, and the names `a#b` and `a` would share the `a#` prefix that
`InflightCounter` counts a pipeline's in-flight work by. A UUID could not contain `#`; a free-form
name could. `requirePipelineName` is therefore a hard dependency of the runner's change, not a
nicety.

Throwing `PipelineValidationException` also fixes the exception type: `requireUserIdentifier` throws
`TableValidationException` → `table_validation_failed`, so the error-code contract does not hold
until the pipeline path throws its own.

*Rejected:* rename the four enrichments to snake_case — moves the mismatch onto the evaluator names.

### D-2: An enabled ENRICH pipeline may be re-declared; an enabled AGGREGATE one may not

`PipelineService.patch` refuses `hasDeclaration()` while `enabled` is true; `RuleService.update` has
no such guard and simply re-runs the gate and bumps `generation`. Carrying the pipeline posture over
unchanged would be a real narrowing: a routine live tweak (`cadence`, `rate_rpm`,
`batch_scan_limit`) would need disable → patch → enable, and a disable/enable cycle on a group LLM
lane is not free — the runner drops the pipeline from its cache, and requeueing a DLQ item while it
is disabled destroys the stored labels.

**Decided: exempt ENRICH, and this is correctness rather than convenience.** Read the reason the 409
gives — the target would hold "rows computed two different ways, with nothing recording which row
came from which". That is a statement about a rollup rebuilt window by window. An ENRICH target is a
grain-keyed `ReplacingMergeTree`: after a re-declaration new rows compute the new way and old rows
keep their old values, which is *already* how the enrich path behaves and is precisely why
`history_rederived_at` and the backfill lane exist. The invariant is not weakened for ENRICH; it was
never the ENRICH invariant. Keep the 409 for AGGREGATE, where it means something.

`resetCursor` needs kind dispatch in the same breath. `patch` calls it on every declaration change,
and for an ENRICH row two of its four effects are wrong or meaningless: clearing `cursor_*` and
`has_more` is a no-op (the cursor lives in the runner and those columns stay NULL), but re-stamping
**`rebuilt_at`** writes the *rollup rebuild anchor* on a row that is not a rollup. The Class-1
position must **not** be reset here — that is `discardMarkOfOtherGeneration`, keyed on `generation`,
which the bump handles on its own.

*Deferred:* a third patch category — runtime knobs patchable while enabled, declaration requiring
disabled — is new behavior and belongs in its own change.

### D-3: One `spec` column, mapped untyped, dispatched on `kind` in a codec

`PipelineEntity.spec` is a concretely-typed `AggregateSpec` annotated `@JdbcTypeCode(SqlTypes.JSON)`.
Hibernate binds it through the *declared field type*, and an `AttributeConverter` could not help
even if one existed — a converter receives only the column value, never a sibling field like `kind`.

**Decided: `PipelineEntity.spec` becomes a `String`** (still `@JdbcTypeCode(SqlTypes.JSON)`), and a
single `PipelineSpecCodec` reads it as `AggregateSpec` or `EnrichSpec` and writes it back.
`PipelineRegistration.spec` widens to a sealed `PipelineSpec permits AggregateSpec, EnrichSpec` as
the **service-layer** type only.

The deciding property is what it does *not* require: **no migration stamp, so the existing rollups'
`spec` values are read byte-identically to today.** `V34` touches their rows for nothing but
`trigger_kind`, and a mistake in the spec plumbing cannot take every existing rollup down
together. The kind dispatch is not new work — `PipelineService.validate` already branches on kind —
and dirty tracking is not really lost, because `spec` is always replaced wholesale via `setSpec` and
never mutated in place.

*Rejected:* `@JsonTypeInfo` with a `@kind` key stamped into the existing rows by `V34`. More
type-safe at the entity, but it buys that by putting every working rollup behind a data migration it
does not need, and it couples the annotation and the stamp so tightly that either one shipping alone
fails at first read. *Available if entity-level type safety later matters:* two columns — `spec`
stays `AggregateSpec`, add `enrich_spec` — which is more defensible than it sounds, since Part A
already keeps five per-kind trigger fields as real columns on the same argument.

### D-4: One ordered list query — `updated_at ASC, name ASC` for every listing

The merged list endpoint carries four filters (`target`, `kind`, `enabled`, `updated_since`) and
inherits **two orderings**: `PipelineService.list` returns `findAll(Sort.by("name"))`, while the sync
feed's contract is `updated_at ASC, name ASC`. The tiebreaker is not cosmetic — `updated_at` is
truncated to microseconds, so rules written in one transaction would otherwise shuffle between two
calls over an unchanged registry.

**Decided: `updated_at ASC, name ASC` for every listing, in one query.** Two things make this free
rather than a trade. Nothing depends on the name order — it is a convenience. And nothing yet
depends on the feed order either: the runner fetches the whole `enabled=true` list with
`updated_since=null` on every poll and reconciles it into a map, so a shuffle is invisible to it.
The tiebreaker is a *latent* guarantee for a future paging or diffing consumer, which is exactly why
it should be preserved deliberately and in one place. For a registry of eight objects "most recently
changed last" also reads better to an operator than alphabetical.

The payoff is structural: four optional predicates in one query, which also removes today's wart
where `list` calls `findAll(Sort.by("name"))` and then filters `kind` **in memory**.

### D-5: Five per-kind fields stay columns; the ENRICH transform goes into `spec`

The split is trigger (shared infrastructure) vs transform (per-kind) — the tables-and-data-movement design's framing, "the kind
selects the transform". `trigger_kind`, `trigger_cron`, `trigger_group_by`, `ready_when` and
`member_select` stay real columns, which is what lets the three structural guards on `rule` survive
verbatim as `pipeline_cron_iff_schedule`, `pipeline_group_by_iff_group` and
`pipeline_member_select_only_group`. Two further constraints force specific columns:
`evaluator_name` keeps its **foreign key** to `evaluator(name)`, and the five Class-1 position
columns are written by `@Modifying` bulk updates and so cannot be JSON.

**Two invariants are lost unless two new guards are added**, and neither has a counterpart on `rule`
to copy — they exist there as plain `NOT NULL`s that only make sense per-kind:

- `pipeline_evaluator_iff_enrich` — `(kind = 'ENRICH') = (evaluator_name IS NOT NULL)`. `V11`
  declares `rule.evaluator_name TEXT NOT NULL`; the merged column must be nullable because an
  AGGREGATE row has no evaluator, and dropping the `NOT NULL` without this check makes **an ENRICH
  pipeline with no evaluator storable** — a row nothing can execute and nothing refuses.
- `pipeline_aggregate_is_scheduled` — `kind <> 'AGGREGATE' OR trigger_kind = 'SCHEDULE'`. Without it
  the widened `trigger_kind` admits an AGGREGATE row claiming `GROUP` or `ON_INGEST`, which
  `AggregateScheduler` would never fire and `findDue` would never select.

### D-6: `findDue` is kind-scoped to AGGREGATE, with belt and braces

The single most dangerous line in the change: an ENRICH row with a non-null `next_run_at` selected
by the aggregate tick would attempt an aggregate refresh against an enrichment target. Three
independent guards, because one is not enough for a failure of that shape: `V34` leaves
`next_run_at` NULL for the moved rows, `findDue` filters on `kind = AGGREGATE`, and
`AggregateRefreshService.refresh` asserts the kind.

The mirror case: `findByInputTable` becomes cross-kind, and its two callers want it differently. The
table-delete guard wants both kinds. `RuleGenerationService.onVersionColumnRepointed` wants ENRICH
only — `findByInputTable('dial_usage_log')` would otherwise bump the `generation` **and
`updated_at`** of `conversations_rollup`, `sessions_rollup` and `turns_rollup` on every
version-column repoint, for a signal no aggregate consumer reads. (`findByTargetInAndInputTableIsNull`
needs no kind filter — an AGGREGATE row's `input_table` is never null — but the query says so
rather than leaving it to be rediscovered.)

### D-7: `trigger.kind` defaults to `SCHEDULE`, and `requireValidCron` becomes conditional

`CreatePipelineRequest.trigger` is optional and `TriggerRequest.kind` is a free-form `String` that
nothing currently reads. Once `trigger_kind` is `NOT NULL`, an aggregate body that omits `trigger` —
or sends `trigger` with only `cron` — must resolve to `SCHEDULE`, or every existing aggregate
registration body becomes a 422. And `requireValidCron` is called *unconditionally* today, throwing
on a blank value; an `on_ingest` or `group` ENRICH pipeline has no cron, so the call becomes
conditional on the trigger kind in step with the DDL dropping `trigger_cron NOT NULL`.

`kind` stays a `String` on the wire rather than being promoted to the `TriggerKind` enum. That is
what preserves today's property that an unknown trigger kind is a **422 governance failure, not a
400 binding error** — the same reason `RuleRegistration.triggerKind` is a raw string.

### D-8: `EnrichmentFreshness` moves into `service/pipeline`; nothing else moves

`EnrichmentFreshness` (310 L) lives in `service/enrichment/support` and injects `RuleRepository`;
`service/pipeline`'s `AggregateSpecValidator` already reaches across into it. Repoint it at
`PipelineRepository` and the two packages point at each other — `service/pipeline` →
`service/enrichment/support` → `service/pipeline` — a cycle that exists in neither direction today.
Move it to `service/pipeline`, where both of its callers then live. `EntityFieldResolver` and
`EntityFields` stay put: they depend only on `service/table` and `service/query`, so they carry no
cycle. `service/enrichment/` keeps its name and its remaining files; `service/pipeline/PipelineService`
dispatches into it.

### D-9: The `pipeline-registry` capability is founded rather than the shared contract left in place

`aggregate-pipeline` documents the registry object today. Leaving it there after the fold reproduces
the confusion in the one place meant to resolve it, so a new capability owns the kind-independent
half. OpenSpec has no move operation: each moved requirement is an `ADDED` (full text) in
`specs/pipeline-registry/spec.md` **plus** a `REMOVED` in the source capability carrying `Reason`
and `Migration`. Getting that wrong leaves the same requirement live in two capabilities.

### D-10: Registration-time `enabled` is honoured for ENRICH and ignored for AGGREGATE

*Not in the source plan; surfaced while merging the two create paths, and it is a behaviour question rather
than a mechanical one.* `PipelineService.create` forces `enabled:false` whatever the caller asked for;
`RuleService.create` applies `registration.enabled()`, and the runner's own runbook registers rules enabled.
Carrying the pipeline posture over uniformly would be a silent narrowing — every enrichment registration
would need a second `PATCH` — so the two are kept apart on the reason the forced disable exists at all.

**Decided: the forced disable belongs to the preview gate, not to registration.** An aggregate pipeline is
stored disabled because its dry-run preview is what the disabled state is *for*: an operator reads the
compiled statement and a sample before anything writes. The enrich path has no preview endpoint — that is
explicitly out of scope here — so requiring the extra call would gate nothing while costing a round trip.
Its static governance gate has already run either way. If ENRICH preview later ships, this becomes uniform
by itself.

### D-12: `enabled` is required for ENRICH at registration, and reports 422 rather than 400

*Surfaced by `missingEnabledIsRejectedAsBadRequest`.* The enrichment registry made `enabled` mandatory on a
create — "whether a rule starts live is an operator decision", rejected with 400 `bad_request` by a
`@NotNull` — while the aggregate request has no `enabled` member at all, because a rollup is always stored
disabled. Both cannot hold on one request shape: a `@NotNull` would turn every aggregate registration body
ever written into a 400.

**Decided: keep the requirement, move the status.** It stays required for `enrich` (D-10 makes the flag
load-bearing there — an omitted one would silently mean "disabled" and leave a pipeline that looks
registered and never runs) and is ignored for `aggregate`. Because the requirement is now per-kind it cannot
be bean validation, so it reports as a **422 governance failure** naming the field instead of a 400. That
status change is the smaller of the two available costs, and it is the same shape `inputs` already has.

### D-13: The `kind` query parameter binds by its wire spelling

*Surfaced by three integration tests answering 400 for `?kind=enrich`.* Spring's enum converter is
`Enum.valueOf(name)` — case-sensitive on the constant — while a pipeline kind is published lower-case
through `@JsonValue`. The filter has existed since the aggregate registry shipped and was never exercised
from the wire, so `?kind=enrich`, the only spelling a client has seen, did not convert. An `@InitBinder`
now resolves it by `code()`.

An unrecognised kind stays a **400** here, against the 422 an unimplemented kind gets in a request *body*,
and the difference is deliberate: in a body the kind is part of a declaration this capability governs, while
here it is a filter that will not convert to its declared type — which is a bad request service-wide.

### D-14: The gate normalizes the spec, so the spec is serialized *after* validation

*Surfaced by `materializeWritesRenamedTargetColumn`.* The governance gate does not only validate — it
**normalizes**, rewriting each output binding's column to the target's physical `source_name`, by mutating
the declaration object it is handed. While bindings were entity columns that mutation persisted for free.
With the transform in a JSON column, serializing at `apply()` time stored the un-normalized form and
silently discarded the rewrite, which both write paths depend on.

So `apply()` returns the spec object rather than storing it, and `validateAndStore()` serializes what the
gate leaves behind. The enable path re-reads, re-validates and re-stores for the same reason — which is
what the rule path did implicitly, because its entity was managed.

**This is the failure mode D-3 warned about in a different form**: moving a typed field into an untyped
column moves the write barrier, and anything that mutated the old field in place needs its write made
explicit.

### D-11: Three structural additions the fold forced

Each one arrived from a compile error rather than from the plan, and each is worth naming because it is a
new type, not a rename:

- **`EnrichDeclaration`** (`service/enrichment/model`) — a flat read-only view over the pair
  `(PipelineEntity, EnrichSpec)`. The storage split is deliberate (trigger fields as columns for their
  constraints, transform as JSON) and noise everywhere else: the governance gate asks ~35 questions of a
  declaration and not one cares which half holds the answer. The alternative was threading two parameters
  through twenty method signatures in a 974-line validator.
- **`EnrichResolver`** (`service/enrichment`) — the resolution `RuleService` used to do (evaluator pin,
  read source, grain key, version column, and the batched form that keeps the sync feed at a constant
  number of queries). Kept out of `PipelineService` for the reason the plan kept `ScanService` and
  `EnrichWriteService` out of it: what it resolves is enrichment-domain knowledge, and the registry has no
  business restating it. `PipelineService` delegates, so callers still see one entry point.
- **`PipelineDeclaration.spec` is the raw stored JSON**, not a typed member. The projection the catalog's
  delete guard reads must stay a constructor projection over columns (that is what makes it detached), and
  the column holds a different type per kind — so a typed member would make the other kind's declaration
  unreadable through the same query. It also makes the follow-up change nearly free: the aggregate side's
  `group_by` and `measures` are already in the blob.

Two smaller ones, recorded so they are not read as oversights. `PipelineGenerationBump` and
`PipelineInputDependents` are **kept** rather than deleted, with their rationale rewritten: the stated
reason (`service.table` never depends on the registry) is indeed disproved by `TableCatalogService`'s own
import, but the inversion still prevents a circular *bean* graph, which is the part that bites. And
`PipelineAuthorizer`/`PipelineTimestamps` stay in `service/enrichment/support` — checked rather than
assumed: neither takes a `PipelineEntity`, so neither drags a `service.pipeline` dependency into that
package, and the cycle D-8 removes stays removed.

## Risks / Trade-offs

- **An ENRICH row picked up by the aggregate scheduler** → three independent guards (D-6). This is
  the one failure that would write wrong rows into an existing enrichment rather than erroring.
- **A mistake in the spec plumbing taking every existing rollup down together** → D-3 is chosen
  precisely so `V34` never rewrites an existing `spec` value; verification step 2 boots against a
  seeded dump and reads all four back.
- **A blanket `rule` → `pipeline` replacement corrupting unrelated code** → "rule" is also the
  ordinary English word for a validation rule: ~15 times in `AggregateSpecValidator` alone, plus
  `ScanMetadataRules`, `TableColumnRules` and `TableTtlRules` in `service/table` (none of which
  touches the registry), the `## Rules` heading in `docs/enum-columns.md`, `openspec/config.yaml`'s
  own top-level `rules:` key, and every `rules:`/pod-selector block in
  `docs/kubernetes-exposure.md`. Rename by symbol, and read every prose hit.
- **The security chain silently 403-ing the runner** → the four data-plane matchers must be declared
  *before* the admin rule; verification step 4 drives one `scan` → `rows` round trip with a
  non-admin authenticated identity, and step 3 asserts `/v1/rules` answers **404, not 401** (a 401
  would mean the path still matches a security rule).
- **Hibernate enforces `@Column(nullable = false)` at flush**, not only in generated DDL → it must
  be relaxed on `inputTable` and `triggerCron` in step with the DDL, or an ENRICH insert fails
  before it reaches PostgreSQL.
- **Loading a `RuleEntity`/`PipelineEntity` in a transaction that bulk-updates the same row silently
  loses the bulk write** → a pre-existing trap on the Class-1 position writes; do not restructure
  those four `@Modifying` queries into entity mutations while renaming them.
- **The spec work is the larger half of this change, not a rename pass.** `enrichment-registry` has
  16 requirements of which 13 are rule-worded; `enrichment-runtime` 16; `aggregate-pipeline` 23 over
  1723 lines. A requirement whose title and every scenario's prose change is a full-text
  transcription in practice. Sequenced as its own task section, not as a final step.

## Migration Plan

One maintenance window, both services. The runner is single-replica, so there is no split-brain to
manage. Rollback is a **restore**, not a migration: neither Flyway migration is reversible, so
reverting means restoring the step-0 snapshots and redeploying the previous images.

| # | Step | Where |
|---|---|---|
| 0 | **Pre-flight.** Assert no `rule.name` collides with a `pipeline.name`; assert every name matches the D-1 charset; **record each rule's current `enabled` value**, because step 7 restores *that* rather than setting everything true — a pipeline such as `conversation-insights-live` may be deliberately disabled, and switching a group LLM lane back on that an operator deliberately switched off spends real money; **snapshot both PostgreSQL databases** (this is the only rollback). | both |
| 1 | **Quiesce.** `enabled:false` on every rule via the old API, then wait for `SELECT count(*) FROM scheduled_tasks WHERE task_name IN ('rule-scan','class1-materialize','enrich-chunk','enrich-group','upsert-rows','backfill-run')` to reach 0. It can reach 0 including the two recurring names: the runner's sync cancels the recurring instance of any rule that leaves the `enabled=true` feed, so one 30 s poll after the disable removes them. Load-bearing rather than hygiene — `DeterministicId` puts the identity in both the instance-id prefix and the sha1 basis, so a work item that survives the window is re-enqueued under a new id and evaluated a second time; on an LLM lane that is duplicate DIAL spend. | ADAS API, runner DB |
| 2 | **Stop the runner.** | runner |
| 3 | **Capture the id→name mapping:** `SELECT id, name FROM rule ORDER BY name;` | ADAS DB |
| 4 | **Deploy ADAS** — `V36` runs at startup (authored as `V34`; renumbered, see the proposal's shipped-as note). | ADAS |
| 5 | **Remap the runner's state**, one transaction, against the **still-old** column names: `rule_cursor`, `run_history`, `dlq_item`, `spend_counter`, `group_state`. Verify affected-row counts against step 3 before commit. Not a Flyway migration because the two services have separate databases, so the runner's Flyway cannot read the mapping. | runner DB |
| 6 | **Deploy the runner** with the renamed env vars; its `V8` renames the tables/columns/indexes. | runner |
| 7 | **Restore and verify:** `PATCH /v1/pipelines/{name} {"enabled":<the step-0 value>}` — never a blanket `true`; confirm one sync, one scan tick, one Class-1 materialize, one group sweep. | both |
| 8 | **Follow-up change:** drop `rule_legacy`. | ADAS |

`rule` is renamed to `rule_legacy` rather than dropped because it is the only source of the step-3
mapping (re-runnable at step 5 if ADAS is already deployed) and the only in-database rollback
reference.

Local rehearsal of steps 1–7 against a copy of a representative catalog and a local runner is a task in this
change, **before** the window is booked. Verify locally throughout — do not gate any of it on a
remote backfill or drain; check a running deployment only after the deploy.

## Open Questions

None blocking. Two things deliberately settled elsewhere:

- The §5.6 broadening of the enrichment-read guards to AGGREGATE declarations is a **behavior
  change** (a table delete that succeeds today starts answering 409) and ships as its own change,
  `guard-aggregate-enrichment-reads`, sequenced immediately after this one. This change collapses
  only the SOURCE-branch lookup, which is behavior-preserving.
- `V34` is free against `V33` today. **Re-check for a collision at implementation time** — an
  in-flight branch may have claimed it.
