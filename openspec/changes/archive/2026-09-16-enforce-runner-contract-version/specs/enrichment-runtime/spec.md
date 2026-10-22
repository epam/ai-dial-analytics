## ADDED Requirements

### Requirement: The governed data plane requires an exact runner contract version

Every request to a runner-only data-plane endpoint SHALL carry the `Adas-Contract-Version` header naming
the contract the caller was built against, and SHALL be refused unless it equals the version this service
serves. The endpoints SHALL be `POST /v1/pipelines/{name}/scan`, `POST /v1/pipelines/{name}/group-rows`
and `POST /v1/pipelines/{name}/rows`, plus `POST /v1/pipelines/{name}/materialize` (see the
`class1-enrichment` capability).

The version SHALL be a single opaque integer compared for **equality**, not a range and not an ordering:
the service SHALL NOT attempt to serve a caller that declares any other value. It SHALL be a property of
the build, not configuration — a deployment that could set it to the wrong value could disable every
pipeline at once, silently, which is the failure this requirement exists to remove. It SHALL be raised
whenever a breaking change lands in the compiled projection or in any of these four endpoints' request or
response shapes.

Refusals SHALL use the shared error contract: a missing header SHALL be HTTP 409
`runner_contract_required` and a header that does not match SHALL be HTTP 409
`runner_contract_mismatch`, whose message SHALL name both the version served and the version declared so
the reader learns which side is behind. The refusal SHALL be decided **before** any pipeline lookup, the
per-call grant check and any query, so a skewed caller cannot consume a read budget or reach ClickHouse.
It SHALL be decided **after** authentication: an unauthenticated caller SHALL still be answered `401`,
because "who is calling" is settled before "which build is calling".

This requirement exists because the transport survives a version skew that the definition does not. The
release that shrank the declaration surface left these four endpoints unchanged, so the runner image
deployed at the time kept calling them successfully while holding an authored projection with no evaluator
and no `grain_key` — and the scan cursor, which advances at enqueue and is never re-examined, ran through
the whole backlog producing nothing. The runner has since grown an admission arm that refuses that
particular entry, which is why this requirement is stated over the *transport* rather than over any one
missing field: the next shape change removes a different field, and the endpoints stay just as willing.

Refusing the call is what this service can guarantee on its own: the cursor is untouched and nothing is
written, whatever the caller does next, so the skew costs a stall rather than a loss. It is deliberately
*not* the whole of "equivalent to a runner scaled to zero" — a chunk already queued on the caller's side
reaches its model call without asking this service anything, so suppressing the spend is the runner half's
responsibility and is specified there. This requirement covers what is decidable from the request alone.

The operator surfaces `POST /v1/pipelines/{name}/preview` and `/refresh`, pipeline registration, and both
read projections SHALL NOT require the header, so a management client, the OpenAPI console and an
operator's own reads are unaffected by it.

#### Scenario: A scan without the contract header is refused before any lookup

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with no `Adas-Contract-Version` header
- **THEN** the response is HTTP 409 `runner_contract_required`, no scan is executed, and the pipeline's
  cursor position and the caller's read budget are untouched

#### Scenario: A caller declaring a different contract version is refused on every data-plane endpoint

- **WHEN** `scan`, `group-rows`, `materialize` or `rows` is called with an `Adas-Contract-Version` that
  differs from the version served
- **THEN** each response is HTTP 409 `runner_contract_mismatch` with a message naming both versions, and
  no row is read, computed or written

#### Scenario: A matching caller is served exactly as before

- **WHEN** the same requests carry the matching `Adas-Contract-Version`
- **THEN** each endpoint behaves exactly as specified elsewhere in this capability, the header having no
  effect beyond admission

#### Scenario: An operator surface does not require the header

- **WHEN** `POST /v1/pipelines/{name}/preview` or `/refresh` is called with no `Adas-Contract-Version`
  header
- **THEN** the request is served normally, because these are operator surfaces rather than the runner's

### Requirement: A pipeline-scoped write-back that could write no label is refused

Every row of `POST /v1/pipelines/{name}/rows` SHALL name at least one of the pipeline's declared output
columns, and a row naming only the grain key and provenance columns SHALL be rejected with HTTP 422
`write_output_column_required` naming the pipeline and the requirement. The check SHALL be on the
**presence of the column**, not on its value: an output column explicitly carrying `null` is a derived
label of `null` and SHALL be accepted, because a mapping that deliberately maps a sentinel to `null` is a
declared outcome.

The code SHALL be distinct from the existing `write_column_not_allowed`, which this endpoint already uses
for a row naming a column the pipeline may **not** write. Both are 422s about a write's column set, but
the remedies are opposite — remove the named column there, add a declared one here — and a machine code a
client acts on without reading the message may not carry the wrong remedy half the time.

The target is a `ReplacingMergeTree` read at its grain, so a row of grain plus provenance does not fail
and does not no-op — it **replaces** whatever label that grain already holds with nothing, after the model
call that produced it was made and charged. The existing subset rule cannot catch this: such a row names
only permitted columns. A caller that has lost its output mapping, for any reason including a version
skew, therefore reaches a silent and irreversible overwrite through a request that is otherwise valid, and
this requirement is what makes that unreachable regardless of which caller version sent it.

#### Scenario: A row of grain and provenance only is refused

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row naming the grain key and provenance
  columns but none of the pipeline's declared output columns
- **THEN** the response is HTTP 422 `write_output_column_required`, and no row of the batch is written

#### Scenario: A row naming a forbidden column keeps its own code

- **WHEN** a row names a column the pipeline may not write
- **THEN** the response is still HTTP 422 `write_column_not_allowed`, unchanged by this requirement

#### Scenario: An output column present with a null value is accepted

- **WHEN** a row names a declared output column whose value is `null`
- **THEN** the write is accepted and stores `null` for that column, because the label was derived and its
  value is `null`

### Requirement: A runner that has stopped calling is visible as a metric

The service SHALL publish, per `enrich` pipeline, the time since the runner last successfully called one
of that pipeline's data-plane endpoints, and SHALL count contract refusals tagged by reason
(`required` or `mismatch`). The refusal counters SHALL be registered at startup, so the series exists
before the first skew rather than appearing once it has already happened, and SHALL **not** carry a
`pipeline` tag: a refusal is decided before the pipeline is looked up, so the only name available is the
unvalidated path segment the caller sent — an unbounded tag whose values a skewed or hostile client
chooses. Staleness SHALL be measured against the present at **observation** time from a
stored last-contact instant, not published as a value frozen between calls, so a runner that has stopped
shows as a series that keeps climbing with no call needed to move it — the same rule the aggregate
staleness metric follows and for the same reason.

A pipeline's series SHALL be registered on its **first** data-plane contact, and SHALL be dropped when the
pipeline is **deleted or disabled** — by every path that deletes one, including the deletion that follows
its target table being dropped. A gauge derived at scrape time from an instant nothing will move again
raises the stall signal forever, so a series must not outlive the thing that moves it: a pipeline the
runner has never called has no series rather than one climbing since startup, and a pipeline an operator
switched off leaves the runner's `enabled=true` feed by design, which would otherwise make the intended
behaviour the loudest alarm on the dashboard. Re-enabling or re-creating a pipeline re-registers the
series on the next call, measured from a contact that actually happened.

This exists because ADAS's own output depends on the runner being alive: a Class-1 enrichment's
materialized position is what every dependent rollup's freshness clamp reads, so a runner that is down,
skewed or refused makes rollups write zero rows per tick with the clamp naming an enrichment, and nothing
in ADAS today distinguishes that from a quiet period. The signal SHALL be a metric and SHALL NOT be a
health contributor: the deployment's liveness probe targets the aggregate health endpoint, so a
contributor reporting DOWN for an operational condition would restart the service instead of reporting it.

#### Scenario: Staleness climbs while the runner is absent

- **WHEN** no data-plane call is made for an `enrich` pipeline over several observations
- **THEN** its runner-contact staleness series grows between observations, without any call occurring

#### Scenario: A refused caller is counted by reason

- **WHEN** a data-plane call is refused for a missing or mismatched contract version
- **THEN** a refusal counter tagged with that reason, and with no `pipeline` tag, is incremented

#### Scenario: A refusal naming an unknown pipeline adds no tag value

- **WHEN** a data-plane call is refused for a contract version while naming a pipeline that does not exist
- **THEN** the refusal is counted and the metric registry gains no new series, because the counters are
  registered at startup and are not keyed by the name the caller sent

#### Scenario: A deleted pipeline leaves no climbing series

- **WHEN** an `enrich` pipeline is deleted — directly, or because its target table was dropped
- **THEN** its runner-contact series is removed rather than left to climb indefinitely

#### Scenario: A disabled pipeline leaves no climbing series

- **WHEN** an `enrich` pipeline is disabled, so it leaves the runner's feed and stops being called
- **THEN** its runner-contact series is removed, because the runner's silence is now the declared state
  rather than a condition to report

#### Scenario: A pipeline the runner has never called has no series

- **WHEN** an `enrich` pipeline has been registered but no data-plane call has been made for it
- **THEN** no runner-contact staleness series exists for it
