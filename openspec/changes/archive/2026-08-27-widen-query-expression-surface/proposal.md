## Why

The query surface cannot express a ratio, cannot read a key out of a `map`, and cannot filter an
`array`. While building a "how the org uses AI"
dashboard, this blocked five concrete demo queries — and the gap is not a policy that the grammar
stays closed, it is a catalog that was never widened. `abs` is the **only** numeric operation in the
28-function catalog, so every efficiency signal (cache-hit rate, cost per turn, tokens per accepted
edit) has to be precomputed as a rollup or exported and divided client-side.

Two of these are not merely inconvenient:

- **`otel_claude_code_metrics` is structurally unqueryable in aggregate.** Its only non-`map`
  dimensions are `service_name`, `metric_name`, `metric_type`, `scope_name` and the metric
  descriptors. Every *business* dimension — person, session, model, agent, effort — lives in
  `point_attributes`, a `map`, and the entity carries no enrichments. Unlike the JSON columns
  (`request_tags`, `jwt_claims`, which `json_extract_*` reads fine) there is no workaround at all:
  `SELECT json_extract_string(point_attributes,'model')` returns
  `400 map field 'point_attributes' can only be projected`.
- **The `structured-query` spec already asserts behaviour the service does not have.** Its shipped
  scenario at `spec.md:97` exercises `round(div(sum(total_tokens), count()), 2)`. Neither `round` nor
  `div` exists. This change corrects a live spec/implementation contradiction, not only adds surface.

The extension path is sanctioned and rehearsed: `2026-08-26-add-derivation-expression-functions`
added seven functions through exactly these files one day earlier.

## What Changes

**Sixteen new scalar functions** in the closed catalog. All scalars — no new aggregate, so the
aggregate-pipeline measure vocabulary is untouched.

| Function | Returns | Purpose |
|---|---|---|
| `plus(a,b)`, `minus(a,b)`, `multiply(a,b)`, `div(a,b)` | numeric | the arithmetic that ratios and deltas need |
| `modulo(a,b)` | numeric | remainder — bucketing and cyclical grouping |
| `round(x[,n])`, `floor(x)`, `ceil(x)` | numeric | presentable ratios |
| `map_value(map, key)` | string | read one key out of a `map` column |
| `map_contains(map, key)` | boolean | test a key's presence without reading its value |
| `map_keys(map)` | array | the map's keys — what a map actually carries, and its size via `array_length` |
| `array_has(arr, v)`, `array_has_any(arr, [v…])`, `array_has_all(arr, [v…])` | boolean | array predicates in filter and `if` position |
| `array_length(arr)` | integer | element count — "users with no role" |
| `split_string(text, delim)` | array | array from a delimited string, elements trimmed |

- **`div` and `modulo` return NULL for a zero or NULL right operand, never an engine error.** This is
  not ClickHouse's default — `/` yields `inf`/`nan` on floats and *throws* on integers, and `modulo`
  throws on a zero divisor — so it is a rendering obligation for both, not a free consequence. A ratio
  panel must not fail because one group has an empty denominator.
- **`map_value` and `map_contains` take a single, non-variadic, literal key matched exactly.** Every
  other extractor in the catalog uses a variadic *path* (`json_extract_string(x,'user','email')`
  traverses). A `Map(String,String)` has one flat key namespace and the live keys are dotted
  (`user.email`, `session.id`), so path semantics would silently return empty. A missing key gives
  `map_value` the empty string (matching `json_extract_string`) and `map_contains` false.
- **`map_keys` returns an array, so its result is projection-only** on the same footing as
  `json_extract_array`'s and `split_string`'s — except as an array accessor's first argument, which is
  what makes `array_length(map_keys(m))` (a map's size) and `array_has(map_keys(m), 'k')` expressible.
  The latter overlaps `map_contains` deliberately: `map_contains` is the direct form and the one a
  filter should use, `map_keys` is for discovering *which* keys a producer-defined map actually
  carries — the question that cannot be asked at all today.
- **Infix arithmetic in the SQL subset.** `a + b`, `a - b`, `a * b`, `a / b`, `a % b` and unary minus
  lower to the named functions above, in projection, `WHERE` and `HAVING`, with normal precedence. No
  new `Expr` kind and no operator node in the DSL — Calcite already parses these into `RexCall`s and
  `RexToExprTranslator` lowers them, the way `LOWER`/`ABS`/`TRIM` already are. `MOD(a, b)`, Calcite's
  standard function spelling of the same operation, lowers to `modulo` too. The arithmetic **catalog
  names are the DSL spelling only** — `minus(a, b)` cannot parse as a SQL function because `MINUS` is a
  reserved set operator in the grammar, so rather than accept four of five, SQL spells arithmetic with
  operators throughout.
- **A boolean-valued call becomes usable as a predicate.** `WHERE array_has(roles, 'admin')` requires it,
  and predicate position previously accepted only a bare boolean *column* — so `not_empty`,
  `starts_with`, `contains`, `equals` and `json_extract_bool` were all unusable in `WHERE` too. Widened
  for the whole family rather than for the array predicates alone, since the alternative is an arbitrary
  line through one operand type. Not breaking: each of these previously failed translation.
- **ISO-8601 string literals coerce to timestamp** when compared against a timestamp column:
  `'2026-08-20'`, `'2026-08-20 00:00:00'`, `'2026-08-20T00:00:00Z'`. Today only
  `TIMESTAMP '2026-08-20 00:00:00'` is accepted, and Grafana, Power BI and most generated SQL emit
  the bare string. Anything unparseable still fails with a clear `invalid_argument`.
- **`map` and `array` columns gain a positional carve-out** in `SqlProjectionOnlyTypes`, on the same
  footing as the existing `object`-as-`json_extract_*`-argument exception: a `map` field is legal as
  argument 0 of `map_value`/`map_contains`/`map_keys`, an `array` field as argument 0 of
  `array_has*`/`array_length`. They stay projection-only everywhere else.
- **The envelope `distinct` flag gets a spec requirement.** It ships today — `StructuredQuery.distinct`
  → `StructuredQueryBuilder:117`, covered by `queryLevelDistinct`, verified against a running instance — but the
  `structured-query` spec has no requirement for it and does not list it in the envelope enumeration,
  which is why it reads as missing. Documentation of shipped behaviour, no code change.
- **`distinct_supported: false` gains its rationale** in the catalog `description` of `min`, `max`,
  `percentile_cont` and `percentile_disc`: `min(distinct x) ≡ min(x)` is a no-op, and
  `percentile(distinct x)` measures a different distribution. Today the flag carries no explanation,
  so it reads as arbitrary.
- **`point_attributes` is declared `sensitive`.** The column holds `user.email` in cleartext and is
  flagged `sensitive: false`; `map_value` makes it aggregatable, which turns a projectable column
  into a one-query named per-person cost ranking. Sensitivity is whole-column, so this also hides
  `model`, `effort` and `query_source` from any principal that is not `FULL_ADMIN` — **the demo
  queries this change exists to enable will require `FULL_ADMIN`.** That tradeoff is accepted
  deliberately; see design. `resource_attributes` and `scope_attributes` are verified to carry only
  host/OS/service metadata and stay unflagged.

Not breaking: every function, operator spelling and cast is new surface, and the `distinct` and
`distinct_supported` items are documentation. The one behaviour change is the `point_attributes`
sensitivity flag, which **narrows** read access.

### Deliberately out of scope

Recorded so they are visibly declined rather than silently dropped:

- **`array_join` / `unnest`** — the ask's other half, deferred to its own change. It changes row
  cardinality, so it is not an `Expr` at all: it needs a new `StructuredQuery` section and it
  interacts with `QueryBudget` cost estimation, `include_total`/paging, sensitive-column masking, and
  a Calcite `LogicalCorrelate` that `RelToStructuredQueryTranslator` does not handle. `array_has` +
  `if` already covers every closed-vocabulary distribution (roles, MCP tools, models, deployments,
  `execution_path`) in a single query; the only open-vocabulary case is
  `conversation_insights.topics`, for which a normalized enrichment is both cheaper and faster than
  exploding arrays per panel.
- **`uniq(x)`** — `count(DISTINCT x)` already works on both surfaces. `uniq` is ClickHouse-specific;
  standard BI generators emit `COUNT(DISTINCT …)`, which is already accepted. Adding it would also be
  the change's only new aggregate, widening the aggregate-pipeline measure vocabulary as a side
  effect nobody asked for.
- **Key-level sensitivity for `map` columns** — the principled fix for the PII tradeoff above, and a
  new capability in `table-access-control`/`table-management` larger than the rest of this change
  combined. Recorded in design as the follow-up.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: sixteen rows added to the supported function catalog with their semantics —
  `div`'s and `modulo`'s NULL-on-zero-right-operand rule, `map_value`'s and `map_contains`'s
  literal-single-key rule, `map_keys`' projection-only array result, `array_has*`'s
  false-on-empty-or-missing rule, `split_string`'s trimming; a requirement for the envelope
  `distinct` flag; the `distinct_supported` rationale.
- `query-schema-discovery`: `GET /v1/queries/functions` advertises the sixteen new entries; the
  "lists all supported functions" scenario enumerates names and must name them.
- `sql-query-translation`: the subset accepts the sixteen calls and infix `+ - * / %` and unary minus
  (plus `MOD`); the CHAR→TIMESTAMP cast joins the tolerated cast pairs; the projection-only carve-out
  widens to `map` as `map_value`/`map_contains`/`map_keys`'s argument 0 and `array` as
  `array_has*`/`array_length`'s argument 0; `map_keys`' and `split_string`'s array results are
  projection-only as `json_extract_array`'s is.
- `query-translation-endpoint`: DSL → SQL rendering and round-trip for the new calls, including
  whether `div`/`modulo` render as operators or function calls, and the widened carve-outs.
- `class1-enrichment`: a `type: sql` evaluator's output expression is validated through the same
  catalog, so arithmetic, the three map accessors and the array predicates become expressible in a
  derivation.
- `otel-sink`: the metrics landing table declares `point_attributes` as a sensitive column.

## Impact

**Surfaces.** No new endpoints. Behaviour changes on `GET /v1/queries/functions`,
`POST /v1/queries/execute`, `/execute-sql`, `/translate`, `/translate-sql`, Flight SQL,
`POST /v1/evaluators`, `POST /v1/rules`, and `POST /v1/otel/sinks`. No config keys.

**Code.** `QueryFunctionCatalog` and `translate.ExprTranslator` (held in parity by
`QueryFunctionCatalogParityTest`); `SqlQueryOperators`, `RexToExprTranslator` (the new function names
plus the arithmetic `SqlKind` lowering and the widened `isSupportedCast`),
`RelToStructuredQueryTranslator`, and `SqlProjectionOnlyTypes` — the single classifier both
directions consult, whose `isAllowedFunctionArgument` already models a positional, type-specific
carve-out and gains two more type/function pairings; `StructuredQueryToSqlRenderer` for
translate-back; `ArrayValuedFunctions` for `map_keys`' and `split_string`'s array results;
`ExprFieldType` for the new return types.
`OtelSinkSchemas` for the sensitivity flag.

**Operational.** `OtelSinkService:109` applies a sink's schema **once, at sink creation** — there is
no startup reconciliation of column metadata. So the `OtelSinkSchemas` change protects only sinks
created after it ships; an already-materialized `otel_claude_code_metrics` needs a separate
`PATCH /v1/tables/{name}` to pick up the flag. Both are required, or the flag silently does not apply
to the data that motivated it.

**Risks carried into design.** The NULL contract on `div` and `modulo` has to survive ClickHouse's
own division semantics on both integer and float operands — `modulo` throws on a zero divisor exactly
as integer division does, so it needs the same guard and not a weaker one. New function names must be
registered as custom operators ahead of Calcite's standard table (the `DATE_TRUNC` precedent) or a
standard-table entry shadows them; `CONTAINS`/`EQUALS` proved non-reserved in the Babel grammar, but
`ROUND`, `FLOOR`, `CEIL` and `MOD` are Calcite standard functions and may need explicit handling
rather than registration. The
`json_extract_string_ci` precedent from the preceding change is the live-risk template: a naive
rendering over a `map`/`object` column returned empty for every row **without raising anything** on
ClickHouse 26.3, so all three map accessors and `array_has*` need live-ClickHouse assertions on
non-empty results rather than review. The `otel_claude_code_metrics` feed currently holds 709 data points, so a
green integration test says nothing about whether the demo panel has data — that is an ingest
question this change does not address.
