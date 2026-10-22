## ADDED Requirements

### Requirement: Loggers that can print SQL literals or HTTP bodies are pinned at INFO

Every statement text the service logs SHALL be logged only on the dedicated logger
`com.epam.aidial.analytics.sql`, at DEBUG. That covers the Class-1 and aggregate `INSERT … SELECT`, the
structured query and the pipeline scan, and every message that can quote a caller's statement: the SQL
parser's diagnosis, the identifier probe's parse failure and a Flight SQL statement failure. A statement
inlines its literals, which include filter values, cursor tuples and group keys, so no other logger SHALL
carry it at any level. The log configuration SHALL declare an explicit INFO level for
`com.epam.aidial.analytics.sql`, `org.springframework.web.client`, `org.springframework.http.converter`,
`org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind` and `org.springframework.jdbc.core`: the Enrichment
Runner's pinned set, plus this service's SQL logger. The Hibernate and JDBC loggers print the catalog's
statements and their bound values, which include pipeline declarations and saved queries. Each is its own
logger configuration, so a level set on an ancestor (`com.epam`, `com.epam.aidial.analytics`,
`org.springframework`, `org.hibernate`) leaves the pinned logger at INFO. The log
configuration SHALL NOT enable file monitoring, so the runtime-levels mechanism is the only thing that
changes levels after startup.

A statement can still be logged at DEBUG through a static level on the SQL logger, set at startup through
`logging.level.*`. That is a deliberate, restart-scoped decision by whoever controls the deployment's
environment.

#### Scenario: Tracing an executor does not print its statement

- **WHEN** `ClickHouseInsertSelectExecutor` or `StructuredQueryExecutor` is set to TRACE
- **THEN** no statement text is logged, because statements are logged only on the SQL logger, which is still
  at INFO

#### Scenario: An ancestor at DEBUG leaves the pins in place

- **WHEN** `com.epam.aidial.analytics`, `org.springframework` or `org.hibernate` is set to DEBUG
- **THEN** every pinned logger stays at INFO

### Requirement: Per-logger levels change at runtime from a mounted file

The service SHALL poll a JSON file at `observability.log-levels.path` (default
`/app/log-config/logging.levels.json`) every `observability.log-levels.interval` (a duration, default `10s`,
refused at startup when below `1s`), and apply the levels it names without a restart. The file has the form
`{"loggerLevels": {"<logger>": {"defaultLevel": "INFO", "configuredLevel": "DEBUG", "validTill": <epoch ms>}}}`.
An entry's `configuredLevel` SHALL apply while `validTill` is in the future or absent. After that the entry's
`defaultLevel` SHALL apply. An entry with neither in effect SHALL restore the logger's baseline. Level names
SHALL be matched case-insensitively against TRACE, DEBUG, INFO, WARN, ERROR, FATAL and OFF. A null, empty or
unknown level SHALL skip that entry with one WARN.

A level SHALL be applied to the exact logger named, and to no other: a logger's own configuration, including
an XML pin on a descendant, SHALL be left in place. Before the service first changes a name, it SHALL record
that name's own configured level (none when the name has no configuration of its own) as the baseline. An
entry that leaves the file, or expires with no `defaultLevel`, SHALL restore that baseline, or SHALL remove the
configuration the service created when there was none. It SHALL NOT clear a pinned logger's configuration so
that the logger inherits from its parent.

The service SHALL re-apply only when the file's content hash changes or a `validTill` passes. It SHALL
overwrite nothing on a tick where neither happened.

| File state | Behaviour |
|---|---|
| missing at startup | one INFO naming the path; nothing applied |
| removed after levels were applied | every applied level reverts to its baseline; one INFO |
| not parsable | the last applied state is kept; one WARN per distinct content hash |

A level below INFO SHALL be refused, with one INFO, for every pinned logger and every descendant of each. A
change to an ancestor of these loggers SHALL be accepted, because the pins keep them at INFO.

Each applied change SHALL log one INFO line: `log level {logger}: {old} -> {new} (source=file, until={validTill|-})`.
An entry that applies a level below INFO with no `validTill` SHALL additionally log one WARN, because a DEBUG
or TRACE entry is meant to be time-boxed.

The file is delivered by a ConfigMap and reaches every replica. The service exposes no endpoint for it, and
actuator exposure is unchanged. The same file format, property names and log line are used by the
Enrichment Runner.

#### Scenario: A time-boxed DEBUG applies and reverts on its own

- **WHEN** the file sets a logger's `configuredLevel` to DEBUG with a `validTill` one minute ahead and a
  `defaultLevel` of INFO
- **THEN** that logger is at DEBUG within one poll interval, and at INFO within one poll interval after
  `validTill` passes

#### Scenario: A removed entry restores a pinned logger's own level

- **WHEN** an entry that changed a logger pinned in the XML is removed from the file
- **THEN** the logger returns to its XML level, not to its parent's level

#### Scenario: A denylisted descendant is refused

- **WHEN** the file sets `com.epam.aidial.analytics.sql.anything` to DEBUG
- **THEN** the entry is refused with one INFO and the logger's level is unchanged

#### Scenario: An unparsable file keeps the last state

- **WHEN** the file is replaced by invalid JSON after levels were applied
- **THEN** the applied levels stay in effect and one WARN is logged for that content, however many polls see it

#### Scenario: An unchanged file does nothing

- **WHEN** the file's content is unchanged and no `validTill` has passed since the last poll
- **THEN** no level is set on that poll

#### Scenario: An interval below one second is refused

- **WHEN** the service starts with `observability.log-levels.interval` set to `500ms`
- **THEN** startup fails naming the property

### Requirement: Annotated components emit execution trace lines on demand

A component annotated with `@LogExecution`, on its type or on a method, SHALL log through the target class's
own logger, and only while that logger is at TRACE:

- `enter {Class}.{method}()` on entry
- `exit {Class}.{method}() in {ms} ms` on return
- `exception {Class}.{method}() in {ms} ms: {ExceptionSimpleName}` on a throw, after which the exception is
  rethrown unchanged

The lines SHALL NOT carry arguments, return values, exception messages, stack traces or bodies. The logger's
level SHALL be read on every call, so a runtime level change takes effect on the next call. At any level above
TRACE, the only cost SHALL be the proxy hop and a level check, and the method SHALL be invoked exactly once.
Methods declared by `Object` SHALL NOT be traced. `observability.execution-trace.enabled` (default `true`)
SHALL remove the advice entirely when `false`.

The service SHALL annotate `MaterializeService`, `ScanService`, `GroupFetchService`, `EnrichWriteService`,
`AggregateRefreshService`, `PipelineService`, `ClickHouseInsertSelectExecutor` and `StructuredQueryExecutor`.
The advice applies through Spring proxies, so self-invocations, private methods, final classes and records
are not traced. `StructuredQueryExecutor` also serves every user query, so a TRACE on it traces them all.
Each line inherits the request's log context.

#### Scenario: TRACE on a class shows its calls

- **WHEN** `MaterializeService` is set to TRACE and a materialize call runs
- **THEN** an enter line and an exit line with a duration are logged for each traced method it passes through

#### Scenario: At INFO nothing is logged

- **WHEN** an annotated class's logger is at INFO
- **THEN** no trace line is logged and each annotated method runs exactly once

#### Scenario: An exception is traced without its message

- **WHEN** a traced method throws an exception whose message contains a response body
- **THEN** the exception line names the class, the method, the duration and the exception's simple class
  name, contains no part of the message, and the same exception reaches the caller

#### Scenario: The switch removes the advice

- **WHEN** the service starts with `observability.execution-trace.enabled=false`
- **THEN** no annotated component is proxied for tracing

### Requirement: Pipeline requests carry a log context and an access line

Every request under `/v1/pipelines/**` SHALL run with the MDC keys `pipeline_name` (the `{name}` path
segment, where there is one), `request_id` (generated per request) and `caller` (the authenticated
principal's name, `-` when there is none). These keys are removed when the request completes. Every log line
the request produces, including execution trace lines, SHALL therefore be attributable to it. The key
spelling `pipeline_name` SHALL be the one the Enrichment Runner uses.

Each such request SHALL log one INFO access line when it completes:
`http {method} {path} pipeline={} status={} ms={} caller={} contract={}`, where `contract` is the
`Adas-Contract-Version` request header value or `-`. That includes requests refused by the security chain
or by the contract check. The line SHALL carry no query string, request body or response body. Requests
outside that prefix SHALL be logged at DEBUG, and actuator requests SHALL NOT be logged. The Flight SQL
listener is not a servlet path, so this logging does not cover it.

#### Scenario: A materialize call is logged with its context

- **WHEN** the runner calls `POST /v1/pipelines/p1/materialize`
- **THEN** one INFO access line reports the method, the path, `pipeline=p1`, the status, the duration, the
  caller and the contract version, and every other line the call logs carries `pipeline_name=p1` and the same
  `request_id`

#### Scenario: A refused call is still logged

- **WHEN** a data-plane call is refused with `401` by the security chain
- **THEN** one INFO access line reports the `401`

### Requirement: Exported logs carry an explicit set of context attributes

When log export is enabled, each exported log record SHALL carry the MDC keys `pipeline_name`, `request_id`,
`caller` and `query_id`, when they are set, as log attributes. No other MDC key SHALL be exported, so a key
added later for local diagnosis cannot leak into the collector by default. Trace and span ids keep reaching
the record through its trace context, as before. Console output SHALL include `pipeline_name` and
`request_id`.

#### Scenario: An exported pipeline line is filterable by pipeline

- **WHEN** log export is enabled and a materialize call for `p1` logs its outcome line
- **THEN** the exported record carries the attributes `pipeline_name=p1`, the call's `request_id`, the
  `caller`, and the outcome's `query_id`

### Requirement: Every pipeline ClickHouse statement carries a traceable query id

Every statement issued on a pipeline's behalf SHALL set a ClickHouse `query_id` of the form
`adas-{pipeline}-{stage}-{uuid}`. That covers the scan page, both phases of a group fetch, each materialize
probe and re-probe, each materialize write attempt, the aggregate refresh probe and write, and the previews.
`stage` names the statement's role. Each narrowing retry and each re-probe SHALL get a fresh id, never one
copied from the statement before it. The id SHALL be recorded on the statement's ClickHouse `CLIENT` span and
on the call's outcome line, so a log line can be joined to `system.query_log`. No pipeline statement SHALL go
through a client call that takes no per-query settings.

#### Scenario: Each narrowing attempt has its own id

- **WHEN** a materialize write is refused, narrows, re-probes and retries
- **THEN** the first write, the re-probe and the retried write each carry a distinct `query_id`, all
  prefixed with the pipeline's name

#### Scenario: The id is on the span

- **WHEN** a pipeline statement runs while tracing is enabled
- **THEN** its `CLIENT` span carries the statement's `query_id`

### Requirement: Refusals are logged without stacks and rate-limited

The error handler SHALL log a refusal of a caller's request without a stack trace. That covers ClickHouse
rejecting a query, ClickHouse unavailable, a PostgreSQL data-access error, the preview budget, the query
budget, the pipeline read budget and the runner contract. It SHALL log at most one line per refusal kind and
request path pattern per minute, and each line after the first SHALL give the number suppressed since the
previous line when any were. A pipeline read-budget refusal SHALL be limited per pipeline as well: every
pipeline shares the pattern, and one pipeline refused on every call must not hide another's first refusal.
The pipeline name is bounded there, because that refusal comes only after the name resolved. The stack trace SHALL be available at DEBUG. The runner-contract refusal line SHALL name the
caller, together with the code and both versions it already names. The `409 pipeline_conflict` refusal SHALL
be logged at INFO without a stack. The catch-all handler for an unexpected exception, which answers `500`,
SHALL log at ERROR with the stack. A failure to record a materialize's bookkeeping SHALL keep its WARN with the
message and SHALL add the throwable at DEBUG.

The rate limit is held in memory per replica, so each replica logs its own first line.

The `ErrorView` responses, status codes and `error` codes are unchanged.

#### Scenario: A skewed runner does not flood the log

- **WHEN** the runner sends a mismatched contract version on many calls within one minute
- **THEN** one WARN line without a stack names the reason, both versions and the caller, and the next line
  after the minute reports how many were suppressed

#### Scenario: Two pipelines refused by the read budget are both logged

- **WHEN** one pipeline's materialize calls are refused by the pipeline read budget many times within a minute,
  and another pipeline's call is refused once
- **THEN** each pipeline's first refusal is logged

#### Scenario: An unexpected failure is an error

- **WHEN** a handler throws an exception no other handler maps
- **THEN** the response is `500` and the service logs one ERROR line with the stack

### Requirement: Log lines name a cursor position by digest

Every log line that names a cursor position SHALL give it as `(version_ms,#8hex)`: the version half as the
source's epoch-millisecond version, and the identity half as the first four bytes of its SHA-256 in hex. That
covers the materialize outcome line's clamp bound, the aggregate refresh's clamped-window line and the
position writer's lines. The identity is a source column value the service cannot assume is free of user
data, so no line SHALL carry it at any level. Equal identities give equal digests, so two lines still show
whether a bound or a mark moved.

#### Scenario: A clamped refresh names its bound by digest

- **WHEN** an aggregate refresh's window is clamped to an enrichment's position
- **THEN** its INFO line gives the bound's version and the digest of its identity, and not the identity
