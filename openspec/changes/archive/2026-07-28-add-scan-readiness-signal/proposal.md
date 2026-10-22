## Why

ADAS accepts a group rule whose readiness is `ready_when.signal` — it validates the predicate under the
bounded Calcite grammar, entitlement-checks its columns, and stores it — but the enrichment runner
**refuses every such rule at sync**, so a registered signal rule silently never runs. The reason is on
the ADAS side of the wire: the scan projects the *raw columns the predicate reads* rather than the
predicate's result, so honouring `signal` would mean re-implementing ADAS's bounded grammar in a second
codebase, whose divergence would silently mark the wrong groups ready rather than failing loudly. The
runner declined to do that, correctly.

ADAS already has everything needed to answer the question itself: the scan analyses the signal through
the same `FilterSqlValidator` it uses for `filter_sql`, and it already translates a `FilterNode` into a
jOOQ condition for the filter. The verdict is one projected boolean away.

Shipping the raw columns is also worse than shipping the verdict for a second reason: the values reach a
caller that has no use for them beyond re-deriving the boolean, and a readiness signal over a body or
a user identifier is exactly the kind of column ADAS otherwise works to keep inside ClickHouse.

## What Changes

- **A group rule's scan projects `__signal`**, a computed boolean per row: does this row satisfy the
  rule's `ready_when.signal`? Absent entirely for a rule that declares no signal, and for every
  row-grain rule.
- **The raw columns the signal reads are no longer projected** (unless a binding or the filter
  independently needs them). The verdict replaces the evidence.
- **The sensitive-column posture is unchanged.** The scan's re-check now covers the signal's columns even
  though they are not returned: the guard rejects a sensitive column being *read or derived*, and
  computing the verdict server-side must not become a way around it.
- `__signal` is normalized to a JSON boolean. ClickHouse answers a predicate with `UInt8` (nullable when
  the predicate touches a nullable column), and a null verdict fails closed to `false` — unknown is not
  a signal.

Out of scope: any change to registration (the predicate is already validated and entitlement-checked
there); how a caller *uses* the verdict, which is the runner's readiness sweep; and `groups:fetch`,
whose membership filter is a different predicate over a different question.

## Capabilities

### Modified Capabilities

- `enrichment-runtime`: "Scan projection and version isolation" — a group rule's page carries the
  computed `__signal` verdict instead of the signal's raw columns; the sensitive re-check covers the
  columns a governed predicate derives from.

## Impact

**Code:** `ScanService` — `SIGNAL_COLUMN` constant, the signal analysis retained as a `FilterAnalysis`
(rather than reduced to its columns), `buildScan` takes the signal's `FilterNode` and projects
`DSL.field(condition)`, the sensitive guard runs over `projection ∪ signal columns`, and `toPage`
normalizes the verdict to a boolean. No other class changes.

**No migration, no config, no new endpoint, no DTO change** — scan rows are already open maps.

**Compatibility: breaking for a caller that reads the signal's raw columns from a scan page.** The only
such caller is the enrichment runner, which refuses signal rules today and therefore reads no such
column; the paired runner change consumes `__signal`. A group rule without a signal, and every row-grain
rule, is byte-identical to before.
