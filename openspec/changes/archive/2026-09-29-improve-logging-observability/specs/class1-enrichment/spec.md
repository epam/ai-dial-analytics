## ADDED Requirements

### Requirement: A materialize call reports its outcome in one log line

Each materialize call that reaches its window probe SHALL log one INFO outcome line. The line names the
pipeline and its generation, and gives an outcome of `written`, `drained`, `withheld` or `clamped_empty`. It
also gives the rows written, whether the write narrowed, `has_more`, `lag_s`, and the durations of the first
probe (`probe_ms`), all re-probes together (`reprobe_ms`), all write attempts together (`write_ms`) and the
whole call (`total_ms`). When the freshness clamp bounded the window, the line SHALL name the enrichment
that bound it and the bound tuple: the version half as the epoch-millisecond position, and the identity half
as a short digest rather than its value, because the identity is a source column value the service cannot
assume is free of user data. It SHALL give the ClickHouse `query_id` of the last write statement, or of the
last probe when nothing was written.

- `written`: rows were written.
- `drained`: the window was empty and the drained horizon was recorded. A call carrying `from`/`to` that
  finds its window empty also reports `drained`, although such a call records no horizon.
- `clamped_empty`: the clamp emptied the window, so no horizon was recorded.
- `withheld`: a joined enrichment has no usable position, so no statement ran. This line reports a zero
  `total_ms`.

The line SHALL carry no row values, no filter literals and no SQL.

`drained`, `withheld` and `clamped_empty` SHALL be logged only when a pipeline's outcome differs from its
previous outcome on the same replica. A caught-up or blocked pipeline is called on every runner cadence,
and the line exists to show changes, not to repeat a steady state. `written` SHALL be logged on every call.
A call that fails SHALL NOT log an outcome line. Its failure is reported by the error handler.

A stall caused by a missing upstream position is otherwise invisible in the logs. Before this requirement,
none of the empty paths logged anything, and the only durations were trace spans, which exist only while
trace export is on.

#### Scenario: A written window is logged with its stage durations

- **WHEN** a materialize call writes rows after narrowing once
- **THEN** one INFO line reports `outcome=written`, the rows, `narrowed=true`, `has_more=true`, and
  `probe_ms`, `reprobe_ms`, `write_ms` and `total_ms`, with the write's `query_id`

#### Scenario: A withheld pipeline is logged when it becomes withheld

- **WHEN** a pipeline whose joined enrichment has no usable position is materialized on several calls in a
  row
- **THEN** only the first of those calls logs `outcome=withheld`, naming the enrichment, and the next call
  with a different outcome logs that outcome

#### Scenario: A clamped empty window is distinguished from a drained one

- **WHEN** the clamp empties the window while rows above the bound match
- **THEN** the outcome line reports `clamped_empty` with the bounding enrichment and tuple, not `drained`
- **AND** the tuple's identity half appears only as a digest, never as the source value

### Requirement: A materialize call nearing its budget is warned about

A materialize call whose `total_ms` passes half of the configured `pipeline.budget.timeout` SHALL log one
WARN, whether it succeeds or not. The WARN names the pipeline, `probe_ms`, `reprobe_ms`, `write_ms`,
`total_ms` and the configured timeout. The threshold is derived from the budget rather than configured
separately. Under the single per-call deadline, the budget is the call's upper bound, and half of it is the
point at which one more slow statement would reach the refusal.

#### Scenario: A slow successful call warns with its stage split

- **WHEN** a materialize call succeeds after more than half of `pipeline.budget.timeout`
- **THEN** one WARN names the pipeline, the three stage durations, the total and the configured timeout

#### Scenario: A fast call does not warn

- **WHEN** a materialize call completes in less than half of `pipeline.budget.timeout`
- **THEN** no soft-deadline WARN is logged

### Requirement: A discarded materialized position is logged

The service SHALL log one INFO line when recording a materialize discards a materialized position or
drained horizon because the mark was stored under a different generation. The line names the pipeline, the
stored generation and the current generation. The line SHALL be logged only when a stored generation existed. A
pipeline's first mark ever claims a generation where none was stored, and that is not a discard. The stored
generation SHALL be read by the same statement that discards the mark, with no separate read that a
concurrent writer could interleave with.

This line answers "why did the rollup freeze": a discarded position holds every dependent rollup's
freshness clamp until the pipeline materializes again under its new generation.

#### Scenario: A mark under an older generation is discarded and logged

- **WHEN** a materialize records its position while the stored mark carries an older generation
- **THEN** one INFO line reports the pipeline and `stored_gen -> gen`

#### Scenario: The first mark ever is not logged as a discard

- **WHEN** a pipeline records its first materialized position, with no generation stored
- **THEN** no discard line is logged
