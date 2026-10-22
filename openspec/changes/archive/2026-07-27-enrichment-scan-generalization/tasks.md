## 1. Catalog scan metadata (S1)

- [x] 1.1 Confirm the next free Postgres migration number (expected `V12`) — `V12` free (V1–V11 present)
- [x] 1.2 Add `db/migration/postgres/V13__source_scan_metadata.sql`: two nullable columns
      `version_column` / `identity_column` on `table_definition`, seeded for `dial_usage_log` with
      `('_ingested_at', 'event_id')`
- [x] 1.3 Add the two fields to `TableDefinitionEntity`
- [x] 1.4 Resolve them in `ScanService` and delete the hardcoded `cursorColumns()` branch; an
      unconfigured source keeps its rejection with a catalog-driven message. Extracted into
      `support/ScanSourceMetadata` + `support/CursorColumns` so the materialize path can share it.
      **CORRECTION:** the plan called this a 422; the hardcoded branch threw `QueryValidationException`,
      which the error contract maps to **400 `bad_request`**. Status and code are unchanged from
      the first version; only the message changes. Specs corrected to match the code.

## 2. Bounded windows (S2)

- [x] 2.1 Add `from` / `to` (ISO-8601 instants) to `ScanRequest` with `@Schema` docs
- [x] 2.2 Add the `(from, to]` predicate in `ScanService.buildScan` alongside the keyset and
      safety-lag conditions; reject `from >= to` with HTTP 400. Bounds compare via
      `fromUnixTimestamp64Milli(...)` so they are `DateTime64(3)`, matching the column
- [x] 2.3 Pass the window through `RuleDataController` → `ScanService.scan`

## 3. Spec + docs

- [x] 3.1 Delta spec `specs/enrichment-runtime/spec.md` (ADDED catalog-configured cursor columns;
      MODIFIED scan endpoint, keyset semantics, safety-lag)
- [x] 3.2 No `docs/configuration.md` change (no new config properties)

## 4. Tests + build

- [x] 4.1 Integration: scan resolves version/identity from catalog metadata and `dial_usage_log`
      behaviour is unchanged after the hardcoded branch is deleted (the five pre-existing scan tests
      still pass unmodified)
- [x] 4.2 Integration: `sourceWithoutScanMetadataIsRejected` — a table-API-created source has no scan
      metadata, so the scan is 400 with "has no scan metadata configured"
- [x] 4.3 Integration: `boundedWindowReturnsOnlyItsRange`,
      `adjacentWindowsDoNotOverlapAtTheSharedBoundary`, `invertedWindowIsRejected`
- [x] 4.4 `./gradlew clean build` green (Docker/Testcontainers PG+CH) — verified via
      `EnrichmentScanIntegrationTest` (9/9); full build run at the end of the phase
- [x] 4.5 `openspec validate enrichment-scan-generalization --strict` passes
