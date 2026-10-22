## 1. B0 — Grain readiness (verify-before-build)

- [x] 1.1 Run the uniqueness gate against a representative ClickHouse: `SELECT count() - uniqExact(event_id) FROM dial_usage_log FINAL` must be `0` — **verified = 0** on ClickHouse 25.3 (real V1 DDL, 500k spans + 1k re-ingested duplicates; FINAL collapsed 501k→500k, `distinct_event_ids = rows`). `event_id` confirmed a sound unique grain key.
- [x] 1.2 Happy path: no code/migration for the grain — the scan uses the existing `event_id` as the cursor identity and the enrichment grain key
- [x] 1.3 Fallback (only if 1.1 fails): materialized `grain_id` + `column_mapping` migration + renumber — **N/A: 1.1 passed (gate = 0)**, happy path confirmed, no fallback needed

## 2. B4 — Scan index (ClickHouse migration)

- [x] 2.1 Confirmed next free ClickHouse migration is `V3` (V1/V2 present; run by `ClickHouseMigrationRunner` via Flyway)
- [x] 2.2 Added `db/migration/clickhouse/V3__dial_usage_log_scan_index.sql` — minmax skip index on `_ingested_at`
- [x] 2.3 `MATERIALIZE INDEX` for existing parts included, with the off-peak cost note
- [x] 2.4 Verify a keyset range scan prunes granules (`EXPLAIN indexes=1` / query log) — **verified** on ClickHouse 25.3 after V3 `MATERIALIZE INDEX` on existing parts: `EXPLAIN indexes=1` on `WHERE _ingested_at > cursor ORDER BY _ingested_at, event_id LIMIT n` shows `Skip idx_ingested_at` selecting **6/63 granules**; query_log confirms 34,056 read_rows vs 501,000 with `ignore_data_skipping_indices='idx_ingested_at'`.

## 3. B3b — Versioned `_updated_at` (DDL default)

- [x] 3.1 `TableDdlRenderer` renders enrichment `_updated_at` as `DateTime64(3) DEFAULT now64(3)` for new tables
- [x] 3.2 Added `db/migration/clickhouse/V4__enrichment_updated_at_default.sql` — no-op (no pre-existing enrichment tables); documents the `ALTER … MODIFY COLUMN` to add for any that exist
- [x] 3.3 System (`_`-prefixed) columns are caller-unsettable: the generic write path already rejects non-physical columns (`_updated_at` is not physical), and `RuleWriteValidator` rejects `_`-prefixed columns explicitly (422 `write_column_not_allowed`); omitted from JSONEachRow so ClickHouse stamps the default
- [x] 3.4 `openspec/specs/table-management/spec.md` delta matches shipped behavior

## 4. B2 — Cursor codec

- [x] 4.1 `service/enrichment/CursorCodec`: `base64url(payload).base64url(hmac)`, payload = `formatVersion ∥ keyId ∥ entity ∥ tuple…`, HMAC-SHA256 (placed under `service/enrichment`, alongside the rule domain)
- [x] 4.2 HMAC **keyring** (`CursorProperties`) keyed by `key-id`; active key signs, all keys verify (dual-key rotation); unknown/retired key → decode failure
- [x] 4.3 Decode verifies HMAC + entity match; malformed/tampered/cross-entity/unknown-key → `InvalidCursorException` → 400 `invalid_cursor`
- [x] 4.4 Unit tests (`CursorCodecTest`, 6): round-trip, tamper, cross-entity, key rotation keeps prior cursors valid, retired key rejected, malformed rejected

## 5. B2 — Keyset scan engine (query pipeline)

- [x] 5.1 Scan-mode query: source-only model (empty enrichment joins), projection pushdown, executed via new `StructuredQueryExecutor.executeScan` under `final=1`. **DEVIATION:** built as a dedicated `ScanService` path (+ `executeScan`) reusing `FilterTranslator`/render context, rather than extending `StructuredQueryBuilder.applyPage`'s `CursorPage` branch — smaller blast radius on the general pipeline.
- [x] 5.2 Keyset predicate as a tuple-compare on projected `toUnixTimestamp64Milli(_ingested_at)`/`toString(event_id)` (type-robust) combined with the rule filter; `ORDER BY` the cursor tuple asc; `LIMIT`
- [x] 5.3 Safety-lag predicate `_ingested_at <= now64(3) − lag` (configurable, default 5s)
- [x] 5.4 New `ScanPage` result type carries `nextCursor`/`hasMore`/`lagSeconds` (`= server_now − max(version)`); **DEVIATION:** a dedicated `ScanPage` rather than adding fields to `QueryResultPage`/`CursorPage`
- [x] 5.5 `POST /v1/queries/execute` still rejects cursor pagination (its `CursorPage` branch is untouched — general endpoint stays deferred)
- [x] 5.6 `openspec/specs/structured-query/spec.md` delta matches shipped behavior

## 6. B2/B3 — Rule-scoped controller + DTOs

- [x] 6.1 `web/controller/enrichment/RuleDataController` hosts `POST /v1/rules/{id}/scan` and `POST /v1/rules/{id}/rows`
- [x] 6.2 `web/dto/enrichment/{ScanRequest,ScanResponse}` with `@Schema`; write reuses `web/dto/table/{WriteRowsRequest,WriteRowsResponse}`
- [x] 6.3 `ScanService`/`RuleWriteService` resolve the rule's source/filter/grain via the registry's `rule`/`enrichment_grain` records (`RuleService.get`); unknown rule → 404 `unknown_rule`
- [x] 6.4 Scan requires the rule to exist but not be `enabled`; empty page returns the input cursor unchanged with `hasMore=false`
- [x] 6.5 `service/enrichment/RuleWriteValidator`: row column-set ⊆ {rule outputs} ∪ {grain key} ∪ {provenance}; else 422 `write_column_not_allowed`; `RuleWriteService` then delegates to `TableDataService.write`

## 7. B5 (scan authz) — target-enrichment authorization

- [x] 7.1 `RuleAuthorizer` authorizes both `/scan` and `/rows` by the rule's target-enrichment **WRITE** grant (single "operate-this-rule" model — there is no separate read-list in the access model, so the WRITE grant is the target-enrichment access); `FULL_ADMIN` satisfies it. Enforced programmatically with a `none`-mode bypass (mirrors inert method security). **Also relaxed the `OidcSecurityConfiguration` URL matcher** so `POST /v1/rules/*/scan|rows` require only `.authenticated()` (like `/v1/tables/*/rows`) — without this the endpoints were `FULL_ADMIN`/`READ_ONLY_ADMIN`-gated at the URL layer, defeating B5 least-privilege (the runner would need global read admin).
- [x] 7.2 Missing target access → 403 `rule_access_denied`

## 8. Error contract + config + docs

- [x] 8.1 Added `@ExceptionHandler`s + stable codes to `DefaultExceptionHandler`: `invalid_cursor` (400), `write_column_not_allowed` (422), `rule_access_denied` (403); `unknown_rule` (404) reused from the registry
- [x] 8.2 Config for scan safety-lag (`ScanProperties`, default 5s) and the cursor HMAC keyring (`CursorProperties`), env-driven in `application.yml`
- [x] 8.3 Documented in `docs/configuration.md` (scan + cursor-keyring tables); no descriptions added to `application.yml`/README

## 9. Tests + build

- [x] 9.1 B2 keyset exactly-once across pages + empty-page-echoes-cursor — integration test written (`EnrichmentScanIntegrationTest.keysetPagingReturnsEachRowExactlyOnce`). **Compiles + checkstyle; execution needs Docker.**
- [x] 9.2 Cursor unit coverage (`CursorCodecTest`): `nextCursor` round-trips, tampered/cross-entity → 400, key rotation keeps prior cursors valid. Tampered-cursor-at-endpoint also in `EnrichmentScanIntegrationTest`.
- [x] 9.3 B2 integration written (`EnrichmentScanIntegrationTest`: scan omits enrichment-join columns, projection, disabled-but-existing rule scans, unknown rule → 404; `RuleDataAuthorizationIntegrationTest`: missing target access → 403, per-target grant allows a non-admin runner, FULL_ADMIN allowed). **Execution needs Docker.**
- [x] 9.4 B3: `RuleWriteValidatorTest` (unit, column-set + system-column) + `EnrichmentWriteBackIntegrationTest` (write lands + grain-collapse-to-latest under `final=1` + undeclared/system column → 422). **Integration execution needs Docker.**
- [x] 9.5 `./gradlew clean build` green (Docker/Testcontainers CH+PG) — verified with `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew clean build` (BUILD SUCCESSFUL, full test suite incl. Testcontainers). `curl` smoke on `bootRun` not run.
- [x] 9.6 `openspec validate enrichment-runtime --strict` passes
