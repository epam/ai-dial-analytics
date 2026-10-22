## MODIFIED Requirements

### Requirement: Aggregate spec vocabulary

An `aggregate` pipeline's `spec` SHALL declare `group_by`, `measures`, and `freshness` over its input,
plus an optional `filter`. The declaration SHALL be engine-agnostic: it names no ClickHouse function and
no engine construct.

**`group_by`** SHALL be an ordered, non-empty list of key parts, each either:

- a plain declared column of the input, or
- a **time bucket** `{trunc: {col, unit}, as: <name>}` over a declared `date`/`timestamp` column, with
  `unit` from the closed set `{hour, day, week, month}` (week starting Monday/ISO; month starting at
  midnight on the 1st).

**At most one** time bucket SHALL appear in a `group_by`. A `unit` outside the supported set SHALL be
rejected with HTTP 422 stating the supported units. The set is narrower than the truncation function the
query catalog offers: a bucket finer than an hour would carry about as many keys as the rows it
summarizes, and a refresh costs per dirty key. Widening it further is additive.

A `month` bucket SHALL be a **calendar** month, not a fixed number of days, and SHALL be compiled through
the catalog's calendar truncation rather than its fixed-width binning. The pruning bound a bucketed
refresh applies SHALL step by one calendar month accordingly, inside the statement, so the step past the
last dirty bucket stays in the same calendar the bucketing used.

A `month` bucket SHALL be bindable to a `date` or a `timestamp` target column: a month start is midnight
on the 1st, so either temporal type holds it without loss.

**`measures`** SHALL be a non-empty list of `{name, fn, column?}` entries. `fn` SHALL come from the
aggregate functions of the shared query function catalog (`count`, `sum`, `avg`, `min`, `max`), `column`
SHALL be a declared column of the input and SHALL be omitted only for `count`. A measure SHALL be **one
function over one column**: no expression is permitted inside a measure. Per-row arithmetic SHALL be a
derived column on the input, and ratios or other expressions *over* measures SHALL be computed at query
time over the stored additive measures — never stored, because a ratio cannot be re-aggregated across
buckets.

**`filter`**, when present, SHALL be a predicate in the same bounded SQL-boolean grammar the query
façade and enrichment rules use, over declared and entitled columns of the input. It is optional in
general and **required** when a `group_by` key reads a nullable column — see "A nullable group key requires
a declared filter".

**`freshness`** SHALL declare `{mode}`. Only `periodic` is implemented. `incremental` SHALL be rejected
with HTTP 422 naming the deferred capability rather than silently downgraded, so a caller never believes
it has real-time materialization.

**`trigger`** SHALL be `{kind: "schedule", cron: <expression>}`. Any other trigger kind SHALL be
rejected with HTTP 422.

#### Scenario: A measure carrying an expression is rejected

- **WHEN** a `measures` entry supplies an expression instead of a single column
- **THEN** the request is rejected with HTTP 422 pointing at derived columns and query-time expressions

#### Scenario: An unsupported bucket unit is rejected

- **WHEN** a `group_by` time bucket declares a `unit` outside `{hour, day, week, month}`
- **THEN** the request is rejected with HTTP 422 stating the supported units

#### Scenario: A month bucket produces calendar months

- **WHEN** a pipeline groups by a `month` time bucket over a timestamp column
- **THEN** each bucket starts at UTC midnight on the first of its month, months of differing lengths each
  form exactly one bucket, and the refresh's pruning bound steps by one calendar month

#### Scenario: A month bucket bound to a date target column is accepted

- **WHEN** a `month` bucket's `as` name is bound to a target column declared `date`
- **THEN** the declaration is accepted, since a month start carries no time-of-day to lose

#### Scenario: Two time buckets are rejected

- **WHEN** a `group_by` declares two `trunc` parts
- **THEN** the request is rejected with HTTP 422

#### Scenario: Incremental freshness is rejected, not downgraded

- **WHEN** a pipeline declares `freshness: {mode: "incremental"}`
- **THEN** the request is rejected with HTTP 422 and no pipeline is stored

#### Scenario: An unknown aggregate function is rejected

- **WHEN** a measure names a function outside the catalog's aggregate group
- **THEN** the request is rejected with HTTP 422

### Requirement: Aggregate pipeline errors use the shared error contract

Every `/v1/pipelines` failure SHALL use the `ErrorView` contract with a stable machine-readable `error`
code: an unknown pipeline SHALL be 404; a target that already has a pipeline, refreshing a disabled
pipeline, and editing an enabled pipeline's declaration SHALL each be 409; and a spec or governance
violation SHALL be 422 (403 for a sensitive-column refusal). ClickHouse and driver internals SHALL NOT be
echoed; a ClickHouse outage during preview or refresh SHALL surface as 503.

A preview refused for exceeding its resource budget SHALL carry its **own** code, distinct from both the
validation failure and the outage. It is not a 503: a 503 promises a transient condition that retrying
will clear, and this one is deterministic — the identical request will fail identically until either the
declaration or the budget changes. It is reported as 422, because the actionable fix is on the caller's
side of the contract (coarsen the grain, add a membership filter, or have the budget raised), and the
message SHALL say which budget was exceeded so an operator can tell an expensive declaration from a
broken one.

A rejection the capability's own rules define SHALL be reported by those rules rather than by request
deserialization. A value that is part of the declaration's vocabulary but not implemented — an unsupported
`kind`, `incremental` freshness, a non-`schedule` `trigger.kind`, a bucket `unit` outside the supported set
— SHALL be accepted by the binding and refused by validation, so the response is the 422 above with a
message naming the alternatives, never the generic 400 an unbindable value produces.

Every such code SHALL identify *this* capability. Validation shared with other capabilities — notably the
`filter` grammar, which the enrichment registry also uses — SHALL be re-attributed to the pipeline code
rather than reported under the sharing capability's, since a code is what a client switches on and a
borrowed one points at an object that does not exist. A sensitive-column refusal keeps its 403 and is not
flattened into a validation failure.

#### Scenario: Unknown pipeline

- **WHEN** any `/v1/pipelines/{name}` endpoint is called for a name not in the registry
- **THEN** the response is 404 with a stable `error` code

#### Scenario: A declared-but-unimplemented value reports under this capability's code

- **WHEN** `POST /v1/pipelines` names an unimplemented `kind`, or `incremental` freshness
- **THEN** the response is 422 carrying this capability's `error` code and a message naming the implemented
  kinds / the deferred capability, not a generic deserialization 400

#### Scenario: An invalid filter reports under the pipeline error code

- **WHEN** a pipeline is registered with a `filter` that does not parse under the bounded grammar
- **THEN** the response is 422 carrying the pipeline capability's own `error` code, not the enrichment
  registry's

#### Scenario: ClickHouse unavailable during preview

- **WHEN** `POST /v1/pipelines/{name}/preview` is called while ClickHouse is unreachable
- **THEN** the response is 503 with the shared contract and no raw driver text

#### Scenario: A budget refusal is distinguishable from an outage

- **WHEN** a preview is refused for exceeding its resource budget
- **THEN** the response is 422 with the budget-exceeded code and a message naming the exceeded budget,
  never the 503 `clickhouse_unavailable` an unreachable server produces
