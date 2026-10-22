# Design: add-otel-observability

## Context

ADAS (Spring Boot 4.0.6, Java 25, Log4j2) exposes metrics only via the Prometheus actuator
endpoint on the main HTTP port. Two sibling DIAL services already export telemetry over OTLP:

- **ai-dial-admin-backend** (Boot 3.5.14): community
  `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.12.0`, configured through
  `otel.*` SDK-autoconfigure properties, master switch `OTEL_SDK_DISABLED`.
- **ai-dial-admin-mcp-manager-backend** (Boot 4.0.6): official
  `org.springframework.boot:spring-boot-starter-opentelemetry`, configured through Boot-native
  `management.*` properties, with `OTEL_*` env-var fallbacks preserving operational parity with
  the admin backend. Includes a context-runner guard test
  (`OpenTelemetryExportConfigurationTest`) pinning the env-var → property wiring.

The MCP manager backend is the donor implementation: it already performed the Boot-3 → Boot-4
migration of this exact setup, and its ADR-style comments encode the sharp edges (Boot defaults
export flags to `true`; endpoint properties are evaluated even when export is disabled).

## Goals / Non-Goals

**Goals:**

- OTLP export of metrics, traces, and logs, off by default, enabled by `OTEL_EXPORT_ENABLED`
  (master) or per-signal flags.
- Env-var surface compatible with the other DIAL services: `OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_TRANSPORT`, per-signal endpoint overrides.
- Prometheus scrape endpoint keeps working unchanged; OTLP metrics export is additive.
- Trace correlation: `traceparent` response header on every MVC request; `trace_id`/`span_id`
  in console log output; logs exported with trace context attached.
- Context must start cleanly with **no** OTEL env vars set (the default deployment).

**Non-Goals:**

- Instrumenting the Arrow Flight SQL gRPC server (no Spring auto-instrumentation exists for it;
  documented as a known gap in the spec).
- Moving actuator to a separate management port (MCP manager uses 9464; ADAS keeps the main port).
- `traceparent` in `ErrorView` responses (the MCP manager does this; ADAS's `ErrorView` contract
  is `{status, error, message, path, method}` and changing it is out of scope).
- Custom application metrics — this change wires the export pipeline only.

## Decisions

### D1: Official Boot 4 starter, not the community OTel starter

Use `org.springframework.boot:spring-boot-starter-opentelemetry` with `management.*` properties
(Path taken by the MCP manager backend). The admin backend's community starter 2.12.0 targets
Boot 3 and its autoconfiguration is not compatible with Boot 4's reshuffled modules. The official
starter is BOM-managed, first-party supported, and wires metrics through Micrometer's
`OtlpMeterRegistry`, so Prometheus and OTLP registries coexist naturally.

Consequence: configuration lives under `management.*` keys, but env-var names remain the
`OTEL_*` set via placeholder fallbacks (see D3), so operators see no difference from the
admin backend.

### D2: Copy the MCP manager backend's wiring, adapted to ADAS conventions

- `application.yml` OTel block translated from its `application.yml` (properties → yml, service
  name `analytics-data-access-service`), keeping the nested-fallback pattern:
  `${OTEL_METRICS_EXPORT_ENABLED:${OTEL_EXPORT_ENABLED:false}}` and
  `${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/metrics}`.
- The base endpoint default `http://localhost:4318` (OTLP/HTTP spec default) MUST stay: Spring
  evaluates endpoint properties in auto-configuration conditions even when export is disabled;
  an unresolvable placeholder would fail context startup in the default deployment.
- Export flags MUST be explicitly defaulted off in yml — Spring Boot defaults them to `true`.
- Per ADAS convention, `application.yml` stays bare `key: ${ENV:default}` lines; all property
  descriptions go to `docs/configuration.md` only.

### D3: Env-var compatibility layer via nested placeholders

One `OTEL_EXPORTER_OTLP_ENDPOINT` base URL fans out to per-signal endpoints with spec-standard
paths (`/v1/traces`, `/v1/logs`, `/v1/metrics`); per-signal `OTEL_EXPORTER_OTLP_<SIGNAL>_ENDPOINT`
vars override. `OTEL_EXPORT_ENABLED` is the master switch that each per-signal enabled flag
defaults to. This mirrors the MCP manager backend exactly, so Helm charts / collector setups
written for one service work for the other.

### D4: Log pipeline — OTel Log4j2 appender installed programmatically

The official starter does not auto-install the Log4j2 appender (the community starter did).
Add `<OpenTelemetry name="OpenTelemetryAppender" numLogsCapturedBeforeOtelInstall="1000"/>` to
`log4j2.xml` and a tiny `@Configuration` class that calls
`OpenTelemetryAppender.install(openTelemetry)` via `ObjectProvider<OpenTelemetry>.ifAvailable`.
The 1000-event buffer preserves startup logs emitted before the Spring context wires the SDK.
Console pattern gains `trace_id: %X{trace_id} span_id=%X{span_id}`.

Appender artifact: `io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17`, version
aligned with the OpenTelemetry API on the classpath (instrumentation 2.28.x ↔ SDK/API 1.62.x at
time of writing; verify against the Boot BOM's managed OTel version during implementation and
pin `opentelemetry-bom` only if the managed version is older than needed).

### D5: `traceparent` correlation interceptor

Port `TraceContextUtils` (formats W3C `traceparent` from `Span.current()`, guarding invalid/zero
trace and span IDs) and `CorrelationIdInterceptor` (sets the `traceparent` response header in
`preHandle`), registered at `Ordered.HIGHEST_PRECEDENCE` via a `WebMvcConfigurer`. Package
placement follows ADAS layout: `configuration/logging/` for the interceptor + config, a small
utility class for trace-context formatting.

### D6: Guard test ported from the MCP manager backend

Port `OpenTelemetryExportConfigurationTest` (ApplicationContextRunner +
`ConfigDataApplicationContextInitializer` over the OTel/tracing/metrics auto-configurations),
asserting: no exporter beans by default; master switch enables all three signals; enabling
export without an endpoint falls back to the OTLP spec default; per-signal endpoint override
works without the base var; `grpc` transport selects the gRPC span exporter. This test is what
makes the fiddly yml safely editable later.

## Risks / Trade-offs

- [Boot BOM's OTel version may lag or carry CVEs] → The MCP manager pins
  `opentelemetry-bom:1.62.0` over Boot's 1.55.0 for a baggage-propagation fix. During
  implementation, check the Boot 4.0.6 BOM's managed OTel version and apply the same platform
  pin if it is < 1.62.0. Keep the log4j-appender version in lockstep with the resulting API
  version.
- [Alpha-suffixed appender artifact] → `opentelemetry-log4j-appender-2.17:*-alpha` is the
  upstream-supported artifact (logs signal was stabilized later than traces/metrics); the MCP
  manager backend already ships it. Accept.
- [Flight SQL gRPC traffic invisible to HTTP metrics/traces] → Documented as a known gap in the
  spec; revisit if Flight observability becomes a requirement.
- [Nested placeholder fallbacks are subtle] → Guard test (D6) pins the wiring; docs table in
  `docs/configuration.md` describes precedence explicitly.
- [Tracing overhead when enabled] → Sampling probability configurable via
  `OTEL_TRACES_SAMPLING_PROBABILITY` (default 1.0, parent-based), matching the MCP manager.

## Migration Plan

Purely additive; default behavior (all export off, Prometheus unchanged) is identical to today.
No rollback steps beyond reverting the change. Enabling in an environment = setting
`OTEL_EXPORT_ENABLED=true` + `OTEL_EXPORTER_OTLP_ENDPOINT`.

## Open Questions

- Exact OTel API version managed by the Spring Boot 4.0.6 BOM (determines whether the
  `opentelemetry-bom` pin and which appender version are needed) — resolve at implementation
  time with `./gradlew dependencyInsight`.
