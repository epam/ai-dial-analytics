## MODIFIED Requirements

### Requirement: Probe-then-write window semantics

A materialize call SHALL choose its window **before** it executes it, in two statements. First a
two-column probe SHALL select the source's `(version, identity)` tuples matching the pipeline's filter,
the cursor keyset, the safety-lag, any `from`/`to` bound, and the enrichment freshness clamp below,
ordered by that tuple and limited to `limit`; the **last** returned tuple SHALL be the window end, and
`hasMore` SHALL be true exactly when the probe filled its limit. Second, a single `INSERT … SELECT` SHALL
write the derived values for source rows in `(cursorFrom, windowEnd]` only. Both statements SHALL read
with `final=1`, and each SHALL emit the grain join for the enrichments its **own** clauses reference and
no others — so a probe whose filter names no enrichment stays unjoined even when the write's expressions
join one.

**A probe is two-column in what it returns, not in what it reads, and SHALL NOT be assumed cheap.** A
probe whose filter names an enrichment column emits that grain join like any other statement, and the
join's derived table projects whatever the filter addresses — for an `object` column, the addressed
literal paths where every reference names one, and otherwise the whole column. Its cost is therefore the
cost of that projection across the parts the restriction admits, which can exceed the write's: the write
projects the same columns over the same rows, and the probe returns two columns only after reading them.
Both statements SHALL run under the same resource ceiling, for the reason
[enrichment-runtime](../enrichment-runtime/spec.md) gives: a bounded probe gating an unbounded write
bounds the wrong statement.

**The enrichment freshness clamp.** When the pipeline's statements join one or more enrichments, the window
SHALL additionally be bounded by the position through which each of those enrichments has been
materialized, taking the lowest when several are joined (see
[enrichment-runtime](../enrichment-runtime/spec.md), which carries the same clamp on the scan window).
Class-1 is the strict case for this bound: the write derives one value per source row and records the
position it wrote through, so a row read while its enrichment cell is still absent is stored with a value
derived from that absence and is never revisited — a permanently wrong value rather than a missing one.
An enrichment that contributes no bound SHALL NOT hold the window back, and a pipeline joining no enrichment
SHALL be unclamped and its window unchanged.

Because the window is fixed before the write, a call that times out, fails, or is retried SHALL
re-execute an **identical** window. A single ClickHouse `INSERT` is atomic and the target enrichment
is a `ReplacingMergeTree` collapsing by grain, so re-running a window SHALL be a no-op in effect: the
`final=1` result is unchanged. This is the exactly-once *effect* guarantee at window granularity, the
counterpart of the row path's per-grain guarantee.

A write narrowed by the resource ceiling SHALL preserve that guarantee unchanged. The narrowed window is a
**prefix** of the fixed one, the returned cursor names its end, and the rows above it are left for the next
call rather than skipped — so the sequence of windows still partitions the source in cursor order, and each
is still re-executable identically. `hasMore` SHALL be true whenever the write narrowed, whatever the probe
reported, because rows below the probed window end remain unwritten.

An empty probe SHALL write nothing, return `rowsWritten: 0`, `hasMore: false`, and echo the input
cursor unchanged, so a caller that advances only on success never skips a window. A window emptied by the
freshness clamp SHALL be reported this way rather than as a failure.

An empty probe ordinarily also records the pipeline's **drained horizon** — "nothing matching below this
instant minus the safety lag" — which is what releases a filtered pipeline's dependents from waiting on rows
nothing will ever enrich. That horizon SHALL NOT be recorded when the clamp is what emptied the window.
Under a clamp the claim can be false: rows above the bound match and were withheld, and recording the
horizon would let anything clamping on *this* enrichment advance past rows it never enriched — the same
unbounded read the clamp denied, one level down and equally unrecoverable.

Whether the clamp withheld anything SHALL be **established, not inferred** — by re-probing the same window
with the clamp lifted, one row being enough to answer it. Inferring it from where the bound sits relative to
the safety-lag horizon is wrong in the common direction: a source whose newest row is hours old puts every
bound below that instant, so a clamped pipeline would never record a drained horizon again even when genuinely
caught up, silently withdrawing the mechanism that releases a *filtered* pipeline's dependents from waiting on
rows nothing will ever enrich. A clamped pipeline that really has nothing left below its bound SHALL record the
horizon exactly as an unclamped one does.

The write SHALL stamp the provenance columns the target physically carries — `evaluator_version` with
the pipeline's resolved pinned version and `enriched_at` with the write time — and SHALL skip any provenance
column the target lacks, matching the write-back path's best-effort lineage. It SHALL also skip every
provenance column a Class-1 pipeline cannot describe, each for a structural reason rather than a missing
value: `model` (a `sql` evaluator makes no model call), `group_version` and `truncated` (governance
forbids a `sql` evaluator the `group` trigger, and a set-based window is never trimmed). The version
column `_updated_at` SHALL be stamped by ClickHouse, never by ADAS.

#### Scenario: Re-running an identical call leaves the result unchanged

- **WHEN** the same materialize call is issued twice against an unchanged source
- **THEN** the second call re-executes the same window and a subsequent `final=1` read returns the
  same values, with one row per grain key

#### Scenario: An interrupted call leaves the cursor unmoved

- **WHEN** a materialize call fails or times out
- **THEN** no cursor advance is reported to the caller, and retrying re-executes the same window

#### Scenario: An empty window echoes the cursor

- **WHEN** no source rows match above the cursor and below the safety-lag horizon
- **THEN** the response is `rowsWritten: 0`, `hasMore: false`, and `nextCursor` equal to the input cursor

#### Scenario: The window is clamped to a referenced enrichment's position

- **WHEN** a `type=sql` pipeline whose expressions read an enrichment column is materialized while source
  rows exist above that enrichment's recorded materialize position
- **THEN** those rows are outside the window, no value is written for them, the returned cursor does not
  advance past that position, and a later call writes them once the enrichment has reached them

#### Scenario: A window emptied by the clamp echoes the cursor

- **WHEN** the referenced enrichment has been materialized no further than the pipeline's current cursor
- **THEN** the response is `rowsWritten: 0`, `hasMore: false`, and `nextCursor` equal to the input
  cursor, rather than a failure

#### Scenario: A window emptied by the clamp does not record the pipeline as drained

- **WHEN** the same call returns its empty window
- **THEN** the pipeline's drained horizon is left unmoved, so a reader clamping on this pipeline's target
  enrichment is not released past the source rows the clamp withheld

#### Scenario: A clamped pipeline that is genuinely caught up still records the horizon

- **WHEN** a clamped pipeline's window is empty and no row matches above its cursor even with the clamp lifted
- **THEN** the drained horizon is recorded, exactly as for an unclamped pipeline, because the emptiness is the
  absence of matching rows rather than the clamp

#### Scenario: A pipeline joining no enrichment is unclamped

- **WHEN** a `type=sql` pipeline whose expressions read only its read source's own columns is materialized
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A probe naming no enrichment stays unjoined

- **WHEN** a `type=sql` pipeline's `filter` references no enrichment column while its output expressions
  reference one
- **THEN** the probe statement emits no join and the `INSERT … SELECT` emits the grain join for that
  enrichment

#### Scenario: A narrowed write leaves the skipped rows above the cursor

- **WHEN** a write over the probed window is refused by the resource ceiling and succeeds over half of it
- **THEN** the returned cursor names the narrowed window's end, `hasMore` is true, and a subsequent call
  writes the remaining rows

#### Scenario: A probe whose filter names an enrichment is bounded like any other read

- **WHEN** a `type=sql` pipeline's `filter` references an enrichment column and the probe's projection
  exceeds the memory ceiling
- **THEN** the probe is refused with HTTP 422 `pipeline_read_budget_exceeded` and the cursor does not
  advance

#### Scenario: Provenance absent from the target is skipped, not failed

- **WHEN** the target enrichment does not physically carry `enriched_at` or `evaluator_version`
- **THEN** those columns are omitted from the write and the write still succeeds

#### Scenario: Provenance a sql pipeline cannot describe is skipped

- **WHEN** the target enrichment physically carries `model`, `group_version`, or `truncated`
- **THEN** those columns are omitted from the materialize write and the write still succeeds
