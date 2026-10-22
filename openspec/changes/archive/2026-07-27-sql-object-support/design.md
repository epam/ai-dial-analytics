# Design: sql-object-support

## Context

The governed SQL pipeline (the Calcite front end shared by `execute-sql`, Flight SQL and
`translate-sql`) excludes `object`-typed fields from the SQL-visible schema — a single predicate,
`CalciteQueryCatalog.sqlVisible`, drops them — so any reference fails validation as an unknown
column. Four places encode "object has no SQL/Arrow representation" as a hard stop:

- `CalciteQueryCatalog.sqlVisible` (exclusion) and `EntityTable.scalarType` (`IllegalStateException`);
- `RelToStructuredQueryTranslator.fieldKind` (`IllegalStateException` for `OBJECT`);
- `StructuredQueryToSqlRenderer.bareSelectList` — throws
  `"an empty 'select' projects the object-typed column '<name>', which has no SQL representation;
  list the desired columns explicitly"`, which makes the most common DSL shape (row query, no
  `select`) untranslatable for `dial_usage_log`, whose source columns include `jwt_claims` and
  `request_tags`;
- `ArrowTypeMapper` / `ArrowBatchWriter` — `ResultColumnKind` has no object family.

Everything upstream already handles objects: the catalog declares `type=object` columns
(ClickHouse `JSON`, never `Nullable`), discovery publishes them, the write path stores JSON, and
`POST /v1/queries/execute` projects them natively (`JooqTypes` maps `OBJECT` → `SQLDataType.OTHER`,
so the column renders as a plain identifier and Jackson serializes whatever the ClickHouse client
returns).

The immediately preceding change `sql-array-support` (archived 2026-07-21) solved the identical
problem for `array` columns and established the pattern this design reuses: make the type visible
with a real Calcite type, enforce **projection-only** in the Rel→DSL translator and in the DSL→SQL
renderer, and give the Arrow layer a concrete encoding. The one genuinely new question is the
Arrow encoding, because — unlike an array with a declared scalar `element_type` — a ClickHouse
`JSON` column is schemaless and row-heterogeneous.

## Goals / Non-Goals

**Goals:**

- `object` fields are projectable via SQL on all three SQL surfaces (`execute-sql`, Flight SQL,
  `translate-sql`) and render correctly on the DSL→SQL surface (`translate`).
- The bare-select rejection quoted above is **removed**: an absent/empty row-mode `select` over
  an entity with `object` source columns renders as the explicit source-column list (or `*`),
  matching what execution projects.
- Arrow Flight results carry `object` columns as `Utf8` holding the value's canonical JSON text;
  FlightInfo schema and `doGet` batches agree.
- REST SQL results return `object` values as native JSON objects — byte-identical to `/execute`.
- `object` fields are **projection-only** in SQL: use in `WHERE`, `GROUP BY`, `ORDER BY`,
  `SELECT DISTINCT`, or as a function/aggregate argument is rejected at translation with an error
  naming the field and the restriction — never "unknown column", never a raw ClickHouse error.
- After this change **no declarable field type is SQL-invisible**, so the "SQL-visible subset of
  the schema" concept collapses into "the schema".

**Non-Goals:**

- JSON operators — path/member access (`jwt_claims.sub`), `JSON_VALUE`/`JSON_QUERY`, key existence,
  filtering or grouping by an extracted member. The DSL has no object literal and no path
  expression; adding them is a separate change (the same boundary `sql-array-support` drew).
- Typed/structured Arrow output for objects (Arrow `Struct` or `Map`) — see decision 3.
- Any change to declaration, discovery, ingestion, or the `/execute` path.
- Column governance changes: sensitive-column filtering already happens when the
  `QueryEntityModel` is built, upstream of both the Calcite schema and execution.

## Decisions

### 1. Make `object` SQL-visible; delete the visibility filter entirely

`CalciteQueryCatalog.sqlVisible` is removed rather than extended: with `object` admitted, every
declared field type is visible, so the predicate and its two call sites (`fieldTypes` and
`EntityTable.getRowType`) become unconditional. This is deliberate — leaving a
`sqlVisible(...) -> true` stub would invite the next reader to assume some type is still hidden.
The class javadoc's "fields of type object … are excluded" paragraph is rewritten to state the
projection-only rule for `array` and `object` instead.

### 2. Calcite type for `object`: `VARCHAR`

`EntityTable.scalarType` maps `OBJECT` to `SqlTypeName.VARCHAR`.

Rationale: Calcite has no JSON type — its own JSON support (`JSON_VALUE`, `JSON_EXISTS`, …) is
defined over character strings, so VARCHAR is the idiomatic Calcite spelling for a JSON column and
leaves the door open for a later JSON-operator change without re-typing the schema. It also lines
up with the Arrow encoding (decision 3), keeping one story: *an object is JSON text once it leaves
the DSL*.

The cost is that Calcite's validator will happily accept `WHERE jwt_claims = 'x'` or
`upper(jwt_claims)` — VARCHAR gives no type-level protection the way the array type did. That is
acceptable because the projection-only guard (decision 4) rejects those explicitly, with a better
message than a validator type error would produce. Because our guard runs on *every* such shape
(rather than only the ones Calcite's type system happens to reject), object misuse actually gets
*more* consistent messages than array misuse does.

*Alternatives considered*: `SqlTypeName.ANY` — rejected, it disables Calcite's operand checking
wholesale and would let more nonsense reach our translator in un-anticipated Rex shapes.
`SqlTypeName.OTHER` — rejected, comparisons against it fail with a Calcite type error before our
descriptive guard runs, which is exactly the "misleading error" UX this change removes.
A Calcite MAP type — rejected, it lies: ClickHouse `JSON` is heterogeneous and nested, not a
homogeneous map.

### 3. Arrow encoding: `Utf8` carrying canonical JSON text

`ResultColumnKind` gains `OBJECT`; `ArrowTypeMapper.field` maps it to a nullable `ArrowType.Utf8`
column. The mapping lives in `field` rather than in the `arrowType` switch on purpose: `arrowType`
is also the *element-kind* table for array columns, so keeping it scalar-only means an array
declaring a non-scalar element kind fails at schema time instead of advertising a schema the batch
writer then chokes on mid-stream. `ArrowBatchWriter` coerces the cell to JSON text with the
application's shared Jackson mapper before writing it into the `VarCharVector`.

Arrow simply cannot express "arbitrary JSON value" natively: `Struct` requires one fixed field set
for the whole batch, which a schemaless ClickHouse `JSON` column does not have (row 1 may carry
`{"sub":…}`, row 2 `{"roles":[…],"tier":3}`); `Map<Utf8,Utf8>` would flatten and stringify nested
values, losing type and shape. Text is what every other system does here, including ClickHouse's
own Arrow output for `JSON` columns. Note this does *not* contradict `sql-array-support`'s
rejection of JSON-string arrays: an array has a declared scalar element type, so a faithful Arrow
type exists; an object does not.

The writer must produce *canonical JSON text*, not `Object::toString`: the ClickHouse client-v2
surfaces a `JSON` cell as a Java `Map` (nested values as `Map`/`List`/boxed scalars), whose
`toString` is Java map notation (`{sub=user-1}`), not JSON. So the coercion is `objectMapper.writeValueAsString(cell)` for *every*
cell, whatever its Java type — deliberately with no `String` pass-through. A pass-through looks
defensive but breaks the one invariant that matters here: REST serializes the same cell with the
same mapper, so special-casing `String` on the Arrow side would make the two surfaces disagree
about a value the specs require them to agree on. Agreement beats a guess about whether a `String`
cell was "really" pre-serialized JSON. This makes
`ArrowBatchWriter` take an `ObjectMapper`; it is currently a static utility, so the mapper is
threaded through `drain(...)` from the Flight producer rather than by making the class a bean —
smallest change consistent with the existing call shape.

*Alternative considered*: tag the field with the canonical `ARROW:extension:name = arrow.json`
metadata so extension-aware clients can auto-decode. Rejected for this change — the storage type
and the bytes are identical either way, unregistered-extension handling varies across client
languages, and it is a purely additive follow-up if a consumer asks for it.

### 4. Projection-only enforcement: generalize the array guard, do not duplicate it

The three existing array guards are widened to a shared notion of *projection-only field type*
(`ARRAY` or `OBJECT`), with the type name interpolated into the message so operators keep getting
type-accurate text:

- `RexToExprTranslator.rejectArrayOperand` → `rejectProjectionOnlyOperand`, covering comparison
  operands, `IS [NOT] NULL`, LIKE patterns and function arguments;
- `RelToStructuredQueryTranslator.rejectArrayField` → `rejectProjectionOnlyField`, covering
  `GROUP BY` keys, sort keys, `SELECT DISTINCT` projections and aggregate arguments;
- `StructuredQueryToSqlRenderer.rejectNonProjectionArrayUse` → `rejectNonProjectionUse`, the
  render-side mirror that keeps the DSL→SQL→DSL round-trip inside the accepted subset.

Message shape stays exactly as today, with the type word varying:
`object field 'jwt_claims' can only be projected; a comparison on an object field is not
supported` (translation side) and `… cannot be expressed in SQL` (render side). Existing array
messages are unchanged, so array tests and any client string-matching keep passing.

Result kinds: `RelToStructuredQueryTranslator.fieldKind` maps `OBJECT` → `ResultColumnKind.OBJECT`
instead of throwing. `ResultColumn.elementKind` stays array-only (an object has no element type).

### 5. Renderer: the bare-select rejection is deleted, not narrowed

`bareSelectList` drops the `object` check outright and renders object columns as ordinary
double-quoted identifiers, so no source column type can block a bare select any more. The `*`
shortcut for enrichment-free entities is untouched. The round-trip then holds: the rendered
statement validates against the now-complete Calcite schema and translates back to the same DSL
query.

### 6. REST SQL inherits `/execute` — but the executor's array unwrap had to become recursive

`execute-sql` shares the execution and serialization path with `/execute`, so once translation
stops rejecting the column it inherits object projection for free. This is pinned by an integration
test asserting the `execute-sql` response is byte-identical to the equivalent `/execute` response
(the same assertion `sql-array-support` used for arrays).

One production change was needed, and it is a **latent bug fix, not new behavior**:
`StructuredQueryExecutor.normalize` unwrapped the ClickHouse client's `ArrayValue` only at the top
level of a cell. A `JSON` cell surfaces as a `Map` whose nested arrays carry the same wrapper, so
`{"roles":["admin"]}` serialized as `{"roles":{"array":["admin"],"arrayOfObjects":["admin"]}}` —
wrong on `/execute` too, and invisible until this change added the first test that projects an
object column with a nested array. `normalize` now recurses through maps and lists, rebuilding a
container only when a nested value actually changed, so scalar cells still cost one type check and
no allocation. Covered by a `structured-query` spec scenario and a `/execute` integration test, so
the fix is pinned on the surface where the defect actually lived.

The same integration test pinned a second observed property worth writing down rather than
rediscovering later: ClickHouse's `JSON` type **flattens nested objects into dotted path
keys**, so a stored `{"ctx": {"tags": ["a"]}}` reads back as the member `"ctx.tags"`, not as a
nested object. That is the column's storage format, and the service passes it through untouched —
re-nesting it would be a lossy guess (a literal member named `"a.b"` is indistinguishable from
nested `a` → `b`). Documented in the `structured-query` delta so consumers know what to parse.

Note the deliberate asymmetry this creates and which the specs must state: over REST an object is
a **native JSON object**, over Flight/Arrow it is **JSON text in a Utf8 column**. REST keeps native
objects because the two REST surfaces must not diverge; Arrow uses text because it has no other
option.

## Risks / Trade-offs

- [The ClickHouse client's Java representation of a `JSON` cell is not pinned by any current test
  — `/execute` never had integration coverage for `jwt_claims`] → **this risk materialized**: the
  client wraps arrays nested inside a JSON cell in `ArrayValue`, which the top-level-only unwrap
  missed (see decision 6). The Arrow coercion accepts any shape by delegating to Jackson (`Map`,
  `List`, scalar, `String` pass-through), and integration tests against real ClickHouse now pin the
  REST JSON shape, the nested-array shape, and the Arrow text. If the client returns a type Jackson
  cannot serialize, the writer's existing `IllegalStateException` wrapper names the column and kind.
- [VARCHAR typing lets Calcite accept string operations on objects that then fail in our guard,
  one shape at a time] → the guard is applied at every operand site the array guard already
  covers, and the test matrix mirrors the array one (WHERE, IS NULL, LIKE, GROUP BY, ORDER BY,
  DISTINCT, aggregate arg, function arg) so no site is left unprotected.
- [`SELECT *` on `dial_usage_log` now streams two JSON blobs per row over Flight, enlarging
  batches] → batch sizing already measures real allocated bytes per batch rather than row width,
  so a wider row simply flushes sooner; no new bound is needed.
- [Sensitive-column exposure: `jwt_claims` is a sensitive column and becomes reachable by a new
  surface] → no new reachability. `CalciteQueryCatalog` snapshots `CatalogQueryService.findModel`,
  whose bindings are already filtered by `SensitiveColumnAccess.maySeeSensitive()`; a caller
  without the grant gets a model without the column, so it is an unknown column in SQL exactly as
  it is invisible in discovery. An integration test asserts this rather than leaving it to
  inspection.
- [Removing the bare-select error changes behavior clients may depend on] → the error was a
  hard failure of the most common shape; nothing can depend on it succeeding. Any client
  string-matching that message loses a case it could never act on.

## Migration Plan

Additive, no configuration, no DB migration, no dependency changes. Previously rejected SQL
statements and translations start succeeding; no previously accepted request changes shape.
Rollback = revert the commit.

## Open Questions

(none — the Arrow encoding question is settled in decision 3; JSON operators are explicitly a
follow-up change)
