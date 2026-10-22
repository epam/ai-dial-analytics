# Design: add-arrow-flight-sql-endpoint

## Context

ADAS exposes a structured query DSL (`StructuredQuery`, JSON over `POST /v1/queries/execute`)
that is validated, translated to ClickHouse SQL via jOOQ (render-only, `SQLDialect.CLICKHOUSE`),
and executed through `ClickHouseClientWrapper` (client-v2, `queryAll`). Entity/field metadata
comes from the Postgres table catalog via `CatalogQueryService` (capability
`query-schema-discovery`), not from ClickHouse introspection.

This change adds an Arrow Flight SQL server (Arrow `flight-sql` 18.3.0, gRPC port 32010) in
front of a Calcite pipeline: a transport layer — server lifecycle, producer, ticket handling,
concurrency gate, per-request child allocators, `ArrowBatchWriter`, `ArrowTypeMapper`, gRPC
error mapping — plus a `RelNode → StructuredQuery` translator, so Flight traffic executes
through the existing, governed structured-query pipeline rather than any parallel
SQL-rendering path.

Decisions fixed at proposal time: Flight inherits the REST row cap (limit clamp 1000),
authentication reuses ADAS security (open when `security.mode=none`), statement-only v1 (no
prepared statements), translation happens at the `RelNode` level.

## Goals / Non-Goals

**Goals:**
- SQL-in, Arrow-out access to the same queryable entities, semantics, and governance as
  `POST /v1/queries/execute`. One execution path: every Flight query becomes a `StructuredQuery`
  and runs through `StructuredQueryService` unchanged.
- Fail-closed translation: any SQL construct the DSL cannot express is rejected with a clear
  `INVALID_ARGUMENT` before anything touches ClickHouse.
- `getFlightInfo` returns the Arrow result schema without executing the query.
- Deterministic, documented `QueryFieldType`/DSL-type → Arrow type mapping.
- Endpoint disabled by default; zero impact on existing deployments until enabled.

**Non-Goals:**
- Prepared statements / parameter binding (`ParamExpr` remains rejected end-to-end).
- Row limits beyond the REST clamp, streaming reads from ClickHouse, cursor pagination.
- TLS on the gRPC listener (deploy behind trusted network / mesh for now).
- pgwire front door, DML/DDL, transactions, multi-statement requests.
- BI-tool SQL coverage (CASE, CAST, arithmetic, subqueries, joins) — out by construction; the
  supported subset is exactly the DSL surface.

## Decisions

### D1. Translate at the RelNode level, tolerating validator-injected casts

**Choice:** lower SQL to `RelNode` via Calcite (`Frameworks.getPlanner` with Babel parser,
case-insensitive, validate against our catalog adapter, `planner.rel(...).project()`), then
walk the RelNode stack and map it onto `StructuredQuery`.

**Why:** the RelNode shape for the supported subset is a small, normalized, linear stack —
`TableScan → [Filter] → [Aggregate] → [Filter(having)] → Project → [Sort/Limit]` — that maps
almost 1:1 onto `StructuredQuery` fields (`filter`, `group_by`, `having`, `select`, `sort`,
`page`). Name resolution, `*`-expansion, alias scoping, and type checking come from Calcite's
validator for free. The alternative (translating the validated `SqlNode` AST) stays closer to
user text but reimplements name resolution and operator semantics by hand.

**Cost and mitigation:** Calcite's validator/planner injects constructs the user never wrote —
most importantly implicit `CAST`s and literal normalization. The translator maintains a small
tolerance list: identity/widening casts within the same type family (e.g. `INTEGER→BIGINT`,
`CHAR→VARCHAR`) are unwrapped and ignored; any other `RexCall` whose operator is not in the
mapping table fails closed. Planner rewrites are minimized by using the plain
parse→validate→`rel()` path with no optimization rule sets.

### D2. RelNode → StructuredQuery mapping rules

- `TableScan` → `entity`. Exactly one scan is required; zero or more than one (joins,
  subqueries that survive lowering) → rejected. Enrichment columns are exposed as flat quoted
  columns (see D3), so no SQL join ever maps to the DSL's implicit enrichment join.
- No `Aggregate` node → `mode="row"`; `Aggregate` present → `mode="aggregate"` with group keys
  → `group_by` and aggregate calls → `FnExpr` select entries. `LogicalAggregate.groupSets`
  beyond the plain group set (ROLLUP/CUBE/GROUPING SETS) → rejected.
- `Filter` below the `Aggregate` → `filter`; `Filter` above it → `having`.
- `Project` → `select` list of `OutputColumn`; output field names become `as` aliases.
  Computed/aggregate entries always get aliases (Calcite synthesizes `EXPR$n` when the user
  wrote none — accepted, since the DSL requires aliases for computed entries).
- `Sort` → `sort` (collation → `SortItem` with `dir`; Calcite null-direction → `nulls`) and
  `fetch`/`offset` → `OffsetPage`. `LIMIT` > 1000 is **not** rejected — it is clamped by the
  existing `applyPage` governance, matching REST behavior. No `fetch` → default limit 100.
- `SELECT DISTINCT` (lowered by Calcite to an `Aggregate` with all-column group keys and no
  aggregate calls) → detected and mapped back to `distinct=true` in row mode.
- RexNode → `Expr`: `RexInputRef` → `FieldExpr` (resolved through the scan's field list),
  `RexLiteral` → `ValueExpr` (typed per D4), `RexCall` → operator table lookup:
  - comparisons `=, <>, <, >, <=, >=` → `ComparisonOp` `eq, ne, lt, gt, le, ge`;
    `SEARCH`/`IN`-lowered forms → `in` with `ArrayExpr` of literals; `IS NULL`/`IS NOT NULL` →
    `eq`/`ne` against a `null` `ValueExpr`; `AND/OR/NOT` → `LogicalNode`.
  - `LIKE` maps to `co`/`nc` **only** for the exact contains pattern `'%text%'` with no other
    metacharacters (the DSL's `co` is case-insensitive contains; this asymmetry is documented
    in the spec). Any other pattern → rejected.
  - scalar/aggregate function names map 1:1 onto the DSL catalog (`lower, upper, length, trim,
    abs, width_bucket, date_bin, count, sum, avg, min, max, percentile_cont, percentile_disc`);
    `COUNT(*)` → 0-arg `count`; `DISTINCT` aggregate flags carry over. Anything else — including
    arithmetic operators, `CASE`, explicit `CAST` — → rejected.
- The translator's output is a plain `StructuredQuery` record; from that point on, the existing
  `StructuredQueryService` validation and execution applies (double validation is accepted —
  it is cheap and keeps one source of truth for DSL rules).

**Alternative considered:** bypassing the DSL and rendering RelNode → ClickHouse SQL directly
(Calcite's `RelToSqlConverter`). Rejected: it forks governance into two parallel rule sets and
contradicts the point of this change.

### D3. Calcite catalog adapter over query-schema discovery

A Calcite root schema with one `Table` per queryable entity, built from
`CatalogQueryService` (same source as `GET /v1/queries/entities/schema/{name}`): flat field
name → Calcite type derived from `QueryFieldType`. Enrichment columns keep their dotted flat
names (`enrichment_table.column`) and therefore must be double-quoted in SQL
(`SELECT "deployment_ref.display_name" …`); the translator emits the flat name into
`FieldExpr` and the existing builder handles the implicit LEFT JOIN. `object`/`array` fields
are **excluded from the Calcite schema** in v1 (not selectable via SQL) because they have no
DSL literal type and no clean Arrow mapping. The schema is built per-request (catalog reads are
cheap Postgres queries and the catalog is mutable via table-management); if this shows up in
profiles, add a short-TTL cache later.

### D4. Type mapping (one normative table, used in both directions)

| QueryFieldType / DSL ValueType | Calcite type | Arrow type |
|---|---|---|
| string | VARCHAR | Utf8 |
| integer | INTEGER | Int32 |
| long | BIGINT | Int64 |
| decimal | DECIMAL(38,9) | Decimal128(38,9) |
| boolean | BOOLEAN | Bool |
| date | DATE | DateDay |
| timestamp | TIMESTAMP_WITH_LOCAL_TIME_ZONE(3) | Timestamp(MILLI, "UTC") |
| uuid | VARCHAR | Utf8 |
| object / array | — excluded — | — |

All Arrow fields nullable. SQL literals convert to `ValueExpr` with the string encoding the DSL
expects (timestamps → epoch-millis string; dates → ISO date; decimals → plain string).
Aggregate result types for `getFlightInfo` come from Calcite's validated row type mapped
through the same table (`count` → Int64, `avg`/`percentile_*` → Float64, `sum` follows operand
family widened per Calcite). Precision/scale for decimal follows the catalog's declared type
where available.

### D5. Result path: materialized rows → Arrow batches

`StructuredQueryService.execute` returns ordered field→value row maps (≤1000 rows by
governance). The `ArrowBatchWriter` fills a `VectorSchemaRoot` from those rows using the
D4 mapping and flushes on `batch-rows` (default 1024) or `batch-max-bytes` (default 4 MiB) —
with the current cap this usually means a single batch, which is fine. Value coercion handles
the ClickHouse client-v2 native Java types surfaced through the existing DTO path. The
`getFlightInfo` schema and the `doGet` schema are derived from the same translated
`StructuredQuery`, guaranteeing agreement.

**Why not stream from ClickHouse:** out of scope by the row-limit decision; the writer is
written in its streaming form anyway so lifting the cap later only touches the executor seam.

### D6. Server lifecycle, concurrency, memory

- `FlightServer` started/stopped by a Spring `SmartLifecycle` bean, only when
  `flight.enabled=true`; `Location.forGrpcInsecure(0.0.0.0, flight.port)` (default 32010,
  0 = ephemeral for tests).
- Producer extends `BasicFlightSqlProducer`; overrides `getFlightInfoStatement`,
  `getStreamStatement`, `getStreamSqlInfo`, `determineEndpoints` (single local endpoint,
  opaque ticket = encoded SQL statement). Prepared-statement RPCs are left to the base class'
  default `UNIMPLEMENTED`. SqlInfo advertises read-only / no transactions / no DDL /
  case-insensitive identifiers.
- Two-call trust model kept: `getFlightInfo` translates + validates but does not execute;
  `doGet` re-runs the full translate→validate→execute pipeline from the ticket's SQL — nothing
  trusts the client.
- Concurrency: semaphore gate (`flight.max-concurrent-streams`, default 4) held only during
  `doGet` execution; saturation → `RESOURCE_EXHAUSTED` before any ClickHouse call.
- Memory: one app-scoped `RootAllocator`; per-request bounded child allocator
  (`flight.request-memory-limit-bytes`, default 64 MiB) closed in `finally`; separate child
  allocator for the server itself. `--add-opens=java.base/java.nio=ALL-UNNAMED` added to
  `bootRun`/`test` JVM args and documented for deployment images.

### D7. Authentication via Flight header middleware

When `security.mode=oidc`, register a `CallHeaderAuthenticator`-based middleware that accepts
either `authorization: Bearer <jwt/opaque>` (validated through the same multi-issuer
JWT/introspection machinery as HTTP) or an `api-key` header (validated through
`CoreApiKeyIntrospector` + `ApiKeyCache`, same precedence rule: bearer wins when both present).
Successful auth must yield at least `READ_ONLY_ADMIN`-equivalent authority (query reads are
open to any authenticated caller on HTTP, mirrored here); failures map to gRPC
`UNAUTHENTICATED`; introspection backend unavailable → `UNAVAILABLE`. When
`security.mode=none`, no middleware is registered and the endpoint is open — identical posture
to HTTP. The Spring Security filter chain does not apply to gRPC, so this is an explicit
adapter over the same underlying components, not a reuse of the filter chain.

### D8. Error mapping

One `FlightErrorMapper` translating existing exceptions to gRPC statuses, mirroring the
`ErrorView` philosophy (stable category, no raw backend messages):

| Source | gRPC status |
|---|---|
| SQL parse error, unsupported construct (translator), `QueryValidationException` | INVALID_ARGUMENT |
| Unknown entity/column (Calcite validation) | INVALID_ARGUMENT |
| Auth failure | UNAUTHENTICATED |
| Concurrency gate saturated | RESOURCE_EXHAUSTED |
| `ClickHouseUnavailableException`, introspection unavailable | UNAVAILABLE |
| `ClickHouseQueryException` | INVALID_ARGUMENT (matches HTTP 400 semantics) |
| Anything else | INTERNAL (message redacted) |

### D9. Package layout and dependency hygiene

- `com.epam.aidial.analytics.web.flight` — lifecycle, producer, auth middleware, error mapper,
  ticket codec, properties (`flight.*`).
- `com.epam.aidial.analytics.service.query.sql` — Calcite framework config, catalog adapter,
  RelNode→`StructuredQuery` translator (no Arrow or gRPC imports here; unit-testable
  SQL-string → `StructuredQuery` seam).
- `com.epam.aidial.analytics.service.query.arrow` — type mapper + batch writer (no gRPC
  imports; unit-testable rows → batches seam).
- `flight-sql` excludes its transitive `flight-core` to prevent version skew; Calcite pulls
  Guava/Jackson — versions aligned via the Boot BOM where possible, explicit pins otherwise.

## Risks / Trade-offs

- [Calcite validator rewrites reject legitimate SQL (implicit casts, literal coercions beyond
  the tolerance list)] → build the translator test suite from real client SQL (pandas/ADBC
  patterns) early; extend the tolerance list deliberately, each entry spec'd and tested.
- [`SEARCH`/Sarg lowering: Calcite 1.42 lowers `IN`, ranges, and some `OR` chains into
  `SEARCH(RexInputRef, Sarg)`] → translator must expand Sargs: point sets → `in`/`eq` chains,
  ranges → `lt/gt/le/ge` conjunctions, else reject. This is the fiddliest part of the
  translator; isolate it in one class with exhaustive tests.
- [`co`/`nc` are case-insensitive while SQL `LIKE` is case-sensitive] → semantic mismatch is
  explicit in the spec and SqlInfo advertises case-insensitivity; revisit if the DSL ever grows
  a case-sensitive contains.
- [Dotted enrichment identifiers surprise SQL users (`"deployment_ref.display_name"` looks like
  a qualified name but is a quoted flat column)] → document prominently; Calcite treats it as
  one identifier only when quoted, which is testable and unambiguous.
- [Arrow value coercion from client-v2 native types drifts from the JSON path] → single
  normative D4 table, integration tests against Testcontainers ClickHouse comparing REST JSON
  vs Flight Arrow values for the same query.
- [jOOQ CLICKHOUSE dialect is experimental (existing spec caveat for `percentile_*`,
  `width_bucket`)] → Flight adds no new rendering paths, and this change's integration tests
  (Testcontainers ClickHouse, part of the standard `./gradlew build` suite) execute these
  functions against a real ClickHouse end-to-end, closing the spec's "verify against a live
  ClickHouse" caveat for the exercised paths.
- [New always-on gRPC port widens attack surface] → disabled by default; auth on by the same
  switch as HTTP; no TLS in v1 is an accepted, documented limitation.
- [Arrow/Netty `--add-opens` requirement breaks runtime images that don't set JVM args] →
  wire into Gradle for local runs and tests, document the flag in `docs/configuration.md` for deployment.
- [Per-request Calcite schema build adds Postgres reads per Flight call] → accepted for v1;
  measured before optimizing.

## Migration Plan

Additive feature, no data migration. Deploy with `FLIGHT_ENABLED=false` (default) → no
behavior change. Enable per environment; rollback = disable the flag. The JVM `--add-opens`
flag must be present in deployment images before enabling.

## Open Questions

- ~~Exact authority requirement for Flight queries~~ — RESOLVED during implementation: HTTP's
  `/v1/**` rule requires `FULL_ADMIN` or `READ_ONLY_ADMIN` (there is no "any authenticated"
  tier), so Flight mirrors exactly that; a principal without either role gets gRPC
  PERMISSION_DENIED (`CallStatus.UNAUTHORIZED`).
- Whether `getFlightInfo` should also pre-flight entity/column validation errors (it does, via
  translation) versus deferring everything to `doGet` — current design validates in both.
- Decimal precision/scale: single fixed Decimal128(38,9) vs. catalog-declared per-column
  precision — start fixed, tighten if catalog metadata proves reliable.
