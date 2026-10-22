## 1. Migration

- [x] 1.1 Confirm the default branch still tops out at `V24`, so this change takes `V25`
- [x] 1.2 Write `src/main/resources/db/migration/postgres/V25__denormalize_table_catalog.sql` adding
      `grain_key TEXT` and `ordering_key TEXT[] NOT NULL DEFAULT '{}'` to `table_definition` —
      **no `cardinality` column**, it is derived
- [x] 1.3 Backfill the grain: `UPDATE table_definition d SET grain_key = g.grain_key
      FROM enrichment_grain g WHERE g.table_name = d.name`
- [x] 1.4 Backfill the ordering key with `array_agg(column_name ORDER BY ordinal)` grouped by
      `table_name` — **the `ORDER BY` inside the aggregate is load-bearing**; without it PostgreSQL
      gives no ordering guarantee and the MergeTree `ORDER BY` is silently permuted
- [x] 1.5 Add the check `grain_key IS NULL OR type = 'ENRICHMENT'`
- [x] 1.6 `DROP TABLE enrichment_grain` and `DROP TABLE table_ordering_key`
- [x] 1.7 Verify the seeded `dial_usage_log` and `rate_analytics` rows migrate correctly — they are
      the only catalog rows that exist in a fresh database, and both carry ordering keys

## 2. Entity and mapping

- [x] 2.1 Add a `grainKey` field to `TableDefinitionEntity` — no `cardinality` field — with javadoc
      explaining that it is null on a `PENDING` enrichment and always null on a source
- [x] 2.2 Replace the `orderingKey` `@ElementCollection` / `@CollectionTable` / `@OrderColumn` mapping
      with `@JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]")`, keeping the field
      type `List<String>` and its empty-list default — copy the pattern already used by `tagOrder` on
      this same entity
- [x] 2.3 Delete `EnrichmentGrainEntity` and `EnrichmentGrainRepository`
- [x] 2.4 Change `TableView` from `(definition, columns, grain)` to `(definition, columns)`

## 3. Call sites

- [x] 3.1 `TableCatalogService` — drop the `grains` repository dependency; `view()` no longer fetches
      the grain, and `delete()` no longer needs to consider it
- [x] 3.2 `CatalogSchemaWriter` — write `grainKey` onto the definition instead of building and saving
      an `EnrichmentGrainEntity`; **keep** the `only zero_or_one cardinality is supported` rejection at
      line 120, which is input validation and is unaffected by dropping the storage
- [x] 3.3 `TableSchemaService` — read the grain off the definition; the grain-key immutability check
      over a source's enrichments (`findBySourceTable` then a per-enrichment grain lookup) collapses
      into a single query
- [x] 3.4 `TableSchemaCreationService`, `TableDataService`, `CatalogQueryService` — read the grain off
      the definition
- [x] 3.5 `TableMapper:258` — build `GrainDto` from the definition, emitting
      `Cardinality.ZERO_OR_ONE` whenever `grainKey != null` and a null `GrainDto` otherwise; confirm the
      emitted JSON is byte-identical, including the lowercase `zero_or_one` wire form
- [x] 3.6 `RuleService` and `RuleGovernanceValidator` — `grainKeyOf(rule)` and the group-trigger check
      resolve the grain from the target enrichment's definition instead of the grain repository
- [x] 3.7 Update `RuleEntity`'s javadoc, which names `EnrichmentGrainEntity` as where the grain lives
- [x] 3.8 Review **every** touched call site for the `Optional.empty()` vs `null` conflation: code that
      treated "no grain row" as "not an enrichment" must now test the type explicitly

## 4. Tests

- [x] 4.1 Update the tests that construct `EnrichmentGrainEntity` or a three-arg `TableView` — these
      are construction-only edits; **no assertion about behavior may change**
- [x] 4.2 Migration test against Testcontainers PostgreSQL seeded with realistic rows: a source with a
      multi-column ordering key, an `ACTIVE` enrichment with a grain, and a `PENDING` enrichment
      without one — assert the ordering key survives **in order** and the grain lands on the right row
- [x] 4.3 Test that the new check rejects a grain key on a `SOURCE` row
- [x] 4.4 Test that `GET /v1/tables/{name}` on an enrichment still returns
      `grain: {grain_key, cardinality: "zero_or_one"}`, and that `POST .../schema` with an explicit
      `cardinality` other than `zero_or_one` is still rejected — the derivation must be invisible in
      both directions
- [x] 4.5 Confirm the existing table, query, enrichment, and Flight SQL suites pass **unmodified**
      apart from 4.1's construction edits — an edit needed in a behavior-asserting test is a defect in
      this change, not a stale test
- [x] 4.6 Assert `GET /v1/tables/{name}` returns an `ordering_key` and grain byte-identical to the
      pre-change response for a seeded table

## 5. Spec and documentation

- [x] 5.1 Confirm the delta in `openspec/changes/denormalize-table-catalog/specs/enrichment-runtime/`
      matches the shipped wording
- [x] 5.2 Update the `context:` section of `openspec/config.yaml` where it describes the catalog's
      table layout — **no change needed**: `config.yaml` names no catalog table. `grep -rn
      'EnrichmentGrain\|enrichment_grain' openspec/specs/` returns exactly two live references, both
      now handled: `openspec/specs/enrichment-runtime/spec.md:117` (normative — 5.1's delta rewords
      it) and `openspec/specs/query-schema-discovery/spec.md:289` (see 5.2a)
- [x] 5.2a Drop `EnrichmentGrainRepository` from the `CatalogQueryService` repository list in
      `openspec/specs/query-schema-discovery/spec.md`'s Implementation notes — the class is deleted by
      2.3 and `CatalogQueryService` now holds only `TableDefinitionRepository` and
      `ColumnMappingRepository`. Edited in the shipped spec directly rather than via a delta: the text
      sits under `## Implementation notes`, and OpenSpec rejects a delta file with no
      `## ADDED/MODIFIED/REMOVED/RENAMED Requirements` section ("move non-delta notes outside
      `specs/`"). No requirement in that capability mentions the grain, so there is nothing for a
      delta to modify
- [x] 5.3 Confirm `docs/configuration.md` and `README.md` need no change and state so explicitly
- [x] 5.4 Run `./gradlew clean build` and make it pass

## 6. Hand-off to the audit change

- [ ] 6.1 Rebase `worktree-audit-tables-and-rules` onto this branch once merged
- [ ] 6.2 Renumber the audit migration from `V25` to `V26`
- [ ] 6.3 Shrink the audit change: drop `enrichment_grain_aud` and `table_ordering_key_aud` from its
      migration, drop the `EnrichmentGrain` activity resource type and its mapper case and tests, and
      drop `grains.deleteById(name)` from its cascade-delete fix (the grain is now a column and goes
      with the row)
