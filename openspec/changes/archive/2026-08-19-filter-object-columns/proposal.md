## Why

`object` columns (ClickHouse native `JSON`) are **projection-only** today: a client can select one
and receive the whole document, but cannot narrow a result set by anything inside it. The
`json_extract_string|long|double|bool` family already gives exactly that reach — but only over
`string` columns holding JSON *text*. Pointing it at an `object` column fails: the DSL renders
`JSONExtractString(meta, 'model')` and ClickHouse rejects it with `ILLEGAL_TYPE_OF_ARGUMENT`
("The first argument of function JSONExtractString should be a string containing JSON, illegal
type: JSON"), while the SQL front end refuses even earlier with "object field 'meta' can only be
projected".

So the same query is expressible against a JSON-in-`String` column and inexpressible against the
typed `object` column that supersedes it. Callers who model a document column properly are pushed
back to `String`, or forced to pull every row and filter client-side.

## What Changes

- **`json_extract_*` is overloaded to accept an `object` field as its JSON argument.** The four
  function names, their arity, and their fixed return types are unchanged; only the accepted type of
  the first argument widens. Where that argument resolves to an `object`-typed field, the translator
  renders **native JSON subcolumn access** (`toString("meta"."usage"."total_tokens")` and its numeric
  / boolean coercions) instead of the `JSONExtract*` text accessor. Observable semantics are
  identical to the `string` case — same values, same coercions, same totality: an absent path, a null
  member, or a type mismatch yields the extracting type's default, never an error and never a null.
- **Filtering, sorting, grouping and aggregating over extracted paths follow for free**, because
  `json_extract_*` is an ordinary expression in the grammar: `json_extract_string(meta, 'model') =
  'gpt-4o'` in `filter`/`having`, as a `sort` or `group_by` key, or as an aggregate argument.
- **The SQL surface accepts the same construct.** `SqlProjectionOnlyTypes` currently rejects an
  `object` field in every position but a bare projection item. That rejection is narrowed: an
  `object` field is now also legal as the **first argument of `JSON_EXTRACT_*`**. Every other
  non-projection use (a direct comparison operand, a `sort`/`group_by`/`DISTINCT` key, any other
  function's argument) still fails closed with the existing wording.
- **The DSL→SQL renderer stops tripping over it**, so a DSL query filtering on an extracted object
  path renders to SQL and round-trips back to the same DSL query.
- **`array` fields stay projection-only** and are untouched — the DSL still has no array literal or
  comparison.
- **The bare `object` field gains no new reach.** Every use outside the `json_extract_*` JSON
  argument — direct comparison, sort, grouping, `distinct`, any other function — is rejected exactly
  as it is today.
- **Subcolumn access makes it fast, not merely possible.** Measured on ClickHouse 25.3, 300k rows
  whose documents carry an unrelated 400-byte member: filtering on a path reads **6.87 MiB in 15 ms**
  through subcolumn access versus **135.51 MiB in 404 ms** through whole-document serialization —
  ~20× less data for byte-identical results, because ClickHouse reads only the touched subcolumn.
- No new endpoint, no new function, no config, no migration, and no ClickHouse version bump — all of
  it works on the deployed 25.3. The function catalog at `GET /v1/queries/functions` is unchanged:
  `json_extract_*` already advertises its `json` argument as a generic `expression`, and this change
  widens what that accepts rather than altering the advertised contract.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: the "Supported function catalog" requirement's `json_extract_*` paragraph is
  amended — the JSON argument may be an `object`-typed field as well as a JSON-valued text
  expression, rendering through `toJSONString(...)`, with identical totality semantics.
- `sql-query-translation`: the "Object fields are projection-only in SQL" requirement is narrowed —
  an `object` field is additionally legal as the first argument of `JSON_EXTRACT_*`; every other
  non-projection use still fails closed.
- `query-translation-endpoint`: the DSL→SQL untranslatable-construct requirement is narrowed the
  same way — an `object` field passed to `json_extract_*` renders instead of being rejected as a
  function argument, while `count(<object field>)` and the other listed uses still reject.

## Impact

**Surfaces** — behavior changes on `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`,
`POST /v1/queries/translate`, `POST /v1/queries/translate-sql`, and the Flight SQL query path
(which shares the SQL front end). All are widenings: queries that are rejected today start
succeeding; nothing that succeeds today changes shape or result.

**Code**

- `service/query/translate/ExprTranslator` — `jsonExtract(...)` renders an `object`-typed JSON
  argument as subcolumn access plus the per-function coercion, instead of the `JSONExtract*` accessor.
- `service/query/sql/SqlProjectionOnlyTypes` — the classification gains an "allowed as a
  `JSON_EXTRACT_*` JSON argument" carve-out.
- `service/query/sql/RexToExprTranslator` — `rejectProjectionOnlyOperand` is not applied to the
  first operand of a `JSON_EXTRACT_*` call.
- `service/query/sql/StructuredQueryToSqlRenderer` — `rejectNonProjectionUse` allows an `object`
  field in that same position.
- `service/query/sql/RelToStructuredQueryTranslator` — no result-kind change (the `json_extract_*`
  result kinds are already fixed by function name), verified only.

**Access control** — unchanged and unbypassed. `sensitive` object columns are still absent from the
bindings a non-`FULL_ADMIN` caller sees, so naming one in an extraction fails as an unknown column,
exactly as it does in a projection today. A `heavy` object column stays nameable: filtering on a
path inside it transfers no payload, so heaviness does not block it (it only excludes the column
from wildcard expansion).

**Performance** — subcolumn access reads only the touched path, so a filter over an `object` column
is ~20× cheaper than the same filter over a `string` column holding the same document (6.87 MiB vs
135.51 MiB on the 300k-row benchmark in design.md). Existing `string`-column queries are unaffected —
their rendering does not change.

**Not affected** — no dependency, no ClickHouse or PostgreSQL migration, no configuration key, no
`docs/configuration.md` entry, no change to the table-management catalog or to `object` column
projection/serialization.
