## 1. Role as a query parameter

- [x] 1.1 Map one role's `GET`/`PUT`/`DELETE` to `/v1/tables/{name}/access?role=`, the list to `params = "!role"`, and trim the role with an `@InitBinder` so a missing or blank one is `400` before the privilege check
- [x] 1.2 Remove the `HttpFirewall` bean and the Tomcat encoded-solidus customizer; keep the `ErrorView` `RequestRejectedHandler`
- [x] 1.3 Remove the health-path `IllegalArgumentException` handler and its exclusion in `DefaultExceptionHandlerInlineQueryTest`
- [x] 1.4 Replace the encoded-path integration tests with `AbstractRoleQueryParamIntegrationTest` (oidc and none): `/analytics/viewers`, `team%ops`, `a;b` round-trip; missing and blank role `400`; `%2F` in a path segment `400`; the path form `404`
- [x] 1.5 Move every test and doc call to `?role=`
- [x] 1.6 Publish the list and the one-role read as one OpenAPI `GET` with an optional `role` (asserted in `OpenApiPublishedContractIntegrationTest`); refuse a `role` given more than once `400`

## 2. Per-role conflict detection

- [x] 2.1 Add `access_role_generations` to the unreleased `V57` migration, the entity (`@NotAudited`) and `RoleGenerationCodec`; extend `RoleKeyedTableAccessMigrationTest`
- [x] 2.2 `PUT`: hold back the role and body refusals, answer an unchanged entry `200` without a write, refuse a generation above the table's or older than the role's mark, then write, bump and mark
- [x] 2.3 `DELETE`: optional `generation` query parameter (`422` non-integer, `404` no entry, `409` conflict), bump and mark (tombstone)
- [x] 2.4 `ColumnPolicyRewriter.apply` marks only the roles whose lists changed
- [x] 2.5 Unit tests in `TableAccessServiceTest` and `TableSchemaServiceTest`; integration tests in `AccessGenerationIntegrationTest` (different roles both succeed, same role conflicts, retried `PUT`, generation above the table's, conditional `DELETE`, tombstone, rename marks); update the tests whose expectation changed

## 3. Revision snapshots apply the current column policies

- [x] 3.1 `SensitiveColumnAccess.visibleColumnsAtRevision` and `ColumnVisibility.and`; `TableMapper` uses them on the revision path
- [x] 3.2 Tests in `TableMapperColumnVisibilityTest` and `TableGrantRevisionIntegrationTest` (policy added after the revision, pre-`V57` revision, renamed column)

## 4. Bounded wait on the definition row lock

- [x] 4.1 `TableDefinitionLocking` fragment: transaction-local `lock_timeout` around the locking query, `TableBusyException` on `55P03`
- [x] 4.2 `tables.lock-timeout` in `application.yml` and `docs/configuration.md`; `409 table_busy` in `DefaultExceptionHandler` and the OpenAPI text of the affected endpoints
- [x] 4.3 Integration test: a writer waiting past the timeout gets `table_busy` and the holder's change stands
- [x] 4.4 Refuse `tables.lock-timeout` below `1ms` at startup; integration test that `lock_timeout` is restored right after the locking statement
- [x] 4.5 A schema definition refused `table_busy` after its `CREATE` undoes the `CREATE`, so a retry as sent succeeds

## 5. Specs and docs

- [x] 5.1 Delta specs for `table-access-control`, `authentication`, `audit-trail`, `catalog-export-import`, `table-management`
- [x] 5.2 `docs/release-0.24-upgrade.md`, `docs/row-level-security.md` (incl. the multi-role column example), `docs/claude-code-telemetry.md`, `docs/catalog-promotion.md`, `docs/recommended-catalog/README.md`, `docs/configuration.md`, `openspec/config.yaml`, Javadoc and OpenAPI text
- [x] 5.3 `openspec validate access-api-review-fixes --strict`, `openspec validate --specs --strict`, and `./gradlew clean build`
