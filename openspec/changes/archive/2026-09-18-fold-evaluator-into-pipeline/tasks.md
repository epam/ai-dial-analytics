## 1. Model: the transform on the declaration

- [x] 1.1 Add a `TransformSpec` nested type to `EnrichSpec`, held under the JSON member `transform`,
      carrying `type`, `model`, `preset`, `params`, `requestTemplate`, `inputs`, `outputs`, `outputVars`
      and `responseSchema` with the same JSON property names the evaluator served them under. Keep
      `@JsonIgnoreProperties(ignoreUnknown = true)`. **Nested, not flat**: `outputs` is already the
      compiled projection's derived mapping (`PipelineOutputDto`), and the authored outputs are a
      different shape — flattening puts two shapes under one member name.
- [x] 1.2 Move `effectiveOutputVars()` and `jsonataByOutputName()` onto `TransformSpec` (or a shared
      helper both it and `EvaluatorVersionEntity` call), preserving the `outputs`-then-`outputVars`
      resolution exactly. This accessor feeds the catalog drop/rename guard — a missed resolution is
      silent data loss, not a failed request — and it also feeds the declaration gate, which must
      require at least one **resolved** output rather than at least one `outputs` entry.
- [x] 1.3 Update the `EnrichSpec` class javadoc: it currently states that `evaluator_name` stays a
      column because it keeps its foreign key. That is no longer true and the doc is the stated rule
      other decisions cite.
- [x] 1.4 Remove `evaluatorName` / `evaluatorVersion` from `PipelineEntity`, and delete
      `PipelineRepository.findByEvaluatorNameAndEvaluatorVersionIsNull` plus the two columns from the
      JPQL constructor projection (`PipelineRepository` line ~94).
- [x] 1.5 Delete `evaluator_name` / `evaluator_version` from `CreatePipelineRequest`,
      `PatchPipelineRequest` and `PipelineDto`, and drop them from `PipelineMapper.ENRICH_ONLY`. The
      strict request mapper then refuses them at the binding with 400 on **either** kind, exactly as
      `cadence` is refused — no explicit rejection list is added, and the pre-fold 422-on-`aggregate`
      assertion becomes a 400-on-either assertion.
- [x] 1.6 Add `transform.<member>` entries to `PipelineMapper.ENRICH_ONLY`, member by member as
      `advanced.*` already is, so an `aggregate` declaration carrying a prompt is told which field it
      misplaced.
- [x] 1.7 Update `PipelineMapper.requireComplete`: the ENRICH completeness trio is `target`,
      **`transform`** and `trigger`, replacing `evaluator_name`. Leaving `evaluatorName` in the
      condition after removing the member would make every declaration patch fail; leaving nothing in
      its place would let a patch drop the transform silently.
- [x] 1.8 Update `PatchPipelineRequest.hasDeclaration()` to test `transform` instead of
      `evaluatorName`/`evaluatorVersion`, so a patch that changes only the prompt still takes the
      declaration path rather than being read as an enable-only patch.
- [x] 1.9 Give `POST` and `PATCH` different transform record types so the verb-specific rule is
      enforced by the binding rather than by hand: the create-side record omits `output_vars`, which
      makes `transform.output_vars` on a `POST` an unknown member and therefore a 400 naming the
      field, exactly as `cadence` is refused.
- [x] 1.10 Move `vars` into the block as `transform.inputs`: delete the top-level member from
      `CreatePipelineRequest`, `PatchPipelineRequest` and `PipelineDto`, and add it to both transform
      records with the same `LinkedHashMap<String, VarSpec>` type and `VarSpecDeserializer`. The flat
      `vars` then becomes a binding 400 on either kind with no explicit rejection list, exactly as
      `cadence` is. Keep the map ordered — the keys drive substitution and their order is the member
      object's field order at group grain.
- [x] 1.11 Move `spec.input_bindings` under `transform` alongside the rest, so the stored declaration
      and the wire agree on where the transform's inputs live. **`@JsonAlias` cannot do this** — it
      renames a key within one object and cannot read a member that used to sit at the parent level, and
      `EnrichSpec.inputBindings` carries no alias today (the four `@JsonAlias`es on `EnrichSpec` are the
      advanced knobs). A stored pre-fold `spec` therefore needs a real legacy-read path: keep the
      top-level `input_bindings` as a read-only shim that populates the nested value when the nested one
      is absent, or a custom setter. Without it every pre-fold spec reads back with **empty** bindings —
      which `EnrichSpec`'s own javadoc identifies as the silent-DDL-data-loss case, because the
      drop/rename guard derives its edges from them. A spec is never rewritten to migrate it, so the
      shim is permanent.
- [x] 1.12 Add `transform.inputs` to `PipelineMapper.ENRICH_ONLY` and to
      `PatchPipelineRequest.hasDeclaration()`'s member list — a patch that rebinds only the inputs must
      take the declaration path, not be read as an enable-only patch that discards them.

## 2. Validation: fold the evaluator's shape checks into the declaration gate

- [x] 2.1 Move `EvaluatorService.requireValidShape` / `requireValidLlmShape` / `requireValidSqlShape`
      into the enrich declaration gate, raising the same `PipelineValidationException` (422
      `pipeline_validation_failed`). **Do not move `requireValidShape` verbatim**: it requires at
      least one `outputs` entry, and every declaration the fold migration produces from an old-shape
      evaluator version has none. Require at least one output resolved through the accessor from 1.2.
      This is the single most likely way to ship the change broken — every pipeline registered before
      `simplify-pipeline-authoring` resolves to an old-shape version.
- [x] 2.1a While relocating `requireValidLlmShape`, **delete its blank-`prose` branch** — the
      `StringUtils.isBlank(output.getProse())` throw. It is the sole enforcement of non-blank prose:
      `OutputSpecBody.prose`'s `@NotBlank` never fires, because the `outputs` map is declared
      `@NotEmpty LinkedHashMap<String, OutputSpec>` with no `@Valid` and no `Map<String, @Valid …>`, so
      bean validation does not cascade into the values. Deleting the branch is the whole of D12's
      write-side relaxation: the mapper already carries a `null` or `{}` value through as an output
      with only a name, so both immediately mean "default it" with no mapper or deserializer change.
      Drop the decorative `@NotBlank` too, so the published schema stops advertising a constraint that
      is not enforced.
- [x] 2.1b **Do not** add a refusal for an `outputs` object declaring none of `prose`, `values` or
      `jsonata`. An earlier draft of this task (and one clause of the `enrichment-registry` delta) asked
      for a 422 there while the same paragraph said `{}` means "default it"; the two cannot both hold,
      because `{}` and `{"prose": null}` bind to the same value as `null` and the refusal would reject
      one spelling of *default it* while accepting the others. **Decided: `{}`, `null`, a blank string
      and an absent value all mean "default it" and are accepted.** The delta and task 7.11c are
      corrected with it. Keep `requireValidSqlShape`'s non-blank expression check exactly as it is — a
      `sql` output cannot be defaulted from a column, and the dispatch is already type-split so the
      relaxation cannot leak across.
- [x] 2.2 Validate `outputs` against the target at declaration time — registration, patch and read —
      and reject an unresolvable output with 422 naming the output and the target. Remove every
      comment and code path that defers this to a later write.
- [x] 2.3 Validate a `sql` transform's expressions at declaration time against the pipeline's read
      source, which is now always in hand. Reject an unknown column with 422 naming the expression
      and the column.
- [x] 2.4 Reject `transform.output_vars` on `POST` with 400 naming the field, and reject a
      declaration carrying both `transform.outputs` and `transform.output_vars` with 422 on either
      verb. Accept `output_vars` and `response_schema` on `PATCH` — the round trip of a folded legacy
      declaration depends on it.
- [x] 2.5 Make `response_schema` an ordinary authored member: a declaration patch that omits it
      clears it and the pipeline composes from `outputs` thereafter. Assert the clearing path in a
      test — it is the only migration route off the legacy shape and it changes a live prompt.
- [x] 2.6 Run the template-placeholder check **at arm and preview**, not on every declaration write:
      on `PATCH … {enabled: true}`, on a registration that arms in the same call, and on
      `POST /v1/pipelines/{name}/preview`. A disabled pipeline may be stored with a `request_template`
      whose placeholders bind to nothing, and with `transform.inputs` that no placeholder names — that
      is an author composing a declaration, not an error. Shape validation stays at write time; only
      this referential check moves. Split the existing register-and-enable gate rather than adding a
      second one.
- [x] 2.7 Delete `EvaluatorDependentValidator` and `EvaluatorRegistrationService`, and their tests.
      Nothing replaces them: the target is in scope wherever the declaration is written.
- [x] 2.8 Read every output through the accessor in the **cycle scan** and the **read-side drop and
      rename guard** as well as the gate — that is `TableSchemaService.requireNoPipelineReads`, reached
      from both the drop and the rename call site, plus `pipelinesReadingEnrichment` for the
      table-delete guard. A `sql` pipeline's reads live only in its output expressions, and a guard that
      reads `transform.outputs` alone contributes zero edges for a folded old-shape declaration — which
      is silent data loss at the DDL, not a failed request. **The write-side guard
      (`requireNoPipelineWrites`) is not in scope here**: it reads the stored, derived `outputBindings`
      and never touches the accessor, so "both guards" in an earlier draft meant drop + rename, not
      reads + writes. Say which, so nobody wires the accessor into a path that does not want it.
- [x] 2.9 **Do not route response-schema composition through the accessor.** Composition reads
      `transform.outputs` **only**, and a folded declaration carrying `output_vars` and no stored
      `response_schema` must keep composing nothing — that is the exact row D4 refuses to convert,
      because giving it a composed schema sends a `response_format` where none was sent before and
      silently changes a live prompt. Every other consumer uses the accessor; this one deliberately does
      not, and the carve-out belongs in a comment at the call site, not only in the spec.

## 3. Resolution and the compiled projection

- [x] 3.1 Rework `EnrichResolver` to build `ResolvedEnrichPipeline` from the pipeline's own `spec`,
      removing the `EvaluatorService` dependency and the `resolveAll` / `resolveAllIfPresent` calls.
- [x] 3.2 Delete `PipelineMapper.inlinedEvaluatorOf` and the `PipelineDto.evaluator` member. Every fact
      it carried is already on the entry (`name`, `generation`, `updated_at`, `transform`) except the
      resolved schema, which 3.2a adds. `EvaluatorVersionDto` stays, serving the archive read only, and
      its `version` stays an `int` — nothing folds a `generation` into it any more.
- [x] 3.2a Add a derived top-level `response_schema` to `PipelineDto`, served on `view=compiled` only:
      the declaration's stored schema where it has one, otherwise the composed one. **Do not** serve the
      resolved schema inside `transform` — `transform.response_schema` means "the schema this
      declaration stores", and serving a composed schema there would let a `view=compiled`
      read-modify-write client post it back as a stored one.
- [x] 3.3 Keep the stored-`response_schema`-wins-over-composition rule, now reading the stored schema
      from `transform.response_schema`.
- [x] 3.4 Serve `transform` in **both** projections, so a read-modify-write client can patch it back
      unchanged. The compiled projection's derived member set becomes: `response_schema`, `grain_key`,
      `version_column`, `outputs`, resolved `inputs` — one member swapped for one member.
- [x] 3.5 Update `ScanService`, `MaterializeService`, `EnrichPreviewService`, `EnrichmentFreshness`,
      `EnrichSpecValidator` and `PipelineService` to branch on `transform.type` rather than on
      `EvaluatorVersionEntity.getType()`.
- [x] 3.6 Update `EnrichPreviewDto`: drop `evaluator_name` and `evaluator_version`, rename
      `evaluator_type` to `type`, keep `response_schema_source` — an author previewing a folded legacy
      declaration needs to know whether the schema is stored or composed.
- [x] 3.7 Add the prose default to `ResponseSchemaComposer.property()`: declared prose, else the
      column's `description`, else omit the member. The `ColumnMappingEntity` is already the method's
      second parameter, so nothing is plumbed, and the existing blank check already omits a blank
      description — the fallback slots in beside it. Update the class javadoc, which currently
      enumerates what comes from the column and what comes from the declaration, and the composition
      table it mirrors.
- [x] 3.8 Apply the default at **composition only**. It must not be written into the stored `spec`, must
      not appear in `view=source`, and must not appear in the authored `transform.outputs` under
      `view=compiled` — only in the derived `response_schema`. A derived default echoed onto the
      authored surface is sent back by the next read-modify-write `PATCH`, becomes authored, and
      silently stops tracking the column, which is precisely the round trip this change is protecting.
- [x] 3.9 Add a preview note for an `llm` output that composed no `description` — no declared prose and
      no column description. Put it beside the existing `NO_SCHEMA_NOTE` in `EnrichPreviewService`. A
      note, not a refusal: the declaration is storable and armable, and D9's line is that storability
      and executability are separate questions.
- [x] 3.10 Widen `CatalogSchemaWriter`'s generation-bump condition to include a `description`-touching
      update, and rewrite the comment above it. That comment currently asserts the rationale D12
      reverses — "a metadata-only patch changes neither, because an output's prose comes from the
      evaluator rather than the column" — so leaving it would leave a false claim next to the code that
      falsifies it. `MetadataWrite` already carries `Change<String> description`, so a description
      update is distinguishable from a `display_name`/tag/`sensitive`/`heavy` one, and
      `PipelineGenerationService.onTargetColumnsChanged` already self-scopes to `llm`, so no Class-1
      `materialized_through_version` is discarded. Keep the existing `TableType.ENRICHMENT` scoping.

## 4. Provenance

- [x] 4.1 Add `PIPELINE_GENERATION` to `ProvenanceColumns` — `NAMES`, `DECLARED_ORDER` (directly after
      `EVALUATOR_VERSION`) and `canonicalColumns()` with type `QueryFieldType.LONG` and a canonical
      description. The type is load-bearing: `MaterializeService#addProvenance` switches on the
      declared type, so a `string` declaration would stamp `name:generation`.
- [x] 4.2 Update the `TableController` schema-endpoint descriptions that enumerate the five provenance
      columns; they must name six. **Three edits, not two**: the `POST` description's enumeration, the
      `PATCH` description's enumeration, and the `PATCH` description's "widening an explicit operation
      list into a **five-column DDL**" phrase, which carries the numeral separately.
- [x] 4.3 Update the `evaluator_version` canonical description to say it is stamped only by rows
      written before the fold, so an analyst reading an empty column on a new target is told why.
- [x] 4.4 In `EnrichWriteValidator`, **reject** a caller-supplied `evaluator_version` with 422
      `validation_error` naming the column, whether or not the row also names `pipeline_generation`.
      Nothing writes it after the fold and the feed offers no member to derive it from, so a row naming
      it is a retired spelling, refused rather than absorbed. **Do not describe it as the deploy
      detector**: `/rows` already requires `Adas-Contract-Version`, so a stale runner is refused 409
      before any validation runs and will never reach this check. What this refusal covers is the caller
      the header gate does not reach the same way — a hand-rolled `curl`, an out-of-date `adas-mcp`, any
      holder of a `WRITE` grant. An earlier draft renamed it onto `pipeline_generation`; that tolerance
      has no client left now the runner ships in the same window. Note the check order in the test, so
      nobody asserts a 422 where the running service answers 409.
- [x] 4.5 Stamp `pipeline_generation` from `MaterializeService#addProvenance` and stop stamping
      `evaluator_version` there.
- [x] 4.6 Confirm the best-effort drop still applies: a target that does not declare
      `pipeline_generation` silently drops it rather than failing the write. It stays the degraded mode
      the reconciler (4.8) falls back to, so it must keep working.

- [x] 4.7 Reject a `transform.inputs` entry on a `sql` transform with 422, as the pre-fold rule
      rejected `vars` — the member moved, the rule did not.
- [x] 4.8 Build `ProvenanceReconciler implements ApplicationRunner`, `@Order(2)`, under
      `service/enrichment/support/`. Follow the two that already exist: `ClickHouseMigrationRunner`
      (`@Order(0)`, the DDL-issuing one) and `SchemaValidator` (`@Order(1)`, which reconciles the
      mapping against the live table and whose javadoc says it "issues no DDL"). For every `ACTIVE`
      `ENRICHMENT` whose name is the `target` of a `kind = 'ENRICH'` pipeline and whose
      `column_mapping` holds no `pipeline_generation` row: issue the conditional `ALTER` through
      `ClickHouseDdlExecutor`, then append the canonical column through `CatalogSchemaWriter`. DDL
      first, catalog second — `IF NOT EXISTS` is what makes that order replayable.
- [x] 4.9 Add `addColumnIfNotExistsCommand` to `TableDdlRenderer`, beside `addColumnCommand`. Do
      **not** change `addColumnCommand` itself: the schema-patch endpoint's unconditional `ADD COLUMN`
      is what makes a duplicate a 422 there, and the endpoint's duplicate check is against the catalog.
      `IF NOT EXISTS` is already the established spelling in the ClickHouse migrations (`V5`, `V7`,
      `V8`).
- [x] 4.10 Scope the reconciler to pipeline **targets**, not to all enrichments. Two concrete reasons
      to record in the javadoc: a system-owned enrichment (`dial_usage_log_payload`, seeded by V38)
      cannot be patched through the API at all — `requireModifiable` throws — so reconciling every
      enrichment would reach a table the API cannot, and a non-target enrichment can never carry the
      stamp. `PipelineEntity.target` is UNIQUE, so the target list is exact.
- [x] 4.11 **Do not** call `pipelineGenerations.onTargetColumnsChanged` from the reconciler. A
      provenance column binds to no output, so neither the composed `response_schema` nor the derived
      output mapping changes. Say so in the javadoc — the manual `PATCH` this replaces *would* have
      bumped every `llm` pipeline once, and skipping it is deliberate, not an omission.
- [x] 4.12 Warn and continue on failure; never fail startup. Log once with the target name, publish a
      metric, and leave the service serving on the already-defined degraded mode (4.6). ADAS liveness
      reads the aggregate `/actuator/health`, so a boot-failing contributor restart-loops the service
      over a lineage column.
- [x] 4.13 Add `ENRICHMENT_PROVENANCE_RECONCILE_ENABLED` (default `true`) with one row in
      `docs/configuration.md`. One line of description, `Required: No` — behaviour and rationale live
      in the `table-management` spec, not the table. This makes task 8.3's "no config property is
      added" false; update it.

## 4b. The runner contract version

- [x] 4b.1 Raise `RunnerContract.VERSION_VALUE` from `"1"` to `"2"`. That is the **whole** of this
      change's contract work: `2026-09-16-enforce-runner-contract-version` already ships the
      `Adas-Contract-Version` echo on every `/v1/pipelines` response (the feed included, via a servlet
      filter ahead of the security chain) and the exact-match requirement on the four runner-only
      data-plane endpoints. The runner already sends the header on every call and already classifies an
      absent or differing echo as an incompatible ADAS that it will not interpret. So the bump is what
      makes both misorderings unreachable — including the expensive one, where an old runner renders
      unsubstituted placeholders, because it never reads the body at all.
- [x] 4b.2 Update `RunnerContractShapeTest`'s golden lists. It pins `PipelineDto` as carrying
      `evaluator_name: String`, `evaluator_version: Integer`, `evaluator: EvaluatorVersionDto` and
      `vars: Map<String, VarSpecBody>` — all four of which tasks 1.5, 1.10 and 3.2 remove — plus the
      `EvaluatorVersionDto` and `EvaluatorVarDto` shapes, which stay pinned only if the runner still
      dereferences them (after 3.2 it does not, so drop them from the projection's scope and say so in
      the class note). **The build fails until 4b.1 and 4b.2 are both done**, which is the point of that
      test: updating the golden list without the bump is "a reviewable one-line lie rather than an
      invisible one".
- [x] 4b.3 Add `response_schema` and `transform` to the pinned compiled projection, and re-scope the
      class note's "what the runner dereferences" list: it currently names "the inlined evaluator, the
      `outputs` mapping, the `vars`".
- [x] 4b.4 Add **no** `contract` query parameter, **no** `FeedContractException`, **no** 426 and **no**
      body echo. An earlier draft of this change specified all four; each is either already shipped under
      a different spelling or contradicts a shipped rule (see design D8) — notably
      `pipeline-registry`'s "the control-plane reads SHALL NOT require it, so an operator, the OpenAPI
      console and a management client keep reading the registry unchanged", which `view=compiled` is.
      If a task list resurfaces asking for the parameter, it is the stale draft.
- [x] 4b.5 Test the pair: a `/v1/pipelines` read (either projection, and an error response) carries
      `Adas-Contract-Version: 2`; a data-plane call declaring `1` is refused 409 `runner_contract_mismatch`;
      one declaring nothing is refused 409 `runner_contract_required`. `RunnerContractHeaderIntegrationTest`
      and `PipelineDataContractTest` already cover these shapes — extend rather than duplicate them.

## 5. The evaluator archive

- [x] 5.1 Remove `POST /v1/evaluators` from `EvaluatorController`. Keep the three `GET` routes, and keep
      `PUT`/`DELETE` answering 409 `evaluator_immutable`.
- [x] 5.2 Reduce `EvaluatorService` to reads only — delete `register`, `resolve`, `resolveAll`,
      `resolveAllIfPresent`, `EvaluatorRef` and the shape checks (moved in task 2.1).
- [x] 5.3 Delete `CreateEvaluatorRequest` and any now-unreferenced mapper method. **Do not delete
      `OutputSpec`, `OutputSpecBody` or `OutputSpecDeserializer`** — task 1.1 needs exactly them:
      `transform.outputs` binds the same dual bare-string-or-object shape they exist for, and
      `CreateEvaluatorRequest` is their only current production user. Move them onto the transform
      records rather than removing them. Keep `EvaluatorMapper` — it serves the archive read, including
      the `input_vars` the archive still exposes (task 6.5's "nothing reads it" is about the fold, not
      about the archive).
- [x] 5.4 Assert by test that no pipeline path reads `EvaluatorRepository` or
      `EvaluatorVersionRepository` — registration, patch, both projections, preview and write-back.
      That is what makes the eventual drop of the two tables a data decision rather than a code one.

## 6. Migration

- [x] 6.1 Write the Flyway migration as **V41**, after `V39__evaluator_outputs.sql` (from
      `simplify-pipeline-authoring`) and `V40__payload_partition_alignment.sql` (from the pipeline-read
      bounding work). A version collision never conflicts in git and instead fails every test at
      startup.
- [x] 6.2 Pre-flight: `DO $$ … RAISE EXCEPTION` naming every `kind = 'ENRICH'` pipeline whose
      `COALESCE(evaluator_version, max(version))` resolves to no `evaluator_version` row, and
      `RAISE NOTICE` the `(pipeline, evaluator_name, resolved version)` triples about to be folded —
      the only record of that mapping once the columns are gone.
- [x] 6.3 Fold each resolved version into `pipeline.spec` under a `transform` key with the generic
      `UPDATE … FROM` in design.md §Migration Plan. Lower-case the `type` and `preset` values (they
      are uppercase `TEXT` with CHECKs) so the folded value equals what a later `PATCH` would store.
- [x] 6.4 Strip nulls from the folded block: wrap only the four **scalar** members in
      `jsonb_strip_nulls` and concatenate `params`, `outputs`, `output_vars` and `response_schema`
      conditionally. Never `jsonb_strip_nulls` the whole object — it recurses, and a stored
      `response_schema` or `params` may legitimately contain a JSON null. Omit `params` when `{}` and
      `output_vars` when `[]`. Test `outputs` as `IS NOT NULL AND outputs <> '[]'::jsonb`, not
      `IS NOT NULL` alone: `EvaluatorVersionEntity.effectiveOutputVars()` treats an **empty** `outputs`
      as old-shape (`outputs == null || outputs.isEmpty()`), so `IS NOT NULL` alone would fold a row the
      accessor then reads the other way.
- [x] 6.5 Do not fold `input_vars`: `EvaluatorService` has written it empty since the `vars` shape
      landed and nothing reads it. Say so in the header comment so its absence reads as a decision.
- [x] 6.6 Drop the `pipeline_evaluator_iff_enrich` CHECK **and nothing else**. It is
      `CHECK ((kind = 'ENRICH') = (evaluator_name IS NOT NULL))` from V36 and is the only object making
      `evaluator_name` required for an ENRICH row — V36:107-109 says so, and neither column carries a
      column-level `NOT NULL`. **Retain** `pipeline.evaluator_name` (with its FK and
      `pipeline_evaluator_name_idx`) and `pipeline.evaluator_version`, unmapped and unread: the
      outgoing pod maps both through `PipelineRepository.findAllDeclarations()`'s constructor projection
      and `findByEvaluatorNameAndEvaluatorVersionIsNull`, so dropping them would fail every pipeline
      read on it for the length of the roll — and a `maxUnavailable: 0` rollout guarantees such a
      pod exists. Leave both `pipeline_aud` mirror columns in place too. Say in the header comment that
      the columns are deliberately retained and which follow-up change drops them, so their presence
      does not read as an oversight.
- [x] 6.6b Record the follow-up change that drops the two retained columns. It is named in the
      proposal and in design D11/Open Question 2; do not leave it as an unwritten intention, because a
      deferred contract step nobody schedules is how dead schema accumulates.
- [x] 6.6a **Assert the fold covered every row**, as a statement after the `UPDATE` rather than only
      as a pre-flight prediction: `RAISE EXCEPTION` if any `kind = 'ENRICH'` row has
      `spec -> 'transform' IS NULL`. The `UPDATE … FROM` skips a non-joining row with no error and no
      row count, and the pre-flight and the `UPDATE` resolve `COALESCE(pinned, max(version))`
      independently — Flyway runs the migration in one transaction but at READ COMMITTED, so each
      statement takes a fresh snapshot and a concurrent admin write between the two would make the
      `RAISE NOTICE` log disagree with what was written. Better still, resolve **once** into a CTE-backed
      temp table, `RAISE NOTICE` from it and drive the `UPDATE` from it: one snapshot, and the log
      provably matches the write. Either way this assertion is what makes "every ENRICH pipeline has a
      transform" a checked fact.
- [x] 6.7 Bump nothing: the `UPDATE` must not touch `generation` or `updated_at`. The migration folds
      in the transform each pipeline already resolved to, so a bump would re-sync every runner lane and
      discard every Class-1 materialized position for a change the migration itself did not make. The
      release *does* change the projection — that is the deploy's job, gated by the contract — but the
      `UPDATE` is not what changes it.
- [x] 6.8 Confirm no `pipeline_aud` column is added — every folded member lands in `spec`, which
      `pipeline_aud` already mirrors. A missing mirror column fails **every** pipeline write at commit.
- [x] 6.9 Write the migration's header comment to the standard of V36: what is carried, what is
      deliberately not, and what discarding would have cost. Never edit it after it is applied anywhere
      — Flyway checksums comments and a mismatch aborts startup.

## 7. Tests

- [x] 7.1 Integration test: register an `enrich` pipeline carrying an inline `llm` transform, read both
      projections, patch only the prompt, and assert `generation` and `updated_at` both advance.
- [x] 7.2 Integration test: register an inline `sql` transform, assert an expression naming an unknown
      source column is refused at declaration rather than at materialize.
- [x] 7.2a Follow `CatalogDenormalizationMigrationTest` for every migration test below: it is the one
      test in the repo that drives Flyway itself — throwaway database on the shared container via
      `PostgresContainerSupport.jdbcUrlFor(...)`, `flyway("<n-1>").migrate()` to stop before the
      migration under test, seed pre-migration rows over plain JDBC, then `flyway(null).migrate()`. With
      the fold at V41 the stop target is **`"40"`**, not `"39"` — stopping at 39 would silently also
      skip V40. Assert a `RAISE EXCEPTION` with
      `assertThatThrownBy(() -> flyway.migrate()).hasMessageContaining(...)`, as that test does for a
      constraint name.
- [x] 7.3 Migration test on a fixture holding **both** output shapes plus a pinned and an unpinned
      pipeline: assert each resolves to the right version, that `output_vars` and `response_schema` are
      carried byte for byte, that no `outputs` array is synthesised, and that the folded block carries
      no `model: null` / `preset: null` keys. A mixture is the expected state at fold time, not an edge
      case: `simplify-pipeline-authoring` has already shipped, so evaluators registered either side of
      that change coexist in both shapes.
- [x] 7.3a **Cover the old-shape path by fixture, because no environment may cover it any more.** A
      registry whose evaluators and pipelines were re-registered in the `outputs` shape after that
      change folds to new-shape transforms, so a broken `outputs`-else-`output_vars` accessor would
      surface on **nothing** there. It is still reachable — the fold resolves
      `COALESCE(pinned, max(version))` per pipeline, so any pipeline whose pinned or latest version
      predates that change still folds old-shape, on any deployment.
      Assert the accessor from every consumer that reads it: the gate, the cycle scan, and **both**
      drop/rename guards, where a missed resolution is silent DDL data loss rather than a failed
      request.
- [x] 7.4 Migration test: a pipeline pinning a non-existent version aborts the migration with the
      pre-flight exception naming it. Also assert 6.6a's post-`UPDATE` guard fires — seed an ENRICH row
      whose evaluator has zero versions, so `max(version)` is NULL and the join cannot match.
- [x] 7.5 Migration test: a folded old-shape pipeline is readable, patchable and enablable afterwards —
      the gate accepts a declaration whose only outputs are `output_vars`. This is the regression 2.1
      is about.
- [x] 7.6 Round-trip test: `GET view=source` on a folded old-shape pipeline, strip the server-owned
      members (`generation`, `updated_at`, `state` — a write does not accept them, so the absolute
      "send the body back" form fails at the binding), `PATCH` the declaration back unchanged, and
      assert the stored `spec` and the compiled projection are byte-identical. Assert separately that
      a **compiled** body sent back is refused 400 on its derived members rather than accepted.
- [x] 7.7 Wire-shape test pinning the compiled projection's serialized JSON: no `evaluator` object, no
      top-level `vars`, `transform.inputs` and `transform.outputs` present, and a derived top-level
      `response_schema`. No `contract` body member — the version is the `Adas-Contract-Version` response
      header (4b). This is the contract the runner's own change is written against, so it is the test
      the two repositories agree through, and `RunnerContractShapeTest` is its build-time half.
- [x] 7.8 Write-back test: a row carrying `evaluator_version` is refused 422 naming the column, with or
      without `pipeline_generation` beside it and whether or not the target carries either; a row
      carrying only `pipeline_generation` is written; a target lacking the column drops the stamp and
      still writes its labels.
- [x] 7.9 Assert `POST /v1/evaluators` answers **405** `method_not_allowed` — not 404: the collection
      path stays mapped for `GET`, and `DefaultExceptionHandler` maps
      `HttpRequestMethodNotSupportedException` to 405. Assert the three `GET` routes still serve
      archived rows, and that no pipeline path reads either evaluator table.
- [x] 7.10 Assert `evaluator_name` on `kind: aggregate` is now **400** at the binding, not 422 — the
      existing cross-kind test asserts the old rule.
- [x] 7.11 Schema-definition test: a new enrichment receives six provenance columns, with
      `pipeline_generation` typed `long` and ordered after `evaluator_version`. Unchanged by the
      reconciler — creation-time injection still reaches new tables only.
- [x] 7.11a Migration test for 6.6: after V41, `pipeline_evaluator_iff_enrich` is gone and **both**
      `pipeline.evaluator_name` and `pipeline.evaluator_version` still exist, with the FK and index
      intact. Assert an ENRICH row is insertable with both NULL. The point of the test is the
      *retention* — a later well-meaning edit that adds the `DROP COLUMN`s back would silently break a
      rolling deploy, and nothing else in the suite would notice.
- [x] 7.11b Reconciler tests (6 cases, all against the real container): a target missing the column
      gains it in both ClickHouse and the catalog, typed `long` and tagged `system`; a second run
      issues no DDL and writes no catalog row; no pipeline's `generation` or `updated_at` moves and no
      Class-1 `materialized_through_version` is discarded; a non-target enrichment and a system-owned
      one are untouched; a target whose physical column exists but whose catalog row does not is
      completed rather than refused (this is the `IF NOT EXISTS` case, and the one a manual `PATCH`
      cannot recover from); and a failing `ALTER` logs, counts, completes startup and leaves the
      best-effort drop working.
- [x] 7.11c Prose-default tests: a declared prose wins over the column description; an output with
      `null`, `{}` or a blank string composes the column's `description`; both blank composes no
      `description` member and is neither refused at write nor at enable; the default appears in
      `response_schema` only and never in either projection's authored `transform`; a stored
      `response_schema` suppresses composition entirely so no column description is read; an object
      with none of `prose`/`values`/`jsonata` is **accepted** and defaults like `null` (see 2.1b — the
      earlier 422 was unimplementable); a `sql` output with a blank expression is still 422. `ResponseSchemaComposerTest.proseBecomesTheDescription` is the sibling to model these on.
- [x] 7.11d Bump-inversion tests for 3.10: a `description`-only patch on an `llm` target bumps
      `generation` and `updated_at`; a `display_name`-only patch bumps nothing; a `description` patch on
      a Class-1 target bumps nothing and preserves `materialized_through_version`; a `description` patch
      on an enrichment no pipeline targets bumps nothing. **No test asserts the current
      no-bump-on-metadata behaviour**, so nothing here is a fix — these are the first assertions on that
      path, which is also why the old rule could be inverted without a failing build to catch it.
- [x] 7.12 Build fixtures from synthetic shapes modelled on the real ones — do not check real
      deployment prompts or declarations into test resources.
- [x] 7.13a Draft test: register a pipeline with `enabled: false`, a `request_template` full of
      placeholders and no `transform.inputs` — accepted 201. Then `PATCH {enabled: true}` — refused 422
      naming the placeholder, pipeline still disabled. Then `POST .../preview` — refused 422 the same
      way, rather than rendering an unsubstituted template.
- [x] 7.13b Binding test: a top-level `vars` is a **400** on either kind, and `transform.inputs` on an
      `aggregate` declaration is a **422** naming `transform.inputs`.
- [x] 7.14 Name test methods so Checkstyle passes: no `aFooBar` (second character must be lowercase) and
      no two consecutive capitals. This fails `build`, not just lint.

## 8. Documentation and consumers

- [x] 8.1 Update `openspec/config.yaml`'s `context:` in two places: the frozen bare-array list that
      names `/v1/evaluators` (still accurate, but the registry is now an archive) and the
      "the evaluator registry is deliberately not audited" note, which now describes an immutable
      archive rather than a live gap in attribution.
- [x] 8.2 Update `README.md`'s audit paragraph, which names the evaluator registry as outside the
      trail. **`docs/release-0.2-upgrade.md` is deliberately NOT edited** — this corrects an earlier
      draft of this task, which asked for a forward-pointer in §4.1 and §4.3 saying the evaluator write
      surface is removed in the next release. It is a **release-scoped, read-only** document: it
      describes the 0.1 -> 0.2 upgrade as that upgrade was performed, and an operator reaching for it is
      reading history, not current instructions. Annotating it with what a later release does makes it
      describe two releases and belong to neither.
- [x] 8.3 `docs/configuration.md` is touched on **two** counts. (a) One new row for
      `ENRICHMENT_PROVENANCE_RECONCILE_ENABLED` (4.13): one line of description, `Required: No`,
      behaviour and rationale in the `table-management` spec rather than the table. Keep the padding of
      any table you touch — fix the oversized cell, never unpad the table.
      (b) Its "Release and deployment order" section gains **two sentences and no argument**. Its four
      numbered steps stay exactly as they are. Step 2 gains the warning this change contributes, from
      D11: **issue no pipeline write for the length of the roll**, folded inline where the operator is
      already reading it. And one line records that work the runner still had queued needs no drain,
      pause or count, because the new build adopts and runs it. That is the whole edit. Deliberately
      **not** carried across: D11's required-versus-recommended levelling (9.2a is the requirement,
      9.1a/9.2/9.3 the recommendation) and the cost-of-skipping-the-quiesce paragraph. Both are
      change-document reasoning; a property reference tells an operator what to do and what not to do,
      and an operator who follows the four steps gets the recommended sequence and obeys the
      requirement inside it. Three earlier drafts of this task were wrong in three directions: one left
      the section exactly as committed on the grounds that D11 argued the weaker position; one added a
      queue-drain step with a `scheduled_tasks` verification query, withdrawn because it asks operators to
      run SQL and call an API for a hazard the runner now handles in code; and one pushed the whole
      levelling argument into the doc as a section preamble.
      **Left stale on purpose, and flagged rather than fixed**: the section's closing "safety net"
      paragraph still says a skewed deploy "still charges model calls whose write-back is then refused
      and dead-lettered on the first attempt". Against the post-guard runner that is no longer true —
      `AdasWorkGate.requireCompatible` throws `AdasIncompatibleHoldException`, which holds the item with
      no attempt consumed and nothing dead-lettered. It describes the predecessor image. Correcting it
      is a one-sentence edit that belongs to whoever next opens that section; it is named here so it is
      not mistaken for current behaviour.
- [x] 8.3a Do **not** rewrite `docs/release-0.2-upgrade.md` §2's deploy-order warning. It describes
      upgrading *from* a guard-less runner, where "the old runner still charges its queued DIAL calls"
      is accurate; that was the deploy the guard was built for. Its §4.3 evaluator-authoring passages
      are **also** left alone — see 8.2, which withdraws the forward-pointer an earlier draft wanted
      there: the whole document is release-scoped and read-only.
- [x] 8.4 Residual vocabulary, stated accurately — an earlier draft of this task said "four
      requirements", then named seven, said the residue was "in scenarios" when four of the seven carry
      it in normative body prose, and claimed "none of them states anything the fold makes false", which
      was wrong for three. What is actually left, and why:

      **Now carried by this change** (they named the renamed member, not an adjective, so leaving them
      would have shipped a spec naming a member that does not exist): `enrichment-registry`'s "An ENRICH
      pipeline's read source is declarable" and "An ENRICH pipeline reads its read source's entity"
      (both normative `vars`), `enrichment-runtime`'s "An ENRICH pipeline's read window is clamped to
      its referenced enrichments' positions" (normative `vars`, twice), `audit-trail`'s "Point-in-time
      reads for pipeline and saved query" (asserts a snapshot cannot resolve a pinned evaluator, and
      asserts the `evaluator` member this change deletes), and `pipeline-registry`'s "A requested
      projection is served as requested or refused" (enumerates the derived member set).

      **Deliberately left** — mostly the bare adjective "a `sql` evaluator", plus one requirement whose
      evaluator mention is accurate *history*, in requirements this change does not otherwise open.
      The list below is the whole of it, not a sample: `class1-enrichment`'s "Class-1 derivations may read map and array
      source columns" (6 scenarios), "The Class-1 gate refuses a window function explicitly" (normative
      + 2 scenarios) and "A materialize records the position it wrote through, against its pipeline"
      (rationale prose); `enrichment-registry`'s "An enrichment-sourced value is readable only where
      absence is representable" and "Every governed SQL fragment is deterministic"; `enrichment-runtime`'s
      "Scan projection and version isolation" ("evaluator inputs" in one scenario) and "The governed data
      plane requires an exact runner contract version" (its rationale recounts a past release leaving a
      runner "holding an authored projection with no evaluator" — accurate history, and the requirement
      is deliberately stated over the transport rather than over any one field);
      `table-management`'s "Delete a table definition" ("its evaluator's output expressions"); and
      `pipeline-registry`'s "A pipeline name is an identity, not a SQL identifier" (cites kebab-case
      evaluator names as precedent, which the archive still supplies). Sweep them in the next change
      that touches each.
- [x] 8.4a The three capability **Purpose** sections describe the retired object as current and are not
      requirements, so no delta can carry them — they need a direct edit to
      `openspec/specs/<cap>/spec.md` **at archive time**, which is where this task is deliberately left
      open: an implementation pass cannot land it, because editing `openspec/specs/` directly before the
      archive would put the shipped-behaviour file ahead of the change that ships it. `/opsx:archive` is
      the step that applies it. `enrichment-registry`'s
      Purpose is stale five times over ("registering versioned **evaluators**", "what this capability
      owns is the evaluator and …", "An evaluator is an immutable, versioned definition…", "binds a
      **pinned evaluator**", the gate list "(catalog, read source, filter, evaluator, trigger, …)", and
      a feed URL that omits `view=compiled`); `class1-enrichment`'s says "An **evaluator** declares
      `type=sql`…" for a requirement this change renames to "SQL-computed transforms";
      `enrichment-runtime`'s says the write-back "lands **evaluator output**".
- [ ] 8.5 Update `adas-mcp` in the same window: remove the evaluator write tool, keep the read tools,
      extend the pipeline tools with `transform`. **Another repository**, so it is left open here
      deliberately rather than forgotten — the ADAS half is complete and the two deploy in any order. Compare the regenerated OpenAPI snapshot
      structurally (`--sort-keys`) — a plain regeneration churns ~1400 lines of hash-order noise.

## 8b. Spec-delta hygiene (done at review, listed so it is not undone)

- [x] 8b.1 Keep the `class1-enrichment` delta rebased on **current** main text. Two requirements were
      written against a base predating `enforce-runner-contract-version` and `add-relative-time-functions`
      and would have silently reverted shipped rules on archive: "Probe-then-write window semantics"
      (the "a probe is two-column in what it returns, not in what it reads, and SHALL NOT be assumed
      cheap" paragraph, the narrowed-write prefix guarantee, and both their scenarios) and "Class-1
      expression governance" (gate item 10 **Deterministic**, its rationale, and the `now()` scenario —
      reverting it would have restored the now-false claim that determinism is guaranteed by
      construction because `now()` is absent from the catalog). Re-run a main-vs-delta scenario diff
      before archiving; a MODIFIED requirement replaces its base wholesale, so a stale base is a
      silent revert that the validator does not catch.
- [x] 8b.2 Five requirements were added to the deltas because the fold makes them false, not merely
      dated: `audit-trail`'s "Point-in-time reads for pipeline and saved query",
      `enrichment-runtime`'s read-window clamp, `pipeline-registry`'s "A requested projection is served
      as requested or refused", and `enrichment-registry`'s "An ENRICH pipeline's read source is
      declarable" and "An ENRICH pipeline reads its read source's entity". Do not drop them for size.

## 9. Verification and roll-out

- [x] 9.1 **There is no pre-upgrade *table* step.** Nothing is patched per target and no table is
      edited before the deploy: Flyway folds the declarations and `ProvenanceReconciler` (4.8) adds
      `pipeline_generation` on the same startup. If a reviewer re-adds a pre-deploy schema patch here,
      design D10 says why it was withdrawn — it is unbounded *and* less safe than the reconciler. This
      says nothing about the **deploy sequence**, which 9.1a–9.3 prescribe and design D11 justifies —
      one hard requirement (9.2a) and a recommended order around it (9.1a, 9.2, 9.3).
- [ ] 9.1a **Nothing is drained, paused or counted before the deploy.** Scale the runner to 0 and go;
      work it had queued is handled by the new runner build rather than by an operator. Recorded here
      because two earlier drafts of this task said the opposite — and because removing this step is
      what demoted the whole sequence from a required window to a recommended one (9.3). The reasoning
      is worth keeping:
      scaling to 0 stops execution but does **not** empty `scheduled_tasks`, and a work item enqueued
      before the window names `evaluatorVersion` in its payload, which db-scheduler's
      `JacksonSerializer` reads as `pipelineGeneration = 0` because it disables
      `FAIL_ON_UNKNOWN_PROPERTIES` (verified in 15.6.0). ADAS never serves generation 0, so the runner
      treats it as an unambiguous *pre-fold* marker: on its chunk and write lanes it adopts the live
      generation and runs the item, and on its group lane it discards it because the readiness sweep
      re-derives that group anyway. A shutdown drain was considered and rejected — it would have to run
      in the image being replaced, which already shipped without it. See the runner's
      `read-transform-directly`, which owns this behaviour; this task exists so nobody reinstates the
      operator step.
- [ ] 9.2 Deploy ADAS, then the runner's `read-transform-directly` build, **in that order and in one
      window — recommended, not required** (`adas-mcp` is unordered — it makes no data-plane call). No
      recreate strategy and no replica constraint: 6.6 retains the two columns the outgoing pod reads.
      Scale the runner back to 1 only after its new image is deployed; a ready pod is the confirmation
      that the pair matches. The order is a recommendation because **both misorderings are refused
      rather than absorbed**: the runner's `AdasWorkGate.requireCompatible` throws
      `AdasIncompatibleHoldException`, which *holds* the work item — rescheduled, no attempt consumed,
      nothing archived — and ADAS's data plane answers 409 before any lookup, so a skew stalls and
      self-heals once both sides are up. 9.2a is the one thing here that is **not** negotiable, and
      9.3 says what following the recommendation buys.
- [ ] 9.2a **The one hard requirement of this roll-out: issue no pipeline write while ADAS is
      rolling** — no `POST`, `PATCH` or `DELETE` on
      `/v1/pipelines`, from an operator, a script or `adas-mcp`, from the first new pod until the last
      old one is gone. `PipelineService.validateAndStore` ends in `setSpec(specCodec.write(spec))` and is
      reached from a bare `PATCH {"enabled": true}`; the pre-fold `EnrichSpec` is
      `@JsonIgnoreProperties(ignoreUnknown = true)` with no `transform` member, so an old pod handling
      that write rewrites the spec **without** the transform V41 just folded in and with
      `input_bindings` reset to `[]`, silently. `maxUnavailable: 0` guarantees an old pod is there for
      the length of the roll, so no deploy strategy and no ordering removes this — only not writing
      does. ADAS **fails fast** when it later reads such a spec, so the erasure is loud rather than
      silent, which is a better failure and not a lesser one: if a write does slip through, re-`PATCH`
      the affected pipeline's `transform` from the migration's `RAISE NOTICE` record after the roll
      completes.
- [ ] 9.3 Quiescing the runner is **recommended, not required** — this corrects the previous wording of
      this task, which said the opposite. `docs/configuration.md`'s "Release and deployment order" is
      the operator-facing form of 9.1a–9.2a: follow it, and note that it states the procedure without
      grading its steps, deliberately. The grading is here. **This is the third position this change
      has taken on the question**, and the trail matters more than the current answer: first there was
      said to be no ordered window at all; then a *required* window, resting on two legs — the runner's
      queued work items, which then needed an operator drain, and the outgoing ADAS pod's write path.
      **That required-window conclusion was correct while the drain was still needed** — a procedure
      with an operator step in it genuinely has to be sequenced. It went stale when the drain was
      replaced by code (9.1a), not when anyone found a flaw in it. What is left is one leg, 9.2a, and
      it is a prohibition rather than an ordering.
      *Why the quiesce is still worth doing, which is the honest cost of skipping it.* Leaving the
      runner up through the ADAS roll is not a clean skew: both ADAS builds serve concurrently during a
      rolling update, so a running old runner can read a *compatible* echo from an outgoing pod, start
      work, pay for a model call, and then have its write refused 409 by a new pod. That work is
      **held, not failed** — no attempt consumed — and the new runner build's pre-fold adoption writes
      it after the upgrade, so it is **recovered, not lost**. The cost is a deferred write and a
      compatibility signal that flaps for the length of the roll: noise, not damage. Quiescing buys
      that off for a few minutes of frozen Class-1 positions, during which consumers such as
      `sessions_rollup` and `session-insights-live` clamp for a cadence and then self-heal.
      *If the runner is skewed rather than quiesced*, expect the 409 arm: data-plane calls refused
      before any lookup or read, cursors untouched, work held rather than dead-lettered, and the same
      frozen Class-1 positions. That arm is what makes an accidental **skew** cheap; it does not cover
      a queued work item, which makes no ADAS call before its model call and is instead answered by
      9.1a's pre-fold adoption.
      *One thing the deploy does close, and it is not a consequence of any skipped step.* Anything that
      dead-letters **after** the new runner image is running requeues normally — with the pipeline left
      **enabled**, since requeueing into a disabled pipeline destroys the stored labels — but anything
      dead-lettered **before** it cannot: `DlqRequeueService.foldEra` is kept and refuses every pre-fold
      payload permanently, and pre-0.3 `dlq_item` rows outlive the deploy, so such an item is re-derived
      at full model cost rather than replayed. That holds however the deploy is sequenced and whether
      or not the runner is quiesced; no drain would have prevented it, which is worth saying because
      the withdrawn drain (9.1a) used to sit next to this sentence and could be read as its remedy.
      Check `/v1/pipelines/paused` afterwards.
- [ ] 9.4 Do not disable or re-enable any pipeline during the roll-out. Toggling `enabled` bumps
      `generation`, which discards the Class-1 materialized mark, and a disabled pipeline destroys the
      labels of anything requeued into it.
- [ ] 9.5 Verify the guard on the way through, which is cheaper than trusting it: with ADAS deployed
      and the runner not yet, confirm the **old** runner image reads `Adas-Contract-Version: 2`,
      classifies the feed incompatible, logs it once, publishes the metric and evaluates nothing — rather
      than rendering unsubstituted placeholders. Its data-plane calls will also answer **409**; expect
      both, so neither reads as a second fault. This is the one moment the guard can be observed doing
      its job, and it is worth the minute: the paid-work gate is what bounds an *accidental* skew, and
      this repository asserts it without being able to verify it. **Confirm the stall is silent rather
      than charged**: no DIAL spend against the frozen lanes while the pair is skewed. If it charges,
      an accidental misorder is more expensive than D11 grades it — the recommended sequence and the
      9.2a requirement are unaffected either way, since neither rests on the gate.
- [ ] 9.5a Confirm the reconciler ran: each existing enrich target carries `pipeline_generation` in both
      ClickHouse and the catalog after the first ADAS start, no pipeline's `generation` moved as a
      result, and the second start logs no DDL.
- [ ] 9.6 Run the local enrichment-runner harness against the new feed with the **new** runner build
      and confirm it syncs, scans and writes back — including that its stamp lands in
      `pipeline_generation`. Test the `evaluator_version` refusal with a **hand-rolled request** carrying
      the current contract header, not with a stale build: a stale build is refused 409 at the header
      and never reaches the 422.
- [ ] 9.7 Confirm the runner's `pipeline_cursor` and `group_state` rows are untouched across both
      deploys — no re-derivation. Those two are keyed by pipeline name alone; `dlq_item` and
      `spend_counter` are **re-keyed** by the runner's `V9__rekey_state_on_pipeline_generation.sql`,
      which renames and widens their `evaluator_version` column and deletes no row. Expect the two
      residues rather than reading them as faults: a pre-cut-over DLQ row no longer collapses with a
      post-cut-over failure for the same grain (two rows until the 30-day sweep), and per-generation
      spend reporting straddles two number spaces. The daily spend-pause gate is unaffected — it sums
      across that key component.
- [x] 9.8 `./gradlew clean build` passes (the Testcontainers suites need a reachable Docker daemon).
- [ ] 9.9 Keep this change and the runner's `read-transform-directly` consistent: the wire facts live
      in both, and 7.7 is the test they agree through. A change to either side's shape is a change to
      both documents. The runner's change has been corrected in the same review: its withdrawn draft
      (a mandatory `contract=N`, a 426 `feed_contract_unsupported` and a body echo) is gone, and it now
      raises `AdasContract.VERSION` to 2 against the header its own shipped `pipeline-sync` requirement
      already sends and verifies. Two consequences of this change landed there and are worth knowing
      here: its `Admission refuses a pipeline missing any field its lane dereferences` requirement was
      written **for** this fold and refuses an entry with no inlined `evaluator`, so it is retargeted to
      `transform` — left alone it would refuse every pipeline; and `evaluator_version` turns out to be a
      component of **three of the runner's own state keys** (the deterministic work-item instance id,
      the DLQ row-identity index and the `spend_counter` primary key), so the runner takes a migration
      renaming and widening two columns. Neither is ADAS's work, but a review of this change that
      assumed the runner only had DTO edits to make was wrong about the blast radius.
