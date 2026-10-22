# Tasks

## 1. The `map` column type

- [x] 1.1 Add `MAP("map")` to `QueryFieldType`; render it as `Map(String, String)` in
      `ClickHouseTypeMapper` (never `Nullable`, and reject `map` as an `array` `element_type`).
- [x] 1.2 Enforce the declaration rules in `TableColumnRules`: a `map` column takes no `element_type`,
      is always non-nullable (explicit `nullable: true` → 422), and cannot be an `array` element type.
- [x] 1.3 Reject a `map` column as `ordering_key`, `partition_by`, `grain_key`, TTL column
      (`TableTtlRules`), `identity_column`/`version_column` (`ScanMetadataRules`).
- [x] 1.4 Read path: `ResultColumns.scalarKind(MAP) → ResultColumnKind.OBJECT`; `JooqTypes` →
      `SQLDataType.OTHER`; `CalciteQueryCatalog.fieldType` → `VARCHAR` (with `scalarType` rejecting
      `MAP` like `OBJECT`); `InformationSchema` → `CHARACTER VARYING` with no numeric/datetime facets.
      (`FlightCatalogStreams` and `ArrowTypeMapper` switch on `ResultColumnKind`, which `MAP` shares
      with `OBJECT`, so they needed no change — that is the point of 1.4's kind reuse.)
- [x] 1.5 Classify `MAP` as projection-only in `SqlProjectionOnlyTypes` (the hard-coded "an" article is
      now derived, so `map` reads "a map field"); reject `MAP` in `AggregateSpecValidator` alongside
      `ARRAY`/`OBJECT`. (`ExprSqlValidator` switches on `ResultColumnKind` and already rejects the
      `OBJECT` family.)
- [x] 1.6 Unit tests: `ClickHouseTypeMapperTest` (map DDL, nullable not wrapped, element-type rejected,
      map rejected as an array element) and `TableColumnRulesTest` (nullability resolution and the
      three rejections).
- [x] 1.7 Integration coverage: the sink's landing tables declare `map` columns, rows written through
      the OTLP path read back through `/v1/queries/execute-sql` as JSON objects, discovery reports
      `type: map`, and a `map` field used as a sort key is rejected — all in
      `OtelSinkIntegrationTest`, which exercises the type on the surface that actually needs it.

## 2. Sink catalog (Postgres)

- [x] 2.1 Add `V30__otel_sink.sql` creating `otel_sink` (`name` PK, `description`, `created_at`) and
      `otel_sink_signal` (`sink_name` FK → `otel_sink`, `signal`, `table_name` UNIQUE,
      PK `(sink_name, signal)`). `V30` is the next version on merged the default branch (top: `V29`).
- [x] 2.2 Add `service/otel/OtelSinkEntity`, `OtelSinkSignalEntity` and `OtelSinkRepository`
      (in `service/otel/`, per the project's no-`repository`-package rule). Not `@Audited`.

## 3. Sink provisioning service

- [x] 3.1 Add `service/otel/OtelSignal` (`LOGS`, `TRACES`, `METRICS`, lowercase `@JsonValue` codes) and
      `OtelTableNames` (the single `otel_<sink>_<signal>` derivation, plus the ≤51-char sink-name rule).
- [x] 3.2 Add `service/otel/OtelSinkSchemas`: the fixed per-signal `DefineSchemaInput` (columns,
      ordering key, `PARTITION BY event_time` at `day`, optional TTL) exactly as specified in
      `specs/otel-sink/spec.md`, "OTLP landing table schemas".
- [x] 3.3 Add `service/otel/OtelSinkService`: create (validate name/signals, reject duplicate sink 409,
      reject colliding table name 409, create + materialize each signal's table via
      `TableCatalogService.create` + `TableSchemaCreationService.defineSchema`, persist the sink;
      compensate by deleting every table it created on any failure), list, get, delete (drop each
      backing table via `TableCatalogService.delete`, skipping ones already gone, then remove the sink).
- [x] 3.4 Add `service/otel/OtelSinkAccess` (`@Component("otelSinkAccess")`) exposing
      `canIngest(sinkName, signalCode)` that derives the table name and delegates to
      `TableAccessEvaluator.canWrite`.

## 4. OTLP decoding

- [x] 4.1 Add `service/otel/otlp/OtlpValues`: AnyValue → String rendering (string/bool/int/double/bytes,
      array/kvlist as compact JSON, absent → empty), nanos → millis timestamp, hex id normalization,
      dual field-name lookup (lowerCamelCase and proto snake_case), non-finite double → null.
- [x] 4.2 Add `service/otel/otlp/OtlpScope` (resource + scope facts resolved once per pair) and
      `OtlpFlattener` producing `List<Map<String, Object>>` keyed by physical column name, per the
      spec's mapping requirement. **One class rather than the three planned**: the
      `resourceX → scopeX → records` fan-out is identical for all three signals, so it lives once and
      each signal contributes only its per-record mapping.
- [x] 4.3 Add `service/otel/OtelIngestService`: resolve the sink + signal (404 when unknown or not
      enabled), require the backing table present and `ACTIVE` (409 otherwise), flatten, enforce
      `otel-sink.max-rows-per-request` (422), then write in chunks of
      `tables.limits.max-write-batch-rows` through `TableDataService.write`.

## 5. Web layer

- [x] 5.1 Add `web/dto/otel/`: `CreateSinkRequest`, `SinkDto` (with nested `SignalDto`), `SinkListDto`,
      `OtlpExportResponse` (`partial_success`), with snake_case `@JsonProperty` on every multi-word field.
- [x] 5.2 Add `web/controller/otel/OtelSinkController` (`/v1/otel/sinks`: POST `@FullAdminOnly` 201, GET
      list, GET one, DELETE `@FullAdminOnly` 204) and `OtelIngestController`
      (`/v1/otel/sink/{sinkName}/v1/{logs,traces,metrics}`, `consumes = application/json`,
      `@PreAuthorize("@otelSinkAccess.canIngest(...)")`), both with OpenAPI annotations.
- [x] 5.3 Add `exception/OtelSinkNotFoundException` and `exception/OtelDisabledException`; map them plus
      `HttpMediaTypeNotSupportedException` in `DefaultExceptionHandler` (404 `not_found`,
      503 `otel_disabled`, 415 `unsupported_media_type`). Reuse `TableConflictException` /
      `TableValidationException` for 409 / 422.
- [x] 5.4 Permit the ingest paths at the URL layer in `OidcSecurityConfiguration`
      (`POST /v1/otel/sink/*/v1/logs|traces|metrics` → `.authenticated()`), leaving `/v1/otel/sinks/**`
      on the app-role rule.

## 6. Configuration and docs

- [x] 6.1 Add `OtelSinkProperties` (`otel-sink.enabled` default `true`, `otel-sink.max-rows-per-request`
      default `50000`, `otel-sink.default-retention-days` default `30`) and the bare
      `key: ${ENV:default}` lines in `application.yml`. Picked up by the application's
      `@ConfigurationPropertiesScan`. **Prefix is `otel-sink`, not `otel`**: the OpenTelemetry SDK owns
      the `OTEL_*` environment namespace, and Spring's relaxed binding would map a deployment's
      `OTEL_ENABLED` onto this surface's kill switch.
- [x] 6.2 Add the `otel-sink.*` table to `docs/configuration.md` (descriptions live only there); mention
      the OTLP endpoint in `README.md` with a link to `configuration.md` — no env-var table.
- [x] 6.3 Gate the whole surface on `otel-sink.enabled`, answering 503 `otel_disabled` on every
      `/v1/otel/**` endpoint when off.

## 7. Tests

- [x] 7.1 `@WebMvcTest` slices for both controllers (`OtelSinkControllerTest`,
      `OtelIngestControllerTest`): response shapes, 404/409/415/422/503 mappings, and the
      `{"partial_success": {}}` success body.
- [x] 7.2 Unit tests for `OtlpValues` (every AnyValue shape, both field spellings, hex ids, non-finite
      doubles, numeric arrays), `OtlpFlattener` (fan-out counts, resource/scope propagation, each metric
      shape's columns) and `OtelTableNames` (derivation and the sink-name bound).
- [x] 7.3 `OtelSinkIntegrationTest` against the ClickHouse + Postgres containers: create a three-signal
      sink, export one payload per signal, assert the rows land with the right columns and map contents,
      assert the tables appear under `/v1/tables` and are queryable, then delete the sink and assert the
      tables are gone.
- [x] 7.4 Failure paths in the same class: a derived table name already taken (409, nothing created), a
      duplicate sink (409), ingest after the backing table was deleted (409), an unknown sink or a signal
      the sink did not enable (404), and an empty export (200 no-op).
- [x] 7.5 **Added during implementation**: `everyLandingColumnIsSelectableByItsBareName` — selects every
      column of all three landing tables unquoted through `execute-sql`. This is the regression guard for
      the reserved-word rename (see design D10); without it, a future column named `value` or `count`
      would ship unqueryable.

## 8. Verification

- [x] 8.1 Delta specs in `openspec/changes/add-otel-sink-endpoint/specs/` updated to match shipped
      behaviour: the landing-table column names (design D10), and the `structured-query` delta rewritten
      to the DSL's real behaviour — a `map` field is projection-only when translating to SQL, exactly as
      `object` is, rather than a new execute-path rejection `object` does not have.
- [x] 8.2 `./gradlew clean build` passes (compile + checkstyle + tests).
