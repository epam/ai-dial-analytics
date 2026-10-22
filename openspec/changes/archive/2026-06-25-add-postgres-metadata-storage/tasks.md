## 1. Dependencies & configuration

- [x] 1.1 Add `spring-boot-starter-data-jpa`, `runtimeOnly 'org.postgresql:postgresql'`, and `testImplementation 'org.testcontainers:testcontainers-postgresql'` to `build.gradle` (verify the TC 2.0 artifactId; mirror the existing TC clickhouse comment about the `testcontainers-` prefix).
- [x] 1.2 In `application.yml`, set `spring.flyway.enabled: ${POSTGRES_MIGRATIONS_ENABLED:true}` with `spring.flyway.locations: classpath:db/migration/postgres`; add bare `spring.datasource.url/username/password` (`${POSTGRES_URL:jdbc:postgresql://localhost:5432/adas}`, `${POSTGRES_USER:postgres}`, `${POSTGRES_PASSWORD:}`) and `spring.jpa.hibernate.ddl-auto: none` + `spring.jpa.open-in-view: false`. Keep lines bare (no explanatory comments).
- [x] 1.3 Document the new env vars (`POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_MIGRATIONS_ENABLED`) — key, purpose, default — in `docs/configuration.md` as a new config-group table. Do not add a description table to `README.md` or comments to `application.yml`.

## 2. Database migration

- [x] 2.1 Create `src/main/resources/db/migration/postgres/V1__create_metadata.sql` with `CREATE TABLE metadata (name TEXT PRIMARY KEY);`.
- [x] 2.2 Move the ClickHouse migration to `src/main/resources/db/migration/clickhouse/` and point `ClickHouseMigrationRunner` at `classpath:db/migration/clickhouse` so each Flyway instance is scoped to its own subfolder under the shared `db/migration/` parent.

## 3. Persistence layer

- [x] 3.1 Create `service/metadata/MetadataEntity.java` — JPA `@Entity` mapped to `metadata` with `@Id String name`.
- [x] 3.2 Create `service/metadata/MetadataRepository.java` extending `JpaRepository<MetadataEntity, String>`.
- [x] 3.3 Create `service/metadata/MetadataService.java` (`@Service`, `@Transactional`) with create / list / getByName / rename / delete, throwing the exceptions from task 4 for not-found, duplicate, and rename-collision cases.

## 4. Exceptions & error contract

- [x] 4.1 Add `exception/MetadataNotFoundException.java` and `exception/MetadataAlreadyExistsException.java`.
- [x] 4.2 In `DefaultExceptionHandler`, map `MetadataNotFoundException` → 404 `not_found`, `MetadataAlreadyExistsException` → 409 `conflict`, and `org.springframework.dao.DataAccessException` → 503 `postgres_unavailable` (log a warning; no stack traces or driver internals in the response), mirroring the ClickHouse handlers.

## 5. Web layer

- [x] 5.1 Create DTOs under `web/dto/metadata/`: `MetadataDto` (record `{name}`, `@Schema`), a create/rename request record with `@NotBlank name`, and a `MetadataListDto` wrapping `items` (object, not bare array).
- [x] 5.2 Create `web/mapper/metadata/MetadataMapper.java` (MapStruct, `componentModel = "spring"`) for `MetadataEntity` ↔ `MetadataDto`.
- [x] 5.3 Create `web/controller/metadata/MetadataController.java` under `/v1/metadata`: `POST` (201), `GET` list (200, object-wrapped), `GET /{name}` (200/404), `PUT /{name}` (200/400/404/409), `DELETE /{name}` (204/404); annotate with springdoc `@Operation`/`@ApiResponse`/`@Tag` like `StructuredQueryController`.

## 6. DSL isolation

- [x] 6.1 Confirm `metadata` is never registered in `QueryEntityRegistry`/schema providers (no code change expected) so it cannot be queried via `POST /v1/queries/execute` nor appear in `/v1/queries` discovery.

## 7. Tests

- [x] 7.1 Add a shared `integration/AbstractIntegrationTest` (or refactor the existing base) that starts singleton Testcontainers PostgreSQL (`PostgreSQLContainer`) — and ClickHouse where needed — wiring `spring.datasource.*`, `spring.flyway.enabled=true`, and the existing `clickhouse.*` via `@DynamicPropertySource`.
- [x] 7.2 Refactor `AbstractClickHouseIntegrationTest` and `AnalyticsDataAccessApplicationTests` so the full Spring context boots with the PostgreSQL `DataSource` present (they no longer fail for lack of a `DataSource`).
- [x] 7.3 Add `integration/MetadataCrudIntegrationTest` exercising end to end: create (201), list (200 object-wrapped), get (200/404), rename (200/404/409), delete (204/404), duplicate create (409), blank name (400).
- [x] 7.4 Add a test asserting the Flyway-provisioned `metadata` table exists (and ClickHouse tables are not present in PostgreSQL).
- [x] 7.5 Add a test asserting DSL isolation: `POST /v1/queries/execute` with `entity: "metadata"` returns 400, and `metadata` is absent from `/v1/queries` schema discovery.

## 8. Verify & document

- [x] 8.1 Run `./gradlew checkstyleMain checkstyleTest` and fix any style violations (Google Java Style, 180-char lines).
- [x] 8.2 Run `./gradlew clean build` and confirm compile + checkstyle + tests pass (Docker daemon required for Testcontainers).
- [x] 8.3 Update `openspec/project.md` if a convention changed (e.g. ADAS now has a JDBC `DataSource`/JPA and Boot Flyway is enabled for PostgreSQL); add a one-line note to `README.md` prose referencing `docs/configuration.md` for the PostgreSQL vars if appropriate.
