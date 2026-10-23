## 1. Physical columns

- [x] 1.1 Add `src/main/resources/db/migration/clickhouse/V15__dial_usage_log_route.sql`. It runs a single
  `ALTER TABLE dial_usage_log` with `ADD COLUMN IF NOT EXISTS route_name LowCardinality(Nullable(String))`
  and `ADD COLUMN IF NOT EXISTS route_type LowCardinality(Nullable(String))`, appended in that order.
  - The header is one line that points at the `data-sync` spec. The rationale (nullable strings rather
    than an enum, metadata-only cost, the ordering invariant with the mapping) lives in the spec and
    `design.md`, not in the checksummed file.
- [x] 1.2 Confirm that no in-flight branch claims ClickHouse `V15` or PostgreSQL `V49` before the files
  are committed.

## 2. Catalog registration

- [x] 2.1 Add `src/main/resources/db/migration/postgres/V49__seed_route_columns.sql`, modelled on
  `V29__seed_assembled_response_column.sql`.
  - It is one `INSERT INTO column_mapping (table_name, source_name, name, type, element_type, nullable,
    description, display_name, tag, sensitive, heavy, ordinal)` with two rows. Both rows are `'string'`,
    `nullable TRUE`, tag `'deployment'`, `sensitive FALSE` and `heavy FALSE`, with display names
    `Route name` and `Route type`.
  - The ordinals are `39` and `40`. Recheck that they come after the current maximum, including the gap
    that `V38` left at 38.
  - The descriptions say when the value is NULL and that pre-existing rows read NULL. For `route_name`,
    the description says it is unique only together with `route_type` and, for application routes,
    `deployment`. For `route_type`, it gives the current values `global` and `application` and says that
    other values are stored as reported.
  - The header is one line. The ordinal choice and the reason `deployment` needs no `tag_order` change
    (the tag is already listed in `V10`) are recorded in `design.md`.

## 3. Mapping

- [x] 3.1 In `src/main/resources/mapping/dial-usage-log.yaml`, append `route_name` and `route_type` after
  `response_body_bytes`. Each entry is `source: <same name>`, `kind: string`, `nullable: true`. Update the
  header comment wherever it enumerates or counts the columns.

## 4. Tests

- [x] 4.1 `MappingFileLoaderTest`: change the expected column count from 33 to 35, and change the
  last-column assertion to `route_type`.
- [x] 4.2 `MappingEngineTest`:
  - Leave the `SAMPLE` fixture unchanged. It is a chat completion, which no route serves, so it is the
    absent-pair case as it stands.
  - Add `carriesTheReportedRouteAndNullWhenNoRouteServedTheRequest`. It derives the route variants from
    `SAMPLE` and covers:
    - an absent pair (both NULL);
    - a global route;
    - an application route;
    - a single reported field (the other NULL);
    - an unrecognized `route_type`, stored verbatim;
    - `event_kind` unaffected by the route columns.
  - Extend `writesOnlyDeclaredColumnsInOrder` with the two columns, in order.
- [x] 4.3 `SchemaValidatorTest.liveSchema()`: add the two `LowCardinality(Nullable(String))` columns.
- [x] 4.4 `DialUsageLogTestModel`: add the two catalog columns (string, nullable, tag `deployment`).
- [x] 4.5 `QuerySchemaDiscoveryIntegrationTest` and `TableManagementIntegrationTest`: increase the
  `dial_usage_log` column, description and display-name counts by two. Assert that both columns are
  reported under tag `deployment`. A new `seededRouteColumnsAreNullableDeploymentAttributes` checks the
  `/v1/tables` type, nullable, tag, sensitive, heavy and display name.
- [x] 4.6 Add a new `RouteColumnsIngestIntegrationTest`, because no existing IT asserts ingested column
  values. Through the poller path, the same one `IngestLedgerIntegrationTest` uses, ingest a global-route
  record, an application-route record and a non-route record. Read back `route_name` and `route_type`
  from ClickHouse and check them against the spec scenarios. Use invented fixture values.
- [x] 4.7 `local_env/seed/seed_dial_usage_log.py`: emit both keys in the row dict. The seed generates
  only model and embedding calls, so both keys are `None`.

## 5. Docs

- [x] 5.1 `docs/column-tags.md`: add `route_name` and `route_type` to the `deployment` tag row.
- [x] 5.2 Confirm that no other doc needs an edit:
  - `docs/configuration.md`: no property is added.
  - `docs/heavy-columns.md`: the columns are not heavy.
  - `docs/recommended-catalog/adas-catalog.json`: it lists no `dial_usage_log` columns.

## 6. Verify

- [x] 6.1 Run `openspec validate add-route-columns --strict`.
- [x] 6.2 Run the public-repo hygiene greps from `CLAUDE.md` (the PCRE line pass and the multiline pass)
  over the new and changed files.
- [x] 6.3 Run `./gradlew clean build` and confirm that compile, checkstyle and tests pass. The
  integration contexts must also log that the `dial_usage_log` mapping reconciled with 35 columns.
