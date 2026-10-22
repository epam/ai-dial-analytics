## Why

The SQL surface (`POST /v1/queries/execute-sql`, Flight SQL, `POST /v1/queries/translate-sql`) accepts
exactly the single-entity shape the structured query DSL can express. BI tools and analysts routinely
write joins between entities, `WITH` clauses, derived tables and `IN (SELECT …)`/`EXISTS` predicates,
`CASE` expressions and window functions (`row_number() OVER (…)`, running `sum(...) OVER (…)`), and today
every one of those is refused with "cannot be expressed in the structured query DSL". Arithmetic was
already widened in an earlier change but the specs, the OpenAPI text and the README still say it is
rejected. This change makes the SQL surface a usable analytical SQL dialect while keeping every existing
governance rule — catalog validation, sensitive-column masking, enrichment joins, the closed function
catalog, the row limits and the query budget — in force.

## What Changes

- **Joins.** `INNER`, `LEFT`, `RIGHT`, `FULL` and `CROSS` joins between queryable entities (including
  self-joins under aliases), with any conjunction in `ON` that carries at least one equality between the
  two sides. Each joined entity keeps its own enrichment columns, wildcard rules and masking.
- **Common table expressions.** Non-recursive `WITH` clauses whose body is a `SELECT` are accepted and
  inlined at every reference site. `WITH RECURSIVE` stays rejected.
- **Derived tables and nested query blocks.** A `FROM (SELECT …)` subquery of any accepted shape — including
  one that carries `ORDER BY`/`LIMIT`, aggregation or `DISTINCT` — is accepted; every shape the translator
  previously refused as "above ORDER BY/LIMIT", "multiple ORDER BY/LIMIT levels" or "nested aggregation" now
  becomes a derived table.
- **Subquery predicates.** `IN (SELECT …)`, `NOT IN (SELECT …)`, `EXISTS`, `NOT EXISTS` and scalar
  subqueries (`x > (SELECT avg(x) FROM …)`, `(SELECT count(*) FROM …) AS n`) are accepted when the
  subquery is **uncorrelated**. Correlated subqueries are rejected with an error that names the rewrite
  (a join).
- **`CASE`.** Searched and simple `CASE … WHEN … THEN … [ELSE …] END` translate to a new DSL expression
  kind `case`, so the DSL, `translate-sql` and `translate` all carry it.
- **Window functions.** `row_number()`, `rank()`, `dense_rank()` and the aggregates `count`, `sum`,
  `avg`, `min`, `max` over `OVER (PARTITION BY … ORDER BY … [ROWS|RANGE frame])` translate to a new DSL
  expression kind `window`. The three ranking functions join the function catalog under a new group
  `window`.
- **Arithmetic.** Already supported; this change reconciles the stale spec text, the OpenAPI description
  and the README that still list `+ - * /` among the rejected constructs.
- **Composite statements have no DSL document.** A statement that needs a join, a derived table or a
  subquery executes over REST and Flight through the same pipeline, but `POST /v1/queries/translate-sql`
  answers 400 for it, because the structured query DSL stays single-entity by design. A statement that
  only adds `CASE` or a window expression still translates to a DSL document.
- **Governance unchanged in substance.** The outermost statement keeps the default limit of 100 and the
  1000-row maximum over REST and the `flight.max-rows` clamp-and-probe over Flight; inner blocks keep the
  `LIMIT` they state. Sensitive columns stay invisible in every block; every entity is still resolved
  through the catalog; `final = 1` and the `query.budget.*` settings cover the whole statement.
- **Still rejected**, with an error naming the construct: `UNION`/`INTERSECT`/`EXCEPT`, `WITH RECURSIVE`,
  correlated subqueries, `LATERAL`, `ROLLUP`/`CUBE`/`GROUPING SETS`, general `CAST`, quantified
  comparisons (`> ALL`, `= SOME`), `FILTER (WHERE …)` on aggregates, and functions outside the catalog.

No **BREAKING** changes: every statement accepted today is accepted with the same result; only previously
rejected statements change outcome.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `sql-query-translation`: the supported SQL subset gains joins, CTEs, derived tables, subquery
  predicates, `CASE` and window functions; the fail-closed rejection list shrinks accordingly (and drops
  its stale arithmetic clause); composite statements produce an executable plan instead of a
  `StructuredQuery`; star expansion covers joined and derived sources; result-column typing covers the new
  expression kinds.
- `structured-query`: the expression grammar gains the `case` and `window` kinds; the function catalog
  gains the `window` group with `row_number`, `rank`, `dense_rank`; validation rules for where a window
  expression may appear.
- `query-schema-discovery`: `GET /v1/queries/functions` advertises the new `window` group and its three
  functions.
- `rest-sql-endpoint`: execution governance stated for composite statements (outer statement governed,
  inner blocks not); the "unsupported construct" scenario no longer uses a join or arithmetic as its
  example.
- `flight-sql-endpoint`: the same governance statement for composite statements; the row-cap clamp and
  truncation probe apply to the outermost block.
- `query-translation-endpoint`: `translate-sql` rejects composite statements with a stable message;
  `translate` renders `case` and `window` expressions and they round-trip through `translate-sql`.

## Impact

- **Code**: `service/query/sql/` (front end screen, star expander, RelNode fold generalised to a tree of
  query blocks, Rex translation for `CASE`/`OVER`/subqueries), `service/query/model/` (new `Expr` and
  `FilterNode` kinds plus internal relation/plan records), `service/query/translate/` (ExprTranslator,
  FilterTranslator, StructuredQueryBuilder limit policy, a new composite renderer), `service/query/`
  (StructuredQueryService/Executor execute a composite plan), `web/controller/query/SqlQueryController`,
  `web/controller/query/QueryTranslationController`, `web/flight/AdasFlightSqlProducer`,
  `QueryFunctionCatalog` and `QueryFunctionGroup`.
- **APIs**: additive. New DSL expression kinds on `POST /v1/queries/execute`, new function group value
  `window` on `GET /v1/queries/functions`. Error codes unchanged (`bad_request`, `query_budget_exceeded`,
  `clickhouse_unavailable`; Flight `INVALID_ARGUMENT`).
- **Dependencies / config / migrations**: none. No new env var, no schema change, no new credential.
- **Docs**: README SQL section, OpenAPI descriptions in the two controllers, `docs/heavy-columns.md`
  wording about the accepted subset.
- **Tests**: rejection tests for join/CTE/CASE/subquery/window flip to acceptance; new unit tests for the
  fold and renderer; new integration tests executing joins, CTEs, subqueries, `CASE` and windows against
  ClickHouse over REST and Flight.
