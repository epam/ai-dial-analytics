## ADDED Requirements

### Requirement: Pipeline registry object

The system SHALL maintain a Postgres-backed registry of **pipelines** — declared row-producing
processes, each writing exactly one **target** table. A pipeline SHALL carry a `name` (the registry
key and the `{name}` in the API path), a `kind`, the `target` table it writes, its `inputs`, a
kind-specific declaration, a `trigger`, and an `enabled` flag. A pipeline is **data, not code**:
registering one deploys nothing, and the platform's generic executor runs it.

`kind` SHALL be a closed set. `aggregate` and `enrich` SHALL be implemented; the `kind` SHALL select
both the declaration's vocabulary and **where the pipeline runs** — an `aggregate` pipeline is
refreshed by ADAS on its own cron, an `enrich` pipeline is driven by the Enrichment Runner through
the pipeline-scoped data plane, except for its Class-1 case which ADAS materializes in-database. A
request naming an unimplemented kind SHALL be rejected with HTTP 422 naming the implemented kinds.
The unimplemented members SHALL therefore be part of the accepted vocabulary rather than omitted
from it — an omitted member is a deserialization failure, answered with a generic HTTP 400 that
names neither the offending value nor the alternatives, which is not the contract above. Only an
implemented kind is ever persisted.

`kind` SHALL be immutable: a different transform over the same target is a different pipeline, and a
patch that changes it SHALL be rejected.

**One pipeline per target**: a target table SHALL have at most one pipeline **of any kind**,
enforced by a database `UNIQUE (target)` constraint, and registering a second pipeline for the same
target SHALL be rejected with HTTP 409. This SHALL hold across kinds rather than by disjointness of
target shapes. The reasons are per-kind and both stand: a rollup's target replaces rows by key, and
an enrichment's target is a `ReplacingMergeTree` with full-row replace by grain, so two pipelines
writing the same target would clobber each other's columns. Multi-label output is expressed as one
declaration emitting multiple output columns, not as a second pipeline.

`inputs` SHALL name at most one input table (multi-source input is post-v1). An `aggregate` pipeline
SHALL name its input. An `enrich` pipeline MAY omit it, which SHALL mean "read the target
enrichment's own parent source"; the response SHALL echo the resolved input rather than the omitted
value, so a caller can see what was chosen. The input SHALL NOT be the pipeline's own target — a
pipeline that reads what it writes SHALL be rejected with HTTP 422.

Pipeline registration, mutation, and deletion SHALL require the `FULL_ADMIN` authority in `oidc`
mode; per-table access roles SHALL NOT authorize them. Reading a pipeline SHALL require the same
authority: the declaration names the columns a pipeline reads, including `sensitive` ones, so it is
not a read-only-admin surface.

#### Scenario: Register a pipeline

- **WHEN** `POST /v1/pipelines` is called with a valid declaration of an implemented kind
- **THEN** the response is 201 with the stored pipeline, `enabled: false`

#### Scenario: A second pipeline for the same target is rejected

- **WHEN** `POST /v1/pipelines` names a `target` that already has a pipeline
- **THEN** the request is rejected with HTTP 409 and nothing is persisted

#### Scenario: The one-writer rule holds across kinds

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline whose `target` is already written by
  an `aggregate` pipeline, or the reverse
- **THEN** the request is rejected with HTTP 409 by the same constraint, not admitted because the two
  kinds were once separate registries

#### Scenario: An unimplemented kind is rejected

- **WHEN** `POST /v1/pipelines` is called with a `kind` that is part of the vocabulary but not
  implemented
- **THEN** the request is rejected with HTTP 422 naming the implemented kinds

#### Scenario: A pipeline reading its own target is rejected

- **WHEN** `POST /v1/pipelines` names the same table in `inputs` and `target`
- **THEN** the request is rejected with HTTP 422 (a cycle)

#### Scenario: An enrich pipeline may omit its input

- **WHEN** `POST /v1/pipelines` registers an `enrich` pipeline with no `inputs`
- **THEN** the pipeline is accepted and reads its target enrichment's parent source, and the response
  echoes that resolved input

#### Scenario: An aggregate pipeline may not omit its input

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with no `inputs`
- **THEN** the request is rejected with HTTP 422

#### Scenario: The kind cannot be changed

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a different `kind`
- **THEN** the request is rejected and the stored kind is unchanged

#### Scenario: Pipeline management requires admin

- **WHEN** a non-`FULL_ADMIN` caller invokes any `/v1/pipelines` registry endpoint in `oidc` mode
- **THEN** the request is rejected with HTTP 403

### Requirement: A pipeline name is an identity, not a SQL identifier

A pipeline `name` SHALL match `^[a-z][a-z0-9_-]{0,63}$` — a lower-case letter followed by up to 63
lower-case letters, digits, underscores or hyphens — validated at registration and reported as a
pipeline validation failure. The grammar is an **identity** grammar and SHALL NOT be the
table-and-column identifier grammar: a pipeline `name` never becomes a ClickHouse identifier, so
what the grammar exists for is not injection safety but the properties out-of-process consumers
depend on.

The exclusions are what the grammar is for. `#` SHALL be excluded because the Enrichment Runner
embeds the identity in `#`-delimited work-item identifiers and counts a pipeline's in-flight work by
its `name + "#"` prefix; the names `a#b` and `a` would then share a prefix and one pipeline's
backpressure would count another's work. Upper case and a leading underscore SHALL be excluded so an
identity is unambiguous in the paths, logs and metric tags that carry it.

Hyphens SHALL be admitted. Kebab-case is the enrichment sub-domain's established convention — its
evaluator names are kebab-case — while snake_case is the catalog's, *because* catalog names become
SQL. A registry holding both kinds SHALL admit both spellings rather than force one sub-domain onto
the other's convention.

`name` SHALL be **immutable**. A patch that supplies a different name SHALL NOT rename the pipeline
in place. Out-of-process consumers key their own runtime state on the identity, so a rename is a
data migration and not a field edit.

#### Scenario: A hyphenated name is accepted

- **WHEN** `POST /v1/pipelines` registers a pipeline named `usage-client-identity-live`
- **THEN** the pipeline is accepted and addressable at `/v1/pipelines/usage-client-identity-live`

#### Scenario: A name carrying the work-item delimiter is refused

- **WHEN** `POST /v1/pipelines` registers a name containing `#`
- **THEN** the request is rejected with HTTP 422 and the pipeline validation error code, not the
  table validation code

#### Scenario: Upper case and a leading underscore are refused

- **WHEN** `POST /v1/pipelines` registers a name containing an upper-case letter, or one beginning
  with `_`
- **THEN** the request is rejected with HTTP 422

#### Scenario: A name is not renamable

- **WHEN** a caller attempts to change a registered pipeline's `name`
- **THEN** the stored name is unchanged

### Requirement: Register and enable lifecycle

A pipeline SHALL be registered `enabled: false` and SHALL NOT run until explicitly enabled through
`PATCH /v1/pipelines/{name}`. A declaration edit SHALL be re-validated in full, exactly as at
registration, and SHALL be supplied whole: a partial declaration patch SHALL be rejected rather than
merged, since a declaration is validated as one object and a half-applied one has no meaning.

A `disabled` pipeline SHALL NOT write to its target by any route — neither on a schedule, nor
through an operator-triggered refresh, nor through the pipeline-scoped data plane. Were any route to
write anyway, `enabled` would describe only the schedule while the gate it exists to enforce stayed
open, and a target could be filled from a declaration nobody had looked at.

**Whether a declaration may be edited while the pipeline is enabled SHALL depend on the kind**, and
the reason is the target's write semantics rather than operator convenience:

- For a kind whose target holds **windowed** results replaced by key — `aggregate` — editing an
  enabled pipeline's declaration SHALL be rejected with HTTP 409. It is a state conflict, like
  refreshing a disabled pipeline, and not a 422: the declaration sent may be perfectly valid and only
  the timing wrong, and reporting it as a validation failure would tell the client to fix a spec that
  has nothing wrong with it. What the 409 protects against is a target holding rows computed two
  different ways with nothing recording which row came from which.
- For a kind whose target is **grain-keyed with full-row replace** — `enrich` — a declaration edit on
  an enabled pipeline SHALL be permitted. After such an edit new rows compute the new way and rows
  already written keep their previous values, which is already how that kind behaves and is why it
  carries a re-derivation marker and a backfill lane of its own. The invariant above is not weakened
  for this kind; it was never this kind's invariant. Requiring a disable/enable cycle for a routine
  runtime edit would instead cost real work: the out-of-process runner drops a disabled pipeline from
  its cache, and its dead-letter items lose their stored labels while it is disabled.

A declaration change SHALL bump the pipeline's change token (`generation`), which is what an
out-of-process consumer detects a re-declaration by.

A declaration change SHALL reset **only the execution state ADAS itself owns for that kind**. For a
kind whose cursor and rebuild anchor are ADAS-owned, the cursor SHALL be reset so the next run reads
its input from the beginning and recomputes every key under the new declaration; keeping it would
confine the new declaration to windows not yet read, leaving every key materialized before the edit
holding values the *previous* declaration produced — indefinitely, and with nothing in the target
recording it. For a kind whose cursor is held by the out-of-process runner, the reset SHALL be a
no-op and SHALL NOT stamp a rebuild anchor that does not apply to it. Rows whose key the new
declaration no longer produces are NOT removed: recomputation can only replace keys it derives, so
rebuilding or replacing a re-grained target remains an operator action.

`DELETE /v1/pipelines/{name}` SHALL remove the pipeline and leave the target table and its rows
intact: a target that stops being fed is stale, not invalid.

#### Scenario: A registered pipeline does not run until enabled

- **WHEN** a pipeline is registered and its trigger fires
- **THEN** no statement is executed against the target while `enabled` is false

#### Scenario: A declaration edit is fully re-validated

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration that violates a governance rule of its
  kind
- **THEN** the request is rejected with HTTP 422 and the stored declaration is unchanged

#### Scenario: Editing an enabled aggregate pipeline's declaration is a conflict

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration for an `aggregate` pipeline that is
  `enabled`
- **THEN** the request is rejected with HTTP 409 and the stored declaration is unchanged

#### Scenario: Editing an enabled enrich pipeline's declaration is permitted

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration for an `enrich` pipeline that is
  `enabled`
- **THEN** the declaration is re-validated, stored, and the change token is bumped, and the
  pipeline's rebuild anchor is left untouched

#### Scenario: A partial declaration patch is refused

- **WHEN** `PATCH /v1/pipelines/{name}` supplies some but not all of a declaration's required members
- **THEN** the request is rejected and the stored declaration is unchanged

#### Scenario: Deleting a pipeline keeps the target

- **WHEN** `DELETE /v1/pipelines/{name}` is called
- **THEN** the pipeline is removed, and the target table and its rows still exist and remain
  queryable

### Requirement: A declaration field belonging to another kind is rejected

The system SHALL reject a registration or patch carrying a declaration field that belongs to a kind
other than the request's own `kind`, with HTTP 422 naming the offending field. Silently ignoring it
SHALL NOT be an option.

The registry serves one flat request and response shape across kinds, so a caller can send any
field to any kind and the binding will accept it. Cross-kind rejection is what makes that flat shape
safe, and it is the only thing that does: a field ignored rather than refused reads back absent from
the stored pipeline, so a caller who mistook one kind's vocabulary for another's would be told their
declaration was accepted while the part they cared about was discarded.

Consequently a field that is required for one kind and meaningless for another SHALL NOT be enforced
by the request shape alone. Such a field SHALL be optional at the binding and required by the
kind-dispatched validation, so the failure is the 422 above rather than a generic deserialization
400.

#### Scenario: An aggregate-only field on an enrich declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: enrich` carrying aggregate `measures`
- **THEN** the request is rejected with HTTP 422 naming the field, and nothing is persisted

#### Scenario: An enrich-only field on an aggregate declaration is refused

- **WHEN** `POST /v1/pipelines` registers `kind: aggregate` carrying an `evaluator_name`
- **THEN** the request is rejected with HTTP 422 naming the field, and nothing is persisted

#### Scenario: A field required only for another kind is not required by the binding

- **WHEN** `POST /v1/pipelines` registers a kind for which `inputs` is optional, omitting it
- **THEN** the request is accepted, rather than failing the binding that another kind's requirement
  would impose

### Requirement: Pipeline listing carries the registry's filters, envelope and ordering

`GET /v1/pipelines` SHALL return an object envelope — `{"pipelines": [ … ]}` — never a bare array, so
counts, cursors and metadata can be added without breaking a client.

The listing SHALL accept four independent, all-optional filters, applied together: `target` (the
table a pipeline writes), `kind`, `enabled`, and `updated_since` (pipelines whose declaration
changed at or after an instant). Omitting a filter SHALL mean "do not filter on it".

`enabled` SHALL be bound **strictly**: exactly `true` or `false`. Any other value, including an empty
one, SHALL be rejected with HTTP 400 rather than treated as absent. A parameter that selects which
subset is returned cannot be lenient — a caller asking for the enabled subset and receiving the whole
registry would act on pipelines it deliberately excluded.

The listing SHALL be ordered `updated_at` ascending, then `name` ascending. The tiebreaker is
required rather than cosmetic: `updated_at` is stored at limited precision, so pipelines written in
one transaction share it, and without a deterministic secondary key two calls over an unchanged
registry would return them in different orders — which a consumer diffing or paging the feed would
read as a change. Every listing SHALL use this one ordering, so a consumer's ordering guarantee does
not depend on which filters it passed.

Each entry SHALL carry the pipeline's change token `generation`, which SHALL be a declaration fact
rather than a member of runtime `state`: an out-of-process consumer detects a re-declaration by
comparing it against the token it last saw, so it cannot live behind a projection that a consumer
might not read.

#### Scenario: The listing is an envelope

- **WHEN** `GET /v1/pipelines` is called
- **THEN** the response body is an object carrying the pipelines under a named member, not a bare
  array

#### Scenario: Filters combine

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called
- **THEN** only enabled pipelines of that kind are returned

#### Scenario: A non-boolean enabled value is a bad request

- **WHEN** `GET /v1/pipelines?enabled=yes` or `GET /v1/pipelines?enabled=` is called
- **THEN** the response is HTTP 400, not the unfiltered registry

#### Scenario: Ordering is stable across calls

- **WHEN** `GET /v1/pipelines` is called twice over an unchanged registry containing two pipelines
  whose `updated_at` values are identical
- **THEN** both responses order them identically, by `name` within the shared `updated_at`

#### Scenario: A change-detection consumer sees the change token

- **WHEN** a pipeline's declaration is patched and `GET /v1/pipelines` is called
- **THEN** the entry's `generation` differs from the value returned before the patch

### Requirement: Pipeline runtime state is readable

A pipeline SHALL surface its runtime execution state read-only on `GET /v1/pipelines/{name}`,
including a staleness measure, so an operator can tell a fresh target from a stalled one without
reading logs. State SHALL be reported under one projection for every kind, with the members that do
not apply to a kind omitted rather than reported empty — an omitted member says "this kind has no
such position", where a zero would read as a real one.

State SHALL be server-owned: no endpoint SHALL let a caller set a cursor, since a caller-chosen
position could skip input rows and silently produce wrong results.

Where a kind's schedule is ADAS-driven, the state SHALL carry the scan cursor, the last and next run
times, and the last failure, persisted in the registry (not ClickHouse), because no caller holds the
position. Where a kind's cursor is held by the out-of-process runner, those members SHALL be absent
and the runner SHALL remain their owner; the registry SHALL NOT report a position it does not know.

Where a read window was clamped short of its input's available cursor to keep a referenced
enrichment's join sound, the state SHALL say so, identifying the enrichment that bounded it and the
position it bounded the window to. A clamped pipeline is behind its input for a reason an operator
must be able to see: without it, a pipeline permanently held back by a lagging enrichment is
indistinguishable from a healthy one that simply had less input to read, and the failure this
introduces is a quiet one.

Where a pipeline reads an enrichment that imposes no clamp because ADAS cannot establish a position
on the axis the read windows on, the state SHALL name that enrichment and the reason. This indicator
is what makes admitting such a column different from ignoring the problem: the pipeline is exposed to
the freshness gap for that one enrichment, and an operator has to be able to see which one and why
without reading the declaration against the registry.

Where an enrichment a pipeline reads has had its history re-derived since the pipeline last read it,
the state SHALL report that the pipeline's output needs rebuilding, naming the enrichment. This is
not a clamp and no window is held back: the output is already wrong and no ordinary run will correct
it, so the signal is an instruction to the operator rather than a description of progress.

A failure SHALL be recorded against the pipeline **whichever route triggered it** — a trigger firing
or an operator-triggered run. A failure that only returned an HTTP error would leave the state view
reporting the *previous* run's health, so the endpoint an operator consults would disagree with what
actually happened. Recording the failure SHALL NOT mask the failure being reported: if the state
write itself fails, the original cause is still what surfaces. A recorded message SHALL be non-empty,
since a blank one is stored as "no error" and reads as a success.

#### Scenario: State is reported after a run

- **WHEN** `GET /v1/pipelines/{name}` is called after a successful ADAS-driven refresh
- **THEN** the response carries the advanced cursor, the last run time, the next run time, and a
  staleness measure

#### Scenario: A kind whose cursor the runner owns reports no cursor

- **WHEN** `GET /v1/pipelines/{name}` is called for an `enrich` pipeline
- **THEN** the ADAS-owned cursor and schedule members are absent from `state` rather than reported as
  zero or epoch values

#### Scenario: A failure is visible without reading logs

- **WHEN** a run fails — on a trigger or on an operator-triggered route — and
  `GET /v1/pipelines/{name}` is called
- **THEN** the response reports the failure alongside the unadvanced cursor, identically for both
  routes

#### Scenario: Cursors are not caller-settable

- **WHEN** a caller attempts to set a pipeline's cursor through the API
- **THEN** the attempt is rejected

#### Scenario: A clamped window is visible in state

- **WHEN** a read window is clamped to a referenced enrichment's materialized position rather than to
  its input's available cursor
- **THEN** `GET /v1/pipelines/{name}` reports that the window was clamped, names the enrichment that
  bounded it, and reports the cursor the read did reach

#### Scenario: An unclamped read reports no clamp

- **WHEN** a read window ends at or before every referenced enrichment's materialized position, or
  the pipeline references no enrichment at all
- **THEN** the state reports no clamp, so the indicator means something when it is present

#### Scenario: An enrichment that cannot be clamped against is named

- **WHEN** a pipeline reads an enrichment for which no position on the read's own axis can be
  established
- **THEN** `GET /v1/pipelines/{name}` names that enrichment as read-but-unclamped, with the reason,
  whether or not any other enrichment clamped the window

#### Scenario: Output needing a rebuild says so

- **WHEN** an enrichment a pipeline reads has had its history re-derived since the pipeline last read
  it
- **THEN** `GET /v1/pipelines/{name}` reports that the output requires a rebuild and names that
  enrichment

#### Scenario: Every unclamped-read reason is part of the published vocabulary

- **WHEN** `GET /v1/pipelines/{name}` reports an enrichment as read-but-unclamped
- **THEN** the reason is one of the published set — no pipeline writes the enrichment, the writing
  pipeline is group-triggered, the writing pipeline is not in-database derived, or the read windows on
  an axis foreign to the enrichment — and the published set names all four regardless of the reading
  pipeline's kind

### Requirement: Pipeline errors use the shared error contract

Every `/v1/pipelines` failure SHALL use the `ErrorView` contract with a stable machine-readable
`error` code, and one set of codes SHALL serve every kind. A code is what a client switches on, so
the registry SHALL NOT report the same condition under two codes according to which kind produced it.

The mapping SHALL be: an unknown pipeline is 404 `unknown_pipeline`; a declaration, governance or
identity violation is 422 `pipeline_validation_failed`; a state conflict is 409 `pipeline_conflict` —
covering a target that already has a pipeline, running a disabled pipeline, and editing an enabled
pipeline's declaration where its kind forbids it; and an authorization refusal on a pipeline-scoped
data-plane call is 403 `pipeline_access_denied`. A state conflict SHALL NOT be reported under a
validation code: the declaration sent may be perfectly valid and only the timing wrong.

A sensitive-column refusal SHALL keep its own 403 code and SHALL NOT be flattened into a validation
failure. A preview refused for exceeding its resource budget SHALL carry its **own** code, distinct
from both the validation failure and an outage.

ClickHouse and driver internals SHALL NOT be echoed; a ClickHouse outage during any pipeline read or
write SHALL surface as 503.

A rejection the registry's own rules define SHALL be reported by those rules rather than by request
deserialization. A value that is part of a declaration's vocabulary but not implemented — an
unsupported `kind`, an unimplemented freshness or trigger mode, a bucket unit outside the supported
set — SHALL be accepted by the binding and refused by validation, so the response is the 422 above
with a message naming the alternatives, never the generic 400 an unbindable value produces.

Validation shared with other capabilities — notably the `filter` grammar, which the enrichment
declaration also uses — SHALL be re-attributed to the pipeline code rather than reported under the
sharing capability's, since a borrowed code points at an object that does not exist. The label the
shared analysis reports SHALL name the kind whose declaration was being validated, so a message
identifies which declaration was refused.

#### Scenario: Unknown pipeline

- **WHEN** any `/v1/pipelines/{name}` endpoint is called for a name not in the registry
- **THEN** the response is 404 `unknown_pipeline`, whatever the kind of pipeline the caller expected

#### Scenario: A conflict is not reported as a validation failure

- **WHEN** a registration names a target that already has a pipeline, of either kind
- **THEN** the response is 409 `pipeline_conflict`

#### Scenario: A declared-but-unimplemented value reports under the pipeline code

- **WHEN** `POST /v1/pipelines` names an unimplemented `kind`, or an unimplemented freshness mode
- **THEN** the response is 422 `pipeline_validation_failed` with a message naming the implemented
  alternatives, not a generic deserialization 400

#### Scenario: An invalid filter reports under the pipeline error code

- **WHEN** a pipeline of either kind is registered with a `filter` that does not parse under the
  bounded grammar
- **THEN** the response is 422 `pipeline_validation_failed`, and the message names the kind whose
  declaration was refused

#### Scenario: A data-plane authorization refusal has its own code

- **WHEN** a caller without the target's write grant invokes a pipeline-scoped data-plane endpoint
- **THEN** the response is 403 `pipeline_access_denied`

#### Scenario: ClickHouse unavailable

- **WHEN** a pipeline read or write is attempted while ClickHouse is unreachable
- **THEN** the response is 503 with the shared contract and no raw driver text

#### Scenario: A budget refusal is distinguishable from an outage

- **WHEN** a preview is refused for exceeding its resource budget
- **THEN** the response is 422 with the budget-exceeded code and a message naming the exceeded
  budget, never the 503 `clickhouse_unavailable` an unreachable server produces
