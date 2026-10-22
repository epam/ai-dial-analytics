# Design: table-list-column-count

## Context

`GET /v1/tables` maps `TableCatalogService.list()` (a `findAll` over `table_definition`) through
`TableMapper.toListItemDto`, which builds a lean `TableDto` with `columns = null` (`NON_NULL`
serialization drops it). The exposed columns live in `column_mapping` rows (one per exposed column,
system columns and the hidden grain key are never stored there), fetched today only on the single-get
path via `ColumnMappingRepository.findByTableNameOrderByOrdinal`.

## Goals / Non-Goals

**Goals:**
- Each list item carries `column_count` = number of exposed columns, always equal to the length of
  the `columns` array single-get would return for that table.
- One extra aggregate query for the whole list — no per-table lookups (no N+1).

**Non-Goals:**
- No `column_count` on `GET /v1/tables/{name}` (clients have the `columns` array there).
- No pagination, filtering, or other list-shape changes.

## Decisions

1. **Count source = `column_mapping` GROUP BY.** Add
   `ColumnMappingRepository.countByTable()` — a JPQL aggregate
   (`select tableName, count(*) ... group by tableName`) returning per-table counts. This is the
   same store the single-get `columns` array is built from, so the two can never disagree.
   *Alternative rejected*: loading all mappings and counting in memory — wasteful (drags full column
   rows) for no benefit.

2. **Service returns a list-item aggregate.** `TableCatalogService.list()` changes to return
   `List<TableSummary>` where `TableSummary(TableDefinitionEntity definition, int columnCount)`
   (a small record next to `TableView`). Tables with no `column_mapping` rows (fresh enrichment
   shells) simply have no GROUP BY row → count `0`.
   *Alternative rejected*: returning a side `Map<String, Long>` from the controller — spreads the
   join logic into the web layer; the service is the natural owner of catalog aggregation.

3. **DTO field on `TableDto`, list-populated only.** Add
   `@JsonProperty("column_count") Integer columnCount` to `TableDto`; `toDto` (single-get) passes
   `null` so `NON_NULL` keeps it off that response, mirroring how `columns` is absent on the list.
   *Alternative rejected*: a separate list-item DTO record — duplicates ~8 fields for one extra
   field; the existing codebase already reuses `TableDto` for both shapes.

## Risks / Trade-offs

- [Two queries on the list path (definitions + counts) instead of one] → both are tiny catalog
  tables (max-tables limit applies); acceptable and still O(1) queries.
- [`Integer` (nullable) on a count field could leak `null` on the list if a mapping bug passes
  null] → mapper always passes a concrete count on the list path; test asserts `column_count`
  present (and `0` for a column-less shell).
