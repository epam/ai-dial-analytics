# Proposal: table-list-column-count

## Why

`GET /v1/tables` deliberately omits the per-column detail (`columns` is single-get only), but that
leaves list consumers with no signal about a table's width at all — a UI listing the catalog cannot
show "12 columns" without issuing one `GET /v1/tables/{name}` per table. A lightweight `column_count`
on each list item fills that gap without re-introducing the heavy column payload.

## What Changes

- Each item returned by `GET /v1/tables` carries a new `column_count` field: the number of exposed
  columns of that table (the same set single-get returns as `columns` — system columns and the hidden
  grain key are not counted).
- The count is computed with one aggregate query over the column mappings (no N+1 per-table lookups).
- `GET /v1/tables/{name}` (single-get) is unchanged: it keeps returning the full `columns` array and
  does not add `column_count`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `table-management`: the "Read and list table definitions" requirement gains list-item
  `column_count` semantics (exposed-column count, consistent with single-get's `columns`).

## Impact

- `web/dto/table/TableDto.java` — new optional `column_count` field (list-only, `NON_NULL` keeps it
  off single-get responses).
- `service/table/TableCatalogService.java` + `ColumnMappingRepository.java` — list path aggregates
  exposed-column counts per table.
- `web/mapper/table/TableMapper.java` — list mapping carries the count.
- OpenAPI document changes shape only via annotations; no config, no migrations, no auth changes.
