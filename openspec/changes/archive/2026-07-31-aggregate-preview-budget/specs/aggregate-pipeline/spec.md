## ADDED Requirements

### Requirement: Preview executes under a resource budget

The dry-run preview SHALL execute its sample query under an explicit, configured **resource budget** —
a wall-clock limit, a memory ceiling, and a spill-to-disk threshold — carried to ClickHouse as query
settings on the preview statement alone.

The budget is required because preview is deliberately **not** window-restricted: the sample answers "are
these the right numbers", which a window would obscure, so the compiled sample aggregates the pipeline's
entire input on every call. Bounding the rows *returned* (`aggregate.preview-rows`) bounds none of that
work. A declaration grouping by a high-cardinality wide column — a request body, a URI, a claims blob —
therefore builds an aggregation state proportional to the whole input — enough, on an input of only tens
of thousands of rows, to exhaust ClickHouse and take the host down with it.

The exposure this closes is specific to the gate's position in the lifecycle. Preview is the endpoint an
operator is *expected* to call on a declaration nobody has vetted, before it is ever enabled, and a
pipeline that is refused the instant it runs must not be able to take the data plane offline on the way to
being refused. Registration validates the declaration's shape, not its cost, and cost cannot be validated
from the shape alone — it depends on the input's cardinality, which is a property of the data.

The budget SHALL bound the preview's **cost**, never its **scope**. The system SHALL NOT sample, window,
or otherwise narrow the preview's input to stay inside the budget: numbers computed over a silently
reduced input would still be presented as the declaration's output, which is worse than a refusal because
the operator would act on them. A preview that cannot be completed within its budget SHALL be refused
outright.

The budget SHALL apply to preview only. Refresh is already bounded by its window and its failure mode is a
correctness question rather than a capacity one; constraining it here would make a legitimate large
window fail as though it were malformed.

The budget SHALL be deployment-configurable, so an installation whose ordinary previews are heavier than
the default can raise it deliberately rather than discovering the ceiling by hitting it.

#### Scenario: An affordable preview is unaffected

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a declaration whose sample completes inside
  the configured budget
- **THEN** the response is the compiled statement and the bounded sample exactly as before, with the
  sample computed over the whole input

#### Scenario: A preview that exceeds its budget is refused

- **WHEN** a preview's sample query exceeds the configured wall-clock or memory budget
- **THEN** the request is refused with the shared `ErrorView` contract and the budget-exceeded error code,
  and no partial or reduced-input sample is returned

#### Scenario: The budget does not narrow the sample's input

- **WHEN** a preview runs within its budget
- **THEN** the sample is computed over the pipeline's entire input, unsampled and unwindowed, so the
  numbers are the declaration's real output

#### Scenario: Refresh is not subject to the preview budget

- **WHEN** an enabled pipeline refreshes a window whose recompute is more expensive than the preview
  budget allows
- **THEN** the refresh runs to completion, because it is bounded by its window rather than by the preview
  budget

## MODIFIED Requirements

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
`kind`, a `month` bucket `unit`, `incremental` freshness, a non-`schedule` `trigger.kind` — SHALL be accepted
by the binding and refused by validation, so the response is the 422 above with a message naming the
alternatives, never the generic 400 an unbindable value produces.

Every such code SHALL identify *this* capability. Validation shared with other capabilities — notably the
`filter` grammar, which the enrichment registry also uses — SHALL be re-attributed to the pipeline code
rather than reported under the sharing capability's, since a code is what a client switches on and a
borrowed one points at an object that does not exist. A sensitive-column refusal keeps its 403 and is not
flattened into a validation failure.

#### Scenario: Unknown pipeline

- **WHEN** any `/v1/pipelines/{name}` endpoint is called for a name not in the registry
- **THEN** the response is 404 with a stable `error` code

#### Scenario: A declared-but-unimplemented value reports under this capability's code

- **WHEN** `POST /v1/pipelines` names an unimplemented `kind`, or a `month` bucket `unit`
- **THEN** the response is 422 carrying this capability's `error` code and a message naming the implemented
  kinds / the supported units, not a generic deserialization 400

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
