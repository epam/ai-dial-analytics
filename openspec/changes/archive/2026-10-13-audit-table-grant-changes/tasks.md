## 1. Shared grant DTO conversion

- [x] 1.1 Move the private static `TableAccess → AccessDto` helper (`TableAccessController:81`) and the `RowPolicy → RowPolicyDto` helper (`TableRowPolicyController:81`) to shared code (static factories on the DTOs); both controllers call them, behaviour unchanged
- [x] 1.2 Make the access conversion null-safe: a null `TableAccess` (historic rows) maps to five empty lists

## 2. Snapshot grant members

- [x] 2.1 Add nullable `access` (`AccessDto`) and `row_policies` (`Map<String, RowPolicyDto>`) to `TableDto`, with OpenAPI descriptions: "revision snapshot only; requires `TABLE_GRANT` (always present in `none` mode); absent means withheld"
- [x] 2.2 Inject `RowPolicyCodec` into `TableMapper`; add `toDto(TableView, boolean includeGrants)` that fills both members from the historic entity when true (`row_policies` via `codec.read`, `{}` when NULL); every existing caller stays on `false`
- [x] 2.3 In `TableController.snapshot`, compute `includeGrants = !securityMode.isEnforced() || claims.hasPrivilege(Privilege.TABLE_GRANT)` and pass it through
- [x] 2.4 Update the hand-built `TableMapper` constructors in `TableMapperColumnVisibilityTest:35` and `CatalogBundleMappingTest:51`

## 3. No-op write guards

- [x] 3.1 `TableAccessService.replaceAccess`: skip `setAccess` when, for every privilege, the merged list equals the current one as a set (stored order kept)
- [x] 3.3 Per list, keep the stored order when the role set is unchanged, also on a save that changes another list
- [x] 3.2 `RowPolicyService.put`: return the stored policy without `setRowPolicies` when `stored.equals(current.get(grantee))`

## 4. Tests

- [x] 4.1 OIDC integration (extend `AbstractOidcAuthIntegrationTest`, no extra properties; feed filtered `resourceId eq <table>` and `resourceType eq Table`, sorted by `revision`), on an active enrichment table as in the bug: `PUT /access` `[]`→`[a,b]` → as `fullAdmin()` revision n and n-1 differ in `access`
- [x] 4.2 Same harness: row-policy `PUT` then `DELETE` → snapshots differ in `row_policies`; a pre-policy snapshot carries `row_policies: {}`
- [x] 4.3 The same revisions read as `viewer` (READ_ONLY_ADMIN) carry neither member
- [x] 4.4 `PUT /access` `[a,b]`→`[b,a]` records no Table activity and `GET /access` keeps `[a,b]`
- [x] 4.5 Identical row-policy `PUT` twice → exactly one `Update / Table` activity
- [x] 4.6 None mode (`AuditActivityFeedIntegrationTest`): the snapshot carries both members; live `GET /v1/tables/{name}` and the list carry neither
- [x] 4.7 Correct the comment at `SensitiveClearanceIntegrationTest:180-181`: a revision read is authorized against the table's live grants, not the revision's

## 5. Specs and hand-off

- [x] 5.1 Confirm the delta specs match shipped behaviour; `openspec validate audit-table-grant-changes --strict`
- [ ] 5.2 adas-mcp: refresh the OpenAPI snapshot (`--sort-keys`)
- [ ] 5.3 Optional FE note: labels for `access` / `row_policies` in `ActivityAudit/EntityGrid/constants.ts`

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes
