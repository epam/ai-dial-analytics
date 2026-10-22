# observability — delta spec

## ADDED Requirements

### Requirement: OTLP export disabled by default
The service SHALL NOT export any telemetry (metrics, traces, or logs) over OTLP unless export is
explicitly enabled via configuration. The application context MUST start successfully when no
`OTEL_*` environment variables are set.

#### Scenario: Default startup with no OTEL configuration
- **WHEN** the application starts with no `OTEL_*` environment variables set
- **THEN** the context starts successfully and no OTLP exporter beans (span, log record, or
  OTLP meter registry) are created

### Requirement: Master switch enables all telemetry signals
Setting `OTEL_EXPORT_ENABLED=true` SHALL enable OTLP export of metrics, traces, and logs
together. Per-signal flags (`OTEL_TRACES_EXPORT_ENABLED`, `OTEL_LOGS_EXPORT_ENABLED`,
`OTEL_METRICS_EXPORT_ENABLED`) SHALL default to the master switch value and SHALL override it
per signal when set.

#### Scenario: Master switch enables all signals
- **WHEN** the application starts with `OTEL_EXPORT_ENABLED=true` and
  `OTEL_EXPORTER_OTLP_ENDPOINT` pointing at a collector
- **THEN** OTLP exporters for traces, logs, and metrics are all active

#### Scenario: Per-signal flag overrides master switch
- **WHEN** the application starts with `OTEL_TRACES_EXPORT_ENABLED=true` and no other OTEL flags
- **THEN** only the trace exporter is active; log and metric OTLP export remain disabled

### Requirement: Collector endpoint resolution
The service SHALL resolve OTLP endpoints from a single base URL
(`OTEL_EXPORTER_OTLP_ENDPOINT`, no trailing slash) by appending the OTLP signal paths
(`/v1/traces`, `/v1/logs`, `/v1/metrics`). Per-signal endpoint variables
(`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`,
`OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`) SHALL override the derived URL for their signal. When no
endpoint is configured, the base URL SHALL default to `http://localhost:4318` (the OTLP/HTTP
specification default) so that endpoint properties always resolve.

#### Scenario: Export enabled without explicit endpoint
- **WHEN** export is enabled but no `OTEL_EXPORTER_OTLP_ENDPOINT` is set
- **THEN** exporters target the OTLP spec default `http://localhost:4318` with the appropriate
  signal path appended

#### Scenario: Per-signal endpoint override
- **WHEN** `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` is set to a full URL and the base endpoint is
  unset
- **THEN** the trace exporter targets the per-signal URL exactly

### Requirement: OTLP transport selection
The service SHALL support `http` (default) and `grpc` OTLP transports for traces and logs via
`OTEL_EXPORTER_OTLP_TRANSPORT`. Metrics SHALL be exported over HTTP.

#### Scenario: gRPC transport selected
- **WHEN** export is enabled with `OTEL_EXPORTER_OTLP_TRANSPORT=grpc`
- **THEN** the trace exporter uses OTLP over gRPC instead of HTTP

### Requirement: Prometheus endpoint unaffected by OTLP export
The Prometheus scrape endpoint (`/actuator/prometheus`) SHALL remain active and serve the same
metrics regardless of OTLP export flags. OTLP metrics export is additive.

#### Scenario: OTLP export enabled alongside Prometheus
- **WHEN** OTLP metrics export is enabled
- **THEN** `/actuator/prometheus` continues to respond with the metrics exposition, and the same
  meters are also pushed over OTLP

### Requirement: Trace context in HTTP responses
Every HTTP response processed by the MVC layer SHALL carry a W3C `traceparent` header
(`00-{trace-id}-{span-id}-{trace-flags}`) derived from the current OpenTelemetry span context,
when a valid span context exists. When no valid trace context is available (e.g., tracing
disabled), the header MAY be absent.

#### Scenario: Traceparent header set on response
- **WHEN** a request is processed while a valid OpenTelemetry span context is active
- **THEN** the response includes a `traceparent` header in W3C Trace Context format

#### Scenario: No valid trace context
- **WHEN** a request is processed with tracing disabled or an invalid (all-zero) span context
- **THEN** the response is served normally without a `traceparent` header

### Requirement: Log export with trace correlation
Application log events SHALL be routed to the OpenTelemetry SDK via the OTel Log4j2 appender so
that, when log export is enabled, log records reach the collector with trace context attached.
Log events emitted before the Spring context installs the SDK SHALL be buffered (bounded) rather
than lost. Console log output SHALL include `trace_id` and `span_id` fields.

#### Scenario: Logs exported with trace context
- **WHEN** log export is enabled and a log statement executes within an active span
- **THEN** the log record is exported over OTLP carrying that span's trace context

#### Scenario: Startup logs buffered before SDK installation
- **WHEN** log events are emitted during application startup before the OpenTelemetry SDK is
  installed
- **THEN** up to the configured buffer size of those events is retained and forwarded once the
  SDK is installed

### Requirement: Service identity and env-var parity with DIAL services
Telemetry SHALL carry the resource attribute `service.name`, defaulting to
`analytics-data-access-service` and overridable via `OTEL_SERVICE_NAME`. Additional resource
attributes SHALL be settable via `OTEL_RESOURCE_ATTRIBUTES`. The environment-variable surface
(`OTEL_EXPORT_ENABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_TRANSPORT`,
per-signal flags and endpoints, `OTEL_TRACES_SAMPLING_PROBABILITY`) SHALL match the conventions
used by the other DIAL admin services.

#### Scenario: Default service name
- **WHEN** telemetry is exported without `OTEL_SERVICE_NAME` set
- **THEN** its `service.name` resource attribute is `analytics-data-access-service`

### Requirement: Trace sampling configuration
When tracing is enabled, the service SHALL use a parent-based ratio sampler with probability
configurable via `OTEL_TRACES_SAMPLING_PROBABILITY`, defaulting to 1.0.

#### Scenario: Default sampling
- **WHEN** tracing is enabled without a sampling probability configured
- **THEN** all new traces are sampled (probability 1.0), honoring upstream sampling decisions
  for propagated contexts

### Requirement: Arrow Flight SQL instrumentation gap is documented
The configuration documentation SHALL state that the Arrow Flight SQL gRPC endpoint is not
auto-instrumented: Flight traffic produces no HTTP-server metrics or spans.

#### Scenario: Flight SQL request with export enabled
- **WHEN** a Flight SQL request is served while OTLP export is enabled
- **THEN** no server-side span or HTTP-server metric is produced for that request (JVM- and
  process-level metrics still reflect the work)
