## 1. Catalog migration

- [x] 1.1 Add `src/main/resources/db/migration/postgres/V19__table_write_facet.sql`: add
  `write_mode TEXT NOT NULL DEFAULT 'APPEND' CHECK (write_mode IN ('APPEND','UPSERT_BY_KEY'))` to
  `table_definition`, with a header comment explaining that the facet selects the ClickHouse engine
  and is therefore immutable
- [x] 1.2 In the same migration, defensively `DELETE FROM table_definition WHERE type = 'AGGREGATION'`
  (an inert shell holds no ClickHouse table), then drop and re-add the `type` CHECK narrowed to
  `('SOURCE','ENRICHMENT')` so a dirty catalog cannot fail the constraint at startup

## 2. Domain model

- [x] 2.1 Add `service/table/TableWriteMode.java` (`APPEND("append")`, `UPSERT_BY_KEY("upsert_by_key")`),
  following the wire-value enum convention used by `TableType`/`Cardinality`
- [x] 2.2 Remove the `AGGREGATION` value from `service/table/TableType.java` and rewrite its javadoc
  (aggregation is a pipeline kind whose output is an ordinary `source`)
- [x] 2.3 Add the `writeMode` field to `service/table/TableDefinitionEntity.java`
  (`@Enumerated(EnumType.STRING)`, non-null, defaulting to `APPEND`)
- [x] 2.4 Add `write` to `service/table/CreateTableInput.java` and surface it on
  `service/table/TableView.java` and `service/table/TableSummary.java`

## 3. Create-time validation and persistence

- [x] 3.1 In `service/table/TableCatalogService.java`, default an absent `write` to `APPEND`, persist it,
  and reject a `write` member on an `enrichment` create with 422 (an enrichment's discipline is implied
  by its kind)
- [x] 3.2 Reject any attempt to change `write` through `TableSchemaService` (`PATCH …/schema`) or the
  `PUT /v1/tables/{name}` metadata update with 422, keeping the facet immutable
- [x] 3.3 Confirm `service/table/TableColumnRules.java` rejects a caller-declared `_updated_at` on an
  `upsert_by_key` source through the existing reserved `_`-prefix rule — add a test rather than a new
  rule if it already holds

## 4. DDL rendering

- [x] 4.1 Add `write` to `TableDdlRenderer.CreateSpec` and select the engine from `(kind, write)`:
  `UPSERT_BY_KEY` source ⇒ `ReplacingMergeTree(_updated_at)` over the declared `ordering_key`,
  `APPEND` source ⇒ today's plain `MergeTree`, enrichment unchanged
- [x] 4.2 Add the `_updated_at DateTime64(3) DEFAULT now64(3)` column for an `UPSERT_BY_KEY` source,
  reusing the existing `UPDATED_AT` / `UPDATED_AT_TYPE` constants (one version-column definition for
  both callers) and update the class javadoc's engine-selection paragraph
- [x] 4.3 Thread `write` from the definition into the `CreateSpec` in
  `service/table/TableSchemaCreationService.java`

## 5. Web layer

- [x] 5.1 Add the optional `write` member to `web/dto/table/CreateTableRequest.java` with an
  `@Schema` description and add `write` to `web/dto/table/TableDto.java` (and the list item shape)
- [x] 5.2 Map the facet in both directions in `web/mapper/table/TableMapper.java`
  (wire `write` ↔ stored `write_mode`)

## 6. Tests

- [x] 6.1 `TableDdlRendererTest`: an `upsert_by_key` source renders
  `ENGINE = ReplacingMergeTree(_updated_at) ORDER BY (…)` with the version column, and an `append`
  source renders byte-identical DDL to before the change
- [x] 6.2 `TableCatalogServiceTest`: `write` defaults to `append`; an explicit `upsert_by_key` round-trips;
  `write` on an enrichment create is 422; replace the existing `TableType.AGGREGATION` usage (line ~154)
- [x] 6.3 Immutability tests: `PATCH …/schema` and `PUT /v1/tables/{name}` attempting to change `write`
  are 422 (`TableSchemaServiceTest` / controller slice)
- [x] 6.4 Row-write test: a row naming `_updated_at` on an `upsert_by_key` source is 422, and the column
  appears in neither `columns` nor `column_count`
- [x] 6.5 Integration test (ClickHouse container): write the same `ordering_key` twice into an
  `upsert_by_key` source with different values and assert a query returns one row carrying the later
  values (the `final = 1` guarantee), plus that the source is listed by `GET /v1/queries/entities`
- [x] 6.6 Fix the remaining `TableType.AGGREGATION` compile references across
  `CatalogQueryServiceTest`, `CatalogSchemaWriterTest`, `TableColumnRulesTest`,
  `TableSchemaCreationServiceTest`, `FlightSqlIntegrationTest` as the compiler surfaces them
- [x] 6.7 Sweep the non-test `AGGREGATION`/"aggregation" mentions in javadoc for staleness —
  `service/query/StructuredQueryService.java`, `service/query/CatalogQueryService.java`,
  `service/enrichment/validation/RuleGovernanceValidator.java` (its "an aggregating rule is an
  aggregation" wording is about the concept and stays correct)

## 7. Specs and docs

- [x] 7.1 Update `openspec/project.md`: the engine sentence (engine now selected by `(type, write)`)
  and the operational-constraints line asserting that "enrichment and aggregation tables are not
  listed as queryable entities"
- [x] 7.2 Fix the stale `Purpose` prose (not requirement text, so not covered by the delta specs) in
  `openspec/specs/query-schema-discovery/spec.md` and `openspec/specs/table-management/spec.md` where
  it names the aggregation table type
- [x] 7.3 Run `openspec validate align-table-write-facet` and `./gradlew clean build` (with the
  Docker socket env vars for the container tests) and confirm both pass
