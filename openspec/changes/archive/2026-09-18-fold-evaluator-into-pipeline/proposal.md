## Why

Three standing complaints about the evaluator registry share one root cause: **an evaluator cannot be
deleted** (test evaluators created on real environments are permanent), **an evaluator cannot be saved
incomplete** (every required field must be supplied at creation, because there are no updates), and
**the abstraction does not pay for itself**. Registering *is* publishing, and publishing *is* the audit
record, so neither problem can be fixed without weakening the immutability claim the registry rests on.

In practice, the one benefit that would justify the separate object — reuse — has never
occurred: every `enrich` pipeline names a distinct evaluator, so maximum reuse is 1, and many
evaluators have no pipeline at all. Reuse is also *structurally* blocked, because `outputs` is keyed
by target column names and the spec therefore requires a second target to "reuse the same column names".
The one case that demanded reuse could not use it: `conversation-insights` and `session-insights` are the
same prompt at two grains, built as two separate evaluators because the targets' columns differ. Every
output binding across the existing pipelines is an identity mapping (`{"column":"title","var":"title"}`),
so the binding indirection carries no information either.

Everything else the evaluator provides, the pipeline already provides better: it is Envers-audited with a
full attributed snapshot per revision (evaluators are deliberately **not** audited and carry no
attribution at all), it has a `generation` change token, it can be patched while enabled, and it can be
deleted. Folding the evaluator into the pipeline dissolves all three complaints instead of patching them.

## What Changes

- **BREAKING** The `enrich` declaration absorbs the evaluator definition as one nested, enrich-only
  **`transform`** block carrying `type`, `model`, `preset`, `params`, `request_template`, `inputs` and
  `outputs`, stored in the pipeline's `spec` JSONB. It is nested rather than flat because `outputs`
  already names the *derived* output mapping at the top level of a pipeline read; two shapes under one
  member name is what the registry's one-spelling rule exists to prevent. `evaluator_name` and
  `evaluator_version` are removed from the declaration and from its wire shape.
- **BREAKING** The top-level `vars` map moves inside the block as **`transform.inputs`**, so the
  transform's two halves read as a pair rather than as a mechanism (`vars`) beside a role (`outputs`).
  `vars` was already meaningful only for `transform.type: llm` — a `sql` pipeline is refused for
  declaring any — so the one member dispatched on `type` was the one member outside the type-dispatched
  block. The collision with the top-level `inputs` (the table list) is resolved by nesting, the same rule
  that already puts a group key under `trigger.group_by`, and the two are different JSON types so a
  misplaced member is a binding 400 rather than a silent accept.
- **BREAKING** The inlined **`evaluator` object is deleted** from the compiled projection. It existed
  solely to spare the runner a redeploy, which the `transform.inputs` rename spends anyway; keeping it
  would ship a standing exception to the one-spelling rule and duplicate a multi-kilobyte
  `request_template` in every feed entry. Every fact it carried is already on the entry — `name`,
  `generation`, `updated_at` and the `transform`'s own members — except the **resolved response schema**,
  which becomes a derived top-level `response_schema` (stored where the declaration stores one, otherwise
  composed). The derived member set therefore swaps one member for one member: `response_schema`,
  `grain_key`, `version_column`, `outputs`, resolved `inputs`.
- **BREAKING** `POST /v1/evaluators` is removed. `GET /v1/evaluators` and
  `GET /v1/evaluators/{name}/versions/{version}` are retained as a **read-only archive** of definitions
  as they stood before the fold. `PUT`/`DELETE` on a version keep answering 409 `evaluator_immutable`.
- An `enrich` pipeline is created, edited and deleted through the existing `/v1/pipelines` surface only.
  This is what closes the original request: deleting a test enrichment becomes
  `DELETE /v1/pipelines/{name}`, which already exists, is already audited, and already leaves target rows
  intact.
- The two legacy output members — `output_vars` and a stored `response_schema` — become part of the
  authored surface: readable under `view=source` and accepted on `PATCH`, so a read-modify-write client
  round-trips a folded declaration without deleting them. `output_vars` is refused on `POST` (a new
  declaration authors `outputs`), and a declaration carrying both shapes is refused with 422.
- `pipeline_generation` joins the permitted provenance columns and the canonical set injected into a new
  enrichment's schema, carrying the pipeline `generation` that produced a row. The updated runner stamps
  it directly. Because injection reaches new tables only, **a startup reconciler adds the column to the
  existing enrich targets** — idempotently, bumping no `generation`, warning rather than failing
  startup — so no operator patches a target by hand before the deploy (design D10).
- An output's **`prose` becomes optional**, defaulting to the target column's `description` and derived
  at composition time rather than stored. Composition already reads an output's `type`, `enum` domain
  and enum order off its column; the description was the one fact an author had to duplicate. An absent
  value, `null`, `{}` and a blank string all mean "default it" — there is deliberately no separate
  refusal of "an object declaring none of the three", because once `prose` is optional `{}` binds to
  exactly what `null` binds to and a refusal could only reject one spelling while accepting the
  others. `sql` outputs are unaffected —
  their string is an expression and stays required. **Consequence this change owns:** a column
  `description` is now a live input to the composed prompt, so a `description`-touching metadata patch
  on an `llm` target bumps `generation`, where the shipped rule says a metadata-only patch bumps nothing
  (design D12). A caller-supplied **`evaluator_version` is refused** at `POST /v1/pipelines/{name}/rows`
  with 422 rather than tolerated: with the inlined object gone, a row naming that column is proof of a
  stale runner, so the tolerance an earlier draft specified (renaming it onto `pipeline_generation`)
  has no client left to serve. It is **not** a deploy detector — `/rows` already refuses a stale runner
  with 409 on the contract header before any validation runs — but the ordinary rule that a retired
  spelling is refused rather than absorbed, applied to a provenance column name, earning its place
  against a hand-rolled write or an out-of-date `adas-mcp`. Already-stamped `evaluator_version` values
  stay valid and readable; nothing writes that column again.
- **The roll-out needs no pre-upgrade table edits, no deploy strategy and no operator step. It carries
  exactly one hard requirement, and a recommended sequence around it.** The requirement: **no pipeline
  write — `POST`, `PATCH` or `DELETE` on `/v1/pipelines` — while ADAS is rolling.** Nothing guards it.
  The recommendation: scale the runner to 0, deploy ADAS, deploy the runner, scale back. Nothing is
  drained, paused or counted first, and `adas-mcp` is unordered.
  *This is the third position this proposal has taken on the question, and the moves are worth
  recording.* The first draft said there was no ordered window at all. The second said the window was
  required, and rested it on two legs: the runner's queued work items, which then needed an operator
  drain, and the outgoing ADAS pod's write path. **That conclusion was correct while the drain was
  still needed**, and it went stale the moment the drain was replaced by code rather than by argument.
  Scaling to 0 stops execution but does not empty `scheduled_tasks`, and db-scheduler's
  `JacksonSerializer` disables `FAIL_ON_UNKNOWN_PROPERTIES` (verified in 15.6.0), so a work item
  enqueued before the fold deserializes on the new image with `pipelineGeneration = 0` — a value ADAS
  never serves. The runner therefore reads 0 as an unambiguous *pre-fold* marker rather than as a stale
  generation: on its chunk and write lanes it adopts the live generation and runs the item, so no
  scanned row and no paid-for model result is lost, and on its group lane it discards the item because
  the readiness sweep re-derives that group anyway. Nothing is drained, paused or counted. A shutdown
  drain was considered and rejected: it would have to run in the image being replaced, which is already
  deployed without it. That behaviour belongs to the runner's `read-transform-directly`; it is named
  here because it is what removed the operator step this proposal used to require — and, with it, one
  of the two legs the required window stood on.
  *The surviving leg, and the only hard requirement.* The second leg is this repository's own write
  path, which D11 originally missed by reasoning only about reads: `PipelineService.validateAndStore`
  ends in `setSpec(specCodec.write(spec))`, reached from a plain `PATCH {"enabled": true}` and not only
  from a declaration edit, and the pre-fold `EnrichSpec` is `@JsonIgnoreProperties(ignoreUnknown = true)`
  with no `transform` member — so any pipeline write handled by an old pod during the roll silently
  erases the transform V41 just folded in and resets `input_bindings` to `[]`. `maxUnavailable: 0`
  guarantees such a pod exists for the length of the roll, so no deploy strategy removes this; only not
  writing does. ADAS now fails fast when it later reads such a spec, so the damage is loud rather than
  silent, but recovery still means re-declaring the pipeline's `transform` from the migration's
  `RAISE NOTICE` record. Note the asymmetry that keeps this out of the "required ordering" column: it is
  a thing the operator must **not do**, not a step they must perform.
  *Why the ordering is a recommendation rather than a requirement.* Both misorderings are **refused
  rather than absorbed**, and that is verified rather than assumed: the runner's
  `AdasWorkGate.requireCompatible` throws `AdasIncompatibleHoldException`, which *holds* the work item —
  rescheduled, no attempt consumed, nothing archived — rather than failing it, and ADAS's data plane
  answers 409 before any lookup. A skew stalls and then self-heals once both sides are up (design D11).
  *Why the quiesce is still recommended, which is the honest cost of skipping it.* During the ADAS roll
  both ADAS builds serve concurrently, so a running old runner can read a *compatible* echo from an
  outgoing pod, start work, pay for a model call, and then have its write refused 409 by a new pod. That
  work is held rather than failed, and the new runner build's pre-fold adoption writes it after the
  upgrade — so it is **recovered, not lost**. The cost is a deferred write and a compatibility signal
  that flaps for the length of the roll: noise, not damage. Scaling to zero buys that off, and it is why
  the sequence is recommended rather than merely permitted. Two constraints an earlier draft carried are
  still removed at the root rather than mitigated: the migration **drops only the
  `pipeline_evaluator_iff_enrich` CHECK**, retaining both `pipeline.evaluator_name` and
  `pipeline.evaluator_version` unmapped so the outgoing pod keeps reading and the follow-up change
  `drop-retained-evaluator-columns` drops them (D11); and the per-target provenance patch becomes the
  reconciler above (D10).
- A Flyway migration folds each `enrich` pipeline's **resolved** evaluator version into its `spec`,
  driven entirely by `COALESCE(pipeline.evaluator_version, max(evaluator_version.version))` — the
  resolution rule `EvaluatorService.select` applies — with no environment-specific name lists. A
  pre-flight `DO $$ … RAISE EXCEPTION` fails the deploy loudly on any pipeline whose pinned version does
  not resolve, rather than silently writing a pipeline with no transform, and `RAISE NOTICE`s the
  `(pipeline, evaluator, version)` triples it folds — the only record of that mapping once the columns
  are gone.
- `EvaluatorDependentValidator` and `EvaluatorRegistrationService` are deleted: with the declaration on
  the pipeline, a target is always in scope at validation time, so the entire "accepted at registration,
  refused at binding" deferral disappears and with it the re-validation machinery it required.
- The Enrichment Runner is updated **in the same release**, reading `transform` directly. Its change is
  proposed in its own repository (`analytics-enrichment-runner`, change `read-transform-directly`); the
  two must stay consistent. **No cursor is lost and nothing is re-derived** — `pipeline_cursor` and
  `group_state` are keyed by pipeline name alone, live in the runner's own Postgres and are untouched.
  "No runner state is discarded" was nonetheless too strong, and the runner's own proposal corrects it:
  `dlq_item.evaluator_version` (the third component of the partial unique index
  `dlq_item_row_identity_uk`) and `spend_counter.evaluator_version` (the middle of its primary key) are
  both **re-keyed** by runner migration `V9__rekey_state_on_pipeline_generation.sql`, which renames and
  widens them to `pipeline_generation` and rewrites no row. Two behavioural residues follow: a
  pre-cut-over DLQ row can no longer collapse with a post-cut-over failure for the same grain, so the
  same failing grain sits as two rows until the 30-day sweep; and per-generation spend reporting
  straddles two number spaces permanently. The daily spend-pause gate is **unaffected** — it sums
  across that key component for the pipeline and day.
- **`RunnerContract.VERSION` is raised from 1 to 2**, which is the whole of this change's obligation to
  the deploy sequence — and what turns the sequence from a requirement into a recommendation, since a
  misorder is then held rather than absorbed. The `Adas-Contract-Version` mechanism shipped in
  `2026-09-16-enforce-runner-contract-version`, which names this change as its motivating case: ADAS
  echoes the version on every `/v1/pipelines` response (the feed included) and requires an exact match
  on the four runner-only data-plane endpoints with 409; the runner already sends it on every call and
  already refuses to interpret a response whose echo is absent or different. Bumping the constant is
  therefore what makes both misorderings harmless — each is refused, held and self-healing rather than
  unreachable — and `RunnerContractShapeTest` — which pins the
  compiled projection against a golden list naming `evaluator_name`, `evaluator_version`, `evaluator`
  and `vars` — fails the build until the bump and the golden list are both done. **No new handshake is
  added**: an earlier draft's `contract` query parameter, 426 `feed_contract_unsupported` and body echo
  are withdrawn as a duplicate of shipped machinery (see design D8).
- The **template-placeholder check moves to enable and preview** rather than running on every declaration
  write, so an author may save a pipeline whose `request_template` is written and whose
  `transform.inputs` are not yet bound. Shape and type validation stay at write time — a declaration that
  cannot be read back is not a draft. No `draft` state is added.

Explicitly **out of scope**, each a separate change: dropping the two retained `pipeline` columns, which
is the contract half of the expand/contract above and is named as a follow-up; a server-side draft
**object** with its own state and publish step
(the placeholder relaxation above covers ordinary composition without one); a preview that evaluates a
candidate transform supplied in the request body rather than the stored one; extending the placeholder
relaxation to the other referential checks, which feed the catalog's drop/rename guard.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `enrichment-registry`: the evaluator registration requirement is replaced by the pipeline-borne
  `transform` block; the static governance gate dispatches on the declaration rather than on a resolved
  evaluator, and drops "the evaluator resolves" as a check; the response-schema composition, the two
  drop/rename guards, the target-column notification and the enrich preview all read the declaration;
  the template-placeholder check moves from every declaration write to enable and preview; the feed
  drops the inlined `evaluator` object and serves a derived `response_schema`; the evaluator surface
  becomes a read-only archive; the dependent-pipeline re-validation requirement is removed. An output's
  `prose` becomes **optional**, defaulting to the target column's `description` in the composed schema
  only, and the metadata-only bump rule **inverts** for a `description`-touching patch on an `llm`
  target, since the column description is now a live prompt input.
- `pipeline-registry`: the authored declaration gains the `transform` block, whose members are
  enrich-only for cross-kind rejection; `vars` becomes `transform.inputs`;
  `evaluator_name`/`evaluator_version` belong to no kind and are refused at the binding with 400 rather
  than 422; the compiled projection drops the inlined `evaluator` object and gains a derived
  `response_schema`; the compiled projection's enumerated derived-member set and the shipped
  `Adas-Contract-Version` requirement are restated for the new shape (**no** second contract mechanism is
  added); and the projection-refusal requirement's derived-member enumeration is corrected.
- `class1-enrichment`: a Class-1 derivation is declared as `transform.type: sql` on the pipeline rather
  than on a separate evaluator; expression validation no longer straddles two objects; the materialize
  path stamps `pipeline_generation`.
- `enrichment-runtime`: the permitted provenance column set gains `pipeline_generation`; a
  caller-supplied `evaluator_version` is refused with 422 as a retired spelling (the header contract,
  not this, is what catches a stale runner); and the read-window clamp's reference to the columns a
  group pipeline's `vars` bind is restated as `transform.inputs`.
- `table-management`: the canonical provenance columns injected into a new enrichment's schema go from
  five to six, gaining `pipeline_generation`; without this a new target could never physically carry the
  new stamp. And the "existing enrichments are not retro-fitted" position is **revised for that one
  column**: the system reconciles it onto every `ACTIVE` enrichment that an `ENRICH` pipeline targets,
  at startup, idempotently, bumping no pipeline `generation` and never failing startup. Creation-time
  injection still reaches new tables only, and `PATCH` still does not inject — the reconciler is a
  separate, narrower path, and it is the first time ADAS adds a column to an already-materialized
  enrichment.
- `audit-trail`: the exclusion of the evaluator registry from coverage is narrowed to the retained
  archive tables, and a `Pipeline` snapshot now carries the transform declaration. Two known limitations
  are recorded: pre-fold revisions carry no transform, and the fold migration itself creates no revision.
  The point-in-time read requirement is also corrected — it currently says an enrich snapshot "SHALL NOT
  resolve its pinned evaluator … since reconstructing those would require historical state this
  capability does not track for evaluators", and after the fold there is no pin to resolve and the
  transform lives in the `spec` that `pipeline_aud` mirrors.

## Impact

- **APIs**: `POST /v1/evaluators` removed (BREAKING). `POST`/`PATCH /v1/pipelines` accept `transform`
  (with `transform.inputs` replacing `vars`) and reject `evaluator_name`/`evaluator_version` at the
  binding. `GET /v1/pipelines` (both projections) changes shape — `vars` gone, `transform` served in
  both, and on `view=compiled` the inlined `evaluator` object replaced by a derived `response_schema`.
  `POST /v1/pipelines/{name}/preview` drops `evaluator_name`/`evaluator_version` and renames
  `evaluator_type` to `type`. `POST /v1/pipelines/{name}/rows` refuses a caller-supplied
  `evaluator_version` with 422. All three `GET /v1/evaluators*` routes are retained read-only, so
  `POST /v1/evaluators` answers **405** `method_not_allowed` rather than 404 — the collection path stays
  mapped for `GET`. **No request or response member is added for the contract**: `Adas-Contract-Version`
  already carries it on every `/v1/pipelines` response, and only the constant's value changes (1 → 2).
- **Database**: one Flyway migration, **V41**, following V39 `evaluator_outputs` from
  `simplify-pipeline-authoring` and V40 `payload_partition_alignment` from the pipeline-read bounding
  work. Fold into `pipeline.spec`, and **drop only the `pipeline_evaluator_iff_enrich`
  CHECK** — the single object that makes `evaluator_name` required for an ENRICH row, since neither
  column carries a column-level `NOT NULL`. `pipeline.evaluator_name` (with its FK and index) and
  `pipeline.evaluator_version` are **retained, unmapped and unread**, which is what makes a rolling ADAS
  deploy safe; the follow-up change `drop-retained-evaluator-columns` drops them. Because every folded
  field lands in `spec`, and
  `pipeline_aud.spec` already exists, **`pipeline_aud` needs no new columns** — the trap V36 hit, where a
  missing mirror column fails every pipeline write at commit. `evaluator` and `evaluator_version` are
  retained as-is. The migration bumps no `generation` and advances no `updated_at`. One ClickHouse
  `ALTER … ADD COLUMN IF NOT EXISTS` per un-reconciled enrich target is issued by the startup reconciler,
  not by a migration — the ClickHouse Flyway lane cannot reach runtime-created tables, and a
  Postgres-only variant would write a catalog row with no physical column, which breaks every enrich
  write-back to that target.
- **Deploy**: **no pre-upgrade steps, no deploy strategy and no operator step — one hard requirement
  and a recommended sequence.** The requirement is that **no pipeline write is issued while ADAS is
  rolling** — no `POST`, `PATCH` or `DELETE` on `/v1/pipelines`, from an operator, a script or
  `adas-mcp` — because the outgoing pod's write path silently erases the folded transform (see What
  Changes and design D11), and nothing guards it. The recommended sequence around it: scale the runner
  to 0; deploy ADAS; deploy the runner; scale back. `adas-mcp` is unordered. Nothing is drained, paused
  or counted: a work item the runner had queued is adopted and run by the new build on the chunk and
  write lanes, and discarded on the group lane where the sweep re-derives it — which is why the
  sequence is a recommendation and not, as two earlier drafts had it, a required window. The ordering
  is recommended rather than required because with `RunnerContract.VERSION` at 2 a **skew** is refused
  rather than absorbed, in both directions: the runner's `AdasWorkGate.requireCompatible` throws
  `AdasIncompatibleHoldException`, which holds the work item — rescheduled, no attempt consumed —
  rather than failing it, the runner will not interpret a feed response whose echo it does not
  recognise, and the data plane answers 409 before any lookup or read. So getting the order wrong
  stalls and self-heals rather than corrupting. What skipping the quiesce actually costs, since someone
  will skip it: during the ADAS roll both ADAS builds serve, so a running old runner can read a
  *compatible* echo from an outgoing pod, start work, pay for a model call, and have its write refused
  409 by a new pod — work that is held rather than failed and that the new runner build's pre-fold
  adoption writes after the upgrade, so it is recovered rather than lost. The cost is a deferred write
  and a compatibility signal that flaps for the length of the roll, plus frozen Class-1 positions, so
  downstream consumers such as `sessions_rollup` and `session-insights-live` clamp for a cadence and then self-heal: noise, not
  damage. Do **not** disable pipelines during the roll-out, and do **not** edit a pipeline declaration
  while the runner's carried-over queue is draining: a queued work item is run under whatever
  declaration is current when it executes, so an edit made mid-window reaches work that was built
  before it. The
  runner's paid-work gate — which bounds what a skew costs to DLQ items that requeue for free with the
  pipeline left enabled — is asserted here and verifiable only there, so task 9.5 observes it in
  flight.
- **Code**: `EvaluatorService`, `EvaluatorRegistrationService`, `EvaluatorDependentValidator`,
  `EvaluatorController`, `EnrichResolver`, `EnrichSpecValidator`, `EnrichSpec`, `PipelineEntity`,
  `PipelineRepository` (`findByEvaluatorNameAndEvaluatorVersionIsNull` and the JPQL projection listing
  the two columns), `PipelineMapper` (`ENRICH_ONLY`, `inlinedEvaluatorOf`), `PipelineRequests`,
  `PipelineDto`, `EnrichPreviewDto`, `ProvenanceColumns`, `EnrichWriteValidator`, `MaterializeService`,
  `ScanService`, `EnrichPreviewService`, `EnrichmentFreshness`, `TableController`'s schema-endpoint
  descriptions, `RunnerContract.VERSION_VALUE` (1 → 2) and `RunnerContractShapeTest`'s golden lists.
  Also, and absent from an earlier draft of this list: `EvaluatorMapper`, `ResponseSchemaComposer`
  (which the derived `response_schema` runs through), `ResolvedEnrichPipeline`, `PipelineDeclaration`,
  `PipelineRegistration`, `EnrichDeclaration`, `PipelineJsonReadNotes`, `PipelineGenerationService` and
  `PipelineGenerationBump` (whose `llm`-only scoping reads the evaluator's type),
  `TemplatePlaceholderRule` (the check D9 moves), `EnrichWriteService` (the best-effort provenance
  drop), `TableSchemaCreationService` (which injects the sixth provenance column), `PipelineController`
  and `PipelineResponses` (where `EnrichPreviewDto` actually lives, as a nested record). No
  `FeedContractException` is added — see the contract bullet above. Every branch on evaluator `type`
  already operates on a resolved object in the service layer rather than as a SQL predicate, so moving
  `type` into `spec` costs no query rewrite.
  Added by D10: a new `ProvenanceReconciler` (`ApplicationRunner`, `@Order(2)`, beside
  `ClickHouseMigrationRunner` at 0 and `SchemaValidator` at 1), an `addColumnIfNotExistsCommand` on
  `TableDdlRenderer`, and one config property. Added by D12: the blank-`prose` refusal in
  `EvaluatorService` is deleted where task 2.1 relocates it, `ResponseSchemaComposer.property()` gains
  the column-`description` fallback, `OutputSpecBody` loses a decorative `@NotBlank` (it never
  cascaded — the `outputs` map carries no `@Valid`), and `CatalogSchemaWriter`'s bump condition widens
  to a `description`-touching update, with the comment asserting the old rationale rewritten.
- **Docs**: `README.md`'s audit paragraph and two `context:` passages in `openspec/config.yaml` (the
  frozen bare-array list and the "evaluator registry is not audited" note). **`docs/release-0.2-upgrade.md`
  is deliberately not edited**, §2 and §4.3 alike — including its `input_vars`/`output_vars` → `outputs`
  migration table, its "register a new version and repoint the pipeline" paragraph and the four-step
  worked example whose step 3 is `POST /v1/evaluators`. All of it is a release-scoped, read-only record
  of the 0.1 → 0.2 upgrade as that upgrade was performed; annotating it with what a later release does
  makes it describe two releases and belong to neither (tasks 8.2 and 8.3a). `docs/configuration.md`
  **is** touched, on two counts: one new row for the reconciler's property, and its "Release and
  deployment order" section, which gains **exactly two sentences** and no argument (task 8.3). Step 2
  gains the constraint the section was missing and which is this roll-out's one hard rule — **issue no
  pipeline write for the length of the roll** — folded inline where the operator is already reading;
  and one line records that work the runner still had queued needs no drain, pause or count, because
  the new build adopts and runs it. The four steps stay as written. The required-versus-recommended
  levelling this proposal argues is deliberately **not** carried into the reference doc: it tells an
  operator what to do and what not to do, and an operator who follows it gets the recommended sequence
  and obeys the requirement inside it. Three earlier drafts of this bullet were wrong in three
  directions: one added a queue-drain step to the doc, withdrawn because it asked operators to run SQL and
  call an API for a hazard the runner now answers in code; one left the section untouched on the
  grounds that D11 argued a weaker position; and one pushed D11's whole levelling argument into the
  doc, which is change-document reasoning in a property reference.
- **Consumers**: the **Enrichment Runner**, which reads `transform` and `response_schema` instead of the
  inlined object and `transform.inputs` instead of `vars`, is built against contract version 2, and
  stamps `pipeline_generation`; its change is proposed in its own repository as `read-transform-directly` and
  the two proposals must stay consistent. Also `adas-mcp` (evaluator write tool removed, pipeline tools
  gain `transform`), and any operator runbook that registers an evaluator before a pipeline.
- **Not impacted**: the runner's **cursors and group state** (`pipeline_cursor` and `group_state` are
  keyed by pipeline name alone in the runner's own Postgres, so there is no cursor loss and no
  re-derivation; the DLQ and spend counters are *re-keyed* rather than untouched — see above), every
  already-stamped `evaluator_version` row value, and every ClickHouse target's **own** columns — the
  reconciler appends one provenance column and touches nothing else, and no target is rewritten, since
  `ADD COLUMN` on a ClickHouse table is metadata-only.
