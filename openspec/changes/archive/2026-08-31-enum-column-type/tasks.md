## 1. Type vocabulary and catalog storage

- [x] 1.1 Add `ENUM("enum")` to `QueryFieldType`, with a javadoc sentence saying it is a closed set
      of strings parameterized by the column's `enum_values` and that it behaves as a string on
      every read surface.
- [x] 1.2 Add `V33__column_enum_values.sql` under `db/migration/postgres/`: `ALTER TABLE
      column_mapping ADD COLUMN enum_values text[]` and the same column on the Envers mirror
      `column_mapping_aud`. Both nullable, no backfill.
- [x] 1.3 Add `enumValues` (`List<String>`) to `ColumnMappingEntity` with
      `@JdbcTypeCode(SqlTypes.ARRAY)` and `@Column(name = "enum_values", columnDefinition = "text[]")`,
      mirroring `TableDefinitionEntity.tagOrder`; javadoc that it is set only for an `enum` column
      and that its order assigns the ClickHouse enum ids.

## 2. Declaration and validation

- [x] 2.1 Add `enumValues` (`List<String>`) to the service-layer `ColumnInput` record, documented
      beside the existing `elementType` sentence as the parallel type parameter.
- [x] 2.2 Add `MAX_ENUM_VALUE_LENGTH` (64) and `MAX_ENUM_VALUES` (512) to `TableColumnRules`, plus
      `normalizeEnumValues(List<String>)`: trims each value and rejects an empty list, a null/blank
      value, a duplicate (after trimming), an over-length value, and an over-count list.
- [x] 2.3 Extend `TableColumnRules.requireValidUserColumn` with a `requireValidEnumColumn` branch
      beside the array/map branches: `enum` requires `enum_values`, any other type carrying
      `enum_values` is rejected, and `enum` is rejected as an `array` `element_type`.
- [x] 2.4 Extend `TableColumnRules.requireKeyable` (or its callers) so an `enum` column is accepted
      as an `ordering_key`/`grain_key`, and confirm `TableTtlRules` already rejects it as a TTL
      column and `ScanMetadataRules` as a `version_column` (both require `timestamp`).
- [x] 2.5 Persist the normalized values in `CatalogSchemaWriter.columnEntity`, and copy `enumValues`
      across in `CatalogSchemaWriter.renameColumn` next to `elementType`.

## 3. ClickHouse materialization

- [x] 3.1 Extend `ClickHouseTypeMapper.toDdlType` to accept the column's enum values and render
      `Enum8('<v>' = 1, …)` for ≤127 values, `Enum16(…)` above that, ids by 1-based declared
      position, `Nullable(...)`-wrapped when the column is nullable.
- [x] 3.2 Add the private single-quote escape (one `'` becomes two) applied to every value before it
      is embedded, with a javadoc note on why `Identifiers.quote` is not the tool here.
- [x] 3.3 Add the `ENUM` arm to `ClickHouseTypeMapper.scalarType` and update every `toDdlType` call
      site (schema creation and the `PATCH add` path) to pass the column's enum values.

## 4. Downstream type handling

- [x] 4.1 Add the `ENUM` case to the seven remaining exhaustive switches, joining the `STRING` arm
      in each: `JooqTypes.of`, `InformationSchema.dataType`, `InformationSchema.facets`,
      `ResultColumns.scalarKind`, `CalciteQueryCatalog.scalarType`, `SqlProjectionOnlyTypes`,
      `ExprFieldType`. The compiler names each one; none should need a new behavior.
- [x] 4.2 Confirm no change is needed below `ResultColumnKind` — `ArrowBatchWriter`,
      `ArrowTypeMapper` and `FlightCatalogStreams` switch on the kind, not on `QueryFieldType`, so
      `ENUM → ResultColumnKind.STRING` should cover them. Verify rather than assume.
- [x] 4.3 Teach `ExprTranslator.unify` that `ENUM` and `STRING` unify (to `ENUM`), beside the
      numeric widening rule, so a string literal compares against an enum field. Keep an
      `enum`-vs-numeric/timestamp comparison rejected.
- [x] 4.4 Confirm the safe-direction predicates need no edit: `ExprFieldType.isScalar`,
      `SqlProjectionOnlyTypes.isProjectionOnly`, `ExprFieldType.isNumeric`,
      `TableTtlRules.wrapsToDateTime`. Add a test pinning `isScalar(ENUM)` so a future refactor to
      an allow-list cannot silently make enum projection-only.
- [x] 4.5 In `EnrichmentTypeMapping`: allow `isCompatible(STRING → ENUM)`, and refuse `enum` as an
      output variable's declared logical type with a message directing the author to declare
      `string` and let the target column carry the domain (mirroring the `array<...>` refusal).

## 5. API surface

- [x] 5.1 Add `@JsonProperty("enum_values") List<String> enumValues` to
      `CreateTableRequest.ColumnRequest` (shared by `DefineSchemaRequest` and
      `PatchSchemaRequest.add`), with an `@Schema` description naming the required-iff-`enum` rule,
      the caps, and immutability.
- [x] 5.2 Add `enum_values` to `PatchSchemaRequest.ColumnMetadataUpdate` **solely to reject it**
      with 422 — required because `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, so an undeclared field
      would be silently swallowed. Mirror the wording of the frozen `ordering_key`/`partition_by`
      members.
- [x] 5.3 Add `enum_values` to `TableDto.ColumnDto` and map it in `TableMapper` so
      `GET /v1/tables/{name}`, the list surface, and the revision snapshot all return it.
- [x] 5.4 Add `enum_values` to `QuerySchemaFieldDto` and populate it in
      `CatalogQueryService.addColumn`; a hidden `sensitive` column is already omitted entirely, so
      no extra guard is needed.
- [x] 5.5 Thread `enumValues` through the request → `ColumnInput` conversion for both
      `POST /v1/tables/{name}/schema` and `PATCH /v1/tables/{name}/schema` adds, and enforce the
      update-rejection rule in `TableSchemaService`.

## 6. Tests

- [x] 6.1 Unit-test `ClickHouseTypeMapper`: `Enum8` under the threshold, `Enum16` above it,
      `Nullable(Enum8(…))`, positional ids, and an apostrophe value rendering as `''`.
- [x] 6.2 Unit-test `TableColumnRules`: `enum` without values, `enum_values` on a non-enum column,
      `enum` as an array element type, empty list, blank value, duplicates, over-length value, and
      over-count list each rejected; a valid list trimmed and order-preserved.
- [x] 6.3 Unit-test the DSL rule: a `string` literal unifies with an `enum` field; a numeric literal
      against an `enum` field is rejected; `isScalar(ENUM)` is true.
- [x] 6.4 Unit-test `EnrichmentTypeMapping`: `string` → `enum` compatible, `timestamp` → `enum`
      rejected, and `enum` refused as an output variable type.
- [x] 6.5 Integration-test the catalog path: declare an `enum` column via `POST .../schema`, assert
      the materialized ClickHouse type, assert `GET /v1/tables/{name}` returns `type: "enum"` with
      `enum_values` in declared order, and assert the discovery schema carries both.
- [x] 6.6 Integration-test the live-schema path: `PATCH add` issues the expected `ALTER`; a `PATCH
      update` carrying `enum_values` is a 422 `validation_error` leaving the stored domain
      untouched; a `PATCH rename` preserves type and domain.
- [x] 6.7 Integration-test rows and reads: a declared value round-trips through
      `POST /v1/tables/{name}/rows` and both a DSL and a SQL projection; a filter with a string
      literal matches; a value outside the domain fails through the existing row-write contract with
      nothing partially stored.
- [x] 6.8 Integration-test `information_schema` reports the enum column as `CHARACTER VARYING`, and
      that the audit revision snapshot returns its `enum_values` (guards the `column_mapping_aud`
      column).
- [x] 6.9 Integration-test that ordering by an `enum` column follows declared order, not
      alphabetical order.

## 7. Documentation and spec

- [x] 7.1 Add `docs/enum-columns.md` following the shape of `docs/column-tags.md` /
      `docs/heavy-columns.md`: what the type is for, how to declare it, the `Enum8`/`Enum16` split,
      declared-order sorting, string-comparability in the DSL and SQL, and the drop-and-re-add path
      for widening. Cross-link from those two docs and from `README.md`.
- [x] 7.2 No configuration property is added or renamed, so `docs/configuration.md` is deliberately
      untouched — confirm that is still true before closing out.
- [x] 7.3 Re-read `openspec/changes/enum-column-type/specs/` (five delta files) against the shipped
      behavior and correct any divergence, so they archive as an accurate description of what was
      built.

## 8. Verification

- [x] 8.1 Run `./gradlew clean build` (compile + checkstyle + tests) and make it pass.
