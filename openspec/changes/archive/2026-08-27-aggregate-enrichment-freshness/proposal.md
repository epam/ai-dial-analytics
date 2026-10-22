## Why

`aggregate-reads-entity` let an aggregate pipeline read its input's enrichment columns and emit the grain
join. It did **not** ship the freshness half: an enrichment row is written later than the source row it
derives from, so a refresh can aggregate a source row whose enrichment cell is still NULL — and nothing
ever corrects it, because that source row does not move and no later window revisits it. Where the
enrichment feeds a group *key*, the row is not mis-measured but absent.

That change's design resolved the clamp against "the writer cursor of every referenced enrichment", and
implementation found no such cursor exists: the scan cursor is ADAS-issued but persisted by the
*enrichment runner*, and it advances at chunk **enqueue**, marking what is queued for evaluation rather
than what is written. The clamp requirement therefore shipped unimplemented.

Exploring the runner found the premise was wrong rather than the goal. The enrichments that motivated
this are **Class-1** (`evaluator.type = sql`), and a Class-1 materialize is *synchronous and performed by
ADAS itself*: when `MaterializeService.materialize` returns, the rows are in ClickHouse and ADAS is holding
the exact `(version, identity)` tuple it just wrote through — on the same axis the aggregate refresh's own
cursor uses. ADAS does not need to be told its own writes. The clamp is an ADAS-local change with no runner
change and no new cross-service contract.

## What Changes

- ADAS **records the position it has materialized each Class-1 enrichment through**, at the point of
  writing it, as a monotonic high-water mark on the rule that owns the enrichment.
- An aggregate refresh **clamps its window** to the slowest position among the enrichments its statements
  join, and proceeds over the clamped window rather than skipping it. A refresh joining no enrichment is
  byte-identically unaffected.
- The clamp is **surfaced in pipeline state**, naming the enrichment that bounded the window, so a rollup
  held back by a lagging enrichment is distinguishable from a healthy one with little input.
- A declaration referencing a column of a **group-grain enrichment is refused** with 422. For such an
  enrichment no useful position exists — see design D-5 — and the alternative is a rollup that is silently
  and permanently wrong.
- A **re-derivation of history is detected and surfaced**. A *windowed* backfill materialize is
  distinguishable from a forward one on the wire; an *unbounded* one (the default backfill shape) is not,
  and is recognised instead by what it wrote — a window ending at or below the position the rule was already
  complete through. Either way, for a Class-1 enrichment ADAS can record that history moved and flag the
  rollups reading it — narrowing an operator obligation that `aggregate-reads-entity` had to state as
  undetectable.
- Class-2 row-grain enrichments are **out of scope and refused the same way** as group-grain until the
  runner publishes a position. The runner publishes none yet.

## Capabilities

### New Capabilities

None. This completes behaviour already declared by `aggregate-pipeline` and adds one fact to the
Class-1 write path.

### Modified Capabilities

- `aggregate-pipeline`: the refresh window is clamped to its referenced enrichments' materialized
  positions; the clamp is reported in pipeline state; an enrichment whose position ADAS cannot establish
  (group-grain, or Class-2 row-grain) is refused as a field reference; a rollup whose enrichment had
  history re-derived is flagged.
- `class1-enrichment`: a materialize that writes a window records the position it wrote through, and a
  materialize that finds nothing records that the rule is drained; a windowed (backfill) materialize
  records neither, and instead marks that history was re-derived — as does an unwindowed one whose window
  ends at or below what the rule was already complete through, which is how a full replay is recognised.

## Impact

**Endpoints** — no new endpoint and no request-shape change. `GET /v1/pipelines/{name}` gains clamp fields
in its state object. `POST /v1/rules/{id}/materialize` is unchanged on the wire; only what ADAS persists
alongside it changes.

**Catalog schema** — one PostgreSQL migration, touching two tables. On `rule`, the high-water mark: the
materialized position, the drained-at instant, the `generation` it was recorded under, and the last
history-re-derivation instant. On `pipeline`, what the last refresh's clamp was (the enrichment that bounded
it and the position it bounded to) plus the instant the rollup was last rebuilt, which is the anchor the
rebuild flag compares a re-derivation against. The clamp is a fact about the *last run* and so cannot be
derived at read time; the rebuild flag and the unclamped-reads report are facts about *now* and are. No
ClickHouse migration.

**Code** — `MaterializeService` (record), `AggregateRefreshService` (clamp the probed window),
`AggregateSpecValidator` (refuse an enrichment whose position cannot be established),
`PipelineStateWriter` + the pipeline state DTO (surface it), `RuleEntity` (+ migration).

**Configuration** — none. Both inputs to the clamp arithmetic (`enrichment.scan.safety-lag`,
`aggregate.safety-lag`) are existing ADAS-side keys, so no clock agreement with the runner is needed.
`docs/configuration.md` gains guidance, not a property row.

**Depends on** — `aggregate-reads-entity`, which emits the join this change makes safe. That
change's delta spec still carries the unimplemented clamp requirement and the unimplemented clamp clause in
*Pipeline runtime state is readable*; **both move here**, and must be dropped from its delta before it is
archived, or the archived spec will describe behaviour that was never built.

**Not affected, in code** — the enrichment runner. No change to it, no new contract, no new endpoint, no new
call.

**But newly depended upon, at runtime.** ADAS is the *writer* on the Class-1 path and so needs nothing
reported to it; ADAS is not the *caller*. A mark advances only inside a materialize call the runner makes, and
`drained_at` is itself a liveness signal ("ADAS looked just now, and there was nothing"), so it does not
compensate. A runner that is down — or a Class-1 rule that is merely **disabled**, since the sync feed hands
out enabled rules only — therefore holds every rollup reading that enrichment where it is, and in the
disabled case at *nothing at all*, since the mutation that disabled the rule also discarded its recorded
position and no materialize will re-establish one. Before this change such a rollup kept advancing (with the
wrong values this change exists to prevent). This
is a deliberate trade in the safe direction, and the clamp indicator in pipeline state is what keeps it from
being discovered rather than seen; design Risks carries the failure modes, the operational guidance, and why
a staleness timeout must **not** be added as a safety valve.
