## 1. Persistence: table_grants

- [x] 1.1 Add a Flyway migration under `src/main/resources/db/migration/postgres` creating
      `table_grant` (id, table_name FK → table_definition ON DELETE CASCADE, subject_type, project,
      claim_name, claim_operator, claim_value, permission, created_by, created_at) with the
      `(table_name, subject_type, permission, project, claim_*)` unique index and an index on
      `table_name`. (Table is `table_definition`, not `table_catalog`; name is singular per convention.)
- [x] 1.2 Add a domain model for a grant (subject_type, project/claim-rule fields, permission) and
      the `Permission` (WRITE|MODIFY), `GrantSubjectType` (PROJECT|CLAIM_RULE), and `ClaimOperator`
      (EQUALS|CONTAINS|STARTS_WITH) enums.
- [x] 1.3 Add a grant repository (Spring Data JPA — the catalog uses JPA, not jOOQ) with
      `findByTableName` and `findByIdAndTableName`; cascade is enforced by the FK.

## 2. Caller identity on the security context

- [x] 2.1 Enrich `ApiKeyAuthenticationToken` (project flag/project/claims), `IntrospectionResult`
      (carry normalized `userClaims`), `CoreApiKeyIntrospector`, and the API-key filter.
- [x] 2.2 Add a `CallerIdentity` record + `CallerIdentityResolver` exposing `projectKey`, `project`,
      and `claims` normalized across API-key, Bearer JWT, and opaque callers.
- [x] 2.3 Unit-test the identity view for all four shapes (`CallerIdentityResolverTest`: project key,
      JWT-rooted key, Bearer JWT, opaque), asserting project vs claims are populated correctly.

## 3. Grant service and evaluation

- [x] 3.1 Add `TableGrantService`: create (validation → 422, duplicate → 409, unknown table → 404),
      list, and revoke (unknown grant → 404); stamp `created_by` from `SecurityClaimsExtractor`.
- [x] 3.2 Implement `ClaimRuleMatcher` (`EQUALS`/`CONTAINS`/`STARTS_WITH`, scalar and list-valued
      claims; missing claim never matches).
- [x] 3.3 Add the `tableAccess` method-security bean with `canWrite(name)` / `canModify(name)`:
      FULL_ADMIN bypass, else load grants and match subject + required permission.
- [x] 3.4 Unit-test evaluation (`TableAccessEvaluatorTest` + `ClaimRuleMatcherTest`): WRITE vs MODIFY
      independence, PROJECT vs CLAIM_RULE caller pairing, FULL_ADMIN bypass, and no-match denial.

## 4. API surface

- [x] 4.1 Add grant DTOs (`CreateGrantRequest`, `GrantSubjectDto`+`ClaimRuleDto`, `GrantDto`,
      `GrantListDto`) with bean-validation; subject-shape validation is server-side in the service.
- [x] 4.2 Add `TableGrantController` (`GET`/`POST`/`DELETE /v1/tables/{name}/grants[/{grantId}]`) as
      `@FullAdminOnly`, wired to the grant service, with `GrantMapper`.
- [x] 4.3 Swap `@FullAdminOnly` for `@PreAuthorize("@tableAccess.canWrite(#name)")` on `writeRows`
      and `@PreAuthorize("@tableAccess.canModify(#name)")` on `patchSchema` and `update` in
      `TableController`; keep `create`/`delete` on `@FullAdminOnly`.
- [x] 4.4 No new handlers needed: reuse `TableNotFoundException` (404), `TableConflictException`
      (409), and `TableValidationException` (422), which already map to the `ErrorView` contract.

## 5. Tests

- [x] 5.1 Integration test grant CRUD (`TableGrantIntegrationTest`: create/list/revoke, 422/409/404
      paths, FULL_ADMIN-only).
- [x] 5.2 Integration test per-table authorization: WRITE grant permits rows but not MODIFY; MODIFY
      grant permits description update; no-grant denial; delete stays FULL_ADMIN-only even with MODIFY;
      grants cascade on table delete (verified by delete→recreate→empty grants).
- [x] 5.3 `none` mode: covered by the existing `TableManagementIntegrationTest`, which drives
      `PATCH /schema` (and rows) unauthenticated in `none` mode — the re-annotated endpoints stay open
      because method security is disabled in `none` mode.

## 6. Docs and spec sync

- [x] 6.1 Confirmed no new tunables — `docs/configuration.md` needs no change (grants add no config
      properties; they are data, not configuration).
- [x] 6.2 Updated `openspec/project.md` with the `table-access-control` capability and the
      `service/table/grant` area.
- [x] 6.3 Ran `./gradlew clean build` (compile + checkstyle + tests) — BUILD SUCCESSFUL.
