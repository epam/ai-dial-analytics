## Context

The structured query DSL accepts a closed allowlist of functions, enforced by
`service.query.translate.ExprTranslator#toFunction` (a `switch` whose `default` rejects with 400).
The allowlist — 14 functions today, including `date_bin` — is normatively defined in
`openspec/specs/structured-query/spec.md` ("Supported function catalog") but is not discoverable at
runtime. Schema discovery already lives in `web.controller.query.QuerySchemaController` under
`/v1/queries` (entities + flat schemas, served by `CatalogQueryService`).

## Goals / Non-Goals

**Goals:**

- Machine-readable runtime discovery of the supported function catalog: names, groups, signatures,
  return types, `distinct` support, and argument constraints (allowed `date_bin` units,
  `percentile_*` fraction range, literal-vs-expression argument kinds).
- A single in-code source of truth so the advertised catalog cannot drift silently from what the
  translator accepts.

**Non-Goals:**

- No change to validation or translation behavior; `ExprTranslator` keeps rejecting exactly as today.
- No per-entity or per-role function filtering — the catalog is global and static (all functions
  apply to all entities; nothing in it is sensitive).
- No SQL (`execute-sql`) function discovery — that surface is governed by Calcite's dialect, not this
  allowlist.

## Decisions

### 1. Static descriptor catalog + parity test (not a translator refactor)

A new `service.query.QueryFunctionCatalog` holds an immutable, ordered list of function descriptors
(pure data: name, group, args, returns, distinct flag, constraints). `ExprTranslator` is NOT
rewritten to be table-driven — its hand-written arity/type checks stay as they are.

- *Why not derive the endpoint from the translator?* The translator is a `switch` over behavior;
  there is nothing to reflect over. Making it data-driven would rewrite the most safety-critical
  class in the query path for zero behavior gain.
- *Drift control:* shared constants + a parity test (below). The constraint values that appear in
  both places move to the catalog and are referenced from the translator: `ExprTranslator`'s
  `DATE_BIN_UNITS` map stays (it maps unit → ClickHouse keyword) but its key set is asserted equal
  to the catalog's advertised units by test.

### 2. Parity is enforced by tests in both directions (as far as mechanically possible)

- Forward: for every catalog entry, build a minimal valid `FnExpr` (using each descriptor's declared
  arity and literal kinds) and assert `ExprTranslator.toField` accepts it.
- Constraint parity: `date_bin` advertised units == `DATE_BIN_UNITS.keySet()`; advertised
  `distinct` flags match translator behavior (a `distinct: true` call on a non-distinct function is
  simply ignored by the translator today — the flag advertises where `distinct` is *meaningful*).
- Reverse (translator supports nothing unadvertised): not mechanically provable against a `switch`;
  mitigated by a cross-referencing comment on `toFunction` naming the catalog, plus the existing
  spec table which review keeps in sync.

### 3. Wire contract: `GET /v1/queries/functions` → JSON array (no wrapper)

Follows the `GET /v1/queries/entities` precedent (bare `List<...>`), same controller
(`QuerySchemaController`), snake_case fields per the DSL convention, stable alphabetical order by
function name. Shape per entry:

```json
{
  "name": "date_bin",
  "group": "scalar",
  "signature": "date_bin(amount, unit, timestamp)",
  "returns": "timestamp",
  "distinct_supported": false,
  "description": "Truncates timestamp to the start of the fixed-width interval amount × unit.",
  "args": [
    {"name": "amount", "kind": "integer_literal", "constraints": {"min": 1}},
    {"name": "unit", "kind": "string_literal", "constraints": {"allowed_values": ["day", "hour", "minute", "second", "week"]}},
    {"name": "timestamp", "kind": "expression"}
  ]
}
```

- `group`: `scalar` | `aggregate` | `ordered_set_aggregate` (mirrors the spec table's groups).
- `args[].kind`: `expression` (any resolvable expression) | `integer_literal` | `numeric_literal` |
  `string_literal`. `count()`'s optional argument carries `"optional": true` (the only variadic-ish
  case; `count()` vs `count(col)`).
- `args[].constraints`: optional object, only the keys that apply — `allowed_values` (list),
  `min` / `max` (numeric bounds, used for `date_bin` amount ≥ 1 and `percentile_*` fraction
  ∈ [0, 1]). Omitted (JSON `null`-free) when unconstrained.
- `returns`: `string` | `integer` | `long` | `numeric` | `timestamp` | `same_as_argument`
  (for `min`/`max`/`percentile_disc`, whose return type follows the operand).
- DTOs: `web.dto.query.QueryFunctionDto`, `QueryFunctionArgDto`, `QueryFunctionArgConstraintsDto`;
  Jackson `@JsonInclude(NON_NULL)` on optional members so absent constraints don't serialize.

### 4. Endpoint placement and access

Lives on `QuerySchemaController` with the other discovery endpoints; inherits whatever
authentication applies to `/v1/queries/**` (no role gating — the catalog is static, public-to-any-
authenticated-caller metadata, with no sensitive content). No new config. Errors: none specific —
the endpoint takes no input; the shared `ErrorView` contract covers the namespace as usual.

## Risks / Trade-offs

- [Catalog drifts from translator when someone adds a function to only one side] → forward parity
  test fails if a catalog entry isn't accepted; the reverse direction is covered by a pointed
  comment on `toFunction` + spec review discipline (structured-query's table must change in the
  same PR anyway).
- [Descriptors encode arity/kinds a second time] → accepted duplication; it is pure data, tested
  against the behavior, and far cheaper than making the translator table-driven.
- [`same_as_argument` return type pushes type resolution onto clients] → intentional: the server
  does not type-infer expressions at discovery time; clients know the operand's type from the
  entity schema.
