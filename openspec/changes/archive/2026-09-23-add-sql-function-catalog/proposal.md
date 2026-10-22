## Why

`GET /v1/queries/functions` lists exactly the functions the structured-query **DSL** accepts, but it
is also the only function discovery a **SQL** caller has, and for SQL it is wrong in both directions.
An audit of the function catalog found three problems:

- Five advertised names (`plus`, `minus`, `multiply`, `div`, `modulo`) cannot be called in SQL.
- Four advertised signatures cannot be written literally in SQL: `percentile_cont`/`percentile_disc`
  need `WITHIN GROUP`, `count` needs `count(*)`, and `array_has_any`/`array_has_all` need `ARRAY[…]`.
- About ten spellings that do work in SQL are never advertised: `COALESCE`, `NULLIF`, `CHAR_LENGTH`,
  `CEILING`, `MOD`, `CURRENT_TIMESTAMP`, `LIKE`/`ILIKE`, and the operators.

On top of that, the SQL validator's `unknown_function` remedy tells the caller to consult the same
DSL catalog that misled them. A caller who writes SQL has no machine-readable way to learn what they
may write.

## What Changes

- **New endpoint `GET /v1/queries/sql-functions`.** It returns the catalog of the ad-hoc SQL surface
  as an object envelope, `{"functions": [...]}`. There is one entry per SQL spelling family, and each
  entry carries:
  - `name` (the SQL spelling), `form` (`function`, `operator` or `syntax`), `aliases` (other accepted
    spellings), `group`, `signature` (as written in SQL), `returns`, `distinct_supported`,
    `deterministic`, `description` and `args`;
  - `dsl_function`: the DSL catalog function the entry lowers onto, or null for SQL-only constructs
    such as `COALESCE`, `NULLIF` and `ILIKE`;
  - `example`: a complete, runnable `SELECT` statement.
- The SQL catalog is **derived from** the DSL catalog (`QueryFunctionCatalog`) plus a small, explicit
  set of SQL overrides and SQL-only entries. It is not a second hand-written list: a DSL function with
  no SQL mapping fails the build, and so does an SQL entry whose example does not translate.
- **Corrected DSL descriptions** in `GET /v1/queries/functions`. The five arithmetic entries stop
  saying "Also spelled with the `+` operator in SQL"; they now state that the name is not callable in
  SQL, which operator to write instead, and where the SQL spellings are listed. The response shape of
  `GET /v1/queries/functions` is unchanged: it stays a bare array (frozen) and gains no fields.
- **SQL validation remedies** (`POST /v1/queries/validate-sql`) point at
  `GET /v1/queries/sql-functions` for `unknown_function`, `unsupported_function` and
  `function_arity`, instead of at the DSL catalog.
- **Guard tests**:
  - every SQL entry's `example`, and every alias, translates through the SQL front end;
  - every DSL catalog function is reachable from at least one SQL entry;
  - every caller-facing name registered in `SqlQueryOperators.TABLE` is advertised;
  - the five DSL-only arithmetic names are absent from the SQL catalog.
- Docs: `docs/sql-reference.md` points SQL callers at the new endpoint.
- **Fix, found by executing the examples:** `json_extract_array` and `json_extract_string_ci` over a
  text column read across the payload `LEFT JOIN` (`"dial_usage_log_payload.request_body"`) failed
  inside ClickHouse (`Code 43`, an array type inside `Nullable`). They now answer the empty array or
  empty string that the spec already promises for an absent document (design D7).

Apart from that fix, no behaviour of the SQL or DSL query surfaces changes; the rest is discovery.
There is no new configuration and no migration.

## Capabilities

### New Capabilities
<!-- none: the SQL catalog is a discovery endpoint and belongs with the existing discovery capability -->

### Modified Capabilities
- `query-schema-discovery`: adds the "SQL function catalog discovery" requirement for
  `GET /v1/queries/sql-functions`. It also corrects the DSL catalog requirement so that the arithmetic
  entries state they are DSL-only, and adds the missing `date_trunc` to the enumerating scenario
  (found by the same audit).
- `rest-sql-endpoint`: the validation remedies for function errors (`unknown_function`,
  `unsupported_function`, `function_arity`) name `GET /v1/queries/sql-functions`.

## Impact

- **API**: one new read-only endpoint, `GET /v1/queries/sql-functions`, under the existing `/v1/**`
  authorization (`FULL_ADMIN` or `READ_ONLY_ADMIN`). The change is additive; there are no breaking
  changes.
- **Code**:
  - new: `service.query.sql.SqlFunctionCatalog` and `web.dto.query.{SqlFunctionDto, SqlFunctionForm,
    SqlFunctionCatalogDto}`;
  - changed: `QuerySchemaController` gets the new route, `QueryFunctionCatalog` gets reworded
    arithmetic descriptions, and `SqlIssueClassifier` gets new remedy strings.
- **Tests**: a new `SqlFunctionCatalogParityTest` and controller cases in `QuerySchemaControllerTest`.
- **Docs**: `docs/sql-reference.md`. `docs/configuration.md` is untouched, because the change adds no
  property.
- **Out of scope**, tracked by the audit: the `length` byte-count problem, `distinct_supported`
  enforcement, the stale array carve-out prose, the `if` condition wording, and `round` semantics.
