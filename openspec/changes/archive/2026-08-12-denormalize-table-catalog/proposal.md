# Denormalize the enrichment grain and ordering key onto the table definition

## Why

Two of the table catalog's four tables carry no independent identity. `enrichment_grain`'s primary
key **is** `table_name` — a strict 1:1 with `table_definition` holding two columns — and
`EnrichmentGrainRepository` declares no query methods at all: every access in the codebase is
`findById(tableName)`. `table_ordering_key` is an ordered list of column names, always read whole,
never queried independently, and already surfaced in Java as a `List<String>`.

Neither earns a table. The cost of the split is paid at every call site — a second repository
round-trip to answer "what is this table's grain", and a join to answer "what is its ordering key" —
and it will shortly be paid twice over: the audit trail being designed alongside this change would
need an `_aud` mirror and a distinct activity resource type for each of them, freezing that split
into a published wire contract.

## What Changes

- **`enrichment_grain` folds into `table_definition`** as a single nullable column, `grain_key`. The
  table is dropped, along with `EnrichmentGrainEntity` and its repository.
- **`cardinality` stops being stored.** The column holds one legal value on every row that has one —
  `CatalogSchemaWriter` hard-codes `ZERO_OR_ONE` and rejects anything else at the door — so persisting
  it stores no information. It becomes a constant derived from `grain_key != null` at the mapper. The
  `Cardinality` enum, the request fields, and the response member all stay exactly as they are; only
  the storage goes.
- **`table_ordering_key` folds into `table_definition.ordering_key`** as a `text[]`, mirroring how
  `tag_order` already works on that same row. The table is dropped and the `@ElementCollection` /
  `@CollectionTable` / `@OrderColumn` mapping becomes `@JdbcTypeCode(SqlTypes.ARRAY)`. The Java-facing
  type stays `List<String>`, so callers, the DDL renderer, and the DTO mappers are untouched.
- **`column_mapping` stays a table.** It has genuine per-row identity, and two uniqueness constraints
  the database enforces today — `PRIMARY KEY (table_name, source_name)` and
  `UNIQUE (table_name, name)` — that no array or JSON document can express. Folding it would also turn
  every per-column `PATCH .../schema` into a read-modify-write over one document, which is a
  lost-update hazard on a schema whose concurrent-edit story is already last-writer-wins.
- **A new invariant becomes expressible.** `enrichment_grain` was keyed to `table_definition` by a
  foreign key that could not see the row's `type`, so nothing stopped a grain row existing for a
  `SOURCE`. As a column it gets `CHECK (grain_key IS NULL OR type = 'ENRICHMENT')`.
- **`TableView` loses its `grain` component**, becoming `(definition, columns)`; the grain is read off
  the definition.
- **No API change.** `TableDto` already exposes `ordering_key` as a flat list and the grain as a
  nested object; both are unaffected by where the data is stored. No request or response changes
  shape, and no validation or authorization rule changes.

Explicitly **not** in this change:

- **No change to `column_mapping`**, per the above.
- **No behavior change of any kind.** If a test asserting API behavior needs editing, that is a
  signal the change went wrong, not that the test was stale.
- **No optimistic locking.** The catalog's concurrent-edit weakness (last-writer-wins on the JSONB
  `access` column) is untouched and still tracked separately.

## Capabilities

### Modified Capabilities

- `enrichment-runtime`: the scan endpoint's requirement names the physical `enrichment_grain` record
  as the place a rule's grain is resolved from. That record ceases to exist, so the requirement is
  reworded to name the target enrichment's catalog row instead. Behavior is unchanged.

## Impact

- **Migration** `V25__denormalize_table_catalog.sql`: adds the two columns, backfills them from the
  two tables (`array_agg(column_name ORDER BY ordinal)` for the key), adds the check, drops both
  tables. Forward-only; the backfill is a straight projection with no ambiguity.
- **Removed code**: `EnrichmentGrainEntity`, `EnrichmentGrainRepository`.
- **Touched code** (14 main, 7 test files): `TableDefinitionEntity`, `TableView`, `TableMapper`,
  `TableCatalogService`, `CatalogSchemaWriter`, `TableSchemaService`, `TableSchemaCreationService`,
  `TableDataService`, `CatalogQueryService`, plus the enrichment readers `RuleService`,
  `RuleGovernanceValidator`, and `RuleEntity`'s javadoc. Most edits collapse a
  `grains.findById(x).map(EnrichmentGrainEntity::getGrainKey)` into `definition.getGrainKey()`,
  deleting a repository round-trip at each site.
- **Unchanged**: `Cardinality`, `GrainDto`, `DefineSchemaRequest`, `CreateTableRequest`,
  `PatchSchemaRequest`, and `CatalogSchemaWriter`'s "only zero_or_one cardinality is supported"
  rejection. The value is derived rather than read back, which no caller can observe.
- **Sequencing**: the in-flight `audit-tables-and-rules` change rebases onto this one and shrinks —
  from four `_aud` tables to two, and from three activity resource types (`Table`, `TableColumn`,
  `EnrichmentGrain`) to two. Its migration renumbers from `V25` to `V26`. Doing the denormalization
  second would instead mean removing a resource type from an already-published audit contract.
- **Docs**: no `docs/configuration.md` change (no env vars). The `context:` section of
  `openspec/config.yaml` describes the catalog's table layout and is updated.
