## 1. Guard the join's shared name (prerequisite for the rename)

- [x] 1.1 In `TableSchemaService`, resolve the `ACTIVE` enrichments of the patched table when it is a
      `SOURCE` (`TableDefinitionRepository.findBySourceTable` + `EnrichmentGrainRepository`), building a
      map of grain-key column → enrichment name; keep the existing enrichment-side `grainKey` lookup for
      the case where the patched table *is* the enrichment.
- [x] 1.2 Split `requireMutable` into `requireDroppable` / `requireRenameable` over a shared core, and call
      them from the `drop` and `rename` loops respectively (D2).
- [x] 1.3 Reject in the shared core a source column named as any `ACTIVE` enrichment's `grain_key`, with a
      422 naming the enrichment and why the name cannot be repaired on either side.
- [x] 1.4 Update the class javadoc of `TableSchemaService` — it currently enumerates the rejections and
      says the scan-metadata pair is frozen against both operations.

## 2. Allow the rename and repoint the catalog

- [x] 2.1 Move the `identity_column`/`version_column` rejection into `requireDroppable` only, replacing the
      "no endpoint repoints it" comment with the repoint that now exists.
- [x] 2.2 In `CatalogSchemaWriter.applyPatch`, beside the TTL-column line, set `identityColumn` /
      `versionColumn` to `rename.to()` when the renamed physical name matches, and extend the method's
      javadoc (which documents the TTL repoint) to cover the pair.
- [x] 2.3 Extend `CatalogSchemaWriterTest` with unit coverage: renaming the identity column repoints it;
      renaming the version column repoints it; renaming an unrelated column leaves both alone; a rename
      that hits both members at once (a table declaring the same column twice is impossible, so assert the
      two independent cases plus a rename of the TTL column in the same request).
- [x] 2.4 Extend `TableSchemaServiceTest` for the drop/rename asymmetry and the grain-key rejection with a
      mocked catalog.

## 3. Close the `sensitive` hole on `update`

- [x] 3.1 In `TableSchemaService`'s `update` resolution loop, reject `sensitive: true` on the column named
      by `identity_column`/`version_column`, resolved through the request's own `rename`s so the check
      judges the post-op state (D4). Message: name the column, the field, and that the scan projects both
      cursor columns.
- [x] 3.2 Confirm `sensitive: false` and every other attribute on those columns still pass, and that
      system/grain/ordering-key columns keep their unrestricted `update`.
- [x] 3.3 Unit-test both directions in `TableSchemaServiceTest`.

## 4. Bump rule generation on a version-column repoint

- [x] 4.1 Add `RuleGenerationBump` (single method, `service/table`) with javadoc stating why the port exists
      (transaction sharing without inverting the `service.enrichment` → `service.table` dependency).
- [x] 4.2 Implement it in `service/enrichment` (a `@Component` beside `RuleService`): resolve the source's
      `ACTIVE` enrichments, load their rules, increment `generation` and set `updated_at`; add
      `RuleRepository.findByTargetEnrichmentIn`.
- [x] 4.3 Inject the port into `CatalogSchemaWriter` and invoke it from `applyPatch` when — and only when —
      the version column was repointed by this request.
- [x] 4.4 Document in the implementation's javadoc why `updated_at` moves too (the sync feed selects on it,
      so a bumped generation alone would never reach an incremental sync).

## 5. Integration coverage

- [x] 5.1 `TableManagementIntegrationTest`: rename the identity column of a source with no enrichment →
      200, `GET /v1/tables/{name}` shows the renamed `identity_column`; same for `version_column`; `drop`
      of either → 422; `update` with `sensitive: true` on either → 422.
- [x] 5.2 `TableManagementIntegrationTest` (or `EnrichmentJoinIntegrationTest`, wherever the source +
      enrichment fixture already exists): a `drop` and a `rename` of a source column an `ACTIVE`
      enrichment is keyed on → 422 naming the enrichment; a query referencing that enrichment still
      succeeds afterwards.
- [x] 5.3 `EnrichmentScanIntegrationTest`: page a rule's scan, rename the source's version column, then
      resume with the cursor issued before the rename — the scan pages on the new name and neither skips
      nor repeats a row; assert the page's key follows the rename.
- [x] 5.4 `RuleRegistryIntegrationTest`: renaming the source's version column bumps the affected rule's
      `generation`, advances `updated_at`, and the rule read + the sync feed at the pre-rename watermark
      both carry the new `version_column`; a rule on a different source is untouched; renaming a
      non-scan-metadata column bumps nothing.
- [x] 5.5 Check whether `IntegrationTables` needs a helper for "source with an enrichment keyed on a named
      column" — add one only if a fixture is duplicated, and do not let it default a scan-metadata pair
      (`harden-scan-metadata` D8).

## 6. Specs and docs

- [x] 6.1 Update `openspec/project.md`: the scan-metadata paragraph says the named columns are frozen
      against "a drop or a rename" — it becomes drop-only, plus the rename repoint, the `update`
      restriction, and the grain-key immutability.
- [x] 6.2 Re-read the three delta specs against the shipped behaviour, then `openspec validate
      repoint-scan-metadata-rename` and sync into `openspec/specs/` at archive time.
- [x] 6.3 Check `docs/heavy-columns.md` for any statement about scan-metadata column immutability and
      correct it if present.

## 7. Verify

- [x] 7.1 `./gradlew clean build` is green
      (checkstyle included).
- [x] 7.2 Smoke-test the loop against a live instance: create a source with a pair, materialize, rename the
      version column, confirm the rule read shows the new name and a higher `generation`, and scan across
      the rename with a pre-rename cursor.
      **Run 2026-08-04 against the local stack (Postgres + ClickHouse 25.3.14.14), 27/27 checks green.**
      Script kept at `scratchpad/smoke.sh`. Fixture note worth keeping: put `ordering_key` on a column that
      is neither member of the pair nor the enrichment's grain key, or the ordering-key guard fires first and
      the new rules are never actually exercised (it masked the grain-key rejection on the first run).
