# Tasks — add-sql-rest-endpoint

## 1. Preconditions

- [x] 1.1 Verify `SqlQueryFrontEnd` and its collaborators (`CalciteQueryCatalog`,
  `RelToStructuredQueryTranslator`, `RexToExprTranslator`, `SargExpander`,
  `SqlQueryOperators`) are plain `service/` beans not gated behind the Flight
  `@ConditionalOnProperty(flight.enabled)`; if any is gated, move the gate to the
  Flight-only beans in `web/flight/` so the translator is available with `flight.enabled=false`

## 2. Endpoint

- [x] 2.1 Add `SqlQueryRequestDto` record in `web/dto/query/` with a single `@NotBlank String sql`
  field, documented with `@Schema`
- [x] 2.2 Add `SqlQueryController` in `web/controller/query/` with
  `POST /v1/queries/execute-sql`: bind `@Valid SqlQueryRequestDto`, call
  `sqlQueryFrontEnd.translate(request.sql())`, execute the translation's `StructuredQuery` via
  `StructuredQueryService.execute(...)`, and return `StructuredQueryResultDto(page.rows(),
  page.totalCount())`; annotate for OpenAPI (summary, request/response schemas, error responses),
  noting the case-insensitive `LIKE` deviation in the endpoint description

## 3. Error mapping

- [x] 3.1 Add `@ExceptionHandler(SqlTranslationException.class)` to
  `web/handler/DefaultExceptionHandler` mapping to HTTP 400 with `error` = `bad_request` and the
  translation message, rendered as `ErrorView`

## 4. Tests

- [x] 4.1 Add `SqlQueryControllerTest` (`@WebMvcTest(SqlQueryController.class)` + MockMvc,
  mocked `SqlQueryFrontEnd`/`StructuredQueryService`, template `StructuredQueryControllerTest`):
  happy path asserts `$.rows[...]` and `$.totalCount` null; missing/blank `sql` → 400;
  `SqlTranslationException` → 400 `bad_request` with the translation message;
  `QueryValidationException` → 400; `ClickHouseUnavailableException` → 503
  `clickhouse_unavailable` — all in the `ErrorView` shape
- [x] 4.2 Add a Testcontainers integration test (ClickHouse + Postgres, `flight.enabled=false`,
  support base `ClickHouseContainerSupport`/`AbstractPostgresIntegrationTest`) covering: the same
  logical query via `POST /v1/queries/execute-sql` and `POST /v1/queries/execute` returns
  identical rows; a query with no `LIMIT` gets the default limit 100; `LIMIT 50000` → 400 naming
  the limit and maximum; an unsupported construct (e.g. JOIN) → 400 without contacting
  ClickHouse; an enrichment-column projection preserves null cells; aggregate query
  (`GROUP BY` + `count(*)`) round-trips

## 5. Docs & spec sync

- [x] 5.1 Mention the new endpoint in `README.md` prose (link to existing docs; no env-var
  tables — there is no new configuration)
- [x] 5.2 Confirm the delta spec `specs/rest-sql-endpoint/spec.md` matches shipped behavior;
  adjust if implementation forced deviations

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes (compile + checkstyle + tests)
- [x] 6.2 Smoke test locally: `curl -s -X POST localhost:8080/v1/queries/execute-sql -H
  "Content-Type: application/json" -d '{"sql":"SELECT project_id FROM dial_usage_log LIMIT 5"}'`
  returns `{ "rows": [...] }` (no `totalCount` key); a JOIN query and `LIMIT 50000` each return
  a 400 `ErrorView`
