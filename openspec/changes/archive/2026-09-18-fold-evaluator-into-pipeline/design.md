## Context

The evaluator registry is a second control-plane object holding what an `enrich` pipeline needs in
order to run: `type`, `model`, `preset`, `params`, `request_template` and `outputs`. A pipeline names
one by `evaluator_name` plus an optional `evaluator_version` (null meaning "latest"), and resolution
happens at read time.

**Observations of a working registry**, which drove this design rather than abstract preference:

| Fact | Value |
|---|---|
| Evaluators / `enrich` pipelines | a handful of each, evaluators outnumbering pipelines |
| Maximum reuse of one evaluator | **1** — every pipeline names a distinct evaluator |
| Evaluators with no pipeline at all | about half (probes, verification leftovers, abandoned drafts) |
| Versions referenced by nothing | more than half |
| Output bindings across all pipelines | dozens, **all identity** (`{"column":"title","var":"title"}`) |
| Pipelines pinning a version | all of them, and every pin is the latest version |

The table above was taken before `2026-09-10-simplify-pipeline-authoring` shipped. Once that change
and `simplify-configuration-docs` are in place, evaluators and pipelines are typically re-registered in
the newer `outputs` shape — so the reuse and identity-binding observations stand as the evidence for
folding, while "no `outputs`-shape evaluator exists yet" no longer does. The old shape is **not**
thereby retired: a pipeline whose latest or pinned evaluator version predates that change still folds
old-shape, and any given deployment may hold either shape. D4 is what makes the mixture a non-event;
what changed is that the mixture is the expected state rather than a forecast, and that the old-shape
branch may have no deployment exercising it by default (see Risks).

Reuse is also structurally blocked: `outputs` is keyed by target column names, and the spec requires a
second target to "reuse the same column names". The one case that demanded reuse could not use it —
`conversation-insights` and `session-insights` are the same prompt at two grains, built as two separate
evaluators because the targets' columns differ.

Contrast `input_bindings`, which are genuinely non-identity (`var "request" ← column "request_body"`).
Inputs needed an indirection; outputs never did.

## Goals / Non-Goals

**Goals:**

- One object owns an enrichment: declare, edit, preview, arm and delete it through `/v1/pipelines`.
- Delete a test enrichment on a real environment — the original driver — using the existing, audited
  `DELETE /v1/pipelines/{name}`.
- Validate `outputs` against the target where they are written, removing the "accepted here, refused
  there" deferral and the re-validation machinery built to close it.
- Attributed history for prompt changes, which the evaluator registry never had.
- **One coordinated Enrichment Runner change, with no cursor loss and no re-derivation.** The runner
  changes what it reads from the feed, not what it remembers: `pipeline_cursor` and `group_state` are
  keyed by pipeline name alone and are untouched. `dlq_item` and `spend_counter` are **re-keyed** rather
  than untouched — both carry `evaluator_version` inside a uniqueness key — by the runner's
  `V9__rekey_state_on_pipeline_generation.sql`, which renames and widens the column and deletes no row.
  See D3 for the two residues that leaves.
- **One vocabulary, with no standing exception.** The transform's two halves read as a pair
  (`transform.inputs` / `transform.outputs`), and the one-spelling rule holds without the enumerated
  carve-out an inlined `evaluator` object would have required.
- **The deploy order is enforced rather than trusted**: a misordered roll-out is refused and *held*,
  not absorbed, so it stalls and self-heals rather than corrupting.
- **No pre-upgrade table edits, no deploy strategy and no operator step — one hard requirement and a
  recommended sequence.** Nothing is patched per target by hand, no `RollingUpdate` manifest changes,
  and nothing is drained, paused or counted first. The requirement is **no pipeline write while ADAS
  is rolling**, which nothing guards; the recommended sequence around it is scale the runner to 0,
  deploy ADAS, deploy the runner, scale back. See D10 (the provenance reconciler) and D11 (why the
  column drop is deferred, why one leg of the formerly-required window is answered in the runner's
  code rather than by an operator, and what skipping the quiesce costs).
- A **faithful round trip**: whatever `GET /v1/pipelines/{name}?view=source` returns can be sent back
  as the next `PATCH` and change nothing. This is not decoration — it is the only thing that keeps a
  read-modify-write client from silently deleting a stored `response_schema` and rewriting a live
  prompt.
- **An output's `prose` is optional, defaulting to the target column's `description`.** Composition
  already takes an output's `type`, `enum` domain and `enum` order from its column; the description was
  the one fact an author had to copy by hand. See D12.

**Non-Goals:**

- A server-side draft **object** for an incomplete pipeline declaration — a `draft` state, a relaxed
  store, a separate publish step. The fold already removes the "create == publish" trap that motivated
  it, and D9 makes the one check that blocked ordinary composition (placeholders against
  `transform.inputs`) run at enable and preview instead of at every write, which covers the case the
  draft object was wanted for without adding a state.
- Previewing a **candidate** transform that is not the stored one. See "Risks / Trade-offs".
- Extending D9's relaxation to the other referential checks (`transform.outputs` against the target, a
  `sql` expression against the read source). Both feed the catalog's drop/rename guard, so the blast
  radius differs; see Open Questions.
- Re-labelling any existing row.
- Retiring the `evaluator_version` provenance column.
- **Dropping `pipeline.evaluator_name` and `pipeline.evaluator_version`.** V41 drops only the
  `pipeline_evaluator_iff_enrich` CHECK; the two columns, their FK and their index stay behind,
  unmapped and unread, and the follow-up change `drop-retained-evaluator-columns` drops them once no
  deployed build maps them.
  This is what buys the unordered roll-out — see D11.
- Changing any ClickHouse target schema **beyond adding the one `pipeline_generation` provenance
  column** to existing enrich targets, which D10 does automatically rather than by hand. No target's
  own columns are touched.

## Decisions

### D1. Everything folds into `pipeline.spec`; no new pipeline columns

`spec` is a `JSONB NOT NULL` column (V20) holding the kind-dispatched transform declaration —
`AggregateSpec` or `EnrichSpec`, selected by the `kind` column. Every folded member goes there.

*Why.* `pipeline_aud.spec` already exists (V35), so **`pipeline_aud` needs no new columns**. That is the
exact trap V36 hit: of the twelve columns it added to `pipeline`, six were control-plane facts that had
to be mirrored (the other six — the five Class-1 position columns and `legacy_rule_id` — are
deliberately `@NotAudited`), because a missing mirror column makes Envers' insert name a column that
does not exist and **every pipeline write fails at commit**.

*Alternative rejected — keep `type` and `model` as real columns.* `EnrichSpec`'s own rule says a fact
stays a column when it carries a database structural guard or a foreign key, and `type`/`model` carry
`evaluator_model_iff_llm` / `evaluator_preset_iff_llm` today. Rejected because the service layer already
enforces the identical shapes with a better error (422 naming the field), the DB constraint is
defence-in-depth rather than the primary guard, and keeping them would reopen the aud-widening work for
two columns. **Verified before deciding**: every branch on evaluator `type` operates on an
already-resolved object in the service layer (`ScanService`, `MaterializeService`, `EnrichResolver`,
`EnrichPreviewService`, `EnrichSpecValidator`, `EnrichmentFreshness`, `PipelineService`) — none is a SQL
predicate, so nothing needs `type` in a `WHERE` clause.

### D2. The authored transform is one nested `transform` block, not six flat members

The declaration carries `transform: {type, model, preset, params, request_template, outputs}` — one
optional block, enrich-only — rather than six new top-level members. Cross-kind rejection names
`transform.<member>`, the rule `advanced` already follows.

*Why, and this is the decisive one.* **`outputs` already exists at the top level of a pipeline read**,
and it is the *derived* mapping: a JSON **array**, one ordered entry per mapped target column,
`{name, column, jsonata?}`, served on the compiled projection (`PipelineDto.outputs`, typed
`List<PipelineOutputDto>`) and consumed by the runner. The authored outputs are not that: they are a
JSON **object** keyed by target column name, whose values are prose or expression strings, or objects
`{prose, values?, jsonata?}`. Flattening the transform would put an array and an object under one
member name, in two projections of the same object — which the pipeline-registry rule forbids outright
("A fact SHALL have exactly one spelling across the request surface, both projections and the stored
declaration"), which no typed client or OpenAPI schema can model, and which would let a
read-modify-write client on `view=compiled` post the derived array back as the authored map. Nesting
removes the collision by construction: the authored map is `transform.outputs`, the derived array stays
`outputs`.

Three further things fall out, each worth having on its own:

- **The derived member set stays a clean set difference.** Nesting keeps the transform wholly on the
  authored side, so the compiled projection's extra members remain a short, enumerable list —
  `response_schema`, `grain_key`, `version_column`, `outputs` and the resolved `inputs` (D3 swaps the
  first of those for the inlined object it deletes). The set-difference invariant the registry states
  as a scenario therefore keeps holding as a statement about resolution, not about vocabulary.
- **No `type` sibling to `kind`.** A flat `type: "llm"` next to `kind: "enrich"` is two discriminators
  at one level, in a request shape that is already flat across kinds. `transform.type` reads as what it
  is: the grammar selector *inside* the transform.
- **The block is optional as a whole**, which is what makes the cross-kind rule cheap to state: an
  `aggregate` declaration carrying `transform.model` is refused with 422 naming `transform.model`,
  through the existing nested-member path, with no new machinery.

*`vars` moves inside the block and becomes `transform.inputs`.* An earlier draft kept it outside, on the
grounds that it is the pipeline's binding rather than the transform's own declaration and that moving it
would change the runner's wire shape for no gain. Both halves fail on inspection. It **is** part of the
transform's declaration — `vars` is meaningful only when `transform.type` is `llm`, because
[class1-enrichment](../class1-enrichment/spec.md) refuses `vars` on a `sql` pipeline outright ("there is
no request to render"), so the one member dispatched on `type` was the one member living outside the
type-dispatched block. And the wire-shape cost is no longer a reason to decline anything, because D3 now
changes the wire deliberately.

What the rename buys is that the transform's two halves read as a pair. `vars` named a *mechanism* while
`outputs` named a *role*, so nothing in the spelling said they were the two sides of one thing;
`transform.inputs` and `transform.outputs` do. The collision with the top-level `inputs` — the table
list — is resolved by nesting, which is the third instance of one rule this registry already follows
rather than a new exception: `trigger.group_by` (a group key string) sits under `trigger` precisely
because the top-level `group_by` is an `aggregate` pipeline's rollup grain, and `transform.outputs` is
nested for exactly the reason above. The two are also misnesting-safe, because they are different JSON
types — the table list is an array, the variable map an object — so a member sent at the wrong level is a
binding 400 rather than a silent accept.

*What the rename does not fix, and should therefore be documented rather than renamed around.* The two
maps are keyed on the same side — the transform's own vocabulary — but their values are different kinds
of thing. `transform.inputs` maps a variable name to **where its value comes from** (a source column or a
JSONata expression); `transform.outputs` maps a target column to **what the value means or how to compute
it** (prose for `llm`, an expression for `sql`), with the destination implied by the key. That asymmetry
is inherent to the two directions and survives any spelling.

*Two spellings ruled out.* `input_vars` is a retired member: reviving it with a different shape — a map
where it was a list — would make a pre-fold body bind cleanly and mean something else, which is the exact
failure strict request parsing exists to prevent. `reads` is already taken by the reads-entity vocabulary
for the enrichments a pipeline reads.

*Alternative rejected — name the authored block `evaluator`.* One spelling, which was tempting while a
derived `evaluator` object still existed and the two had to be told apart. D3 deletes that object, so the
question is now only whether the block should be called `evaluator` or `transform`, and `transform` wins
on its own merits: the block covers a `sql` derivation that evaluates nothing, and `evaluator` is the
name of the retired registry object whose semantics (immutable, versioned, shared) are precisely what no
longer hold.

### D3. The inlined `evaluator` object is deleted; the runner reads `transform` directly

The compiled projection stops synthesizing an `evaluator` object. The runner reads the authored
`transform` block, and every fact the object carried is already available elsewhere on the entry:
`evaluator.name` was the pipeline's `name`, `evaluator.version` was its `generation`, `created_at` was
its `updated_at`, and `type`/`model`/`preset`/`params`/`request_template` are the `transform`'s own
members under the same spellings.

*Why this release rather than a later one.* The object existed for exactly one reason — to spare the
runner a redeploy — and D2's rename spends a runner release anyway. Keeping the object would then mean
shipping a stated exception to the one-spelling rule, and scheduling its removal for a release that would
have nothing else in it. It also duplicates the largest payload in the response: a `request_template` is
multi-kilobyte (`session-insights` v6's is ~14 KB), served twice per entry on a feed the runner polls.

*The one member that needs a new home, and the pattern it follows.* The object's `response_schema` was
the only member carrying a **derived** value: the declaration's stored schema when it has one, otherwise
the schema composed from the target and the transform's `outputs`. Deleting the object without replacing
it would leave a composed schema with nowhere to go. It becomes a **derived top-level `response_schema`**
on the compiled projection — which is not a new shape but the third instance of D2's own pattern: the
authored member is `transform.response_schema` (stored only, present on a folded legacy declaration), the
derived one is top-level `response_schema` (stored **or** composed), exactly as the authored map is
`transform.outputs` and the derived array is `outputs`. The derived member set therefore swaps one member
for one member and nothing else moves: `response_schema`, `grain_key`, `version_column`, `outputs`, and
the resolved `inputs`.

*The projections stay a clean set difference.* `view=source` carries the authored declaration, whose
`transform.response_schema` is present only where a legacy declaration stores one; `view=compiled` adds
the five derived members above. A read-modify-write client on `source` therefore still cannot launder a
composed schema into a stored one — which is the hazard that would arrive if the resolved schema were
served *inside* `transform` on the compiled projection, and the reason it is not.

*No cursor loss and no re-derivation — but two tables are re-keyed.*
`V8__rekey_and_discard_runner_state.sql` documents what a discard costs: "a missing `pipeline_cursor`
row means start from the beginning, and **'already enriched' is not a predicate anywhere**" — a full
re-derivation that costs real model spend and on the order of an hour for thousands of sessions on a
group LLM lane. Nothing here pays that: `pipeline_cursor`
and `group_state` live in the runner's own Postgres keyed by pipeline **name** alone, a redeploy does
not touch them, and the runner changes what it *reads from the feed*, not what it remembers.

An earlier draft over-claimed this as "no runner state is discarded", covering the DLQ and the spend
counters too; the runner's `read-transform-directly` corrects it, and the correction belongs here as
well. Both of those tables carry `evaluator_version` **inside a uniqueness key** — the third component
of the partial unique index `dlq_item_row_identity_uk (pipeline_name, grain_key, evaluator_version)`,
and the middle of `spend_counter`'s primary key `(pipeline_name, evaluator_version, day)` — so the fold
re-keys them. Runner migration `V9__rekey_state_on_pipeline_generation.sql` renames and widens both
columns to `pipeline_generation` (`generation` is a 64-bit counter where these were `INT`) and rewrites
no row. Two behavioural residues, neither a loss:

- **A pre-cut-over DLQ row can no longer collapse with a post-cut-over one.** The partial unique index
  exists so a repeated per-row failure upserts instead of accumulating; across the cut-over the key
  value differs, so the same failing grain produces a second row beside the first. Both are visible,
  neither is corrupt, and the 30-day retention sweep clears the old one.
- **Per-generation spend reporting straddles two number spaces permanently.** Pre-cut-over rows keep
  small evaluator-version integers that nothing will produce again. The daily pause **gate** is
  unaffected, and this is worth saying because the obvious fear is the one thing that does not happen:
  it reads `SUM(...) WHERE pipeline_name = ? AND day = ?`, summing across every value of that key
  component, so an exhausted daily budget is not reset by the re-key.

*The coordination this buys, and its price.* This release is no longer runner-optional: both halves must
ship, **in one release**, and the recommended way to land them is in one quiesced window. What the
contract version buys is that a *skew* is refused rather than absorbed — either half landing first
refuses to act on what it cannot interpret, by machinery that already ships rather than anything this
change invents — so a misorder stalls instead of writing a wrong row. That is what demotes the ordering
from a requirement to a recommendation. It is not the same as making the ordering free: D11 states the
one hazard the guard does not reach — this service's own write path on an outgoing pod, which is the
hard constraint that survives — the second hazard it does not reach either, the runner's queued work
items, which is answered by the runner's code rather than by an operator, and the residual cost of
skipping the quiesce, which is a deferred write rather than a lost one. D8 states the mechanism.

### D4. Carry both output shapes verbatim; convert neither — and make the legacy pair authorable

`outputs`, `output_vars` and `response_schema` are copied as-is into `spec.transform`.

*Why not convert.* The obvious objection to carrying two shapes is that a converting migration looks
mechanical: `sql` is `outputs = [{name, sql}]` and drops only the per-output logical `type`, which this
change removes anyway. It breaks on the `llm` half, and not for the reason a first pass suggests:

- An `llm` output used to need a **non-blank `prose`**, which an old-shape row does not have. D12
  removes that requirement, so this is no longer a blocker — and it is worth saying why it stops being
  one rather than deleting the bullet, because the tempting next step is to conclude that conversion is
  now easy. It is not, for the same reason it was not before: `prose` is inert for exactly these
  declarations, since their stored `response_schema` keeps winning over composition. Synthesising it
  from `response_schema.properties.<name>.description` would invent a field nothing reads, and getting
  it wrong would stay invisible until the day someone cleared the schema. Under D12 the honest
  conversion is to synthesise **nothing** and let the column description serve if the schema is ever
  cleared, which is what copying the row verbatim already achieves.
- The killer is the row with **`output_vars` and no `response_schema`**. Such a declaration calls the
  model *unconstrained* today, and the feed serves it deliberately ("a declaration that declares no
  `outputs` is a different case and SHALL still be served"). Converting it gives it an `outputs` array
  and no stored schema, which is precisely the input composition fires on — so the next feed read would
  send a `response_format` where none was sent before. **That is a silent live prompt change, made by a
  migration, on an environment nobody inspected.** One deployment may hold no such row; another's
  orphan or hand-registered evaluator may.

A copying migration is right for every environment by construction, and the accessor that reconciles the
two shapes already exists and already has to keep existing.

*What conversion would have hidden, and this decision must therefore state.* Once the transform is
authored on the pipeline, "stored" is no longer a synonym for "immutable". Three rules follow, and
without them the fold quietly breaks a live pipeline the first time an operator round-trips it:

1. **`output_vars` and `response_schema` are members of the authored surface**, readable under
   `view=source` and accepted on `PATCH`. Without this, `GET view=source` → `PATCH` on a folded
   old-shape pipeline drops both, leaving a declaration with no outputs at all — refused if the gate is
   strict, and a rewritten prompt if it is not. Round-trip fidelity is the requirement; serving them is
   how it is met.
2. **`output_vars` is refused on `POST`.** A new declaration authors `outputs`. The legacy pair is a
   shape that exists to be carried and patched back, never a shape to start from. A declaration carrying
   both `outputs` and `output_vars` is refused with 422 on either verb.
3. **`response_schema` is an authored member, so a patch that omits it clears it**, and that pipeline
   switches to composition from its `outputs`. This follows from a rule that already exists rather than
   a new one: `PipelineMapper.requireComplete` makes a declaration patch supply the whole declaration,
   so the patched `transform` replaces the stored one wholesale and an omitted member is cleared, not
   merged. This is a real change of meaning from the pre-fold rule —
   a stored schema used to win "permanently" because an evaluator version was immutable — and it is the
   operator's explicit act rather than a side effect, because a declaration patch supplies the whole
   declaration. It is also the only way to migrate a legacy declaration to `outputs` at all. The
   consequence — the model is held to a newly composed schema, whose property order is its generation
   order — is stated where the requirement lives.

### D5. `pipeline_generation` is the stamp, and `evaluator_version` is refused at the write boundary

*Why a new column rather than reusing `evaluator_version`.* Existing rows carry small version integers
(single digits, across thousands of stamped rows) and new rows would carry generations (typically
already in the tens). Two number spaces in
one column is unreadable. Provenance columns are optional and best-effort, so adding one is cheap and
removing none is free.

*Why `generation` rather than a second, transform-scoped counter.* A second counter was proposed and
rejected as bad design. `generation` cannot be *narrowed* to transform-only changes — it is the runner's
change-detection token and must keep bumping for `cadence`, `rate_rpm`, `filter`, `trigger` and
`batch_scan_limit`, or the runner executes stale parameters. But it is already a **superset**: every
transform change bumps it. So no second counter is needed; "which prompt produced this row" is answered
from `pipeline_aud`.

*The write boundary, and why it is a refusal rather than a rename.* An earlier draft **renamed** a
caller-supplied `evaluator_version` onto `pipeline_generation` before the write. That existed for one
reason: D3 kept an inlined object whose `version` was the pipeline's `generation`, so an *unchanged*
runner would stamp generation numbers into `evaluator_version` — the two-number-space corruption this
decision exists to avoid — and ADAS had to absorb it silently because the runner was not being touched.

D3 now deletes that object and the runner ships in the same window, stamping `pipeline_generation`
directly. The tolerance therefore has no client left to serve, and it inverts into the better signal: a
row arriving at `POST /v1/pipelines/{name}/rows` that names `evaluator_version` **is proof of a stale
runner**, so it is refused with 422 `validation_error` naming the column rather than quietly renamed.
It is **not** a deploy detector, and must not be justified as one: `/rows` already requires
`Adas-Contract-Version` and answers a stale runner with 409 before it can write anything (see D8). What
this refusal covers is the caller the header gate does not reach the same way — a hand-rolled `curl`, an
out-of-date `adas-mcp`, any holder of a `WRITE` grant — and it is the repository's ordinary rule that a
retired spelling is refused rather than absorbed, applied to a provenance column name. It costs the data
plane no new vocabulary.

`evaluator_version` is therefore never written again by either path, and its stamped values stay valid
and readable. The Class-1 materialize path stamps `pipeline_generation` directly.

*Cost accepted.* The stamp moves more often than the prompt (a pipeline such as `session-insights-live`
can accumulate tens of generations across a handful of prompt versions), so a human-legible "NULL before evaluator v3" becomes a trail lookup. In
exchange the stamp gains correctness: today's `evaluator_version` is blind to a `filter` or
`member_select` change, so two rows stamped v6 can have been selected from entirely different inputs.

*Operational consequence, which is a decision and not a footnote.* Provenance columns are injected into
an enrichment's schema **at creation only**, and `PATCH /v1/tables/{name}/schema` deliberately does not
inject. So every enrichment that exists today lacks `pipeline_generation`, and a best-effort write drops
what the target does not carry — meaning that without something closing the gap, the effect of this
decision is that lineage silently stops rather than improves. **D10 closes it in code**, with a startup
reconciler rather than an operator patching each target before the deploy.

### D6. Retain `evaluator` / `evaluator_version` as a read-only archive

*Why not drop them.* Two reasons that outlive the registry. (1) Existing enrichment rows carry an
`evaluator_version` integer; the archive is the only thing that keeps a stamped value answerable. (2) A
`pipeline_aud` revision recorded before the fold carries no transform, so the archive is the only place a
pre-fold prompt can be read at all. Dropping them also silently destroys every unreferenced version
and orphan evaluator, and on some deployments an orphan may be genuine work-in-progress.

*The archive alone does not close the pre-fold gap, and saying so is part of the decision.* A revision
read re-serialises the stored declaration **in the current shape**, so a pre-fold revision's
`evaluator_name` — recorded in `pipeline_aud`, which keeps both mirror columns — is not served by
`GET /v1/pipelines/{name}/revision/{revision}`. Knowing *which* archived version a past revision resolved
to is therefore a direct read of `pipeline_aud`, not an API call. Two mitigations, both cheap: the mirror
columns are deliberately left in place (V36 already gave them no FK so they survive the evaluator being
retired), and the migration `RAISE NOTICE`s every `(pipeline, evaluator_name, version)` triple it folds,
so the deploy log records the mapping at the moment it stops being derivable.

*Alternative rejected — drop the tables in the same change.* A follow-up change may drop them once the
`evaluator_version` stamps have aged out; that is a decision with its own evidence, not a side effect of
this one.

### D7. Class-1 expressions move onto the pipeline, reversing a stated spec position

`class1-enrichment` currently says expressions "SHALL remain on the **evaluator** rather than moving onto
the pipeline", because a `generation` bump discards the Class-1 materialized position and clamps every
consumer of the target enrichment.

*Why that is reversed.* Both halves of the objection are true; the missing term is duration. The runner
holds the scan cursor and a `generation` bump does not touch it (`PipelineSyncService` calls
`scheduler.reschedule(instance)` and nothing else; `pipeline_cursor` and `group_state` are keyed by
pipeline **name**, not generation; `evictRemovedPipelines` clears only in-memory rate-limiter, metrics and
DLQ-breaker state, and only when a pipeline leaves the feed). So the next materialize resumes at the same
cursor and re-records the position one cadence tick later. **The stall is one materialize cycle, not a
replay.** A replay from the start requires losing the cursor, which no declaration edit can cause.

### D8. The runner contract version is bumped; no second handshake is added

D2 and D3 together make this a breaking wire change, so both halves must ship together. Making the
handling of a skew a *checked* fact rather than a documented procedure is already solved, by machinery
that shipped two days after this design was first written and that names this change as its motivating
case. That is what makes a misordered **skew** safe: the half that lands first refuses to act rather
than absorbing a shape it cannot read — and, on the runner side, held rather than failed:
`AdasWorkGate.requireCompatible` throws `AdasIncompatibleHoldException`, which reschedules the work item
without consuming an attempt. That is what makes the deploy *order* a recommendation rather than a
requirement. It does not make the roll-out unconstrained — D11 records the two hazards the guard cannot
reach, both of which live outside the request path it polices. Only one of them is still answered by an
operator, and it is answered by them not writing rather than by them sequencing.

`2026-09-16-enforce-runner-contract-version` is in the archive. It cites
`fold-evaluator-into-pipeline`, already in flight, as "precisely the case the current arm does not
cover". What it shipped:

- `RunnerContract.VERSION` — a code constant, deliberately not configuration, "because the only wrong
  setting disables every pipeline at once, and silently".
- `RunnerContractHeaderFilter` echoes `Adas-Contract-Version` on **every** response under
  `/v1/pipelines`, the feed read included, ordered ahead of the security chain so a 401, a URL-level 403
  and an unmapped-path 404 carry it too.
- `RunnerContractInterceptor` requires a matching header on the four runner-only data-plane endpoints —
  `scan`, `group-rows`, `materialize`, `rows` — answering **409** `runner_contract_required` or
  `runner_contract_mismatch`.
- `RunnerContractShapeTest` pins the compiled projection and the four endpoints' request/response shapes
  against a golden list and **fails the build naming the constant** when either moves.

On the runner's side, `pipeline-sync`'s "Every ADAS call declares a contract version and every response
is checked for its echo" already sends the header on every call — the sync feed included — and already
classifies an **absent** echo as "an ADAS older than the contract" and a **differing** echo as "the other
side of a bump", with "An incompatible ADAS stalls the runner instead of steering it" specifying that it
keeps its cache, does not reconcile, does not mark the sync successful, logs once per transition and
publishes a metric.

**So this change's whole obligation to the deploy order is one edit: `RunnerContract.VERSION_VALUE`
goes from `"1"` to `"2"`, and `RunnerContractShapeTest`'s golden lists are updated to the new shape.**
That is not optional housekeeping — the golden list currently pins `evaluator_name`,
`evaluator_version`, `evaluator: EvaluatorVersionDto` and `vars: Map<String, VarSpecBody>` on
`PipelineDto`, all four of which D2 and D3 remove, so the build fails until both are done.

*Why the bump is sufficient, which is what an earlier draft of this decision got wrong.* The hazard is
an old runner reading a new feed, finding no `transform.inputs`, rendering literal `{{request}}`, being
charged for the call and storing the answer as a label. With `VERSION` at 2 that is unreachable: the
runner reads the echo on the feed response *before* interpreting the body, sees `2` where it expects
`1`, classifies the response incompatible and does not interpret it. The reverse skew — a new runner
against an old ADAS — is caught by the same echo in the other direction. Both misorderings are covered,
on all five call sites rather than only the feed.

*What an earlier draft proposed, and why it is withdrawn.* It specified a new `contract=N` **query
parameter** required on the compiled read, a **426** `feed_contract_unsupported`, a `FeedContractException`
and a `contract` member echoed in the response body. Every one of those is either already shipped under
a different spelling or actively wrong:

- The echo it invents is the response header, which already covers the feed and also covers the
  responses a body member cannot reach — a 401, a 403, a 404 — which is the distinction the shipped
  filter's own note exists to make.
- 426 was **considered and rejected in the shipped code**: `RunnerContractException`'s javadoc says
  "426 Upgrade Required describes it more precisely but is unused in this service and in the runner's
  HTTP client", and 409 was chosen because the runner treats only 5xx and 429 as retryable. Reviving 426
  would give one fact two status codes.
- Requiring a parameter on the compiled read **contradicts a shipped requirement**. `pipeline-registry`'s
  "Every pipeline response carries the runner contract version" states that "the control-plane reads
  SHALL NOT require it, so an operator, the OpenAPI console and a management client keep reading the
  registry unchanged", and the archived change lists "both read projections" among the surfaces it
  deliberately left alone. `view=compiled` is an operator and Swagger read as well as the runner's.
- It would ship a second integer with the same bump condition as the first — the shipped header's
  javadoc already says it must be raised "on any breaking change to the compiled projection … or to the
  request or response shape of any of the four runner-only data-plane endpoints" — which is the
  one-spelling rule this change otherwise spends D2 and D3 enforcing.

*What still needs stating, since the handshake itself does not.* The data plane's `/rows` endpoint
already requires the header, so a stale runner is refused 409 before it can write anything. D5's refusal
of a caller-supplied `evaluator_version` is therefore **not** a deploy detector and must not be
justified as one; it is the ordinary rule that a retired field is refused rather than absorbed, applied
to a provenance column name, and it earns its place against a hand-rolled `curl` or an out-of-date
`adas-mcp` — callers the header gate does not reach in the same way.

### D9. The template-placeholder check runs at enable and preview, not on every write

A declaration is validated for **storability** when it is written and for **executability** when it is
armed. The placeholder check — every `{{placeholder}}` in `request_template` resolving against
`transform.inputs`, at the trigger's grain — belongs to the second group, because an author legitimately
saves a pipeline with a template written and its inputs not yet bound. Refusing that write makes the
registry unusable as a place to compose a declaration, which is the "create == publish" trap the fold
exists to remove, reappearing one field lower down.

So the check runs where an incomplete declaration is an error rather than a stage: on
`PATCH … {enabled: true}`, on a registration that arms the pipeline in the same call, and on
`POST /v1/pipelines/{name}/preview`. The machinery already exists — the static governance gate is
specified to run "on register and enable", so this splits one existing gate rather than adding a second.

*Where the line falls.* Shape and type dispatch stay at write time: `transform.type` required, the
`llm`/`sql` member rules, `outputs` non-empty through the accessor, `name`/`kind` immutability. Without
those the stored declaration is not coherently readable, and a draft that cannot be read back is not a
draft. What moves is the **referential** check between two members the author fills in at different
times.

*Deliberately narrow.* This is not the server-side draft object listed as a Non-Goal. No `draft` state is
added, nothing new is storable that was not storable before, and `enabled: false` remains what a
work-in-progress pipeline looks like. Whether the same relaxation should extend to the other referential
checks — `transform.outputs` resolving against the target, a `sql` expression resolving against the read
source — is a real question with a different blast radius, because those two feed the catalog's
drop/rename dependency guard. It is left open rather than answered here.

### D10. `pipeline_generation` reaches existing targets through a startup reconciler, not an operator

D5 adds a sixth provenance column, and `table-management` injects provenance **at creation only** —
`POST /v1/tables/{name}/schema` is refused with 409 on a materialized table and `PATCH` deliberately does
not inject. So every enrichment that exists today lacks `pipeline_generation`, and
`EnrichWriteService.dropAbsentProvenance` drops what the target does not carry. An earlier draft closed
that with an operator step: one additive `PATCH /v1/tables/{target}/schema` per existing enrich target,
before the ADAS deploy. **That step is withdrawn.** It is unbounded (one call per target, per
environment, from a list nobody maintains), it is ordered relative to the deploy, and — the part that
settles it — *it is less safe than doing it in code.*

*Why the manual PATCH is the weaker option.* `TableSchemaService.patchSchema` issues the ClickHouse
`ALTER` and **then** writes the catalog in `CatalogSchemaWriter.applyPatch`, and
`TableDdlRenderer.addColumnCommand` emits no `IF NOT EXISTS`. If the catalog write fails after the
`ALTER` lands, the two diverge and **retrying the PATCH cannot recover**: the duplicate check consults
the catalog, passes, re-issues the `ALTER`, and ClickHouse rejects it. Nothing in
`table-management` covers that boundary — its all-or-nothing property is stated *within* the `ALTER`
only. A reconciler that emits `ADD COLUMN IF NOT EXISTS` and re-runs every boot is idempotent by
construction and self-heals a crash between the halves on the next start.

*What is built.* `ProvenanceReconciler implements ApplicationRunner`, `@Order(2)`, beside the two that
already exist — `ClickHouseMigrationRunner` (`@Order(0)`, the DDL-issuing one) and `SchemaValidator`
(`@Order(1)`, which reconciles the mapping against the live table and whose javadoc says it "issues no
DDL"). A third at `@Order(2)` is the same shape and belongs after both. For every `ACTIVE`
`ENRICHMENT` whose name is the `target` of a `kind = 'ENRICH'` pipeline, and whose `column_mapping`
holds no `pipeline_generation` row: emit
`ALTER TABLE "t" ADD COLUMN IF NOT EXISTS "pipeline_generation" Int64` through
`ClickHouseDdlExecutor` — "the only runtime DDL path in ADAS (migrations aside)" — then append the
canonical column through `CatalogSchemaWriter`. DDL first, catalog second, which is the order that
`IF NOT EXISTS` makes replayable.

*Scoped to actual targets, not to every enrichment.* Two reasons, both concrete. A system-owned
enrichment (`dial_usage_log_payload`, seeded by V38) can never be patched through the API at all —
`requireModifiable` throws — so a reconciler over all enrichments would reach a table the API cannot,
which is a wider licence than this needs. And a non-target enrichment can never carry the stamp, so the
column would be dead weight. `PipelineEntity.target` is UNIQUE, so the target list is exact.

*It bumps nothing, and that makes it cheaper than the PATCH it replaces.* The manual patch would have
fired `PipelineGenerationService.onTargetColumnsChanged`, which is `llm`-scoped and would bump each
`llm` pipeline's `generation` once — a re-sync for a column no output binds to. The reconciler
deliberately does **not** call it: a provenance column changes neither the composed `response_schema`
nor the derived output mapping, so there is nothing for a consumer to re-read.

*It warns and continues; it does not fail startup.* The degraded mode already exists and is defined —
`dropAbsentProvenance` — so a ClickHouse hiccup costs lineage for one boot and is retried on the next.
Aborting instead would be actively worse: ADAS's liveness probe reads the aggregate `/actuator/health`,
so a boot-failing contributor restart-loops the service. Gated on
`ENRICHMENT_PROVENANCE_RECONCILE_ENABLED`, default `true`.

*Three alternatives rejected, recorded so they are not re-proposed.*

- **Lazily on the first enrich write.** The best-effort drop sits on the per-chunk hot path inside the
  write, with no transaction that owns DDL. A DDL failure there surfaces as a write-back failure, and a
  non-retryable shape dead-letters computed labels. Rejected.
- **At pipeline enable or registration.** It would never fire for the case that matters: existing
  pipelines are already enabled, are not re-enabled by this release, and the roll-out explicitly forbids
  toggling `enabled` because that bumps `generation` and discards the Class-1 materialized mark. Worth
  adding later as a supplement for a target armed after this release; it does not solve the existing set.
- **Flyway.** The ClickHouse lane cannot reach runtime-created tables and has said so since
  `V4__enrichment_updated_at_default.sql`: "Enrichments are runtime-created and none are seeded via
  ClickHouse migrations, so there is nothing to alter in this environment… If a deployment has
  pre-existing enrichment tables, add their MODIFY COLUMN statements above this line before applying."
  That is a manual instruction embedded in a checksummed migration — worse than the reconciler on every
  axis. The Postgres lane is **actively unsafe**: it can seed the `column_mapping` row (V29 is the
  pattern) but cannot issue the ClickHouse `ALTER`, producing a catalog row with no physical column —
  and since `dropAbsentProvenance` tests catalog-derived columns, that state makes **every** enrich
  write-back to that target fail. Rejected explicitly, not merely passed over.

This is the first time ADAS adds a column to an already-materialized enrichment, which is why it gets a
requirement in `table-management` rather than a footnote. `simplify-pipeline-authoring` recorded the
opposite position — "Provenance injection reaches new tables only… there is no 'existing tables re-post
cleanly'" — and that is what this decision revises, for one injected column, in code.

### D11. The column drop is deferred; one write constraint is required, and the deploy sequence is recommended

Three roll-out constraints in earlier drafts came from three different causes, and they are decided
separately because the reasons are unrelated. The deploy *strategy* is removable at the root. The
queue drain is removable at the root too, but by code in the runner rather than by argument. What is
left is a single hard constraint — **no pipeline write while ADAS is rolling** — around which the
familiar four-step sequence is a **recommendation**.

*This decision has now taken three positions, and a reader is owed the trail rather than a quiet
rewrite.* Revision 1 said there was no ordered window at all. Revision 2 said the window was required,
and rested it on two legs: the runner's queued work items, which at that point needed an operator
drain, and the outgoing ADAS pod's write path. **Revision 2 was correct on the evidence it had** — with
a drain in the procedure, an operator genuinely had to sequence the deploy, and the window was the only
place that sequencing could live. It went stale not because the argument was faulty but because the
drain it rested on was replaced by code (reason 1 below), which is a different kind of obsolescence and
worth distinguishing from an error. Revision 3, this one, keeps the surviving leg as a requirement,
demotes the sequence to a recommendation because a misorder is *held* rather than absorbed, and states
the cost of skipping the recommendation so that it is argued rather than asserted.

*The recreate strategy goes away by deferring the drop.* The hazard is real:
`PipelineRepository.findAllDeclarations()` is a JPQL constructor projection naming `p.evaluatorName,
p.evaluatorVersion`, and `findByEvaluatorNameAndEvaluatorVersionIsNull` is a derived query over both, so
dropping the columns fails **every pipeline read** on a pod still running the old build. An earlier
draft answered that with a deploy strategy — recreate, or a single replica. That answer is both a
manifest change in a separate deployment repository and ineffective: a typical deployment runs ADAS
`RollingUpdate` with effective `maxSurge 1 / maxUnavailable 0`, which *guarantees* the overlap rather than avoiding it.
(It also falsifies `denormalize-table-catalog`'s "there is no window in which old code meets the new
schema" — with `maxUnavailable: 0` there is one.)

So V41 drops only the CHECK. Both columns are nullable today and V36 says why — "the merged column has
to be nullable because an AGGREGATE row has no evaluator", with
`CHECK ((kind = 'ENRICH') = (evaluator_name IS NOT NULL))` as the thing that made it required for
ENRICH. Dropping that CHECK alone is therefore the whole of what the new build needs: it stops mapping
the fields, and new ENRICH rows store NULL. The old pod keeps reading, and keeps resolving exactly what
it resolved before the migration ran, because the fold does not null out the existing values and D6
retains the evaluator tables. The only row it could not resolve is an enrich pipeline **registered
during the overlap**, which fails at enrich-resolution rather than at read — the field is nullable, so
the SELECT is fine — and nobody registers a pipeline mid-roll. The FK tolerates NULL and needs no drop.

*What the contract version really buys, and it is genuinely a lot.* A **skew** — the two images
disagreeing about the wire — is refused rather than absorbed, and that half of the earlier reasoning is
correct and verified. `enforce-runner-contract-version` scopes the old charge-and-dead-letter damage to
**the predecessor image**, which "has none of the runner-side machinery this pair adds", and its charge
bullet names the fix outright: "A chunk already queued makes no ADAS call before its DIAL call, so the
predecessor derives and *charges* it; only the write that follows is refused. **Removing this cost is
exactly what the runner half's paid-work gate is for.**" Against the post-guard runner a skew costs
this: ADAS-first refuses every data-plane call with 409
before any lookup or read, cursors untouched; runner-first never reaches readiness and `maxUnavailable:
0` keeps the outgoing pod serving a matching ADAS. Class-1 positions freeze meanwhile, so consumers
such as `sessions_rollup` and `session-insights-live` clamp for a cadence and self-heal. Crucially the refusal
is a **hold**, not a failure: the runner's `AdasWorkGate.requireCompatible` throws
`AdasIncompatibleHoldException`, which reschedules the work item with no attempt consumed and nothing
archived. A skew therefore stalls and self-heals once both halves are up. That is the whole reason the
*ordering* is a recommendation in revision 3 where revision 2 made it a requirement — and it is also
why revision 1's reversal of the Non-Goal was tempting and still wrong, because a recommendation with a
stated cost is not the same as no window at all.

*Two hazards live outside the request path the guard polices, and only one of them still needs a human.*
The guard is a check on a **call in flight**. Neither of the following is a call in flight, so neither
is reachable by any version handshake, and each left unmitigated is irreversible in a way a held work
item is not. They part company on what answers them: the first is answered by code in the runner —
which is why the roll-out gains no step for it, and why the window stopped being *required* — and the
second only by nobody writing, which is a prohibition rather than an ordering.

1. **The runner's queued work items — mitigated by code rather than by a roll-out step, which inverts
   what an earlier revision of this decision concluded.** The hazard is real and the reasoning behind it
   is kept, because it is the justification for the tolerance the runner now ships rather than something
   the fold made go away. Scaling the runner to 0 stops execution; it does **not** empty
   `scheduled_tasks`. A work item enqueued before the window carries a payload naming `evaluatorVersion`,
   and db-scheduler's `JacksonSerializer` disables `FAIL_ON_UNKNOWN_PROPERTIES` (verified in
   `db-scheduler-15.6.0`), so the new image deserializes it **silently** with `pipelineGeneration = 0`.
   Left alone, each lane then does something wrong with it and says nothing: a chunk is dropped by the
   stale-declaration guard and its rows are gone for good, because the scan cursor advanced in the
   transaction that enqueued the chunk; a group item is dropped at the cost of a re-derivation; an
   `upsert-rows` item is dropped, destroying a result the model was already paid for, because a
   stale-declaration write is dropped rather than archived by design. Nor could it be repaired
   afterwards — the runner's `DlqRequeueService.foldEra` refuses every pre-fold payload permanently, so
   there would be nothing to requeue.

   *What the runner does instead.* `0` is never a generation ADAS serves, so it is not a stale
   generation at all — it is an unambiguous **pre-fold marker**, and the runner reads it as one. On the
   chunk and write lanes it **adopts the live generation and runs the item**, so nothing is lost: no
   scanned row, no already-paid-for model result. Adoption rather than merely skipping the stale check
   is load-bearing, and the distinction is easy to miss: the payload's generation is read downstream to
   build the derived `UpsertPayload`, to key the DLQ row's identity and to attribute spend, so a leaked
   `0` would destroy the very result being saved and produce DLQ rows that can never be requeued. On the
   write lane the runner additionally strips the retired `evaluator_version` provenance key — which ADAS
   now refuses with 422 — and deliberately stamps **no** `pipeline_generation` in its place: provenance
   columns are optional here and nothing reads them back, so an honest absence beats asserting a lineage
   that is not true. On the group lane the item is **discarded**, with a log and a metric but no DLQ
   row, because the readiness sweep re-derives that group anyway; tolerating it would cost two model
   calls, since the sweep's in-flight dedup keys on the generation and cannot see a pre-fold item.

   *Why not a shutdown drain, which is the obvious alternative and was rejected twice over.* First, a
   drain has to run **in the image being replaced**, and 0.2 already shipped without one. Adding it
   now would first pay off at the *next* release, by which point the payload shape is stable and there
   is nothing left to protect. Second, it would not be sufficient even then: db-scheduler's
   `shutdownMaxWait` defaults to 30 minutes against a 30-second Spring shutdown phase and a 30-second
   Kubernetes grace period, so in-flight executions are SIGKILLed and the new build resurrects them as
   dead executions — that is, as pre-fold items — however thoroughly the queue was drained. A tolerance
   in the *new* image is the only mitigation that is both deployable and complete. The operator-facing
   consequence is the one worth stating plainly, because the earlier revision said the opposite in as
   many words ("the mitigation is a roll-out step, not code"): a roll-out deploys, undeploys, scales and
   updates services; it should not require running SQL or calling an API, and it must not block on a
   queue emptying.

   *The residual, stated rather than glossed.* A queued item is run under whatever declaration is
   current when it executes, so a declaration edited *inside* the window could mean a newer prompt, a
   same-name variable repointed to a different column, or a `filter` that now excludes those rows. All
   three were equally true of the 0.2 build that created those items — 0.2's stale key was the evaluator
   version, while `vars`, `filter` and `trigger` were already pipeline-level and never dropped a chunk —
   so this is not a regression. The one genuinely new case is a repointed `target`, and it is narrow.
   Either way it is eliminated by the hard constraint of reason 2 — no pipeline write while ADAS is
   rolling — for as long as ADAS is rolling, and beyond that by the weaker standing caution not to edit
   a declaration while the carried-over queue is still draining.

2. **The outgoing ADAS pod also *writes* `spec`, which this decision originally missed by reasoning only
   about reads.** `PipelineService.validateAndStore` ends in `pipeline.setSpec(specCodec.write(spec))`,
   and it is reached from a plain `PATCH {"enabled": true}` — not only from
   a declaration edit. The pre-fold `EnrichSpec` is `@JsonIgnoreProperties(ignoreUnknown = true)` and has
   no `transform` member, and the new build serves `input_bindings` under `transform` rather than at the
   top level. So an old pod that handles *any* pipeline write during the roll reads the folded row,
   drops `transform` on the floor as an unknown member, and writes the spec back **without it** and with
   `input_bindings` reset to `[]` — erasing what V41 just folded in, with no error anywhere. And this is
   not a narrow race: the same `maxUnavailable: 0` that keeps the old pod serving through a
   runner-first skew *guarantees* such a pod exists for the whole length of the ADAS roll. Hence the
   one hard requirement of this roll-out: **no pipeline write while ADAS is rolling**, operator or
   `adas-mcp` alike, and no deploy strategy or ordering substitutes for it. What the new build later
   does with such a spec has improved without removing the constraint: it **fails fast** on reading a
   folded pipeline with no transform, so the erasure surfaces loudly rather than as a pipeline that
   quietly evaluates nothing. Loud is not the same as harmless — recovery still means re-declaring the
   pipeline's `transform` from the migration's `RAISE NOTICE` record.

So the roll-out is the sequence `simplify-pipeline-authoring` used, with **nothing added to it and its
ordering demoted**: the recommendation is still scale the runner to 0, deploy ADAS, deploy the runner,
scale back, and the one constraint this change contributes — **no pipeline write while ADAS is
rolling** — is the only part of the whole roll-out that is a requirement. Note the shape of that
requirement: it is a thing the operator must *not* do, not a step they must perform, and it is
indifferent to the order the two services land in. Nothing is drained, paused or counted first.
`enforce-runner-contract-version`'s Non-Goal — "Removing the deploy window. Exact matching keeps it; it
only changes what happens when it is not followed" — stands as written, and this decision reads it the
way it is phrased: exact matching keeps a *window*, meaning a period in which the pair is skewed and
work does not flow. It does not make a particular order mandatory, because what happens when the order
is not followed is that work is held and then runs.

*What skipping the quiesce costs, since a recommendation invites skipping and the cost must be stated
rather than hidden.* Leaving the runner up through the ADAS roll is not the same as a clean skew,
because during a rolling update **both ADAS builds serve concurrently**. A running old runner can
therefore read a *compatible* echo from an outgoing pod, start work on the strength of it, pay for a
model call, and then have the write that follows refused 409 by a new pod. That work is held rather
than failed — the same `AdasIncompatibleHoldException` path — and the new runner build's pre-fold
adoption writes it after the upgrade, so it is **recovered, not lost**. What it costs is a deferred
write and a compatibility signal that flaps for the length of the roll, which is noise in the metrics
and in whatever alerts on them rather than damage to any row. Scaling to zero buys that off for the
price of a few minutes of frozen Class-1 positions. That is the honest trade, and it is why the
sequence is recommended rather than merely tolerated.

*The limit, stated rather than glossed.* The paid-work gate lives in `analytics-enrichment-runner`; this
repository asserts it and cannot verify it. It is what bounds an *accidental* skew — the residual if it
does not hold as documented is DLQ items that requeue for free **with the pipeline left enabled**,
because every state-mutating call goes through the interceptor. Task 9.5 observes it during the roll-out;
that is the moment to confirm the stall is silent rather than charged, and it costs nothing extra. Note
what it does **not** cover: a work item already sitting in `scheduled_tasks` makes no ADAS call before
its model call, which is exactly why that hazard is answered by the runner's pre-fold tolerance rather
than by the handshake or by any roll-out step.

*The operator doc gains two sentences and no argument.* `docs/configuration.md`'s "Release and
deployment order" keeps its four numbered steps exactly as they are. Step 2 gains the constraint the
section was missing and which is the only hard one — **issue no pipeline write for the length of the
roll**, folded in as a warning where the operator is already reading. And one line is added whose only
job is to stop the withdrawn step being reinvented: work the runner still had queued needs no drain,
pause or count, because the new build adopts and runs it. That is the whole edit. The required-versus-
recommended levelling argued here is deliberately **not** carried across: a reference doc tells an
operator what to do and what not to do, and a preamble explaining that three of its four steps are
advisory invites exactly the improvisation the steps exist to prevent. An operator following the
section as written gets the recommended sequence and obeys the requirement inside it, which is the
outcome either framing is after. The reasoning belongs here; the doc gets the instruction.
`docs/release-0.2-upgrade.md` is **not** touched at all; it is a release-scoped, read-only record (tasks
8.2 and 8.3a).

*What else is worth sequencing.* Only what ADAS cannot do for itself: the runner and `adas-mcp` are
separate images in separate repositories. `RunnerContract` records that serving a range rather than an exact
match "is a design change, not a relaxation", so the pairing cannot be dissolved by widening the
comparison. `adas-mcp` is unordered — it makes no data-plane call — but it must not be used to edit a
pipeline mid-roll either, for reason 2 above. "Do not disable pipelines during the roll-out" also stays,
and now carries a second reason: a disabled pipeline destroys the labels of anything requeued into it.

### D12. An output's `prose` is optional, defaulting to the target column's `description`

Composition already reads three of a property's four facts off the target column — `type`, the `enum`
domain and **enum order** — and the response-schema table names the fourth as "the output's prose". That
asymmetry has a measured cost: an author copies the column description into the declaration by hand, and
the two then drift. This is the same defect class `ResponseSchemaComposer` was written to remove.

*Read-time, not materialized.* The default is applied where the schema is composed, not written into the
stored `spec`. Both forms round-trip faithfully — a sparse declaration returns sparse, a materialized one
returns what it stored — so the round-trip goal does not decide it. What decides it is that materializing
re-creates the drift-capable duplicate on purpose, and additionally destroys the operator's ability to
tell an authored prose from a defaulted one. One fact, one authority, consistent with how `type` and
`enum` are already composed live.

*Where it surfaces.* In the composed `response_schema` only — never in `view=source`, and never in the
authored `transform` block under `view=compiled`. Showing a derived default on the authored surface is
precisely what would break the round trip subtly: an operator `PATCH`es it back, it becomes authored, and
it silently stops tracking the column. The runner reads the schema and never reads `prose`
(`PipelineMapper`: "the prose is already baked into the `response_schema` descriptions"), so there is **no
runner impact and no contract consideration**.

*Blank on both sides composes no description.* `ResponseSchemaComposer.property()` already omits a blank
description, and a JSON Schema property without one is valid — it is exactly what a folded legacy
declaration already produces. Refusing at write would contradict D9's storable-but-not-armable line;
refusing at enable buys nothing a note does, because a description-less field is degraded, not broken.
`POST /v1/pipelines/{name}/preview` gains a note beside the existing `NO_SCHEMA_NOTE`.

*`llm` only.* A `sql` output's string is an expression, not prose; it cannot be defaulted from anything
and keeps requiring a non-blank value. The shape dispatch is already type-split, so the relaxation
cannot leak across.

*One spelling for "default it".* An absent value, `null`, `{}` and a blank string all mean the same
thing, and the mapper already carries `null` and `{}` through as an output with only a name. An earlier
draft of this decision *also* refused "an object carrying none of `prose`/`values`/`jsonata`" with 422,
as a second spelling — which contradicts the sentence above it and is not implementable: `{}` and
`{"prose": null}` bind to exactly the value `null` binds to, so the refusal would reject one spelling of
*default it* while accepting the others. The refusal is withdrawn; all four forms are accepted.

*The cost, which this change owns rather than absorbs.* The column `description` becomes a live input to
the composed prompt, which falsifies this change's own rule that "a **metadata-only** patch SHALL bump
nothing" — justified verbatim by "an output's prose comes from the declaration rather than the column".
That rule inverts: a `description`-touching metadata patch on an `llm` target bumps, a
`display_name`/`tag`/`sensitive`/`heavy`-only one still does not. The code widening is small and lands in
a path built for it — `MetadataWrite` already carries `Change<String> description`, so the two are
distinguishable, and `PipelineGenerationService.onTargetColumnsChanged` already self-scopes to `llm`, so
no Class-1 materialized position is discarded. `CatalogSchemaWriter`'s comment asserting the old
rationale is rewritten with it. Interaction with a stored `response_schema` (D4) is nil by construction:
composition does not run when one is stored, so the default fires exactly where composition fires, and
`output_vars` has no prose member at all.

## Risks / Trade-offs

- **A pipeline pins an evaluator version that does not exist** → `pipeline.evaluator_name` has an FK but
  `evaluator_version` has **none** (and `evaluator.latest_version` has no FK back either, so an
  evaluator with zero versions makes `max(version)` NULL), so an `UPDATE … FROM` would silently not match
  and leave a pipeline with no transform. Mitigation is **two** statements, not one: a
  `DO $$ … RAISE EXCEPTION` pre-flight — the pattern V36 used for name collisions — **and** a
  post-`UPDATE` assertion that no `kind = 'ENRICH'` row has `spec -> 'transform' IS NULL`. The pre-flight
  alone is a prediction: Flyway runs the migration in one transaction but at READ COMMITTED, so the
  pre-flight and the `UPDATE` take separate snapshots and a concurrent admin write between them would
  make the `RAISE NOTICE` log disagree with what was written. Resolving once into a CTE-backed temp table
  and driving both the log and the `UPDATE` from it closes that too — "makes that a checked fact rather than an assumption, and fails the deploy
  loudly instead of at the first read". Such a pipeline already breaks the whole sync feed today (a 404
  for every pipeline, because `resolveAll` throws on the first unresolvable ref), so this is pre-existing
  damage surfaced, not caused.
- **The declaration-patch completeness trio names a member that no longer exists** →
  `PipelineMapper.requireComplete` demands `target`, `evaluator_name` and `trigger` together, and
  `PatchPipelineRequest.hasDeclaration()` tests `evaluatorName`. Removing the member without replacing
  it in both places either breaks every declaration patch or, worse, lets a patch omit `transform` and
  merge the stored one into a declaration the caller did not write. Both become `transform`. While
  checking this, one shipped statement was found not to match the code and is corrected in the
  `enrichment-registry` delta: the spec says a patch carrying only `advanced` is "re-validated and
  stored" and bumps `generation`, but `requireComplete` refuses it. "Counts as a declaration" answers
  the enable-only question, not the completeness one.
- **The gate rejects every folded old-shape declaration** → the evaluator's shape check requires at least
  one `outputs` entry, and an old-shape declaration has none. Moving that check onto the pipeline verbatim
  would refuse every folded old-shape pipeline at the first patch, enable or read-time validation. Mitigation: the
  gate resolves outputs through the single accessor (`outputs`, else `output_vars`) exactly as every other
  consumer does, and requires at least one *resolved* output. Called out because the check reads as a
  faithful move and is not one — this is the single most likely way to ship this change broken.
- **The folded `spec` carries JSON nulls** → `jsonb_build_object` writes a key for a NULL argument, so a
  folded `sql` transform would store `model: null`, `preset: null`, `request_template: null` and
  `params: {}`. None of those is *rejected* (`rejectLlmOnlyField` tests non-blank and non-empty), so this
  is untidiness rather than breakage — but the untidiness is on the write surface, where a round-tripped
  patch echoes it back. Mitigation: build the scalar members through `jsonb_strip_nulls` and concatenate
  the JSONB members conditionally. **Do not `jsonb_strip_nulls` the whole object**: it recurses, and a
  stored `response_schema` or `params` may legitimately contain a JSON null.
- **Pre-fold pipeline snapshots carry no transform** → stated in the `audit-trail` delta as a known
  limitation, with the retained archive and the migration's `RAISE NOTICE` log as the mitigation (D6).
  Not solvable: the pin was resolved at read time, never stored per revision.
- **The fold migration creates no revision and bumps nothing** → deliberate, and stated so nobody
  "fixes" it. `generation` and `updated_at` are untouched, because the compiled projection is unchanged
  by the fold: bumping would re-sync every runner lane and discard every Class-1 materialized mark for
  a change no consumer can observe. The cost is a discontinuity in `pipeline_aud`, where the transform
  appears between two revisions with no revision in between.
- **A rolling ADAS deploy would break the pod it is rolling from** → *removed by D11, not mitigated by a
  deploy strategy.* The hazard was real and is worth keeping on the record because it is what the
  deferral exists for: `PipelineRepository.findAllDeclarations()` is a constructor projection naming
  `p.evaluatorName, p.evaluatorVersion`, so dropping those columns fails every pipeline read on a pod
  still running the old build, and a `maxUnavailable: 0` rollout guarantees such a pod exists for
  the length of the roll. V41 therefore drops only the CHECK and leaves both columns in place.
- **The deferred columns are never actually dropped** → the standing cost of expand/contract, and the
  honest risk of D11: two unmapped columns, an FK and an index survive in `pipeline` until a follow-up
  change removes them, and a follow-up nobody schedules is how dead schema accumulates. Mitigation is
  cheap and is a task, not a hope: the follow-up is named in this change and folded into Open Question 2,
  which already has to be answered for `evaluator` / `evaluator_version` themselves.
- **The reconciler diverges ClickHouse from the catalog** → it issues DDL then writes the catalog, the
  same order `applyPatch` uses and the same order the known trap names. The difference is that
  `ADD COLUMN IF NOT EXISTS` makes the DDL half replayable, so a crash between the two self-heals on the
  next boot instead of wedging the way a retried `PATCH` does. The residual is one boot in the
  already-defined degraded mode (`dropAbsentProvenance`), not a stuck state.
- **The reconciler runs on every boot, on every environment** → it reads `column_mapping` for the enrich
  targets and, in the steady state, issues nothing. The cost is one query per boot. It is gated on
  `ENRICHMENT_PROVENANCE_RECONCILE_ENABLED` for an operator who needs it off, but the default is `true`
  because a disabled reconciler is a silently degrading stamp — the failure mode the manual step had.
- **Warn-and-continue hides a persistent failure** → a reconciler that never succeeds logs every boot
  and stamps nothing, which looks exactly like the pre-change behaviour. Accepted deliberately over the
  alternative: ADAS's liveness reads the aggregate `/actuator/health`, so failing startup would
  restart-loop the service over a lineage column. The metric and the log are the signal.
- **This change leans on a guarantee implemented in another repository** → the runner's paid-work gate
  is what bounds an *accidental* skew to a silent stall rather than a charged call whose write is then
  refused. That gate is asserted by `enforce-runner-contract-version` and cannot be verified from here.
  Bounded rather than trusted: every state-mutating call goes through the contract interceptor, so the
  worst residual is DLQ items that requeue for free with the pipeline left enabled. Task 9.5 observes it
  during the roll-out. The deliberate roll-out does not rest on it — D11's one hard constraint, no
  pipeline write while ADAS rolls, is for a hazard the gate does not reach at all.
- **A queued work item that predates the window is invisible to the contract guard, and "the runner is
  scaled to zero" does not cover it** → scaling to 0 stops execution but leaves `scheduled_tasks`
  populated, and the new runner image reads a pre-fold payload as `pipelineGeneration = 0` rather than
  failing on it. Mitigated **by code rather than by a roll-out step**, which is precisely the inversion
  of what an earlier draft of this section asserted: `0` is a generation ADAS never serves, so the
  runner treats it as a pre-fold marker, adopts the live generation on the chunk and write lanes and
  runs the item, and discards it on the group lane the sweep re-derives anyway. Task 9.1a exists to keep
  the withdrawn drain withdrawn. Removing this leg is also what demoted the deploy sequence from a
  required window to a recommended one: it was the only leg that needed an operator to act in order.
  The residual is that such an item runs under whatever declaration is current when it executes — true
  of the 0.2 build that enqueued it as well, except for a repointed `target`, and eliminated for the
  length of the ADAS roll by the "no pipeline write while ADAS rolls" requirement. Stated in full in
  D11.
- **An old ADAS pod silently un-folds a pipeline it is asked to write** → `validateAndStore` rewrites
  `spec` on every write including a bare `PATCH {"enabled": true}`, and the pre-fold `EnrichSpec`
  ignores unknown members, so the folded `transform` is dropped and `input_bindings` reset to `[]`.
  Mitigated by the "no pipeline writes during the ADAS roll" constraint (task 9.2a) rather than by code:
  with `maxUnavailable: 0` there is no deploy strategy that removes the overlap, and a guard in the new
  build cannot constrain the old one. See D11.
- **A column description now edits a live prompt** → the consequence of D12, and the reason it is a
  decision rather than a rider. Under composition a `description`-only metadata patch changes what the
  model is told, so it must bump `generation`; a reader of the old rule would expect it not to. Bounded
  by the existing `llm` scoping, so no Class-1 materialized position is discarded, but it means an
  analyst tidying a column description on an `llm` target causes one runner re-sync. Stated in the
  `enrichment-registry` delta rather than left to be discovered.
- **Concurrent work moves the migration baseline** → the fold's Flyway version must be picked against
  the migrations that already exist, and a duplicate version never conflicts in git — it fails every
  test at startup. V39 is `evaluator_outputs` (`simplify-pipeline-authoring`) and V40 is
  `payload_partition_alignment`, from the pipeline-read bounding work, which may already be applied
  wherever it shipped and is certainly not reusable, so **V41** is the next free number. The same
  staleness reached two spec deltas, which were written against a
  `class1-enrichment` predating `enforce-runner-contract-version` and `add-relative-time-functions` and
  would have silently reverted the probe-cost/narrowed-write rules and the explicit `now()` determinism
  check — both rebased onto the current base-spec text.
- **Two pipelines resolve to the same evaluator on some deployment** → each gets its own copy and they
  diverge thereafter. This is the intended semantics and the `UPDATE` handles it with no special case; it
  may not occur in one registry but must not be assumed absent elsewhere.
- **`spec` grows large** — a `request_template` is multi-kilobyte (`session-insights` v6's is ~14 KB) and
  every revision mirrors it into `pipeline_aud` → accepted. `rule_aud` already mirrors JSONB, revisions
  are per-administrative-change not per-tick, and `updated_at` is only stamped by declaration changes.
- **A prompt change is now armed by the same call that makes it** → pre-fold, an operator could register
  a new evaluator version and repoint a pinned pipeline as two steps; post-fold a `PATCH` is one step and
  an enabled pipeline runs the new prompt immediately. The net is not obviously worse — on an *unpinned*
  pipeline, pre-fold registration was already instantly live, and it was unattributed — but the two-step
  option is gone, and `POST /v1/pipelines/{name}/preview` previews only the stored declaration. Accepted;
  the replacement, if operators ask for one, is a preview that accepts a candidate transform in its
  request body, which is additive and out of scope here.
- **The old-shape path may have no environment covering it** → before those two changes shipped, a
  broken `outputs`-else-`output_vars` accessor would have surfaced the moment the migration ran, because
  every existing pipeline resolved to an old-shape version. A registry re-registered since then folds to
  the new shape, so the legacy branch can be exercised by **nothing** on the first environment this
  reaches. It is still required: the fold resolves `COALESCE(pinned, max(version))` per pipeline, so
  any pipeline whose latest or pinned version predates `simplify-pipeline-authoring` still folds
  old-shape, and other deployments may hold any mixture. Mitigation: fixture tests, not a smoke test — and the sharp end is the
  drop/rename guard, where a missed resolution is silent DDL data loss rather than a failed request.

- **A misordered deploy renders unsubstituted placeholders** → a runner reading a feed that has no
  `transform.inputs` (old runner, new ADAS) substitutes nothing, sends literal `{{request}}` to the
  model, is charged for the call and writes the answer as a label. This is why the contract version
  must be bumped in this change rather than left at 1 (D8): with the echo at 2 the runner classifies the
  feed response incompatible and never interprets its body, so the render never happens. Leaving
  `RunnerContract.VERSION` alone is the one way to ship this change with that hazard live — and
  `RunnerContractShapeTest` is what makes forgetting it a build failure rather than a deploy.

- **The runner-side placeholder assertion is not a substitute for the contract version, and vice
  versa** → a version
  handshake catches "wrong build", and a placeholder assertion catches "right build, wrong
  declaration" — a template edited to reference a variable that was never bound, which D9 now permits
  to be *stored*. Both are wanted, in different repositories, and neither implies the other.

- **D9 widens what can sit enabled-adjacent in the registry** → a pipeline may now be stored with a
  template whose placeholders bind to nothing. It cannot run: enabling it is refused, and previewing it
  is refused. The visible cost is that `GET /v1/pipelines` can list a declaration that would not arm,
  with nothing in the response saying so. Accepted — `enabled: false` already meant "not running" and a
  caller reading a disabled pipeline learns the same thing — but a future `validate` endpoint, or a
  `state.armable` member, is the honest fix if operators ask for one.

- **`adas-mcp` breaks** → its evaluator write tool must be removed and its pipeline tools extended in the
  same window. Regenerating its OpenAPI snapshot churns ~1400 lines of hash-order noise; compare
  structurally and use `--sort-keys`.
- **Losing the DB shape constraints** (D1) → the service layer keeps enforcing them with a 422. Accepted
  deliberately; if a future defect writes a malformed `spec` directly to Postgres, nothing catches it.

## Migration Plan

**There are no pre-upgrade *table* edits**: Flyway folds the declarations and the reconciler adds the
one missing provenance column on the same startup, so nothing is patched per target by hand. There is
exactly one **hard requirement** — **no pipeline write while ADAS is rolling** — and around it a
**recommended sequence**: scale the runner to zero, deploy ADAS, deploy the runner, scale back. The
sequence is a recommendation rather than a requirement because a misordered deploy is refused *and
held* rather than absorbed, so it stalls and self-heals; it is still recommended because skipping the
quiesce defers writes and flaps the compatibility signal for the length of the roll. There is no
preparatory step inside the window either: nothing is drained, paused or counted, because the new
runner build adopts and runs the work its predecessor had queued. D11 says why the write-path hazard is
not reachable by the contract version, why the queued-item one is answered in the runner rather than by
an operator — which is what demoted the sequence from required to recommended — and what skipping the
quiesce costs.

0. **On startup, after Flyway**, `ProvenanceReconciler` (D10) adds `pipeline_generation` to every
   `ACTIVE` enrichment that a `kind = 'ENRICH'` pipeline targets and whose catalog holds no such column:
   `ALTER TABLE … ADD COLUMN IF NOT EXISTS "pipeline_generation" Int64`, then the catalog row. It is
   idempotent, bumps no `generation`, and warns rather than failing startup. An earlier draft made this
   an operator's `PATCH /v1/tables/{target}/schema` per target *before* the deploy; that step is
   withdrawn as both unbounded and less safe than the reconciler — see D10 for why, including why the
   Flyway routes are rejected and why the Postgres-only variant is actively unsafe.

1. **Pre-flight** (same migration, first statement): `RAISE EXCEPTION` if any `kind = 'ENRICH'` pipeline's
   `COALESCE(evaluator_version, max(version))` does not resolve to an `evaluator_version` row, naming
   every offender. `RAISE NOTICE` the `(pipeline, evaluator_name, resolved version)` triples that will be
   folded — the only record of the mapping once the columns are gone (D6).

2. **Fold**, entirely generic — no environment-specific name lists:
   ```sql
   UPDATE pipeline p
   SET spec = p.spec || jsonb_build_object('transform',
           jsonb_strip_nulls(jsonb_build_object(
               'type',             lower(ev.type),
               'model',            ev.model,
               'preset',           lower(ev.preset),
               'request_template', ev.request_template))
           || CASE WHEN ev.params <> '{}'::jsonb
                   THEN jsonb_build_object('params', ev.params) ELSE '{}'::jsonb END
           || CASE WHEN ev.outputs IS NOT NULL
                   THEN jsonb_build_object('outputs', ev.outputs) ELSE '{}'::jsonb END
           || CASE WHEN ev.output_vars <> '[]'::jsonb
                   THEN jsonb_build_object('output_vars', ev.output_vars) ELSE '{}'::jsonb END
           || CASE WHEN ev.response_schema IS NOT NULL
                   THEN jsonb_build_object('response_schema', ev.response_schema) ELSE '{}'::jsonb END)
   FROM evaluator_version ev
   WHERE p.kind = 'ENRICH'
     AND ev.name = p.evaluator_name
     AND ev.version = COALESCE(p.evaluator_version,
           (SELECT max(e2.version) FROM evaluator_version e2 WHERE e2.name = p.evaluator_name));
   ```
   `COALESCE(pinned, max(version))` is `EvaluatorService.select()` expressed in SQL. The `outputs` test
   SHALL be `ev.outputs IS NOT NULL AND ev.outputs <> '[]'::jsonb`, not `IS NOT NULL` alone:
   `EvaluatorVersionEntity.effectiveOutputVars()` reads an **empty** `outputs` as old-shape
   (`outputs == null || outputs.isEmpty()`), so the looser test would fold a row the accessor then reads
   the other way. The `params` and `output_vars` tests need no null guard — both columns are
   `NOT NULL DEFAULT`, so `<>` can never yield NULL there, while `outputs` and `response_schema` are the
   two nullable ones and correctly use `IS NOT NULL`. `type` and `preset`
   are uppercase `TEXT` with CHECK constraints (V11/V14), and are lower-cased for the same reason V36
   lower-cased `priority`: the application's `JsonMapper` writes enums lower-case, so the folded value
   must equal what a later `PATCH` would store. `jsonb_strip_nulls` wraps only the four scalar members —
   never the whole object, which would recurse into a stored `response_schema` and delete legitimate
   JSON nulls. `input_vars` is deliberately **not** folded: `EvaluatorService` has written it empty since
   the `vars` shape landed and nothing reads it, so there is nothing to carry.

2a. **Assert** afterwards that the fold covered every row: `RAISE EXCEPTION` if any `kind = 'ENRICH'`
   row has `spec -> 'transform' IS NULL`. The `UPDATE … FROM` skips a non-joining row with no error and
   no row count, so this is what turns "every ENRICH pipeline has a transform" into a checked fact
   rather than something the pre-flight predicted (see Risks). This is also the project's **first
   in-place mutation of `pipeline.spec`** — V36 populated `spec` only for rows it inserted and states
   that it "never touches an existing row's `spec`" — which is worth saying in the header comment.

3. **Drop the `pipeline_evaluator_iff_enrich` CHECK, and nothing else.** That CHECK —
   `CHECK ((kind = 'ENRICH') = (evaluator_name IS NOT NULL))`, V36 — is the only thing making
   `evaluator_name` required for an ENRICH row; neither column has a column-level `NOT NULL`. Dropping
   it is therefore the whole of what the new build needs in order to store an ENRICH pipeline with no
   evaluator. `pipeline.evaluator_name` (with its FK and `pipeline_evaluator_name_idx`) and
   `pipeline.evaluator_version` are **retained, unmapped and unread**, so a pod running the old build
   keeps reading and keeps resolving through the overlap; the follow-up change
`drop-retained-evaluator-columns` drops them once no
   deployed build maps them (D11). Leave `pipeline_aud.evaluator_name` /
   `pipeline_aud.evaluator_version` in place too — a mirror row is a historical fact, and V36 already
   gave them no FK precisely so they survive the evaluator being retired.

4. **Do not touch** `evaluator`, `evaluator_version`, or any ClickHouse table. The `UPDATE` does not
   bump `generation` or advance `updated_at`, deliberately (see Risks).

5. **Deploy.** Steps 5.1 to 5.4 are the **recommended** order, with any strategy; **5.2's "no pipeline
   write" is the one part that is required.** Out-of-order is refused and held rather than absorbed
   (D8), so it stalls and self-heals — it is not free, and the cost is stated under 5.4:

   1. **Scale the Enrichment Runner to 0** and wait for the pod to be gone. That is the whole step:
      nothing is drained, paused or counted, and no SQL is run. Scaling to 0 stops execution and does
      *not* empty the queue — a work item enqueued before the window survives into the new image as
      `pipelineGeneration = 0` — but the new build reads that as a pre-fold marker, adopts the live
      generation and runs the item on the chunk and write lanes, and discards it on the group lane the
      readiness sweep re-derives anyway (D11). An earlier revision of this plan put a
      `scheduled_tasks` drain here; it is withdrawn, because it asks operators to run SQL for a hazard the
      runner now handles, and because a shutdown drain could neither ship in the image being replaced
      nor survive the SIGKILL that `shutdownMaxWait` guarantees.
   2. **Deploy ADAS**, with **no pipeline write in flight** — no `POST`, `PATCH` or `DELETE` on
      `/v1/pipelines`, from an operator or from `adas-mcp`, for the length of the roll. An outgoing pod
      rewrites `spec` on any write and silently drops the folded `transform` (D11). No recreate strategy
      and no replica constraint are needed: step 3 leaves the columns the outgoing pod *reads*.
   3. **Deploy the Enrichment Runner**, reading `transform` and built against contract version 2, and
      **scale it back to 1**. Its readiness stays DOWN until the first pipeline sync succeeds, so a
      ready pod is itself the confirmation that the pair matches.
   4. **Deploy `adas-mcp`** — evaluator write tool removed, pipeline tools carrying `transform`. It
      makes no data-plane call, so it is unordered relative to the rest; it only may not be used to edit
      a pipeline during 5.2.

   **The contract version bump is what makes a *skew* safe rather than trusted** (D8): whichever half
   lands first, the other refuses to act on what it cannot interpret — an `Adas-Contract-Version` echo
   the runner will not read a body against, or a 409 on the data plane — instead of writing a wrong row.
   On the runner side the refusal is a **hold** — `AdasWorkGate.requireCompatible` throws
   `AdasIncompatibleHoldException`, which reschedules the item without consuming an attempt — so
   getting the order wrong costs a stall that clears itself. That is what makes 5.1, 5.3 and 5.4
   recommendations rather than requirements. It does not make them free, and it does not reach 5.2's
   constraint, because neither hazard in D11 is a call the guard can refuse.

   **What skipping 5.1 costs**, since it is a recommendation and someone will skip it: during the ADAS
   roll both ADAS builds serve concurrently, so a running old runner can read a *compatible* echo from
   an outgoing pod, start work, pay for a model call, and then have its write refused 409 by a new pod.
   That work is held rather than failed, and the new runner build's pre-fold adoption writes it after
   the upgrade — **recovered, not lost**. The cost is a deferred write and a compatibility signal that
   flaps for the length of the roll: noise, not damage. Scaling to zero buys it off, at the price of a
   few minutes of frozen Class-1 positions.

   Do **not** disable pipelines as part of the roll-out. Toggling `enabled` bumps `generation`, which
   discards the materialized mark — and if anything did dead-letter, requeueing into a disabled pipeline
   destroys the stored labels. Anything dead-lettered **before** the new runner image cannot be requeued
   at all and is re-derived at full model cost — `DlqRequeueService.foldEra` refuses a pre-fold payload
   permanently, and pre-0.3 `dlq_item` rows outlive the deploy. That is a property of the payload era,
   not a consequence of skipping any roll-out step: it holds however the deploy is sequenced, and there
   is no drain that would have prevented it.

6. **Afterwards, in the follow-up change `drop-retained-evaluator-columns`**:
   `DROP COLUMN pipeline.evaluator_name` (its FK and
   `pipeline_evaluator_name_idx` go with it) and `DROP COLUMN pipeline.evaluator_version`, once no
   deployed build maps them. Unordered and unremarkable by then. It pairs naturally with Open Question 2,
   which has to answer the same question for the `evaluator` tables themselves.

*Rollback*: not a design constraint for this project. Deploy skew is still real, which is what the
deferred drop and the contract version exist to bound.

## Open Questions

1. **Should the fold also drop orphan evaluators that no pipeline references?** D6 keeps everything. Dropping the
   obvious test artifacts is a separate, operator-driven cleanup — and under the fold it is no longer
   urgent, because they are invisible to every write path.
2. **When do `evaluator` / `evaluator_version` get dropped for good — and with them
   `pipeline.evaluator_name` / `evaluator_version`?** The two halves of the same follow-up. The
   `pipeline` columns are the easy half and have a precise trigger: once no deployed build maps them,
   which is one release (D11). The archive tables are the data-age half, answerable only with a
   measurement: no consumer may still read an `evaluator_version` stamp, so the stamps must have aged
   out of every retention window. One further thing unblocks the archive drop: `pipeline-registry`'s
   justification for exposing a prompt to `READ_ONLY_ADMIN` — which today cites the evaluator registry
   being open to that role — must be restated against the pipeline declaration, which this change
   already makes true. Ship the column drop without waiting for the archive question.
3. **Does `adas-mcp` need a compatibility window?** No: nothing in this repository calls
   `POST /v1/evaluators` outside its own tests, so its evaluator write tool is removed in the same
   release. Confirm no operator runbook outside the two repositories does.
4. **Should D9's relaxation extend to the other referential checks?** An author composing a declaration
   hits the same wall with `transform.outputs` (a column not yet added to the target) and with a `sql`
   expression (a source column not yet present) that D9 removes for placeholders. The asymmetry is
   defensible — both of those feed the catalog's drop/rename dependency guard, where an unvalidated
   reference is not merely inert but changes which columns are droppable — but it is an asymmetry an
   author will notice. Answer it with the UI's actual authoring flow rather than in the abstract.
5. ~~**Which contract version number does this release serve, and does ADAS ever serve two?**~~
   **Answered**: `RunnerContract.VERSION` goes 1 → 2, and the shipped mechanism is equality-only by
   deliberate design — its own note says "a comparison other than `==` is a design change, not a
   relaxation". Serving a *set* is therefore not a shape this release may quietly adopt; it would be its
   own change, and it is not needed while the window is scaled to zero.
