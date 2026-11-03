## MODIFIED Requirements

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
switched off is one the runner stops calling by design — it follows `enabled` as the pipeline's single run
control — which would otherwise make the intended behaviour the loudest alarm on the dashboard.
Re-enabling or re-creating a pipeline re-registers the series on the next call, measured from a contact
that actually happened. A pause the runner imposes on its own — its dead-letter circuit breaker — is not
reported to ADAS, so the series keeps climbing through one: that is accurate, since the runner is not
calling, and the runner's own runtime status names the cause.

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

### Requirement: A disabled pipeline's write paths refuse

`POST /v1/pipelines/{name}/rows` and `POST /v1/pipelines/{name}/materialize` SHALL refuse a pipeline
that is disabled with HTTP 409 `pipeline_conflict`, before reading or writing anything and whatever its
declaration holds, after the `Adas-Contract-Version` check, `unknown_pipeline` and the caller's
per-target authorization.

A disabled pipeline writes to its target by no route (see
[pipeline-registry](../pipeline-registry/spec.md), "Register and enable lifecycle"), and these two were
the routes that did not enforce it. Their tolerance existed so a paused pipeline could still be
backfilled, which no consumer relied on: the Enrichment Runner follows `enabled` as the
pipeline's single run control — it schedules nothing for a disabled pipeline, holds its queued work
unexecuted, and pauses its backfill until the pipeline is re-enabled (see
[pipeline-registry](../pipeline-registry/spec.md), "A pipeline has exactly one run control"). Once a
declaration may be stored before it passes the gate, the tolerance would also have written a target
from a declaration that was never armed and so never validated.

409 rather than 422, because the call may be perfectly good and only the pipeline's state is wrong — the
answer a disabled `aggregate` pipeline's on-demand refresh already gives. The two read endpoints,
`…/scan` and `…/group-rows`, SHALL stay open to a disabled pipeline: they write nothing, and the dry-run
preview of a disabled pipeline samples through them.

**On these two endpoints `pipeline_conflict` SHALL mean only that the pipeline is disabled**, and a
consumer MAY rely on that. A work item the runner has already started when an operator disables a
pipeline — its model call made before the runner's next sync observes the flag — has its write refused
here, and the runner holds that item, as it holds every queued item of a stopped pipeline, instead of
dead-lettering it or counting it against its circuit breaker; it writes once the pipeline is
re-enabled. That is only sound while the code is unambiguous, so another refusal on these endpoints
SHALL use a code of its own rather than `pipeline_conflict`.

#### Scenario: A write-back to a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/rows` is called for a disabled pipeline
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict` and no row is written

#### Scenario: A materialize of a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a disabled pipeline, whether its
  declaration is complete or a draft
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict`, no statement is executed, and no
  position or drained horizon is recorded

#### Scenario: The write paths' 409 names nothing but the disabled state

- **WHEN** `POST /v1/pipelines/{name}/rows` or `POST /v1/pipelines/{name}/materialize` answers HTTP 409
  with error `pipeline_conflict`
- **THEN** the pipeline is disabled; every other refusal on these endpoints carries a different code

#### Scenario: The contract and existence checks still come first

- **WHEN** either endpoint is called for a disabled pipeline with a wrong `Adas-Contract-Version`, or
  for a name that resolves to no pipeline
- **THEN** the response is the contract 409 or the 404 `unknown_pipeline` respectively, not the
  disabled-pipeline refusal
