## 1. Service layer

- [x] 1.1 Add `MAX_DESCRIPTION_LENGTH = 1024` and a `normalizeDescription(...)` helper to `TableColumnRules` (trim, blank → absent, reject > 1024 chars with 422), mirroring `normalizeDisplayName`/`MAX_DISPLAY_NAME_LENGTH`
- [x] 1.2 Call `normalizeDescription` from `TableColumnRules.requireValidUserColumn` (alongside `normalizeDisplayName`/`normalizeTag`) so create/`add` validate description length
- [x] 1.3 In `CatalogSchemaWriter.columnEntity(...)`, set `description` from the input normalized via `normalizeDescription` (previously stored raw)
- [x] 1.4 Add a `setDescriptionColumn(...)` method to `CatalogSchemaWriter` (catalog-only, no ClickHouse `ALTER`), mirroring `setDisplayNameColumn(...)`
- [x] 1.5 Add a `SetDescription(name, description)` case to `PatchInput` (record + null-safe accessor + constructor arg) and wire it into `TableSchemaService` PATCH handling in the operation order `drop → rename → add → retag → set_display_name → set_sensitive → set_description`, normalizing and rejecting a set_description of an unknown column with 422

## 2. Web DTOs

- [x] 2.1 Add a nested `SetDescriptionColumn(String name, String description)` record (+ `@Schema` docs) and a `@JsonProperty("set_description") List<SetDescriptionColumn> setDescription` field to `PatchSchemaRequest`
- [x] 2.2 Thread `setDescription` through `TableMapper.toPatchInput` and the `PatchInput` constructor call

## 3. Tests

- [x] 3.1 `CatalogSchemaWriterTest`: `setDescriptionColumn` sets/clears with no ClickHouse `ALTER`; unknown column rejected
- [x] 3.2 `TableManagementIntegrationTest`: create returns `description`; `set_description` sets it, is preserved across `rename`, and blank clears it; discovery field carries it; set_description of unknown column → 422; description > 1024 chars → 422

## 4. Specs & docs

- [x] 4.1 Update the `table-management` delta spec: add `set_description` to the "Modify table schema via PATCH" requirement (op list, payload, ordering, preserved-across-rename, unknown → 422, no mutability restriction) with set/clear/rename and unknown-column scenarios; add the description length cap clause + scenario to "Schema validation"
- [x] 4.2 Note the column `description` field and the `set_description` op in `docs/column-tags.md` (no new env config)

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes (compile + checkstyle + tests)
- [x] 5.2 `openspec validate add-column-description-modification` passes
