## 1. Privileges

- [x] 1.1 Add `SETTINGS_READ` and `SETTINGS_WRITE` to `Privilege` (Javadoc: object class `SETTINGS`, both global-only), and keep them out of the table-scoped set in `service.table.grant.PrivilegeScope`
- [x] 1.2 Add the direct implication `SETTINGS_WRITE → SETTINGS_READ` to `PrivilegeImplications`
- [x] 1.3 Add `SETTINGS_READ` to the `READ_ONLY_ADMIN`, `READ_ONLY_ANALYTIC` and `READ_ONLY_SENSITIVE_ANALYTIC` bundles in `RoleBundle`; confirm `FULL_ADMIN` picks up both by construction
- [x] 1.4 Extend `PrivilegeModelTest` (and any bundle-expansion test) for the new bundle contents and the implication edge, including "only `FULL_ADMIN` holds `SETTINGS_WRITE`"

## 2. Storage and audit

- [x] 2.1 Add PostgreSQL migration `V53__instance_setting.sql` (next free number) creating `instance_setting (name TEXT PRIMARY KEY CHECK (name IN ('sessions-source')), value JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL, updated_by TEXT)` and `instance_setting_aud` keyed `(rev, name)` with a `(name, rev)` index; one-line header linking `openspec/specs/instance-settings/spec.md`
- [x] 2.2 Add `service/setting/`: `@Audited` `InstanceSettingEntity`, `InstanceSettingRepository`, and the `SessionsSourceSetting` value record (`base_table`, `roles`) with `@EqualsAndHashCode` semantics, converted to and from the `value` text with the application `JsonMapper`
- [x] 2.3 Add `Setting` to `ActivityResourceType` and a `Setting` case to `AuditResourceMapper` (resource id = setting name, no parent)

## 3. Validation and service

- [x] 3.1 Implement `SessionsSourceValidator`: resolve `base_table` through `CatalogQueryService.findModel(base_table, Privilege.TABLE_GET)` under the caller's authorities; check required roles, field existence by flat name, role-to-`QueryFieldType` fit, the array rule and the projection-only rule; collect a message naming `base_table` / `roles.<role>` (type mismatch names the field type and the accepted types); never distinguish hidden from absent
- [x] 3.2 Implement `SessionsSourceSettingService`: `get()` returning the stored setting or throwing `SettingNotFoundException`; `replace(body)` validating, stamping `updated_at` and `updated_by` (principal name, `null` in `none` mode, via `SecurityClaimsExtractor`) and upserting in one transaction, retrying once as an update on a first-write primary-key race
- [x] 3.3 Add `SettingNotFoundException` (`404 setting_not_found`) and `SettingValidationException` (`422 validation_error`) with handlers in `DefaultExceptionHandler`

## 4. Web layer

- [x] 4.1 Add DTOs under `web/dto/setting/`: `SessionsSourceSettingRequestDto` (`base_table`, `roles` only, so `updated_at`/`updated_by` and any other member are refused `400` by the strict request mapper) and `SessionsSourceSettingDto` (adds `updated_at`, `updated_by`; unset roles serialized as `null`), snake_case via `@JsonProperty`
- [x] 4.2 Add `web/controller/setting/SettingsController` with `GET /v1/settings/sessions-source` (`@RequiresPrivilege(SETTINGS_READ)`) and `PUT /v1/settings/sessions-source` (`@RequiresPrivilege(SETTINGS_WRITE)`), with OpenAPI annotations for `200`, `400`, `403`, `404`, `422`
- [x] 4.3 Confirm `EndpointAuthorizationCoverageTest` passes with the new mappings

## 5. Tests

- [x] 5.1 Unit tests for `SessionsSourceValidator`: each role's accepted and refused types, missing/null required roles, unknown field, enrichment qualified name, array field, projection-only field, non-source / unknown `base_table`
- [x] 5.2 Integration test (`AbstractClickHouseIntegrationTest`, owns its data): `404` before first write; valid `PUT` then `GET` round trip with `updated_at`/`updated_by`; replace drops roles not resent; `422` leaves the stored value unchanged; `400` for `updated_by` and unknown members; stored value survives a column drop and a re-`PUT` of it is refused `422`
- [x] 5.3 OIDC auth integration test (`AbstractOidcAuthIntegrationTest`): `READ_ONLY_ANALYTIC` reads `200` and writes `403 forbidden`; `FULL_ADMIN` writes `200`; a raw `TABLE_READ` mapping reads `403`; a writer lacking `TABLE_SENSITIVE` binding a sensitive column gets the same `422` as an absent field, and `FULL_ADMIN` binding it is refused `422` as sensitive
- [x] 5.4 Audit integration test: first `PUT` records `Create` for `Setting`/`sessions-source` with no parent, a later one records `Update`, a refused `PUT` and a `GET` record nothing, and the feed filters on `resourceType eq Setting`

## 6. Docs and specs

- [x] 6.1 `docs/configuration.md` security section: update "sixteen" to "eighteen" and the bundle table to add `SETTINGS_READ` (no new property rows)
- [x] 6.2 `openspec/config.yaml` context: update the privilege count ("Sixteen `Privilege` values") and add `instance_setting` to the list of audited entities
- [x] 6.3 Add a note to the upcoming release's upgrade guide under `docs/` that a `roles-mapping` naming raw privileges must add `SETTINGS_READ` for its holders to read the sessions-source setting, and that rollback requires removing any explicit `SETTINGS_*` names first
- [x] 6.4 `CLAUDE.md` "What this is": mention the instance settings surface (`/v1/settings/sessions-source`, capability `instance-settings`)
- [x] 6.5 Confirm the delta specs (`instance-settings`, `authentication`, `audit-trail`) match the shipped behavior and run `openspec validate add-sessions-source-setting --strict`

## 7. Verification

- [x] 7.1 Run the public-repo hygiene sweep from `CLAUDE.md` over the changed files (`git grep -nIP` plus the multiline pass)
- [x] 7.2 `./gradlew clean build` passes
