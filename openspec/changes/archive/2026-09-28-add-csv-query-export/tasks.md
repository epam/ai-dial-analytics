## 1. Result columns on the page

- [x] 1.1 Add `List<String> columns` to `QueryResultPage`; populate it in both `StructuredQueryExecutor` construction sites from the `select.getSelect()` names `materialize` keys rows by; update every other constructor call (main and test)
- [x] 1.2 Test: an executed DSL query and a composite SQL query with zero rows still report their projected column names in order

## 2. CSV writer

- [x] 2.1 Write `CsvResultWriterTest` first: header-only output for no rows, CRLF terminators, quoting of `,` `"` CR LF, `""` for the empty string vs empty unquoted field for null, number/boolean literals, array/object/map cells as compact JSON (via the primary `JsonMapper`), formula-like strings unchanged, UTF-8 without BOM
- [x] 2.2 Implement `CsvResultWriter` (web layer, RFC 4180, cell conversion by direct serialization through the primary `JsonMapper` — not `valueToTree`, which normalizes decimals; see design D3) until the tests pass

## 3. Endpoints

- [x] 3.1 Extract the execute-sql plan switch (metadata / translation / composite → rows + columns + totalCount) into a shared package-private helper used by `SqlQueryController`, keeping its JSON behaviour unchanged
- [x] 3.2 Write `QueryCsvControllerTest` (MockMvc) first for both endpoints: 200 with `text/csv; charset=UTF-8` and `Content-Disposition: attachment; filename="result.csv"`; header-only body for an empty result; `X-Total-Count` present with `include_total` and absent otherwise; `Accept: application/json` still yields CSV; `INFORMATION_SCHEMA` statement exceeding the default limit returns every row; limit above maximum → same 400 `bad_request` as the JSON endpoint; `count(*)` header is `count`
- [x] 3.3 Implement `QueryCsvController` with `POST /v1/queries/execute-csv` and `POST /v1/queries/execute-sql-csv`, OpenAPI-annotated (`text/csv` 200, 400/422 responses), until the tests pass

## 4. Error contract under Accept: text/csv

- [x] 4.1 Write failing tests: a validation failure and an SQL translation failure posted with `Accept: text/csv` to each CSV endpoint return 400 `application/json` `ErrorView` with `error` = `bad_request` (strict JSON parse of the body, not only jsonPath) — reproduced: all five failed before the fix
- [x] 4.2 Make them pass with a content-negotiation strategy scoped to the two CSV paths that admits `application/json` (`QueryCsvWebConfiguration`, per design D5 — chosen over a request filter)

## 5. Security and docs

- [x] 5.1 Test in oidc mode (`QueryCsvAuthIntegrationTest`): unauthenticated post → 401 `unauthorized` `ErrorView`; a `READ_ONLY_ADMIN` reference to a sensitive column → 400 `bad_request` on both CSV endpoints; a read-only and a full-admin caller execute to a header-only CSV against real ClickHouse
- [x] 5.2 Add a short "CSV variant" note to `docs/sql-reference.md` naming `/v1/queries/execute-sql-csv` (and `/v1/queries/execute-csv` for the DSL); no `docs/configuration.md` change (no new property)

## 6. Verification

- [x] 6.1 Confirm `specs/query-csv-export/spec.md` matches the shipped behaviour and run `openspec validate add-csv-query-export`
- [x] 6.2 Run `./gradlew clean build` and make it pass
