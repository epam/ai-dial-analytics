## 1. Persistence

- [x] 1.1 Add Postgres Flyway migration `src/main/resources/db/migration/postgres/V8__add_column_display_name.sql` with `ALTER TABLE column_mapping ADD COLUMN display_name TEXT;` (nullable, no default; model after `V3__add_sensitive_columns.sql`)
- [x] 1.2 Add `private String displayName;` (with getter/setter, `@Column(name = "display_name")`) to `ColumnMappingEntity`

## 2. Service layer

- [x] 2.1 Add `String displayName` to the `ColumnInput` record
- [x] 2.2 Add `MAX_DISPLAY_NAME_LENGTH = 128` and a `normalizeDisplayName(...)` helper to `TableColumnRules` (blank → absent, reject > 128 chars with 422), mirroring `MAX_TAG_LENGTH`/`normalizeTag`
- [x] 2.3 In `CatalogSchemaWriter.columnEntity(...)`, set `displayName` from the input (normalized)
- [x] 2.4 In `CatalogSchemaWriter.renameColumn(...)`, copy `displayName` onto the renamed mapping (alongside `description`/`tag`/`sensitive`)
- [x] 2.5 Add a `setDisplayNameColumn(...)` method to `CatalogSchemaWriter` (catalog-only, no ClickHouse `ALTER`), mirroring `setSensitiveColumn(...)`
- [x] 2.6 Add a `SetDisplayName(name, displayName)` case to `PatchInput` and wire it into `TableSchemaService` PATCH handling in the operation order `drop → rename → add → retag → set_display_name → set_sensitive`, rejecting a set_display_name of an unknown column with 422
- [x] 2.7 Apply `normalizeDisplayName` validation on the create path and on `PATCH add`/`set_display_name` inputs

## 3. Web DTOs

- [x] 3.1 Add `@JsonProperty("display_name") String displayName` (+ `@Schema` doc) to `CreateTableRequest.ColumnRequest`
- [x] 3.2 Add a nested `SetDisplayNameColumn(String name, String displayName)` record to `PatchSchemaRequest` (`set_display_name` op) and include it in the parsed operation set
- [x] 3.3 Add `@JsonProperty("display_name") String displayName` to `TableDto.ColumnDto`
- [x] 3.4 Add `@JsonProperty("display_name") String displayName` (+ `@Schema` doc) to `QuerySchemaFieldDto`
- [x] 3.5 Thread `displayName` through `TableMapper` (`toColumnInput` and `toDto`) and the `set_display_name` op mapping

## 4. Query schema discovery

- [x] 4.1 In `CatalogQueryService.addColumn(...)`, pass `col.getDisplayName()` into the constructed `QuerySchemaFieldDto` (present for admin/none, absent for hidden sensitive fields as today)

## 5. Tests

- [x] 5.1 `CatalogSchemaWriterTest`: display name set at create, preserved across rename, set/cleared via set_display_name with no ClickHouse `ALTER`
- [x] 5.2 `TableManagementIntegrationTest`: create returns display_name; `PATCH add`/`set_display_name` behavior; set_display_name of unknown column → 422; display_name > 128 chars → 422; blank display_name treated as absent; duplicate display names accepted
- [x] 5.3 `QuerySchemaControllerTest` / `QuerySchemaDiscoveryIntegrationTest`: schema-discovery field carries `display_name` (null when unset); sensitive hidden fields carry no display name
- [x] 5.4 Update `DialUsageLogTestModel` and any other fixtures/assertions broken by the new DTO field

## 6. Specs & docs

- [x] 6.1 Verify the shipped behavior matches the delta specs for `table-management` and `query-schema-discovery` (update the change's delta specs if implementation reveals a discrepancy)
- [x] 6.2 Note the new column `display_name` field in the table-management API docs / `docs/column-tags.md` neighbor (no new env config)

## 7. Verification

- [x] 7.1 `./gradlew clean build` passes (compile + checkstyle + tests)
- [x] 7.2 `openspec validate add-label-field` passes
