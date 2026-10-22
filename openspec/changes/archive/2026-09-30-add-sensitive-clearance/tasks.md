## 1. Privilege model

- [x] 1.1 Replace `Privilege.TABLE_READ_SENSITIVE` with `TABLE_SENSITIVE` (Javadoc: the clearance, confers nothing alone); update `TABLE_MODIFY` and `TABLE_GRANT` Javadoc (GRANT = grant lists only)
- [x] 1.2 `PrivilegeImplications`: drop the `READ_SENSITIVE → READ` edge; the DAG becomes `GET → LIST`, `READ → GET`, `MODIFY → GET`; rewrite the class Javadoc (ladder of three, clearance orthogonal)
- [x] 1.3 `RoleBundle.READ_ONLY_SENSITIVE_ANALYTIC` declares `TABLE_READ` + `TABLE_SENSITIVE`; confirm an unknown `TABLE_READ_SENSITIVE` mapping value fails startup naming the value and property
- [x] 1.4 `PrivilegeScope`: rename to `TABLE_SENSITIVE`, keep it table-scoped and grantable, and ensure it is not in the read-side set that answers `404` at the endpoint gate (design D10)
- [x] 1.5 `SystemIdentity`: hold `TABLE_READ` + `TABLE_SENSITIVE` globally; update the exact-containment assertion to the new closure
- [x] 1.6 Update `PrivilegeModelTest` and the bundle-closure tests for the new DAG and bundle sets

## 2. Grant lists and storage

- [x] 2.1 `TableAccess`: key `read_sensitive` → `sensitive` (field, `@JsonProperty`, `rolesFor`/set switch)
- [x] 2.2 DTOs: `ReplaceAccessRequest`, `AccessDto`, `AccessReplacedDto` use `sensitive`; the strict request mapper refuses `read_sensitive` with `400` naming it
- [x] 2.3 `EffectivePermissions` / `TableDto.Permissions`: `read_sensitive` → `sensitive` (reports the clearance); `TableMapper` and `TableAccessController` wiring
- [x] 2.4 `TableAccessService`: add ineffective-grant reason `sensitive_without_verb` (role in `sensitive` holding none of GET/READ/MODIFY on the table, globally or by list)
- [x] 2.5 ~~Add `V44__table_access_sensitive_clearance.sql`~~ — removed: no stored data uses the old key (design D9)
- [x] 2.6 ~~Migration test~~ — removed with the migration

## 3. Sensitive-column predicate and query surfaces

- [x] 3.1 `SensitiveColumnAccess`: `maySeeSensitive(T)` = `TABLE_SENSITIVE` on T; add `mayReadSensitive(T)` = `TABLE_READ` ∧ `TABLE_SENSITIVE` on T (design D2)
- [x] 3.2 Keep `CatalogQueryService.buildModel` on `maySee` (the verb bar is already applied); verify discovery, DSL, SQL, translation, validation and Flight tests still pass with renamed privileges
- [x] 3.3 Switch the paths with no own verb bar to `mayReadSensitive`: `EnrichSpecValidator` and `AggregateSpecValidator` author checks, `SensitiveProjectionGuard`, the scan/group re-checks, `MaterializeService` (grain-join-owned columns) (the saved-query publish path has no sensitive check of its own and needs none: it resolves at the TABLE_READ bar); update their error messages to name `TABLE_READ and TABLE_SENSITIVE`
- [x] 3.4 Tests: an author holding the clearance but no `TABLE_READ` on the source is refused exactly as an author with neither; global READ + per-table clearance is accepted

## 4. Catalog read surface

- [x] 4.1 `TableMapper`: filter `columns` and `column_count` by `maySeeSensitive(table)` (fail open in `none`); leave structural fields unfiltered
- [x] 4.2 The revision read (`GET /v1/tables/{name}/revision/{revision}`) filters on the snapshot's flags with the caller's current clearance
- [x] 4.3 Integration tests: GET-only caller sees N−k columns and `column_count` N−k; GET + clearance sees N; list and single-get agree; revision read hidden then shown after granting `sensitive`

## 5. Schema mutation

- [x] 5.1 Delete `TableSchemaService.requireGrantAuthority` and its call; remove the TABLE_GRANT reasoning from the class Javadoc and inline comments
- [x] 5.2 `PATCH .../schema`: resolve `drop`, `rename.from` and `update` against the caller-visible column set (unknown → 422 with the same message as a non-existent column); `add`/`rename.to` collisions still check the full set
- [x] 5.3 Without the clearance: any `update` carrying `sensitive` and any `add` with `sensitive: true` → `403 forbidden` (`AccessDeniedException`, naming `TABLE_SENSITIVE`)
- [x] 5.4 `TableSchemaCreationService` (`POST .../schema`): a declared sensitive column without the clearance → `403`, nothing materialized
- [x] 5.5 Keep the cursor-column rule and the laundering check in place and ordered after the authorization checks
- [x] 5.6 Update `CreateTableRequest.ColumnRequest.sensitive` and `ColumnInput` / `ColumnMappingEntity` docs to the clearance wording
- [x] 5.7 Tests: the table-management and authentication scenarios in this change (hidden drop/rename/update → 422; set/add → 403; delegated owner sets and clears; TABLE_GRANT alone → 403)

## 6. Laundering invariant

- [x] 6.1 `SensitiveGrantInvariant`: the source side becomes roles listed under both `read` and `sensitive`; the derived side is the `sensitive` list; the `modify` list is not consulted; update the `SensitiveGrantWidensException` message to name both source keys
- [x] 6.2 Update the enrich and aggregate invariant tests (registration, grant widen, source narrow via `read` or `sensitive` revoke, mark-sensitive) to set both source lists

## 7. Consumers, docs and conventions

- [x] 7.1 `docs/configuration.md`: update the privilege list and the example `roles-mapping` (no new property row)
- [x] 7.2 `openspec/config.yaml` context: the privilege count and ladder text (three rungs plus the clearance), the 404 read-side set, and the chokepoint note on the two predicates
- [x] 7.3 MCP `adas_*` table access tools and the bundled tables/schema demo pages (since removed): the `sensitive` key and flag (no change in this repo: the demo pages do not use the grant keys, and the MCP tools live outside it — follow-up there)
- [x] 7.4 Grep for leftover `READ_SENSITIVE` / `read_sensitive` in `src/`, `docs/`, `grafana/`, the MCP tool descriptions and the OpenAPI descriptions; none may remain outside V44 and the archived changes

## 8. Verification

- [x] 8.1 `openspec validate add-sensitive-clearance --strict`
- [x] 8.2 `./gradlew clean build` passes (compile with -Werror, checkstyle, full test suite)
