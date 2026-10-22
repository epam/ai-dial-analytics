## 1. JSON extraction in the shared function catalog

- [x] 1.1 Add `BOOLEAN` to `QueryFunctionReturnType` and a `variadic` flag to `QueryFunctionArgDto`
- [x] 1.2 Register `JSON_EXTRACT_STRING|LONG|DOUBLE|BOOL` operators in `SqlQueryOperators.TABLE`
      (`OperandTypes.repeat(from(2), CHARACTER)`, fixed return-type inference per function)
- [x] 1.3 `RexToExprTranslator.callExpr`: map the four operator names to `FnExpr`, requiring 2+ args
      with every path argument a string literal
- [x] 1.4 `RelToStructuredQueryTranslator.fnKind`: fixed result kinds `STRING` / `INT64` / `FLOAT64` /
      `BOOL`
- [x] 1.5 `ExprTranslator.toFunction`: render to native `JSONExtractString|Int|Float|Bool`.
      **FINDING:** `JSONExtractBool` yields ClickHouse `UInt8`, which serializes as `1`/`0` — the
      advertised `boolean` return type would have been false on the wire, so the call is wrapped as
      `toBool(JSONExtractBool(…))`. Caught by the live-ClickHouse test, recorded in the spec
- [x] 1.6 `StructuredQueryToSqlRenderer` round-trips the calls unchanged (generic function rendering)
- [x] 1.7 Add the four descriptors to `QueryFunctionCatalog` in alphabetical position
- [x] 1.8 Delta specs `specs/structured-query/spec.md` + `specs/query-schema-discovery/spec.md`

## 2. Registry — `type=sql` evaluators

- [x] 2.1 Confirm the next free Postgres migration number (expected `V13`) — `V13` free after this
      phase's `V13__source_scan_metadata.sql`
- [x] 2.2 `db/migration/postgres/V14__class1_sql_evaluators.sql`: relax `evaluator_version.type` CHECK
      to `('LLM','SQL')`; make `model` and `preset` nullable; add `evaluator_model_iff_llm` /
      `evaluator_preset_iff_llm` CHECKs tying each to `type = 'LLM'`
- [x] 2.3 `EvaluatorType` += `SQL`; `EvaluatorVar` += `sql`; `EvaluatorVersionEntity` nullability
- [x] 2.4 `CreateEvaluatorRequest`/`EvaluatorVarDto`: `model`/`preset` optional, `sql` exposed
      (MapStruct maps `sql` by name — no mapper change needed)
- [x] 2.5 `EvaluatorService.register`: type-dispatched shape check (LLM needs model+preset; SQL
      forbids model/preset/template/input_vars/response_schema and requires `sql` + `type` per output var)

## 3. Class-1 governance

- [x] 3.1 Extract the `Expr`-tree column walk out of `FilterSqlValidator` into a shared
      `ExprColumnCollector`
- [x] 3.2 New `validation/ExprSqlValidator`: wrap as `SELECT <expr> AS __expr FROM <source>` through
      `SqlQueryFrontEnd`; assert `mode == ROW` (aggregate rejection), exactly one output column,
      collect referenced columns, infer the result kind from `SqlTranslation.columns()`
- [x] 3.3 Map `ResultColumnKind` → `QueryFieldType` and check against the output var's declared type
      via `EnrichmentTypeMapping`; reject array results
- [x] 3.4 `RuleGovernanceValidator`: dispatch on evaluator type — SQL skips the model check and output
      JSONata parse, runs `ExprSqlValidator` per output var, runs the existing entitlement check on
      the referenced columns, rejects input bindings

## 4. Materialize data plane

- [x] 4.1 `configuration/MaterializeProperties` (`enrichment.materialize.default-limit`, `max-limit`)
      + bare `application.yml` entries
- [x] 4.2 Extract `ScanService.enforceSensitiveAccess` into `support/SensitiveProjectionGuard` and
      use it from `ScanService`
- [x] 4.3 `clickhouse/ClickHouseInsertSelectExecutor` on the writer credential, returning
      `CommandResponse.getWrittenRows()`; `ClickHouseSpans.insertSelect` is its value-returning span
- [x] 4.4 `service/enrichment/MaterializeService`: probe (2-column keyset, `final=1`) then a single
      `INSERT … SELECT` bounded by the probed window; provenance stamped (`evaluator_version` in
      whichever shape the declared column can hold), `model` skipped, absent provenance columns dropped
- [x] 4.5 `web/dto/enrichment/Materialize{Request,Response}` + `POST /v1/rules/{id}/materialize` on
      `RuleDataController`; added to the `oidc` URL matcher alongside `scan`/`rows`.
      **CORRECTION:** the plan's body was `{from?, to?, limit?}` — it must also carry `cursor`, since
      ADAS is stateless about cursor position (the runner plan assumed it read `rule_cursor` but the
      ADAS body had nowhere to put it). Spec corrected
- [x] 4.6 Reject `materialize` on a `type=llm` rule and `scan` on a `type=sql` rule with 422
      (`resolveForScan` now resolves the evaluator so the scan can refuse a Class-1 rule)

## 5. Sensitive posture (spec + docs only)

- [x] 5.1 Record the `FULL_ADMIN` runner identity and the no-propagation decision in the
      `enrichment-registry` delta spec
- [x] 5.2 Note in `openspec/project.md` that the writer credential now also needs `SELECT` on scan
      sources (the materialize `INSERT … SELECT` reads the source)
- [x] 5.3 **FINDING (design gap closed):** the sensitive-column check can only run after an expression
      is parsed, but the SQL front end hides sensitive columns from a non-entitled caller's schema, so
      such a caller's parse failed first with a 422 "unknown column" — the documented 403 was
      unreachable on this path. Added `SensitiveProjectionGuard.reattribute`, which converts a
      governed-expression failure to `sensitive_column_not_entitled` when (and only when) the caller may
      not read the source's sensitive columns. Fails closed; an entitled caller still gets the accurate
      422. Recorded in the spec

## 6. Tests + build

- [x] 6.1 `QueryFunctionCatalogParityTest` green with the four new descriptors; `SqlQueryFrontEndTest`
      covers literal-path enforcement, arity, and the fixed result kinds
- [x] 6.2 `StructuredQueryProvisionalIntegrationTest`: all four functions render and execute on real
      ClickHouse over top-level and nested paths, and a missing field yields the type default
- [x] 6.3 `Class1MaterializeIntegrationTest` (18 tests): registry shape checks both ways; governance
      rejects an aggregate, an unknown column, a non-catalog (non-deterministic) function, a
      type-incompatible expression, and input bindings
- [x] 6.4 Same class: `materialize` writes the probed window with a matching `rows_written`; re-running
      the identical call leaves the `final=1` result unchanged; the limit reports `has_more`; a
      `from`/`to` window materializes only that range; an empty window echoes the cursor; an
      unparseable body yields the type default; provenance is stamped and `model` skipped
- [x] 6.5 Same class: `materialize` on an LLM rule and `scan` on a SQL rule are 422; unknown rule 404;
      a sensitive-derived non-sensitive target column is neither failed nor silently marked sensitive
- [x] 6.6 `RuleDataAuthorizationIntegrationTest`: a granted-but-non-entitled caller gets 403
      `sensitive_column_not_entitled` on a sensitive-reading Class-1 rule; `FULL_ADMIN` succeeds
- [x] 6.7 `docs/configuration.md` group for the materialize limits + the writer-credential note
- [x] 6.8 `./gradlew clean build` green (Docker/Testcontainers PG+CH)
- [x] 6.9 `openspec validate class1-sql-enrichment --strict` passes
