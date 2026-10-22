# Enum column type for catalog tables

## Why

Every low-cardinality string column ADAS materializes is stored as an unbounded ClickHouse
`String`: `status`, `severity_text`, `span_kind`, `deployment`, `finish_reason` and the like.
Two costs follow, and both are visible today. Storage and scan cost — a `String` column carries
its bytes per row where a fixed domain needs one, and a `WHERE status = 'failed'` compares strings
instead of an integer. And the domain is invisible: nothing in the catalog says which values a
column may hold, so a consumer building a filter UI has to `SELECT DISTINCT` to find out, an
operator has no declaration to check a value against, and a typo (`'FAILED'` where the convention
is `'failed'`) is accepted silently and quietly stops matching.

ClickHouse's `Enum8`/`Enum16` solves both: a declared, closed set of strings stored as one or two
bytes, compared as integers, with the server itself rejecting a value outside the set at insert
time. The catalog is the right place to declare that set, because the catalog already decides how
a column materializes and how it is exposed.

**Why now, specifically.** `QueryFieldType` is a published wire vocabulary — it is the `type` on
`GET /v1/tables/{name}`, on `GET /v1/queries/entities/schema/{name}`, in `information_schema`,
and over Flight. Adding a member to it is close to free while no consumer depends on it, and is a
breaking change afterwards. The system is not in use yet, so this is the cheapest moment this
change will ever have.

## What Changes

- **`enum` becomes a member of the catalog column-type vocabulary** (`QueryFieldType.ENUM`, wire
  code `"enum"`), alongside `string`, `array`, `map` and the rest.
- **An `enum` column declares `enum_values`** — an ordered, non-empty list of distinct string
  values — on `POST /v1/tables/{name}/schema` and on a `PATCH .../schema` column add. This is the
  exact shape `array` already uses for `element_type`: a type parameter required iff the type
  needs it, rejected on any other type.
- **Materialization**: an `enum` column becomes ClickHouse `Enum8('a' = 1, …)` for up to 127
  values and `Enum16(…)` above that, ids assigned by declared position, `Nullable(...)`-wrapped
  when the column is nullable.
- **Downstream, an `enum` behaves as a string.** It reports `CHARACTER VARYING` in
  `information_schema`, binds as `VARCHAR` in jOOQ and Calcite, and is written into an Arrow
  `Utf8` vector — so SQL, Flight and every BI client handle it as the text it is. Two places need
  an explicit rule rather than a mechanical one:
  - **the DSL comparison rule** — a `string` literal SHALL compare against an `enum` field
    (`filter: status eq "running"`), which today's "operands must be of the same type" check would
    otherwise reject;
  - **enrichment output binding** — a rule declaring a `string` output SHALL be bindable to an
    `enum` column, which today's exact-match compatibility check would otherwise reject.
- **`enum_values` is returned wherever a column's shape is** — `GET /v1/tables/{name}`,
  `GET /v1/tables` (when columns are requested), the revision snapshot, and the flat field on
  `GET /v1/queries/entities/schema/{name}` — as a JSON array in declared order, absent/null for
  a non-`enum` column.
- **Validation** rejects with 422: an `enum` column with no `enum_values`; `enum_values` on a
  non-`enum` column; an empty list; a blank value; duplicates; a value longer than 64 characters;
  and more than 512 values. `enum` is not a valid `array` `element_type` in this change.
- **The declared domain is immutable for the life of the column.** A `PATCH .../schema` metadata
  update SHALL NOT change `enum_values` (changing a ClickHouse enum's domain is an
  `ALTER MODIFY COLUMN` that rewrites the column and can fail on existing rows). A domain is
  widened by dropping and re-adding the column, exactly as a column's type is changed.
- **New migration** `V33__column_enum_values.sql` adds a nullable `enum_values text[]` to
  `column_mapping` and to its Envers mirror `column_mapping_aud`.
- **Writes are unchanged in shape**: `POST /v1/tables/{name}/rows` supplies an enum column's value
  as the JSON string it already does; ClickHouse rejects a value outside the declared set, which
  surfaces through the existing row-write failure contract.

Explicitly **not** in this change: enum-typed array elements (`Array(Enum8(…))`); altering an
existing column's domain in place; caller-chosen numeric ids; and `enum` as an enrichment rule's
declared *output variable* type (a rule declares a value, the column declares the domain).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `table-management`: the column-type vocabulary gains `enum` with its required `enum_values`
  parameter, its validation rules, its ClickHouse `Enum8`/`Enum16` materialization, its
  immutability under `PATCH`, and its presence on the table read surfaces.
- `query-schema-discovery`: a flat field may have type `enum` and carries the column's
  `enum_values`.
- `structured-query`: a `string` literal compares against an `enum` field; an `enum` field is an
  ordinary scalar everywhere else in the DSL.
- `sql-query-translation`: an `enum` column is typed `VARCHAR` to the SQL surface and reported as
  `CHARACTER VARYING` by `information_schema`, so inbound SQL treats it as text.
- `enrichment-registry`: a rule output declared `string` may be bound to an `enum` target column.

## Impact

- **API**: `CreateTableRequest.ColumnRequest` (shared by `DefineSchemaRequest` and
  `PatchSchemaRequest.add`) gains `enum_values`; `TableDto.ColumnDto` and `QuerySchemaFieldDto`
  gain it on the way out. Additive — no existing request or response changes shape.
- **Wire vocabulary**: `QueryFieldType` gains `enum`. Additive, and taken now precisely because it
  would be a breaking change once consumers exist.
- **Catalog schema**: `column_mapping.enum_values text[]` (nullable), plus the matching
  `column_mapping_aud` column so the audit trail keeps recording full column state.
- **Code**: `QueryFieldType`, `ColumnInput`, `ColumnMappingEntity`, `TableColumnRules`,
  `ClickHouseTypeMapper`, `CatalogSchemaWriter`, `TableSchemaService`, the table/query mappers,
  the eight exhaustive `QueryFieldType` switches (`JooqTypes`, `InformationSchema` ×2,
  `ResultColumns`, `CalciteQueryCatalog`, `SqlProjectionOnlyTypes`, `ExprFieldType`,
  `ClickHouseTypeMapper`), plus `ExprTranslator.unify` and `EnrichmentTypeMapping`.
- **Docs**: a new `docs/enum-columns.md` alongside `column-tags.md`/`heavy-columns.md`. No new
  config keys, so `docs/configuration.md` is untouched.
