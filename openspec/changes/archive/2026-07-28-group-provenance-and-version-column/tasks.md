## 1. `version_column` on the rule DTO

- [x] 1.1 `model/ResolvedRule` gains `versionColumn`, documented as derived from the source's catalog
      scan metadata and null when the source declares none
- [x] 1.2 `RuleService.resolve`: resolve the source once, then `versionColumnOf(source)` — a tolerant
      lookup returning null for an unknown or non-scannable source (blank trimmed to null)
- [x] 1.3 `RuleService.sync`: one batched `findAllById` over the distinct source tables of the fetched
      target definitions, so the feed's constant query count is preserved
- [x] 1.4 `resolveForScan` / `resolveForWrite` pass null — the scan resolves cursor columns through
      `ScanSourceMetadata` (where a missing declaration is a 422), and the write reads neither
- [x] 1.5 `RuleDto` gains `version_column` (`@JsonInclude(NON_NULL)` omits it when absent); MapStruct
      maps it by name off `ResolvedRule`, so create/update/get and the sync feed all carry it

## 2. Group provenance columns

- [x] 2.1 `ProvenanceColumns` gains `GROUP_VERSION` (`group_version`) and `TRUNCATED` (`truncated`),
      both in `NAMES` — which the write-back allow-list (`RuleWriteValidator`) and the
      absent-column skip (`RuleWriteService.dropAbsentProvenance`) already read from that one set, so
      both paths pick them up with no further change
- [x] 2.2 Class doc records why the names are un-prefixed: `_` is reserved for system columns, which are
      not caller-settable, and a user-declared enrichment column may not start with `_`
- [x] 2.3 `MaterializeService`: `NOT_APPLICABLE_TO_SQL = [model, group_version, truncated]`, skipped with
      one debug log each, replacing the single `model` branch

## 3. Tests

- [x] 3.1 `RuleWriteValidatorTest`: the group pair is accepted as permitted provenance
- [x] 3.2 `GroupEnrichmentIntegrationTest`: `version_column` is `_ingested_at` on rule create and on the
      sync feed (the fixture's source is `dial_usage_log`, whose scan metadata V13 seeds)
- [x] 3.3 `GroupEnrichmentIntegrationTest`: the group pair round-trips — declared on the target, written
      through `POST /v1/rules/{id}/rows`, queryable through the grain join; and skipped, not rejected,
      when the target declares neither
- [x] 3.4 `RuleRegistryIntegrationTest`: an API-created source declares no scan metadata, so
      `version_column` is absent and the rule read still succeeds
- [x] 3.5 `RuleServiceTest`: the sync feed tolerates a rule whose target definition is missing, so its
      source (and therefore its version column) resolves to null — the lookup is null-guarded rather
      than relying on the map implementation's null-key behaviour

## 4. Specs and verification

- [x] 4.1 Delta specs: `enrichment-registry` (rule CRUD + sync feed carry `version_column`),
      `enrichment-runtime` (permitted provenance set), `class1-enrichment` (materialize skips both)
- [x] 4.2 `./gradlew clean build` green (checkstyle + unit + integration, Docker required)
