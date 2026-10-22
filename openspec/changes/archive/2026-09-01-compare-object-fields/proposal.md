## Why

An `object` column (ClickHouse native `JSON` — `request_tags`, `jwt_claims`) can be **projected** and
can be **read key-by-key** through the `json_extract_*` carve-out, but the document as a whole cannot
be filtered on. A caller asking the obvious question — "which requests mention `gpt-4` anywhere in
their tags?" — gets
`object field 'request_tags' can only be projected; a 'filter' comparison on an object field cannot be
expressed in SQL` from `POST /v1/queries/translate`, and has no way to express it at all.

Worse, the three surfaces disagree about it. `POST /v1/queries/execute` applies **no operand-type
check whatsoever** (`FilterTranslator.toComparison` translates any expression to a jOOQ condition and
hands it to ClickHouse), so the same filter that fails closed on the translate path fails *open* on
the execute path. Verified against the deployed ClickHouse 26.3:

| DSL filter on an `object` field | What ClickHouse does with the rendered SQL today |
| --- | --- |
| `co` / `ico` / `nc` / `inc` | `Code 43 ILLEGAL_TYPE_OF_ARGUMENT` |
| `lt` / `gt` / `le` / `ge` against a string | `Code 117 INCORRECT_DATA` |
| `eq` / `ne` against a non-JSON string | `Code 117 INCORRECT_DATA` |
| `eq` against a **valid JSON** string | **returns zero rows, silently** — even for a row whose document is exactly that value |

The first three reach the caller as a 400 (the "ClickHouse rejects the query" scenario of the
"Query validation and allowlist" requirement maps them), but as a *ClickHouse type error* rather than
the stable, field-naming validation error the same document gets from `/v1/queries/translate` — and
the same requirement states that "no raw ClickHouse internals are exposed".

The fourth row is the real defect, and it is not an error at all: a plausible-looking query returns a
plausible-looking (empty) answer that is simply wrong.

The comparison *is* expressible; only the rendering was missing. ClickHouse's `toJSONString` over a
native `JSON` column is a **canonical** form — keys sorted recursively, whitespace stripped, numbers
normalised, string/number distinction preserved:

| stored document | `toJSONString` |
| --- | --- |
| `{"b":2,"a":1}` | `{"a":1,"b":2}` |
| `{ "a" : 1 }` | `{"a":1}` |
| `{"a":1.0}` | `{"a":1}` |
| `{"n":{"z":1,"y":2}}` | `{"n":{"y":2,"z":1}}` |

Canonicalising *both* sides — `toJSONString(doc) = toJSONString(CAST(<literal> AS JSON))` — gives
document equality that is independent of key order, whitespace and number format, so the caller never
has to guess ClickHouse's serialisation. Verified: the literal `{"b":2,"a":1}` matches rows stored as
both `{"b":2,"a":1}` and `{"a":1,"b":2}`; the literal `{ "a" : 1.0 }` matches rows stored as both
`{ "a" : 1 }` and `{"a":1.0}`.

## What Changes

- **A comparison whose operand is an `object` field becomes expressible on every surface** — the DSL
  execute path, `POST /v1/queries/translate`, `POST /v1/queries/translate-sql`,
  `POST /v1/queries/execute-sql` and Flight SQL — for a bounded operator set, in `filter` and
  `having` alike, round-tripping DSL ↔ SQL:

  | operator | meaning | execute rendering | SQL rendering |
  | --- | --- | --- | --- |
  | `co` / `nc` | case-sensitive substring of the canonical JSON text | `like(toJSONString(f), …)` | `"f" LIKE '…'` |
  | `ico` / `inc` | case-insensitive substring | `ilike(toJSONString(f), …)` | `"f" ILIKE '…'` |
  | `eq` / `ne` | canonical document (in)equality | `toJSONString(f) = toJSONString(CAST(v AS JSON))` | `"f" = '…'` |
  | `in` | canonical document membership | the same over a set | `"f" IN ('…', '…')` |

- **The right operand of `eq`/`ne`/`in` on an `object` field must parse as JSON.** A literal that does
  not is rejected with a stable HTTP 400 naming the field and the literal, before any ClickHouse
  contact — today it reaches ClickHouse and returns `Code 117`.

- **The DSL surface gains the operand-type check it never had.** Every comparison that stays
  inexpressible now fails closed with the same stable, field-naming 400 that `/v1/queries/translate`
  already returns, instead of a relayed ClickHouse type error or a silent empty result:
  - `lt` / `gt` / `le` / `ge` on an `object` field. Ordering over canonical JSON text is *renderable*
    but meaningless — "documents alphabetically before this string" answers a question nobody asked,
    and a plausible-looking wrong answer is worse than an error.
  - `eq` / `ne` against a **null literal** on an `object` field. Left rejected deliberately: a
    ClickHouse `JSON` column is never `NULL`, so `IS NULL` / `IS NOT NULL` is a constant
    false / true — verified — and accepting it would hand callers a predicate that silently
    matches nothing or everything.
  - **every** comparison on an `array` or `map` field. `array` has no round-tripping SQL form at all
    (Calcite types it as a true `ARRAY`, so `LIKE` does not type-check), and `map` has no canonical
    text rendering the SQL subset could name.

- **Nothing else about the projection-only classification moves.** `sort`, `group_by`, `distinct`,
  and every function/aggregate argument over an `object`, `array` or `map` field are rejected exactly
  as they are today, with the same wording. The `json_extract_*` carve-out is untouched and remains
  the precise tool for per-key filtering; the widened operators search the whole document, so they
  match keys as well as values and match the escaped form of a value.

- No new endpoint, no new DSL function, no new config, no migration, no ClickHouse version bump.
  The function catalog at `GET /v1/queries/functions` is unchanged — this widens which *comparison
  operands* are accepted, not the function surface.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: one added and **three** modified requirements. A new requirement states that an
  `object` field is comparable as a canonical JSON document under the
  `co`/`nc`/`ico`/`inc`/`eq`/`ne`/`in` operators, with the canonicalisation contract and the
  JSON-literal validation rule. "Query validation and allowlist" gains comparison-operand type
  checking in its coverage list, so the DSL surface stops deferring the whole classification to the
  SQL surface. "Map fields are projection-only when translating to SQL" is amended because its
  "on the same footing as an `array` and an `object` field" cross-reference no longer holds. And
  "Supported function catalog" is amended because its sentence that widening the `json_extract_*` JSON
  argument "SHALL be the whole of the change to this capability" would otherwise contradict the new
  requirement — it is narrowed to the non-comparison positions, which this change genuinely leaves
  alone.
- `sql-query-translation`: the "Object fields are projection-only in SQL" requirement is narrowed —
  an `object` field is additionally legal as an operand of `LIKE`/`ILIKE`/`=`/`<>`/`IN` against a
  character literal. Ordering comparisons, `IS NULL`/`IS NOT NULL`, `GROUP BY`, `ORDER BY`,
  `SELECT DISTINCT` and non-`JSON_EXTRACT_*` function arguments stay rejected with the existing
  wording. The array and map requirements are unchanged.
- `query-translation-endpoint`: the passage listing what `POST /v1/queries/translate` refuses is
  amended so a comparison operand on an `object` field is no longer blanket-rejected, and the
  "Object field in filter rejected" scenario is split into the accepted and still-rejected operator
  sets. The array scenarios are unchanged.
- `rest-sql-endpoint`: the "Object misuse rejected per the translation contract" scenario keeps its
  `IS NULL` example (still rejected) and gains a companion scenario for an accepted
  `WHERE request_tags ILIKE '%gpt-4%'`.

## Impact

**Endpoints** — behaviour changes on `POST /v1/queries/execute`, `/v1/queries/translate`,
`/v1/queries/translate-sql`, `/v1/queries/execute-sql`, and the Flight SQL `CommandStatementQuery`
path (which shares the SQL front end). No request or response *shape* changes; only which documents
are accepted, and the error code for those that are not.

**Code**

- `service/query/sql/SqlProjectionOnlyTypes.java` — the single classifier gains the comparison
  carve-out alongside the existing positional function carve-outs, so both directions cannot drift.
- `service/query/translate/FilterTranslator.java` — the `toJSONString` / `CAST(… AS JSON)`
  canonicalisation for the widened operators, and the new operand-type rejection for the rest.
- `service/query/translate/ExprFieldType.java` — the scalar/non-scalar classification the new DSL
  check reuses.
- `service/query/sql/StructuredQueryToSqlRenderer.java` — `rejectUseInFilter` consults the carve-out.
- `service/query/sql/RexToExprTranslator.java` — `rejectProjectionOnlyOperand` consults the carve-out.

**Compatibility** — no client can be relying on the widened cases (they error today). The
fail-closed cases turn a raw ClickHouse `Code 43`/`Code 117` — or, for valid-JSON `eq`, a silent
empty result — into a 400 `ErrorView`; a client that was swallowing those errors now sees a stable
machine code instead. Callers relying on the *silent empty result* would be relying on a bug.

**Tests** — roughly 31 assertions across 9 test files reference the projection-only message; the
`object`-plus-comparison cases among them flip from rejected to accepted, and the DSL-surface cases
flip from raw-ClickHouse to 400. Six spec files describe the restriction; four carry deltas here.

**Docs** — no `docs/configuration.md` change (no new config). No migration.
