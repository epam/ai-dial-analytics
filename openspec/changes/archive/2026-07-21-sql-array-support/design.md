# Design: sql-array-support

## Context

The governed SQL pipeline (Calcite front end shared by `execute-sql`, Flight SQL, and
`translate-sql`) deliberately excludes `array`-typed fields from the SQL-visible schema
(`CalciteQueryCatalog.sqlVisible`), so any reference fails validation as an unknown column, and
the bare-select renderer rejects entities whose source columns include an array
(`StructuredQueryToSqlRenderer.bareSelectList`). The Arrow result path has no list type: the
value family (`ResultColumnKind`) is scalar-only, `ArrowTypeMapper` maps only scalars, and
`ArrowBatchWriter.setValue` writes only scalar vectors.

Everything upstream of SQL/Arrow already supports arrays, completed by the
`array-column-support` change (archived 2026-07-20):

- Users declare `type=array` columns with a required scalar `element_type` (`string`,
  `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid`); ClickHouse
  materializes `Array(<element>)`; array columns are always non-nullable
  (`Nullable(Array(...))` is rejected at declaration).
- Discovery (`GET /v1/queries/entities/schema/{name}`) exposes `element_type` on array fields
  (`QuerySchemaFieldDto.elementType`, from `ColumnMappingEntity.elementType`).
- The structured JSON path (`POST /v1/queries/execute`) projects array columns natively:
  `JooqTypes` maps `ARRAY` → `SQLDataType.OTHER` (plain column reference),
  `StructuredQueryExecutor` unwraps the ClickHouse client's `ArrayValue`, and Jackson
  serializes a JSON array.

What is NOT yet threaded to where the SQL front end needs it: `QueryFieldBinding` (the flat
name → jOOQ field → `QueryFieldType` binding consumed by the Calcite snapshot) carries no
element type. The seeded `dial_usage_log.execution_path` (`Array(LowCardinality(String))`,
`element_type=string`) plus any user-declared array column are the affected fields.

## Goals / Non-Goals

**Goals:**

- Array fields are selectable via SQL on all three SQL surfaces (`execute-sql`, Flight SQL,
  `translate-sql`) and render correctly on the DSL→SQL surface (`translate`), for every scalar
  `element_type` the catalog allows.
- Arrow Flight results encode array columns as Arrow `List` vectors with a typed element child;
  FlightInfo schema and doGet batches agree.
- REST SQL results return array values as native JSON arrays (identical to `/execute`).
- Array fields are **projection-only** in SQL: use in `WHERE`, `GROUP BY`, `ORDER BY`,
  `SELECT DISTINCT` (row-mode full-width grouping), or as a function/aggregate argument is
  rejected at translation with an error naming the field and the restriction — never a
  misleading "unknown column", never a raw ClickHouse error.

**Non-Goals:**

- Array *operators* (membership/`has`, length, element access, `IN` against an array column) in
  SQL or the DSL — the DSL has no array-value literal (`ValueType` has no `array`), and adding
  operator semantics is a separate change.
- Discovery/table-management changes — `element_type` exposure and user-declarable array
  columns already shipped in `array-column-support`.
- `object` fields — they remain excluded from the SQL-visible schema entirely.
- Nested arrays (`Array(Array(...))`) — the catalog only models scalar element types.

## Decisions

### 1. Make arrays SQL-visible in the Calcite schema, keep `object` invisible

`CalciteQueryCatalog.sqlVisible` admits `ARRAY`; `calciteType` maps an array field to
`typeFactory.createArrayType(<element calcite type>, -1)`. This gives real Calcite typing
(rather than pretending arrays are ANY/VARCHAR), so the validator's own type checking rejects
most misuse (e.g. `length(execution_path)` fails function-signature validation) before our
translator runs.

*Alternative considered*: keep arrays invisible and special-case projection. Rejected — it
cannot produce a correct validated row type, and "unknown column" errors for a real column are
the exact UX problem this change fixes.

### 2. Thread the element type into the query model binding

`QueryFieldBinding` gains a nullable `elementType` (`QueryFieldType`, set only for `ARRAY`
fields), populated in `CatalogQueryService.addColumn` from `ColumnMappingEntity.elementType` —
the same source discovery already uses for `QuerySchemaFieldDto.elementType`. The per-request
snapshot in `CalciteQueryCatalog` carries it alongside the field type, and the result-schema
derivation reads it for the array column kind. A missing element type on an array column
(defensive; declaration validation always stores one) falls back to `string`.

### 3. Projection-only enforcement in the Rel→DSL translator

Once visible, Calcite will happily validate `WHERE execution_path = ...`, `GROUP BY
execution_path`, `ORDER BY execution_path`, and `COUNT(execution_path)`. The DSL cannot express
any of these (no array literal, no array comparisons), so `RelToStructuredQueryTranslator` /
`RexToExprTranslator` reject any array-typed field reference outside a plain projection item
with `SqlTranslationException` messages of the form
`array field 'execution_path' can only be projected; <use> is not supported`. Concretely:

- comparison/predicate operands (`RexToExprTranslator`): reject array field refs and any array
  literal Calcite might construct;
- `group_by` keys and sort keys: reject array fields (this also covers `SELECT DISTINCT`
  projecting an array, which Calcite lowers to a full-width group-by — the error message for
  that shape names DISTINCT rather than GROUP BY where distinguishable, else the group-by
  message is acceptable);
- function/aggregate arguments: reject array fields (belt-and-braces behind Calcite's own
  signature checking).

Result kinds: `ResultColumnKind` gains `ARRAY`, and `ResultColumn` carries a nullable
`elementKind` (the element's scalar kind) so the Arrow layer knows the child type. The
defensive `fieldKind`/`calciteType` `IllegalStateException`s for ARRAY are replaced by the real
mappings; the `object` ones stay.

### 4. Arrow encoding: `List` vector with a typed nullable `item` child

`ArrowTypeMapper` maps `ARRAY(element)` to `new ArrowType.List()` with a single child `Field`
named `item` whose type is the element kind's existing scalar mapping (string→Utf8 etc.),
child nullable (consistent with the endpoint's "all fields nullable" rule; ClickHouse array
elements are non-null, so element nulls simply never occur). `ArrowBatchWriter.setValue` gains
a `ListVector` branch: start a list, coerce each element with the existing per-kind scalar
coercion, end the list; a Java `List` or `Object[]` cell (post-`ArrayValue`-unwrap shapes from
client-v2) is accepted; a null cell writes a null list; an empty collection writes an empty
(non-null) list. Batch size accounting continues to use the existing per-vector buffer-size
mechanism (nothing kind-specific).

*Alternative considered*: encode arrays as JSON strings (Utf8). Rejected — it lies about the
type to Arrow-native consumers and would diverge from the JSON path's native arrays.

### 5. Renderer: bare select narrows its rejection to `object`

`StructuredQueryToSqlRenderer.bareSelectList` keeps rejecting `object` source columns (still no
SQL representation) but includes array columns as ordinary double-quoted identifiers. Explicitly
selected array fields render the same way. The render→translate round-trip then holds: rendered
SQL referencing an array column validates against the now-visible Calcite schema. `ArrayExpr`
(the IN-list construct) is untouched — still only valid as the right operand of `in`.

### 6. Execution and JSON path: no changes

Execution already renders array columns as plain identifiers (`JooqTypes` OTHER), the executor
already unwraps `ArrayValue`, and the `/execute` DTO already serializes lists as JSON arrays.
`execute-sql` inherits all of it once translation stops rejecting the column. Integration tests
pin the JSON shape (`execution_path` returns `["a","b"]`-style arrays) rather than changing code.

## Risks / Trade-offs

- [Calcite lowers some array usage into shapes we don't anticipate (e.g. Sarg over arrays,
  implicit casts)] → the translator fails closed: any Rex node kind we don't explicitly accept
  already throws an unsupported-construct error, and the new array checks run before operand
  translation. Tests cover comparison, IN, GROUP BY, ORDER BY, DISTINCT, and function-arg misuse.
- [client-v2 cell shape varies (`List`, `Object[]`, typed arrays) across element types] → the
  Arrow writer accepts any `Collection`/array shape and normalizes; the integration suite runs
  against a real ClickHouse to pin actual shapes for string arrays (the seeded column) and, via
  a user-declared table, at least one non-string element type.
- [Element-type gaps — the Arrow child mapping must cover every scalar `QueryFieldType`] → the
  child mapping reuses the existing normative scalar table, so every scalar kind is covered by
  construction; a unit test asserts totality over the declarable element types.
- [Existing clients relying on "array misuse fails as unknown column"] → the failure stays a
  400/INVALID_ARGUMENT translation error; only the message improves.

## Migration Plan

Additive, no configuration, no DB migration, no dependency changes. Previously rejected SQL
statements start succeeding; no previously accepted request changes shape (the only response
addition is array columns appearing in bare-select renderings that were previously rejected).
Rollback = revert the commit.

## Open Questions

(none — scope questions were settled in the proposal: array operators are a follow-up;
user-declarable array columns and discovery `element_type` already shipped upstream)
