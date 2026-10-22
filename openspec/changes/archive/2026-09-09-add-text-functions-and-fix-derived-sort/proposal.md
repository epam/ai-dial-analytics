## Why

An audit of six prototype Grafana dashboards (42 panels, 47 queries) against the query surface found
every panel reachable but three things standing between "reachable" and a faithful rebuild:

1. **A defect.** A `SELECT` list that does not carry its own `ORDER BY` expression is rejected with
   `an ORDER BY expression must be a column or appear in the SELECT list`, even though the statement is
   ordinary SQL and executes fine in ClickHouse. Measured against the default branch (ee0b614), all of these
   are refused today:
   - `SELECT event_kind FROM dial_usage_log GROUP BY event_kind ORDER BY count(*) DESC LIMIT 5` — the
     top-N-by-aggregate idiom, with no derived table anywhere in the statement;
   - the same shape inside `deployment IN (SELECT …)`, which is how "Spend by model over time (top 8)"
     in `adas-cost-and-model-mix.json` picks its series;
   - `SELECT d FROM (SELECT deployment AS d, count(*) AS n … ORDER BY n DESC LIMIT 5) t` — the reduced
     form the audit reported;
   - `SELECT event_kind FROM dial_usage_log ORDER BY length(event_kind) LIMIT 5` — the same failure in
     row mode, with no aggregate involved.

   So the defect is not "sorting on an aggregate under a trimmed projection" as first diagnosed: it is
   **any computed `ORDER BY` expression the projection does not carry**. Rewriting the `IN` as an inner
   join to a derived table works, which localizes it to the SQL front end rather than to execution.

2. **No string concatenation, and no way to render a value as text.** `concat(...)` is not in the
   catalog, `||` is refused (`SQL construct '||' cannot be expressed in the structured query DSL`), and
   `CAST(x AS VARCHAR)` is refused (`CAST from BIGINT to VARCHAR is not supported`) — so a number cannot
   be put into a label at all. `adas-ai-supply-chain`'s "Top call edges"
   (`concat(parent_deployment, ' -> ', deployment)`) and "p95 latency by chain depth over time"
   (`concat('depth ', toString(…))`) need exactly this and have no SQL workaround.

3. **No array-to-text rendering and no way to cap an array's length.**
   `group_uniq_array(CASE WHEN … END)` already works and returns a JSON array, but nothing renders one
   as a cell of text and nothing truncates it. `adas-agentic-depth`'s "Deepest turns" (Tools used /
   Models billed, sliced to 6) and `adas-gateway-populations`' "Actors" (Auth / Clients columns) need
   both.

Everything else the dashboards use is already shipped: joins, CTEs, derived tables, uncorrelated
subqueries, `CASE`, window functions, arithmetic, `percentile_cont(f) WITHIN GROUP (ORDER BY x)`, and
`array_join(array)` row expansion. The enrichment joins those dashboards write are unnecessary —
`usage_client_identity.*`, `session_insights.*` and `conversation_insights.*` are flat columns — and
`FINAL` is applied by the façade.

## What Changes

- **Fix the dropped-sort-key defect.** When a projection above `ORDER BY` does not carry a *computed*
  sort expression, the block folded so far is sealed into a derived table and the projection applies
  over it — the same sealing already used for a filter or an aggregate above `ORDER BY`. The statement's
  own row bound travels with the seal so governance does not re-apply the 100-row default over it. A
  sort key that is a **plain column** keeps translating as one block exactly as today.
- **Add `concat(value, value[, value…])`** — variadic (two operands minimum), any scalar operand,
  result `string` and never null. Non-text operands render as text through the same rule `to_string`
  states; a null operand contributes the empty string, which is PostgreSQL `concat()`'s own behaviour.
- **Add `to_string(value)`** — any scalar to `string`, never null. This is the coercion `concat`
  delegates to, and the only way to render a single value as text on its own.
- **Lower `||` onto `concat`.** `a || b` is re-bound to `concat(a, b)` before validation, so the
  standard spelling works for mixed operand types rather than failing Calcite's string-only
  type check.
- **Lower `CAST(<non-text> AS <character type>)` onto `to_string`.** This is what BI tools emit;
  casts within one type family keep being unwrapped as identity exactly as today, and every other
  `CAST` stays unsupported.
- **Add `array_to_string(array, delimiter)`** — an array (declared column or array-returning call) to
  one `string`, elements rendered by the same `to_string` rule, joined by a string-literal delimiter;
  `''` for an empty array.
- **Add `array_slice(array, offset, length)`** — a bounded window of an array, result an array of the
  same element type. 1-based; a negative offset counts from the end. Composes with the above:
  `array_to_string(array_slice(group_uniq_array(x), 1, 6), ', ')` is the "Deepest turns" cell.
- Both new array functions accept a **projection-only array in argument 0** — the same positional
  carve-out `array_length`, `array_has*` and `array_join` have — because their real operand is a
  `group_uniq_array` result. `array_slice` additionally *returns* an array, so its own result is
  projection-only.
- **Naming.** `array_join` is taken by row expansion and its spec deliberately refuses a second
  operand, so Presto's `array_join(array, delimiter)` spelling is unavailable here and is not
  overloaded onto it. Hence `array_to_string`, which is also ClickHouse's own name for the operation
  (`arrayStringConcat` aside) and unambiguous next to it.
- `least`/`greatest` are **not** added: a `CASE` expresses both, they did not fall out of any of the
  above for free, and the p95-depth panel is expressible as
  `concat('depth ', to_string(CASE WHEN array_length(execution_path) > 4 THEN 4 ELSE array_length(execution_path) END))`.

Not in this change, deliberately:

- **The `ARRAY JOIN` / `LEFT ARRAY JOIN` clause** stays absent. It is the only route to left/outer
  expansion (keeping rows whose array is empty), element-wise zipping of two arrays, and
  `arrayEnumerate` indices — and because Calcite has no `ARRAY JOIN`, such statements would execute on
  `/execute-sql` and fail on `/translate-sql`, which makes it a change of its own. None of the six
  dashboards needs it (the "Roles" panel filters `notEmpty(user_roles)` and so wants the function
  form's empty-array-drops-the-row semantics). It is worth proposing separately.
- **FR-QS-6**, the 1000-row outer cap that rejects rather than clamps, stays as it is: no panel here
  asks for more than 500 rows.
- **`adas-gateway-populations`' "Identity coverage"** honesty-guard panel stays unreproducible: it
  counts `client_type IS NOT NULL` over a `LEFT JOIN`, and against flattened columns an absent
  enrichment row reads as the type default, so `<> ''` conflates "no identity row" with "empty
  client_type". An enrichment-row-presence test is a design question of its own and is not designed
  inline here.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: adds `concat`, `to_string`, `array_to_string` and `array_slice` to the closed
  function catalog with their null and type contracts, and widens the array-operand position and the
  array-valued (projection-only) set.
- `sql-query-translation`: fixes the dropped-computed-sort-key rejection (a new scenario under
  "Derived tables nest query blocks"), accepts `CONCAT`, `TO_STRING`, `ARRAY_TO_STRING`, `ARRAY_SLICE`
  and the `||` operator, and accepts `CAST` to a character type as `to_string`.
- `query-schema-discovery`: `GET /v1/queries/functions` gains the four descriptors in alphabetical
  position.
- `query-translation-endpoint`: `POST /v1/queries/translate` renders the four new calls, and the
  DSL → SQL → DSL round trip holds for each; a statement nested to carry its sort key reports "no
  structured-query equivalent" on `POST /v1/queries/translate-sql` rather than a 400 naming `ORDER BY`.

## Impact

- **Endpoints** (behaviour only, no contract shape change): `POST /v1/queries/execute-sql`,
  `POST /v1/queries/execute`, `POST /v1/queries/translate`, `POST /v1/queries/translate-sql`,
  `GET /v1/queries/functions`, and the same statements over Flight SQL.
- **Code**: `service/query/QueryFunctionCatalog`, `ArrayValuedFunctions`,
  `translate/{ExprTranslator,ExprFieldType}`, `sql/{SqlQueryOperators,RexToExprTranslator,
  RelToStructuredQueryTranslator,SqlQueryFrontEnd}` plus one new pre-validation re-binder for `||`.
  `SqlProjectionOnlyTypes` and `StructuredQueryToSqlRenderer` need no new rule: both delegate the
  array position and the array-valued set to `ArrayValuedFunctions`.
- **Docs**: `docs/sql-reference.md` (accepted-subset tables). No configuration property is added, so
  `docs/configuration.md` is unaffected.
- **No** database migration, catalog schema change, credential, or config key.
- **Not breaking.** Every statement accepted today is still accepted and still translates to the same
  document — a caller-written `CAST(<text> AS VARCHAR)` included: the cast is lowered onto an internal
  marker before validation and unwrapped again once the operand has a type, so it stays the bare column
  it already was in a projection, a `WHERE` and a `GROUP BY` alike.
- **Two observable changes**, and one of them is a new refusal. First, a statement whose projection drops
  a computed sort key now *executes* instead of returning 400, and on `/translate-sql` reports "no
  structured-query equivalent" instead of the `ORDER BY` message — a shape that was refused either way
  before. Second — the one place this change is **not** purely additive — a statement whose select list
  aliases something to the name of the very column it sorts by
  (`SELECT length(event_kind) AS request_time FROM dial_usage_log ORDER BY request_time DESC`) is now
  refused, where the default branch accepted it and silently ordered by the alias instead of by the column the
  caller named. That is a correctness fix rather than a regression — the old answer was ordered by the
  wrong expression — but it is a statement that used to translate and now returns 400, so it belongs
  here and not only in the spec.
