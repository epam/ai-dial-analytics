# Proposal: add-otel-observability

## Why

ADAS currently exposes metrics only via the Prometheus scrape endpoint and has no distributed
tracing or centralized log export. Its sibling DIAL services (admin backend, MCP manager backend)
ship OpenTelemetry OTLP export of metrics, traces, and logs behind a common `OTEL_*` environment
variable surface, so deployments that already run an OTel collector cannot observe ADAS the same
way. Bringing ADAS in line closes that operational gap.

## What Changes

- Add OpenTelemetry OTLP export for **metrics, traces, and logs**, using Spring Boot 4's official
  `spring-boot-starter-opentelemetry` (Boot-native `management.*` configuration), mirroring the
  MCP manager backend's implementation — not the admin backend's Boot-3-era community starter.
- All OTLP export is **disabled by default**; a single `OTEL_EXPORT_ENABLED` master switch enables
  all three signals, with per-signal override flags and endpoints. Env-var names stay compatible
  with the other DIAL services (`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`, …).
- The existing Prometheus scrape endpoint stays active regardless — OTLP metrics export is additive.
- Log4j2 gains an `<OpenTelemetry>` appender (with startup-event buffering) installed
  programmatically, and `trace_id`/`span_id` in the console log pattern.
- Every HTTP response carries a W3C `traceparent` header derived from the current OTel span
  context (correlation interceptor).
- New env-var documentation table in `docs/configuration.md` (per project convention, the only
  place property descriptions live).

## Capabilities

### New Capabilities

- `observability`: OpenTelemetry OTLP export of metrics, traces, and logs; trace-context
  correlation (`traceparent` response header, trace IDs in log output); coexistence with the
  Prometheus scrape endpoint; disabled-by-default configuration surface.

### Modified Capabilities

_None. Existing capability requirements are unchanged; this is a cross-cutting operational
addition._

## Impact

- **Dependencies** (`build.gradle`): add `org.springframework.boot:spring-boot-starter-opentelemetry`,
  an `io.opentelemetry:opentelemetry-bom` platform pin, and
  `io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17`; test-scope
  `opentelemetry-exporter-otlp` and `micrometer-registry-otlp`.
- **Configuration**: new `management.opentelemetry.*` / `management.tracing.*` /
  `management.logging.*` / `management.otlp.metrics.*` block in `application.yml`; documented in
  `docs/configuration.md`.
- **Code**: small additions under `configuration/` (OTel log appender installer, correlation
  interceptor + WebMVC registration) and a trace-context utility; no changes to existing
  endpoints or business logic.
- **Logging**: `log4j2.xml` gains the OTel appender and trace-context pattern fields.
- **Known gap (documented, out of scope)**: the Arrow Flight SQL gRPC server is not
  auto-instrumented by Spring's observability; Flight traffic produces no HTTP-server metrics or
  spans.
