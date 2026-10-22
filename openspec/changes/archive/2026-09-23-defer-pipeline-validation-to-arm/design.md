## Context

The pipeline registry validates a declaration as one object, at write time. `PipelineService.create`
and `PipelineService.patch` both route through `validateAndStore(pipeline, spec, armed)`, which runs the
kind's gate — `AggregateSpecValidator.validate` (52 throw sites) or `EnrichSpecValidator.validate` (56)
— and only then serializes the spec. `PipelineMapper.requireComplete` additionally forces a declaration
patch to carry the whole declaration for its kind. The net effect is that **every stored declaration is
valid at every instant**, which is a property worth having for a pipeline that runs and a
precondition that cannot be met by someone still authoring one.

Three facts shape every decision below:

1. **The arming gate already exists.** `PipelineService.java:180` re-runs the kind gate over the stored
   entity before flipping `enabled`, for both kinds. Deferring is a matter of *not* running it earlier,
   not of building a new enforcement point.
2. **The precedent is already in the code.** `validateAndStore`'s `armed` parameter exists so a disabled
   enrich pipeline may hold a template whose placeholders bind to nothing, on the stated grounds that
   "an incomplete declaration is a failure rather than a stage" only where it is about to run. The
   aggregate validator has no such parameter; the concept must be introduced on that side.
3. **The request layer, not the spec layer, is where completeness lives.** `requireComplete`,
   `requireEnabledStated`, `requireTargetAvailable` and `triggerKindOf` all refuse an incomplete body
   *before* the gate is reached. The arm path re-runs the gate but none of these, so "arming re-validates"
   is true of the spec gate and false of the completeness layer.

## Goals / Non-Goals

**Goals.** Register a pipeline with `name` + `kind` + `target`. Configure it by repeated `PATCH` while
disabled. Get each problem clearly reported at the moment the pipeline is armed or previewed, rather
than refused at registration. Preserve every invariant that protects an *armed* pipeline.

**Non-Goals.** A `draft` lifecycle state. A new validation endpoint. Any change to *what* the gate
checks. Merge-patch semantics. Pause/resume. (Fixing the pre-existing `enabled` divergence on
`/rows` and `/materialize` was a non-goal at planning; it was brought into scope during implementation —
see Risk 3.)

## Decisions

### D1 — `disabled` is the draft state; no new state is added

**Decision.** Reuse `enabled = false`. A pipeline is "being authored" iff it is disabled and its
declaration does not pass the gate — a condition the registry computes, never stores.

**Alternatives rejected.** A `status: draft | ready | enabled` column. It adds a state to describe a
computable condition, needs a `pipeline_aud` widening (and an un-widened mirror fails *every* pipeline
write at commit), and creates a second axis that every listing filter, the scheduler predicate and the
runner feed must now reason about. A one-way `draft` flag was also considered, to keep `requireComplete`
total for anything once armed; D4 achieves the same protection from state already present.

### D2 — The gate is split: derivation always runs, refusal runs only when the write arms

**The gate is not pure validation.** `EnrichSpecValidator.deriveOutputBindings:1100` calls
`spec.setOutputBindings(derived)` and `AggregateSpecValidator.deriveGroupKeys:446` calls
`spec.setGroupBy(derived)`. `PipelineMapper.toSpec` builds a **fresh** spec from the request that
carries neither — the DTO has no `output_bindings` member at all — and `validateAndStore:530` then
persists whatever object it was handed. **Skipping the gate on a write therefore does not skip a check;
it erases two derived members.**

The consequence is not cosmetic. `TableSchemaService.requireNoPipelineWrites` resolves writers from
stored `output_bindings`. With them erased, a column the pipeline writes becomes droppable; the
ClickHouse `ALTER` executes *before* `applyPatch`, whose re-derivation then fails and rolls back the
Postgres transaction — leaving the column gone from ClickHouse and present in the catalog, a state
`PipelineGenerationService` documents as having "no operation that repairs the pair".

**Decision.** Split `validateAndStore` into two responsibilities:

- **Derivation** runs on **every** declaration write, best-effort. Where it succeeds it replaces the
  derived member; where it cannot (no transform yet, an output naming a column that does not exist), it
  **leaves the stored value untouched** rather than writing an empty one. A declaration write never
  erases a derived member it could not re-derive.
- **Refusal** — every check that throws — runs only when the write leaves the pipeline armed, and at
  the execution entry points, which it already does.

Storing derived members as **empty collections rather than null** is part of this decision: it keeps one
representation in memory and in storage, and it avoids the two unguarded dereferences that a null would
reach (`withResolvableOutputMapping:337`, `AggregateSpec.groupKeyOutputNames():50`).

**Alternatives rejected.** *Skip the gate wholesale when unarmed* — the original shape of this proposal,
and it silently corrupts stored declarations as above. *Run a "shape-only" subset on every write* —
defines a second, weaker contract to keep consistent, and the ordering makes it nearly worthless anyway:
`AggregateSpecValidator` resolves input and target in its first two statements, so nothing spec-only is
reachable until both tables exist and are `ACTIVE`. *Validate on read* — every read would pay for table
lookups and entitlement checks.

### D3 — `target` stays required at registration

**Decision.** The minimal body is `{name, kind, target}`. `target` keeps `NOT NULL`, `UNIQUE`, its FK and
`ON DELETE CASCADE`.

**Rationale.** `target` is not knowledge the author lacks — it is a dropdown of existing tables — and it
is the input to every subsequent authoring step: aggregate group keys derive from its `ordering_key`,
measures are checked against its columns, and enrich `transform.outputs` is *keyed by* its column names.
A pipeline without a target can populate no form, so deferring it buys the UI nothing.

**Alternatives rejected.** *Nullable `target`.* This was costed properly and is far worse than it looks.
Beyond the migration, the null case in `requireTargetAvailable`/`findByTarget`/the input-delete guard,
and breaking `ON DELETE CASCADE` (drop the table and a targetless draft survives pointing at nothing,
a new orphan state with no cleanup path — and Postgres `UNIQUE` is NULLS-DISTINCT, so they accumulate):

- **One targetless draft stops every pod from booting.** `ProvenanceReconciler:139` runs
  `Set.copyOf(pipelines.findTargetsByKind(ENRICH))` in an `ApplicationRunner` enabled by default.
  `Set.copyOf` throws on a null element, `SpringApplication.callRunners` rethrows, the context closes.
  Every pod, every restart, until someone deletes the row by hand in Postgres.
- **A null target answers 503 `postgres_unavailable`.** It reaches `findById` on seven paths —
  `EnrichResolver.sourceOf`/`grainKeyOf`/`resolveAll` and `EnrichSpecValidator.readSourceOf`, hence all
  four runner endpoints, `view=compiled`, **table DELETE** and **schema patch**. `findById` asserts
  non-null; Spring translates that to `InvalidDataAccessApiUsageException`, a `DataAccessException`,
  which `DefaultExceptionHandler:233` reports as a database outage.
- **A successful registration would answer 500.** `PipelineController` calls `withNotes` after the
  commit; `PipelineFreshnessDiagnostics` catches the resulting exception but it was thrown by a
  *participating* transactional call, so the transaction is already rollback-only and the outer commit
  throws `UnexpectedRollbackException`. The pipeline is created, the caller sees 500, retries, gets 409.

Keeping `target` required removes all four without writing a line of defensive code.

### D4 — `PATCH` keeps replace semantics; `requireComplete` is scoped to arming patches

**Decision.** One conditional around a 21-line method: require the whole declaration only when the patch
leaves the pipeline armed. `PATCH` remains a replace at all times — a draft save stores what it was given
and clears the rest, which is honest because it *is* the whole declaration the author has so far.

**Why this preserves the spec's rationale rather than reversing it.** The registry refuses partial
patches because "a half-applied declaration has no meaning" — the objection is about a stored state *no
caller described*. Under replace-with-optional-members every storable state is still one a caller wrote
whole. The SHALL is narrowed by state, not overturned.

**Alternatives rejected.** *JSON Merge Patch (RFC 7386).* Rejected on four grounds, the first decisive:
- **It silently changes live behaviour.** `PipelineRequests.java:220` and `:267` document that *omitting*
  `response_schema` on a PATCH is how a declaration switches to the composed schema. Under merge,
  omission means "unchanged", so the documented mechanism becomes a **200 that changed nothing** — the
  exact failure class `StrictRequestJsonConfiguration` was written to prevent. The same reversal hits
  `filter`, `measure.where`, `trigger.member_select` and every `advanced` knob.
- **Jackson cannot distinguish absent from explicit null here.** `PatchPipelineRequest` is a record bound
  by the strict mapper, and `hasDeclaration()` is built on that conflation. Every fix (per-member
  `Optional`, a nullable wrapper dependency, binding to `JsonNode`, a bespoke deserializer) is real,
  permanent machinery.
- **It would not actually be RFC 7386**, which is recursive. Top-level merge would make
  `advanced: {rate_rpm: 75}` *replace* the block and clear `scan_every` — the opposite of what a
  merge-literate client expects. Going recursive opens deletion semantics inside `transform.outputs`.
- **It buys the UI nothing.** A wizard holds the whole form in state and the arming patch must be
  complete regardless, so the UI sends everything anyway.

### D5 — Batched reporting and its error code are cut to a follow-up change

**Decision.** Every refusal keeps today's code, HTTP 422 `pipeline_validation_failed`, and the gate
reports one problem per refusal. The data-plane and `view=compiled` completeness refusal is the one
exception in scope: it is a single presence sweep and names every absent member in one message (D13).
No collector, no `pipeline_declaration_incomplete`, no change to `ErrorView`.

**Why, having designed it first.** The design that was going to deliver batching cannot. Two
structural reasons, both verified in the code:

- **The members an author actually omits throw outside the validators.** `target`
  (`requireTargetAvailable`), `inputs` on a patch (`singleInput`), `trigger.kind` (`triggerKindOf`)
  and `cron` (`requireValidCron`) all throw in `PipelineService`/`PipelineMapper`. A collector that
  wraps the two validators' sections cannot see any of them, so the one message a draft author most
  needs — "you are missing an input, measures and a trigger" — is exactly the one it cannot produce.
- **Inside the validators the sections are a chain, not a fan.** `AggregateSpecValidator.validate`
  resolves input and target in its first two statements and every later section reads the resulting
  `EntityFields`; the enrich side threads a shared `referenced` set through its last three sections.
  For a *draft* the batch therefore has one element, because the missing member **is** the
  prerequisite. Batching pays off only for a complete declaration with several independent defects,
  which is not the authoring bottleneck.

Shipping the promise anyway would have put a scenario in three capabilities that no implementation
satisfies. Cutting it also removes a change to a **shipped wire code** on runner-facing endpoints,
which this project's own rule would make a paired `Adas-Contract-Version` question.

**What the author loses:** nothing they asked for. The request was that errors be *explained* at
preview or enable, which they are — the existing messages are good (`"a transform requires at least
one output"`, `"trigger.kind is required (allowed: …)"`). They are simply delivered one at a time,
as every other refusal on this surface is.

**The follow-up that would do it properly** batches across *both* layers — a completeness pass over
the four entity-level members plus a collector inside the validators — and adds
`pipeline_declaration_incomplete` and a structured `details: [{field, message}]` together, since a
per-field payload is what the authoring UI actually wants and it needs the field path the throw sites
do not carry today. That is a coherent change on its own; it is not this one.

### D7 — Preview runs the full gate while disabled, and today's light check while enabled

**Decision, stated per kind because the two do not start from the same place.**

- **`aggregate` preview runs the full gate in both states** — which is what it does today
  (`AggregateRefreshService.preview:322`), unchanged. There is no "lighter check" on this arm to keep,
  and describing one would *remove* sensitivity propagation and the inactive-input refusal from preview.
- **`enrich` preview runs the full gate when the pipeline is disabled**, and keeps
  `EnrichSpecValidator.validateForPreview` — three lines checking template placeholders — when enabled.

**Rationale.** `validateForPreview`'s javadoc argues against widening: "the preview is previewing what is
stored, not re-validating it … asking it again would report a pipeline as unpreviewable for a drift the
operator did not cause". That argument is sound **for an armed pipeline**, whose declaration passed the
gate when it was armed. It does not hold for a declaration that was never gated at all. Splitting on
`enabled` honours both readings: disabled means "check my work", enabled means "show me a sample".

### D13 — The data plane refuses an absent declaration, and `/materialize` re-checks exactly two rules

**Decision.** The four data-plane endpoints refuse a declaration whose required members are **absent**.
`/materialize` additionally re-checks the **result-type fit** against the bound target column and the
**row-grain rule** against the read source's `identity_column`. Nothing else is imported.

**The hole is real.** Under D12 a complete-but-**invalid** declaration becomes storable for the first
time, and the absence refusal does not catch it — it is complete. `MaterializeService` re-derives
expression parsing, the closed catalog, determinism, source scoping and the caller's sensitive
entitlement, but **not** those two rules. A Class-1 target keyed more coarsely than its read source
would materialize, and the target's `ReplacingMergeTree` would collapse many rows to an arbitrary
survivor — a label that is neither wrong nor reproducible, which is precisely what the row-grain rule
exists to prevent.

**Why not run the whole gate there, which was the first answer.** Because it would stop a running
pipeline for drift the operator did not cause. The writing endpoints now refuse a **disabled** pipeline
with 409 `pipeline_conflict` (see Risks, item 3), so the paused-backfill tolerance they once had is gone
and a never-armed declaration reaches neither of them. What the two re-checks guard is an **enabled**
pipeline whose stored row has drifted since it was armed — a renamed column on the read source, an
enrichment that moved. Running the full gate there would stop that live pipeline mid-run for drift it
cannot see from there; the two checks named above are the ones whose absence produces a silently wrong
row rather than a loud failure, and they are cheap. The two read endpoints stay open to a disabled
pipeline, and there the full gate is not run either, because a disabled preview samples through them
after it has already run the gate itself.

**Completeness names everything; the gate names one thing.** The absence refusal is a single presence
sweep, so it names every absent member in one message, on the data plane and on a `view=compiled` read
alike — except that the data plane asks the shared no-transform refusal first, so there a declaration
with no `transform` gets that refusal and only `view=compiled` lists `transform` among the others. An
unresolvable read source is not a completeness member; `/scan` and `/materialize` keep refusing it as
400 `bad_request`. The compiled read checks only that and whether the outputs compose to a response schema — it does
not statically validate expressions. Only the arming/preview gate reports one problem at a time (D5).

**A refusal an endpoint already owns stays unconditional.** The no-transform refusal and the null
`trigger.group_by` crash-fix apply in either state: turning a server fault into a refusal is not a new
gate.

**A stale derivation counts as absent.** D2 retains a stored derived member that cannot be re-derived,
so `output_bindings` can outlive the transform it came from. If the stored bindings do not re-derive
from the current transform, `/materialize` SHALL refuse — otherwise it writes a newly-authored
expression into a column bound by the previous one, a write the stored declaration does not describe.

### D14 — `target` is sticky on a patch

**Decision.** A declaration patch that omits `target` keeps the stored value; one that supplies a
different value repoints the pipeline. Every other member keeps replace semantics.

**Why it is required rather than a nicety.** Without it the authoring loop breaks on the author's
*second* request. `PipelineService.patch` calls `requireTargetAvailable(registration.target(), name)`
before anything else, which throws "target must not be blank" — so under pure replace semantics
`PATCH {"measures": [...]}` is refused, and every draft save would have to restate `target` forever.
That is the confusing error this change exists to remove.

**Why it does not reopen the merge-patch hazard.** Merge was rejected because omission is the
documented way to *clear* `response_schema` and `filter`, so "absent means unchanged" would silently
turn a clearing request into a no-op. `target` has no clear semantics to break: it is required at
registration and can never legitimately become absent. This puts it on the same footing as `name` and
`kind`, which a patch may already either restate or omit.

### D15 — Review fixes

Tightenings made after review, each stated in the delta specs:

- **Preview of a disabled enrich pipeline runs arming's order** — the trigger's arm checks, then the full
  gate, before any transform-presence check — so its first refusal is the first refusal an enable-only
  patch would give. A bare draft reports `trigger.kind is required` on both.
- **A disabled preview samples through the declaration it just gated**, with the output mapping derived
  in memory rather than the stored one, so a draft whose stored mapping is stale (its target's schema
  defined after the draft was saved) previews successfully whenever arming would succeed.
- **A schema patch never fails on a draft.** The post-DDL re-derivation of a disabled target pipeline's
  output mapping is best-effort: where it fails, the stored mapping stands and nothing is bumped. Where
  it succeeds, the mapping is rewritten as for an enabled pipeline — an `llm` pipeline's `generation`
  and `updated_at` advance, a `sql` one's do not. The refusal backstop stays for an enabled pipeline.
- **Freshness classifies a disabled writer with an incomplete declaration `undeclared_writer` first** —
  any missing member, not only the transform — before the group-trigger classification, since a draft
  writes nothing whatever trigger it has declared. A disabled writer whose declaration is complete keeps
  today's classification. A composed-schema mismatch on an `llm` draft is logged at debug, not warn.
- **Derived members carry over only for the same tables.** A draft save that cannot re-derive `group_by`
  or `output_bindings` keeps the stored value only while target and read source are unchanged; a repoint
  stores it empty.
- **The declared read source must be restated by every declaration patch**, armed or not; `inputs: []`
  drops it explicitly.
- **Restating only the stored `target` is not a declaration** and changes nothing, rather than clearing
  the draft; beside `enabled: true` it is an enable-only patch. A blank or whitespace `target` on a patch
  counts as omitted (sticky, and not a declaration), and a padded one is trimmed.
- **The authored projection omits an undeclared `trigger`** rather than serving `{}`.
- **A disabled scan of a `sql` pipeline says "use /materialize"** before any completeness problem.
- **A complete declaration stays complete.** A patch that leaves the pipeline disabled is refused (422)
  when the stored declaration names every authored member its kind requires and the patched one would
  not. Nothing stored separates a paused pipeline from a draft, and without this a one-knob patch of a
  paused, previously armed pipeline erased its transform, trigger and inputs with a 200. Derived members are not
  compared; an incomplete draft keeps any-subset saves; a complete one may be replaced whole.

## Risks / Trade-offs

1. **A column a draft will eventually read is droppable.** `TableSchemaService` resolves readers from
   *stored* declarations, so an incomplete declaration under-declares its reads and the drop/rename guard
   lets the column go. → Bounded and stated in the `table-management` delta: the guard covers complete
   declarations only. Completing the declaration afterwards fails loudly at the gate rather than silently
   reading a dropped column, so the failure mode is lost authoring work, not wrong data.
2. **Feed log noise.** `withResolvableOutputMapping` logs at ERROR for every pipeline it drops. A
   `view=compiled` listing that does not filter `enabled=true` would now log an ERROR per draft. → Skip
   the log for a disabled pipeline: incomplete-and-disabled is the expected state, not an error.
3. **An inherited invariant this change must not claim.** The registry spec says a disabled pipeline
   "SHALL NOT write to its target by any route", but neither `/rows` nor `/materialize` checks `enabled`
   today, and both write. → **Closed during implementation**: both refuse a disabled pipeline with 409
   `pipeline_conflict`. Nothing relied on the tolerance — the runner reads only the enabled feed and
   stalls its own backfill of a disabled pipeline — and the accepted cost is that a runner's in-flight
   writes between a disable and its next sync are refused. The read endpoints stay open.
4. **A longer window for target drift.** A declaration authored on Monday and armed on Friday is gated
   against Friday's schema. → Already true of every disabled pipeline; the arming gate is the mitigation
   and it is unchanged.
5. ~~**Message truncation.**~~ Moot: batching was cut (D5), and the completeness sweep names at most a
   handful of fixed member names, far below the 2000-character sanitizer limit.
6. **Unguarded iteration over `group_by`/`measures`.** `AggregateSpecValidator.declaredFieldsOf:278,283`
   iterates both with no null check, on a path that runs over *every* stored declaration during an
   unrelated schema patch. It is unreachable today only because `NON_NULL` serialization plus the model's
   field initialisers mean a null never round-trips. → D2's "empty, never null" rule keeps it
   unreachable; add the guards anyway, since the distance to reachable is one refactor.
7. **`referencedEnrichments` is not exception-safe on the enrich side**, unlike its aggregate twin,
   and it sits inside two read-only transactions whose callers assume it never throws. → Give it the
   same `catch (RuntimeException) → empty` contract; a drifted declaration must not fail an unrelated
   table delete.
8. **More frequent generation bumps while authoring.** Every accepted declaration patch bumps
   `generation` and discards the Class-1 materialized mark, holding dependent rollups at zero rows until
   the next materialize. → Harmless on a never-armed draft (nothing is materialized yet) and unchanged
   for a previously-armed pipeline, which already behaves this way. Recorded, not designed around.

## Migration Plan

One Flyway migration, **V42**. It drops one `NOT NULL`, re-scopes three
CHECKs and adds one. It is data-safe: every existing row is either enabled and complete (satisfying
`pipeline_armed_is_complete`) or disabled and unaffected.

Deploy order does not matter. The change is additive from every client's perspective — bodies that were
valid before remain valid, and the only new refusals are on declarations that could not previously
exist. No `Adas-Contract-Version` bump: the compiled projection is untouched.

Rollback: the migration is reversible in principle, but a draft stored while it was applied would violate
the restored constraints. Rolling back therefore requires deleting drafts first. Given the project's
stated position that rollback compatibility is not a design requirement, this is recorded rather than
designed around.

### D12 — A complete body at `POST` is not gated either; the rule is the write's armed state and nothing else

**Decision.** `POST /v1/pipelines` runs the refusal half **iff the write arms the pipeline**. An
`aggregate` registration always lands disabled, so it is never gated; an `enrich` registration carrying
`enabled: true` always is.

**This is a deliberate behaviour change, and it is the one place existing callers see a difference.** An
`aggregate` body that is complete but *invalid* — a measure whose type does not fit its target column,
group keys that do not equal the `ordering_key` — is refused with 422 today and will be **accepted and
stored** after this change, with the refusal arriving at preview or arming instead.

**Alternative rejected: gate a body that "looks complete".** This is the intuitive compromise and it is
the worst of the three. Acceptance would depend on a threshold the caller cannot see, so adding one more
optional member could flip a request from 201 to 422 — a rule that surprises precisely the people it is
meant to help. One rule, keyed on a state the caller chose explicitly, is worth the regression.

**What limits the cost.** The `aggregate` lifecycle already mandates preview before enable, so the
diagnosis arrives at the step the author was required to take anyway; preview reproduces every arming
refusal with the same status and code, so "preview passes" means "arming will pass". Existing bodies
that are *valid* register exactly as before, which is every passing test and every working client.

## Open Questions

1. ~~**Does preview reproduce the 403 half of the gate?**~~ **Closed.** Entitlement to `sensitive`
   columns simply *is* `FULL_ADMIN` (`SensitiveColumnAccess.maySeeSensitive()` is
   `!securityEnabled || roles.contains(FULL_ADMIN)`), and every surface that runs the gate — create,
   patch, preview, refresh — is already `@FullAdminOnly`. A caller who reaches **the gate** un-entitled
   cannot exist, so retiming the entitlement check has no observable effect and there is no
   403-versus-read ordering to pin. This is a statement about the gate, not about the sensitive guard
   in general: the four runner data-plane endpoints are pinned `authenticated()` rather than
   `FULL_ADMIN`, and they call the guard per read and per derive, so `sensitive_column_not_entitled`
   is reachable and load-bearing **there** — by a WRITE-granted non-admin identity — and this change
   does not move those calls. The check stays inside the refusal half as defence-in-depth against
   a future narrowing of who may author. The specs must not describe a non-entitled author storing a
   declaration, because that scenario is untestable through the API.
2. **Where does the completeness check sit relative to authorization on the data-plane endpoints?**
   Authorization first, then completeness, then execution, so an unauthorized caller learns nothing
   about the declaration's state. Stated here; worth confirming against the contract-header interceptor,
   which runs earlier still.
3. **Does the authoring UI want the batched problems keyed by field?** If yes, `details: [{field,
   message}]` becomes worth the contract change — but it requires threading a field path through the
   throw sites, so it should be scoped as its own change rather than smuggled in here.
