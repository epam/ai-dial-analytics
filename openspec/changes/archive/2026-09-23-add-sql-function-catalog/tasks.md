## 1. DTOs

- [x] 1.1 Add `web.dto.query.SqlFunctionForm` (`FUNCTION`, `OPERATOR`, `SYNTAX`; serialized lowercase)
- [x] 1.2 Add `web.dto.query.SqlFunctionDto` record (`name`, `form`, `aliases`, `group`, `signature`, `returns`, `distinct_supported`, `deterministic`, `description`, `dsl_function`, `example`, `args`) with snake_case `@JsonProperty` and `@Schema` docs; `dsl_function` serialized as null when absent
- [x] 1.3 Add `web.dto.query.SqlFunctionCatalogDto` envelope `{functions}`

## 2. SQL catalog

- [x] 2.1 Add `service.query.sql.SqlFunctionCatalog` (`@Component`) deriving one entry per DSL descriptor outside `DSL_ONLY` (`plus`, `minus`, `multiply`, `div`, `modulo`), with signature / description / alias override maps and an example per entry
- [x] 2.2 Add the SQL-only entries: operators `+ - * / % ||`, `like`, `ilike`; functions `mod`, `coalesce`, `nullif`; syntax `case`, `cast`
- [x] 2.3 Sort case-insensitively by name; expose `listFunctions()` and the package-visible `DSL_ONLY` set for tests
- [x] 2.4 Verify each SQL-specific override (`if` condition, `contains` → `ILIKE`, `count(*)`, percentiles `WITHIN GROUP`, `ARRAY[...]`, window `OVER`) against the front end rather than prose

## 3. Endpoint

- [x] 3.1 Add `GET /v1/queries/sql-functions` to `QuerySchemaController` returning `SqlFunctionCatalogDto`, with OpenAPI annotations
- [x] 3.2 Cross-link: the OpenAPI description of `GET /v1/queries/functions` states it lists DSL `fn` names and points SQL callers at `/v1/queries/sql-functions`

## 4. DSL catalog and remedies

- [x] 4.1 Reword the five arithmetic descriptions in `QueryFunctionCatalog` (not callable in SQL; the operator to write; `GET /v1/queries/sql-functions`)
- [x] 4.2 Point `SqlIssueClassifier`'s `unknown_function`, `unsupported_function` and `function_arity` remedies at `GET /v1/queries/sql-functions`, and update the stale javadoc references in `SqlTextRebinder`/`SqlQueryOperators` that describe the SQL accepted surface

## 5. Tests

- [x] 5.1 `SqlFunctionCatalogParityTest`: every example translates through `SqlQueryFrontEnd` (test catalog with `dial_usage_log` + an OTel metrics entity carrying a map column)
- [x] 5.2 Every alias, substituted into its entry's example, translates
- [x] 5.3 Every DSL catalog name is some entry's `dsl_function`
- [x] 5.4 Every caller-facing name in `SqlQueryOperators.TABLE`'s custom operators is an entry name or alias (internal markers excluded explicitly)
- [x] 5.5 Each `DSL_ONLY` name is refused by the front end and absent from the catalog
- [x] 5.6 `QuerySchemaControllerTest`: envelope shape, ordering, all members present, arithmetic operators and SQL-only entries, `now` non-deterministic, SQL signatures (`WITHIN GROUP`, `count(*)`, `ARRAY[`, `OVER (`), strict JSON parse
- [x] 5.7 `QuerySchemaControllerTest`: the five arithmetic DSL descriptions name `GET /v1/queries/sql-functions` and no longer say "Also spelled"
- [x] 5.8 `SqlIssueClassifier`/validate-sql test: `plus(...)` → `unknown_function` with the new remedy; `count()` → `function_arity` with the new remedy

## 6. Docs and specs

- [x] 6.1 `docs/sql-reference.md`: point SQL callers at `GET /v1/queries/sql-functions` (intro and Functions section)
- [x] 6.2 Confirm the delta specs match shipped behavior; the discovery spec's Implementation notes (SQL catalog class, DTOs, parity test) are updated in `openspec/specs/` at archive, since requirement deltas do not carry that section; no `docs/configuration.md` change (no property added)

## 6a. Runtime-found fix

- [x] 6a.1 Guard the text-form operand of `json_extract_array` / `json_extract_string_ci` with `assumeNotNull(ifNull(…, ''))` (`ExprTranslator.nonNullJsonText`)
- [x] 6a.2 `PayloadJsonTextExtractionIntegrationTest`: both functions over the joined `request_body`, with a matched row and an unmatched one, against real ClickHouse

## 7. Verification

- [x] 7.1 `./gradlew clean build` passes
