# Tasks: array-column-support

## 1. Validation & type mapping

- [x] 1.1 `TableColumnRules.requireValidUserColumn`: replace the blanket ARRAY/elementType rejection
      with the new rules — `type=array` requires a scalar `element_type` ∈ {string, integer, long,
      decimal, boolean, date, timestamp, uuid}; `element_type` on a non-array column → 422; nested
      `array` or `object` element → 422; explicit `nullable: true` on an array column → 422 (message
      should point at `[]` as the natural absent value). Update the class Javadoc (no more
      "postponement" language).
- [x] 1.2 `TableColumnRules.resolveNullable`: an array column resolves to `false` even when the
      table is an enrichment (omitted nullable).
- [x] 1.3 `ClickHouseTypeMapper.scalarType`: reject `OBJECT` as an array element type so DDL
      rendering agrees with validation; refresh the class Javadoc ("no longer user-declarable" is
      stale).

## 2. Wire DTOs & mapping

- [x] 2.1 `CreateTableRequest.ColumnRequest`: add optional `element_type` (`QueryFieldType`,
      `@JsonProperty("element_type")`), update the `@Schema` docs on `type`/`element_type` (drop
      "array is not user-declarable"). PATCH `add` reuses this record — no separate change.
- [x] 2.2 `TableMapper.toColumnInput`: thread `element_type` through instead of hardcoding null.
- [x] 2.3 `TableDto.ColumnDto`: add `element_type` (null/absent for non-array columns), populate in
      `TableMapper.toDto` from `ColumnMappingEntity`.
- [x] 2.4 `QuerySchemaFieldDto`: add `element_type`; populate in `CatalogQueryService.addColumn`
      from the column mapping.

## 3. Tests

- [x] 3.1 Unit — `TableColumnRules`/`CatalogSchemaWriterTest`: replace
      `rejectsUserDeclaredArrayColumn` with acceptance of `array`+`element_type`; add rejection
      tests: array without element_type, element_type on non-array, element_type=array/object,
      nullable:true array; enrichment array resolves non-nullable.
- [x] 3.2 Unit — `ClickHouseTypeMapperTest`: add `objectElementIsRejected`; existing array mapping
      tests stay green.
- [x] 3.3 Integration — `TableManagementIntegrationTest`: replace
      `userDeclaredArrayTypeIsRejected` with: create a source with an `Array(String)` column (201,
      element_type echoed on GET), PATCH-add an array column, write rows with JSON array values and
      read them back via a structured row query, plus the 422 shapes (missing element_type,
      nullable:true).
- [x] 3.4 Integration — `QuerySchemaDiscoveryIntegrationTest`: assert `execution_path` carries
      `element_type: "string"` and a scalar field carries none.

## 4. Specs & docs

- [x] 4.1 Delta specs in this change match shipped behavior (table-management,
      query-schema-discovery) — re-verify after implementation.
- [x] 4.2 Check `docs/` for statements that arrays are not user-declarable (e.g. column docs) and
      update if present; no `docs/configuration.md` change expected (no new config).

## 5. Verify

- [x] 5.1 `./gradlew clean build` passes (compile, checkstyle, tests).
