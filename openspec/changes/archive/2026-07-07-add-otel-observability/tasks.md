# Tasks: add-otel-observability

## 1. Dependencies

- [x] 1.1 Check the OTel version managed by the Spring Boot 4.0.6 BOM
      (`./gradlew dependencyInsight --dependency io.opentelemetry:opentelemetry-api`); decide
      whether an `opentelemetry-bom` platform pin (≥ 1.62.0, per design D1/risks) is needed and
      which `opentelemetry-log4j-appender-2.17` version aligns with it
- [x] 1.2 Add to `build.gradle`: `org.springframework.boot:spring-boot-starter-opentelemetry`,
      the `opentelemetry-log4j-appender-2.17` artifact (and the `opentelemetry-bom` platform pin
      if 1.1 determined it is needed), with rationale comments matching the file's existing style
- [x] 1.3 Add test-scope deps: `io.opentelemetry:opentelemetry-exporter-otlp`,
      `io.micrometer:micrometer-registry-otlp` (compile-time visibility for the guard test)

## 2. Configuration

- [x] 2.1 Add the OTel block to `application.yml` (bare `key: ${ENV:default}` lines, no comments):
      `management.opentelemetry.resource-attributes."service.name"` (default
      `analytics-data-access-service`), tracing/logging OTLP endpoints + transport with nested
      `OTEL_EXPORTER_OTLP_<SIGNAL>_ENDPOINT` → `OTEL_EXPORTER_OTLP_ENDPOINT` →
      `http://localhost:4318` fallbacks, `management.tracing.export.enabled`,
      `management.logging.export.otlp.enabled`, `management.otlp.metrics.export.enabled` + `url`
      (each per-signal flag defaulting to `${OTEL_EXPORT_ENABLED:false}`), and
      `management.tracing.sampling.probability` (`OTEL_TRACES_SAMPLING_PROBABILITY:1.0`)
- [x] 2.2 Update `log4j2.xml`: add
      `<OpenTelemetry name="OpenTelemetryAppender" numLogsCapturedBeforeOtelInstall="1000"/>`,
      reference it from the Root logger, and add `trace_id: %X{trace_id} span_id=%X{span_id}` to
      the console pattern

## 3. Code

- [x] 3.1 Add `OpenTelemetryLogAppenderConfiguration` under `configuration/` — constructor takes
      `ObjectProvider<OpenTelemetry>` and calls `OpenTelemetryAppender::install` via
      `ifAvailable` (official starter does not auto-install the appender)
- [x] 3.2 Add a trace-context utility (formats W3C `traceparent` from `Span.current()`, returning
      null for invalid/all-zero trace or span IDs) — port of the MCP manager backend's
      `TraceContextUtils`, adapted to `com.epam.aidial.analytics` package conventions
- [x] 3.3 Add `CorrelationIdInterceptor` (sets `traceparent` response header in `preHandle`) and
      register it at `Ordered.HIGHEST_PRECEDENCE` via a `WebMvcConfigurer` under
      `configuration/logging/`

## 4. Tests

- [x] 4.1 Port `OpenTelemetryExportConfigurationTest` from the MCP manager backend
      (ApplicationContextRunner + `ConfigDataApplicationContextInitializer` over the
      OTel/tracing/metrics auto-configurations): no exporter beans by default; master switch
      enables traces+logs+metrics; enabled-without-endpoint falls back to
      `http://localhost:4318`; per-signal endpoint override without base var; `grpc` transport
      selects the gRPC span exporter
- [x] 4.2 Add a `CorrelationIdInterceptor` unit test: valid span context → `traceparent` header
      set in W3C format; invalid/absent context → no header
- [x] 4.3 Verify existing tests still pass with the new appender present and export disabled
      (default context startup, per spec "OTLP export disabled by default")

## 5. Documentation & spec sync

- [x] 5.1 Add an "OpenTelemetry Configuration" table to `docs/configuration.md` (the only place
      property descriptions live): all `OTEL_*` vars, defaults, precedence of per-signal vs
      master/base fallbacks, Prometheus coexistence note, and the Arrow Flight SQL
      instrumentation gap
- [x] 5.2 Mention observability briefly in `README.md` prose (no env-var tables) linking to
      `docs/configuration.md`, if README structure warrants it
- [x] 5.3 Run `./gradlew clean build` (compile + checkstyle + tests) and confirm green
- [x] 5.4 Confirm the delta spec `specs/observability/spec.md` matches shipped behavior; adjust
      if implementation details diverged (e.g., final appender buffer size, BOM pin outcome)
