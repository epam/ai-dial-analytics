## MODIFIED Requirements

### Requirement: Pipeline runtime state is readable

A pipeline SHALL carry its own runtime execution state — the scan cursor, the last and next run times,
and the last failure — persisted in the registry (not ClickHouse), because the schedule is ADAS-driven
and no caller holds the position. `GET /v1/pipelines/{name}` SHALL surface this state read-only,
including a staleness measure, so an operator can tell a fresh rollup from a stalled one without reading
logs.

Where a refresh's window was clamped short of its input's available cursor to keep a referenced
enrichment's join sound, the state SHALL say so, identifying the enrichment that bounded it and the
position it bounded the window to. A clamped rollup is behind its input for a reason an operator must be
able to see: without it, a pipeline permanently held back by a lagging enrichment is indistinguishable
from a healthy one that simply had less input to read, and the failure this introduces is a quiet one.

Where a pipeline reads an enrichment that imposes no clamp because ADAS cannot establish a position on the
axis the refresh windows on, the state SHALL name that enrichment and the reason. This indicator is what
makes admitting such a column different from ignoring the problem: the rollup is exposed to the freshness gap
for that one enrichment, and an operator has to be able to see which one and why without reading the
declaration against the rule registry.

Where an enrichment a pipeline reads has had its history re-derived since the pipeline last read it, the
state SHALL report that the rollup needs rebuilding, naming the enrichment. This is not a clamp and no
window is held back: the rollup is already wrong and no refresh will correct it, so the signal is an
instruction to the operator rather than a description of progress.

State SHALL be server-owned: no endpoint SHALL let a caller set a cursor, since a caller-chosen position
could skip input rows and silently produce wrong measures.

A refresh SHALL record its failure against the pipeline **whichever route triggered it** — the scheduler
tick or `POST /v1/pipelines/{name}/refresh`. An on-demand failure that only returned an HTTP error would
leave the state view reporting the *previous* run's health, so the endpoint an operator consults would
disagree with what actually happened. Recording the failure SHALL NOT mask the failure being reported: if
the state write itself fails, the original cause is still what surfaces. A recorded message SHALL be
non-empty, since a blank one is stored as "no error" and reads as a success.

#### Scenario: State is reported after a run

- **WHEN** `GET /v1/pipelines/{name}` is called after a successful refresh
- **THEN** the response carries the advanced cursor, the last run time, the next run time, and a
  staleness measure

#### Scenario: A failure is visible without reading logs

- **WHEN** a refresh fails — on a scheduler tick or through `POST /v1/pipelines/{name}/refresh` — and
  `GET /v1/pipelines/{name}` is called
- **THEN** the response reports the failure alongside the unadvanced cursor, identically for both routes

#### Scenario: Cursors are not caller-settable

- **WHEN** a caller attempts to set a pipeline's cursor through the API
- **THEN** the attempt is rejected

#### Scenario: A clamped window is visible in state

- **WHEN** a refresh's window is clamped to a referenced enrichment's materialized position rather than
  to its input's available cursor
- **THEN** `GET /v1/pipelines/{name}` reports that the window was clamped, names the enrichment that
  bounded it, and reports the cursor the refresh did reach

#### Scenario: An unclamped refresh reports no clamp

- **WHEN** a refresh's window ends at or before every referenced enrichment's materialized position, or
  the pipeline references no enrichment at all
- **THEN** the state reports no clamp, so the indicator means something when it is present

#### Scenario: An enrichment that cannot be clamped against is named

- **WHEN** a pipeline reads an enrichment for which no position on the refresh's own axis can be established
- **THEN** `GET /v1/pipelines/{name}` names that enrichment as read-but-unclamped, with the reason, whether
  or not any other enrichment clamped the window

#### Scenario: A rollup needing a rebuild says so

- **WHEN** an enrichment a pipeline reads has had its history re-derived since the pipeline last read it
- **THEN** `GET /v1/pipelines/{name}` reports that the rollup requires a rebuild and names that
  enrichment

### Requirement: Re-running an enrichment over history invalidates its dependent rollups

A rollup SHALL be treated as invalid until rebuilt where an enrichment it reads has had its values
recomputed for source rows the pipeline already aggregated — a rule's definition changed, or a backfill
re-derived history. The capability SHALL document how to rebuild one **using the operations it already
has**, and SHALL NOT introduce a rebuild operation of its own: deleting a pipeline and re-registering it
resets the cursor, since a pipeline is stored with none and a delete leaves the target's rows intact; and
where the re-run changed values that were already aggregated, deleting and recreating the target removes
the keys that no longer occur, taking the pipeline with it by cascade.

The refresh-window clamp guards an enrichment value arriving *late*. It does nothing for one that *changes*,
and the two failures look nothing alike. A source row whose enrichment value moves from `A` to `B` does not
move its own cursor position, so no window revisits it; the target keeps its row for `A` indefinitely,
because an upsert-by-key target can replace a key's row but cannot delete one whose key no longer occurs,
and `B` never appears. The rollup is then wrong in both directions at once.

Whether the condition is *detectable* SHALL depend on who writes the enrichment, and the capability SHALL
NOT state it as uniformly undetectable. Where ADAS materializes the enrichment itself, a re-derivation of
history is distinguishable from forward progress — by the call where it can be (a windowed materialize names
the range it is rewriting, a live one does not), and otherwise by the write, since an unwindowed replay
rewrites rows the rule was already complete through where a live tick never does — so ADAS SHALL record it
and report the dependent rollups as requiring a rebuild. Where ADAS does not write the enrichment, the condition remains undetectable: the write
is indistinguishable from any other and the pipeline has no way to know that a value it already read has
since changed. That asymmetry is one of the reasons an enrichment ADAS does not write is refused as a field
reference altogether. In both cases the obligation SHALL be stated against the operator surface rather than
left to folklore, because detection is not correction — a flagged rollup is still wrong until rebuilt.

#### Scenario: Re-registering a pipeline re-reads its whole input

- **WHEN** an operator deletes a pipeline and registers the same declaration again
- **THEN** the new pipeline carries no cursor, so its next refresh re-reads its input from the beginning
  and rewrites every group from current enrichment values

#### Scenario: A re-run that only filled absent values needs no target change

- **GIVEN** an enrichment re-run that filled values which had been absent, changing none that were
  already aggregated
- **WHEN** the pipeline is re-registered
- **THEN** every group is rewritten and no stale key remains, because a key that never occurred was
  never written

#### Scenario: A re-run that changed aggregated values needs the target recreated

- **GIVEN** an enrichment re-run that moved an already-aggregated source row from one key to another
- **WHEN** the operator recreates the target table and re-registers the pipeline
- **THEN** the superseded key is gone, because an upsert-by-key target can replace a key's row but
  cannot delete one whose key no longer occurs

#### Scenario: The obligation is documented against the enrichment surface

- **WHEN** an operator re-runs or backfills an enrichment that a pipeline reads
- **THEN** the capability states that the dependent rollups are invalid until rebuilt, and which of the
  two rebuilds above the re-run calls for

#### Scenario: A re-derivation ADAS performed is detected rather than left to folklore

- **WHEN** an enrichment ADAS materializes itself has its history re-derived, whether over a named window
  or by a full replay carrying none
- **THEN** the rollups reading it report that they require a rebuild, rather than the capability stating
  that nothing can detect the condition

## ADDED Requirements

### Requirement: A refresh window is clamped to its referenced enrichments' materialized positions

A refresh whose statements join an enrichment SHALL NOT close its window past the position ADAS has
materialized that enrichment through. Where the probed window would end beyond it, the window end SHALL be
clamped to the earliest such position across every enrichment the refresh references, and the refresh
SHALL proceed over the clamped window rather than be skipped. A refresh whose window already ends at or
before every referenced position SHALL be unaffected, and a refresh referencing no enrichment SHALL compile
and execute exactly as it did before.

An enrichment row is written *later* than the source row it derives from. Without the clamp a refresh reads
source rows whose enrichment cells are still NULL and writes the resulting group, and **nothing ever
corrects it**: those source rows did not move, so no later window revisits them, and the target's upsert
leaves the wrong value in place permanently.

Where the enrichment feeds a group *key* the declaration must carry a `filter`, so the unreached row leaves
the rollup rather than being mis-keyed — and whether it comes back depends on where it sits, which is worth
stating precisely because the unqualified reading overstates the danger and the precise one locates it. A
refresh's window ends at the cursor of the last row that **matched** the filter, so an unreached row is
revisited only when no reached row sits after it. Ordinary trailing lag is therefore self-correcting: the
unreached rows are the newest, the window parks before them, and a later refresh picks them up. The
permanent loss needs coverage to be **non-contiguous** — a backfill filling older gaps, `sampling`,
`ready_when`, or out-of-order arrival — because a reached row *after* an unreached one carries the window
end past it, and nothing revisits a source row whose own cursor did not move. Both behaviours were
confirmed against a running ClickHouse. The clamp closes the second case by holding the window
end at the enrichment's materialized position, which is below the unreached row by construction.

The clamp is sound by induction rather than by margin: if every window ever closed at a position its
referenced enrichments had already been materialized through, then every source row ever aggregated had its
enrichment row present when it was read. The guarantee maintained is not "the enrichment is fresh now" but
"the enrichment was never behind at any point that was read". A configured safety lag SHALL NOT be accepted
as a substitute: a lag is a guess about a cadence, and a re-derivation of history invalidates the guess
exactly when its consequence is largest.

A referenced enrichment's position SHALL be taken as the later of the position ADAS last materialized it
through and the horizon at which ADAS last found it drained. Taking the materialized position alone is
unsound as a clamp even though it is correct as a record: an enrichment whose rule declares a filter
advances its position only to its last *matching* source row, so source rows above it that the rule will
never cover would hold the window back forever while nothing was ever going to enrich them.

An enrichment whose owning rule no longer exists SHALL NOT clamp anything. Such an enrichment will never
gain another row, so its absent values are final rather than late and there is nothing to wait for; an
enrichment whose rule exists but has materialized nothing yet SHALL clamp, because that rule is expected to
write.

An enrichment whose owning rule reads a **different table** from the pipeline's input SHALL be admitted
without clamping, and SHALL be reported as read-but-unclamped in the pipeline's state, naming the enrichment
and the reason. The clamp is sound only where the rule derives each enrichment row from the very source row
it is keyed on; where a rule declares its own read source, the mapping from its position to the pipeline's is
the grain join, which is not order-preserving, so no recorded position supports the guarantee above. Refusing
such a column would remove the only means ADAS has of relating two tables — a rollup at a shared grain plus a
pass-through rule — to guard a case in which the enrichment's absences are typically permanent rather than
late, and therefore already the business of the declared-nullable rule and the pipeline's own `filter`.
Reporting it is what distinguishes this from the silent gap this requirement closes.

The cost SHALL be stated rather than hidden: a rollup over an enrichment column is only as fresh as the
slowest enrichment it reads. **And the cost includes a liveness dependency**, which SHALL be documented
against the operator surface rather than left to be discovered: a position advances only when the enrichment
is actually materialized, and the drained horizon is itself an observation made at that moment, so an
enrichment that stops being materialized at all — its runner unavailable, or its rule merely disabled — holds
every rollup reading it at its current position indefinitely. That is the safe direction, since the
alternative is aggregating absent values permanently and silently, and it is why the clamp is reported in
state. A staleness timeout that released the clamp after some interval SHALL NOT be introduced: it would
convert a visible, recoverable stall into exactly the silent corruption this requirement exists to prevent,
and it would do so most readily during the outages in which the enrichment's values are least trustworthy.

#### Scenario: A window is clamped to a lagging enrichment

- **GIVEN** source rows ingested ahead of the enrichment rows derived from them
- **WHEN** a refresh runs
- **THEN** the window ends at the position the enrichment was materialized through, and the rows beyond it
  are left for a later refresh rather than aggregated with NULL enrichment cells

#### Scenario: A clamped group is written correctly on a later tick

- **GIVEN** the refresh above, and the enrichment subsequently catching up
- **WHEN** the next refresh runs
- **THEN** the previously withheld rows are aggregated with their enrichment values present, and no group
  was ever written from a NULL the enrichment had not yet filled

#### Scenario: A drained enrichment does not hold the window back

- **GIVEN** an enrichment whose rule declares a filter, whose last matching source row is old, and which
  ADAS last found drained
- **WHEN** a refresh runs
- **THEN** the window is clamped to the drained horizon rather than to the last matching row, so source
  rows the rule will never cover are aggregated rather than waited for

#### Scenario: A pipeline referencing no enrichment is not clamped

- **WHEN** a refresh whose declaration references no enrichment column runs
- **THEN** its window, predicates and rendered statements are identical to what they were before this
  capability clamped anything

#### Scenario: An enrichment with no rule imposes no clamp

- **GIVEN** an enrichment whose owning rule has been deleted, its table and rows left in place
- **WHEN** a refresh reading one of its columns runs
- **THEN** the window is not clamped on its account, since no further rows will ever be written to it

#### Scenario: An enrichment whose rule reads another table is admitted and reported

- **GIVEN** an enrichment whose owning rule declares a read source other than the pipeline's input
- **WHEN** a pipeline reading one of its columns is registered and refreshed
- **THEN** the declaration is accepted, the window is not clamped on its account, and the pipeline's state
  reports that enrichment as read-but-unclamped with the reason

#### Scenario: An enrichment that stops being materialized holds the window

- **GIVEN** a referenced enrichment whose rule has been disabled, or is otherwise no longer materialized
- **WHEN** a refresh runs
- **THEN** the window stays clamped at the last recorded position and the state names that enrichment,
  rather than the window being released after any interval

### Requirement: An enrichment whose materialized position cannot be established is not aggregatable

A declaration SHALL be refused with HTTP 422 where it references a column of an enrichment for which ADAS
cannot establish a materialized position. The refusal SHALL name the enrichment and say why it cannot be
read, so the answer is actionable rather than a flat prohibition. The check SHALL be applied at
registration and re-applied at execution, like every other rule on this path.

**Exactly two shapes** are refused, for one reason — that the value itself has no settled position, not
merely that ADAS cannot name one:

- an enrichment written by a **group-trigger** rule, whose value is derived from a group of source rows and
  recomputed whenever the group gains a member;
- an enrichment written by a rule ADAS does not execute itself, whose write-through position is therefore
  held by the runner and not reported to ADAS.

A third shape SHALL NOT be refused, and the distinction is the point: an enrichment whose rule reads a table
other than the pipeline's input has a perfectly settled position, just not one on the axis the refresh
windows on. Its values do not change under the rollup and are not provisional, so the failure it is exposed
to is the ordinary freshness gap rather than a permanently moving target. It is admitted, unclamped and
reported — see *A refresh window is clamped to its referenced enrichments' materialized positions*.

For a group-grain enrichment the position is not merely unavailable but useless. A group's members span
arbitrary source positions and aggregating any member of an unsettled group reads a provisional value, so
the only sound position is the one below which every group has settled — which a single long-lived group
pins at its earliest member indefinitely. Worse, the value does not merely arrive late, it **changes** on
every recompute, by design, which no clamp addresses. Admitting such a column would produce a rollup that
is silently and permanently wrong in both directions, which is the failure this capability exists to
prevent.

This refusal SHALL be understood as narrowing rather than final: it is stated this way because a refusal
can be relaxed once a sound mechanism exists, whereas numbers already published from a wrong rollup cannot
be withdrawn.

#### Scenario: A group-grain enrichment column is refused

- **WHEN** a declaration names a column of an enrichment whose rule uses the group trigger
- **THEN** it is refused with 422, naming the enrichment and stating that a group-derived value has no
  settled position to aggregate against

#### Scenario: An enrichment ADAS does not write is refused

- **WHEN** a declaration names a column of an enrichment whose rule is executed by the runner rather than
  by ADAS, so no materialized position is recorded
- **THEN** it is refused with 422, naming the enrichment

#### Scenario: The refusal is re-applied at execution

- **GIVEN** a stored pipeline whose enrichment's rule is changed to the group trigger after registration
- **WHEN** the pipeline is refreshed
- **THEN** the refresh fails with the same refusal rather than writing a rollup from provisional values

### Requirement: A rollup whose enrichment had history re-derived is flagged

ADAS SHALL record a re-derivation of an enrichment's history, and SHALL report every rollup reading that
enrichment as requiring a rebuild, where values were re-derived for source rows the rollup had already
aggregated. The rebuild itself SHALL remain the operator's, using the operations the capability already
has, and no rebuild operation SHALL be introduced.

This narrows an obligation the capability previously had to state as undetectable. For an enrichment ADAS
materializes itself, a re-derivation of history is distinguishable from forward progress on two grounds, and
both are needed: a windowed materialize names the range it is rewriting where a live one does not, and an
unwindowed replay — which carries a cursor and no window, and so is indistinguishable from a live tick by
its request alone — rewrites rows the rule was already complete through, which a live tick never does. So
the condition that produces a silently wrong rollup is observable rather than folklore. It remains
undetectable for an enrichment ADAS does not write, which is one of the reasons those are refused entirely.

The flag SHALL NOT hold back a window. The rollup is already wrong for rows it has aggregated, and no
future refresh corrects them, so the signal is an instruction rather than a description of progress.

#### Scenario: A re-derivation over history is recorded

- **WHEN** an enrichment ADAS materializes has a window of its history re-derived
- **THEN** ADAS records that its history moved, and the pipelines reading it report that they require a
  rebuild

#### Scenario: Forward progress is not mistaken for a re-derivation

- **WHEN** an enrichment is materialized forward in the ordinary way
- **THEN** no rebuild is reported, and only the materialized position advances

#### Scenario: A full replay carrying no window is still recorded

- **GIVEN** an enrichment ADAS materializes, already complete through some position
- **WHEN** it is replayed from the start of its source with no window named
- **THEN** ADAS records that its history moved, and the pipelines reading it report that they require a
  rebuild

#### Scenario: A flagged rollup still refreshes

- **GIVEN** a pipeline flagged as requiring a rebuild
- **WHEN** its next refresh runs
- **THEN** it refreshes normally over its clamped window, and the flag persists until the operator rebuilds
