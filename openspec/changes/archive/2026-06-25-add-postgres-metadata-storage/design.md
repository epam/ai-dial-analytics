## Context

ADAS uses ClickHouse exclusively, accessed over the HTTP client-v2 for read/sync and over the
ClickHouse JDBC driver only for programmatic Flyway migrations (`ClickHouseMigrationRunner`). It
deliberately has **no Spring `DataSource`** and excludes `spring-jdbc` — Boot's Flyway
auto-configuration is turned off (`spring.flyway.enabled: false`) for exactly that reason.

We now need a transactional, relational store for service-owned metadata. ClickHouse is a poor fit
for small row-level CRUD. This change adds PostgreSQL as a second backend with a single `metadata`
table (`name` column, primary key) exposed via CRUD endpoints, while keeping it entirely out of the
analytical query DSL.

Constraints from `openspec/project.md` and `CLAUDE.md`: Java 25 / Spring Boot 4; functional
endpoints under `/v1`; the `ErrorView` error contract with stable machine codes; responses are
objects, never bare arrays; config is env-driven `${ENV:default}`; **env-var descriptions live only
in `docs/configuration.md`** (not in `application.yml` comments or README tables); a change is done
only when `./gradlew clean build` passes.

## Goals / Non-Goals

**Goals:**
- Persist metadata in PostgreSQL via a Spring-managed `DataSource` + Spring Data JPA.
- Provision the `metadata` table with a Flyway SQL migration isolated from ClickHouse migrations.
- CRUD endpoints under `/v1/metadata` keyed by `name`, honoring the `/v1` + `ErrorView` conventions.
- Keep PostgreSQL strictly out of the structured query DSL (not a queryable entity).
- Testcontainers integration tests covering every endpoint end to end plus the DSL-isolation
  invariant, and keep the existing full-context tests green now that a `DataSource` is required.

**Non-Goals:**
- No additional columns, timestamps, soft-delete, or pagination on `metadata` (single `name` column
  for now; responses stay object-wrapped so these can be added later without breaking clients).
- No auth/tenancy (still out of scope project-wide).
- No exposure of PostgreSQL data through the query DSL, now or as part of this change.
- No migration of any existing ClickHouse data.

## Decisions

### Decision: Persist via Spring Data JPA on a single primary `DataSource`

Use `spring-boot-starter-data-jpa` (Hibernate + HikariCP + `spring-jdbc`) with the
`org.postgresql:postgresql` driver. A `MetadataEntity` (`@Entity`, `@Id String name`), a
`MetadataRepository extends JpaRepository<MetadataEntity, String>`, and a `MetadataService` carry the
CRUD logic.

- **Why over Spring JDBC / `JdbcClient`**: chosen by the maintainers; least boilerplate for plain CRUD.
- **`spring.jpa.hibernate.ddl-auto: none`**: Flyway owns the schema; Hibernate neither creates nor
  validates it. `validate` is rejected because Hibernate's type expectations (e.g. `varchar` vs the
  migration's `text`) cause brittle startup failures for no benefit. `open-in-view: false`.

### Decision: PostgreSQL migrations via Boot Flyway autoconfig, in a per-database subfolder

Turn Boot's Flyway autoconfiguration **on** (`spring.flyway.enabled: true`) now that a `DataSource`
exists, and point it at a PostgreSQL-only location `classpath:db/migration/postgres` (file
`V1__create_metadata.sql`). Migrations for both stores live under a common `db/migration/` parent in
per-database subfolders; the ClickHouse migrations move to `classpath:db/migration/clickhouse`, scanned
by the programmatic `ClickHouseMigrationRunner` over the ClickHouse JDBC driver.

- **Isolation**: each Flyway instance is scoped to its own subfolder (`db/migration/postgres` vs
  `db/migration/clickhouse`), so Boot Flyway never applies ClickHouse migrations to PostgreSQL and the
  ClickHouse runner never sees the PostgreSQL ones — even though both sit under `db/migration/`. The
  ClickHouse runner must point at the `clickhouse` subfolder specifically (not `db/migration`), or its
  recursive scan would pick up the PostgreSQL migration. Each Flyway instance keeps its own
  `flyway_schema_history` in its own database.
- **Why not a second programmatic runner** (mirroring `ClickHouseMigrationRunner`): the project
  comment states autoconfig was disabled *only* because there was no `DataSource`. With one present,
  autoconfig is the idiomatic path and removes hand-rolled wiring. ClickHouse stays programmatic
  because it has no `DataSource` and needs the JDBC-only migration connection.
- The migration SQL:
  ```sql
  CREATE TABLE metadata (
      name TEXT PRIMARY KEY
  );
  ```

### Decision: CRUD keyed by `name`; rename via PUT

`name` is the primary key (the table has only that column, per the maintainers). Endpoints:

| Method | Path                  | Body            | Success | Errors |
|--------|-----------------------|-----------------|---------|--------|
| POST   | `/v1/metadata`        | `{name}`        | 201     | 400 blank, 409 duplicate |
| GET    | `/v1/metadata`        | —               | 200 `{items:[…]}` | — |
| GET    | `/v1/metadata/{name}` | —               | 200 `{name}` | 404 |
| PUT    | `/v1/metadata/{name}` | `{name:newName}`| 200 `{name}` | 400 blank, 404 missing, 409 collision |
| DELETE | `/v1/metadata/{name}` | —               | 204     | 404 |

- List returns an object `{ "items": [ {"name": …}, … ] }`, never a bare array (project convention).
- Entry objects are `{ "name": … }` objects, so fields can be added later.
- A MapStruct mapper (`componentModel = "spring"`) maps `MetadataEntity` ↔ DTO, matching `web/mapper`
  convention even though the mapping is trivial today.

### Decision: Error-contract mappings

Add to `DefaultExceptionHandler`:
- `MetadataNotFoundException` → 404 `not_found`.
- `MetadataAlreadyExistsException` → 409 new stable code `conflict`.
- `org.springframework.dao.DataAccessException` (incl. connection failures) → 503
  `postgres_unavailable`, mirroring the existing `clickhouse_unavailable` handling; log a warning, no
  stack traces or driver internals leaked. Blank-name validation reuses the existing 400 `bad_request`
  path via `@Valid` / `@NotBlank`.

### Decision: DSL isolation is by omission, asserted by test

The DSL only runs entities registered in `QueryEntityRegistry`/schema providers (today only
`dial_usage_log`). `metadata` is simply never registered there, so the JPA path and the DSL share no
code. The isolation is locked in by an integration test that posts `entity: "metadata"` to
`POST /v1/queries/execute` and asserts HTTP 400 (unknown entity) and that `metadata` is absent from
`/v1/queries` discovery.

### Decision: Shared integration-test base for both backends

Introducing a `DataSource` means every `@SpringBootTest` now needs PostgreSQL reachable at startup
(Flyway runs eagerly, Hibernate needs the `DataSource`). Introduce a base that starts singleton
Testcontainers for the backends a test needs and wires them via `@DynamicPropertySource`:
- A `metadata` PostgreSQL container (Testcontainers `testcontainers-postgresql` →
  `PostgreSQLContainer`) wiring `spring.datasource.url/username/password` and
  `spring.flyway.enabled=true`.
- The existing `AbstractClickHouseIntegrationTest` and the bare `AnalyticsDataAccessApplicationTests`
  context-load test must also have the PostgreSQL `DataSource`. The simplest robust shape is a common
  `AbstractIntegrationTest` that provisions **both** singleton containers (reused across the suite via
  the test-context cache) and registers all dynamic properties; the ClickHouse base and the
  context-load test extend it, and metadata tests extend it too. Containers remain singletons started
  in a static initializer (Ryuk cleans them up), so the suite stays fast.

## Risks / Trade-offs

- **All full-context tests now require Docker + PostgreSQL** → They already require Docker for
  ClickHouse; the shared base reuses singleton containers so the added cost is one container per JVM.
- **JPA/Hibernate is a heavyweight dependency for one table** → Accepted (maintainers' choice); it is
  the conventional CRUD path and leaves room to grow the metadata model.
- **Two Flyway instances over different locations could be misconfigured to overlap** → Mitigated by
  using a dedicated per-database subfolder (`db/migration/postgres`, with the ClickHouse runner scoped
  to `db/migration/clickhouse`) and an integration test that
  confirms `metadata` exists and the ClickHouse tables are absent from PostgreSQL.
- **`ddl-auto: none` means entity/schema drift is not caught by Hibernate** → Acceptable for a single
  column; the integration tests exercise the real schema and would fail on drift.
- **Boot Flyway autoconfig is global** → It targets the single primary `DataSource` (PostgreSQL). As
  long as only the PostgreSQL `DataSource` exists, there is no ambiguity; revisit if a second
  `DataSource` is ever added.

## Migration Plan

1. Add dependencies and configuration (env-driven), documented in `docs/configuration.md`.
2. Add the entity/repository/service, controller/DTO/mapper, exceptions and handler mappings, and the
   `V1__create_metadata.sql` migration.
3. Refactor the test bases to provision PostgreSQL; add the metadata integration tests.
4. Deploy with `POSTGRES_*` env vars pointing at the provisioned database; on startup Flyway creates
   `metadata`. Rollback is removing the endpoints/dependencies; the `metadata` table can be dropped
   out of band as it holds only newly-introduced metadata.

## Open Questions

- None blocking. Future: whether metadata records eventually need surrogate ids/timestamps — deferred;
  the object-wrapped responses keep that door open without a breaking change.
