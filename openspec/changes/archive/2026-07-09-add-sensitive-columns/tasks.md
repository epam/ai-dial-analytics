## 1. Catalog storage

- [x] 1.1 Add the next Flyway migration under `src/main/resources/db/migration/postgres/` (confirm the highest existing `V<n>` first): `ALTER TABLE column_mapping ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE`.
- [x] 1.2 In the same migration, seed sensitivity for both source tables: `UPDATE column_mapping SET sensitive = TRUE WHERE table_name = 'dial_usage_log' AND source_name IN ('jwt_claims','request_body','response_body')` and `UPDATE column_mapping SET sensitive = TRUE WHERE table_name = 'rate_analytics' AND source_name = 'comment'`. (`user_hash` and `request_tags` intentionally left visible.)
- [x] 1.3 Add a `sensitive` field (primitive `boolean`, default false) to `ColumnMappingEntity`, mapped to the new column.

## 2. Role-aware model filtering (enforcement chokepoint)

- [x] 2.1 Establish a "security enabled" signal for the model layer (resolved `security.mode` value or a dedicated flag bean wired from `OidcSecurityConfiguration`/`NoneSecurityConfiguration`); confirm how it is exposed before choosing.
- [x] 2.2 Add a helper deriving `maySeeSensitive = !securityEnabled || SecurityClaimsExtractor.getRoles().contains("FULL_ADMIN")`.
- [x] 2.3 Make `CatalogQueryService.buildModel`/`findModel` (and `addColumn`) role-aware: when `maySeeSensitive` is false, omit sensitive columns from both `bindings` and `baseSchema` so they are absent from the resolved `QueryEntityModel`.
- [x] 2.4 Audit any `QueryEntityModel` caching in `CatalogQueryService`; if models are cached per-entity, key the cache by `(entity, maySeeSensitive)` (or compute per-request) so admin/non-admin models do not bleed across callers.
- [x] 2.5 Confirm no separate handling is needed in `StructuredQueryBuilder` — the empty-projection expansion and `resolveField` operate over the (already filtered) `bindings`; add a code comment noting the filter lives upstream.

## 3. Discovery + query DTO surface

- [x] 3.1 Add `sensitive` to `QuerySchemaFieldDto` and populate it from `ColumnMappingEntity.sensitive` where the DTO is built in `CatalogQueryService`.
- [x] 3.2 Verify `/v1/queries/entities/schema/{name}` (structured), `/v1/queries/execute`, `/v1/queries/execute-sql`, and the Flight SQL producer all flow through the filtered model (no bypass path that reads `column_mapping` directly).

## 4. Table-management API surface

- [x] 4.1 Add `sensitive` (Boolean, default false) to `CreateTableRequest.ColumnRequest` and thread it into the persisted `ColumnMappingEntity` on source create.
- [x] 4.2 Add `sensitive` to `TableDto.ColumnDto` and its mapper so `GET /v1/tables` and `GET /v1/tables/{name}` return the flag (default false).
- [x] 4.3 Add `sensitive?` to the `add` operation in `PatchSchemaRequest` and persist it on added columns.
- [x] 4.4 Add a `set_sensitive` operation `{name, sensitive}` to `PatchSchemaRequest`; apply it catalog-only (no ClickHouse `ALTER`) after `retag` in the `drop → rename → add → retag → set_sensitive` order; reject an unknown column with 422.
- [x] 4.5 Preserve a column's `sensitive` flag across a `rename` (mirror the existing `tag` preservation).
- [x] 4.6 Wire MapStruct mappers / record builders that construct these DTOs/entities for the new field.

## 5. Tests

- [x] 5.1 Discovery: non-`FULL_ADMIN` caller does not see seeded sensitive columns; `FULL_ADMIN` (and `none` mode) sees them with `sensitive` flags set.
- [x] 5.2 Structured + SQL execute: non-admin referencing a sensitive column in `select`/`filter`/`group_by`/`having`/`sort` is rejected 400 unknown-field; empty projection excludes sensitive columns for non-admins; admin resolves them.
- [x] 5.3 `none` mode: sensitive columns visible in discovery and resolvable in queries (fail-open).
- [x] 5.4 Table-management: create with `sensitive: true` is returned; `PATCH set_sensitive` toggles and survives a `rename`; `set_sensitive` of an unknown column is 422; `GET /v1/tables` reflects the flag.
- [x] 5.5 Regression: existing discovery/query/table tests still pass with the default-false column.

## 6. Docs, specs, and build

- [x] 6.1 Update table-management API docs to note the new `sensitive` column field and `set_sensitive` PATCH operation (no new config property, so `docs/configuration.md` is unaffected).
- [x] 6.2 Confirm the delta specs (`authentication`, `query-schema-discovery`, `table-management`, `structured-query`) match the shipped behavior; adjust if implementation deviated.
- [x] 6.3 `openspec validate add-sensitive-columns --strict` passes.
- [x] 6.4 `./gradlew clean build` passes (compile + checkstyle + tests).
