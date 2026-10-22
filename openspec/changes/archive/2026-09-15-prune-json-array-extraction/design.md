## Context

`JsonExtractFunctions` splits the family in two: `PATH_ADDRESSABLE`, read from a native JSON subcolumn
at a literal path, and `SERIALIZED_TEXT`, read from `toJSONString(<column>)`. Both the renderer
(`ExprTranslator`) and the pruning collector (`ObjectPathUsage` via `collectObjectPaths`) and the
registration advisory (`PipelineJsonReadNotes`) resolve the membership from that one class, so moving a
name moves all three together — which is what this change relies on.

`json_extract_array` was placed in `SERIALIZED_TEXT` when the `object` overload was widened, on the
ground that "an array member comes back typed `Dynamic` rather than as an array of text". That is true
of the member's type and false as a conclusion about addressability.

## Goals / Non-Goals

**Goals:**

- `json_extract_array` over an `object` field reads one subcolumn, not the whole column.
- No observable change to any value it returns, on any shape of member.
- `usage-client-identity-live` materializes under the 2 GiB ceiling.

**Non-Goals:**

- Making `json_extract_string_ci` cheap. It has no statically known subcolumn by construction.
- Changing `json_extract_array`'s projection-only rule, its arity, or its result type.
- Any change to the text-column rendering, which is what the object form must keep agreeing with.

## Decisions

### D1 — Re-serialize the subcolumn; never stringify it

**Decision.** Over an `object` field, render
`JSONExtract(assumeNotNull(ifNull(toJSONString(<col>.<key>…), '')), 'Array(String)')`.

**Why not `toString`.** `JSONExtract(toString(<col>.<key>), 'Array(String)')` is the natural spelling —
it reuses the `toString` the four scalar extractors already share in `subcolumnExtract` — and it is
silently wrong. `toString` over an array subcolumn renders ClickHouse's array syntax, `['a','b']`, whose
single-quoted strings are not JSON; `JSONExtract` returns the empty array rather than failing. Only
**quoted strings, nulls and composites** diverge between the two syntaxes — bare integers, floats and
booleans render identically in both — so the defect survives any spot check that avoids those three.
Measured on ClickHouse 26.3.24:

| member | stored | whole-document form | `toString` form | shipped form |
|---|---|---|---|---|
| `roles` | `["a","b"]` | `['a','b']` | `[]` | `['a','b']` |
| `mix` | `[1,"q"]` | `['1','q']` | `[]` | `['1','q']` |
| `obj` | `[{"x":1}]` | `['{"x":1}']` | `[]` | `['{"x":1}']` |
| `wn` | `[null,1]` | `['','1']` | `[]` | `['','1']` |
| `nums` | `[1,2]` | `['1','2']` | `['1','2']` | `['1','2']` |
| `s` | `"abc"` | `[]` | `[]` | `[]` |
| `d` | `{"k":1}` | `[]` | `[]` | `[]` |
| absent | — | `[]` | `[]` | `[]` |

Over a real column the `toString` form agreed with the shipped form on **none** of the sampled rows; the
re-serializing form agreed on every one, and on every non-empty result over a full day.
Cost over that window: on the order of 100 KiB peak and a fraction of a second against roughly 100 MiB and several seconds.

**Why both null wrappers.** `toJSONString` over a subcolumn is typed `Nullable(String)` — an absent path
really is NULL — and `JSONExtract` refuses a `Nullable` operand at analysis time with
`Code 43: Nested type Array(String) cannot be inside Nullable type`. `ifNull` fixes the **value**,
substituting the empty string, which extracts to the empty array — exactly what the whole-document form
returns for an absent path, and what the function's contract promises. `assumeNotNull` pins the **type** rather than
relying on the analyzer's `Nullable` inference, which has moved across releases. It is not load-bearing
on the versions measured: both 26.3.21 and 26.3.24 fold `ifNull(Nullable(String), '')` to `String` and
accept the extraction without it, over a literal, over a real MergeTree `JSON` column, under
`enable_analyzer=0`, and through a `LEFT JOIN` at either setting of `join_use_nulls`. What fails on
**both** versions is the **un-wrapped** `JSONExtract(toJSONString(<sub>), 'Array(String)')`. Because
`ifNull` has already guaranteed a non-NULL value, `assumeNotNull` never meets a NULL and its
unspecified-on-NULL behaviour is never reached, so it is kept as a cheap pin on a type the extractor
refuses to infer wrongly.

**Alternatives rejected.**
- *Typed subcolumn access* (`.:Array(String)`). Strict: it yields NULL when the stored member's type
  differs from the requested one, where the text accessors coerce — the same reason `subcolumnExtract`
  uses the untyped subcolumn plus a coercion for the four scalars.
- *Keep the whole-column fallback and raise the ceiling.* Moves the number and keeps the trap; the
  `FINAL` measurement (on the order of a GiB against a hundred-odd MiB) says the gap is a factor, not a margin.

### D2 — Move the name, do not special-case it

**Decision.** `json_extract_array` moves from `SERIALIZED_TEXT` to `PATH_ADDRESSABLE` in
`JsonExtractFunctions`, and the array rendering branches inside `subcolumnExtract` before the shared
`text` field is built.

**Why.** The pruning collector, the renderer's fallback predicate and the registration advisory all ask
the same class the same question. Moving the name answers all three at once and in step; special-casing
the renderer alone would prune nothing, and special-casing the collector alone would render a statement
addressing a column the derived table no longer projects.

**The branch position is the whole of the risk.** `subcolumnExtract` opens by computing
`final Field<String> text = toString(value)` and switching per function. Routing the array branch through
that shared `text` **is** the broken rendering of D1, and it is the edit a later reader is most likely to
make while tidying. The branch therefore sits above it, takes `value` directly, and carries a comment
saying so — the one place a future change would land.

## Risks / Trade-offs

- **A silently emptied `user_roles` if the rendering is ever "simplified"** → *Mitigation*: the render
  tests assert the absence of `JSONExtract(toString(` as well as the presence of the shipped form, and
  the integration fixture carries an array of strings alongside the all-integer array precisely because
  an integer-only fixture passes either way.
- **Patch-version drift in ClickHouse's Nullable inference** → *Mitigation*: `assumeNotNull` pins the
  type, so the rendering does not depend on the inference at all. **The suite does not cover the drift**:
  `ClickHouseContainerSupport` pins the floating tag `clickhouse/clickhouse-server:26.3`, so the version
  the integration tests run is whatever that resolves to at pull time and can move without any change
  here; and no test exercises the un-wrapped or single-wrapper forms, so a change in inference either way
  would go undetected. Pinning the tag was considered and deliberately not done in this change — it
  changes build behaviour for every test, not just this one, and belongs in its own change.
- **A pruned `json_extract_array` now depends on the alias contract** it did not use before → shares
  `ObjectPathUsage.alias`/`subcolumn` with the four scalars, and a keyed-join test renders the
  projection and the outer reference together rather than asserting either against a hand-written
  expectation.

## Migration Plan

No migration, no config key, no credential. Deploy is a rolling restart; the statements ADAS renders
change shape and nothing persisted changes. Rollback is the previous image — the rendering is a
render-time decision with no stored artefact, and every declaration is valid against both builds.

Verification against a running deployment: materialize `usage-client-identity-live`, then confirm
`sessions_rollup` advances behind it.

## Open Questions

None.
