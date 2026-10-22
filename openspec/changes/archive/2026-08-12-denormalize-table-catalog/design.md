## Context

See `proposal.md` — Why. The constraints that shape the approach:

- The catalog is Spring Data JPA over PostgreSQL with `spring.jpa.hibernate.ddl-auto: none`; Flyway
  owns the schema (`classpath:db/migration/postgres`, currently through `V24` on the default branch).
- `enrichment_grain` is `PRIMARY KEY (table_name) REFERENCES table_definition(name) ON DELETE CASCADE`
  plus `grain_key TEXT NOT NULL` and `cardinality TEXT NOT NULL CHECK (cardinality IN ('ZERO_OR_ONE'))`.
  `EnrichmentGrainRepository` declares no methods.
- `table_ordering_key` is `PRIMARY KEY (table_name, ordinal)` plus `column_name TEXT NOT NULL`, mapped
  as an `@ElementCollection` with `@OrderColumn` into `List<String> orderingKey`.
- `TableDefinitionEntity` already holds a `text[]` column — `tag_order`, via
  `@JdbcTypeCode(SqlTypes.ARRAY)` — so there is a working precedent for the array mapping on this
  exact entity.
- `TableDto` exposes `ordering_key` as a flat `List<String>` and the grain as a nested `GrainDto`, so
  neither is coupled to the physical layout.

## Goals / Non-Goals

**Goals:**

- Remove two tables that have no identity of their own, and the per-call-site round-trips they impose.
- Stop persisting `cardinality`, which has one legal value and therefore carries no information.
- Land it before the audit trail, so the split is never published as an audit resource type.
- Tighten the grain invariant that the split could not express.

**Non-Goals:**

- Touching `column_mapping` — see `proposal.md` for why it stays relational.
- Any behavior change. This is a refactor with one wording-level spec delta.
- Optimistic locking or any other fix to the catalog's concurrent-edit story.

## Decisions

### The grain becomes one nullable column, not a JSON object

`grain_key TEXT` on `table_definition`, not a `grain jsonb`.

*Why:* the grain key is a scalar compared against column names in several validators. A plain column
keeps the `CHECK` constraint, keeps the value greppable in a `psql` session, and costs nothing in
mapping. JSON would buy flexibility this field does not need. The row already models its optional
facets this way — `source_table`, `partition_column`/`partition_granularity`, and the TTL triple are
all nullable columns with paired checks.

### `cardinality` is derived, not stored

The column is dropped and not replaced. `Cardinality` has exactly one constant;
`CatalogSchemaWriter:120` rejects anything else as a `TableValidationException` and then writes the
literal `ZERO_OR_ONE` at line 141. The stored value is therefore a function of `grain_key IS NOT NULL`
and nothing else — it carries zero bits. `TableMapper` emits the constant when the definition has a
grain key, which is the same total function the database was computing the long way.

*Why not keep it for forward-compatibility:* the argument for storing it is that `MANY` might arrive
post-v1. But a column that today has one value would need a backfill decision the moment a second
value exists anyway, and adding a nullable column to a control-plane table with hundreds of rows is a
one-line migration. Storing a constant now buys nothing and costs a column, a `CHECK`, an entity
field, and a mapping on every read.

*What deliberately does not change:* the `Cardinality` enum, its `@JsonValue` lowercase wire form, the
`cardinality` member of `GrainDto`, the field on all three request DTOs, and the 422 rejections in
`CreateTableRequest`/`PatchSchemaRequest`. Callers still send it and still receive it. This keeps the
change's no-observable-API-change property intact and keeps the door open for `MANY` without a
contract revision — only the persistence of a value that was never in doubt goes away.

### The ordering key becomes `text[]`, matching `tag_order`

`ordering_key TEXT[] NOT NULL DEFAULT '{}'`, mapped `@JdbcTypeCode(SqlTypes.ARRAY)`.

*Why:* an array is ordered, which is the only thing `@OrderColumn` was buying, and it makes the
`(table_name, ordinal)` uniqueness structurally impossible to violate rather than merely enforced.
`NOT NULL DEFAULT '{}'` preserves today's semantics exactly: a `PENDING` table has no ordering-key
rows and its entity field is an empty list, so empty array and no rows mean the same thing. The
Java-facing type is unchanged, which is what keeps `TableDdlRenderer`, the request DTOs, and
`TableMapper` out of this change entirely.

### The new check the separate table could not express

```sql
CHECK (grain_key IS NULL OR type = 'ENRICHMENT')
```

`enrichment_grain`'s foreign key pointed at `table_definition(name)` and could not see the row's
`type`, so nothing at the database level stopped a grain row for a `SOURCE`. As a column on the row
itself, the type is right there.

Note the deliberate looseness: the check is *not* `(grain_key IS NOT NULL) = (type = 'ENRICHMENT')`.
An enrichment is created as a grain-less shell at `POST /v1/tables` and gets its grain only at
`POST /v1/tables/{name}/schema`, so a `PENDING` enrichment legitimately has no grain. The check
constrains the direction that is always true and leaves the lifecycle-dependent direction to the
service layer, which already owns it.

### One forward-only migration, no compatibility window

`V25__denormalize_table_catalog.sql` adds the columns, backfills, adds the checks, and drops both
tables in one migration:

```sql
UPDATE table_definition d SET grain_key = g.grain_key
FROM enrichment_grain g WHERE g.table_name = d.name;

UPDATE table_definition d SET ordering_key = k.cols
FROM (SELECT table_name, array_agg(column_name ORDER BY ordinal) AS cols
      FROM table_ordering_key GROUP BY table_name) k
WHERE k.table_name = d.name;
```

*Why one migration and not an expand/contract pair:* ADAS deploys as a single replica of a control
plane, and Flyway runs before the application serves traffic, so there is no window in which old code
meets the new schema. A two-phase migration would add a release cycle to buy a guarantee nothing here
needs. The backfill is a total projection — `array_agg … ORDER BY ordinal` reproduces the list
exactly, and the 1:1 grain join cannot fan out — so it needs no reconciliation step.

*Rollback:* redeploying the prior version against the migrated schema does **not** work, because the
old code reads tables that no longer exist. Rollback is therefore a forward fix or a database restore.
This is worth stating plainly rather than implying reversibility: it is the one genuine risk in an
otherwise behavior-free change, and it is why the migration is verified against a Testcontainers
PostgreSQL seeded with realistic catalog rows before it ships.

### Migration numbering

This change takes `V25` — the next number after the default branch, which tops out at `V24`. The
in-flight `audit-tables-and-rules` branch currently claims `V25` and renumbers to `V26` when it rebases
onto this. Numbering against merged history only, and letting whoever merges second renumber, is the
project's standing rule; it keeps the applied sequence dense rather than leaving gaps reserved for
migrations that may never land.

## Risks / Trade-offs

- **The migration is not reversible by redeploying the old version.** → Stated above; verified against
  a seeded Testcontainers PostgreSQL, and the change is small enough to fix forward.
- **A silent behavior change hiding inside a "pure refactor".** The grain and ordering key are read by
  the enrichment path (`RuleService`, `RuleGovernanceValidator`) as well as the table path, and a
  `findById` returning `Optional.empty()` behaves differently from a null column if a call site
  conflated "no grain row" with "not an enrichment". → Every touched call site is reviewed for that
  conflation specifically, and the existing enrichment and table test suites must pass **unmodified** —
  an edit needed in a behavior-asserting test is treated as a defect in this change, not a stale test.
- **`array_agg` ordering.** The backfill depends on `ORDER BY ordinal` inside the aggregate; without it
  PostgreSQL gives no ordering guarantee and the MergeTree `ORDER BY` would be silently permuted — a
  corruption that would surface only as wrong query plans much later. → Explicit in the SQL, and
  asserted by a migration test on a table with a multi-column ordering key.
- **Concurrent edits to `ordering_key` are now whole-array writes.** Previously two concurrent changes
  to different key positions were separate rows. → Not a regression in practice: the ordering key is
  immutable after materialization, and the entity already loaded and rewrote the whole collection.

## Migration Plan

1. `V25__denormalize_table_catalog.sql` — add columns, backfill, add the check, drop the two tables.
2. Entity and call-site changes in the same commit, since the schema and mapping must move together
   under `ddl-auto: none`.
3. `./gradlew clean build` with the existing suites unmodified.
4. Runtime verification against the local stack: create a source and an enrichment, define schemas,
   confirm `GET /v1/tables/{name}` returns byte-identical `ordering_key` and grain to the pre-change
   response, and confirm a rule scan still resolves its grain.

Rollback is forward-only, as described above.
