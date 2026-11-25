## Context

Catalog import (`service/catalog/transfer/`) plans each bundle object against a read-only snapshot of the target, runs a validation
pass that calls the real create services in a rolled-back transaction with no-op ClickHouse DDL, and then applies each object as its
own committed step, compensating by deleting what it created. The original design refused `OVERWRITE`. Its reasons were:

- schema PATCH runs its DDL before the catalog write;
- a generation bump stalls dependent rollups;
- a pipeline would be replaced under a live runner.

It pointed operators at `SKIP_IF_EXISTS` plus `differs`/`prev`/`next` and the existing PATCH endpoints.

That holds for a one-off promotion. It does not hold for a bundle that is re-imported into many environments as it evolves, such as
the recommended catalog. There, every change becomes a per-environment manual procedure. The evolutions seen so far are small:

- a new nullable column;
- changed column descriptions;
- changed `enrich` output expressions.

The existing mutation services already handle all of them safely:

- `TableCatalogService.updateMetadata` handles table metadata;
- `TableSchemaService.patchSchema` handles adds and metadata updates as one validated `ALTER`;
- `PipelineService.patch` handles re-declaration, with the generation and cursor rules of `pipeline-registry`.

The relevant facts of those services:

- Re-declaring an enabled `aggregate` is refused with `409`. Re-declaring an enabled `enrich` is allowed and runs the arm gate.
- A change to an enrichment's columns or column descriptions bumps the generation of the pipeline targeting it
  (`CatalogSchemaWriter`, via `PipelineGenerationService.onTargetColumnsChanged`).
- No table or pipeline mutation takes an optimistic-concurrency token. Table writes serialize on the table row lock.
- `PATCH /v1/pipelines/{name}` replaces whatever a declaration patch leaves out, except `target`.

## Goals / Non-Goals

**Goals:**
- Re-importing a bundle with `OVERWRITE` brings existing tables and pipelines to the bundle's definition where the existing
  services can do it without losing data or recreating the object.
- Re-imports are idempotent: a second run is all `SKIP` and writes no revision.
- The preview says, per object, what will change, what is kept and why, and what the change sets off. An admin rolling a bundle out
  to many environments can decide from the preview alone.
- Per-environment tuning survives every re-import.

**Non-Goals:**
- Dropping, renaming or retyping columns, and changing frozen keys, a pipeline's `kind` or its `target`. They stay manual.
- An optimistic-concurrency token between preview and apply.
- Arming pipelines, triggering backfills or rebuilding targets. These are reported as `effects` and left to the operator.
- Updating system tables, saved queries or dashboards.
- A bundle format change. `format_version` stays `4`.

## Decisions

### D1. `OVERWRITE` means "converge where safe", not "replace"
`OVERWRITE` is implemented with additive, convergent semantics rather than by adding a fourth policy:

- it adds columns;
- it updates metadata;
- it re-declares pipelines;
- it fails any object that would need more.

`OVERWRITE` is already in the enum and already deployment manager's vocabulary, so a UI that offers it needs no change.

Rejected alternatives:
- A new `UPDATE_IF_EXISTS` value, keeping `OVERWRITE` for a later destructive replace. A destructive replace (drop and recreate,
  losing rows and runner state) is not something an import should ever do silently, so the name would stay reserved for nothing.
- Making `OVERWRITE` destructive, with column drops. It would turn a routine re-import into data loss on any environment that added
  a column locally.

### D2. Diff into service operations, one committed step per object
A new `ObjectDiff` compares the target's full object (`existing.bundle()`) with the bundle's, using the `JsonMapper` normalization
`compare` already uses. It emits a list of `PlannedChange`s, each tied to one existing service call:

- `update_table` calls `updateMetadata`.
- `add_column` and `update_column` are folded into one `PatchInput` for `patchSchema`.
- `disable` and `redeclare` call `PipelineService.patch`.

Applying an `UPDATE` table is two commits: the metadata update, then the schema patch. Applying an `UPDATE` pipeline is an optional
disable patch and then the re-declaration.

Rejected: a dedicated "apply bundle object" service that writes catalog rows directly. It would bypass the generation bumps, the
column-policy and row-policy guards and the audit revisions the existing services already get right, and it would drift from them.
The original import design rejected a client-side replay for the same reason.

### D3. Target-owned fields come from one registry
A field is target-owned when two things hold:

- its right value depends on the environment (deployments, capacity, cost, retention);
- changing it does not change what a row means.

The target-owned fields are:

- every `advanced` member;
- `trigger.cron` only. `trigger.kind`, `group_by`, `ready_when` and `member_select` decide which rows group together and when, so
  they follow the bundle;
- table `ttl`;
- the deployment binding `{transform.preset, transform.model, transform.route}`.

The binding is one unit because `route` is required with `dial_app_route` and forbidden otherwise. Keeping the target's model while
taking a bundle's new preset would produce an invalid declaration. So the target's binding is kept while the presets match, and the
bundle's whole binding is taken when the preset changed upstream. That case is reported, and the app-routes arm check surfaces any
consequence.

`transform.params` stays bundle-owned. It is prompt tuning, and upstream changes to it are deliberate.

`TargetOwnedFields` is the single list. `ObjectDiff` substitutes those fields from the target before comparing a pipeline, and the
`env_specific` scan in `CatalogImportPlanner.collectEnvSpecific` reads the same list. A field kept on update is therefore always
reported, and the two lists cannot drift apart. `env_specific` grows from model, `rate_rpm`, cron and `ttl` to the whole registry,
plus the date literals it already reports. Each item gains `target_value` and `kept`.

Rejected alternatives:
- A request parameter naming the fields to keep. Every admin would have to know the field list. The recommended-catalog rollout
  needs one safe default, not a knob.
- Treating all of `trigger` as target-owned. A changed `group_by` would then be silently dropped.

### D4. Sensitivity is tighten-only
An import may set `sensitive: true` and never clear it. Clearing it would widen access to a column on an environment that classified
it as personal data, and it would do so because a bundle said so. A loosening stays a manual schema patch by a holder of
`TABLE_SENSITIVE`, and the import reports it under `not_applied`.

### D5. An updated aggregate lands disabled; an enrich pipeline keeps its state
`PATCH` refuses re-declaring an enabled aggregate, so the import must disable it first. Re-enabling it afterwards would make the
import responsible for arming order (enrichments before the aggregates that read them) and for an arm gate failing mid-apply. Created
pipelines already leave arming to the operator, so updated aggregates do the same: `effects` carries `rearm_required`.

An `enrich` re-declaration on an enabled pipeline is allowed and runs the arm gate inside the patch. Keeping it enabled avoids
stalling its labels, and it raises no ordering question.

Rejected alternatives:
- Disable, re-declare and re-enable. The import cannot choose the arming order safely.
- `FAIL` until the operator disables the pipeline. That adds a manual step per environment, the very thing this change removes.

### D6. The validation pass runs the update services too
For `UPDATE` objects the pass calls `updateMetadata`, `patchSchema` and `PipelineService.patch` inside its rolled-back transaction.
Two adjustments make that work:

- `patchSchema` gains a `DdlExecutor` overload, mirroring `TableSchemaCreationService.defineSchema(name, input, executor)`. Under
  `DdlExecutor.NO_OP` it skips the `alterTookEffect` probe, which would otherwise query ClickHouse for a change that was never sent.
- Updated pipelines that end disabled are armed like created ones. An enabled `enrich` is checked by its own re-declaration.

Today an existing pipeline with `exported_enabled` counts as armable without a check. That shortcut stays for `SKIP` pipelines only.

Rejected: validating updates in Java only. The point of the pass is that its verdict is the real services' verdict.

### D7. Compensation reverts updates from the planned snapshot
`PlannedObject` keeps the full `existing` object. Compensation walks created and updated objects in reverse apply order:

- An updated pipeline is re-declared to its previous declaration through the same mapper, then its `enabled` state is restored.
- An updated table has its metadata and column metadata restored, and the columns this import added are dropped.

The pipelines are reverted first, so a reverted pipeline no longer reads an added column when that column is dropped. Reverts run
inside the import's `AuditImportScope`, so their revisions carry the `import_id`. A refused revert is `rollback_failed`.

Rejected: leaving updates in place on failure and reporting a partial outcome. It breaks the import's contract that `rolled_back`
leaves the target as it was, and an admin re-running it with `OVERWRITE` would converge anyway.

### D8. `SKIP_IF_EXISTS` previews `OVERWRITE`
The diff is computed for every existing object under both non-failing policies. Under `SKIP_IF_EXISTS` the lists are reported and
nothing is applied. An admin can therefore see a rollout's effect from the safe policy before choosing `OVERWRITE`.

### D9. Response vocabulary
`ImportAction` gains `UPDATE` and result `status` gains `updated`. The bundle is unchanged, so `format_version` stays `4`. The new
values change only the response, and the bundle's version rule covers the bundle alone.

## Risks / Trade-offs

- [A description-only change on an enrichment bumps its pipeline's generation, and a re-declaration of the same pipeline bumps it
  again. Dependent rollups are clamped until the runner writes again.] → Reported as `generation_bump` effects naming the pipeline.
  Coalescing the bumps would mean changing `CatalogSchemaWriter`'s rule for every caller, so it is out of scope.
- [A re-declared `enrich` pipeline leaves old rows computed the old way.] → `existing_rows_not_rederived` in `effects`, with the
  runbook pointing at the backfill.
- [Preview-to-apply race: another writer changes an object between preview and apply.] → Apply re-reads and re-plans, and table
  writes take the row lock. The result reflects what was applied. A concurrency token is a non-goal.
- [A revert can itself fail, for example when a concurrent pipeline starts reading an added column.] → `rollback_failed`, the same
  best-effort contract as today's deletes. Re-running with `OVERWRITE` converges.
- [Clients that branch on `resolution_policy_unsupported`, or on the closed sets of `import_action` and `status`, see new
  behaviour.] → Called out as breaking in the proposal and the release notes.
- [Adding a column to a large table is a ClickHouse metadata `ALTER`, not a rewrite.] → No mitigation needed. A non-nullable added
  column reads as its type's default for existing rows, as a direct schema patch does.

## Migration Plan

- There is no data migration and no new config key, credential or Flyway migration.
- **Deploy:** ordinary release. `FAIL_IF_EXISTS` and `SKIP_IF_EXISTS` behave as before, apart from the wider `env_specific` list and
  the added preview lists.
- **Rollback:** an older ADAS refuses `OVERWRITE` again. Objects already updated stay as updated; nothing needs undoing.
- **After release:** the next recommended-catalog change is shipped as a bundle update, and its upgrade note says to re-import with
  `OVERWRITE`. It replaces hand-written steps such as re-declaring a pipeline and patching descriptions.

## Open Questions

None. The updated object's import action is spelled `UPDATE`, as the maintainers confirmed.
