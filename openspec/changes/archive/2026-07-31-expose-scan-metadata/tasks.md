## 1. Service boundary

- [x] 1.1 `DefineSchemaInput`: add `identityColumn`/`versionColumn`, documenting that they are optional, source-only, and that an omitted member leaves the stored value unchanged
- [x] 1.2 `CatalogSchemaWriter.persistSourceSchema`: validate each supplied name is among the request's declared columns (422 naming it), then persist it; leave the stored value untouched when omitted
- [x] 1.3 `TableSchemaCreationService.requireComplete`: reject either member for an `enrichment` with a 422 explaining that the scan cursor is a source concept

## 2. Web surface

- [x] 2.1 `DefineSchemaRequest`: add optional `identity_column`/`version_column` with schema descriptions that state the caller's promise (monotonic version, unique identity) and that only membership is checked
- [x] 2.2 `TableDto`: add `identity_column`/`version_column`, absent when the definition has none
- [x] 2.3 `TableMapper`: map both into `DefineSchemaInput` and out of the definition on `toDto`/`toListItemDto`

## 3. Tests

- [x] 3.1 `CatalogSchemaWriterTest`: a supplied pair is persisted; an omitted pair leaves the stored value; a name absent from the declared columns is a 422
- [x] 3.2 `TableSchemaCreationServiceTest`: either member on an enrichment is a 422 and nothing is persisted
- [x] 3.3 `TableManagementIntegrationTest`: define a source with both, assert `GET /v1/tables/{name}` and the list item carry them; a bad name is 422 leaving the table `PENDING`; an enrichment supplying either is 422
- [x] 3.4 `TableManagementIntegrationTest`: `GET /v1/tables/dial_usage_log` reports the migration-seeded `event_id`/`_ingested_at`, and a source defined without the pair omits both fields
- [x] 3.5 `EnrichmentScanIntegrationTest`: a source whose schema declared the pair is scannable through `POST /v1/rules/{id}/scan` with no migration

## 4. Documentation and specs

- [x] 4.1 Update `openspec/project.md`'s table-management paragraph: the pair is caller-declarable at schema-define and returned on the read surface, and the promise it carries
- [x] 4.2 Confirm the delta specs match what shipped, and that nothing outside `table-management`/`enrichment-runtime` needed changing
- [x] 4.3 Run `openspec validate expose-scan-metadata` and `./gradlew clean build` and make both pass

## 5. Follow-on work inside this change

- [x] 5.1 `TableSchemaCreationService.requireComplete`: require BOTH members for a source (422 naming the
      missing field), and allow an `upsert_by_key` source to name the engine version `_updated_at`
- [x] 5.2 `TableSchemaService.requireMutable`: reject a drop or rename of the column either member names
- [x] 5.3 Update the shared integration fixtures and the delta specs for both, and note in `design.md`
      (D5/D6) that the requirement shipped without the type gate D3 had parked
