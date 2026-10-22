## 1. Spike: DDL seam (gate)

- [x] 1.1 Prototype the per-call seam in `TableSchemaCreationService`: extract a `DdlExecutor` interface over `ClickHouseDdlExecutor`, add an internal `defineSchema` overload that takes it (covering `safeDrop` too), and keep the public method's behaviour unchanged
- [x] 1.2 Prove in a throwaway integration test that `create` + `defineSchema(no-op)` + `replaceAccess` + `PipelineService.create` inside one rollback-only transaction leaves no catalog row, no ClickHouse table and no audit revision
- [x] 1.3 If the seam is invasive or the services don't join the outer transaction, stop and report to the user before going on (fallback: planner-only validation with `armable` unknown)

## 2. Shared helpers (no behaviour change)

- [x] 2.1 Extract public static helpers for the reserved and internal name checks (`requireNameIsNotInternal`, `requireNameIsNotSchemaName`) and the `max-tables` check from `TableCatalogService`
- [x] 2.2 Expose the column and scan-metadata rule checks the planner needs from `TableColumnRules` as public static helpers (`ScanMetadataRules` needed none)
- [x] 2.3 Fix the stale "lenient request JSON" comments (`TableMapper:52-53, :127`, `PatchSchemaRequest:96`)

## 3. Bundle DTOs and mappers

- [x] 3.1 Add DTOs in `web/dto/catalog/` (`ExportRequest`, `ExportComponent`, `ExportPreview`, `CatalogBundle`, `BundleTable`, `BundlePipeline`, `ExternalTableRef`, `ImportOptions`, `ImportPreview`, `ImportResult`, `ImportEntry`): snake_case `@JsonProperty`, every helper field declared, `@Valid` cascaded through every nested record
- [x] 3.2 Add the inverse mappers `TableMapper.toCreateRequest` / `toDefineSchemaRequest`: stored columns with provenance, explicit `nullable`/`sensitive`/`heavy`, the stored write mode, and the per-type omitted fields (spec "Columns travel as stored")
- [x] 3.3 Add `PipelineMapper.toBundle` (the source projection minus runtime fields, `inputs` → `VarSpec`, `output_vars` kept) and `toRegistration(BundlePipeline)` (reuses `transformFieldsOf`/`toSpec`, keeps `requireKindFields`, forces `enabled=false`)
- [x] 3.4 Unit tests for the mapper round-trips: an enrichment with self-declared provenance, one with none (expect injected columns), and a legacy `output_vars` pipeline

## 4. Export

- [x] 4.1 `CatalogExportService`: read raw entities; filter system, non-ACTIVE (into `skipped`) and OTLP (`OtelTableNames`); external references with a column fingerprint
- [x] 4.2 Closure in both directions using `EnrichSpecValidator.referencedEnrichments`, `AggregateSpecValidator.referencedEnrichments` and `readSourceOf`, recording a `reason` per object
- [x] 4.3 Export authorization: per-table `TABLE_GET` (404 / silent omission), sensitive clearance (403 `sensitive_column_not_entitled`), `access` only with `TABLE_GRANT` plus `access_omitted`; every check skipped when security isn't enforced
- [x] 4.4 `catalog_export_invalid` (422) for an explicit OTLP, PENDING or FAILED selection; `not_found` / `unknown_pipeline` for a missing name
- [x] 4.5 Envelope: `format`, `format_version: 1`, `exported_at`, `source` (`RunnerContract.VERSION` and the current Flyway version)

## 5. Import planner and validation pass

- [x] 5.1 Two-stage parse: a lenient envelope check (`format` → 400, unsupported `format_version` → 422 `catalog_bundle_version_unsupported`), then a strict bind with the converter's mapper plus `Validator.validate` (400)
- [x] 5.2 `CatalogImportPlanner` (pure): apply `role_map`; check external references (exists, system, ACTIVE, fingerprint); check closure against bundle, external references and existing target objects; classify collisions by `resolution_policy`; check the `UNIQUE(target)` conflict; sort topologically with per-table grants; pre-check the totals for `max-tables` and `max-columns-per-table` (with provenance)
- [x] 5.3 `differs` / `metadata_only`: re-export `prev` with `CatalogExportService` and compare the serialized strings, excluding `enabled`, state and timestamps
- [x] 5.4 Compare external-reference grants with the target's (never write them), report differences under `env_specific`, and check system-table enrichments against the target's grants
- [x] 5.5 Re-used name guard from `pipeline_aud` delete revisions; `acknowledge_reused_names`
- [x] 5.6 Collect `env_specific` (model, `rate_rpm`, cron, absolute date literals, `ttl`, unmapped roles, differing system grants)
- [x] 5.7 `CatalogImportValidator`: run the real services in a rollback-only transaction with the no-op DDL seam, validating `SKIP` objects against the existing object; then run the arm-time validators plus `requireNotWidenedAgainst` to fill `armable`/`arm_problems`
- [x] 5.8 Programmatic privilege checks (`TABLE_MODIFY`, `REGISTRY_ADMIN`, `TABLE_GRANT`, `TABLE_SENSITIVE`), skipped when security isn't enforced
- [x] 5.9 Planner unit tests: ordering, policy classification, `differs`/`metadata_only`, fingerprint mismatch, missing dependency, an enrichment referenced only in a filter, the totals limits, `role_map`

## 6. Import apply

- [x] 6.1 `CatalogImportService.apply` (not `@Transactional`): run the validation pass (problem → 422 `catalog_import_invalid`, `FAIL_IF_EXISTS` collision → 409 `catalog_import_conflict`), then commit step by step in topological order, with pipelines always disabled
- [x] 6.2 Reverse-order compensation with the targeting-pipeline ownership check, producing the `rolled_back` / `rollback_failed` statuses
- [x] 6.3 Structured `catalog_import` log event (`import_id`, bundle hash, object list)

## 7. Web layer and config

- [x] 7.1 `CatalogTransferController`: export, export/preview, import/preview, import (multipart `file` + optional `options`, required `resolution_policy`, `OVERWRITE` → 422 `resolution_policy_unsupported`); `@RequiresPrivilege(REGISTRY_READ)` on export and `TABLE_CREATE` on import; OpenAPI summaries state which calls write
- [x] 7.2 Strict parsing of the `options` part (unknown field → 400)
- [x] 7.3 `DefaultExceptionHandler`: the new exceptions mapped to `catalog_import_invalid`, `catalog_import_conflict`, `catalog_bundle_version_unsupported`, `resolution_policy_unsupported` and `catalog_export_invalid`; `MaxUploadSizeExceededException` → 413 `payload_too_large`; `MissingServletRequestPartException` → 400
- [x] 7.4 Measure a full export of a populated catalog, then add `spring.servlet.multipart.max-file-size: ${CATALOG_IMPORT_MAX_FILE_SIZE:<measured>}` and `max-request-size: ${CATALOG_IMPORT_MAX_REQUEST_SIZE:<measured>}` to `application.yml` (bare lines)
- [x] 7.5 Add the two `docs/configuration.md` rows (one line each) in a new config-group table

## 8. Integration and failure tests

- [x] 8.1 Fresh promotion: build the source, enrichment, sensitive `dial_usage_log` enrichment, aggregate target, and enrich + aggregate pipelines; export; reset the user-table and pipeline rows plus their `*_aud` tables, keeping system tables and grants; preview (`armable=true`, nothing written); import (GETs match, pipelines disabled, system grants identical); arm each pipeline
- [x] 8.2 Second promotion: `FAIL_IF_EXISTS` → 409; `SKIP_IF_EXISTS` → all `SKIP`, `differs=false`, nothing written; one new pipeline is created alone; a changed required column → `differs=true` + `armable=false`
- [x] 8.3 `OVERWRITE` → 422 on both calls; missing `resolution_policy` → 400; OTLP landing tables excluded and an explicit OTLP selection → 422
- [x] 8.4 Failure tests: a forced DDL failure on the 2nd table rolls back the 1st, then a `SKIP_IF_EXISTS` re-run converges; re-used name with and without acknowledgement; export 403/404; import without `TABLE_MODIFY` → 403; a sensitive grant wider than its source → 422; differing system-table grants untouched
- [x] 8.5 Parsing tests: unknown nested field → 400; blank nested field → 400; newer `format_version` with unknown fields → 422 naming the version; oversized file → 413; missing `file` → 400; unknown `options` field → 400; preview with differing objects → 200 under both policies; security mode `none` works without claims
- [x] 8.6 `EndpointAuthorizationCoverageTest` passes for the four new mappings

## 9. Docs

- [x] 9.1 `docs/catalog-promotion.md`: the post-import runbook (env_specific review, enable order, preview then arm, aggregate rebuild cost, backfill and runner state not travelling)
- [x] 9.2 README section for the catalog transfer surface; add the capability to CLAUDE.md "What this is" and to the `openspec/config.yaml` `context:` surface list if one exists
- [x] 9.3 Confirm `openspec/changes/catalog-export-import/specs/catalog-export-import/spec.md` matches the shipped behaviour and update it if not

## 10. Verification

- [x] 10.1 Manual check: `bootRun` locally, export, import into a fresh local DB, follow the runbook, re-import and confirm nothing is created
- [x] 10.2 `./gradlew clean build` is green
