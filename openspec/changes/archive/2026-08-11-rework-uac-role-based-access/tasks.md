# Tasks

## 1. Raw provider role retention
- [x] Add `ProviderRoleAuthority` (prefix + helpers).
- [x] Retain prefixed raw roles in `JwtAuthenticationConverter`, `OpaqueAuthenticationConverter`,
      `ApiKeyAuthenticationFilter`; authenticate valid tokens even with no application role.
- [x] `SecurityClaimsExtractor.getRoles()` filters the prefix out; add `getProviderRoles()`.

## 2. Storage
- [x] `TableAccess` POJO; `access` JSONB column on `TableDefinitionEntity` (`@JdbcTypeCode(JSON)`).
- [x] Replace `V5__create_table_grants.sql` with `V5__add_table_access.sql` (access column).
- Audit of access changes (Envers) is deferred to a future change — intentionally not shipped here.

## 3. Evaluator
- [x] Rewrite `TableAccessEvaluator`: FULL_ADMIN bypass, else provider-role ∩ access[permission].

## 4. Filter chain
- [x] Relax the three per-table mutating matchers to `authenticated()`; keep `@PreAuthorize`.

## 5. Access-management API
- [x] `TableAccessController` (`GET`/`PUT /v1/tables/{name}/access`, `@FullAdminOnly`).
- [x] `TableAccessService` (get/replace, dedupe, blank-role → 422, unknown table → 404).
- [x] `AccessDto`, `ReplaceAccessRequest`.

## 6. Removal
- [x] Delete `table_grant` entity/repo/service, subject enums, `ClaimRuleMatcher`, grant DTOs/mapper,
      `TableGrantController`, `CallerIdentity(+Resolver)`, and their tests.

## 7. Tests
- [x] `TableAccessEvaluatorTest`, `TableAccessServiceTest`, `TableAccessIntegrationTest`.
- [x] `TableAuthIntegrationTest`: no-role token → 403 (not 401).
- [x] Update `ApiKeyAuthenticationFilterTest` for raw-role retention.

## 8. Spec/docs
- [x] Delta specs: table-access-control, authentication, table-management.
- [x] Update `docs/configuration.md` (raw-role names).

## 9. Verify
- [x] `./gradlew clean build` green.
- [x] End-to-end against the local oidc stack (role write w/o app role; WRITE≠MODIFY; FULL_ADMIN
      bypass). Verified 2026-07-27 against local Keycloak + DIAL Core; that run also surfaced the
      system-table gap addressed by the follow-up `guard-system-table-access` change.
