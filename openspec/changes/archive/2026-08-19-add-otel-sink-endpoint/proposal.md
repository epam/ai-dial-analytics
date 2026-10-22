## Why

ADAS can only ingest DIAL usage logs (the `data-sync` blob path) or rows a caller hand-shapes through
`POST /v1/tables/{name}/rows`. Every other telemetry producer in the DIAL estate already speaks OTLP —
the OpenTelemetry line protocol — and today has nowhere to send it: standing up a ClickHouse landing
zone for logs, traces and metrics means hand-writing three schemas per producer, wiring an exporter,
and keeping the two in step. ADAS already owns the catalog, the writer credential, the DDL renderer and
the query surface, so it is the natural place to provision an OTLP landing zone on request and expose
what lands there through the same governed read path as every other table.

## What Changes

- **New `otel-sink` capability.** A `POST /v1/otel/sinks` control-plane call provisions a *sink*: a named
  OTLP landing zone. The caller names the sink and opts into one, two, or all three signals (`logs`,
  `traces`, `metrics`). ADAS creates one catalog source table per opted-in signal — `otel_<sink>_logs`,
  `otel_<sink>_traces`, `otel_<sink>_metrics` — materializes each in ClickHouse with a fixed
  OTLP-shaped schema, and records the sink. `GET /v1/otel/sinks`, `GET /v1/otel/sinks/{name}` and
  `DELETE /v1/otel/sinks/{name}` complete the control plane.
- **New OTLP ingest endpoint rooted at `/v1/otel/sink/{sink_name}`.** The sink's base URL is exactly what
  an OTLP/HTTP exporter is configured with; the exporter appends the standard signal paths, so the live
  endpoints are `POST /v1/otel/sink/{sink_name}/v1/logs`, `.../v1/traces` and `.../v1/metrics`. Payloads
  are **OTLP/HTTP JSON** (`Content-Type: application/json`); `application/x-protobuf` is refused with
  `415 unsupported_media_type` (protobuf encoding is out of scope for this change).
- **OTLP attributes land in ClickHouse `Map(String, String)` columns** — resource, scope and
  record/span/data-point attributes each get their own map column. This needs a new `map` entry in the
  catalog's column-type vocabulary (`table-management`), which then has to be honoured by every read
  surface: it is projection-only in the DSL and in SQL (like `object`/`array`), surfaces as a JSON object
  over REST, as `Utf8` JSON text over Flight SQL, and as `CHARACTER VARYING` in `INFORMATION_SCHEMA`.
- **A new Postgres catalog table pair** (`otel_sink`, `otel_sink_signal`, Flyway `V30`) records each sink
  and the table backing each of its signals.
- **Global 415 mapping.** `HttpMediaTypeNotSupportedException` currently falls through to the catch-all
  and renders as `500 internal_error`; it is mapped to `415 unsupported_media_type` in
  `DefaultExceptionHandler`, which the OTLP endpoints rely on and every endpoint benefits from.
- **New config group `otel-sink.*`** (enable flag, per-request row cap, default retention TTL) documented in
  `docs/configuration.md`.

Explicitly **not** in this change, and stated so in the spec: OTLP/protobuf encoding, gRPC OTLP,
span events/links as first-class relations (they are stored as JSON columns), metric exemplars,
partial-success accounting (a rejected batch is rejected whole), and adding a signal to an existing
sink (delete and recreate).

## Capabilities

### New Capabilities

- `otel-sink`: provisioning OTLP sinks and their ClickHouse landing tables, and the OTLP/HTTP JSON
  ingest endpoints those sinks expose — including the OTLP→row mapping, the authorization model, and
  the error contract.

### Modified Capabilities

- `table-management`: the column-type vocabulary gains `map`, rendered as ClickHouse
  `Map(String, String)` — declaration rules, nullability, and its rejection as an `array` element type.
- `structured-query`: `map` fields are projection-only in the DSL and render as a JSON object in the
  response envelope.
- `sql-query-translation`: `map` fields are projection-only in SQL and surface as `CHARACTER VARYING`
  in `INFORMATION_SCHEMA.COLUMNS`.
- `query-schema-discovery`: `map` is a value of a discovered field's `type`.
- `flight-sql-endpoint`: a `map` result column travels as `Utf8` carrying JSON text and reports
  `VARCHAR` in the XDBC type metadata.

## Impact

- **APIs**: new `/v1/otel/sinks` control plane (FULL_ADMIN) and `/v1/otel/sink/{name}/v1/{signal}` data
  plane (authenticated + per-table `write` grant, mirroring `POST /v1/tables/*/rows`). New `ErrorView`
  codes `unsupported_media_type` (415) and reuse of `not_found`/`conflict`/`validation_error`.
- **Catalog schema**: Postgres `V30__otel_sink.sql` adds `otel_sink` and `otel_sink_signal`. The sink's
  signal tables are ordinary (non-`system`) catalog source tables, so they are visible under
  `/v1/tables` and queryable under `/v1/queries` with no further work.
- **Code**: new `web/controller/otel/`, `web/dto/otel/`, `service/otel/` packages; `QueryFieldType`,
  `ClickHouseTypeMapper`, `JooqTypes`, `ResultColumns`, `CalciteQueryCatalog`, `InformationSchema`,
  `SqlProjectionOnlyTypes`, `ArrowTypeMapper`/`ArrowBatchWriter`, `FlightCatalogStreams`,
  `AggregateSpecValidator`, `ExprSqlValidator`, `TableColumnRules` all gain the `map` case;
  `OidcSecurityConfiguration` gains the ingest path matcher; `DefaultExceptionHandler` gains the 415 map.
- **Operations**: no new credential — sink DDL and OTLP inserts use the existing writer credential, so
  the writer grants already provisioned (`CREATE`/`DROP TABLE`, `INSERT`) cover it. No new external
  dependency: OTLP/JSON is parsed with the configured Jackson mapper.
- **Docs**: `docs/configuration.md` gains an `otel-sink.*` table; `README.md` gets an inline mention only.
