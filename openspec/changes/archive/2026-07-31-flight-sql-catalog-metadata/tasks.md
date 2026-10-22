# Tasks — Flight SQL catalog metadata RPCs

## 1. Catalog row building (`FlightCatalogStreams`)

- [x] 1.1 Add `web/flight/FlightCatalogStreams` taking `CatalogQueryService`, with one method per
      command returning a populated `VectorSchemaRoot` built on a caller-supplied `BufferAllocator`
      from the protocol's fixed schema (`FlightSqlProducer.Schemas.*`); vectors are looked up by the
      protocol's field names, never re-declared locally.
- [x] 1.2 `catalogs()` and `dbSchemas()` — build the protocol root and complete with zero rows
      (design D2).
- [x] 1.3 `tableTypes()` — one row, `TABLE` (design D4).
- [x] 1.4 `tables(CommandGetTables)` — one row per `queryableEntityNames()` entry with null
      `catalog_name`/`db_schema_name` and `table_type` `TABLE`; honor `catalog` (omitted = all, empty =
      all, non-empty = none), `db_schema_filter_pattern`, `table_name_filter_pattern` (SQL `LIKE`
      semantics: `%` → `.*`, `_` → `.`, everything else quoted; `\` escapes both wildcards) and
      `table_types` (empty = no filter).
- [x] 1.5 `include_schema` support — for each listed entity resolve `findModel`, take
      `WildcardProjection.sourceFields(model)`, build `ResultColumn`s from the bindings' declared
      types (reusing the same `QueryFieldType` → `ResultColumnKind` mapping the SQL translator uses;
      extract it to one shared place rather than copying the switch), render with `ArrowTypeMapper`,
      and serialize via `MessageSerializer.serialize(new WriteChannel(...), schema)` into
      `table_schema` (design D3). Empty expansion → empty schema, row still emitted.
- [x] 1.6 `xdbcTypeInfo(CommandGetXdbcTypeInfo)` — the eight rows of design D5, driven by an
      exhaustive `switch` over the scalar `ResultColumnKind` values so a new kind fails to compile;
      apply the `data_type` filter when present.
- [x] 1.7 Checkstyle-clean (Google style, 180 cols) and Javadoc on the class and each public method
      explaining what the rows mean, not what the code does.

## 2. Producer wiring (`AdasFlightSqlProducer`)

- [x] 2.1 Add `getStreamCatalogs`, `getStreamSchemas`, `getStreamTables`, `getStreamTableTypes`,
      `getStreamTypeInfo` overrides delegating to `FlightCatalogStreams`, each: acquire a bounded
      child allocator from `ArrowAllocators`, run the catalog read inside `asCaller(context, …)`,
      `listener.start`/`putNext`/`completed`, map failures through `FlightErrorMapper`, and close the
      allocator in `finally` — no `FlightStreamGate` acquisition (design D7, D8).
- [x] 2.2 Replace `determineEndpoints` with a whitelist over the request message type; anything not in
      `{CommandGetSqlInfo, CommandGetCatalogs, CommandGetDbSchemas, CommandGetTables,
      CommandGetTableTypes, CommandGetXdbcTypeInfo}` throws `CallStatus.UNIMPLEMENTED` naming the
      command (design D7).
- [x] 2.3 Reject a statement with no explicit `LIMIT` in `getFlightInfoStatement`: after
      `frontEnd.translate`, fail `INVALID_ARGUMENT` when `translation.query().page()` is null or is an
      `OffsetPage` with `limit == 0`, with the message from design D6. Apply the same check in
      `streamQuery` so a hand-forged ticket cannot bypass it.
- [x] 2.4 Update the class Javadoc: metadata RPCs are supported and sourced from the query catalog;
      prepared statements and key-metadata RPCs remain UNIMPLEMENTED; Flight requires an explicit
      `LIMIT`.

## 3. Tests

- [x] 3.1 Extend `integration/FlightSqlIntegrationTest` with the metadata happy paths over real gRPC:
      `getCatalogs`/`getSchemas` complete with zero rows; `getTableTypes` yields one `TABLE` row;
      `getTables(includeSchema=false)` lists exactly `GET /v1/queries/entities` in the same order with
      null catalog/schema; `getTables(includeSchema=true)` deserializes `table_schema` and asserts it
      equals the `getFlightInfo` schema for `SELECT * FROM dial_usage_log LIMIT 1` field-for-field.
- [x] 3.2 Filter tests: `table_name_filter_pattern` matching and non-matching, a non-empty `catalog`
      yielding zero rows, a `table_types` list without `TABLE` yielding zero rows.
- [x] 3.3 `getXdbcTypeInfo` — eight rows with the expected `type_name`/`data_type` pairs and
      `NULLABILITY_NULLABLE`; the `data_type`-filtered call yields one row.
- [x] 3.4 `getFlightInfo` for `CommandGetPrimaryKeys` (and one other key RPC) fails `UNIMPLEMENTED`.
- [x] 3.5 Rewrite `limitGovernanceMatchesRest` for the new contract: no `LIMIT` → `INVALID_ARGUMENT`
      whose message names the explicit-`LIMIT` requirement; `OFFSET` without `LIMIT` → same;
      `LIMIT 50000` → `INVALID_ARGUMENT`; `LIMIT 1000` and `LIMIT 7` honored. Keep a REST assertion
      showing `POST /v1/queries/execute-sql` still defaults to 100 for the same statement, so the
      Flight-only scope is pinned by a test.
- [x] 3.6 Add explicit `LIMIT` clauses to the existing Flight statements in
      `FlightSqlIntegrationTest`, `FlightClickHouseDownIntegrationTest`, and any other test issuing a
      Flight statement. Expect ~40 mechanical edits; keep each query's semantics unchanged (choose a
      limit above the row count the case asserts) and verify no case's assertions change meaning.
- [x] 3.7 New `integration/FlightMetadataAuthIntegrationTest` in `security.mode=oidc` with
      `flight.enabled=true`, modelled on `TableAuthIntegrationTest` (local HMAC tokens via
      `JwtTestTokens` + `TestSecurityConfig`, one `FULL_ADMIN` and one `READ_ONLY_ADMIN` provider),
      passing the bearer token as a Flight `HeaderCallOption`: assert the `READ_ONLY_ADMIN` caller's
      `getTables(includeSchema=true)` schema omits `jwt_claims` and `request_tags` while the
      `FULL_ADMIN` caller's carries both. This is the spec's sensitive-column scenario.

## 4. Specs and docs

- [x] 4.1 Confirm `openspec/changes/flight-sql-catalog-metadata/specs/flight-sql-endpoint/spec.md`
      matches what shipped; adjust it, not the code, if implementation revealed a better contract.
- [x] 4.2 Update the `Purpose` paragraph of `openspec/specs/flight-sql-endpoint/spec.md` at sync time:
      catalog metadata RPCs are supported; "statement-only v1" now means prepared statements, DML/DDL,
      transactions, key-metadata RPCs, and TLS.
- [x] 4.3 Extend the spec's `Implementation notes` with `FlightCatalogStreams` and the fact that
      catalog rows are a projection of `CatalogQueryService`.
- [x] 4.4 Document the client-facing contract in the Flight SQL section of the docs: no catalog/schema
      namespace, `table_schema` = star projection, explicit `LIMIT` required with the 1000-row ceiling,
      and the Arrow JVM flags needed when not launching via `./gradlew bootRun`. No new env vars, so
      `docs/configuration.md` needs no change — verify that is still true before closing this task.

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes (compile, checkstyle, all tests).
- [x] 5.2 Run against the local stack (`local_env` ClickHouse + Postgres, `./gradlew bootRun` with
      `FLIGHT_ENABLED=true`) and probe each catalog RPC with pyarrow the way the report did —
      `GetFlightInfo` then `DoGet` — confirming rows instead of `UNIMPLEMENTED`, plus `SELECT *` with
      no `LIMIT` failing with the new message. Record request → expected → actual per case.
- [x] 5.3 Note in the MR that the Power BI navigator and Dremio ODBC driver behaviour that motivated
      the change can only be re-confirmed on the reporter's machine.

## 6. GetSqlInfo namespace declaration (follow-up on the draft MR)

- [x] 6.1 `getStreamSqlInfo`: add `withSqlCatalogTerm("")`, `withSqlCatalogsSupportedActions()`,
      `withSqlSchemaTerm("")`, `withSqlSchemasSupportedActions()` (empty varargs). `withSqlDdl*` declares
      DDL support, not namespace existence, so without these a driver defaults to "schemas supported"
      and Power BI qualifies its generated SQL with the empty schema name.
- [x] 6.2 Spec: add the requirement "Capability advertisement declares no catalog and no schema
      namespace" and make the Purpose paragraph plus the zero-rows rationale reference it rather than
      assert the outcome.
- [x] 6.3 Test: assert the streamed `GetSqlInfo` values for both terms are empty and both
      supported-actions bitmasks are zero, and that the keys are present rather than omitted.
- [x] 6.4 Verify at runtime that the four values arrive over the wire.
