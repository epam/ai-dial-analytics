## 1. The rule

- [x] 1.1 Extend `TableColumnRules.requireKeyable` to `requireKeyable(String field, String columnName,
      QueryFieldType type, boolean nullable)`: keep the existing `map` refusal, and add a refusal for
      `nullable == true` with the per-field message from design.md decision 3 (`sorting key` for
      `ordering_key`, `partition key` for `partition_by`), thrown as `TableValidationException`.
- [x] 1.2 Update the class javadoc so it states the nullability rule alongside the existing
      `map`/`array` non-nullability notes, naming ClickHouse's `allow_nullable_key`.

## 2. Call sites

- [x] 2.1 `CatalogSchemaWriter.persistSchema` (draft persist): pass `column.isNullable()` at the
      `ordering_key` and `partition_by` sites so the refusal happens before `columns.saveAll(...)` and
      before the definition's keys are written.
- [x] 2.2 `CatalogSchemaWriter` grain-key site: pass `false`, with a short comment that the value
      describes the *enrichment's* physical grain column (always non-nullable) and not the source
      column being looked up — design.md decision 2.
- [x] 2.3 `TableSchemaCreationService.sourceSpec`: pass `column.isNullable()` at the `ordering_key` and
      `partition_by` sites, so a definition persisted before this rule existed cannot re-materialize.
- [x] 2.4 `TableSchemaCreationService.enrichmentSpec` grain-key site: pass `false`, same comment as 2.2.

## 3. Tests

- [x] 3.1 `CatalogSchemaWriterTest`: add unit tests mirroring `replaceSourceDraftRejectsMapOrderingKey`
      / `...MapPartitionColumn` for a `nullable: true` column named in `ordering_key` and in
      `partition_by` — assert the message names the field and column, and
      `verify(columns, never()).saveAll(any())`.
- [x] 3.2 `CatalogSchemaWriterTest`: add a test that an enrichment whose `grain_key` names a
      `nullable: true` source column is **accepted** (pins design.md decision 2 against a future
      tightening).
- [x] 3.3 `TableManagementIntegrationTest`: add an end-to-end test mirroring
      `mapColumnCannotKeyTheTableAndNothingIsPersisted` — `POST /v1/tables/{name}/schema` with a
      nullable column in `ordering_key`, then one as `partition_by`; each 422 with
      `error = validation_error` and a message naming the column, and afterwards the table still
      `pending` with no columns persisted.
- [x] 3.4 `TableManagementIntegrationTest`: add a positive test that the same schema materializes
      (HTTP 200, `status=active`) once the key column is declared `nullable: false`, so the test proves the rule and
      not merely that the request failed.
- [x] 3.5 Add a test asserting the seeded built-in sources (`dial_usage_log`, `rate_analytics`) satisfy
      the rule — every `ordering_key` entry and the `partition_by` column that is present in
      `column_mapping` is non-nullable — as the safety net named in design.md Risks.

## 4. Spec and docs

- [x] 4.1 Confirm `openspec/changes/require-non-nullable-table-keys/specs/table-management/spec.md`
      matches the shipped messages and status codes (422, `validation_error`), and that
      `openspec validate require-non-nullable-table-keys` passes.
- [x] 4.2 No `docs/configuration.md` change (no config property added or renamed) and no README change
      — confirm explicitly rather than skipping silently.

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes (compile + checkstyle + tests).
