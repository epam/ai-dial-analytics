## Why

**No unfinished pipeline declaration can be stored at all.** `POST /v1/pipelines` runs the full kind
gate, and `PATCH /v1/pipelines/{name}` demands the whole declaration for the kind and re-runs the same
gate — whether or not the write arms the pipeline. So every stored declaration is valid at every
instant, and the author must arrive with all of it at once: an `ACTIVE` target of the right write mode
whose `ordering_key` the group keys equal **exactly and in order**, with **no target column left
unwritten** by a measure or key; an `ACTIVE`, scannable input declaring both a version and an identity
column; every measure resolving in the function catalog and fitting its column's type, nullability and
sensitivity; every output binding an exposed column.

That is a reasonable description of a pipeline that is about to run. It is an unreasonable precondition
for *saving a name*. An author who does not yet know which measures they want cannot write the ones
they do know; a UI cannot offer a second step, because there is nothing to attach the second step to.
The result is the one-shot form this change exists to remove — and the gate is not buying safety for
it, because a disabled pipeline is not run by ADAS and is dropped from the runner's sync feed. What the
gate protects is the moment the pipeline is armed, and that moment already has its own gate.

The system already agrees in one place. `PipelineService.validateAndStore(pipeline, spec, armed)` takes
an `armed` flag precisely so a **disabled** pipeline may hold a request template whose placeholders bind
to nothing, because — in the code's own words — "that is an author composing a declaration, not an
error", and the check belongs "where an incomplete declaration is a failure rather than a stage". That
judgement is right; it is applied to one check out of 108. This change applies it to the gate.

`PipelineService.java:180` **already** re-runs the kind gate over the stored entity before arming, for
both kinds. The enforcement moment moves from "when the row is written" to "when the row is about to do
something".

## What Changes

- **A three-field registration.** `POST /v1/pipelines` accepts `name` + `kind` + `target` alone.
  `inputs`, `filter`, `group_by`, `measures`, `trigger`, `transform` and `advanced` become optional. The
  pipeline is created disabled and the kind gate does not run. `target` stays **required**: it is a
  dropdown of existing tables rather than knowledge the author lacks, and it is the input to every later
  authoring step — aggregate group keys derive from its `ordering_key`, and enrich `transform.outputs`
  is *keyed by* its column names — so a pipeline without one can populate no form. Keeping it required
  also preserves `UNIQUE (target)`, its FK and `ON DELETE CASCADE`, which a nullable target would let a
  draft outlive with no cleanup path. This is a **safety** decision as much as a usability one: a null
  `target` reaches `findById` on seven paths (including table DELETE and schema patch), where Spring
  translates the resulting assertion into a `DataAccessException` that `DefaultExceptionHandler` reports
  as **503 `postgres_unavailable`** — and `ProvenanceReconciler` calls `Set.copyOf` over enrich targets
  in an `ApplicationRunner`, so one targetless draft would stop **every pod from booting**.
- **The gate is split into its two jobs: derivation, which always runs, and refusal, which runs only
  when the write arms.** The gate is not pure validation today — `deriveOutputBindings` and
  `deriveGroupKeys` *mutate* the spec, and they are the only writers of those members. Skipping the gate
  wholesale would therefore persist a declaration with its derived members **erased**, which disarms the
  catalog's column drop/rename guard (it resolves writers from stored `output_bindings`) and can leave
  ClickHouse and the catalog permanently diverged. So derivation runs on every declaration write,
  best-effort, and never erases what it cannot re-derive; only the refusal half is deferred.
- **The refusal half moves to the arming and preview boundary**, unchanged in content. It runs when a
  write leaves the pipeline `enabled`, and on `POST …/preview` of a disabled pipeline. Two refusals
  stay unconditional on every write: an intra-declaration contradiction the database refuses on every
  row (a `member_select` on a non-`group` trigger, a non-`schedule` trigger on an `aggregate`), and a
  malformed body, which remains a 400 naming the field.
- **The four data-plane endpoints refuse an *absent* declaration, and `/materialize` re-checks two
  specific rules — not the whole gate.** The writing endpoints now refuse a disabled pipeline with 409,
  so what reaches them is an enabled pipeline whose declaration was gated when it was armed and may
  have drifted since; running the full gate there would stop that live pipeline mid-run for drift the
  operator did not cause and cannot see from there (design D13). But D12 makes
  a complete-but-**invalid** declaration storable for the first time, and `MaterializeService`
  re-derives expression parsing, determinism, source scoping and caller entitlement while checking
  neither the result-type fit against the bound column nor the row-grain rule against the read
  source's `identity_column`. A Class-1 target keyed more coarsely than its source would materialize
  and its `ReplacingMergeTree` would collapse rows to an arbitrary survivor. Those two checks are
  added at `/materialize`; nothing else is.
- **`target` is sticky on a patch.** Omitting it keeps the stored value; supplying a different one
  repoints the pipeline. Without this the authoring loop breaks on the author's *second* request:
  `requireTargetAvailable` throws "target must not be blank" before anything else, so under pure
  replace semantics every draft save would have to restate `target` forever. The exception is narrow
  and safe — `target` is required at registration and can never legitimately become absent, so it has
  no "clear" semantics for a merge to get wrong, which is exactly what made merge unsafe for
  `response_schema` and `filter`. It puts `target` on the same footing as `name` and `kind`, which a
  patch may already restate or omit.
- **`PATCH` keeps replace semantics for every other member.** The only other change is that
  `PipelineMapper.requireComplete` becomes conditional on the patch leaving the pipeline **armed**. A
  draft save is `PATCH {"target": …, "measures": […]}`, which stores what it was given and clears the
  rest — honest, because it *is* the whole declaration the author has so far. The spec's rationale for
  refusing partial patches ("a half-applied declaration has no meaning") is therefore **preserved, not
  reversed**: every storable state is still one a caller described whole. **Merge-patch semantics were
  considered and rejected** — `PipelineRequests.java:220` and `:267` document that *omitting*
  `response_schema` on a PATCH is how a declaration switches to a composed schema, so "absent means
  unchanged" would silently turn that into a 200 that changed nothing, which is verbatim the failure
  class `StrictRequestJsonConfiguration` exists to prevent.
- **Preview becomes the author's diagnostic, by state — and the split applies only to `enrich`.**
  `aggregate` preview already runs the full gate in both states (`AggregateRefreshService.preview:322`)
  and keeps doing so; there is no "lighter" enabled path to preserve there, and inventing one would
  *remove* sensitivity propagation and the inactive-input refusal from preview. `enrich` preview runs
  the full gate when the pipeline is **disabled** and keeps `EnrichSpecValidator.validateForPreview` —
  a three-line template-placeholder check — when **enabled**, which preserves that method's own
  rationale: an armed pipeline's declaration already passed the gate, and drift the operator did not
  cause must not make it unpreviewable. Disabled means "I am checking my work"; enabled means "show me
  a sample".
- **Failures keep today's reporting under `pipeline_validation_failed`: the gate reports one problem
  per refusal, while the data-plane and `view=compiled` completeness refusal — a single presence sweep —
  names every absent member in one message.** Batching
  every problem into one response and giving it its own code was designed and then **cut to a
  follow-up**, because the design could not deliver it: the members an author actually omits —
  `target`, `inputs`, `trigger.kind`, `cron` — throw in `PipelineService` and `PipelineMapper`,
  outside both validators, and inside the validators the sections form a dependency chain rather than
  a fan (input and target resolution produce the `EntityFields` every later section reads), so a draft
  batches to exactly one problem because the missing member *is* the prerequisite. Shipping the
  promise without the mechanism would put a scenario in three capabilities that no implementation
  satisfies. What the author gets from the gate is the same clear 422 per problem that every other
  refusal on this surface gives, at preview or arming instead of at registration.
- **Four storage guards re-scoped, one added.** `trigger_kind` loses its `NOT NULL`;
  `pipeline_input_required_unless_enrich`, `pipeline_cron_iff_schedule` and `pipeline_group_by_iff_group`
  become conditional on `enabled` — the last two because "picked Schedule, has not typed the cron yet"
  is precisely the state being enabled; and a new `pipeline_armed_is_complete` asserts positively that
  an armed row names a trigger kind and, unless `enrich`, an input.
  `pipeline_member_select_only_group` and `pipeline_aggregate_is_scheduled` stay **verbatim**: SQL CHECKs
  pass on UNKNOWN, so a NULL `trigger_kind` already satisfies both, and they still catch a genuine
  cross-field contradiction on a draft. Relaxing them would delete two guards for nothing.
- **The authored projection stops rendering an absent member as `[]`**, for `group_by` and `measures`.
  This is what makes GET → edit → PATCH round-trip honestly once declarations can be partial, and it
  extends the precedent's rule that `view=source` echoes stored `inputs` absent when undeclared.
  **`output_bindings` is deliberately excluded**: it is derived and served on the *compiled* projection
  only, so changing it would alter the runner-facing body and drag in a contract-version question for no
  round-tripping benefit.
- **`enabled` stops being mandatory for `enrich` at registration**; its absence now means `false`, which
  is exactly the intent the old rule protected. `enabled: true` at registration stays legal for `enrich`
  (the gate runs, because the write arms it) and stays a 422 for `aggregate`.
- **BREAKING (behaviour, not shape): a complete-but-invalid `aggregate` registration is now accepted.**
  The rule is the write's armed state and nothing else, and an `aggregate` always registers disabled —
  so a body whose measure type does not fit its target column, or whose group keys do not equal the
  `ordering_key`, is stored rather than refused, and the refusal arrives at preview or arming. Gating
  only bodies that "look complete" was rejected: acceptance would depend on a threshold the caller
  cannot see, so adding one optional member could flip a request from 201 to 422. The cost is bounded
  because the `aggregate` lifecycle already mandates preview before enable, preview reproduces every
  arming refusal with the same status and code, and a **valid** body registers exactly as before.
- **The transform-less refusal keeps its shipped code** `pipeline_validation_failed`, and two things
  about it change: the message no longer asserts a pre-fold write as the cause — it is now the first
  thing an author saves — and its ERROR log is scoped to an enabled pipeline, since a draft without a
  transform is expected rather than corruption. Its surface widens from three paths to five, adding
  group assembly and write-back.
- **Draft-facing review fixes** are listed in design D15: among them, inputs are restated on draft
  saves too, a body echoing only the stored target is not a declaration (and a blank target counts as
  omitted), a schema patch never fails on a draft, and a disabled preview samples through the mapping
  its gate just derived.
- `pipeline-registry`: registration requires only identity and target; the declaration gate is defined
  once as running when a write arms the pipeline or when the declaration is executed; `requireComplete`
  is scoped to arming patches; `target` is sticky on a patch; the authored projection distinguishes
  absent from empty.
- `aggregate-pipeline`: requirements stating their checks apply "at registration and re-applied at
  execution" are retargeted to the declaration gate — in particular **A non-deterministic declaration is
  refused at registration and at refresh**, whose claim that "the registration half is total: such a
  declaration is never stored, so neither a refresh nor a dry-run preview … can encounter one" stops
  holding and must be rewritten to assert that preview runs the check itself.
- `enrichment-registry`: **Static governance gate on register and enable** is retimed to
  arm-and-execute; **Dry-run preview of an enrich declaration** gains the state-dependent gate above.
- `class1-enrichment`: **Class-1 expression governance** states a read source with no scan metadata is
  "refused at registration rather than at the first materialize call" — retimed.
- `enrichment-runtime`: member-selection validity is no longer "rejected at registration"; the
  data-plane endpoints' deliberate tolerance of a *disabled* pipeline is bounded to a *complete* one.
  This covers all four — `/scan`, `/group-rows`, `/materialize` and `/rows` — not the scan endpoint
  alone. `/group-rows` is the sharpest: it dereferences `trigger_group_by` unguarded, so a `GROUP`
  pipeline whose group key is not yet set is a 500 rather than a refusal.
- `table-management`: the column drop/rename dependency guard resolves readers from **stored**
  declarations, so an incomplete declaration under-declares its reads and a column it will eventually
  read stays droppable. The guard SHALL be stated as covering complete declarations only, and the
  consequence — completing such a declaration afterwards fails at the gate rather than silently reading
  a dropped column — SHALL be explicit. The bound SHALL cover `DELETE /v1/tables/{name}` as well as the
  schema patch.
- `aggregate-pipeline` and `enrichment-registry` additionally own the two drop/rename guard
  requirements themselves ("an aggregate declaration's / an ENRICH pipeline's enrichment references are
  protected against drop and rename"), each stated absolutely today. Both SHALL be bounded to complete
  declarations in the same change, or the merged specs contradict `table-management` after archive.
- `aggregate-pipeline`'s **Scheduled refreshes execute under a system identity** justifies the
  elevation by the sensitive-target-column rule being closed "at declaration time". That rule now runs
  at the declaration gate, so the sentence is retimed and the reason it still holds — a tick selects
  only enabled pipelines, and arming runs the gate in full — is stated rather than assumed.

## Impact

**APIs.** `POST /v1/pipelines` (all members but `name`, `kind`, `target` optional),
`PATCH /v1/pipelines/{name}` (completeness required only when the patch arms), `GET /v1/pipelines` and
`GET /v1/pipelines/{name}` (absent authored members served absent; `view=compiled` on an incomplete
declaration refused naming what is missing), `POST …/preview` (full gate while disabled), and the four
runner data-plane POSTs (a new refusal). No endpoint added or removed.

**Status codes.** An incomplete declaration is **422 `pipeline_validation_failed`**, not 409, and not a
new code — the batched report and its own code are cut to a follow-up (see *What Changes*). The
registry reserves 409 for "the declaration sent may be perfectly valid and only the timing wrong"; here
the declaration is not valid and the caller's next action is to fix the body.

**Code.** `PipelineRequests` (optionality); `PipelineMapper` — `requireComplete` (scoped to arming
patches; its call site does not currently receive the stored `enabled`, so the flag must be threaded),
`requireEnabledStated`, `singleInput` (refuses an aggregate with no `inputs`), the authored projection;
`PipelineService` — `create`, `patch`, `validateAndStore`, `apply`, plus `triggerKindOf` (defaults an
aggregate to `SCHEDULE` and throws for an enrich with no trigger; both arm-scoped), `requireValidCron`
(its two conditional checks arm-scoped, its parse check kept), and `save` (today maps *any*
`DataIntegrityViolationException` to "pipeline name or target already in use", so the new CHECK would
report a collision that did not happen); `AggregateSpecValidator` / `EnrichSpecValidator` entry methods,
plus null guards on `declaredFieldsOf` and the same non-throwing contract on
`EnrichSpecValidator.referencedEnrichments` that its aggregate twin already has;
`EnrichmentFreshness` — `Clampability` needs a value for "the writer has no declaration yet", or a
draft targeting an enrichment makes every pipeline reading it refuse with the false reason *"its
evaluator is not 'sql'"*; `PipelineEntity` (`@Column(nullable = false)` on `triggerKind` is enforced by
Hibernate at flush, so the migration alone is not enough); `PipelineDataController` and the four
services behind it — `GroupFetchService` and `ScanService` both dereference `trigger_group_by`
unguarded and would NPE on a `GROUP` pipeline whose group key is not yet set;
`DefaultExceptionHandler`.

**Database.** One Postgres migration (version **V42**). No
column is added, so `pipeline_aud` needs no widening: every column touched is already nullable in the
mirror, and widening an audited entity without widening its mirror fails every write at commit.

**Invariants — stated honestly.** An armed pipeline remains impossible to store incomplete, enforced in
the service layer *and* positively by `pipeline_armed_is_complete`. ADAS's scheduler selects on
`(enabled, next_run_at)` and the runner's sync feed asks for `enabled=true`, so a draft is driven by
nothing. But the registry spec's claim that "a disabled pipeline SHALL NOT write to its target by any
route" was **violated before this change**: neither `POST …/rows` nor `POST …/materialize` checked
`enabled`, and both write. Decided during implementation: this change **closes** it — both now refuse a
disabled pipeline with 409 `pipeline_conflict`, the answer a disabled aggregate's refresh gives. No
consumer relied on the tolerance (the runner reads only the enabled feed and stalls its own backfill of
a disabled pipeline), and with drafts storable it would have let a target be written from a declaration
that was never validated. The accepted cost: a runner's in-flight writes between an operator's disable
and its next sync are refused. `/scan` and `/group-rows` stay open to a disabled pipeline; they write
nothing and preview samples through them.

**Contract version.** Unchanged. The compiled projection's shape is deliberately untouched, and the four
runner-only POSTs gain a refusal, not a field.
