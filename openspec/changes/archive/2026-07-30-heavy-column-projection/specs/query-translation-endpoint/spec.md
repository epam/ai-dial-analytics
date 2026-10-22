## MODIFIED Requirements

### Requirement: Structured query DSL translates to external-dialect SQL without execution

`POST /v1/queries/translate` SHALL accept the same request body as `POST /v1/queries/execute`
(a structured query DSL document), validate it against the catalog with the same rules as
execution (entity resolution, field allowlist, closed function catalog, mode coherence, offset
paging with limit governance, param rejection), and — without executing it — return `200` with
`{"sql": "<SELECT statement>"}` in the external SQL dialect: the statement SHALL be within the
subset accepted by `POST /v1/queries/execute-sql` and SHALL translate back (per
`sql-query-translation`) to a structured query semantically equivalent to the input. The
rendered SQL SHALL reference only catalog-level identifiers (entity and flat field names,
double-quoted) — never physical ClickHouse table names, join clauses, or internal SQL.
Function names SHALL match case-insensitively, exactly as execution does, and render
lowercase; a function's `distinct` flag SHALL render as the `DISTINCT` quantifier only on the
aggregates whose execution honors it (`count`/`sum`/`avg`) and SHALL be dropped on every other
catalog function (execution silently ignores the flag there, and rendering it would produce a
statement outside the accepted SQL subset); literal values SHALL be normalized (trimmed and
parsed) exactly as execution binds them before rendering, so a value execution accepts never
renders with different semantics.
Every output column SHALL render with an explicit `AS` alias carrying the resolved output name
execution would use (per the structured-query output-column-naming contract) unless the expression
already produces that name on its own — in practice a plain field reference whose resolved name is its
own field name. An entry with no `as` SHALL therefore render its derived name (e.g. `count(*) AS
"count"`) rather than an unnamed expression, so the rendered statement declares exactly the columns the
execute path returns and re-translating it yields the same names.
An absent/empty row-mode `select` SHALL render as `SELECT *` for **every** entity, because a star and
an omitted `select` now share one wildcard rule — the entity's non-heavy source-owned columns of every
declared type, `array` and `object` included (see the `structured-query` capability's "Wildcard
projection excludes heavy columns" and the `sql-query-translation` capability's star expansion). The
rendered star therefore translates back to exactly the projection execution uses. (Previously an
enriched entity had to be spelled out column by column, because SQL `*` would have fanned out into its
enrichment columns.) No source column type SHALL cause a bare `select` to be rejected; a bare `select`
on an entity whose every caller-visible source column is `heavy` SHALL be rejected with the same 400
`bad_request` the execute path produces, naming the entity.
Validation failures — including an unknown/non-queryable entity — SHALL return the same 400
`bad_request` `ErrorView` (with the same message) that `POST /v1/queries/execute` produces.

#### Scenario: Aggregate DSL renders round-trippable SQL

- **WHEN** a client posts a DSL document with `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, a `count` select aliased `n`, a `gt` filter on `price`, sort on `n` desc, and limit 50 to `/v1/queries/translate`
- **THEN** the response is `200` with a single SELECT statement containing `GROUP BY`, `WHERE`, `ORDER BY … DESC`, and `LIMIT 50`, which `/v1/queries/execute-sql` accepts and executes with equivalent semantics

#### Scenario: Enrichment fields render as quoted flat identifiers

- **WHEN** the input DSL selects the enrichment field `deployment_ref.display_name`
- **THEN** the rendered SQL references it as the double-quoted flat identifier `"deployment_ref.display_name"` and contains no JOIN clause and no physical table names

#### Scenario: Bare select renders the execution projection

- **WHEN** the input DSL is a row-mode document with no `select` on an entity that has enrichments
- **THEN** the rendered SQL is `SELECT * FROM "<entity>"` and translates back to a query projecting
  exactly the entity's non-heavy source-owned columns — no enrichment column on either side of the
  round-trip

#### Scenario: Bare select on an entity with object source columns renders

- **WHEN** the input DSL is a row-mode document with no `select` on `dial_usage_log`, whose source columns include the `object`-typed fields `jwt_claims` and `request_tags` and the `heavy` payload columns `request_body`/`response_body`
- **THEN** the response is `200` with `SELECT *` — no source column type blocks the bare projection any more — and `/v1/queries/execute-sql` accepts the statement, expanding the star over the object columns while leaving the heavy payload columns out, exactly as execution does

#### Scenario: Selected array field renders and round-trips

- **WHEN** the input DSL is a row-mode document selecting the `array`-typed field `execution_path` on `dial_usage_log`
- **THEN** the rendered SQL projects `"execution_path"` as a double-quoted identifier, is accepted by `/v1/queries/execute-sql`, and translates back to the identical projection

#### Scenario: Selected object field renders and round-trips

- **WHEN** the input DSL is a row-mode document selecting the `object`-typed field `jwt_claims` on `dial_usage_log`
- **THEN** the rendered SQL projects `"jwt_claims"` as a double-quoted identifier, is accepted by `/v1/queries/execute-sql`, and translates back to the identical projection

#### Scenario: Every scalar catalog function renders within the SQL subset

- **WHEN** the input DSL selects any scalar function from the closed catalog — `lower`, `upper`, `length`, `trim`, `abs`, `date_bin`, or `width_bucket`
- **THEN** the rendered SQL spells the call under its lowercase DSL name (e.g. `length("deployment")`), `/v1/queries/execute-sql` accepts the statement, and it translates back to the identical DSL call

#### Scenario: Uppercase function names translate like execution

- **WHEN** the input DSL uses `COUNT` with no arguments or `PERCENTILE_CONT` with `(fraction, column)` arguments
- **THEN** the response is `200` and the rendered SQL spells the calls lowercase — `count(*)` and `percentile_cont(<fraction>) WITHIN GROUP (ORDER BY "<column>")` — exactly as for the lowercase spellings

#### Scenario: Inert distinct flag drops like execution ignores it

- **WHEN** the input DSL sets `distinct: true` on a function where execution ignores the flag — a scalar catalog function (e.g. `lower`) or `min`/`max` — in a document `/v1/queries/execute` accepts
- **THEN** the rendered SQL spells the call without a `DISTINCT` quantifier (e.g. `lower("deployment")`), is accepted by `/v1/queries/execute-sql`, and translates back to the execution-equivalent call

#### Scenario: Padded literal values normalize like execution

- **WHEN** the input DSL contains a boolean literal `" true "` (whitespace-padded) that `/v1/queries/execute` accepts as `TRUE`
- **THEN** the rendered SQL contains `TRUE` — the parsed value, never a silently inverted or unparseable literal from the raw text

#### Scenario: Unknown entity yields 400

- **WHEN** the input DSL names an entity that is not an ACTIVE queryable entity
- **THEN** the response is a 400 `ErrorView` with code `bad_request`, matching the execute
  endpoint's rejection (naming the supported entities)

#### Scenario: Validation failures yield 400 without rendering

- **WHEN** the input DSL references an unknown field or a limit above the structured-query maximum
- **THEN** the response is a 400 `ErrorView` with code `bad_request` carrying the same message the execute endpoint would produce

#### Scenario: Alias-free select entry renders its derived alias

- **WHEN** the input DSL is an aggregate-mode document grouping by `model` with a `count` select entry that has no `as`
- **THEN** the rendered SQL projects `count(*) AS "count"` alongside `"model"`, is accepted by
  `/v1/queries/execute-sql`, and translates back to outputs named `model` and `count`

