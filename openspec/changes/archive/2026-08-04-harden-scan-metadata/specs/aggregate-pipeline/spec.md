## MODIFIED Requirements

### Requirement: Pipeline registry object

The system SHALL maintain a Postgres-backed registry of **pipelines** — declared row-producing
processes, each writing exactly one **target** table. A pipeline SHALL carry a `name` (the registry key
and the `{name}` in the API path), a `kind`, the `target` table it writes, its `inputs`, a kind-specific
`spec`, a `trigger`, and an `enabled` flag. A pipeline is **data, not code**: registering one deploys
nothing, and the platform's generic executor runs it.

`kind` SHALL be a closed set whose only implemented member in this capability is `aggregate`. The
discriminator SHALL be persisted so the `external` and `enrich` kinds can converge onto this object
later; a request naming an unimplemented kind SHALL be rejected with HTTP 422 naming the implemented
kinds. The unimplemented members SHALL therefore be part of the accepted vocabulary rather than omitted
from it — an omitted member is a deserialization failure, answered with a generic HTTP 400 that names
neither the offending value nor the alternatives, which is not the contract above. Only an implemented
kind is ever persisted.

**One pipeline per target** (D-TR-1): a target table SHALL have at most one pipeline, and registering a
second pipeline for the same target SHALL be rejected with HTTP 409. Because an `aggregate` pipeline
targets a `source` while an enrichment `rule` targets an `enrichment`, the two registries can never
contend for the same table.

`inputs` SHALL name exactly one source table in this capability (multi-source input is post-v1). The
input SHALL NOT be the pipeline's own target — a pipeline that reads what it writes SHALL be rejected
with HTTP 422.

The input SHALL be **scannable**: a materialized `source` declaring the
`version_column`/`identity_column` pair the refresh windows it by (see
[enrichment-runtime](../enrichment-runtime/spec.md)). An input declaring no pair SHALL be rejected with
HTTP 422 saying so — the pair is optional at schema-define (see
[table-management](../table-management/spec.md)), so this is a reachable state and not merely a legacy one.
It also means a rollup, whose own grain is usually multi-column and therefore has no single identity
column, is a poor input for a second pipeline.

Pipeline registration, mutation, and deletion SHALL require the `FULL_ADMIN` authority in `oidc` mode;
per-table access roles SHALL NOT authorize them.

#### Scenario: Register a pipeline

- **WHEN** `POST /v1/pipelines` is called with a valid aggregate pipeline
- **THEN** the response is 201 with the stored pipeline, `enabled: false`

#### Scenario: An input that declares no scan metadata is rejected

- **WHEN** `POST /v1/pipelines` names an `inputs` entry whose definition carries no
  `version_column`/`identity_column` pair
- **THEN** the request is rejected with HTTP 422 stating that the input is not scannable, since a refresh
  could not window it

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

### Requirement: Target shape governance

The target of an `aggregate` pipeline SHALL be an existing, materialized (`ACTIVE`) `source` table whose
write discipline is `upsert_by_key`. A target that is unknown, an `enrichment`, not yet materialized, or
written `append` SHALL be rejected with HTTP 422 — an `append` target would accumulate one row per
refresh instead of replacing the recomputed key, silently multiplying every measure.

The target's `ordering_key` SHALL equal the pipeline's `group_by` output names, **in order**. The rollup's
grain is therefore not declared twice and cannot drift from the pipeline that fills it.

Being a `source`, the target MAY declare the optional scan-metadata pair, which is what makes the rollup
itself scannable by an enrichment rule. It SHOULD do so **only when its `group_by` yields a single
column**: the target collapses by its whole `ordering_key`, so with a multi-column grain no single column
identifies a row, and `identity_column` could only name part of the key. That is not a cosmetic
inaccuracy — one refresh stamps a single `now64(3)` into `_updated_at` for every row it writes, so rows
sharing that one key member are indistinguishable to the keyset seek and all but one are silently skipped.
A single-column-grain rollup declares `identity_column` = its group key and `version_column` =
`_updated_at`, which the refresh restamps on every recomputed key, so a rule sourced on the rollup re-reads
exactly the keys the last refresh touched.

Every `group_by` output name and every `measures[].name` SHALL resolve to a declared column of the
target, and the column's declared type SHALL accept the part's or function's result type. A target column
that no `group_by` part or measure writes SHALL be rejected, since it could only ever hold a default.

For a group key the type rule is exact rather than merely compatible: a plain key's target column SHALL be
declared the same type as the input column it groups. A group key is the input value itself, not an
aggregate of it, so a mismatch is a bad declaration — and left to execution it either surfaces as an engine
rejection attributed to the wrong cause, or is silently coerced.

A **time bucket's** target column SHALL be a temporal type wide enough for the bucket's own width: an
`hour` bucket SHALL require `timestamp`, while a `day` or `week` bucket (which starts at midnight) MAY be
either `date` or `timestamp`. An `hour` bucket bound to a `date` column is the worst case of the whole
binding, and the reason the rule is per-unit rather than merely "temporal": the bucket start is truncated to
midnight on insert, and because the bucket is part of the target's `ordering_key` the engine then collapses
all 24 of a day's rows onto one arbitrary survivor. There is no error and no duplicate row — 23 buckets
simply do not exist.

A measure whose result carries a fraction SHALL require a `decimal` target column: `avg` always, and `sum`
over a `decimal` input column. The shared catalog types `avg` and `sum` alike (both `numeric`), so this rule
cannot come from the return type — and ClickHouse converts rather than refusing on insert, which would store
a truncated mean or total as an exact whole number. `min`/`max` need no such rule: they return the input
value itself.

**Which name addresses which side.** An `input` column — `group_by[].column`, `group_by[].trunc.column`,
`measures[].column`, and any column in `filter` — is named by its **exposed** catalog name, the same
spelling the query DSL and the filter grammar use, because that is what the compiled `SELECT` resolves
against. A `target` column — `group_by[].as` and `measures[].name` — is named by its **physical** name,
because that is what the `INSERT` column list and the target's `ordering_key` are. Validating either side
against the other's spelling would accept declarations that cannot compile, and reject ones that can, on any
aliased column.

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

#### Scenario: A group key bound to a differently-typed target column is rejected

- **WHEN** a plain group key over a `string` input column is bound to a target column declared `long`, or a
  time bucket is bound to a target column that is neither `date` nor `timestamp`
- **THEN** the request is rejected with HTTP 422

#### Scenario: An hour bucket bound to a date target column is rejected

- **WHEN** a `group_by` time bucket with `unit: "hour"` is bound to a target column declared `date`
- **THEN** the request is rejected with HTTP 422, because the hour would be truncated on insert and the
  day's 24 bucket rows would collapse onto one under the target's `ordering_key`

#### Scenario: A day bucket bound to a date target column is accepted

- **WHEN** a `group_by` time bucket with `unit: "day"` or `"week"` is bound to a target column declared
  `date`
- **THEN** the declaration is accepted, because such a bucket starts at midnight and loses nothing

#### Scenario: A fractional measure bound to an integer target column is rejected

- **WHEN** an `avg` measure, or a `sum` over a `decimal` input column, is bound to a target column declared
  `long` or `integer`
- **THEN** the request is rejected with HTTP 422, because ClickHouse would truncate the fraction on insert
  and the rollup would read as an exact whole number

#### Scenario: An unwritten target column is rejected

- **WHEN** the target declares a column that no group key or measure writes
- **THEN** the request is rejected with HTTP 422
