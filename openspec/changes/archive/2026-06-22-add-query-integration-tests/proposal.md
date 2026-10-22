## Why

The structured query capability (`POST /v1/queries/execute` and the `/v1/queries` schema-discovery
endpoints) shipped with only **slice/unit** coverage: `@WebMvcTest` controller slices over a mocked
`StructuredQueryService`, plus `DialUsageLogQueryRenderTest` asserting the *rendered* ClickHouse SQL
string. Nothing exercises the full path **controller → service → executor → `ClickHouseClientWrapper`
→ a real ClickHouse**, so the parts that can only fail against a live engine are unverified:

- whether jOOQ's experimental `SQLDialect.CLICKHOUSE` rendering actually **executes** on ClickHouse
  (the prior change flagged ordered-set aggregates, `width_bucket`, `co`/`nc`, and inlined
  literal/`timestamp` semantics as provisional — design *Risks* and unchecked task `6.2`);
- whether the Flyway `V1__create_dial_usage_log` migration provisions a schema the query path can
  read back;
- whether `GenericRecord.getValues()` row mapping, offset paging, and `include_total` count produce
  correct results over real data.

The prior change's design left this explicit as an **Open Question** ("Should end-to-end execution be
covered by a ClickHouse Testcontainer in a follow-up change?") and a **Non-Goal**. This change answers
it: add Testcontainers-backed integration tests that stand up a real ClickHouse, provision the schema,
seed data, and drive the HTTP endpoints end to end.

## What Changes

- Add the Testcontainers test dependencies (`spring-boot-testcontainers`,
  `org.testcontainers:junit-jupiter`, `org.testcontainers:clickhouse` — Spring Boot BOM-managed, no
  pinned versions) to `build.gradle`.
- Add an `AbstractClickHouseIntegrationTest` base that boots the full Spring context
  (`@SpringBootTest` + `@AutoConfigureMockMvc`) against a **singleton** ClickHouse container, wires the
  container's host/port/credentials into `clickhouse.*` via `@DynamicPropertySource`, lets the real
  Flyway migration provision `dial_usage_log`, and provides per-test fixture helpers (truncate + an
  in-JVM client-v2 insert) so each test seeds only the rows it asserts on (no shared fixture).
- Add `StructuredQueryExecutionIntegrationTest` driving `POST /v1/queries/execute` end to end: row-mode
  projection + filters (`eq`, comparisons, `in`), `select *` (omitted projection), aggregate mode
  (`group_by` + `count`/`sum`), sort + offset paging with `include_total` totalCount, and the
  validation/error mappings (unknown field/entity → 400) against the real stack.
- Add `QuerySchemaDiscoveryIntegrationTest` driving the `/v1/queries` discovery endpoints against the
  real `QueryEntityRegistry` (entities catalog, base schema, unknown entity → 404).
- Update prior-change task `6.2` / the open question to record that end-to-end execution is now covered
  by these tests (where applicable), and document the Docker prerequisite for the test suite.

No production code behavior, API contract, configuration surface, or spec changes. This is a
**verification-only** change: it adds tests and a test dependency.

## Capabilities

### New Capabilities

<!-- None. This change adds test coverage for the existing `structured-query` and
`query-schema-discovery` capabilities; it introduces no new behavior. -->

### Modified Capabilities

<!-- None. Behavior, contracts, and invariants of `structured-query` and `query-schema-discovery` are
unchanged, so no delta spec is required. The capability specs already describe the behavior these
tests now assert against a live ClickHouse. -->

## Impact

- **New test code**: `com.epam.aidial.analytics.query` integration tests under `src/test/java`
  (`AbstractClickHouseIntegrationTest`, `StructuredQueryExecutionIntegrationTest`,
  `QuerySchemaDiscoveryIntegrationTest`).
- **New test dependencies** (BOM-managed): `org.springframework.boot:spring-boot-testcontainers`,
  `org.testcontainers:junit-jupiter`, `org.testcontainers:clickhouse`.
- **Build/runtime prerequisite**: `./gradlew clean build` (and the `test` task) now require a running
  **Docker** daemon. Document this in `README.md`.
- **No production code, config, or spec changes.**
