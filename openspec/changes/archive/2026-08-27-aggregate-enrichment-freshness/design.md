## Context

`aggregate-reads-entity` shipped the grain join on the aggregate path and left its freshness requirement
unimplemented, because its D-6 resolved the clamp against "the writer cursor of every referenced
enrichment" and no such cursor exists in ADAS. Three facts, established by reading the enrichment runner
(`analytics-enrichment-runner`) rather than assuming, reframe the problem:

**The scan cursor is the runner's, and it means the wrong thing.** `RuleCursorEntity` lives in the
runner's PostgreSQL; ADAS treats the cursor statelessly. Its `incremental-scan` capability requires the
cursor to advance in the same transaction as the chunk **enqueue** — so it marks what is queued for
evaluation, not what is written. That is load-bearing for the runner's deterministic-id dedup, not an
oversight, and it cannot be repurposed.

**But the lane that matters does not use it.** The rules in view are either Class-1
(`evaluator.type = sql`) — including `usage_client_identity`, the enrichment that motivated the whole
feature — or group-grain, and **none is Class-2 row-grain**. A Class-1 materialize is performed
*inside ADAS and synchronously*: `MaterializeService.materialize` probes a window, writes it with one
server-side `INSERT … SELECT`, and only then encodes the cursor. At that line ADAS is holding
`(window.version(), window.identity())` — the position it has just written through, on the same axis
`CursorPredicates.versionExpr` gives the aggregate refresh. The runner's own Class-1 executor says the
same from the other side: *"the work is already done in ClickHouse when the response returns … advance on
success is the whole protocol."*

**So ADAS is the writer, and does not need to be told.** The clamp becomes a local change: record what we
wrote, clamp to it. No runner change, no new endpoint, no cross-service contract, and no clock agreement —
both inputs to the arithmetic are ADAS-side config.

What that leaves is the two enrichment shapes ADAS *cannot* establish a position for — group-grain and
Class-2 row-grain — and this change has to say what happens to them rather than let a null decide.

## Goals / Non-Goals

**Goals:**

- Record, per Class-1 rule, the position ADAS has materialized its enrichment through, authoritatively and
  monotonically.
- Clamp an aggregate refresh window to the slowest position among the enrichments its statements join, and
  proceed over the clamped window rather than skipping it.
- Make the clamp visible in pipeline state, naming what bounded it.
- Refuse, at declaration, an enrichment column whose position ADAS cannot establish — rather than clamping
  against a value that does not mean what it appears to.
- Detect and surface a re-derivation of history for a Class-1 enrichment.
- Leave a pipeline that references no enrichment compiling and refreshing byte-identically.

**Non-Goals:**

- Any change to the enrichment runner, or any new call between the two services.
- A position for Class-2 row-grain enrichments. None exists today; when one does, the runner publishes it
  (see D-7) and this change's clamp consumes it unchanged.
- Making a group-grain enrichment aggregatable. D-5 argues it is not, and says what would change that.
- Lineage and delete safety for the pipeline→enrichment edge, still deferred with the follow-up that owns
  the DAG questions.

## Decisions

### D-1: ADAS records its own high-water mark, at the write

`MaterializeService` persists the position it just wrote through, at the point of writing it. Nothing
reports it, nothing is trusted: ADAS ran the `INSERT … SELECT` and holds the probed window's end tuple.

*Alternative rejected — have the runner report a written-through position.* This was the original plan and
it is strictly worse for Class-1: it converts a fact ADAS already holds into a cross-service contract with
a reporting delay, a new endpoint, and a second thing that can be wrong. It remains the answer for
Class-2 (D-7), where ADAS genuinely is not the writer.

*Alternative rejected — derive the position from the data.* An enrichment's coverage is rule-defined
(`filter_sql`, `sampling`, `ready_when`), so "every source row up to P that this rule will ever cover has a
row" cannot be computed without replaying the rule's membership. A `max()` over covered rows is a maximum,
not a watermark. Recording what we wrote sidesteps this entirely: rows the rule filters out or samples away
are never waited for, because the position advances past them by construction.

### D-2: The position is two facts, not one

Stored per rule: `materialized_through` (the end tuple of the last written window) **and** `drained_at`
(when the probe last found nothing more). The clamp uses
`max(materialized_through, drained_at − enrichment.scan.safety-lag)`.

One fact is not enough, and the failure is silent. `probeWindow` applies the rule's `filter_sql`, so the
recorded position is the last row *matching the filter* — and non-matching rows sit **above** it:

```
source ──────────────────────────────────────────────────────────▶
         ●        ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○
         ▲        └── filtered out; their enrichment cells are permanently absent
   last match = recorded position
         │
         └── a rollup clamped here waits forever for rows nothing will ever enrich
```

A rule with no filter (`usage-client-identity-live` has none) tracks the source's tail and never shows
this. A filtered rule (`conversation-insights-live` filters on `event_kind`, `request_body_bytes` and a
`request_time` floor) pins its dependants at its last match indefinitely, while looking healthy.

The second fact costs nothing because the signal already exists: `MaterializeService`'s `window == null`
branch means *"nothing left matching, up to now − safety lag"*. Recording an instant there releases the
clamp for a drained rule and keeps it honest for a backlogged one.

*Alternative rejected — record only the written position and accept the stall.* It makes the clamp
unusable for exactly the rules that most need it, and the stall is indistinguishable from a healthy rollup
with no new input.

### D-3: The mark lives on the rule row, stamped with `generation`

Stored on `rule`, alongside the `generation` it was recorded under, and treated as absent when generation
has since moved.

`generation` is already the invalidation signal, and it covers a case that is easy to miss:
`RuleGenerationBump.onVersionColumnRepointed(source)` bumps every rule reading a source whose
`version_column` was repointed — which does not make a stored `(version, identity)` tuple *stale*, it makes
it **meaningless**, because the axis it is expressed on no longer exists. The bump is transactional with
the repoint by design, so keying off it is exact rather than approximate.

The rule row rather than the enrichment's `table_definition`, because rule deletion is a plain
`rules.delete(rule)` while the enrichment table and its rows survive it. A mark on the table would be
inherited by the *next* rule registered against that table — a position it never earned. On the rule it
disappears with the rule, and one-rule-per-enrichment is already enforced
(`requireTargetAvailable` / `findByTargetEnrichment` → `Optional`), so the clamp's lookup is one indexed
query.

Cost: any accepted rule mutation discards the mark, including one as inert as `cadence`. It is
re-established on the next materialize — one cadence, 5 minutes by default — and discarding is the safe
direction.

### D-4: An enrichment with no rule is unclamped, not unknown

Deleting a rule leaves its enrichment table and rows in place. The mark goes with the rule, so the clamp
would find nothing — and treating "no mark" as "clamp to nothing" freezes every dependent rollup
permanently, with no operator action that could release it.

A rule-less enrichment will never gain another row. Its NULLs are not late, they are **final**, and there
is nothing to wait for. So the absence of a rule is treated as "no constraint", distinct from the absence
of a mark on a rule that exists (which clamps, because that rule has written nothing yet).

*Alternative rejected — freeze, as the conservative reading.* Conservative here means permanently stuck
with no escape, to protect against data that cannot arrive.

### D-5: A group-grain enrichment column is refused

**Decided: refuse, with 422, naming the enrichment and its rule's group key.**

For a group-grain enrichment the position is not merely hard to obtain — it exists and is useless. The
runner's `GroupDirtyMarkCommitter` states *"Nothing is enqueued here"*: a scan page records `group_state`
and advances the cursor, and evaluation happens later, when `GroupReadinessSweep` decides the group is
ready by a clock. So the sound position is "the cursor below which every group is settled", and a group's
members span arbitrary positions:

```
chat c1, active for three weeks
──────────────────────────────────────────────────────────────────▶ source cursor
  m1      m2        m3         m4          m5            m6
  │                                                       │
  │◀──── every one of these belongs to an UNSETTLED group ────▶│
  ▲
  └── watermark pinned here, for three weeks
```

Aggregating *any* member of an unsettled group reads a provisional value, so one perpetually-active
conversation pins the position at its earliest member indefinitely. And the value is not merely late: it
**changes** on every recompute, by design, which is the mutation case the clamp explicitly does not
address. The staleness is created when the rollup reads, not when the group settles, so "read only settled
groups" cannot be retrofitted either — and settledness is runner-side state ADAS cannot see.

Refusing is chosen over the two alternatives on one asymmetry: **a refusal can be relaxed later; numbers
that were silently wrong cannot be un-published.** No pipeline was known to read a group-grain enrichment
such as `conversation_insights`, so refusing forecloses nothing that exists.

*Alternative rejected — allow, and mark the pipeline permanently rebuild-required.* Honest labelling over
wrong numbers is still wrong numbers, and a permanent warning is one nobody acts on.

*Alternative rejected for now — a rebuild freshness mode.* This is the one that would make it correct, and
it is attractively small: a `FreshnessMode` whose entire implementation is *never persist the cursor*, so
every tick recomputes from current values. `FreshnessMode` already exists as a declared vocabulary
(`PERIODIC` implemented, `INCREMENTAL` declared and rejected) precisely so intent stays legible, so it
would fit. It is deferred because it buys nothing today and costs a full input scan plus a full enrichment
read per tick — acceptable at the sizes measured in `aggregate-reads-entity`, unbounded as history grows.
**Reopen it when someone actually wants a rollup over LLM-derived conversation labels**, which is the
plausible demand.

### D-6: A backfill materialize records neither position, and marks history instead

`BackfillExecutor` calls materialize with the run's own cursor plus a `from`/`to` window, which the live
lane never passes — so a **bounded** backfill is distinguishable in ADAS's own request handler, and that
discriminator costs nothing.

**It is not sufficient on its own, and assuming it was is the mistake this section originally made.**
`BackfillWindow.UNBOUNDED` is `(null, null)`, it is what `POST /v1/rules/{id}/backfill` uses when the
request carries no body, and it is *"still the default"* — so a **full replay**, which is precisely how an
evaluator-expression bump is re-applied to history, reaches ADAS carrying a cursor and no window and is
byte-identical to a live tick. Verified against a local ADAS + runner pair: the default backfill
recorded a position and left `history_rederived_at` null, so the rebuild flag never fired for the one case
it exists for.

What separates the two is not the request but the **effect**. A live tick writes rows the rule has never
been complete through; a replay rewrites rows it has. So the second half of the discriminator is asked of
the write, against the same effective position the clamp uses — `max(materialized_through, drained_at −
safety lag)`, evaluated before this call's own record is applied:

| materialize call | `from`/`to` | window end vs. complete-through | records |
|---|---|---|---|
| live tick | absent | above | `materialized_through` |
| live tick, nothing found | absent | — | `drained_at` |
| full replay | absent | at or below | `materialized_through` (unchanged, monotonic) **and** `history_rederived_at` |
| bounded backfill | present | — | `history_rederived_at` — and neither position |

Recording a backfill's position would drag the mark backwards; the monotonic max would absorb it, but the
rule is stated positively so the design does not rest on a guard.

The effect test costs one false positive, kept rather than designed away: a live tick whose response was
lost re-executes an identical window on the next fire (`Class1MaterializeExecutor` advances its cursor only
on success), and nothing on the wire or in the catalog separates that from a replay of one page. It is
reported as a re-derivation. The alternative considered was a one-line runner change sending an explicit
`from` for `UNBOUNDED`, which is exact — rejected because it moves a correctness property into a second
service's request construction, where a future caller that forgets it fails silently, whereas the effect
test holds for any caller. A rare spurious rebuild instruction is the same safe direction every other
decision here takes; a systematic miss on the default backfill shape is not.

The second half is a genuine narrowing of `aggregate-reads-entity`'s D-8, which had to state that a value
*changing* is undetectable because "the enrichment write is indistinguishable from any other". That is true
of Class-2. For Class-1 ADAS can see it, so a rollup reading an enrichment whose history moved can be
flagged rather than left to folklore.

### D-7: Class-2 row-grain is refused on the same rule as group-grain, and the runner work is specified but not built

No Class-2 row-grain rule exists. When one does, the runner must publish a position, and the exploration
established what that has to be so the eventual change does not have to rediscover it:

- **Not the zero-inflight sample** — promoting the cursor whenever the rule's in-flight depth hits zero
  looks cheap (`InflightCounter` already computes that depth for backpressure) and is sound, but the
  per-rule DIAL rate limit caps drain at a low per-minute rate while backpressure re-tops the queue to
  `scan.max-inflight` every cadence. Depth reaches zero **only when the rule is caught up**, which is the
  one time the clamp has nothing to do; while it is behind — a new rule over a large existing source can take
  days of catch-up — the mark is frozen at its last value, or at `null`.
- **A per-page low-watermark** is the shape that behaves while behind. It needs a runner-assigned monotonic
  page sequence and a `seq → cursor` side table, because the runner **cannot order two cursors**: they are
  opaque and ADAS-owned (*"the runner stores it verbatim"*), `DeterministicId` hashes the cursor-before
  into the instance id, and neither `ChunkPayload` nor `UpsertPayload` carries a position.

Until then the refusal is the same one D-5 imposes, and for the same reason: no establishable position.

### D-8: An enrichment whose rule reads another table is admitted, not clamped, and said so

**Decided (resolves Q-3): admit the column, impose no clamp for that enrichment, and report it in pipeline
state.** Not refused, unlike D-5 and D-7.

The clamp is sound only where **the rule walks the same rows the pipeline walks**. It does for
`usage_client_identity`: the rule reads `dial_usage_log`, the enrichment attaches to `dial_usage_log`, and
each materialized row is derived from the very source row it is keyed on — so "materialized through P"
really does mean "every row up to P that this rule covers has its value". Since
`2026-08-13-decouple-rule-read-source`, a rule MAY instead declare its own read source, and then it does
not. `turn_feedback` attaches to `turns` but its rule reads `response_ratings`; a rating written today
attaches to a turn from three weeks ago, so the mapping from the rule's axis to the pipeline's is **the
grain join, which is not order-preserving**. No recorded position on any axis supports the induction in the
clamp requirement, and storing the axis alongside the position does not recover it — there is nothing to
translate.

Refusing was the leaning when Q-3 was raised, on D-5's asymmetry. Two things overturned it:

- **The capability lost would be real.** Cross-table attribution — a rollup at the shared grain plus a
  pass-through Class-1 rule — is the only way to relate two tables in ADAS, and it produces exactly this
  shape. Refusing means its output can never be aggregated.
- **The clamp would be near-inert for it anyway.** Such an enrichment is typically sparse (`turn_feedback`
  covers a tiny fraction of turns), so its rule drains on nearly every tick and D-2's drained horizon
  releases the clamp regardless. The absences are **permanent, not late** — which is the case the
  declared-nullable rule and the pipeline's own `filter` already handle, and which no clamp was ever going
  to help with.

What makes admitting it acceptable rather than a reopening of the gap is that it is **named**: pipeline
state reports the enrichment as read-but-unclamped, with the reason. D-5's asymmetry argument bites hardest
where the alternative is *silence*; it does not apply to a reported condition.

## Risks / Trade-offs

**[A rollup is only as fresh as the slowest enrichment it reads] → stated, and surfaced.** The honest cost
of the clamp, inherited from `aggregate-reads-entity` D-6/D-7. `drained_at` (D-2) is what keeps it from
being worse than that in the common case.

**[The refusal in D-5 is the wrong call if group-grain rollups are the actual demand] → reversible, and the
reversal is designed.** The rebuild freshness mode is specified enough in D-5 to be built when asked. The
asymmetry that decides it — relax later vs. un-publish wrong numbers — is what makes starting strict right.

**[`generation` invalidation is coarse] → accepted, one cadence to recover.** An inert rule edit discards a
valid mark. Making it finer means enumerating which fields matter, which is the kind of list that goes
stale silently; a version-column repoint proves the list would have to include indirect causes too.

**[Recording on the wrong branch would record the caller's claim] → the empty-window branch echoes the
*caller's* cursor.** `MaterializeService` returns `new MaterializePage(0L, cursor, false, null)` when the
probe finds nothing. Recording that as a position would persist what the runner asserted rather than what
ADAS observed. The rule is: record `materialized_through` **only** on the probed-window branch, and
`drained_at` only on the empty branch. Pinned as a scenario because it reads as harmless.

**[The clamp could be read as superseding the declared-nullable rule] → it does not, and the rule stays.**
`aggregate-reads-entity` refuses a group key or measure that reads an enrichment column declared
non-nullable, because ClickHouse's `join_use_nulls` defaults to 0 and an unmatched outer join renders such a
column as its type default rather than as NULL. The clamp does not make that rule redundant: rows a rule's
`filter_sql`, `sampling` or `ready_when` permanently excludes are never enriched, and they pass the clamp by
construction (D-2's drained horizon releases it for exactly those rows). Such a row still reads `''` or `0`
from the join. Both guards are needed, and for different reasons — one is about freshness, the other about
representability.

**[A clamp that is always active would change every existing rollup] → it is inert without a join.** A
refresh that joins no enrichment resolves no enrichments to clamp against and keeps its probed window
unchanged, which is asserted rather than assumed.

**[The aggregate path gains a liveness dependency on the enrichment runner] → accepted, because the
alternative is silent corruption; and it is made visible rather than left to be discovered.** This is the
one risk that is a *new coupling between services* rather than a property of the arithmetic, and it is
worth stating precisely because the correctness argument and the liveness argument run in opposite
directions.

D-1 rests on ADAS being the **writer**: `MaterializeService` runs the `INSERT … SELECT` and holds the tuple
it wrote through, so nothing has to report it. But ADAS is not the **caller**. A Class-1 materialize happens
only when the enrichment runner calls `POST /v1/rules/{id}/materialize`, on its own cadence and holding its
own cursor. Nothing in ADAS advances a mark on its own initiative.

Before this change the aggregate scheduler is independent of the runner: with the runner away, enrichment
columns go stale or stay empty and the rollup keeps refreshing and keeps advancing its cursor — producing
the wrong numbers this change exists to prevent, but moving. After it, a pipeline cannot advance past the
recorded mark of any enrichment it references, so no calls means no marks means the window pins where it is
and **the rollup stops advancing** for as long as the runner is away.

`drained_at` does **not** rescue this, and that is the counterintuitive part worth pinning. It is a
*liveness-derived* signal — it means "ADAS looked just now, and there was nothing" — so it moves only inside
a call too. The mechanism D-2 introduces to stop a **stalled rule** from freezing its dependants gives no
protection whatever against a **stalled caller**.

Five things produce it, and the second has the widest blast radius:

1. the runner is down, not deployed, or crash-looping;
2. **the rule is disabled** — the sync feed hands the runner enabled rules only, so switching off one
   Class-1 rule freezes every rollup reading its enrichment. This is Q-1 arriving through a second door:
   leaving a paused rule clamped is right on soundness grounds, but the consequence reaches pipelines whose
   declarer never considered that rule;
3. **any accepted rule mutation** — D-3 treats a moved `generation` as an absent mark, and absent-on-a-rule-
   that-exists clamps (D-4), so an edit as inert as `cadence` freezes dependants until the next materialize.
   "One cadence to recover" holds only while the runner is calling, and not at all if the rule was edited
   *and* disabled;
4. the runner is alive but not reaching this rule — rate limits, backoff, scheduler threads saturated;
5. a partition or auth failure between the two services.

It is nonetheless the right trade, and for the reason the whole change exists: the only alternative to
stopping is aggregating rows whose enrichment cells are empty, which is permanent and silent, and which an
`upsert_by_key` target cannot even be corrected for by deleting a key that should no longer exist. Stopping
is the safe direction — a stalled rollup is visible and recoverable, a corrupted one is neither — and the
clamp indicator is what makes it visible, turning "the rollup is mysteriously stuck" into "this enrichment
has not materialized for the last hour".

*Alternative rejected, and recorded so it is not added later as an obvious improvement — a staleness
timeout.* Treating a mark older than some threshold as imposing no clamp looks like the natural safety
valve and is exactly wrong: it converts a hard, visible, recoverable stall into the silent corruption this
change was built to prevent, on a timer, and it fires hardest during precisely the outages in which the
enrichment data is least trustworthy. The coupling is removed properly by the dirty-mark mechanism — the
enrichment write marks the affected grain keys and the pipeline consumes those marks instead of a watermark
— which needs no liveness signal at all, and which also resolves D-8's divergent-axis case. One follow-up
change, two reasons to want it.

Operationally this means: alert on the clamp indicator persisting beyond a few enrichment cadences rather
than on `lag_seconds`, which sawtooths by design and cannot distinguish this; and before disabling or
editing a Class-1 rule, check which pipelines read its enrichment. There is no lineage query for that edge
yet, so today that is a manual read of the pipeline declarations — one more argument for the deferred
lineage change.

## Migration Plan

One PostgreSQL migration adding four nullable columns to `rule`; no ClickHouse migration and no backfill.
Existing rules start with no mark, so the first materialize after deploy establishes one — a pipeline
reading a Class-1 enrichment refreshes normally within one enrichment cadence of deploy, and until then
clamps to nothing written, which is the safe direction.

Deployment is additive except for D-5's refusal, which is **behaviour-narrowing**: a declaration that
`aggregate-reads-entity` would have accepted (a group-grain enrichment column) is refused. No such pipeline
was known to exist, so nothing stored becomes invalid; a stored one would fail its next re-validation loudly rather
than write wrong numbers, which is the intended behaviour and not a data hazard.

Rollback is a revert. The recorded marks become inert rather than unreadable, and a pipeline registered
against a group-grain enrichment column under the reverted code would then be accepted again — the reason
D-5 refuses now rather than after someone builds on it.

**Sequencing:** `aggregate-reads-entity` must drop the clamp requirement and the clamp clause in *Pipeline
runtime state is readable* from its delta before it is archived; both move to this change. Archiving it
first would put an unbuilt requirement into `openspec/specs/aggregate-pipeline/`.

## Open Questions

- **Q-1 — should `drained_at` also release the clamp for a *paused* rule?** A paused Class-1 rule stops
  materializing, so its mark freezes and its dependants stall. That is correct (rows are genuinely
  un-enriched), but a rule paused indefinitely is closer to D-4's rule-less enrichment than to a lagging
  one. Leaning: leave it clamped and let D-7's state reporting make it visible, since a pause is reversible
  and a deletion is not.
- **Q-2 — one clamp per refresh, or the window's own end when it is already behind?** Specified as clamping
  only when the probed window would end *past* the slowest position, so a refresh that is already behind
  its enrichments is untouched. Stated here because the reverse reading (always clamp to the enrichment
  position) would drag a healthy rollup backwards.
- **Q-3 — the "same axis" premise is asserted, not enforced. What happens when a rule reads a different
  table from its enrichment's source?** Raised in review of this proposal. **Decided —
  see D-8: admitted, not clamped, and reported.** The analysis below is kept because it is what the
  decision rests on; where it says "leaning: refuse", D-8 records why that leaning was overturned.

  D-1 rests on the recorded tuple being *"on the same axis the aggregate refresh's own cursor uses"*. That
  holds when a rule derives its read source from its target, but a rule may **declare** one:
  `RuleGovernanceValidator.requireReadSource` accepts any declared `source_table` that exists, is an ACTIVE
  source, carries the target's grain key at a compatible type, and is scannable — it never requires it to
  equal `target.getSourceTable()`.

  So: enrichment `E` hangs off source `A`; rule `R` targets `E` but declares source `B`. `R` records
  `materialized_through` on **B's** cursor axis, while an aggregate pipeline over input `A` joins `E` and
  clamps against it. Both positions are `(timestamp, string)`, so the comparison **succeeds silently** and
  means nothing. Depending on the two tables' relative version clocks the clamp is then either permanently
  inert — never binding, so the freshness gap survives with no indication that the clamp is not working — or
  permanently stalling, freezing the rollup with no operator action that releases it.

  D-3's `generation` guard does not cover this. It invalidates a mark when the axis *changes* (a
  `version_column` repoint) and correctly keys off the rule's read source; divergence is instead a static
  property of the rule, valid from the moment it is registered, so no bump ever fires.

  Leaning: **refuse the field reference**, on the same rule and for the same reason as D-5 and D-7 — no
  establishable position *on the axis the refresh needs*. It is the cheapest of the three options, it is
  reversible, and it keeps the clamp's contract exactly one sentence long. The alternatives are to require
  `rule.source_table == target.source_table` for a rule whose enrichment any pipeline reads (a
  behaviour-narrowing rule on the *enrichment* surface, which would have to answer for rules already
  registered), or to record the axis alongside the position and clamp only when it matches (correct, and
  it makes the mark's meaning depend on a second field that every reader has to remember to check).
