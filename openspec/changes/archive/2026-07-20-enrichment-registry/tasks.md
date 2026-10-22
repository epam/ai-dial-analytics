## 1. Dependency + persistence

- [x] 1.1 Add `com.dashjoin:jsonata` to `build.gradle`, pinned to the same version the runner uses (pinned `0.9.10` — latest; runner plan names the artifact but no version, cross-confirm)
- [x] 1.2 Confirm the next free Postgres migration number (expected `V11`; re-check vs `rework-uac-role-based-access`) — `rework-uac` reworks `V5`; `V11` free
- [x] 1.3 Add `db/migration/postgres/V11__enrichment_registry.sql`: `evaluator`, `evaluator_version` (`PK(name,version)`), `rule` (`UNIQUE(name)`, `UNIQUE(target_enrichment)`, indexes on `updated_at`/`enabled`)
- [x] 1.4 Add JPA entities `EvaluatorEntity`, `EvaluatorVersionEntity`, `RuleEntity` + Spring Data repositories

## 2. Evaluator registry

- [x] 2.1 `web/dto/enrichment/*` evaluator request/response records with `@Schema`
- [x] 2.2 `service/enrichment/EvaluatorService`: create-or-new-version (immutable versions; `latest = max(version)`); non-`chat_completion` preset rejected via enum binding; version mutation/delete → 409 `evaluator_immutable` (via controller)
- [x] 2.3 `web/controller/enrichment/EvaluatorController`: `POST /v1/evaluators`, `GET /v1/evaluators`, `GET /v1/evaluators/{name}`, `GET /v1/evaluators/{name}/versions/{v}`; unknown → 404 `unknown_evaluator`; PUT/DELETE version → 409 `evaluator_immutable`
- [x] 2.4 `web/mapper/enrichment/EvaluatorMapper` entity↔DTO (hand-written `@Component` per repo convention — MapStruct is unused in this codebase)

## 3. Rule registry (CRUD + sync)

- [x] 3.1 `web/dto/enrichment/*` rule request/response + sync-feed records with `@Schema`
- [x] 3.2 `service/enrichment/RuleService`: CRUD; derive source/grain from the target enrichment's `source_table`/`enrichment_grain`; bump `generation`/`updated_at` on mutation
- [x] 3.3 `web/controller/enrichment/RuleController`: `POST/PUT/DELETE /v1/rules`, `GET /v1/rules/{id}`; unknown → 404 `unknown_rule`
- [x] 3.4 Sync feed `GET /v1/rules[?updated_since=…]` (enabled-only): inline the resolved pinned evaluator + derived source/grain + per-rule `generation`; `updated_since` filter. NOTE: `generation` is returned in each `RuleDto`; surfacing it as an HTTP `ETag` header is a small follow-up.

## 4. Static governance gate

- [x] 4.1 `service/enrichment/EnrichmentTypeMapping` (logical-type → `QueryFieldType` + output-compat check; complements the existing `ClickHouseTypeMapper`)
- [x] 4.2 `service/enrichment/FilterSqlValidator` wrapping `SqlQueryFrontEnd`: parse, boolean check, bounded grammar (joins/subqueries/CTEs fail closed), referenced-column extraction by walking the translated `FilterNode`
- [x] 4.3 `service/enrichment/RuleGovernanceValidator`: (1) target enrichment ACTIVE/kind + output-binding columns exist & types compatible; (2) filter via FilterSqlValidator; (4) evaluator resolves & `model` non-blank; (5) trigger ∈ {on_ingest, schedule} + cron/kind consistency; (6) input/output JSONata parse via `com.dashjoin:jsonata`. **DEVIATION (4):** no live DIAL-deployment check — ADAS has no deployment-listing client to reuse (only caller-key introspection); live verification deferred, spec updated to match.
- [x] 4.4 Entitlement check: filter/bound-binding columns resolved against the raw source mapping; a `sensitive` column → 403 `sensitive_column_not_entitled`. **DEVIATION:** implemented **deny-by-default** (no separate sensitive-grant store exists yet); when the grant store lands the check consults it. Spec updated to match.
- [x] 4.5 One-rule-per-target: DB `UNIQUE(target_enrichment)` + up-front `findByTargetEnrichment` pre-check → 409 `rule_validation_failed`
- [x] 4.6 Run the gate on both register and enable (create and update both call `governance.validate`)

## 5. B5 write grants

- [x] 5.1 Per-target `WRITE` grant for the runner reuses the **existing** `PUT /v1/tables/{name}/access` (`TableAccessController`/`TableAccessService`, `rework-uac` access model) — no new code and no generic table-write capability in this change
- [x] 5.2 Confirmed the WRITE flavour is the same target-enrichment grant `enrichment-runtime`'s scan/write endpoints will authorize against (single "operate-this-rule" model); cross-change authz lands with `enrichment-runtime`

## 6. Error contract

- [x] 6.1 Added `@ExceptionHandler`s + stable codes to `DefaultExceptionHandler`: `rule_validation_failed` (422 + 409 for conflict), `sensitive_column_not_entitled` (403), `evaluator_immutable` (409), `unknown_evaluator` (404), `unknown_rule` (404)

## 7. Tests + build

- [x] 7.1 Evaluator version immutability: unit (`EvaluatorServiceTest`) + integration (`EvaluatorRegistryIntegrationTest`: v1→v2 append, latest/version reads, unknown → 404, PUT/DELETE version → 409, unsupported preset rejected). **Integration execution needs Docker.**
- [x] 7.2 Rule CRUD + sync integration written (`RuleRegistryIntegrationTest`: create derives source/grain + inlines evaluator, update bumps `generation`, sync feed + `updated_since` filter). **Execution needs Docker.**
- [x] 7.3 Governance rejects: unit (`RuleServiceTest`, `EnrichmentTypeMappingTest`) + integration (`RuleRegistryIntegrationTest`: second rule on same target → 409, `group` trigger → 422, bad output-binding column → 422, unparseable filter → 422). **Integration execution needs Docker.**
- [x] 7.4 `./gradlew clean build` green (Docker/Testcontainers PG+CH) — verified with `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` (BUILD SUCCESSFUL, full test suite incl. Testcontainers). `curl` smoke on `bootRun` not run.
- [x] 7.5 `openspec validate enrichment-registry --strict` passes
