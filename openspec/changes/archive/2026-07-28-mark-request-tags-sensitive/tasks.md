# Tasks: mark-request-tags-sensitive

## 1. Catalog seed

- [x] 1.1 Number against merged history only: the default branch tops out at
  `V11__enrichment_registry.sql`, so this is `V12`. Unmerged branches drafting their own V12–V14 are
  deliberately not consulted; whichever MR merges second renumbers.
- [x] 1.2 Add `src/main/resources/db/migration/postgres/V12__mark_request_tags_sensitive.sql`:
  `UPDATE column_mapping SET sensitive = TRUE WHERE table_name = 'dial_usage_log' AND source_name =
  'request_tags';`, with a header comment recording that it supersedes the V3 rationale and why
  (headers are stored verbatim, so the column can carry credentials/cookies/client IPs).
- [x] 1.3 Leave `V3__add_sensitive_columns.sql` untouched — it is applied everywhere and editing it
  breaks Flyway checksum validation.

## 2. Code

- [x] 2.1 None required. Verified that enforcement is generic and column-agnostic:
  `SensitiveColumnAccess.maySeeSensitive()` + the single filter in `CatalogQueryService.buildModel`
  already cover discovery, `execute`, `execute-sql`, Flight SQL, and star expansion.

## 3. Tests

- [x] 3.1 In `QuerySchemaDiscoveryIntegrationTest.returnsTheFlatBaseSchema`, assert the seeded
  sensitivity through the real migrated catalog: `request_tags`, `jwt_claims`, `request_body`, and
  `response_body` are `sensitive: true` and `user_hash` is `sensitive: false`. Tests run with
  `security.mode=none`, so the filter fails open and the existing `hasSize(38)` assertion still
  holds — only the flag differs.
- [x] 3.2 Confirm no existing test depends on `request_tags` being non-sensitive:
  `SqlQueryFrontEndTest` builds its own hand-filtered model (it removes only `jwt_claims`) so it is
  independent of catalog seeds; `SqlQueryExecutionIntegrationTest` runs in `none` mode; the mapping
  tests are ingest-side.

## 4. Spec & docs

- [x] 4.1 Add the delta `specs/table-management/spec.md` with an ADDED requirement "Seeded column
  sensitivity on the built-in source tables": pin the seeded sensitive set, record why
  `request_tags` is in it and `user_hash` is not, and state that the seed is operator-overridable
  via `PATCH`. Leave the enforcement requirements (`authentication`, `query-schema-discovery`,
  `structured-query`) untouched — they are generic and enumerate no columns.
- [x] 4.2 Verify no doc enumerates the sensitive set (`docs/column-tags.md` lists *tags*, which are
  unrelated), so no doc update is required.
- [x] 4.3 `openspec validate mark-request-tags-sensitive` passes.

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes (compile, checkstyle, tests).
- [x] 5.2 Migration applies on a clean database — covered by the integration suite rather than a
  separate `bootRun`: `PostgresContainerSupport` starts a fresh `postgres:16-alpine` Testcontainer
  with `spring.flyway.enabled=true`, so V12 runs against an empty database on every suite run, and
  the 3.1 assertion (`request_tags` → `sensitive: true` via
  `GET /v1/queries/entities/schema/dial_usage_log`) can only pass if it applied.
- [x] 5.2a Local `bootRun` caveat: the shared `adas-postgres` volume (db `testdb`) still has a
  since-dropped draft `V12__clear_system_table_access.sql` applied, so this migration's differing
  content fails there with a Flyway checksum mismatch. Use a throwaway database
  (`POSTGRES_DATABASE=<name> ./gradlew bootRun`) for local runtime checks, or recreate the volume.
  Testcontainers is unaffected — it starts empty every run.
- [ ] 5.3 Optional pre-merge check in `oidc` mode against the local Keycloak stack: as
  `READ_ONLY_ADMIN`, `request_tags` is absent from schema discovery and
  `SELECT request_tags FROM dial_usage_log` returns `400` unknown field (not `403`); as
  `FULL_ADMIN`, both succeed. Not required for merge — the filter itself is pre-existing, generic,
  and already covered by `SensitiveColumnAccessTest` and `CatalogQueryServiceTest`.
