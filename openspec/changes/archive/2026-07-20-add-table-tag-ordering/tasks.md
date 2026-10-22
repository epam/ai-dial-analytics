## 1. Storage & entity

- [x] 1.1 Add Flyway migration `V10__add_table_tag_order.sql`:
      `ALTER TABLE table_definition ADD COLUMN tag_order TEXT[]` (nullable, NULL = not set)
- [x] 1.2 In the same migration, seed taxonomy orderings for `dial_usage_log` and
      `rate_analytics` via `UPDATE table_definition SET tag_order = ARRAY[...]`, using only tags
      each table's exposed columns actually carry after V7 (check V1/V2/V7 migrations), in order:
      identifier, dimension, principal, deployment, request, response, token-usage, cost,
      performance (the `system` role tag is on hidden `_`-prefixed columns, so it is omitted;
      rate_analytics gets only the subset it exposes)
- [x] 1.3 Add `List<String> tagOrder` to `TableDefinitionEntity` mapped to the `tag_order`
      `text[]` column via Hibernate native array mapping (`@JdbcTypeCode(SqlTypes.ARRAY)`);
      null when unset

## 2. Service layer

- [x] 2.1 Add tag-order validation to `TableColumnRules`: entries trimmed, non-blank, ≤64 chars
      (reuse `MAX_TAG_LENGTH`), unique after trimming, ≤100 entries; violations throw
      `TableValidationException` (422); no existence check against column tags
- [x] 2.2 Extend `PatchInput` with a table-level `Change<List<String>> tagOrder` (reusing the
      existing `Change<T>` record): `leave()` = unchanged, `set(non-empty)` = replace,
      `set(empty/null)` = clear
- [x] 2.3 Extend `TableSchemaService.patchSchema` to validate and apply `tagOrder` last (after the
      column `update`s), catalog-only (no ClickHouse DDL); confirm add/drop/rename/update paths
      never touch the stored ordering
- [x] 2.4 Add `CatalogSchemaWriter.setTagOrder(String tableName, List<String> tags)`
      (transactional single-column update; empty list stores NULL = cleared)
- [x] 2.5 Support `tag_order` at table create: validate in the create path and persist with the
      new definition
- [x] 2.6 Expose `tagOrder` on the read model (`TableView` / `TableCatalogService.get`) as
      null-when-unset

## 3. Web layer

- [x] 3.1 Add top-level `tag_order` (nullable `List<String>`) to `PatchSchemaRequest` and map it in
      `TableMapper.toPatchInput` to `Change<List<String>>` (`Change.leave()` when the member is
      null/absent, `Change.set(list)` when present) — `[]` → clear, non-empty → set
- [x] 3.2 Add optional `tag_order` to `CreateTableRequest` and map it in the create path
- [x] 3.3 Add `tag_order` (`@JsonProperty("tag_order")`, null when unset) to `TableDto` and
      populate it in `TableMapper.toDto`
- [x] 3.4 Add `tag_order` (null when unset) to `QueryEntitySchemaDto` and populate it in
      `CatalogQueryService` from the source table's ordering, verbatim, without role filtering
- [x] 3.5 Add a shared sort util (e.g. `TagOrdering`) implementing the tag-group comparator:
      listed-tag index → unlisted tags alphabetically → untagged last, alphabetical by exposed
      `name` within each group; parameterized by the `tag_order` list and a tag/name accessor
- [x] 3.6 Apply the shared sort to the discovery `fields` in `CatalogQueryService` (after
      sensitive filtering) and to the `columns` array in `TableMapper.toDto`; when no ordering is
      set, keep the existing ordinal order exactly on both surfaces. Presentation-only — never
      rewrite the stored column `ordinal`

## 4. Tests

- [x] 4.1 Unit: `CatalogSchemaWriterTest` — set, replace, clear tag order (clear stores NULL);
      ordering gone after table delete
- [x] 4.2 Unit: tag-order validation rules (blank entry, >64 chars, duplicates, >100 entries,
      non-existent tag accepted)
- [x] 4.3 Integration (`TableManagementIntegrationTest`): set/replace/clear via top-level
      `PATCH tag_order` (`[]` clears, absent/null no-op); `tag_order` returned by
      `GET /v1/tables/{name}`; `columns` sorted by the ordering when set (groups + alphabetical
      within group) and in ordinal order when not set; create with `tag_order`; ordering
      unchanged across update/add/rename churn; 422 cases (blank/over-long/duplicate/>100);
      system table PATCH still 409
- [x] 4.4 Integration (`QuerySchemaDiscoveryIntegrationTest`): schema response carries
      `tag_order` for seeded `dial_usage_log` (from V10 seed) with fields sorted per the seeded
      ordering (alphabetical within groups), `null` + unchanged ordinal order for a table
      without ordering, and the same `tag_order` for non-admin callers
- [x] 4.5 Unit: `CatalogQueryServiceTest` / `QuerySchemaControllerTest` — `tag_order` surfaced
      verbatim; field sorting: listed-tag groups in order, unlisted tags alphabetical after,
      untagged last, alphabetical within groups; no-ordering keeps ordinal order; sorting
      applied after sensitive filtering
- [x] 4.6 Unit: direct test of the shared `TagOrdering` comparator (listed/unlisted/untagged
      grouping, within-group alphabetical, stability, empty/absent ordering)

## 5. Docs & specs

- [x] 5.1 Update `docs/column-tags.md` with the tag-ordering section (server-side sorting rules:
      listed groups first in order, unlisted alphabetical after, untagged last, alphabetical by
      name within groups; no ordering = ordinal order)
- [x] 5.2 Verify delta specs under `openspec/changes/add-table-tag-ordering/specs/` still match
      shipped behavior; adjust if implementation details diverged
- [x] 5.3 Run `./gradlew clean build` and fix until green (checkstyle included)
