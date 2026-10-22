## Context

See proposal.md — Why. The design-relevant state, established by reading the code rather than
assumed:

- `QueryFieldType` (`web/dto/query/`) is the abstract column-type vocabulary — `string`, `integer`,
  `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid`, `object`, `array`, `map` — and a
  published wire value on `/v1/tables`, `/v1/queries/.../schema`, `information_schema` and Flight.
- **Eight switches over it are exhaustive** (switch expressions with no `default`), so adding a
  constant is a compile error at each: `JooqTypes.of`, `InformationSchema.dataType`,
  `InformationSchema.facets`, `ResultColumns.scalarKind`, `CalciteQueryCatalog.scalarType`,
  `SqlProjectionOnlyTypes`, `ExprFieldType`, `ClickHouseTypeMapper.scalarType`. This is deliberate:
  `InformationSchema.facets`'s javadoc says it is a switch expression "so that adding a
  `QueryFieldType` is a compile error here instead of a silently all-null row."
- **The type predicates are written in the safe direction**, so a new constant lands correctly with
  no edit: `ExprFieldType.isScalar` is `!= OBJECT && != ARRAY && != MAP` (a new type is scalar);
  `SqlProjectionOnlyTypes.isProjectionOnly` is an allow-list of the three (a new type is freely
  usable); `ExprFieldType.isNumeric` is a set; `TableTtlRules.wrapsToDateTime` is `== TIMESTAMP`.
- **Two places are not mechanical** and would break an enum column if left alone:
  - `ExprTranslator.unify` (line ~373) requires `left == right`, widening only within the numeric
    family. `filter: status eq "running"` compares `ENUM` against `STRING` and would be rejected
    with "operands must be of the same type".
  - `EnrichmentTypeMapping.isCompatible` — "the numeric types are mutually assignable […] every
    other type must match exactly" — would reject a `string` rule output bound to an `enum` column,
    making enum columns unwritable by any enrichment.
- Downstream of `ResultColumns.scalarKind`, everything switches on `ResultColumnKind`, not
  `QueryFieldType` — so `ArrowBatchWriter`, `ArrowTypeMapper` and `FlightCatalogStreams` need **no**
  change once `ENUM → ResultColumnKind.STRING`.
- `ClickHouseTypeMapper` is the single place turning a type into a DDL spelling.
  `TableDdlRenderer` consumes those spellings as opaque strings and quotes only *identifiers*.
- `column_mapping` is `@Audited` (Envers): a new persisted column needs the same column on
  `column_mapping_aud` or the trail records an incomplete snapshot.
- `column_mapping.element_type` is the precedent for a per-column type parameter;
  `table_definition.tag_order` is the precedent for a Postgres `text[]` under
  `@JdbcTypeCode(SqlTypes.ARRAY)`.

## Goals / Non-Goals

**Goals:**

- `enum` as a first-class member of the type vocabulary, parameterized by `enum_values` in exactly
  the shape `array` is parameterized by `element_type`.
- Materialize as ClickHouse `Enum8`/`Enum16` and make the domain readable from the catalog and from
  schema discovery.
- Make an enum column *behave* as text everywhere downstream, so no consumer needs enum-awareness
  to use one.

**Non-Goals:**

- Altering a live column's domain in place (`ALTER … MODIFY COLUMN`), including widening.
- Enum-typed array elements (`Array(Enum8(…))`).
- Caller-chosen numeric ids; ids are positional and an implementation detail of the DDL.
- An `enum` literal in the DSL, or `enum` as an enrichment output variable's declared type.
- ADAS validating written values against the domain — ClickHouse already does, at the point that
  matters.

## Decisions

### A first-class type, taken now because the vocabulary is still free to change

`QueryFieldType` is a published wire vocabulary, and a new member of an enumerated wire field
breaks any consumer that switches exhaustively on it. That argues for expressing the domain as
metadata on a `string` column (`type: "string"` + `enum_values`) and never touching the vocabulary
— which is the design this change **started with and rejected**, on the maintainers' correction that the
system has no consumers yet.

With no consumers, the compatibility argument is void, and what remains inverts: adding `enum` is
eight mechanical edits today and a breaking change after launch. Modelling a real type as a flag on
a different type would also be a permanent tax — every future reader has to learn that a "string"
column is sometimes not one — paid to dodge a cost that does not exist yet.

Rejected alternative: `type: "string"` + `enum_values`. Zero read-path changes and no vocabulary
growth, but it misnames the thing (ClickHouse itself models this as a type), and it converts a
one-time internal cost into a permanent modelling compromise. Also rejected: encoding the domain in
`element_type` — that field is a single `QueryFieldType`, not a list.

### `enum_values` follows the `array`/`element_type` contract exactly

Required iff `type` is `enum`, rejected on any other type, validated in
`TableColumnRules.requireValidUserColumn` — the single gate both `POST .../schema` and `PATCH add`
already run through, so neither path can be updated without the other. A caller who has met
`array`/`element_type` already knows this shape; a reviewer reading the validator sees the two
parameterized types handled side by side.

### Downstream, ENUM resolves to whatever STRING does — and that is the point

Each of the eight exhaustive switches gets an `ENUM` case joining the `STRING` arm: `VARCHAR` in
`JooqTypes` and `CalciteQueryCatalog`, `CHARACTER VARYING` and the all-null `Facets` in
`InformationSchema`, `ResultColumnKind.STRING` in `ResultColumns`, the string branch elsewhere.
That is not boilerplate to regret — it is the design: a client that speaks only SQL sees text and
needs no enum awareness, while a client that reads the catalog gets the domain. And because
`ResultColumnKind` is what the Arrow and Flight layers switch on, that single `ENUM → STRING`
mapping is what buys zero changes in `ArrowBatchWriter`, `ArrowTypeMapper` and
`FlightCatalogStreams`.

### The two rules that are not mechanical

**`ENUM` unifies with `STRING` in `ExprTranslator.unify`.** The DSL has no enum literal and will
not get one — a caller names an enum value with the string it is. So `unify` gains a rule beside
the numeric widening: if one side is `ENUM` and the other `STRING`, the result is `ENUM`.
Alternative considered: give the DSL an `enum` literal type. Rejected — it would force every caller
to know a field is an enum before filtering it, which is exactly the coupling this design avoids
elsewhere.

**`EnrichmentTypeMapping.isCompatible` admits `STRING → ENUM`.** Without it the governance gate
rejects the binding and an enum column cannot be written by any enrichment. The reverse
(`ENUM` declared as an output variable type) is refused with a message pointing at `string`,
mirroring the existing refusal of a parameterized `array<...>` spelling and for the same stated
reason: the gate compares shapes, so accepting it would advertise a domain check that does not
happen.

### Positional ids, and why declared order is load-bearing

Ids are the 1-based position in the declared list. That makes `enum_values` **ordered data, not a
set**: stored and returned in declared order, never sorted. Two consequences worth stating rather
than discovering:

- `ORDER BY` on an enum column sorts by id, i.e. declared order — which is what a caller declaring
  `["low", "medium", "high"]` wants, and is *not* alphabetical. Spec'd explicitly in both
  `table-management` and `structured-query`.
- Because the domain is immutable, ids are stable, so a catalog export/import reproduces the same
  physical type.

Alternative considered: sorting values before assigning ids, for a canonical DDL. Rejected — it
discards the one piece of caller intent (severity order) an enum is uniquely good at expressing.

### Immutability, and why `update` rejects rather than ignores

Widening a ClickHouse enum is `ALTER TABLE … MODIFY COLUMN`. It rewrites the column, is not free on
a large table, and fails outright if a stored row holds a value the new domain drops. Supporting it
properly means a validation pass against live data that ADAS cannot do cheaply or atomically with
the catalog write. The domain is therefore fixed at column creation — exactly as a column's *type*
already is — and widened by drop + re-add.

`ColumnMetadataUpdate` declares `enum_values` **only in order to reject it** with 422, mirroring
`PatchSchemaRequest`'s existing treatment of the frozen `ordering_key`/`partition_by`/`grain_key`
members. It must be declared to be rejected: `FAIL_ON_UNKNOWN_PROPERTIES` is **disabled** in
`JsonMapperConfiguration`, so an undeclared field is silently swallowed and the caller would
believe the domain changed.

### DDL literal escaping

Enum values are free-form caller text embedded in a `CREATE TABLE` as SQL string literals.
`Identifiers.quote` produces backtick-quoted *identifiers* and is the wrong tool.
`ClickHouseTypeMapper` gains a private single-quote escape (one `'` becomes two) applied to every
value; the 64-char and 512-value caps bound the statement size. This is the one genuinely new
injection-shaped surface in the change, confined to one method with a dedicated test.

### Storage: `text[]`, not a child table or JSON

`column_mapping.enum_values text[]` under `@JdbcTypeCode(SqlTypes.ARRAY)` — the shape
`table_definition.tag_order` already uses, so Hibernate, Envers and the migration behave in a way
the codebase has proven. A child table would give ordering a natural home but adds a second audited
entity for a list never queried by value; JSON would lose array typing for no gain.

## Risks / Trade-offs

- **Eight switch sites is eight chances to write the wrong arm.** → Each is compile-enforced (no
  `default`), each joins an existing `STRING` arm, and the SQL/`information_schema`/Arrow behavior
  is pinned by scenarios in the `sql-query-translation` delta.
- **`unify` is easy to miss and fails only at query time.** → It is called out as its own task with
  its own scenarios in `structured-query`; a missed edit makes every filter on an enum column a
  422, which the runtime verification would catch.
- **Domain immutability will be felt.** Adding one value means drop + re-add, losing the column's
  data. → Stated in the spec and the API description rather than hidden; in-place widening remains
  available as a later change that only adds behavior.
- **Enum ordering surprises.** `ORDER BY status` sorts by declared position, not alphabetically.
  → Spec'd as explicit behavior in two capabilities; it is also why declared order is preserved.
- **A quote in a value breaks the `CREATE`.** → Single-quote escaping plus a dedicated test with an
  apostrophe value; length and count caps bound anything unanticipated.
- **`column_mapping_aud` drifts from `column_mapping`.** → One migration file adds the column to
  both; a revision-snapshot read of an enum column is a task and part of runtime verification.
- **Rename loses the domain.** `CatalogSchemaWriter.renameColumn` is a delete-then-insert copying
  fields one by one, so a missed field silently drops data. → Explicitly copied, with a spec
  scenario and a test.

## Migration Plan

`V33__column_enum_values.sql`. V33 is the next free slot, verified two ways: the default branch
tops out at V32, and no remote branch claims a higher number. It adds a nullable
`enum_values text[]` to `column_mapping` and to `column_mapping_aud`. Both are pure
`ADD COLUMN … NULL`:

- **Forward**: no backfill — every existing column reads back `null`, which is exactly "not an enum
  column", i.e. today's behavior.
- **Rollback**: the previous application version ignores the column, which can be left in place. No
  data is rewritten, and the migration issues no ClickHouse DDL.
