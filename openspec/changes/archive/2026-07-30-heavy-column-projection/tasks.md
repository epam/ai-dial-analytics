## 1. Catalog metadata (Postgres + service layer)

- [x] 1.1 Add `src/main/resources/db/migration/postgres/V18__add_heavy_columns.sql`:
      `ALTER TABLE column_mapping ADD COLUMN heavy BOOLEAN NOT NULL DEFAULT FALSE;` with a header
      comment stating it is catalog-only metadata (no ClickHouse DDL), plus
      `UPDATE column_mapping SET heavy = TRUE WHERE table_name = 'dial_usage_log' AND source_name IN
      ('request_body', 'response_body');`
- [x] 1.2 Add the `heavy` field to `ColumnMappingEntity` with a javadoc mirroring `sensitive` but
      stating it is a transfer-cost hint, not access control
- [x] 1.3 Add `heavy` to `ColumnInput` (definition/add) and to
      `PatchInput.ColumnMetadataUpdate` as a `Change<Boolean>`; update both javadocs
- [x] 1.4 Thread `heavy` through `CatalogSchemaWriter`: its `ColumnMetadataChange` record, the
      metadata merge-patch application, the rename-preservation copy, and the entity-from-`ColumnInput`
      construction
- [x] 1.5 Thread `heavy` through `TableSchemaService`'s metadata-update record and its mapping to
      `CatalogSchemaWriter`
- [x] 1.6 Confirmed `TableColumnRules` needs no new validation: a boolean metadata flag is unconstrained
      like `sensitive`, and is settable on system/grain/ordering-key columns (covered by
      `heavyFlagIsSettableOnAnOrderingKeyColumn`)

## 2. Web API surface

- [x] 2.1 Add optional `heavy` (`Boolean`, OpenAPI-described, defaults false when omitted) to the
      column request record used by `POST /v1/tables/{name}/schema` and `PATCH .../schema` `add`
      (`CreateTableRequest.ColumnRequest`) and to `PatchSchemaRequest`'s `update` entry record
- [x] 2.2 Add `heavy` to `TableDto`'s column record and map it in all three `TableMapper` sites
      (entity → DTO, request → `ColumnInput`, update → `Change`)
- [x] 2.3 Update the `PatchSchemaRequest` class javadoc's metadata enumeration to include `heavy`

## 3. Query model and discovery

- [x] 3.1 Add `heavy` to `QueryFieldBinding` (keep the existing convenience constructors working for
      the scalar/non-heavy case) and set it in `CatalogQueryService#addColumn`
- [x] 3.2 Add `heavy` to `QuerySchemaFieldDto` and populate it in `CatalogQueryService#addColumn`;
      the flag is NOT role-filtered (a heavy column stays in the model for every caller)
- [x] 3.3 Update `service/query/DialUsageLogTestModel` and any other test fixture that constructs
      bindings/schema fields so the new component is exercised (at least one heavy column)

## 4. Wildcard projection (single source of truth)

- [x] 4.1 Add `service/query/WildcardProjection` with the single derivation
      `sourceFields(QueryEntityModel)` — the entity's non-heavy, non-enrichment fields in catalog order
      — documenting that it is the one rule behind every wildcard on every surface
- [x] 4.2 `StructuredQueryBuilder#buildRow`: expand an omitted/empty row-mode `select` through
      `sourceFields`, and throw `QueryValidationException` naming the entity when the expansion is
      empty
- [x] 4.3 `StructuredQueryToSqlRenderer`: route `bareSelectList` and `bareProjectionNames` through the
      same helper, so a rendered bare projection names only non-heavy columns and a heavy
      array/object column no longer trips the projection-only guard

## 5. SQL star expansion

- [x] 5.1 Extend `CalciteQueryCatalog.Snapshot` with the per-entity star field list (built from
      `WildcardProjection#sourceFields` — non-heavy source columns only), keeping the existing
      type/element-type maps and the full flat row type unchanged, so heavy and enrichment columns stay
      resolvable when named explicitly
- [x] 5.2 Add `service/query/sql/SqlStarExpander`: rewrite top-level star select items (`*` and
      `<alias>.*`) of the parsed statement into bare `SqlIdentifier`s for that field list — dropping
      heavy columns and (BREAKING) enrichment columns; resolve the entity from a single-table `FROM`
      (unwrapping an `AS` alias, case-insensitively); recurse into a derived-table `FROM`; leave every
      other `FROM` shape untouched; never touch a function-star such as `COUNT(*)`; throw
      `SqlTranslationException` naming the entity when the expansion is empty
- [x] 5.3 Wire the expander into `SqlQueryFrontEnd#translate` between `planner.parse` and
      `SqlSelectAliasInjector.injectAliases`, and document the ordering requirement in the javadoc

## 6. Documentation

- [x] 6.1 Add `docs/heavy-columns.md`: what `heavy` means, that it is catalog-only and not access
      control, how to set/clear it (schema define + `PATCH` `update`), what a wildcard does on each
      surface (DSL empty `select`, REST SQL / Flight SQL `SELECT *`), how to still get the column (name
      it), how to discover it (`GET /v1/queries/entities/schema/{name}`,
      `POST /v1/queries/translate-sql`), and the seeded `dial_usage_log` payload columns; state
      explicitly that a wildcard now expands to the entity's non-heavy **source** columns only — no
      enrichment columns, on either surface — and how to select an enrichment column by its quoted
      qualified name
- [x] 6.2 Cross-link the new page from `docs/column-tags.md` (both are column metadata) and add one
      sentence with a link in the README's Arrow Flight SQL section for the external-client audience
      (no env-var tables outside `docs/configuration.md`; this change adds no configuration)

## 7. Tests

- [x] 7.1 `service/table/CatalogSchemaWriterTest` + `TableSchemaServiceTest`: `heavy` set at
      definition, toggled by `update` (true → false), preserved across a `rename`, and issuing no
      `ALTER` for a metadata-only patch
- [x] 7.2 `service/query/CatalogQueryServiceTest`: the flag reaches the binding and the schema field,
      and is present for a non-`FULL_ADMIN` caller (not role-filtered)
- [x] 7.3 `service/query/translate/DialUsageLogQueryRenderTest`: an empty row-mode `select` renders
      without the heavy column; an explicit select of it renders normally; an all-heavy entity is
      rejected
- [x] 7.4 `service/query/sql/SqlQueryFrontEndTest` (offline): `SELECT *` omits heavy fields; `SELECT *`
      omits enrichment fields and joins no enrichment; `t.*` behaves the same; an explicitly named heavy
      or quoted enrichment column is kept; heavy usable in `WHERE`/`ORDER BY`; `count(*)` unaffected;
      derived-table star; all-heavy star rejected with the entity named; result-column names unchanged
      for the expanded list
- [x] 7.5 `service/query/sql/StructuredQueryToSqlRendererTest`: an empty DSL `select` renders to a
      statement that translates back to the same non-heavy source projection (round-trip), including the
      enrichment case where the renderer emits an explicit list
- [x] 7.6 `web/controller/query/QuerySchemaControllerTest` + `web/controller/query/…` table slices:
      the `heavy` member is serialized on schema fields and table columns
- [x] 7.7 `integration/TableManagementIntegrationTest`: declare a heavy column, patch it, read it back
      through `GET /v1/tables/{name}`
- [x] 7.8 `integration/SqlQueryExecutionIntegrationTest`: `SELECT *` over a source with a heavy column
      omits it while the explicit projection returns it, and `SELECT *` returns the same columns as the
      empty-`select` DSL document for a source **with an enrichment attached** (no enrichment column in
      either) while the quoted qualified name still returns it
- [x] 7.9 `integration/FlightSqlIntegrationTest` (and/or `EnrichmentJoinIntegrationTest` for the join
      assertion): `getFlightInfo` for `SELECT *` advertises the non-heavy source column set with no
      enrichment field, and the `doGet` schema matches it field-for-field
- [x] 7.10 `integration/QuerySchemaDiscoveryIntegrationTest`: the seeded `dial_usage_log`
      `request_body`/`response_body` fields report `heavy: true` after migration

## 8. Verification

- [x] 8.1 `./gradlew clean build` passes (compile + checkstyle + tests)
- [x] 8.2 Confirm the delta specs in `openspec/changes/heavy-column-projection/specs/` match the
      shipped behavior, and `openspec validate heavy-column-projection` passes
