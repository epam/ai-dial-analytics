## ADDED Requirements

### Requirement: Pipeline registry object

The system SHALL maintain a Postgres-backed registry of **pipelines** — declared row-producing
processes, each writing exactly one **target** table. A pipeline SHALL carry a `name` (the registry key
and the `{name}` in the API path), a `kind`, the `target` table it writes, its `inputs`, a kind-specific
`spec`, a `trigger`, and an `enabled` flag. A pipeline is **data, not code**: registering one deploys
nothing, and the platform's generic executor runs it.

`kind` SHALL be a closed set whose only implemented member in this capability is `aggregate`. The
discriminator SHALL be persisted so the `external` and `enrich` kinds can converge onto this object
later; a request naming an unimplemented kind SHALL be rejected with HTTP 422.

**One pipeline per target** (D-TR-1): a target table SHALL have at most one pipeline, and registering a
second pipeline for the same target SHALL be rejected with HTTP 409. Because an `aggregate` pipeline
targets a `source` while an enrichment `rule` targets an `enrichment`, the two registries can never
contend for the same table.

`inputs` SHALL name exactly one source table in this capability (multi-source input is post-v1). The
input SHALL NOT be the pipeline's own target — a pipeline that reads what it writes SHALL be rejected
with HTTP 422.

Pipeline registration, mutation, and deletion SHALL require the `FULL_ADMIN` authority in `oidc` mode;
per-table access roles SHALL NOT authorize them.

#### Scenario: Register a pipeline

- **WHEN** `POST /v1/pipelines` is called with a valid aggregate pipeline
- **THEN** the response is 201 with the stored pipeline, `enabled: false`

#### Scenario: A second pipeline for the same target is rejected

- **WHEN** `POST /v1/pipelines` names a `target` that already has a pipeline
- **THEN** the request is rejected with HTTP 409 and nothing is persisted

#### Scenario: An unimplemented kind is rejected

- **WHEN** `POST /v1/pipelines` is called with `kind` other than `aggregate`
- **THEN** the request is rejected with HTTP 422 naming the implemented kinds

#### Scenario: A pipeline reading its own target is rejected

- **WHEN** `POST /v1/pipelines` names the same table in `inputs` and `target`
- **THEN** the request is rejected with HTTP 422 (a cycle)

#### Scenario: Pipeline management requires admin

- **WHEN** a non-`FULL_ADMIN` caller invokes any mutating `/v1/pipelines` endpoint in `oidc` mode
- **THEN** the request is rejected with HTTP 403

### Requirement: Aggregate spec vocabulary

An `aggregate` pipeline's `spec` SHALL declare `group_by`, `measures`, and `freshness` over its input,
plus an optional `filter`. The declaration SHALL be engine-agnostic: it names no ClickHouse function and
no engine construct.

**`group_by`** SHALL be an ordered, non-empty list of key parts, each either:

- a plain declared column of the input, or
- a **time bucket** `{trunc: {col, unit}, as: <name>}` over a declared `date`/`timestamp` column, with
  `unit` from the closed set `{hour, day, week}` (week starting Monday/ISO).

**At most one** time bucket SHALL appear in a `group_by`. A `unit` outside the set — notably the calendar
`month` the design vocabulary also lists — SHALL be rejected with HTTP 422, because the available
truncation is fixed-width and cannot express a calendar month.

**`measures`** SHALL be a non-empty list of `{name, fn, column?}` entries. `fn` SHALL come from the
aggregate functions of the shared query function catalog (`count`, `sum`, `avg`, `min`, `max`), `column`
SHALL be a declared column of the input and SHALL be omitted only for `count`. A measure SHALL be **one
function over one column**: no expression is permitted inside a measure. Per-row arithmetic SHALL be a
derived column on the input, and ratios or other expressions *over* measures SHALL be computed at query
time over the stored additive measures — never stored, because a ratio cannot be re-aggregated across
buckets.

**`filter`**, when present, SHALL be a predicate in the same bounded SQL-boolean grammar the query
façade and enrichment rules use, over declared and entitled columns of the input.

**`freshness`** SHALL declare `{mode}`. Only `periodic` is implemented. `incremental` SHALL be rejected
with HTTP 422 naming the deferred capability rather than silently downgraded, so a caller never believes
it has real-time materialization.

**`trigger`** SHALL be `{kind: "schedule", cron: <expression>}`. Any other trigger kind SHALL be
rejected with HTTP 422.

#### Scenario: A measure carrying an expression is rejected

- **WHEN** a `measures` entry supplies an expression instead of a single column
- **THEN** the request is rejected with HTTP 422 pointing at derived columns and query-time expressions

#### Scenario: An unsupported bucket unit is rejected

- **WHEN** a `group_by` time bucket declares `unit: "month"`
- **THEN** the request is rejected with HTTP 422 stating the supported units

#### Scenario: Two time buckets are rejected

- **WHEN** a `group_by` declares two `trunc` parts
- **THEN** the request is rejected with HTTP 422

#### Scenario: Incremental freshness is rejected, not downgraded

- **WHEN** a pipeline declares `freshness: {mode: "incremental"}`
- **THEN** the request is rejected with HTTP 422 and no pipeline is stored

#### Scenario: An unknown aggregate function is rejected

- **WHEN** a measure names a function outside the catalog's aggregate group
- **THEN** the request is rejected with HTTP 422

### Requirement: Target shape governance

The target of an `aggregate` pipeline SHALL be an existing, materialized (`ACTIVE`) `source` table whose
write discipline is `upsert_by_key`. A target that is unknown, an `enrichment`, not yet materialized, or
written `append` SHALL be rejected with HTTP 422 — an `append` target would accumulate one row per
refresh instead of replacing the recomputed key, silently multiplying every measure.

The target's `ordering_key` SHALL equal the pipeline's `group_by` output names, **in order**. The rollup's
grain is therefore not declared twice and cannot drift from the pipeline that fills it.

Every `group_by` output name and every `measures[].name` SHALL resolve to a declared column of the
target, and the column's declared type SHALL accept the part's or function's result type. A target column
that no `group_by` part or measure writes SHALL be rejected, since it could only ever hold a default.

A `system`-owned target SHALL reject pipeline registration, consistent with every other mutation of a
system table.

#### Scenario: An append target is rejected

- **WHEN** a pipeline's `target` is a `source` whose write discipline is `append`
- **THEN** the request is rejected with HTTP 422 explaining that a rollup target must be
  `upsert_by_key`

#### Scenario: A target whose ordering key differs from group_by is rejected

- **WHEN** the target's `ordering_key` is `(day, deployment)` and `group_by` yields
  `(deployment, day)`
- **THEN** the request is rejected with HTTP 422 reporting both key lists

#### Scenario: A measure with no matching target column is rejected

- **WHEN** a `measures` entry names `cost` and the target declares no `cost` column
- **THEN** the request is rejected with HTTP 422

#### Scenario: A type-incompatible target column is rejected

- **WHEN** a `count` measure is bound to a target column declared `string`
- **THEN** the request is rejected with HTTP 422

#### Scenario: An unwritten target column is rejected

- **WHEN** the target declares a column that no group key or measure writes
- **THEN** the request is rejected with HTTP 422

### Requirement: Sensitive-column governance on the aggregate path

The system SHALL apply the per-column `sensitive` rule to every input column a pipeline references — in
`filter`, `group_by`, and `measures` — at registration **and** again at execution, against the acting
identity. A pipeline referencing a sensitive input column SHALL be refused for a caller not entitled to
it, with HTTP 403.

Without this, a rollup would be a laundering path: `min`/`max` over a sensitive column, or a sensitive
column used as a group key, would copy protected values into a target column carrying no `sensitive`
flag, readable by anyone who can read the rollup.

#### Scenario: A measure over a sensitive column is refused for a non-entitled caller

- **WHEN** a non-entitled caller registers a pipeline whose measure reads a `sensitive` input column
- **THEN** the request is rejected with HTTP 403 and no pipeline is stored

#### Scenario: A sensitive group key is refused for a non-entitled caller

- **WHEN** a non-entitled caller registers a pipeline grouping by a `sensitive` input column
- **THEN** the request is rejected with HTTP 403

#### Scenario: Governance is re-applied at execution

- **WHEN** a column referenced by a stored pipeline becomes `sensitive` after registration and a refresh
  runs on behalf of a non-entitled caller
- **THEN** the refresh is refused rather than writing derived values from that column

### Requirement: Register, preview, enable lifecycle

A pipeline SHALL be registered `enabled: false` and SHALL NOT run until explicitly enabled through
`PATCH /v1/pipelines/{name}`. Only a `disabled` pipeline's `spec` SHALL be mutable; a spec edit SHALL be
re-validated in full, exactly as at registration.

The system SHALL expose `POST /v1/pipelines/{name}/preview` — the **dry-run gate** — returning the
compiled statement the refresh would execute plus a bounded sample of its `SELECT` half. Preview SHALL
NOT write to the target and SHALL be permitted on a disabled pipeline, so the declaration can be checked
before it is trusted.

`DELETE /v1/pipelines/{name}` SHALL remove the pipeline and leave the target table and its rows intact:
a rollup that stops being refreshed is stale, not invalid.

#### Scenario: A registered pipeline does not run until enabled

- **WHEN** a pipeline is registered and the scheduler tick fires
- **THEN** no statement is executed against the target while `enabled` is false

#### Scenario: Preview returns the statement and a sample without writing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called on a disabled pipeline
- **THEN** the response carries the compiled statement and a bounded sample of rows, and the target
  row count is unchanged

#### Scenario: A spec edit is fully re-validated

- **WHEN** `PATCH /v1/pipelines/{name}` changes `group_by` so it no longer matches the target's
  `ordering_key`
- **THEN** the request is rejected with HTTP 422 and the stored spec is unchanged

#### Scenario: Deleting a pipeline keeps the rollup

- **WHEN** `DELETE /v1/pipelines/{name}` is called
- **THEN** the pipeline is removed, and the target table and its rows still exist and remain queryable

### Requirement: Scheduled refresh recomputes every affected group key in full

The system SHALL refresh an enabled `aggregate` pipeline on its declared cron, and on demand via
`POST /v1/pipelines/{name}/refresh`. One refresh SHALL run as two statements:

1. a **probe** that fixes the window end over the input's cursor tuple (its version and identity
   columns), applying the pipeline's `filter`, the configured safety lag, and a keyset seek past the
   stored cursor; then
2. a single `INSERT … SELECT` on the writer credential that recomputes, **in full**, every group key the
   probed window touched — the set of dirty keys being a sub-select over exactly that window — and stamps
   the target's version column.

Recomputing each affected key in full SHALL be the contract, not an optimization detail. The target
collapses by key, so a write **replaces** rather than accumulates: a partial per-window aggregate would
overwrite a bucket's running total with only the newest slice. Full recomputation of the dirty keys is
the only shape that is correct under `upsert_by_key`, and it gives two further properties:

- **Idempotence** — a retried or duplicated run re-probes the same window, derives the same key set, and
  writes the same values, which the target collapses. A refresh that fails or times out therefore leaves
  the target unchanged for that window and is safe to repeat, and concurrent runs on multiple instances
  need no coordination.
- **Late-arriving data self-heals** — a row arriving for an already-materialized bucket re-dirties that
  key, and the next refresh recomputes the whole bucket rather than adding to it.

A refresh SHALL advance the pipeline's stored cursor only after the write succeeds. A refresh whose probe
matches nothing SHALL be a no-op that leaves the cursor unmoved.

Refresh SHALL be bounded: the probe SHALL apply a configured window limit, and a run that fills its limit
SHALL report that more work remains so the next tick continues from the new cursor.

#### Scenario: A bucket recomputed after new rows arrive holds the total, not the increment

- **WHEN** a rollup bucket has been materialized from 10 input rows, 5 more rows land in the same
  bucket, and a refresh runs
- **THEN** the bucket's measures reflect all 15 rows

#### Scenario: Re-running a refresh changes nothing

- **WHEN** a refresh runs and then the identical refresh is run again with no new input rows
- **THEN** the target's rows and measures are unchanged

#### Scenario: A late row corrects its bucket rather than double-counting

- **WHEN** a row whose event time falls in an already-materialized bucket is ingested and a refresh runs
- **THEN** that bucket is recomputed to include the late row and no second row appears for the key

#### Scenario: A failed write leaves the cursor unmoved

- **WHEN** the `INSERT … SELECT` fails
- **THEN** the target is unchanged for that window, the stored cursor is not advanced, and the failure is
  reported without leaking ClickHouse internals

#### Scenario: An empty window is a no-op

- **WHEN** a refresh runs and the probe matches no rows
- **THEN** no write is issued and the cursor is unchanged

#### Scenario: Only due pipelines run

- **WHEN** the scheduler tick fires and an enabled pipeline's next run time has not arrived
- **THEN** that pipeline is not refreshed on this tick

### Requirement: Pipeline runtime state is readable

A pipeline SHALL carry its own runtime execution state — the scan cursor, the last and next run times,
and the last failure — persisted in the registry (not ClickHouse), because the schedule is ADAS-driven
and no caller holds the position. `GET /v1/pipelines/{name}` SHALL surface this state read-only,
including a staleness measure, so an operator can tell a fresh rollup from a stalled one without reading
logs.

State SHALL be server-owned: no endpoint SHALL let a caller set a cursor, since a caller-chosen position
could skip input rows and silently produce wrong measures.

#### Scenario: State is reported after a run

- **WHEN** `GET /v1/pipelines/{name}` is called after a successful refresh
- **THEN** the response carries the advanced cursor, the last run time, the next run time, and a
  staleness measure

#### Scenario: A failure is visible without reading logs

- **WHEN** a refresh fails and `GET /v1/pipelines/{name}` is called
- **THEN** the response reports the failure alongside the unadvanced cursor

#### Scenario: Cursors are not caller-settable

- **WHEN** a caller attempts to set a pipeline's cursor through the API
- **THEN** the attempt is rejected

### Requirement: Refresh observability

The service SHALL expose refresh observability through logs and Micrometer metrics, including refresh
runs by outcome, rows written, run duration, and per-pipeline staleness, attributable to the individual
pipeline. Because a rollup is served from stored rows rather than recomputed per read, a silently
stalled refresh is indistinguishable from a quiet period at the query surface — staleness SHALL therefore
be observable as a metric, not only through the API's state view.

#### Scenario: Metrics reflect a refresh run

- **WHEN** a refresh completes
- **THEN** the refresh metrics (runs by outcome, rows written, duration) are updated for that pipeline

#### Scenario: A stalled pipeline is visible as staleness

- **WHEN** an enabled pipeline has not refreshed successfully for longer than its schedule implies
- **THEN** its staleness metric grows, making the stall observable without inspecting logs

### Requirement: Aggregate pipeline errors use the shared error contract

Every `/v1/pipelines` failure SHALL use the `ErrorView` contract with a stable machine-readable `error`
code: an unknown pipeline SHALL be 404, a target that already has a pipeline SHALL be 409, and a spec or
governance violation SHALL be 422 (403 for a sensitive-column refusal). ClickHouse and driver internals
SHALL NOT be echoed; a ClickHouse outage during preview or refresh SHALL surface as 503.

#### Scenario: Unknown pipeline

- **WHEN** any `/v1/pipelines/{name}` endpoint is called for a name not in the registry
- **THEN** the response is 404 with a stable `error` code

#### Scenario: ClickHouse unavailable during preview

- **WHEN** `POST /v1/pipelines/{name}/preview` is called while ClickHouse is unreachable
- **THEN** the response is 503 with the shared contract and no raw driver text
