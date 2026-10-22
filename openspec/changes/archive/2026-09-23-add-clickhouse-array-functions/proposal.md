## Why

A question as ordinary as *"total cost of every user holding a `coder-*` role"* is inexpressible on
the ADAS query surface today. ClickHouse answers it in one line —
`arrayExists(r -> startsWith(r, 'coder-'), user_roles)` — but the ADAS catalog has no lambda, so
every text predicate is scalar-only and cannot reach inside an array. `array_has*` covers equality
and nothing else, and `array_join` is the only way in: it multiplies rows, so any `sum`/`count` in
the same query is inflated by the group's distinct-element count (an order of magnitude or more on
realistic data — the right role set or the right money, never both).

The aggregate half is blocked outright. `group_uniq_array(user_roles)` and
`arg_max(user_roles, request_time)` are refused with `projection_only_field`, so the honest
"a user's whole-window cost, filtered on the roles they held at any point" — a `HAVING` over the
union of the group's arrays — has no spelling at all. Today it needs two round-trips and batching
in 20s around the `IN`-list cap.

The people writing these queries are ClickHouse users. The surface should therefore accept **what
ClickHouse accepts, spelled the way ClickHouse spells it**, rather than an ADAS-invented convenience
function that is cheaper for us to implement and one more thing for them to learn.

## What Changes

- **Lambda expressions in the SQL dialect.** `x -> <expr>` becomes an expression form, in
  ClickHouse's operand position (**first**, not last). Measured working end to end on Calcite 1.42's
  Babel parser — see design.md, "Measured, not assumed".
- **Two expression kinds in the structured DSL** — `lambda` and `lambda_param` — so the SQL
  surface's lambda has a document form and the DSL↔SQL round-trip holds.
- **Capturing a column of the enclosing row inside a lambda body ships**, so
  `arrayExists(x -> starts_with(x, deployment), roles)` means here what it means in ClickHouse.
  Calcite's own lambda scope forbids it; the gap was attacked rather than documented, and it
  closed — the parameter is inlined as a typed marker before validation, leaving
  the body an ordinary expression in the enclosing scope (design.md, Decisions 2 and 9). A captured
  column is governed exactly as a projected one, which is a test rather than a claim.
- **Five higher-order array functions under their ClickHouse names**, lambda-first:
  `arrayExists`, `arrayAll`, `arrayCount` (boolean/integer results, valid as a predicate) and
  `arrayFilter`, `arrayMap` (array results, projection-only like `group_uniq_array`).
- **`match(haystack, pattern)`** — ClickHouse's RE2 regex predicate, the regex half of the same
  family as `startsWith`.
- **Two array-valued aggregates over an array column**: `groupUniqArrayArray` (flatten + dedupe) and
  `groupArrayArray` (flatten, order preserved) — the `-Array` combinator's two useful members, which
  is what makes the post-aggregation role test expressible. The general `-Array`/`-If` combinator
  machinery is **not** in scope.
- **`arg_max(<array>, order)` is admitted**: the array-operand restriction is lifted for the value
  operand of an array-consuming aggregate, giving the "roles as of the user's last request" reading.
- **The new functions carry their ClickHouse names as their canonical DSL names** — `arrayExists`,
  not `array_exists`. No alias is added for the **existing** catalog, deliberately: `startsWith`,
  `hasAny` and `argMax` keep failing as unknown functions, and the spellings inside a lambda body
  stay the catalog's (`arrayExists(x -> starts_with(x, 'coder-'), roles)`). Aliasing the shipped
  catalog to ClickHouse spelling is a mechanical change over 49 functions that touches
  `/v1/queries/functions`, every rendered statement and every stored document; it is worth doing on
  its own terms, not as a rider on this one.
- **Not breaking.** Every statement legal today stays legal and lowers to the same document.

## Capabilities

### New Capabilities

None. The change extends existing query capabilities rather than adding a surface.

### Modified Capabilities

- `structured-query`: the `lambda` and `lambda_param` expression kinds with their scoping rule; the
  higher-order function family, `match`, and the two array aggregates in the supported function
  catalog; the array-field restriction lifted for the value operand of an array-consuming aggregate.
- `sql-query-translation`: lambda syntax and ClickHouse operand order in the accepted subset; the
  scope rule for a lambda body, including capture; lowering a lambda into the DSL and rendering it
  back.
- `query-schema-discovery`: `GET /v1/queries/functions` advertises the new entries and a new
  `lambda` argument kind.
- `aggregate-pipeline`: the measure allowlist is derived from the catalog's `aggregate` group, so
  the two new aggregates would widen it by default; they are instead refused as a measure `fn` with
  a message that states the real reason, exactly as `arg_max` already is.

## Impact

- **Code**: `service/query/model/` (`LambdaExpr`, `LambdaParamExpr`), `service/query/`
  (`HigherOrderFunctions`), `service/query/translate/` (`LambdaScope`, the new `ExprTranslator`
  branches, `ExprFieldType`), `service/query/sql/` (`SqlLambdaRewriter` — the pre-validation
  inlining, `SqlQueryOperators`, `RexToExprTranslator`, `RelToStructuredQueryTranslator`'s aggregate
  carve-out and result typing, `StructuredQueryToSqlRenderer`, `SqlProjectionOnlyTypes`),
  `ArrayValuedFunctions` (the per-function array-operand position), `QueryFunctionCatalog` and
  `QueryFunctionArgKind`, `service/pipeline/AggregateSpecValidator` (the measure refusal).
- **APIs**: `POST /v1/queries/execute`, `/execute-sql`, `/translate`, `/translate-sql`,
  `GET /v1/queries/functions`, and Flight SQL — all widen; none change shape. No new error code:
  a malformed lambda is an ordinary `400` from the existing validation path.
- **No configuration, no migration, no dependency change.** Calcite 1.42's Babel grammar already
  parses `x -> expr`, which is the only thing of its lambda machinery this change uses.
