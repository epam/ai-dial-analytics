## 1. DTO and service input types

- [x] 1.1 In `web/dto/table/PatchSchemaRequest.java`, remove the `retag`, `setDisplayName`, `setSensitive`, `setDescription` fields and the records `RetagColumn`, `SetDisplayNameColumn`, `SetSensitiveColumn`, `SetDescriptionColumn`; add a `List<ColumnMetadataUpdate> update` field and a `ColumnMetadataUpdate(@NotBlank String name, String tag, @JsonProperty("display_name") String displayName, String description, Boolean sensitive)` record. Update the class Javadoc to describe three structural ALTER verbs + one `update` merge-patch list with absent/blank/set semantics.
- [x] 1.2 Add `record Change<T>(boolean present, T value)` (with `leave()` and `set(v)` factories) in the `service.table` package.
- [x] 1.3 In `service/table/PatchInput.java`, remove the four metadata list fields, their records, and their null-safe accessors; add `List<ColumnMetadataUpdate> update` with a null-safe accessor and a `ColumnMetadataUpdate(String name, Change<String> tag, Change<String> displayName, Change<String> description, Change<Boolean> sensitive)` record. Update the Javadoc (apply order `drop → rename → add → update`).

## 2. Mapping and orchestration

- [x] 2.1 In `web/mapper/table/TableMapper.toPatchInput`, delete the four `request.setX()` blocks; map `request.update()` to `PatchInput.ColumnMetadataUpdate`, building each `Change<>` as `present` iff the request field is non-null (carry the raw value; normalization stays in the service).
- [x] 2.2 In `service/table/TableSchemaService.patchSchema`, replace the four resolve loops with one loop over `patch.update()`: reject an unknown post-op column (422, reuse the existing message), reject a duplicate column within `update` (422), normalize each *present* string change via `TableColumnRules.normalize{Tag,DisplayName,Description}`, and build one `ResolvedMetadataUpdate(exposedName, Change<String> tag, Change<String> displayName, Change<String> description, Change<Boolean> sensitive)`.
- [x] 2.3 In `TableSchemaService.patchSchema`, replace the four apply loops with one call per resolved update to `writer.updateColumnMetadata(...)`, keeping metadata application after all DDL and the up-front validate-then-apply structure.

## 3. Writer consolidation

- [x] 3.1 In `service/table/CatalogSchemaWriter.java`, replace `retagColumn`, `setDisplayNameColumn`, `setSensitiveColumn`, `setDescriptionColumn` with one `@Transactional updateColumnMetadata(String tableName, String exposedName, ResolvedMetadataUpdate change)` that loads the column once, applies each present field, and saves once.
- [x] 3.2 Re-verify `renameColumn` still copies `description`, `displayName`, `tag`, and `sensitive` on the delete-then-insert (no change expected).

## 4. Spec, docs, and demo

- [x] 4.1 Update `openspec/specs/table-management/spec.md` — apply the delta: rewrite "Modify table schema via PATCH" and "Schema validation" to the `update` merge-patch contract (handled at archive time via the change's delta spec).
- [x] 4.2 Update `docs/column-tags.md` examples from `retag`/`set_*` to the `update` shape.
- [x] 4.3 Update the bundled tables demo page's (since removed) request examples to the `update` shape.

## 5. Tests

- [x] 5.1 In `service/table/CatalogSchemaWriterTest.java`, replace the four setter tests with `updateColumnMetadata` cases: set one field, set several at once, clear via blank/`Change.set(null)`, and leave absent fields untouched.
- [x] 5.2 In `integration/TableManagementIntegrationTest.java`, port PATCH bodies to the `update` shape and add cases: multi-field update in one entry, clear-via-`""`, absent-field-leaves-unchanged, `display_name` round-trip, unknown-column 422, duplicate-column-in-update 422, and metadata preserved across a rename.
- [x] 5.3 Update any `service/query` test (e.g. `CatalogQueryServiceTest`) that references the old op shape.

## 6. Verify

- [x] 6.1 `./gradlew clean build` passes (compile + checkstyle + tests).
- [x] 6.2 `openspec validate consolidate-column-metadata-patch --strict` passes.
- [x] 6.3 Run locally against a test table and exercise the end-to-end checks from the plan (set multiple fields, clear via blank, absent leaves unchanged, unknown/duplicate 422, structural ops still work).
