# Tasks: caller-table-permissions

## 1. Permissions resolver (shared with enforcement)

- [x] 1.1 Add `EffectivePermissions` record (boolean `write`, `modify`) and a
      `TableCallerPermissions` component in `service/table/grant` that resolves a
      `TableDefinitionEntity` per design D2: system table → both false; `security.mode=none`
      (injected `${security.mode:none}`) → both true; `FULL_ADMIN` → both true; else provider-role
      match against the table's `TableAccess` lists (null-safe).
- [x] 1.2 Extract the role-matching used by `TableAccessEvaluator.isAllowed` so evaluator and
      resolver share one implementation; evaluator behavior unchanged (missing table → false, no
      system check).
- [x] 1.3 Unit tests for the resolver: system table (admin included), none mode, FULL_ADMIN,
      matching write-only role, matching modify-only role, no match, null/empty access lists.

## 2. DTO + mapper

- [x] 2.1 Add nested `PermissionsDto`/`permissions` field to `TableDto` with `@Schema` docs;
      populate it in `TableMapper.toDto(TableView)` and the list-item mapping via the injected
      resolver (no extra catalog queries).
- [x] 2.2 Update existing mapper/controller tests for the new field and add assertions that list
      items and single-get carry always-present boolean flags.

## 3. Integration coverage

- [x] 3.1 Extend `TableAccessIntegrationTest` (oidc): writer-role caller sees
      `write=true/modify=false` on list and get; unlisted caller sees both false; admin sees both
      true on non-system table; system table reports both false for admin.
- [x] 3.2 Assert none-mode behavior (existing none-mode integration/controller test): permissions
      present and both true for a non-system table.

## 4. Specs & docs

- [x] 4.1 Verify delta specs under `openspec/changes/caller-table-permissions/specs/` match shipped
      behavior; adjust if implementation details diverged.
- [x] 4.2 Confirm no config/docs changes needed (no new properties; `docs/configuration.md`
      untouched).

## 5. Verify

- [x] 5.1 `./gradlew clean build` passes (compile + checkstyle + tests).
