## MODIFIED Requirements

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
null) says nothing an operator can act on. Accepted control-plane mutations — register, re-declare,
enable, disable, delete — SHALL likewise be logged, because they decide what subsequently gets written.

The scheduler tick SHALL emit one summary per tick reporting how many pipelines were due, refreshed,
skipped as no longer eligible, and failed, and how long the tick took. It SHALL be suppressed entirely
when nothing was due: the tick's cadence is configurable down to one minute, and a scheduled job must not
produce a log line per minute at rest.

A refresh SHALL run inside an `INTERNAL`-kind parent span, so the `CLIENT` spans its statements raise are
attributable to the pipeline rather than appearing as disconnected roots. The compiled `INSERT … SELECT`
SHALL be available at DEBUG on the refresh path; preview returns it on demand, but a scheduled run that
behaved unexpectedly otherwise leaves nothing to compare against.

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
