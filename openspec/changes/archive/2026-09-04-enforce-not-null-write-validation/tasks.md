## 1. Implementation

- [x] 1.1 In `TableDataService.writeColumns()`, add a `nonNullable` `Set<String>` to the `WriteColumns`
      record, populated from `!col.isNullable()` in the existing per-column loop, plus the grain key
      (when present) added unconditionally.
- [x] 1.2 In `TableDataService.write()`, after the existing unknown-column check and before
      `normalizeRow`, reject a row with `TableValidationException` (naming the row's 1-based index and
      the column) when any column in `writeColumns.nonNullable()` is absent from the row or maps to a
      JSON `null`.
- [x] 1.3 Update the class Javadoc on `write()` (and the choke-point Javadoc above the class) to mention
      the non-nullable check alongside the existing unknown-column/timestamp description.

## 2. Tests

- [x] 2.1 In `TableManagementIntegrationTest`, add cases: a row omitting a non-nullable column is
      rejected 422 `validation_error`; a row explicitly writing `null` for a non-nullable column is
      rejected 422 `validation_error`; a row omitting/nulling a *nullable* column still succeeds
      (unchanged behavior).
- [x] 2.2 In `TableManagementIntegrationTest` (enrichment table setup), add a case that omits or nulls
      an enrichment's grain key and asserts 422 `validation_error` naming the grain-key column, even
      when the source declares that column nullable.
- [x] 2.3 In `EnrichmentWriteBackIntegrationTest`, add a case confirming the rule-scoped write-back
      (`POST /v1/rules/{id}/rows`) rejects a row missing a non-nullable target column, proving the
      shared choke point covers it.

## 3. Verification

- [x] 3.1 Run `./gradlew clean build` and confirm it passes.
