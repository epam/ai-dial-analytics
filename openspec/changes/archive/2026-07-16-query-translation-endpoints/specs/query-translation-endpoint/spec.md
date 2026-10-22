# query-translation-endpoint Delta

## ADDED Requirements

### Requirement: SQL translates to the structured query DSL without execution

`POST /v1/queries/translate-sql` SHALL accept the same request body as
`POST /v1/queries/execute-sql` (`{"sql": "<one read-only SELECT>"}`) and SHALL translate the
statement to the structured query DSL using exactly the mapping contract of the
`sql-query-translation` capability — same supported subset, same catalog validation, same
rejections. The endpoint SHALL NOT execute the query and SHALL NOT contact ClickHouse. On
success it SHALL return `200` with `{"query": <DSL document>}`, where the DSL document is
accepted verbatim by `POST /v1/queries/execute` and executes with semantics equivalent to the
submitted SQL. Translation failures SHALL return the same `ErrorView` errors as `execute-sql`
(400 `bad_request` naming the offending construct or unknown identifier).

#### Scenario: Aggregate SQL returns its DSL document

- **WHEN** a client posts `{"sql": "SELECT model, count(*) AS n FROM dial_usage_log GROUP BY model"}` to `/v1/queries/translate-sql`
- **THEN** the response is `200` with a `query` object having `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, and a `count` fn select entry aliased `n` — and no query was executed

#### Scenario: Unsupported SQL rejected without execution

- **WHEN** a client posts `{"sql": "SELECT a.x FROM t1 a JOIN t2 b ON a.id = b.id"}`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the unsupported construct, and ClickHouse is not contacted

#### Scenario: Translated document is executable

- **WHEN** the `query` object returned by `/v1/queries/translate-sql` is posted unchanged to `/v1/queries/execute`
- **THEN** it deserializes and validates successfully and runs with the same semantics as posting the original SQL to `/v1/queries/execute-sql`

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
An absent/empty row-mode `select` SHALL render the projection execution uses — the entity's
source-owned columns, spelled explicitly when the entity has enrichments (SQL `*` would fan
out into enrichment columns), or as `SELECT *` when the entity has none (where `*` expands to
exactly that projection).
Validation failures — including an unknown/non-queryable entity — SHALL return the same 400
`bad_request` `ErrorView` (with the same message) that `POST /v1/queries/execute` produces.

#### Scenario: Aggregate DSL renders round-trippable SQL

- **WHEN** a client posts a DSL document with `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, a `count` select aliased `n`, a `gt` filter on `price`, sort on `n` desc, and limit 50 to `/v1/queries/translate`
- **THEN** the response is `200` with a single SELECT statement containing `GROUP BY`, `WHERE`, `ORDER BY … DESC`, and `LIMIT 50`, which `/v1/queries/execute-sql` accepts and executes with equivalent semantics

#### Scenario: Enrichment fields render as quoted flat identifiers

- **WHEN** the input DSL selects the enrichment field `deployment_ref.display_name`
- **THEN** the rendered SQL references it as the double-quoted flat identifier `"deployment_ref.display_name"` and contains no JOIN clause and no physical table names

#### Scenario: Bare select renders the execution projection

- **WHEN** the input DSL is a row-mode document with no `select` on an entity that has enrichments (and no `object`/`array`-typed source columns)
- **THEN** the rendered SQL projects the source-owned columns explicitly as double-quoted identifiers — no enrichment columns, no `*` — and translates back to a query projecting exactly those columns

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

### Requirement: DSL constructs inexpressible in the SQL subset fail closed

`POST /v1/queries/translate` SHALL reject, with 400 `bad_request` and a message naming the
construct, any DSL document that the external SQL dialect cannot express even though execution
would accept it: a contains comparison (`co`/`nc`/`ico`/`inc`) whose value contains `%` or `_`
(the SQL subset's contains rule forbids metacharacters in the pattern text), a null literal
used outside an `eq`/`ne` comparison (only `IS NULL`/`IS NOT NULL` are renderable), an
offset page with `include_total: true` (the SQL surface has no total-count opt-in), a
`having` clause on a row-mode query (execution silently ignores it, and a rendered `HAVING`
without grouping would be rejected by the SQL subset), `distinct: true` on an aggregate-mode
query (execution deduplicates aggregate rows, but `SELECT DISTINCT` combined with `GROUP BY`
is rejected by the SQL subset as nested aggregation), and an absent/empty row-mode `select`
on an entity whose source-owned columns include an `object`/`array`-typed field (execution's
bare projection includes the column, but the type has no SQL representation, so no statement
in the subset is semantically equivalent; the error names the column). Contains
operators with metacharacter-free values SHALL render with exact semantics preserved:
`co`/`nc` (case-sensitive) as `LIKE '%<value>%'`/`NOT LIKE '%<value>%'`, `ico`/`inc`
(case-insensitive) as `ILIKE '%<value>%'`/`NOT ILIKE '%<value>%'`; `eq`/`ne` against a null
literal SHALL render as `IS NULL`/`IS NOT NULL`.

#### Scenario: Contains value with percent sign rejected

- **WHEN** the input DSL contains a `co` comparison with value `50%`
- **THEN** the response is a 400 `ErrorView` naming the contains operator and the metacharacter restriction

#### Scenario: Contains operator renders LIKE

- **WHEN** the input DSL contains a `co` comparison on `model` with value `gpt`
- **THEN** the rendered SQL contains `"model" LIKE '%gpt%'`

#### Scenario: Case-insensitive contains renders ILIKE

- **WHEN** the input DSL contains an `ico` comparison on `model` with value `gpt`
- **THEN** the rendered SQL contains `"model" ILIKE '%gpt%'`

#### Scenario: Null equality renders IS NULL

- **WHEN** the input DSL contains an `eq` comparison between a field and a null-typed value
- **THEN** the rendered SQL contains `"<field>" IS NULL`

#### Scenario: Bare select with object/array source column rejected

- **WHEN** the input DSL is a row-mode document with no `select` on an entity whose source columns include an `object`- or `array`-typed field (e.g. `dial_usage_log`'s `execution_path`)
- **THEN** the response is a 400 `ErrorView` naming the non-representable column and asking the client to list the desired columns explicitly, even though `/v1/queries/execute` would accept the document

#### Scenario: Row-mode having rejected

- **WHEN** the input DSL is a `mode="row"` document carrying a `having` clause
- **THEN** the response is a 400 `ErrorView` naming `having` and row mode, even though `/v1/queries/execute` would accept the document (ignoring the clause)

#### Scenario: Aggregate-mode distinct rejected

- **WHEN** the input DSL is a `mode="aggregate"` document with `distinct: true`
- **THEN** the response is a 400 `ErrorView` naming `distinct` and aggregate mode, even though `/v1/queries/execute` would accept the document

#### Scenario: Percentile functions render in WITHIN GROUP form

- **WHEN** the input DSL selects `percentile_cont` or `percentile_disc` with `(fraction, column)` arguments
- **THEN** the rendered SQL spells the call `<fn>(<fraction>) WITHIN GROUP (ORDER BY "<column>")` — the only percentile shape the SQL subset parses — and the statement translates back to the identical DSL call

### Requirement: Translation endpoints share the query surface's access rules

Both translation endpoints SHALL be governed by the same authentication/authorization rules as
the other `/v1/queries` endpoints (in `oidc` mode: `FULL_ADMIN` or `READ_ONLY_ADMIN`; auth
failures return the standard 401/403 `ErrorView`s), and SHALL be documented in the service's
OpenAPI description.

#### Scenario: Unauthenticated caller rejected in oidc mode

- **WHEN** security mode is `oidc` and an unauthenticated client posts to `/v1/queries/translate` or `/v1/queries/translate-sql`
- **THEN** the response is a 401 `ErrorView` with code `unauthorized`
