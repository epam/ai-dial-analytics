## Context

See proposal.md — Why. The relevant existing machinery, all of which this change composes rather than
duplicates:

- `TableCatalogService.create` records a `PENDING` identity; `TableSchemaCreationService.defineSchema`
  persists the columns and issues the single `CREATE TABLE` on the writer credential, flipping to
  `ACTIVE` (and compensating with a best-effort `DROP` on failure). `TableCatalogService.delete` drops
  the ClickHouse table and removes the catalog rows.
- `TableDataService.write(name, rows)` is the **only** caller of `CatalogRowWriter.insert`, which
  serializes rows as `JSONEachRow` on the writer credential. It validates the table is `ACTIVE`, rejects
  unknown columns, caps the batch at `tables.limits.max-write-batch-rows`, and normalizes `timestamp`
  values.
- `TableAccessEvaluator` (`@Component("tableAccess")`) answers `canWrite`/`canModify` for a table from
  the caller's provider roles; it is invoked from `@PreAuthorize` on controllers.
- The column type vocabulary is `QueryFieldType`, converted to DDL by `ClickHouseTypeMapper` and to
  read-path families by `ResultColumns.scalarKind` → `ResultColumnKind`. `SqlProjectionOnlyTypes` is the
  single classification of "can only be projected" (`array`, `object`).

The constraint that shapes the whole design: ADAS has no `map` in that vocabulary, and OTLP attribute
bags are exactly what `Map(String, String)` is for.

## Goals / Non-Goals

**Goals:**

- Provisioning a sink is one call and leaves no partial state on failure.
- The OTLP data plane reuses the existing governed write chokepoint, so the credential split, the
  batch cap, and the per-table access model apply to it for free.
- A sink's tables are indistinguishable from any other catalog source table on the read path.
- The `map` type is added once, in the vocabulary, and every read surface picks it up through the
  switches the compiler forces us to visit.

**Non-Goals:**

- OTLP over protobuf or gRPC. JSON-only, refused with 415 otherwise (see the spec).
- Partial-success accounting, retry/backpressure semantics, or an ingest queue. A payload is written
  synchronously and is accepted or rejected whole.
- Making span events/links or summary quantiles queryable as relations — they are JSON text.
- Mutating a sink's signal set after creation.

## Decisions

### D1 — `map` is a new `QueryFieldType`, fixed to `Map(String, String)`

**Chosen:** add `MAP("map")` to `QueryFieldType`, rendered by `ClickHouseTypeMapper` as
`Map(String, String)`, with no key/value parameters and no `element_type`.

**Why:** the alternative — declaring the attribute columns as `object` (ClickHouse `JSON`) — needs no
new type but gives up the thing that makes attribute bags usable in ClickHouse (`attributes['k']` key
lookup, per-key subcolumn storage) and contradicts the stated requirement. A parameterised
`Map(K, V)` was rejected as scope: OTLP attributes are string→string after the AnyValue rendering
(D5), and every parameter we add now becomes a compatibility surface later.

**Consequence:** `-Werror` plus exhaustive `switch` expressions turn this into a compiler-driven
checklist — every site that switches on `QueryFieldType` or `ResultColumnKind` must be visited. That is
the point: it is how we avoid a read surface silently disagreeing.

### D2 — `map` reuses `ResultColumnKind.OBJECT` rather than adding a kind

**Chosen:** `ResultColumns.scalarKind(MAP) → ResultColumnKind.OBJECT`.

**Why:** `ResultColumnKind` is the *runtime value family*, not the declared type, and a ClickHouse
`Map(String, String)` comes back over the HTTP read path as a JSON object — byte-for-byte the same
family as a `JSON` column. Adding `ResultColumnKind.MAP` would force a second, identical branch into
`ArrowTypeMapper`, `ArrowBatchWriter`, `InformationSchema` and `FlightCatalogStreams` with no
behavioural difference. The declared type stays `map` everywhere it is *declared* (catalog, discovery);
only the value family collapses.

**Rejected:** a distinct `MAP` kind mapping to Arrow `Map`. Arrow `Map` is a structured type the Flight
clients we care about (Dremio ADBC, Power BI) do not handle, and the spec already commits object cells
to `Utf8` JSON text so the two surfaces cannot disagree.

### D3 — sink tables are ordinary, non-`system` catalog tables

**Chosen:** `otel_<sink>_<signal>` are created through the normal catalog services and are **not**
marked `system`.

**Why:** `system` is specified as set only by database migration and never by an API path
(table-management, "System-owned table protection"). Setting it from the sink service would contradict a
shipped requirement. The cost is that a `FULL_ADMIN` can delete a sink's table out from under it; the
design makes that state legible instead of impossible — the sink view reports the signal's table as
absent, and ingest for it answers `409 conflict`.

**Rejected:** a private, catalog-invisible table set. That would make the ingested telemetry unqueryable,
which is the entire point of ingesting it into ADAS.

### D4 — ingest goes through `TableDataService.write`, chunked

**Chosen:** the OTLP flattener produces `List<Map<String, Object>>` keyed by physical column name and
calls `TableDataService.write` in chunks of `tables.limits.max-write-batch-rows`.

**Why:** that method is the documented row-write chokepoint — reusing it inherits the `ACTIVE` check,
the unknown-column check, timestamp normalization and the writer credential, and keeps the two write
surfaces from disagreeing. Chunking (rather than letting the cap reject) is what makes a realistic OTLP
batch land: a single metrics export can flatten to thousands of data points, and rejecting at 10 000
would make the endpoint useless for a normally-configured collector.

A separate, larger cap (`otel-sink.max-rows-per-request`, default 50 000) bounds the whole request so a single
payload cannot be flattened into unbounded memory; exceeding it is a 422 before anything is written.

**Trade-off:** chunked inserts are not atomic. A ClickHouse failure mid-payload leaves earlier chunks
written. This is accepted and stated: OTLP is an at-least-once protocol and exporters retry, so
duplicate-on-retry is the expected failure mode for every OTLP backend, including the reference
ClickHouse exporter.

### D5 — AnyValue is rendered to a string at the edge

**Chosen:** one `OtlpValues.render(JsonNode) → String` used for every attribute value and for a log
record's `body`. Structured values (`arrayValue`, `kvlistValue`) become compact JSON text.

**Why:** the map column is `Map(String, String)`, so *something* has to stringify. Doing it in one place
means the log body and an attribute holding the same structure render identically, and it is the same
choice the OpenTelemetry ClickHouse exporter makes.

### D6 — hand-written OTLP JSON decoding, no new dependency

**Chosen:** decode with the configured Jackson `JsonMapper` into `JsonNode` and walk it, accepting both
the lowerCamelCase OTLP JSON spelling and the proto field name, and ignoring unknown fields.

**Why:** there is no Maven-Central artifact carrying generated Java classes for the OTLP protobuf
messages — `io.opentelemetry.proto:opentelemetry-proto` ships `.proto` files only, so using it means
adding the protobuf Gradle plugin and a code-generation step to a build that has none. Against that, the
subset of OTLP we read is a few dozen fields, tolerant by construction (unknown fields ignored), and
fully covered by the mapping requirement in the spec. Tolerant hand-decoding also gives us the
"both spellings accepted" behaviour that protojson has and a strict POJO binding would not.

**Rejected:** binding to Jackson POJOs with `@JsonProperty`. It buys type safety but not tolerance — a
newer OTLP version's field would need `@JsonIgnoreProperties` anyway — and the OTLP `AnyValue`/`oneof`
shapes bind poorly to records.

### D7 — sink metadata is its own catalog, not derived from table names

**Chosen:** Postgres `otel_sink` (name, description, created_at) and `otel_sink_signal`
(sink_name, signal, table_name), Flyway `V30__otel_sink.sql`.

**Why:** deriving sinks by pattern-matching `otel_%_logs` in `table_definition` would make any
user-created table named that way a phantom sink and would lose the sink's own description and creation
time. An explicit catalog also lets `DELETE` know exactly which tables it owns.

`V30` is the next version on merged the default branch (which tops out at `V29`); per the project's
numbering rule this is numbered against merged history only, and renumbers on rebase if another MR lands
`V30` first.

### D8 — authorization mirrors `POST /v1/tables/*/rows` exactly

**Chosen:** the control plane is `@FullAdminOnly`; the ingest endpoints are `.authenticated()` at the URL
layer plus `@PreAuthorize("@otelSinkAccess.canIngest(#sinkName, '<signal>')")`, a thin
`@Component("otelSinkAccess")` that derives the table name and delegates to
`TableAccessEvaluator.canWrite`.

**Why:** an OTLP exporter is a machine identity that should be able to write one sink's tables and
nothing else — which is exactly what the per-table `write` grant already expresses. Putting the name
derivation in a bean rather than in the SpEL string keeps the `otel_<sink>_<signal>` convention in one
place. Reads and deletes stay on the standard rules.

### D9 — one metrics table, one row per data point

**Chosen:** a single `otel_<sink>_metrics` table with a `metric_type` discriminator and nullable
type-specific columns, rather than the reference exporter's five tables.

**Why:** the requirement is one table per signal. A discriminated table keeps `SELECT ... WHERE
metric_name = ...` working across all metric types with no union, at the cost of columns that are null
for most rows — cheap in ClickHouse, where a `Nullable` column of nulls compresses to nearly nothing.

### D10 — landing columns are named away from SQL reserved words

**Chosen:** `event_time`, `observed_time`, `start_time`, `end_time`, `point_value`, `point_count`,
`point_sum`, `point_min`, `point_max`, `point_attributes`, `exp_scale`, `exp_zero_count`,
`exp_positive_*`, `exp_negative_*` — rather than the OTLP-native spellings `timestamp`, `value`,
`count`, `sum`, `min`, `max`, `attributes`, `scale`, …

**Why:** discovered while running the first integration test against a real container. `timestamp`,
`value`, `count`, `sum`, `min` and `max` are keywords in the Calcite dialect the SQL surface parses, so
`SELECT value FROM otel_app_metrics` and `ORDER BY timestamp` both fail to parse. The project already
knows this failure mode — the archived `reserved-word-alias-diagnosis` change exists to explain it to
callers — and its advice is "rename it, or quote it". Here ADAS chooses the names, not the caller, so
renaming is the only defensible half: telemetry a user cannot query without knowing to quote is not
usable telemetry.

**Rejected:** keeping the OTLP spellings and documenting that they must be quoted. That pushes a
parser detail onto every consumer of every sink, forever, to save a prefix.

**Guarded by** `OtelSinkIntegrationTest.everyLandingColumnIsSelectableByItsBareName`, which selects
every column of all three tables unquoted — so a column added later that reintroduces a keyword fails
the build rather than shipping unqueryable.

**Consequence:** a reader familiar with the OpenTelemetry ClickHouse exporter's schema will not find
`Timestamp`/`Value` here. The column descriptions in the catalog name the OTLP field each column comes
from, so the mapping is discoverable through `GET /v1/tables/{name}` rather than only in this document.

## Risks / Trade-offs

- **Adding a `QueryFieldType` touches ~15 files across the query engine** → the exhaustive switches make
  every site a compile error rather than a silent fallthrough; the map delta specs pin the expected
  behaviour at each surface so the review has something to check against.
- **Chunked inserts are not atomic (D4)** → stated in the design and accepted as OTLP's normal
  at-least-once semantics; the endpoint never claims partial success, so a retry re-sends the whole
  payload.
- **A `FULL_ADMIN` can delete a sink's backing table (D3)** → the sink view reports it and ingest answers
  `409 conflict` instead of a confusing 500.
- **Decimal metric values lose precision** (`Decimal(38, 12)` for an OTLP `double`) and non-finite values
  become null → documented in the spec's mapping requirement; a `double` column type does not exist in
  the catalog vocabulary and adding one is a separate change.
- **Hand-written OTLP decoding can drift from the protocol** → the decoder is tolerant by construction
  (unknown fields ignored, both field spellings accepted), and the mapping requirement is scenario-tested.
- **Flyway `V30` may collide with an in-flight MR** → per the project's numbering rule, whoever merges
  second renumbers.
- **Landing rows older than the retention TTL are dropped on arrival** — a backfill of historical
  telemetry through a sink with a 30-day retention silently lands nothing → inherent to a TTL rather
  than a defect, but worth knowing before pointing a replay at a sink; create the sink with a larger
  `retention_days` for that.
- **A high-volume OTLP endpoint is a new load path onto the writer credential** → bounded by
  `otel-sink.max-rows-per-request` and by Spring's request-size limits; `otel-sink.enabled=false` is the kill
  switch.

## Migration Plan

1. `V30__otel_sink.sql` creates the two sink tables. It is additive — no existing table is touched — so
   rollback is a `DROP TABLE` of the two, and an older build simply ignores them.
2. No ClickHouse migration: sink tables are created at runtime by the DDL renderer on the existing
   writer credential, which already holds `CREATE`/`DROP TABLE` and `INSERT`.
3. Deploying with `otel-sink.enabled=false` ships the code inert; flipping it on needs no restart-time state.
4. Rolling back the application after sinks exist leaves orphaned `otel_*` tables in ClickHouse and rows
   in the two Postgres tables; both are inert and can be cleaned up through `/v1/tables` and SQL.
