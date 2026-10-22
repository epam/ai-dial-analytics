## Context

The structured query stack runs entirely over ClickHouse's HTTP interface: `StructuredQueryExecutor`
renders a jOOQ query with `getSQL(ParamType.INLINED)` on a connection-less `SQLDialect.CLICKHOUSE`
`DSLContext` and executes the resulting string via `ClickHouseClientWrapper.query(sql)` (the official
`com.clickhouse:client-v2`, port 8123). Schema for the single `dial_usage_log` entity is provisioned by
the Flyway migration `V1__create_dial_usage_log.sql`, run programmatically on startup by
`ClickHouseMigrationRunner` over the `clickhouse-jdbc` driver (also HTTP/8123) using the writer
credential.

Existing coverage is slice-only: `StructuredQueryControllerTest`/`QuerySchemaControllerTest`
(`@WebMvcTest` over a mocked service) and `DialUsageLogQueryRenderTest` (asserts the rendered SQL
*string*, never executed). The prior change deliberately deferred a live-execution test (design
Non-Goals + Open Question + unchecked task 6.2). This change adds that layer.

## Goals / Non-Goals

**Goals:**

- Exercise the real path **MockMvc → controller → `StructuredQueryService` → `DialUsageLogQueryRepository`
  → `StructuredQueryExecutor` → `ClickHouseClientWrapper` → real ClickHouse** for the supported query
  surface, asserting actual result rows, aggregates, paging, and totalCount.
- Prove the jOOQ CLICKHOUSE-dialect rendering for the **supported core** (filters; `eq…ge`; `in`;
  `count`/`sum`/`avg`/`min`/`max`; group-by; sort; offset paging) executes and returns correct data —
  retiring the "rendered-but-unexecuted" risk for that core.
- Verify the Flyway migration provisions a `dial_usage_log` the read path can query.
- Verify error mappings end to end (unknown field/entity → 400; unknown entity discovery → 404) against
  the real registry/validation, not mocks.
- Run inside the standard `./gradlew clean build` (default `test` task), reusing one container across
  the suite for speed.

**Non-Goals:**

- Auth/tenancy/PII — out of scope for this phase.
- A dedicated `integrationTest` source set or Failsafe split — overkill for the current size; tests live
  in `src/test/java` and run in the normal `test` task.

> **Update (coverage expanded):** the suite was extended to cover the full operator/function/value-type
> matrix, the paging/sort/grouping/`HAVING` surface, and the validation 400 paths (the latter as fast
> translator unit tests). The **provisional/experimental** constructs are now probed against real
> ClickHouse and their behavior is *locked*, not deferred: `percentile_cont`/`percentile_disc`, the
> case-insensitive `co`/`nc` operators, and `width_bucket` all execute and return correct results — the
> latter two after render fixes (see Decisions). The **`timestamp` vs `DateTime64(3)`** open question was
> resolved by a production fix (see Decisions) and is asserted. Cursor pagination and the complex
> `.../detailed` schema are now covered as their 400 paths.

## Decisions

- **`@SpringBootTest` + `@AutoConfigureMockMvc`, not a slice.** The point is full wiring, so boot the
  real context and drive HTTP through `MockMvc` (matching the project's MockMvc idiom). `webEnvironment`
  stays `MOCK` — no servlet container needed; the DB is the real out-of-process dependency.
- **Singleton container, shared across classes.** A single `ClickHouseContainer`
  (`org.testcontainers.clickhouse.ClickHouseContainer`, image pinned e.g.
  `clickhouse/clickhouse-server:24.8`) is started once in an `AbstractClickHouseIntegrationTest` static
  initializer and **not** stopped (Ryuk reaps it). Container startup dominates wall-clock; reuse keeps
  the suite fast. All IT classes extend the base.
  - *Alternative considered*: per-class `@Container` static field — restarts ClickHouse per test class;
    rejected for speed. The singleton pattern is the Testcontainers-recommended approach for a shared,
    read-mostly fixture.
- **Wire the container via `@DynamicPropertySource`.** A static registrar sets `clickhouse.host =
  container.getHost()`, `clickhouse.port = container.getMappedPort(8123)`, `clickhouse.user`/`password`
  and `clickhouse.writer.user`/`password` to the container credentials, and `clickhouse.database =
  default`. Both the client-v2 query path and the JDBC migration path target HTTP/8123, so the single
  mapped 8123 port serves both — no second port mapping needed.
- **Let the real migration run (`clickhouse.migrations.enabled=true`).** Provisioning `dial_usage_log`
  via Flyway is part of the plumbing under test, so the IT exercises `ClickHouseMigrationRunner` rather
  than hand-creating the table. (Contrast: `AnalyticsDataAccessApplicationTests` sets it `false` because
  it has no ClickHouse.)
- **Per-test data, not a shared fixture.** Each test owns its data: a `@BeforeEach` truncates
  `dial_usage_log` and the test then inserts only the rows it asserts on. So a test's expected values are
  self-contained and tests cannot couple to one another via a shared seed. The single queryable entity is
  hard-bound to `dial_usage_log` (`DialUsageLogSchema.ENTITY_NAME`), so isolation is **per-row in the one
  table**, not a table-per-test (the API cannot target an arbitrary table). Tests run serially, so the
  clean-slate-per-test approach is safe.
  - *Write path*: an in-JVM client-v2 `Client` built from the container's host/mapped-port/credentials
    (mirroring the production `UsageLogWriter`), issuing `TRUNCATE` and `INSERT … VALUES` — kept
    deliberately separate from the read-only `ClickHouseClientWrapper` the API exercises, so the fixture
    never depends on the code under test.
  - *Why in-JVM rather than `execInContainer`?* `docker exec` spawn overhead is the only thing that would
    make per-test seeding expensive (~0.2–0.8s/call); an in-JVM HTTP insert is ~tens of ms, so per-test
    truncate+insert adds only ~0.3–0.8s across the suite versus ~6–14s for the docker-exec equivalent.
    The container and Spring context stay shared (the real cost, ~7s), so isolation is effectively free.
  - *Why not the JDBC driver in test code?* `clickhouse-jdbc` is `runtimeOnly` (not compile-visible to
    tests); `client-v2` is already on the test classpath.
- **Assert results, not SQL.** Unlike `DialUsageLogQueryRenderTest`, these tests assert response JSON
  (`$.rows`, `$.totalCount`) — concrete values computed by ClickHouse over the seeded rows — so a
  rendering that compiles but computes the wrong thing is caught.
- **Naming `*IntegrationTest` (not `*IT`).** Keeps them in the default `test` task / `clean build`; the
  project has no Failsafe/`integrationTest` source set and the convention is `*Test`.
- **Timestamp literals render via `fromUnixTimestamp64Milli`.** Probing revealed the original behavior
  was wrong: a `timestamp` value (epoch-millis `Long`) inlined as a bare integer does not compare
  correctly against a `DateTime64(3)` column (it matched nothing). Fix: `ExprTranslator` now wraps a
  `timestamp` literal in `fromUnixTimestamp64Milli(<millis>)` so ClickHouse compares by instant. The
  integration container's `TZ` is pinned to `UTC` so inserted datetimes and the literal agree
  deterministically. This resolves the prior change's timestamp open question.
- **Provisional constructs all execute after render fixes.** Verified against a live server:
  `percentile_cont`/`percentile_disc` return the median; `co`/`nc` and `width_bucket` execute after the
  two render fixes below. None leak a 500. Tests lock each result.
- **`co`/`nc` render without an `ESCAPE` clause.** jOOQ's `likeIgnoreCase(pattern, escapeChar)` emitted
  `… LIKE … ESCAPE '\'`, which ClickHouse rejects (it has no `ESCAPE` syntax). Fix: `FilterTranslator`
  uses the no-escape `likeIgnoreCase(pattern)` overload; ClickHouse's LIKE/ILIKE treats backslash as the
  escape character by default, matching the backslash escaping `containsPattern()` already applies — so
  `%`/`_` in the operand still match literally. The constructs now execute (case-insensitive substring).
- **`width_bucket` coerces the bucket count to unsigned.** ClickHouse recognizes `width_bucket` (alias of
  `widthBucket`) but requires the count argument to be `UInt*`; jOOQ's `cast(Integer)` rendered a signed
  `Int32`, so ClickHouse failed with `ILLEGAL_TYPE_OF_ARGUMENT`. Fix: `ExprTranslator` wraps the count in
  `toUInt32(...)` instead of a signed cast. The function now executes and buckets correctly.

## Risks / Trade-offs

- **Docker becomes a build prerequisite.** `./gradlew clean build` now needs a Docker daemon;
  CI/contributor machines without it will fail these tests. Documented in `README.md`. (A future option
  is a tag/`@EnabledIf` to skip when Docker is absent, but the default should run them.)
- **Container image pin & pull.** First run pulls the ClickHouse image (slow, network-dependent). Pin a
  specific tag for reproducibility; rely on the local Docker cache thereafter.
- **jOOQ CLICKHOUSE dialect remains experimental** for the *exotic* constructs — intentionally not
  asserted here. If a "supported core" construct turns out to mis-render at execution time, that's a
  real bug this change is designed to surface; fix it in the translator (out of scope to pre-empt).
- **Singleton container + shared seed** means tests must not mutate the dataset in ways that break
  siblings. The query path is read-only and seeding is one-shot, so this holds; keep it so.

## Open Questions

- Should a Docker-absent environment **skip** (e.g. `@EnabledIfDockerAvailable`) or **fail**? Default
  here is fail (tests always run); revisit if CI needs graceful skip.
- *(resolved)* All originally-provisional constructs now execute; none are rejected. A known ClickHouse
  quirk remains — `widthBucket` can mis-assign values exactly on a bucket boundary
  ([ClickHouse#64185](https://github.com/ClickHouse/ClickHouse/issues/64185)) — so tests avoid boundary
  values; revisit if exact boundary semantics ever matter.
