## ADDED Requirements

### Requirement: Every governed SQL fragment is deterministic

Every governed SQL fragment a pipeline carries — `filter`, `trigger.ready_when.signal`,
`trigger.member_select.prefer_sql`, a `sql` evaluator's output expressions, and an aggregate
declaration's `filter` and per-measure `where` — SHALL be rejected when it calls a function the query
function catalog declares **non-deterministic** (see
[structured-query](../structured-query/spec.md), "The catalog declares which functions are
non-deterministic"). The rejection SHALL be HTTP 422 `pipeline_validation_failed`, the same code
every other gate failure carries, with a message naming the offending function and stating that a
pipeline declaration must be reproducible. It SHALL apply to a nested call as well as a top-level
one, since `date_sub('hour', 1, now())` is no more reproducible than `now()` is.

The check SHALL be driven by the catalog's declared property and SHALL NOT be a list of function
names maintained beside the catalog: a second list would imply the catalog is open — it is not — and
would rot the first time a function was added without remembering the list exists. Adding a
non-deterministic function to the catalog therefore closes this gate around it with no change here.

**The rule is deliberately total, across every fragment and both pipeline kinds**, rather than
applied only where non-reproducibility is provably unsound. A narrower rule is arguable for some
positions — a `filter` selecting only recent rows, a readiness `signal` reading the clock — and each
would need its own account of what a moving predicate does to a cursor that never revisits a window,
to a refresh that recomputes a historical bucket, and to the audit trail that records what a
declaration said rather than what it selected. Until such a position is worked through and admitted
deliberately, the gate refuses the whole family: a declaration is a stored statement of intent that
something else re-runs on its own schedule, so "the same declaration means the same thing every time
it runs" is the property worth keeping, and a caller needing a moving window has the query surfaces,
where it is free.

This narrows nothing that previously worked: no non-deterministic function existed in the closed
catalog before the clock was added, so no stored declaration can contain one.

#### Scenario: A pipeline filter reading the clock is rejected

- **WHEN** a pipeline is registered or enabled with a `filter` of `request_time >= now() - INTERVAL 1 DAY`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  `now` and stating that a pipeline declaration must be reproducible

#### Scenario: A readiness signal reading the clock is rejected

- **WHEN** a `group` pipeline declares a `trigger.ready_when.signal` calling `now()`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A nested non-deterministic call is rejected

- **WHEN** a governed fragment calls `date_sub('hour', 1, now())` rather than `now()` directly
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A deterministic fragment using the offset functions is accepted

- **WHEN** a governed fragment calls `date_trunc('day', request_time)` or
  `date_sub('day', 1, request_time)` — the offset functions over a column, with no clock
- **THEN** the fragment is accepted: it is the clock that is refused, not date arithmetic
