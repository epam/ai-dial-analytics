## Context

ADAS reaches ClickHouse over its HTTP interface via the official ClickHouse Java client
(`com.clickhouse:client-v2`); there is no JDBC `DataSource`, no jOOQ code generation, and no Postgres.
The structured query DSL being ported originates in `ai-dial-admin-evaluation-framework-backend`,
where it translates to **parameterized jOOQ SQL executed on a Postgres `DSLContext`** against
generated tables (`test_suites`, `eval_summaries`) with JSONB-backed flattened fields. The request
model (records, sealed `Expr`/`FilterNode`/`PageSpec`, enums, the `FilterNodeDeserializer`) is
framework-agnostic; the translation/execution and schema-discovery layers are not.

## Goals / Non-Goals

**Goals:**

- A body-delivered `POST /v1/queries/execute` with the same wire contract as the source DSL.
- Schema discovery under `/v1/queries` so clients can find queryable entities and field names.
- Reuse the source translators unchanged where possible; isolate ClickHouse specifics.
- Map all failures onto the existing `ErrorView` contract; never leak ClickHouse internals.

**Non-Goals:**

- Authentication, authorization, tenancy, or PII handling.
- Complex (JSONB-flattened, per-instance) entities and the `.../detailed` schema mechanics — the
  endpoint exists but returns 400 for the only (simple) entity registered now.
- Cursor pagination (rejected with 400), and the richer planned response envelope (`page` object /
  aggregate `keys`+`metrics`).
- A ClickHouse Testcontainer for end-to-end execution tests (the source's embedded-Postgres
  functional tests do not port); execution is verified by rendered-SQL unit tests + manual smoke.

## Decisions

- **Render-only jOOQ.** Add `org.jooq:jooq` and a connection-less `DSLContext` bean
  (`DSL.using(SQLDialect.CLICKHOUSE)`, `QueryDslConfiguration`). The source translators build a jOOQ
  query model; `StructuredQueryExecutor` renders it with `getSQL(ParamType.INLINED)` and runs the
  string through `ClickHouseClientWrapper.query(sql)`. jOOQ never opens a connection.
  - *Alternative considered*: a hand-written ClickHouse SQL builder — rejected; it would duplicate
    the proven translator logic. *Alternative considered*: jOOQ JDBC execution — impossible, ADAS has
    no `DataSource` and talks ClickHouse over the HTTP client.
- **Inlined literals.** `ClickHouseClientWrapper.query(String)` takes a raw SQL string with no bind
  API, so the query is rendered with `ParamType.INLINED`; jOOQ's CLICKHOUSE dialect owns literal
  quoting/escaping.
- **Single flat entity.** Replace the source's generated-table schema resolver and per-entity
  Postgres repositories with one hand-built `DialUsageLogSchema` (table + field bindings mirroring
  `docs/clickhouse/dial_usage_log.sql`) and a `DialUsageLogQueryRepository`. The entity-agnostic
  registry/SPI/dispatch (`QueryEntityRegistry`, `QueryableEntitySchemaProvider`,
  `StructuredQueryService`) is kept so further entities are a registration, not a web-layer edit.
- **No JSONB layer.** `dial_usage_log` is flat, so the source's `JsonbFieldResolver` and
  `JsonbRowConverter` are dropped; `ExprTranslator` resolves fields against the bindings map only.
- **`select *` via bindings.** A hand-built `DSL.table(name)` carries no field metadata, so an
  omitted projection selects all bound columns explicitly rather than `table.fields()`.
- **Error mapping.** New ADAS exceptions replace the eval-framework `ValidationException`:
  `QueryValidationException` → 400, `QueryEntityNotFoundException` → 404, and a new
  `ClickHouseQueryException` (non-connectivity ClickHouse failure on the query path) → 400. ClickHouse
  connectivity failures reuse `ClickHouseUnavailableException` → 503.
- **Jackson primitive defaults.** The DSL records have primitive flags (`distinct`, `include_total`).
  Jackson 3 fails on absent primitives by default, so the shared `JsonMapper` disables
  `FAIL_ON_NULL_FOR_PRIMITIVES` (matching the source mapper). Controller slice tests `@Import` the
  `JsonMapperConfiguration` so the slice uses the same mapper.
- **Toolchain.** Java 25 + Gradle 9.6.0 + `io.freefair.lombok` 9.5.0 (older Lombok can't run under
  JDK 25). `org.jooq:jooq:3.21.5` is pinned because `SQLDialect.CLICKHOUSE` only exists in jOOQ 3.20+.

## Risks / Trade-offs

- **jOOQ's CLICKHOUSE dialect is experimental.** The ordered-set aggregates
  (`percentile_cont`/`percentile_disc`, which jOOQ renders as `WITHIN GROUP (ORDER BY …)` — not
  ClickHouse syntax, where the idiom is `quantile()`), `width_bucket`, and the `co`/`nc`
  case-insensitive `LIKE … ESCAPE` may not render to valid ClickHouse SQL. The safe core (filters,
  `eq…ge`, `in`, `count`/`sum`/`avg`/`min`/`max`, group-by, sort, offset paging) is the supported
  surface; the exotic constructs are provisional and must be verified against a live ClickHouse, with
  a ClickHouse-specific render path or an explicit 400 added where they don't translate.
- **Inlined literals** rely on jOOQ's dialect-correct quoting/escaping for safety; verify string/date
  inlining under CLICKHOUSE.
- **Timestamp literals.** `value_type: timestamp` is parsed to epoch-millis `Long` (carried over from
  the source's convention) and compared numerically against `DateTime64(3)` columns; confirm this is
  the intended comparison semantics for ClickHouse.

## Open Questions

- Should `dial_usage_log`'s `request_time`/`_ingested_at` accept ISO-8601 literals (mapped to
  ClickHouse `DateTime64` literals) in addition to epoch-millis?
- Should end-to-end execution be covered by a ClickHouse Testcontainer in a follow-up change?

## Spec / archive note

This change ships its delta specs under `specs/structured-query/` and `specs/query-schema-discovery/`.
Because the work is already implemented and committed, the merged live specs were also written
directly under `openspec/specs/` for immediate source-of-truth accuracy. If the strict opsx flow is
preferred, delete those live specs and run `/openspec:archive` from this change to regenerate them.
