# Tasks: table-list-column-count

## 1. Service layer

- [x] 1.1 Add `ColumnMappingRepository.countByTable()` — JPQL aggregate returning per-table exposed-column counts (`tableName` → count)
- [x] 1.2 Add `TableSummary` record (`TableDefinitionEntity definition`, `int columnCount`) in `service/table/`
- [x] 1.3 Change `TableCatalogService.list()` to return `List<TableSummary>`: definitions ordered by name joined with the counts map, `0` when a table has no mapping rows

## 2. Web layer

- [x] 2.1 Add `@JsonProperty("column_count") Integer columnCount` to `TableDto` with a `@Schema` description ("Number of exposed columns (list only)"); keep `NON_NULL` so single-get omits it
- [x] 2.2 Update `TableMapper`: `toDto` passes `null` for `columnCount`; `toListDto`/`toListItemDto` accept `TableSummary` and populate the count

## 3. Tests

- [x] 3.1 Update/extend the table list test(s): list items carry `column_count` equal to the created column set size and no `columns` array; a column-less enrichment shell reports `column_count: 0`
- [x] 3.2 Assert single-get response has `columns` but no `column_count`
- [x] 3.3 Cover schema evolution: after PATCH add/drop, list `column_count` matches single-get `columns` length

## 4. Verify

- [x] 4.1 `./gradlew clean build` passes (compile + checkstyle + tests)
- [x] 4.2 Confirm delta spec `openspec/changes/table-list-column-count/specs/table-management/spec.md` matches shipped behavior
