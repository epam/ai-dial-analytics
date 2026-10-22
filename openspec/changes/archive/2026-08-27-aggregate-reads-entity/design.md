## Context

The read path resolves a source's enrichments through `QueryEntityModel`: a flat field set (source
columns plus each ACTIVE enrichment's exposed columns, qualified `"<enrichment>.<column>"`) and a list
of `EnrichmentJoin` records carrying the grain-join condition. `StructuredQueryBuilder` joins an
enrichment only when a clause references one of its exposed names.

The pipeline path never picked this up. Two things stop it, and only one of them is the one the
motivating note assumed.

**The validator resolves against a table, not an entity.** `AggregateSpecValidator` builds a `Columns`
record — `Map<String, ColumnMappingEntity>` for exactly one table — and tests membership against it for
group keys and measure columns. Every downstream check reads through the same map: type, nullability,
sensitivity, array element type, bucketability, target binding. Enrichment columns are not in it.

**The compiler holds a `QueryEntityModel` whose enrichment list is empty by construction.** The type is
threaded through `AggregateCompiler`, which is what made this look like a small change. But the pipeline
path obtains its model from `ScanSourceMetadata.sourceModel`, which rebuilds the model with
`List.of()` for enrichments and documents why: a rule-scoped read projects source columns only, so
carrying the joins would add joins nothing selects from. That accessor is shared with the Class-1 scan,
group assembly and materialize paths. The seam is deliberately closed, not merely unused.

Two further facts shape the design and are easy to miss from the API surface:

- **A refresh composes several independent statements, not one.** `AggregateRefreshService.probe`
  composes the window probe; `AggregateCompiler.compileRefresh` composes the recompute select, the
  dirty-key sub-select (`dirtyKeys`) and — for a bucketed declaration — two scalar bound sub-queries
  (`dirtyBucketBound`, min and max); `compilePreview` composes the sample. Each does
  `addFrom(model.table())` on its own. They are not correlated sub-queries, and bindings render as
  two-part physical references (`DSL.name(owningTable, sourceName)`), so an enrichment column standing
  in one of them without its join is an unresolved identifier at execution.
- **The scheduler already runs elevated, and the justification is a validator rule.**
  `SystemIdentity.runAsSystem` grants `FULL_ADMIN` to each refresh because `CatalogQueryService` filters
  the model by the acting identity and a scheduler thread has no caller. Its javadoc names what closes
  the resulting laundering risk: `AggregateSpecValidator` requires a target column derived from a
  sensitive input column to be itself sensitive. That rule reads `Columns.isSensitive` — the per-table
  map above.

## Goals / Non-Goals

**Goals:**

- Resolve every pipeline field reference against the input entity's flat field set, using the qualified
  flat name the read path and discovery already publish.
- Emit the grain join in every statement whose own references need it, through the read path's
  mechanism rather than a second implementation.
- Keep an enrichment-fed rollup from writing a value derived from an enrichment cell that had not been
  filled yet, and make it impossible to declare one that launders a sensitive column or stores a NULL
  as a type default.
- Leave a pipeline that references no enrichment compiling byte-identical SQL.

**Non-Goals:**

- Joins in general. One source plus its own ACTIVE enrichments, on the declared grain key, cardinality
  0..1 — the closed shape the read path already allows. No second input, no arbitrary table join.
- A new naming scheme. The qualified flat name exists and is what discovery publishes.
- Any change to the one-function-over-one-column measure contract. A multi-argument aggregate stays
  refused on arity; `arg_max` is in the catalog and is refused as a measure for that reason, unchanged.
- Lineage and delete safety for the new dependency edge. Deleting a referenced enrichment, or dropping
  one of its columns, should be refused the way deleting a pipeline's input table already is — but that
  failure is a loud broken refresh, not a wrong number, so it is a follow-up change rather than part of
  the correctness set.
- A mechanism against the join's read cost. This change measures and documents it (see Risks).

## Decisions

### D-1: A new entity-model accessor, not a change to `sourceModel`

`ScanSourceMetadata` gains an accessor that returns the catalog model with its enrichments intact;
`sourceModel` keeps stripping them and keeps its contract. The pipeline path switches to the new one.

*Alternative rejected — change `sourceModel` to keep the enrichments.* It is shared with the Class-1
scan, group-row assembly and materialize paths. Those project source columns only, so the joins would be
inert there — until a rule's governed SQL happened to name a column that collides with an enrichment's
exposed name, at which point the meaning of an existing stored rule changes silently. A one-line
"simplification" that alters three unrelated paths' schema view is not worth the shared method.

*Alternative rejected — have the pipeline path call `CatalogQueryService.findModel` directly.* It would
work, but it puts the pipeline path on a different accessor from the scan path for no reason and drops
the `QueryValidationException` framing `ScanSourceMetadata` already applies.

### D-2: `Columns` becomes an entity-wide view keyed by flat name

Rather than special-casing enrichment lookups at the two membership tests, `Columns` is rebuilt to hold
every field of the entity — source columns under their bare exposed name, enrichment columns under their
qualified flat name — each entry carrying its owning table alongside the `ColumnMappingEntity` it
already holds.

This is the decision that keeps the change honest. Every rule the validator enforces reads through
`Columns`: `typeOf` (bucketability, target-column type match), `isNullable` (the measure and group-key
null rules), `isSensitive` (the anti-laundering rule), `elementTypeOf` (array measures). Patching only
`contains` would let an enrichment column past the membership test and then silently answer `null` /
`false` to every one of those — which is not a missing feature but an inverted default: a sensitive
enrichment column would read as non-sensitive, and a nullable one as non-nullable. The two rules whose
whole purpose is to stop a plausible-looking wrong number would be the first to fail.

The owning table is carried because the refusal messages must name the enrichment, and because
`SensitiveProjectionGuard.enforce` is per-table and needs to be called per owning table.

*Alternative rejected — a parallel `EnrichmentColumns` lookup consulted on miss.* Two maps means two
places to remember, and the failure mode of forgetting one is silence.

### D-3: Enrichment-sourced ⇒ nullable, decided at the view, not at each rule

The entity view reports an enrichment-sourced column as nullable regardless of its declared flag, so the
measure-nullability rule and the group-key rule inherit the behaviour without either being taught about
enrichments. The outer join is what makes it true, and it is true for every consumer of the flag.

**Corrected — the premise held only for a column already declared nullable, and D-3a is what
makes the rest of it sound.** "The outer join is what makes it true" is false under ClickHouse's default
`join_use_nulls=0`, which renders an unmatched non-nullable right-hand column as its *type default* rather
than as NULL. D-3 is kept as written because it is the right shape for every column the capability now
admits, but it is no longer load-bearing on its own: D-3a refuses the declarations where it would have been
wrong, so the view's answer and the engine's behaviour agree for everything that can be declared.

*Alternative rejected — an `isEnrichmentSourced` predicate each rule checks alongside `isNullable`.*
Same answer, but it can be forgotten at a third call site, and the rules would then disagree.

### D-3a: A non-nullable enrichment column is refused in value-producing positions

A `group_by` column, a bucketed column and a `measures[].column` may name an enrichment column only where
that column is declared nullable (422 otherwise, naming the column and the enrichment). A `filter` and a
`measures[].where` may name one regardless. `array` and `map` are exempt.

**This is a correction to D-3, not an addition to it**, and it was found by review rather than by test.
ClickHouse's `join_use_nulls` defaults to `0`; ADAS sets `final=1`, `max_execution_time`,
`max_memory_usage` and `max_bytes_before_external_group_by`, and never this. So an unmatched LEFT JOIN
yields `''`, `0`, `false` or the epoch for a non-nullable right-hand column. Every mitigation D-3 was
written to enable then misses the exact case it was written for: the mandated `filter` excludes nothing when
spelled `IS NOT NULL`, the mandated nullable target receives a zero, `avg`/`min` absorb one default per
unreached row, and a bucketed key's pruning bound is dragged to 1970. The codebase already knew this — it is
written down in `FlightSqlIntegrationTest`'s enrichment-null test — but the pipeline surface was designed as
though it did not.

*Alternative rejected — set `join_use_nulls=1` on the pipeline statements.* It would make D-3's premise
literally true and is the more elegant answer. Rejected on scope and on divergence: setting it only on the
pipeline path makes the pipeline and the read path disagree about what an unmatched non-nullable column
reads as, which is precisely the drift this change's own "the two paths agree" requirement exists to
prevent; setting it globally changes shipped read-path behaviour for every existing query over an
enrichment, which is a different change with its own compatibility question. Worth revisiting as a
deliberate platform-wide decision.

*Alternative rejected — document the default-value semantics and let declarers handle it.* The whole point
of the surrounding rules is that a declarer cannot be expected to reason about which engine default their
rollup silently absorbs. A rule the operator has to remember is the failure mode, not the fix.

*Alternative rejected — refuse predicates too.* `"<enrichment>.<column>" != ''` is the correct way to
exclude unreached rows for a column that cannot be nullable, so refusing predicates would remove the one
sound workaround along with the unsound declarations. The asymmetry matches the one
`requireSensitivityPropagated` already draws: predicates select rows, derivations copy values.

### D-3b: An enrichment group key must declare `as`

Refused at declaration (422) rather than left to fail against the target.

A key's output name defaults to the column it reads, and that output name *is* the target column it binds to
and a member of the target's `ordering_key`. A qualified flat name carries a `.`; `Identifiers` admits none
in a column name. So an unaliased enrichment key cannot bind to any target that could exist — it already
failed, but against the target's ordering key, blaming the half the declarer wrote correctly.

### D-4: Sensitivity crossing the join is a prerequisite, not a policy addition

The anti-laundering rule extends to enrichment columns, and the refusal (422) names both the enrichment
column and the target column.

This is not a new judgement call to be weighed. `SystemIdentity`'s elevation to `FULL_ADMIN` is
justified in its own javadoc by that rule being total; the moment a pipeline can read an enrichment, an
unmodified rule makes the justification false and the elevation becomes a laundering path by design.
Shipping the join without this is not "a governance gap to follow up" — it is a security regression
introduced by the change that introduces the join.

The entitlement half (403 for a non-entitled caller referencing a sensitive enrichment column) comes
along for free once `SensitiveProjectionGuard.enforce` is called per owning table, because the model is
already role-filtered: a non-entitled caller never sees the column in `bindings` at all.

*Alternative rejected — propagate the `sensitive` flag onto the target column automatically.* The target
column's sensitivity is a declaration its owner should make deliberately. Silently marking a column
sensitive changes who can read the whole rollup, as a side effect of editing a pipeline.

### D-5: Join emission is the read path's method, extracted

`StructuredQueryBuilder.addReferencedJoins(select, model, referenced)` moves to a shared helper and is
called from both paths. It is already exactly the right shape — eight lines, no builder state.

**It is called once per statement, with that statement's own referenced-field set**, not once per
compile. The set differs by statement:

| reference declared in | probe | recompute | bucket bounds | dirty keys | preview |
|---|---|---|---|---|---|
| `filter` | ✔ | ✔ | ✔ | ✔ | ✔ |
| `group_by[].column` | | ✔ | | ✔ | ✔ |
| `group_by[].trunc.col` | | ✔ | ✔ | ✔ | ✔ |
| `measures[].column` | | ✔ | | | ✔ |
| `measures[].where` | | ✔ | | | ✔ |

Deriving the set per statement rather than joining everything everywhere matters: joining an enrichment
into the probe because a *measure* named it would add a full enrichment read to the cheapest statement
in the refresh, on every tick, for nothing.

*Alternative rejected — copy the eight lines into the compiler.* Two implementations of "when does a
join fire" is exactly the drift the shared query-function catalog exists to prevent elsewhere.

### D-6: The freshness clamp, and why the induction argument is the point

**MOVED to `aggregate-enrichment-freshness` — not dropped.** This decision resolved the clamp
against "the writer cursor of every referenced enrichment", and implementation found no such cursor exists
in ADAS (below). Exploring the runner then found the premise, not the goal, was wrong: every enrichment
feeding a rollup today is Class-1, and a Class-1 materialize is synchronous *inside ADAS*, so ADAS already
holds the position it wrote through and never needed to be told. The clamp is therefore an ADAS-local
change with no runner contract, and it is specified and built there — clamping to a **materialized
position** rather than to a writer cursor. The requirement and its state clause have been removed from this
change's delta accordingly, so archiving this change cannot put an unbuilt requirement into
`openspec/specs/aggregate-pipeline/`.

The reasoning below is kept because the induction argument is what makes an outer join over an eventually
written table safe to aggregate from at all, and the successor decision inherits it unchanged.

D-6 says "the writer cursor of every referenced enrichment is resolved". ADAS stores no such cursor.
`ScanService.scan(ruleId, cursor, …)` and `MaterializeService.materialize(…)` both take the cursor **from
the caller** — it is opaque, ADAS-issued and ADAS-verified, but it is persisted in the *runner's* database
(`analytics-enrichment-runner`, `RuleCursorEntity` / table `rule_cursor`), not in ADAS's. `RuleEntity` has
no cursor field and there is no rule-state table. So there is nothing to resolve at refresh time.

Two ways to close it were considered and neither is in this change's scope:

- **Record the cursor ADAS is already handed.** The runner presents its stored cursor on every scan call,
  and ADAS decodes it to `(version, identity)` already, so recording a monotonic maximum on the rule row
  is a migration and about ten lines. It is **unsound for this purpose**: `RuleCursorEntity`'s own
  contract is that the cursor is "advanced atomically with chunk enqueue by the scan transaction" — it
  marks what has been *enqueued for evaluation*, not what has been *written back*. Clamping to it would
  admit exactly the rows whose enrichment write is still in flight, which is the case the clamp exists for.
  A sound version needs the runner to report a distinct write-back-completed position, which is a
  cross-service API change.
- **Derive the position from the data.** Unavailable: coverage is rule-defined (`filter_sql`, `sampling`,
  `ready_when`), so "every source row up to P that this enrichment will ever cover has its row" cannot be
  computed without replaying the rule's own membership. A `max(source.version)` over covered rows is a
  maximum, not a watermark — the same guess as a configured safety lag, which this decision rejects, and
  it costs a join over both tables on every tick.

The rest of the correctness set does not depend on this and is implemented. What ships without it is a
join that is governed, correct in what it computes, and **not** protected against reading an enrichment
cell that had not been filled yet. That is a real gap, not a cosmetic one, and the requirement stays in
the delta spec unimplemented until it is decided — see Open Questions Q-4.

Before a refresh, the writer cursor of every referenced enrichment is resolved. If the probed window
would end past the position an enrichment has reached, the window end is clamped to it. The refresh
proceeds over the clamped window; it is not skipped.

The clamp is not a heuristic margin, and stating why is the reason it is acceptable. Its correctness is
inductive: *if every window ever closed at a position its referenced enrichments had already reached,
then every source row ever aggregated had its enrichment row present when it was read.* The invariant
maintained is not "the enrichment is fresh now" but "the enrichment was never behind at any point that
was read", and the clamp is what carries it from one window to the next. That is what makes an outer
join over an eventually-written table safe to aggregate from at all.

Cost: a rollup is only as fresh as its slowest referenced enrichment. That is the honest trade and it is
surfaced in state (D-7) rather than absorbed.

*Alternative rejected — a configured safety lag.* Cheap, and a guess. A backfill invalidates the guess
precisely when the amount of wrong data is largest.

*Alternative rejected — dirty-marking from the enrichment side.* The most correct answer and the right
long-term shape if enrichment-fed rollups become common: an enrichment write marks the affected group,
and the scheduler revisits it. It needs a second dirty-marking path into the pipeline scheduler, which
is a larger change than this whole delta, and — unlike the clamp — it also solves the mutation case in
D-8. Deferred, not dismissed.

*Alternative rejected — accept and document.* Defensible only if enrichment-fed rollups were always
rebuilt from scratch, which defeats the incremental design.

### D-7: The clamp is surfaced in pipeline state

A clamped refresh reports that it was clamped and which enrichment bounded it. Without it, a pipeline
permanently behind because a rule is permanently lagging looks identical to a healthy one with little
input: cursor advancing, no error, `lag_seconds` explained away by the input. The failure this change
introduces is a *quiet* one, so it needs a loud indicator.

### D-8: The mutation gap is specified as an operator obligation, not built

The clamp guards late *arrival*. It does nothing for a value that *changes* — an enrichment rule edited,
or a backfill re-deriving history. A source row whose enrichment value moves from `A` to `B` does not
move its own cursor, so no window revisits it; an upsert-by-key target can replace a key's row but
cannot delete one whose key no longer occurs, so the row for `A` persists and `B` never appears.

Nothing in the system can detect this: the enrichment write is an ordinary write, and the pipeline has
no way to know a value it already read has changed. So the change states the obligation as a
requirement rather than leaving it as folklore.

**It builds nothing to satisfy it.** The rebuild is already expressible with the operations the
capability has. A pipeline is stored with no cursor and `DELETE /v1/pipelines/{name}` leaves the
target's rows intact, so delete-then-re-register *is* a cursor reset. Where the re-run changed values
that were already aggregated — the only case that leaves an orphaned key — deleting and recreating the
target removes it, and takes the pipeline with it by cascade. Two rebuild shapes, both existing API,
zero new code.

*Alternative rejected — a `POST /v1/pipelines/{name}/rebuild` endpoint.* It is a wrapper over calls that
already exist, for an operation that is rare, deliberate and `FULL_ADMIN`-only. Its one real advantage
is that it could preserve the target's grants and description across the orphan-clearing rebuild, which
recreating the table does not. That is a reason to add it *if* someone hits the problem, not before —
and the moment it exists it is a second write path into pipeline state that has to stay correct.

*Alternative rejected — detect it by versioning enrichment rows and re-dirtying on change.* This is D-6's
rejected dirty-marking alternative wearing a different hat, and it should be decided once, for both,
when the second enrichment-fed rollup makes the case.

*Alternative rejected — document it in design.md only.* An accepted risk with no requirement behind it
is a silent-wrong-number path with nothing on the operator's side of the contract.

## Risks / Trade-offs

**[The join reintroduces the exact cost class `bucketBound` exists to prevent] → MEASURED against a
populated deployment. Proceed, with the guidance aimed at bucketed rollups rather than at this one.**

Measured (`dial_usage_log` + `usage_client_identity`):

| | |
|---|---|
| source rows | millions, spanning weeks |
| enrichment rows | a fraction of the source, spanning a shorter, more recent range |
| coverage | a minority of source rows, and the enrichment is at **event grain** — 1:1 with the source, not a dimension |
| growth | of the same order per day as the source's |
| precedent rollups | `conversations_rollup`, `turns_rollup`: unbucketed, `cron */15` |

The finding inverts the prediction below, for one reason that was not obvious from the code: **the
motivating rollup's grain has no time bucket, so its input is already fully scanned on every tick.**
`sessions` is keyed on `client_session_id` alone, exactly as `conversations_rollup` is keyed on
`chat_id` and `turns_rollup` on `trace_id`. That full scan is the cost
`2026-08-05-document-unbucketed-grain-cost` already measured, documented and accepted.

So for this rollup the join's marginal cost is a hash build over the enrichment on top of a full source
scan that was happening anyway — a fraction more rows touched, 96 times a day, not 1440. And the ratio
cannot diverge: the enrichment is at event grain, so its row count is bounded above by the source's and
coverage approaches 1:1, which asymptotically **at most doubles** rows read. (Note the bound comes from the
grain, not from the growth rates — an enrichment can briefly grow faster than its source while it catches
up on backfill, and reasoning from "it grows at the same rate" would have been reasoning from a number that
says it grows faster.) That is an acceptable trade for the capability, and
it is not the runaway the analysis below predicted.

**The warning belongs somewhere else than expected.** The dangerous shape is a *bucketed* rollup, where
`bucketBound` prunes the source to a few days but prunes the enrichment not at all — there the join
becomes the dominant per-tick cost and grows without bound while the source side stays flat. That
inverts the existing guidance, which tells a declarer that a time bucket makes a rollup cheap. It does,
on the input side only. Task 7.1's guidance must say so; a deployment whose rollups are all unbucketed is
not exposed to it.

**Not measured, and honestly out of reach here:** bytes read and wall-clock per statement.
The SQL endpoint rejected joins at the time, so the five statements could not be run through it, and it
returns no query statistics. The row counts above are what decides the question and they decide it; if
someone wants the byte-level picture, it needs direct ClickHouse access and `EXPLAIN` / `send_logs_level`.

The original analysis, kept because its mechanism is still correct and still governs the bucketed case:

This is the largest open risk and it is not visible from the API. A ClickHouse hash join reads its
right-hand table in full to build the hash table and does not push the left side's keys down into it. So
an enrichment-joined refresh reads the *whole* enrichment table on every tick, at every statement
carrying the join.

Read `AggregateCompiler.bucketBound`'s own rationale: it exists because, restricted only by a tuple `IN`
over a sub-select, "a rollup refreshed every few minutes would full-scan its input every few minutes."
That fight was fought and won with a pruning bound on the raw source column. The join gives it back on
the other side.

The recompute half cannot be bounded against it by construction. It must see every row of every dirty
key, so the grain-key set it needs from the enrichment is not bounded by the window — unlike the
dirty-key select's, which could in principle be pushed down. There is no tuning that changes this; it is
a property of the "recompute every affected group key in full" contract meeting an outer join.

Mitigation is documentation, not mechanism — the measurement above cleared the bar for the unbucketed
shape this capability actually uses, and the precedent is `2026-08-05-document-unbucketed-grain-cost`,
which handled a structurally identical finding the same way. Should a bucketed enrichment-fed rollup
ever be declared and prove too expensive, the alternatives are promoting the enrichment's columns into
the source at ingest, or materializing the enrichment into a source table.

**[A rollup is only as fresh as its slowest enrichment] → stated in the spec, surfaced in state.** The
honest cost of D-6. D-7 keeps it visible rather than absorbed. With D-6 unbuilt this is worse than
"less fresh": see the requirement's implementation note.

**[The join is the first unbudgeted hash build in the refresh path] → documented, deliberately not
capped.** A refresh is unbudgeted by design — `AGGREGATE_PREVIEW_*` bounds the dry-run sample only, and
`AggregatePreviewBudgetIntegrationTest.refreshIsNotSubjectToThePreviewBudget` pins that as intended: a
ceiling that killed a background write would stall the rollup rather than protect anyone. The join is
nonetheless the first thing a refresh does that builds a hash table over a whole second table, so it is the
first thing that can consume server memory with nothing to stop it, and a declaration can clear the
2 GB-budgeted preview and still be the heaviest statement on the box overnight. No cap is added here:
introducing one would change the failure mode of every existing pipeline, enrichment-fed or not, on no
measurement. The asymmetry is documented in the cost guidance instead.

**[The entity view widens the blast radius inside an 850-line validator] → the view is the single
chokepoint, and the existing rules are re-used unmodified.** D-2 and D-3 are chosen so that the
nullability and sensitivity rules need no enrichment-specific branch; if a rule needs one, that is a
signal the view is under-specified rather than a reason to add the branch.

**[Two paths could still drift on join semantics] → D-5 extracts rather than copies.** The remaining
drift surface is the per-statement referenced-field derivation, which is why a test asserts the pipeline
path and the query path report the same value for the same enrichment column over the same rows.

**[No cycle detection exists] → out of scope, recorded.** The motivating note asked whether the DAG
cycle-check covers the new edge. There is no cycle check: the only structural guards are `input != target`
and one-pipeline-per-target, so a two-pipeline cycle is already constructible today, before this change.
The new edge makes the graph harder to reason about but does not create the gap. Recorded here so the
follow-up lineage change can decide it deliberately.

## Migration Plan

No data migration and no schema migration on the catalog for the correctness set; the referenced-enrichment
set is derived from the stored spec on demand rather than persisted (the refresh already re-validates the
full spec on every run, so the parse is not a new cost, and a persisted set is one more thing that can
drift from the declaration it summarizes).

Deployment is additive: declarations refused today start being accepted, and existing pipelines that
reference no enrichment compile identical SQL. Rollback is a revert — no stored declaration written under
the new code becomes unreadable under the old, but a pipeline declared against an enrichment column will
fail validation after a rollback, which is the correct behaviour rather than a data hazard.

**Operational:** the writer credential holds `SELECT` on scan/input sources; an enrichment-joined refresh
also reads the enrichment table, so `SELECT` must be granted on enrichment tables a pipeline references.
This is a grant to provision, not a config key. `docs/configuration.md` gains the cost guidance from the
Risks section above; no new property is introduced.

## Open Questions

- **Q-1 — resolved (task 1).** It clears the bar, but not for the reason the question
  assumed. `usage_client_identity` is **not** roughly one row per conversation: it is at event grain,
  1:1 with the source. What makes the cost acceptable is that the motivating rollup's grain carries no
  time bucket, so its input is already fully scanned every tick and the join's marginal cost is a fraction
  of a scan the capability already documents. The "ten times that size" worry therefore lands on the
  *bucketed* shape instead, which is where the guidance was aimed. See Risks.
- **Q-2 — resolved (D-8).** Recreating the target removes the superseded rows, and no new endpoint is
  added. Reopen only if preserving a target's grants and description across an orphan-clearing rebuild
  turns out to matter in practice.
- **Q-4 — where does an enrichment's written-through position come from?** Raised by implementation
  (see D-6). ADAS holds no enrichment cursor and cannot derive one soundly, so the clamp cannot be built
  here. Two ways forward: split the clamp and its spec requirement into the follow-up change alongside
  lineage/delete safety, shipping the join with the freshness gap stated; or extend the runner contract
  with a written-through position ADAS persists, which is a cross-service change larger than the rest of
  this delta. **Not decided.**
- **Q-3 — should the clamp be per-enrichment or per-refresh?** Specified as per-refresh (clamp to the
  slowest of all referenced enrichments), which is simplest and safest. A per-enrichment clamp is
  unsound: different windows for different joins in the same statement do not compose.
