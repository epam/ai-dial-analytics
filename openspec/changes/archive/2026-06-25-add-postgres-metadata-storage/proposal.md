## Why

ADAS today persists everything in ClickHouse, which is optimized for analytical reads over the
DIAL usage log, not for small, transactional metadata that the service needs to own and mutate
(create/update/delete) over its lifetime. We want a relational store for that metadata. PostgreSQL
is the right backend for transactional, row-level CRUD. This change introduces PostgreSQL as a
second data store dedicated to service metadata, kept strictly separate from the analytical query
path.

## What Changes

- Introduce **PostgreSQL** as a metadata store alongside ClickHouse, accessed via a Spring-managed
  JDBC `DataSource` and Spring Data JPA (the app currently has no `DataSource`).
- Add a `metadata` table with a single `name` column that is its primary key, provisioned by a
  **Flyway SQL migration** kept isolated from the ClickHouse migrations.
- Add CRUD REST endpoints under `/v1/metadata` (create, list, get-by-name, rename, delete) following
  the existing `/v1` + `ErrorView` conventions.
- Map new failure modes to the `ErrorView` contract: not-found (404), duplicate name (409),
  PostgreSQL unavailable (503).
- **Keep PostgreSQL out of the structured query DSL**: the `metadata` table is not a queryable
  entity. `POST /v1/queries/execute` and schema discovery continue to expose only ClickHouse-backed
  entities; querying `metadata` via the DSL is rejected. This invariant is asserted by tests.
- Add Testcontainers-based **integration tests** that boot the full app against a real PostgreSQL and
  exercise every CRUD endpoint end to end (controller → service → repository → PostgreSQL), verify
  the Flyway-provisioned schema, and assert the DSL cannot reach `metadata`.
- Because introducing a `DataSource`/JPA means every full-context (`@SpringBootTest`) test now needs
  PostgreSQL available, standardize a shared integration-test base that provisions the required
  backing stores.

## Capabilities

### New Capabilities
- `metadata-store`: PostgreSQL-backed CRUD over service metadata (the `metadata` table), its
  Flyway-managed schema, error-contract mapping, and its strict isolation from the structured query
  DSL.

### Modified Capabilities
<!-- None. The structured-query and query-schema-discovery capabilities are unchanged: they continue
     to expose only ClickHouse entities. The "metadata is not queryable" invariant is owned by the
     new metadata-store capability, so no existing spec's requirements change. -->

## Impact

- **New dependencies**: `spring-boot-starter-data-jpa` (brings Hibernate, HikariCP, `spring-jdbc`),
  `org.postgresql:postgresql` driver, and `org.testcontainers:testcontainers-postgresql` (test).
- **New code**: `metadata` JPA entity + repository + service; `MetadataController` and DTOs/mapper;
  new exceptions and `DefaultExceptionHandler` mappings; a Flyway migration under a PostgreSQL-only
  location.
- **Configuration**: new `spring.datasource.*` / `spring.jpa.*` / PostgreSQL Flyway bindings
  (env-driven); descriptions added to `docs/configuration.md`. Boot's Flyway autoconfiguration is
  turned on for the PostgreSQL `DataSource` (it was off only because there was no `DataSource`); the
  ClickHouse migrations stay on their existing programmatic runner.
- **Existing tests**: ClickHouse integration tests and the context-load test must now also have a
  PostgreSQL `DataSource` at startup; they move onto the shared integration-test base.
- **No change** to the ClickHouse read/sync paths or the structured query DSL surface.
