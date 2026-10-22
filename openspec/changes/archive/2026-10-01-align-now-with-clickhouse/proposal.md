## Why

The `now()` contract promises an invariant ADAS never had: "one execution observes one instant — every
`now()` call in a statement yields the same value". ADAS renders `now()` as ClickHouse `now64(3, 'UTC')`
and coordinates nothing, and ClickHouse does not behave that way. Measured on ClickHouse 26.3, each
`now64` **call site** is one value across every row of the statement, but separate call sites are
evaluated separately: two of them in one row differed in 7 of 300 runs, identically with and without
`FINAL`. The test that pins the false invariant
(`StructuredQueryExecutionIntegrationTest.oneExecutionObservesOneInstant`) therefore fails ~2% of runs
(one failing CI run: column `a` = `[.917Z, .917Z]`, column `b` = `[.918Z, .918Z]`).

The project directive is that a ClickHouse query behaves exactly as in ClickHouse, so the fix is to the
contract, not the engine: ADAS keeps returning what ClickHouse returns, and the spec, the test and the
prose move to say so. No instant is pinned in ADAS (no literal, no injected clock, no query parameter).

## What Changes

- `structured-query` "Relative time expressions": replace "a statement SHALL observe one instant" with
  ClickHouse's semantics — one value per `now()` call site per statement, constant across every row;
  separate call sites are independent and may differ by a clock tick. State the consequence for
  callers: a window written with two `now()` calls (`request_time >= now() - INTERVAL 1 HOUR AND
  request_time < now()`) may see bounds a millisecond apart, exactly as the same statement does in
  ClickHouse; and an `include_total` `count(*)` is a separate statement with its own `now()`. The
  aggregate rule ("a projected `now()` needs no `group_by` entry") is kept, re-grounded on the per-call-site
  constant. The scenario "One execution observes one instant" is replaced by one asserting the real
  contract.
- `sql-query-translation` "The clock has one DSL name and three SQL spellings": state that lowering the
  three spellings to one function name does not merge call sites — `now()` and `CURRENT_TIMESTAMP` in
  one statement are two call sites, each evaluated on its own.
- `structured-query` "The catalog declares which functions are non-deterministic": checked for
  consistency; it carries no single-instant claim and is relocated unchanged (below).
- **Spec housekeeping (in scope, verbatim move):** the four requirements that sit after
  `## Implementation notes` — `Relative time expressions` and `The catalog declares which functions are
  non-deterministic` in `structured-query`, `The clock has one DSL name and three SQL spellings` and
  `Interval arithmetic translates to the offset functions` in `sql-query-translation` — are moved, text
  unchanged, to the end of each spec's `## Requirements` section. openspec cannot parse a requirement
  outside that section, so without the move the MODIFIED deltas cannot be archived; it is the simplest
  way to make archive work, and it also turns the pre-existing `openspec validate --specs` failure green.
- Test: rewrite `oneExecutionObservesOneInstant` (renamed) to assert each call site is constant across
  rows, plus a bounded |a − b| tolerance documented as a tolerance, not a contract.
- Javadoc of `ExprTranslator.now` and the `now` description in the function catalog
  (`GET /v1/queries/functions`, `adas_list_query_functions`) drop the single-instant claim.
- `docs/sql-reference.md`: same correction in "The clock and relative windows" and the function table.

No runtime behaviour changes: the rendered SQL (`now64(3, 'UTC')`) is identical. The only
API-visible difference is the wording of the `now` catalog entry's description string.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query`: "Relative time expressions" — the clock is one value per call site, not per
  execution; the consequence for two-bound windows and `include_total` is spelled out.
- `sql-query-translation`: "The clock has one DSL name and three SQL spellings" — the three spellings
  share a name, not an evaluation.

## Impact

- Specs: `openspec/specs/structured-query/spec.md`, `openspec/specs/sql-query-translation/spec.md`
  (wording + relocation of four blocks).
- Code: `ExprTranslator` (Javadoc only), `QueryFunctionCatalog` (`now` description string only).
- Tests: `StructuredQueryExecutionIntegrationTest` (one test rewritten and renamed).
- Docs: `docs/sql-reference.md`.
- Surfaces: `GET /v1/queries/functions` description text for `now`; no endpoint, schema, config key,
  error code or rendered SQL changes. No `docs/configuration.md` change.
