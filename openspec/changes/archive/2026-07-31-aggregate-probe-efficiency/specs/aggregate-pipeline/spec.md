## ADDED Requirements

### Requirement: The window probe's cost is independent of its window's size

The probe that fixes a refresh's window SHALL derive both of the facts it needs — the window's end cursor
tuple, and whether the window filled its configured limit — **inside the engine**, returning a result whose
size does not grow with the window. It SHALL NOT transfer the window's rows to the service in order to
inspect them.

The probe needs one tuple and one boolean. Obtaining them by reading the window and taking its last row
satisfies every functional requirement above while moving up to `aggregate.window-limit` rows across the
wire on every tick of every enabled pipeline — 100,000 by default, against a default tick of one minute.
The window limit exists to bound how much a refresh *recomputes*; it is not a budget for how much the
service *reads*, and using it as one makes the cost of scheduling more pipelines superlinear in a
dimension nobody is measuring.

This matches the property the write half of the same refresh states: the derived values are computed
inside ClickHouse and no row payload crosses the wire. A refresh therefore moves a constant amount of data
regardless of how much input its window covers.

The window this yields SHALL be defined solely by its predicates, ordering and limit — the end tuple and
the more-work-remains flag are the ones that window implies — so where the two facts are computed is
invisible in every observable outcome: `rows_written`, `has_more`, `lag_seconds`, the stored cursor, and
the rows written to the target.

#### Scenario: The probe returns a bounded result regardless of window size

- **WHEN** a refresh probes a window containing far more rows than it will report on
- **THEN** the probe's result size does not grow with the number of rows in the window

#### Scenario: The window does not depend on how its bounds are derived

- **WHEN** a refresh runs over a window holding many rows
- **THEN** the stored cursor, `rows_written`, `has_more` and the rows written to the target are those the
  window's own predicates, ordering and limit define — deriving the bounds in the engine rather than in
  the service is not observable in any of them

#### Scenario: A filled window still reports more work remains

- **WHEN** a probe's window fills its configured limit
- **THEN** `has_more` is true and the next refresh continues from the newly stored cursor, exactly as
  before

#### Scenario: An empty window is still a no-op

- **WHEN** a probe matches no rows
- **THEN** no write is issued and the cursor is unchanged
