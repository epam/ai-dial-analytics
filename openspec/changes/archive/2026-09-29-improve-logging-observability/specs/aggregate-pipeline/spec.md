## MODIFIED Requirements

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

The budget SHALL apply to preview only. A refresh is bounded by its window and by the pipeline read budget
(`pipeline.budget.*`, see [enrichment-runtime](../enrichment-runtime/spec.md)), never by this one. The two
bound different things: this budget is sized for an unvetted declaration aggregating its whole input, the
pipeline read budget for the heaviest legitimate window of an enabled pipeline, so holding a refresh to the
preview's ceiling would make a legitimate large window fail as though it were malformed.

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

#### Scenario: A node-wide exhaustion is not the preview's budget refusal

- **GIVEN** a preview running under the `aggregate.preview.*` ceilings
- **WHEN** ClickHouse refuses it because the node itself ran out of memory, not because the preview
  exceeded its own ceiling
- **THEN** the response is HTTP 503 `clickhouse_unavailable` rather than the preview's budget refusal
- **AND** no message names the preview's configured ceiling

#### Scenario: The budget does not narrow the sample's input

- **WHEN** a preview runs within its budget
- **THEN** the sample is computed over the pipeline's entire input, unsampled and unwindowed, so the
  numbers are the declaration's real output

#### Scenario: Refresh is not subject to the preview budget

- **WHEN** an enabled pipeline refreshes a window whose recompute is more expensive than the preview
  budget allows, and within the pipeline read budget
- **THEN** the refresh runs to completion, because it is bounded by its window and the pipeline read budget
  rather than by the preview budget

### Requirement: Refresh observability

The service SHALL expose refresh observability through logs, traces, and Micrometer metrics, including
refresh runs by outcome, rows written, run duration, and per-pipeline staleness, attributable to the
individual pipeline. Because a rollup is served from stored rows rather than recomputed per read, a
silently stalled refresh is indistinguishable from a quiet period at the query surface — staleness SHALL
therefore be observable as a metric, not only through the API's state view.

The staleness metric SHALL be measured against the present at **observation** time rather than published as
the value a run computed. A run's lag published as a stored number is flat between runs and frozen entirely
once refreshes stop succeeding, which is exactly the condition the metric exists to expose: a stall would
render as a steady line. Deriving it from the last position actually reached makes a stall a line that keeps
climbing with no run needed to move it. A failed run SHALL NOT move that position.

Because it is sampled at observation time, the reported staleness SHALL be understood as a **sawtooth, not
a level**: its floor is approximately the safety lag plus the refresh's own duration plus the input's
ingest lag, and its ceiling is that floor plus one cron period. Two reads of the same unchanged cursor
therefore differ by the time between them, and both are correct. It is also **not** "time since the last
run" — it is derived from the cursor, so it bundles time-since-refresh with how far behind the cursor
already was. Distinguishing a stall from a healthy cadence requires reading it alongside the pipeline's
last run time, last error, and whether more input remains; an alert threshold on it MUST exceed one cron
period, or every cron slot fires. The API's state view SHALL describe it in these terms rather than as a
plain measure of how far behind the rollup is.

The service SHALL log the refresh path at INFO on the **successful** path, not only on failure. A
completed refresh SHALL log the pipeline, the rows written, the run's duration, and whether input
remains; a failure SHALL continue to log at WARN with the throwable, whose message alone (an NPE's is
null) says nothing an operator can act on. The one exception is a scheduled run refused by the pipeline read
budget: that refusal's message already names the ceiling, its configured value and the remedy, and the same
window is refused on every tick until something changes, so it SHALL be logged at WARN with its message and
without a stack, which is available at DEBUG. Accepted control-plane mutations — register, re-declare,
enable, disable, delete — SHALL likewise be logged, because they decide what subsequently gets written.

The scheduler tick SHALL emit one summary per tick reporting how many pipelines were due, refreshed,
skipped as no longer eligible, and failed, and how long the tick took. It SHALL be suppressed entirely
when nothing was due: the tick's cadence is configurable down to one minute, and a scheduled job must not
produce a log line per minute at rest.

The scheduler SHALL log one INFO line at startup giving whether aggregates are enabled, the tick cron and
the scheduler pool size, so the cadence a deployment actually runs is readable from its logs. A tick whose
duration runs past the tick's next scheduled fire SHALL log one WARN naming the tick's duration, the cron
and the pool size. With the default pool of one, an overrunning tick delays every aggregate pipeline, and
the per-tick summary alone does not say that a slot was lost.

A refresh that is held back or that finds nothing to write for the same reason tick after tick SHALL log
that reason only when it changes for that pipeline. That covers a window held back entirely because a
referenced enrichment has no usable position, and a probe that matched only rows above an enrichment's
bound. The change SHALL be logged at INFO, both on entering the state and on leaving it. A pipeline with
no new input rows SHALL stay at DEBUG. The state behind this throttle is held in memory per replica, and
the tick runs on every replica, so each replica logs its own transitions.

A refresh SHALL run inside an `INTERNAL`-kind parent span, so the `CLIENT` spans its statements raise are
attributable to the pipeline rather than appearing as disconnected roots. The compiled `INSERT … SELECT`
SHALL be available at DEBUG on the dedicated SQL logger (see [observability](../observability/spec.md)),
never on the refresh service's own logger. Preview returns the statement on demand, but a scheduled run
that behaved unexpectedly otherwise leaves nothing to compare against. The statement inlines literals, so
it is reachable only by a static level set at startup, and the SQL logger's pin and denylist keep a runtime
level change on the refresh service, or on any ancestor package, from printing it.

A metric series SHALL NOT be created for a pipeline name that is not in the registry. The name tags every
series and reaches these endpoints as a path variable, so instrumenting an unknown one would let arbitrary
spellings grow the meter registry without bound. A rejection that executes no statement — an unknown
pipeline, or a disabled one — is therefore not counted as a refresh run at all.

Deleting a pipeline SHALL likewise remove its series. This follows from the same rule read forwards — a
name not in the registry has no series — and matters because of how staleness is derived: a gauge left
behind measures the present against a watermark nothing will ever move again, so it climbs indefinitely
for a pipeline that no longer exists, raising exactly the signal a stall raises. Unlike the rollup's rows,
which a delete deliberately keeps, a series is only a statement about a running process.

#### Scenario: A deleted pipeline's series end with it

- **WHEN** `DELETE /v1/pipelines/{name}` removes a pipeline that had refreshed
- **THEN** no refresh-run, rows-written, duration, or staleness series remains tagged with that name, so its
  staleness does not go on climbing after the pipeline is gone

#### Scenario: Metrics reflect a refresh run

- **WHEN** a refresh completes
- **THEN** the refresh metrics (runs by outcome, rows written, duration) are updated for that pipeline

#### Scenario: A successful refresh is visible at INFO

- **WHEN** a refresh completes successfully
- **THEN** one INFO line reports the pipeline, the rows written, the duration, and whether input remains

#### Scenario: An idle tick logs nothing

- **WHEN** the scheduler tick fires and no pipeline is due
- **THEN** no tick summary is logged

#### Scenario: A tick that did work reports what it did

- **WHEN** the scheduler tick fires with at least one due pipeline
- **THEN** exactly one INFO summary reports how many were due, refreshed, skipped, and failed, and how
  long the tick took

#### Scenario: A refresh's client spans are attributable to it

- **WHEN** a refresh runs with tracing enabled
- **THEN** the ClickHouse `CLIENT` spans its probe and write raise are children of a single
  `INTERNAL`-kind span identifying the pipeline

#### Scenario: The scheduler's configuration is logged at startup

- **WHEN** the service starts
- **THEN** one INFO line reports whether aggregates are enabled, the tick cron and the scheduler pool size

#### Scenario: An overrunning tick is reported

- **WHEN** a tick is still running when its next scheduled fire time passes
- **THEN** one WARN reports the tick's duration, the cron and the pool size

#### Scenario: A held-back pipeline is logged on the change, not every tick

- **WHEN** a pipeline stays held back entirely by an enrichment with no usable position for several ticks
- **THEN** one INFO line is logged when it becomes held back and one when it stops, and none on the ticks in
  between

#### Scenario: Tracing the refresh service does not print its statement

- **WHEN** the refresh service's logger, or an ancestor package, is set to DEBUG or TRACE at runtime
- **THEN** the compiled `INSERT … SELECT` is not logged, because it is logged only on the pinned SQL logger

#### Scenario: A control-plane mutation is recorded in the log

- **WHEN** a pipeline is registered, re-declared, enabled, disabled, or deleted
- **THEN** the accepted mutation is logged, naming the pipeline and what changed

#### Scenario: Staleness is described as a sawtooth

- **WHEN** a caller reads the pipeline state view's staleness field documentation
- **THEN** it states that the value is sampled at observation time, varies between a floor and one cron
  period above it, and is not the time since the last run

#### Scenario: A stalled pipeline is visible as staleness

- **WHEN** an enabled pipeline has not refreshed successfully for longer than its schedule implies
- **THEN** its staleness metric grows between observations, without a run needing to occur, making the
  stall observable without inspecting logs

#### Scenario: An ineligible refresh creates no metric series

- **WHEN** `POST /v1/pipelines/{name}/refresh` is called for a name not in the registry, or for a disabled
  pipeline
- **THEN** the request is rejected and no refresh-run, duration, or staleness series is registered for that
  name

