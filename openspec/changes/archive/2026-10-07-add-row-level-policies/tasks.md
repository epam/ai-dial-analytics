## 1. Storage

- [x] 1.1 Confirm the next free Postgres Flyway version against concurrently added migrations and add `V45__table_row_policies.sql`: `row_policies JSONB NOT NULL DEFAULT '{}'` on `table_definition`, nullable `row_policies JSONB` on `table_definition_aud`
- [x] 1.2 Map `row_policies` on `TableDefinitionEntity` as JSON text, converted by `RowPolicyCodec` with the application `JsonMapper` (Hibernate's Jackson 2 mapper cannot read `FilterNode`); `RowPolicy` record (`filter` | `all_rows`)
- [x] 1.3 Audit trail: the column lives on the already-`@Audited` entity and its `_aud` mirror, so every policy change is a table-definition revision with no mapper change

## 2. Policy validation and rendering

- [x] 2.1 `CatalogQueryService.tableBindings(table)`: the table-only model (all stored columns incl. sensitive, no enrichment joins, no caller filtering)
- [x] 2.2 Policy validation in `RowPolicyService`: shape (`filter` xor `all_rows: true`; unknown members 400 via the strict request mapper), role rules (trimmed, non-blank, not a bundle or privilege name), grammar scope (`param`, aggregate/ordered-set/window functions, window expressions, subqueries refused; dotted enrichment fields unknown on the table-only model), translation against the table's columns, 16 KiB rendered cap — each of these `422 validation_error`; a body that does not bind (malformed JSON, unknown member) is `400 bad_request`, as the delta spec states
- [x] 2.3 `QueryFunctionCatalog.callsExternalService(name)` hook (false for every function today) consulted by the policy scope check; `add-ai-query-functions` declares its functions there
- [x] 2.4 `RowPolicyPredicates.render`: `FilterTranslator` against the table-only model, rendered inlined; the setting value is escaped a second time as a map string literal (`RowPolicyFilter.settingValue`, unit-tested with the hostile value `P0') OR 1 OR ('`, and end-to-end in the integration test)

## 3. Evaluation and enforcement

- [x] 3.1 `ClickHouseProperties.sessionDatabase()` — the configured database or `default` — shared by the client builder and the filter's keys
- [x] 3.2 `RowPolicyFilter`: per table "unconfined" / OR of matching filters (`all_rows` wins), an entry for every confined catalog table keyed `'<db>.<table>'`; empty in `none` mode and for a caller with no provider roles (the system identity)
- [x] 3.3 `StructuredQueryExecutor.execute` and `executeComposite` apply the filter after every other merged setting; nothing is sent when no table confines the caller; pipeline scans never ask (unit-tested)
- [x] 3.4 `permissions.rows_restricted` on table list/get/mutation responses and the `/access` PUT response, from `RowPolicyFilter.isConfined`; `PermissionsDto` OpenAPI description
- [x] 3.5 Debug log of confined table names and caller role count (never the predicate); `adas.row_policy.tables` span attribute

## 4. Policy API

- [x] 4.1 `TableRowPolicyController`: `GET /v1/tables/{name}/row-policies`, `PUT` and `DELETE …/row-policies/{role}`, each `@RequiresPrivilege(TABLE_GRANT)`; `{"policies": {...}}` envelope; `404` for an unknown table or an absent role on delete; `204` on delete
- [x] 4.2 Stored filters returned in the DSL wire serialization; OpenAPI annotations on the three mappings and the DTOs

## 5. Governance

- [x] 5.1 Schema PATCH guard: drop/rename of a column any policy references → `409 column_in_row_policy` naming column and roles (`ColumnInRowPolicyException`, mapped in `DefaultExceptionHandler`)
- [x] 5.1a Schema re-definition guard: `POST …/schema` on a not-yet-materialized (`PENDING`/`FAILED`) table whose new column set no longer exposes a column a policy references → the same `409 column_in_row_policy` (`CatalogSchemaWriter.persistSchema`, before any column is replaced or DDL issued)
- [x] 5.2 Arming (`PipelineService.create/patch`, before anything is applied), aggregate preview and on-demand refresh → `403 row_policy_confined` (`RowPolicyConfinedException`, mapped) when the caller is confined on the input, the target's `source_table`, or an enrichment of either
- [x] 5.3 `PipelineAuthorizer`: scan / group-rows / materialize / enrich preview refuse a confined reader with `403 row_policy_confined`; the write-only `rows` path is not asked
- [x] 5.4 Invariant tests: SQL carrying `SETTINGS additional_table_filters = {}` and SQL reading through `merge(...)` are refused

## 6. Tests

- [x] 6.1 Unit: `RowPolicyFilterTest` (no role, one role, two roles OR, `all_rows` lifts on its table only, every confined table present, session-database keys, `none` mode, system identity, setting escaping, `isConfined` agreement, pipeline refusal through the target's source)
- [x] 6.2 Unit: `PipelineAuthorizerTest` (confined reader refused, writer not asked); `StructuredQueryExecutorTest` (user reads carry the filter, pipeline scans never)
- [x] 6.3 Integration (`RowPolicyIntegrationTest`, oidc, real PostgreSQL + ClickHouse): policy API status codes and validation; a global reader confined on DSL execute with `include_total`, `execute-sql` and `execute-sql-csv`; join, self-join, `IN` subquery and `UNION ALL` all filtered; roles OR, a policy-less role does not widen, `all_rows` lifts; a policy grants no read; `rows_restricted`; hostile literal; query-text override and table-function bypass refused; schema guard; confined author refused on enable and preview while an unconfined one reaches the gate

## 7. Docs and specs

- [x] 7.1 `docs/row-level-security.md` (worked example, the rules, derived-table checklist, cheap predicates, `system.query_log`, rollback); linked from README prose
- [x] 7.2 No `docs/configuration.md` change — the change adds no property
- [x] 7.3 Delta specs aligned with shipped behaviour (one `row_policy_confined` code on every pipeline surface; the superset of pipeline reads; session-database keys); `openspec validate add-row-level-policies --strict`

## 8. Verification

- [x] 8.1 `./gradlew clean build` passes
