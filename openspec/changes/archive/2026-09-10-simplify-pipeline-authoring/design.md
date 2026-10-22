## Context

The full analysis — every count, every drift, and the reasoning behind each decision below — was
kept in a separate planning document. This document records the decisions and the alternatives
rejected; it does not restate the measurements.

Current state, in one paragraph. A pipeline and its evaluator declare the same facts several times
over with nothing cross-checking the copies: the output mapping (`column == var` on every
binding), the output var list and its identity `jsonata` (all but one), `input_vars` (read by nothing),
`distinct: false` (nearly every measure), `freshness` (one legal value), `sampling: 1`, and `priority`
(read by nobody). One field, such as `session_insights.risk_level`, can be declared seven times. The
copies drift three ways — enum domains disagree in order between an evaluator's schema and
its target column, `sentiment_score` has three type spellings, and because `response_schema` is
stored `jsonb` its authored property order was silently re-sorted by key length, so `risk_level` is
generated ninth of fifteen, ahead of every field it is instructed to derive itself from.

Constraints that shape everything below:

- `evaluator_version` is **immutable by contract** — V11's DDL says so — and the stored
  `response_schema` is what a live prompt is made of.
- `generation` is the Enrichment Runner's **only** change-detection token; the feed has no
  `ETag`/304, so a change the runner cannot see is a change that never happens.
- A `generation` bump on a **Class-1** pipeline discards `materialized_through_version`,
  `materialized_through_identity` and `drained_at`. Nothing is re-derived — the materialize cursor is
  caller-held, in the runner, and no bump moves it — but until the next materialize re-records a
  position the freshness clamp holds every consumer of that enrichment back *entirely*:
  consumers such as `sessions_rollup` and `session-insights-live` write zero rows per tick. A *disabled* Class-1
  pipeline never materializes at all, so a disable holds them there for as long as it lasts.
- The runner is a separate deployment on its own release schedule.

## Goals / Non-Goals

**Goals:**

- One authority per field: the target table's column where it can carry the fact, the evaluator's
  output where the column structurally cannot. Drift becomes unrepresentable rather than merely
  unlikely.
- A declaration a human can write in a `curl`, and a response that does not repeat its own defaults.
- Zero prompt delta and zero spend on deploy: no stored row rewritten, no re-registration, no
  re-drain.
- The runner ships **unchanged**, on its own schedule.

**Non-Goals:**

- Migrating existing evaluator versions to the new shape. They reach it on their next revision, under
  an author's eyes — and that revision is a deliberate prompt change, not a tidy-up.
- Fixing the revision-read regression (`GET /v1/pipelines/{name}/revision/{revision}`
  re-serializes rather than returning stored bytes). Recorded as a limitation.
- The runner's `/try` endpoint, the renamed knobs on the wire, and the UI. Separate changes.
- Multi-source `inputs`. Still refused; the rollup + Class-1 pass-through recipe answers it.

## Decisions

### 1. One endpoint per object, shrunken body — no second "simple" API

Required fields drop to those carrying information; optional knobs move behind one nested `advanced`
object. *Rejected:* a parallel "simple" endpoint, which doubles the surface and leaves the verbose
one to rot.

### 2. The **pipeline** composes the response schema, not the table

The pipeline already names both a `target` and an `evaluator`, and binding them is definitionally its
job. In-repo precedent: a `sql` evaluator's expressions are already validated at pipeline
registration, because "an evaluator alone has no source to resolve columns against". *Rejected:*
teaching the table about evaluators (couples the catalog to the registry), and teaching the evaluator
about targets (an evaluator has none at registration).

The cost, stated: an evaluator registration validates nothing structural the *first* time, because
composition needs a target. An evaluator commonly has no pipeline at all, so this is not
a corner case. Second and later registrations do validate — against every pipeline naming the
evaluator with `evaluator_version IS NULL` — which is decision 8's hook.

### 3. `sql` evaluators keep their own object, stripped to `outputs`

*Rejected:* moving the expressions onto the pipeline. `EvaluatorService` contains **zero**
`generation` references, so a new evaluator version bumps nothing, while
`PipelineRepository.discardMarkOfOtherGeneration` nulls the Class-1 position on *any* generation
change. Moving expressions to the pipeline would make each expression edit discard that position and
hold every consumer of `usage_client_identity` back entirely until the next materialize re-records
one. It also stamps per-row `evaluator_version` provenance, which a pipeline-side expression could
not.

### 4. No `evaluator_version` row is rewritten — not one, of any type

A stored `response_schema` keeps winning forever for the version that stores it; composition applies
only to versions registered after this change. Two shapes coexist in storage and one `??` resolves
them.

*Rejected:* migrating the rows. For an `llm` row it is not faithfully possible — the authored property
order was already destroyed by `jsonb`, so a rewrite must *choose* an order, and under
`response_format: {type: json_schema, strict: true}` order is generation order. For a `sql` row it is
mechanically trivial (`outputs = {var.name: var.sql}`) and still wrong: this change drops the
per-output `type`, `GET /v1/evaluators/{name}/versions/{v}` has no target and no source to
re-derive one from, and deriving it from the column would be wrong in both directions because
`EnrichmentTypeMapping.isCompatible` is not equality (numeric widening, `string` → `enum`). A migrated
row would answer `GET` with strictly less than the row it replaced, on a table whose DDL declares its
versions immutable.

One consequence to plan for rather than discover: **an in-use evaluator can be staged for free.** Register
the next version in the new shape while the pipeline stays pinned to the old one — nothing bumps,
nothing re-derives — then flip the pin when you want the re-derivation anyway. Because the
registration-time re-validation of decision 8 only fires on pipelines with `evaluator_version IS
NULL`, a staged version is accepted **unvalidated** and the 422 arrives at pin time. That is cost-free
(a patch validates before it bumps) but it means "register the new version" is not the checkpoint an
author assumes, which is why the preview has to carry that weight.

### 5. Option **B** for the output bindings: derive on write, keep storing

`output_bindings` stops being accepted on the wire and is instead computed by ADAS at registration and
patch time from `outputs` × target columns — and **still stored** in `pipeline.spec`.

This is the one open decision the plan left, and B is taken. `output_bindings` is load-bearing on three
ADAS paths that all fail **closed**: `EnrichWriteValidator:28` builds the write allow-list as
`{bindings} ∪ {grain} ∪ {provenance}` (empty ⇒ every runner row write 422s — an outage),
`MaterializeService.analyzeExpressions` throws on an empty map (every Class-1 pipeline stops
materializing), and `EnrichSpecValidator:463` becomes permanently unsatisfiable (every Class-1
registration and re-enable refused). Under B all three need **no change at all**,
`EnrichResolver.resolveForWrite` keeps the two-to-three-query saving it deliberately makes,
`EnrichDeclaration` stays a two-object view over `(pipeline, spec)`, and the rollback window never
closes.

*Rejected (option A):* resolving the list on read onto `ResolvedEnrichPipeline`. It is the purer
expression of "declared once, derived everywhere", but it is paid for on the write path — three
`EnrichDeclaration` construction sites that hold neither an evaluator nor a target definition today,
plus the hot-path lookups `resolveForWrite` exists to avoid — and it closes the rollback window on the
first enrich pipeline patch after deploy.

The cost of B: the stored copy can go stale, so decisions 7 and 8 must **re-derive and rewrite** it
rather than only bumping `generation`.

`EnrichSpecValidator:463`'s message becomes "the evaluator must declare at least one output that
resolves to a column on the target" either way — a better error than the one it replaces.

### 6. The knob renames reach storage too, behind a read alias

**Superseded.** This decision originally read "the renames never reach storage": `EnrichSpec` kept
`cadence` / `batch_scan_limit` / `batch_chunk` / `sampling` under explicit `@JsonProperty`, and a
`@JsonAlias` was *rejected* on the grounds that it would not make a code rollback safe — the alias
lives in the new code, so reverted code reading a row this build had rewritten would find those knobs
unset.

That reasoning is sound and no longer applies: **rollback compatibility is not a requirement**, and
this change ships with the Enrichment Runner in one maintenance window. In the only direction left —
new code reading old rows — `@JsonAlias` is the ordinary tool, so the four knobs are written under
their authoring names (`scan_every`, `rows_per_scan`, `rows_per_call`, `sample_fraction`) and read
under those or their former spellings. **No row is migrated**, `pipeline_aud` history stays truthful,
and one name per fact now holds at every layer including storage.

The two binding lists deliberately keep their stored names. `vars` is a map and `outputs` is a folded
list; `input_bindings` and `output_bindings` are neither, so renaming them to match the wire would
name a list after a shape it does not have.

`priority` and `freshness` stop being fields — and `priority` is now genuinely removed, along with
`EnrichDeclaration.priority()` and the `PipelinePriority` enum, both of which had zero callers. Both
spec types carry `@JsonIgnoreProperties(ignoreUnknown = true)` under a mapper with
`FAIL_ON_UNKNOWN_PROPERTIES` disabled (`JsonMapperConfiguration:33`), so a leftover member in a
stored row is ignored rather than fatal.

### 7. The catalog notifies the registry on an enrichment target's column change

`onTargetColumnsChanged(target)` alongside the existing
`PipelineGenerationBump.onVersionColumnRepointed`, fired from `CatalogSchemaWriter.applyPatch` for a
column add, drop or rename on an enrichment, bumping `generation` **and `updated_at`** (the feed
selects on `updated_at`, so a bumped generation the feed would never return is no signal at all)
inside the catalog write's transaction, `MANDATORY` propagation. Under option B it also re-derives and
rewrites the stored bindings.

**Scoped to `llm`.** On a Class-1 pipeline the bump is unnecessary — ADAS re-resolves the bindings and
re-analyzes every expression on *each* materialize call, deliberately not trusting the registration
gate — and expensive, per the constraint above. Without the scoping, "add a column" would cost
strictly more after this change than before it, inverting the point of removing the binding patch. A
metadata-only patch bumps nothing: prose now comes from the evaluator, and types and `enum_values` are
immutable already.

### 8. A removed field is a 400 naming it — one coordinated write release, no read coordination

*Rejected:* accepting the old spellings for one release and cross-checking them against the derived
value. Four reasons, all decisive. (a) It cannot deliver the compatibility it exists for: `freshness`
(every aggregate) and `priority` have no derived value to check and are refused outright, and a
declaration patch must carry the whole declaration for its kind — so a stale caller re-declaring any
existing aggregate breaks either way. (b) The assertion it would buy is empty: bindings are already uniformly
`column == var`, and the four renames have nothing to compare against. (c) It reintroduces the exact
failure `StrictRequestJsonConfiguration` was added to prevent — its javadoc names two real failures,
`member_select.order` stored as no ordering at all and a schema `PATCH` naming `columns` answering
`200` having changed nothing. (d) A one-release window only helps a client updated *during* it, and
with ADAS, adas-mcp and the UI shipping together that set is empty.

**Reads are coordinated by the deploy window, not by a default.** The original plan kept
`view=compiled` as the default so that reads needed no coordination at all; with the window, the
default flips to `view=source` immediately and the runner names `view=compiled` explicitly. The
projection itself is permanent either way (decisions 2 and 4).

What the coordinated release actually costs is **text, not types**: `adas_create_pipeline` and
`adas_patch_pipeline` take `body: dict[str, Any]`, so there is no typed client to break. What carries
the old names to a model is the tool **docstrings** and `src/adas_mcp/swagger/adas-openapi.json`
behind `adas_describe_schema`.

### 9. One vocabulary on the wire, and the runner ships in the same window

**Superseded, and this is the largest revision to the change.** The original decision was that the
compiled projection be **byte-identical** to what an Enrichment Runner received before the
declaration surface shrank — synthesizing `output_bindings` and `output_vars` rather than omitting
them, keeping `cadence` / `batch_scan_limit` / `batch_chunk` / `sampling` under their old spellings
indefinitely, and defaulting `view` to `compiled`. All of it existed for one reader: a runner
deployed before this change, reading a new ADAS during a rolling deploy.

It is withdrawn, because that reader is not allowed to exist. The deploy scales the runner to zero,
updates ADAS, updates the runner, and scales back up — so there is no window in which old runner code
reads a new feed, and the price byte-identity was paying is not owed. That price was a permanent
second vocabulary: every renamed fact keeping one name in the authoring surface and another on the
wire, forever, with `EnrichSpec` frozen to match.

What ships instead:

- The compiled projection carries **the authored vocabulary and nothing else** — `vars`,
  `outputs`, `advanced` — plus the derived members. `input_bindings`, `output_bindings`,
  `cadence`, `batch_scan_limit`, `batch_chunk` and `sampling` are gone from every response.
- `outputs` **folds** the old `output_bindings` and the evaluator's `output_vars` into one list,
  `{name, column, jsonata?}`. A consumer needed both and had to join them by name; the join has one
  correct answer and one place that holds both halves. The runner did it per row, into a throwaway
  map, for a list whose transform is the identity on nearly every output.
- `name` and `column` stay **two members**. `name` is the response property (the column's exposed
  name, which the composed schema declares); `column` is the physical `source_name` the write path
  stores. Usually equal in practice, equal by construction on none.
- The inlined evaluator omits `input_vars` and `output_vars`. `GET /v1/evaluators/...` does not: a
  stored version is immutable by contract and its read still answers with exactly what was written.
- The default `view` flips to **`source`**, and resolution is skipped for it — so `?kind=enrich`
  alone is a cheap operator listing and `?kind=enrich&view=compiled` is the runner's feed.

The claim byte-identity was there to make checkable is now made a different way, and a stronger one:
the projections are asserted to differ by **exactly the derived members**, as a set difference over
the two responses' own property names, and every removed spelling is asserted absent by name. A
member added to one projection and forgotten in the other fails that test rather than a consumer.

What does **not** change: the empty-mapping guarantee. A pipeline whose derived mapping resolves to
no columns is still refused on the feed, and that is still the one failure mode of this change that
is silent rather than loud — a consumer handed an empty mapping writes grain plus provenance with
every label NULL over a paid-for label and stamps the work done. The runner gains the matching
refusal in `unsupportedReason`, and that is the one runner change worth shipping ahead of the window.

### 10. Template validation is trigger-aware

The obvious rule — every `{{...}}` is a `vars` key and every `vars` key appears as a placeholder — is
**wrong for a group pipeline and would brick every existing one.** `GroupInputAssembler` hands
`EvaluatorInvoker.derive` a five-entry map, so at group grain each `vars` key becomes a field of each
member object inside `{{members}}` and never a top-level placeholder; a typical group evaluator
mentions none of its vars in its template. And because `PipelineService.patch` re-validates the
**stored** declaration before arming, the naive rule would refuse a routine disable/enable of a
group pipeline such as `session-insights-live`. The five built-ins (`members`, `members_truncated`, `members_included`,
`members_omitted`, `members_notice`) are ADAS's own wire contract, asserted as literals here and
mirrored by the runner's own test.

### 11. Everything else, briefly

- **`inputs` stays, optional and meaningful.** Enrich pipelines commonly read a table
  that is **not** their target's parent, so dropping it would silently repoint them. `view=source`
  echoes the *stored* value (absent when not declared) so a GET → POST round trip stops laundering a
  derived source into a declared one, and a declaration patch omitting a declared `inputs` is a 422
  rather than a silent demotion.
- **`rate_rpm` keeps its name.** Every rename buys a permanent second vocabulary (the compiled wire
  keeps the old spelling for the runner indefinitely); that is a fair price for `cadence`,
  `batch_scan_limit`, `batch_chunk` and `sampling`, each ambiguous or misleading, and not for
  `rate_rpm`, which is neither.
- **Aggregate `group_by` is normalised on write, not derived on read** — expanded into the stored spec
  once at registration. `validateGroupKeys` refuses an empty `group_by`, `AggregateCompiler` reads
  `spec.getGroupBy()` directly, and an `ordering_key` column can neither be dropped nor renamed, so a
  one-time expansion is stable and leaves `validateTargetBinding`'s equality check meaningful for a
  declaration that *did* state its keys.
- **Measure `column` defaults are fn-aware.** Many measures are `count` with no column;
  `count(hop_count)` names nothing and `count(col)` skips NULLs where `count(*)` does not.
- **The `length(<key>) > 0` filter is deliberately kept**, even though most rollups write the same
  predicate. Auto-injecting a predicate into a rollup's `WHERE` is how you get silently wrong numbers.
- **`MeasureRequest.distinct` becomes `Boolean`** with `null ≡ false` inbound and `false ⇒ null`
  outbound, since a primitive cannot be omitted. That collides with its `@JsonSetter(nulls =
  Nulls.AS_EMPTY)` and with MapStruct's generated `toMeasureDtos`; splitting the leaf records into
  request/response pairs is the alternative. Either way it is a day of DTO work, not a free consequence
  of an existing seam — `toResolvedListDto` and `toListDto` are *not* two projections, they funnel into
  one `toDto`.
- **Provenance injection reaches new tables only.** `TableSchemaCreationService.defineSchema` calls
  `requireNotMaterialized`, so `POST /{name}/schema` is 409 on every ACTIVE table; there is no
  "existing tables re-post cleanly", and an existing table such as `usage_client_identity` keeps its zero provenance columns.

## Risks / Trade-offs

- **[An empty derived binding list erases stored labels silently]** → The one failure mode of this change
  that is not an outage. Mitigated three ways, the first not optional: disable any enabled group pipeline
  for the upgrade window and re-enable only after the compiled feed has been diffed against a
  pre-upgrade capture; refuse to serve an `llm` pipeline whose derived mapping is empty (log and omit);
  and add the `llm` case to the runner's `unsupportedReason` in the follow-up change. The byte-identity
  fixture test asserts the binding **count** per pipeline, not only a diff, so a fixture regressing to
  `[]` on both sides still fails.
- **[Trigger-aware template validation refuses an existing declaration]** → The highest-risk item
  (`EnrichSpecValidator` is 946 lines and the rule is easy to get wrong in exactly this direction).
  Land it alone, after everything else, with the "every existing declaration still passes the new
  validator" assertion written **first**.
- **[A Class-1 pipeline gets bumped by accident]** → `PipelineService.patch` bumps `generation` on any
  accepted mutation, **including a bare enable flip**. Do not PATCH and do not disable/enable
  a Class-1 pipeline (e.g. `usage-client-identity-live`) during the upgrade window. Nothing in this change
  requires it; the temptation is to "move the existing declarations to the new shape" as a tidy-up.
- **[Composing enum order from the column couples a dashboard concern to the prompt]** → Accepted.
  Membership is the half that carries correctness (a ClickHouse enum column refuses an out-of-domain
  value at insert, so composition makes that failure unrepresentable); order carries none. An order
  override on the evaluator would rebuild the very drift this removes.
- **[The drift is not fixed on deploy]** → By construction, per decision 4. It is fixed on the next
  registration of an in-use `llm` evaluator, which is a real prompt change to spot-check in the
  preview, not a migration side effect. And whether a given provider *generates* in JSON-Schema
  property order is its own behaviour, not verifiable from either repo — treat the derived-field
  quality improvement as a hypothesis, not a measured win.
- **[No per-row record of which target schema labelled a row]** → Residual and accepted. After a
  column drop-and-re-add, rows labelled either side were computed under different composed schemas and
  `evaluator_version` does not move for a target-schema change. Recorded in the capability spec.
- **[Name-matching couples output names to column names]** → Fine today (an evaluator typically serves one
  pipeline, and bindings are already identical), but a second target must reuse the same column names.
  The 422 says so rather than just "no such column".
- **[A revision read is not truthful]** → `pipeline_aud.spec` is read through the same codec, so a
  historical revision comes back re-serialized in the new shape; `priority` and `freshness` go silently
  absent. Tolerant read stops it crashing; it does not make it truthful. Out of scope, spec'd as a
  limitation, and the reason spec-codec tolerance is **permanent** rather than transitional.

## Migration Plan

**V39 is the whole migration:** `ALTER TABLE evaluator_version ADD COLUMN outputs JSONB`. Not one row
is rewritten, so `generation`, `materialized_*`, `drained_at`, `history_rederived_at` and `enabled`
are untouched by construction rather than by discipline, and the rendered prompt is **bit-identical** —
not "identical except enum order". No new credential and no new config key.

Rollback: a column add is reversed by a column drop and no data moves, so there is no rollback ritual.
Under option B the stored spec is byte-for-byte the shape the old code wrote, so a **code** rollback is
unconditional at any time too. (Under the rejected option A the window would have closed on the first
enrich pipeline patch after deploy.)

**Verify locally first, against real declarations.** Seed a deployment's pipelines and **every**
`evaluator_version` row (including evaluators with no pipeline and deliberately odd probe shapes)
into local Postgres **exactly as stored**,
then assert: the compiled projection is byte-identical for every pipeline including synthesized
bindings; the evaluator payload the feed serves is byte-identical for every in-use `llm` evaluator,
property order included; each spec re-serializes with its **stored** member names unchanged; every
existing declaration still passes the new validator; the built-in list equals the runner's five constants;
`view=source` echoes the stored `inputs`; the collapsed type check agrees per existing output var;
and `getOutputVars()` has no remaining direct callers. Then the full local end-to-end of one pipeline
per kind through the new bodies, with the runner running locally.

**Then against a running deployment, in this order.** Capture `adas_list_pipelines` first — the
baseline may *not* be clean; pipelines can already carry `rebuild_required`. Disable any enabled group
pipeline for the window (safe: a
generation bump reschedules the runner's recurring instance and its group state is not
generation-scoped). Deploy. Compare `generation` on every pipeline, `materialized_through_version` on every
Class-1 pipeline, `rebuild_required` against the captured baseline exactly, and every enrich
pipeline's resolved read source. Diff the compiled feed against the pre-deploy capture. Only then
re-enable the group pipeline — which is also the real exercise of the template rule and of
`PipelineService.patch`'s re-validation.

adas-mcp ships in the same release: shrunken bodies in the docstrings, the new `view` parameter, the
changed `adas_preview_pipeline` contract, and a regenerated OpenAPI snapshot (compare it structurally —
regeneration churns ~1400 lines of hash-order noise). The runner and the UI follow on their own
schedules.

## Open Questions

None blocking. The one decision the plan left open — option A versus option B for the output
bindings — is settled as **B** above, with A's shape recorded in case it is ever revisited.
