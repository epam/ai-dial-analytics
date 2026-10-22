# Design: array-column-support

## Context

The abstract type vocabulary (`QueryFieldType`) has always included `ARRAY`, and the service-layer
`ColumnInput` record carries an `elementType` — but only the Flyway-backfilled
`dial_usage_log.execution_path` row (`array` of `string`) exercises it. The API validator
(`TableColumnRules.requireValidUserColumn`) rejects `ARRAY` and any non-null `elementType`, and
`TableMapper.toColumnInput` hardcodes `elementType = null` because no wire DTO carries it.
Downstream, `ClickHouseTypeMapper.toDdlType` already renders `Array(<scalar>)` and refuses nested
arrays; the row-write path (`CatalogRowWriter`) serializes rows as `JSONEachRow`, so JSON array
values already insert correctly; structured-query row mode already projects array columns as
opaque values; the SQL/Calcite surface excludes `object`/`array` fields entirely.

## Goals / Non-Goals

**Goals:**

- Let users declare `array` columns (with a scalar `element_type`) at source create and via
  `PATCH add`, on both sources and enrichments.
- Surface `element_type` on the table read surface and on schema discovery.
- Keep the catalog truthful: never store a nullability the DDL cannot honor.

**Non-Goals:**

- No array operators in the structured-query DSL (`contains`/`has`/element access) — arrays remain
  projectable-but-opaque (`JooqTypes` maps them to `OTHER`).
- No SQL-surface visibility: `sql-query-translation` keeps excluding `object`/`array` fields, and a
  bare `SELECT *` over an array-bearing entity keeps failing closed on the SQL path.
- No Arrow/Flight result support for arrays (`ResultColumnKind`/`ArrowTypeMapper` unchanged).
- No nested arrays (`Array(Array(...))`) and no `Array(JSON)`.
- No type-change of existing columns (unchanged PATCH rule).

## Decisions

### D1: `element_type` is a required companion field, scalar-only

`ColumnRequest` gains `element_type` (JSON: `element_type`, type `QueryFieldType`). It is required
iff `type=array`, forbidden otherwise (422 both ways — matching `ClickHouseTypeMapper`'s existing
contract, but enforced up front in `TableColumnRules` so the error is a 422 `TableValidationException`,
not a late DDL failure). Allowed element types: `string`, `integer`, `long`, `decimal`, `boolean`,
`date`, `timestamp`, `uuid`. `array` elements stay rejected (nested arrays), and `object` elements
are rejected too: `ClickHouseTypeMapper.scalarType` would happily render `Array(JSON)`, but JSON
inside arrays has different query semantics and no discovery story — excluded until needed. The
mapper's `scalarType` gains the `OBJECT` rejection so DDL rendering and validation agree.

*Alternative considered*: a composite type string (`"array<string>"`). Rejected — the catalog,
`ColumnInput`, and the data-sync mapping vocabulary already model type + element type as two
fields; a separate wire field is the straight-through mapping.

### D2: array columns are always non-nullable; explicit `nullable: true` is a 422

ClickHouse forbids `Nullable(Array(...))` — verified against the current ClickHouse documentation
(Nullable's `T` "can't be … Array — Not supported"; only the inverse nesting `Array(Nullable(T))`,
i.e. nullable *elements*, is supported). Today `resolveNullable` defaults enrichment columns to
nullable, and `ClickHouseTypeMapper` silently ignores the flag for arrays — persisting
`nullable=true` in the catalog against a physically non-nullable column. Instead:
`TableColumnRules` rejects an explicit `nullable: true` on an array column (422 with a message
explaining an empty array `[]` is the natural "absent" value), and `resolveNullable` resolves an
array column to `false` even on enrichments. The catalog stays truthful.

*Alternatives considered*: silently coerce to false — rejected, a silent contradiction of an
explicit client instruction; 422 is honest and cheap. Interpret `nullable: true` as
`Array(Nullable(T))` (nullable elements) — rejected, that overloads a column-level flag with
element-level semantics; nullable elements can be added later as an explicit element-level flag if
ever needed.

### D3: expose `element_type` on reads and discovery, populated from the catalog

`TableDto.ColumnDto` and `QuerySchemaFieldDto` gain an optional `element_type` (null/absent for
non-array columns; `@JsonInclude(NON_NULL)` consistent with neighboring optional fields).
`TableMapper.toDto` and `CatalogQueryService.addColumn` read it from `ColumnMappingEntity`, which
already persists it. The backfilled `execution_path` thereby starts reporting
`element_type: "string"` in discovery — an additive change to that response. `QueryFieldBinding`
(translation-side) does not need the element type: no DSL operation consumes it.

### D4: no persistence or row-write changes; one result-path serialization fix

`column_mapping.element_type` exists since V1; `QueryFieldTypeConverter` handles the enum. Row
writes pass values through Jackson → `JSONEachRow`; ClickHouse parses JSON arrays into `Array(T)`
and rejects mismatches, which `CatalogRowWriter` already maps to the ClickHouse error contract.
Integration tests pin this behavior (write + read back through a structured query).

Reading arrays back exposed a latent bug: the ClickHouse client materializes `Array(...)` cells as
its internal `BinaryStreamReader.ArrayValue` wrapper, which Jackson serialized by its getters
(`{"array": [...], "arrayOfObjects": [...]}`) instead of as a JSON array — already broken today for
`execution_path` projections. `StructuredQueryExecutor` now unwraps `ArrayValue` to a plain `List`
when copying row values, so array cells serialize as JSON arrays.

### D5: PATCH `add` inherits everything

`PatchSchemaRequest` reuses `CreateTableRequest.ColumnRequest`, and `PATCH`'s validation funnels
through the same `TableColumnRules.requireValidUserColumn` — so lifting the rule there covers both
paths. The existing "type-change is rejected" PATCH rule already prevents converting a scalar
column to array (or changing an array's element type, since `type` equality is what's checked and
`element_type` is only valid on `add`).

## Risks / Trade-offs

- [Bare `SELECT *` on the SQL-translation path fails closed for array-bearing tables] → Existing,
  specified behavior (`query-translation-endpoint`); arrays becoming user-declarable makes it more
  visible. Mitigation: none needed now; documented as a non-goal, revisit if SQL consumers need
  arrays.
- [Arrays are write/project-only — no DSL filter can target them] → Acceptable for the modeling
  use-case (project the list, filter on scalars); adding operators later is additive.
- [Discovery response gains a field] → Additive; existing consumers ignore unknown fields
  (Jackson default on the known clients).

## Migration Plan

No database migration. Purely additive API change; deploy and roll back freely. The seeded
`execution_path` row already carries `element_type='string'`, so reads/discovery light up
immediately.

## Open Questions

_None._
