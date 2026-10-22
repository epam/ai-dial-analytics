## ADDED Requirements

### Requirement: A materialize records the position it wrote through

A materialize that writes a window SHALL record, against the rule it ran for, the position it wrote
through — the same `(version, identity)` tuple it returns as the caller's next cursor. The record SHALL be
monotonic: a call SHALL NOT lower it.

ADAS is the writer on this path and the write is synchronous, so this is a fact ADAS observes rather than
one it is told. When the `INSERT … SELECT` returns, the derived rows are in ClickHouse and the probed
window's end is the position through which the enrichment is complete. It is recorded because a consumer
that aggregates the enrichment alongside its source needs to know how far it may safely read, and the
caller's own cursor cannot answer that: the caller persists a position it was handed, and for the row path
the equivalent position marks work that has been queued rather than written.

A materialize that finds no rows SHALL record instead that the rule was **drained**, as of the moment it
looked. That is a distinct fact from the position: a rule declaring a filter advances its position only to
its last matching source row, so without the drained signal a consumer could not tell "there is more to
come" from "there is nothing left that this rule will ever cover".

The position SHALL be recorded only where a window was actually probed and written. An empty window echoes
the **caller's** cursor by contract, and recording that would persist what the caller asserted rather than
what ADAS observed.

A materialize carrying a `from`/`to` bound SHALL record neither the position nor the drained signal, and
SHALL instead record that the rule's history was re-derived, as of that moment. Such a call rewrites values
for source rows that consumers may already have read, which is a different fact from progress and would
otherwise drag the recorded position backwards.

A materialize that writes a window ending **at or below the position the rule is already complete through**
SHALL also record that the rule's history was re-derived, whether or not it carried a bound. The bound is
not a sufficient discriminator on its own: a **full replay** carries a cursor and no window, so no field of
the request separates it from a live tick — and it is the default shape of a backfill, and the way an
evaluator-expression bump is re-applied to history. What separates the two is the effect rather than the
request. A live tick writes rows the rule has never been complete through; a replay rewrites rows it has,
and "complete through" is exactly the position a dependent consumer was allowed to read past — the later of
what was written and where the rule was last found drained.

This SHALL be evaluated against the position as it stood **before** the call's own record was applied, and
a rule whose mark is absent (never written, or recorded under a superseded generation) SHALL NOT be treated
as re-deriving: nothing was declared complete, so nothing can have been read past. One false positive is
accepted rather than designed away — a live tick whose response was lost re-executes an identical window on
the next fire, which is indistinguishable from a replay of one page and is reported as a re-derivation. A
rare spurious rebuild instruction is the safe direction against a systematic miss on the default backfill
shape.

The recorded **position and drained horizon** SHALL be qualified by the rule's `generation` at the time they
were written, and SHALL be treated as absent once that generation has moved. A rule mutation can change what
a recorded position means, and one case makes it meaningless rather than merely stale: repointing the read
source's `version_column` changes the axis the tuple is expressed on. That repoint already bumps the
generation of every rule reading the source, so the qualification is exact rather than approximate.

The recorded **re-derivation** SHALL NOT be qualified that way, and SHALL survive a rule mutation. It is a
fact about values that were already published to consumers, not about a position on an axis: editing the
rule does not make a rollup that already aggregated the re-derived values correct again, so discarding the
record on a mutation would clear a warning that is still owed.

#### Scenario: A written window records its end position

- **WHEN** a materialize call writes a window
- **THEN** the position it wrote through is recorded against the rule, and a later call that writes a
  further window advances it

#### Scenario: An empty window records drained, not a position

- **WHEN** a materialize call's probe finds no rows above the cursor and below the safety-lag horizon
- **THEN** the rule is recorded as drained as of that moment, and the echoed cursor is not recorded as a
  position

#### Scenario: A backfill records a re-derivation instead of progress

- **WHEN** a materialize call carries a `from`/`to` bound
- **THEN** the rule's history is recorded as re-derived, and neither the position nor the drained signal
  is recorded

#### Scenario: An unwindowed replay is recognised by what it wrote

- **GIVEN** a rule already complete through some position
- **WHEN** a materialize call carrying a cursor and no `from`/`to` bound writes a window ending at or below
  that position
- **THEN** the rule's history is recorded as re-derived, and the recorded position is unchanged

#### Scenario: A recorded position does not go backwards

- **WHEN** a materialize call is replayed at an earlier cursor and writes an already-written window
- **THEN** the recorded position is unchanged rather than lowered, and the re-derivation is recorded
  alongside it

#### Scenario: A rule mutation invalidates the record

- **GIVEN** a rule with a recorded position
- **WHEN** the rule is mutated, or its read source's `version_column` is repointed
- **THEN** the record is treated as absent until the next materialize establishes it again
