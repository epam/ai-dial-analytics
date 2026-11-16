## 1. The `grant` flag

- [x] 1.1 Add `grant` to `EffectivePermissions` and set it in `TableCallerPermissions` from the caller's global
  `TABLE_GRANT`: `true` in `none` mode, the same on `system` tables and enrichments, never set by an entry. Update the
  record's Javadoc ("one flag per table-scoped privilege" no longer holds) and fix the callers of its canonical
  constructor (`TableCatalogServiceTest` among them)
- [x] 1.2 Emit `permissions.grant` from `TableDto.PermissionsDto` and `TableMapper` on every surface that carries
  `permissions` (list, get, create, description update, schema `PATCH`, revision snapshots), with the DTO's Javadoc and
  `@Schema` text updated
- [x] 1.3 Unit tests in `TableCallerPermissionsTest` for the cases in the spec: `TABLE_GRANT` held, not held, an entry
  with every privilege, `system` table and enrichment, and `none` mode; update existing assertions that count or list
  the flags
- [x] 1.4 Integration assertions that `permissions.grant` is `true` for a `TABLE_GRANT` holder and `false` without it,
  on `GET /v1/tables/{name}`, on the list, and on the create response

## 2. Warnings

- [x] 2.1 Add the `everyone_reads_all_rows` reason to `reportIneffective`: `read` for `*` with `rows` `null` on a
  non-enrichment table, whatever `columns`. Place it after the two `lifts_*` checks and before the `continue` that
  skips the remaining reasons, so the item order is the spec's. Add it to the PUT's OpenAPI description
- [x] 2.2 Build `ineffective_grants` for both reads from the same report, judged on stored state: every entry in role
  order for the whole configuration, the one entry for a one-role read. Load the table's enrichments and decode its
  policies once per request rather than once per entry
- [x] 2.3 Add `ineffective_grants` to the whole-configuration and one-role response DTOs (always present, `[]` when
  empty) and to the GET's OpenAPI description; fix `TableAccessControllerTest` for the changed `RoleEntry`
- [x] 2.4 Update the tests the new reason breaks: `RowPolicyIntegrationTest` (`hasSize(1)` on a `*` read report) and
  `TableAccessServiceTest` (`*` `read` with `columns` asserted empty), and any other exact report assertion on a `PUT`
  of `read` for `*`
- [x] 2.5 Integration tests for the scenarios in the delta spec: a read for everyone over a row policy reported on both
  reads in the stated order and absent from the other role's read; a warning that appears after another role's change
  and disappears when it is undone; `sensitive_without_verb` disappearing after `*` gains `get` or the role gains
  `modify` on an enrichment; `everyone_reads_all_rows` on save and read (with `rows`, with `columns` only, `get` only,
  no privilege with `rows`, enrichment leftover); empty reports
- [x] 2.6 A unit test that `already_global` is judged on the role mapping at the read (mocked `ProviderRolePrivileges`)

## 3. Docs

- [x] 3.1 `docs/row-level-security.md`: the `grant` flag next to `rows_restricted`/`columns_restricted`; the two GET
  rows of the access API table (lines ~14-15) and the response description listing the reasons (~34-36) now carry
  `ineffective_grants` and five reasons, one of them a standing warning; `everyone_reads_all_rows` in the "Everyone"
  section
- [x] 3.2 `docs/release-0.24-upgrade.md`: §11 lists `grant`, `ineffective_grants` on both reads and the new reason as
  additive; correct "keeps its shape and reasons" (~line 338); add the additions to the Summary table

## 4. Verification

- [x] 4.1 `openspec validate access-ui-hints --strict`
- [ ] 4.2 `./gradlew clean build`
