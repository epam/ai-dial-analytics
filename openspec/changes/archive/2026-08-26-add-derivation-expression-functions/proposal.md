## Why

Class-1 (`type: sql`) enrichments cannot express the derivations they were built for. A `sql`
evaluator's output expression is validated through the same Calcite front end and closed function
catalog as the read path, and that catalog has no branch, no merge, and no array producer. `CASE`, `>`,
and `+` are all rejected at rule registration, so an enrichment that
folds several candidate headers into one session id, or labels a row by which client sent it, cannot
be written at all.

The blocker is not a policy that the grammar stays closed. `ExprSqlValidator` documents the catalog
as an **allowlist** — that is what keeps `now()`/`rand()` out and what Class-1's exactly-once effect
rests on — and the `structured-query` spec's own worked example (`round(div(sum(x), count()), 2)`)
names two functions the catalog does not yet carry. Adding functions is the sanctioned extension
path; nothing structural in the DSL needs to change.

## What Changes

- **Seven new scalar functions** in the closed catalog, each a plain `fn` expression — no new `Expr`
  kind, no operators, no `CASE` node:

  | Function | Returns | Purpose |
  |---|---|---|
  | `if(cond, then, else)` | same as branches | the branch; `cond` is any boolean-valued expression |
  | `not_empty(text)` | boolean | true when non-null and non-empty — the condition producer |
  | `starts_with(text, prefix)` | boolean | user-agent classification |
  | `contains(text, substring)` | boolean | substring test |
  | `equals(a, b)` | boolean | equality as an expression, not just a filter |
  | `json_extract_string_ci(json, key)` | string | case-insensitive JSON **key** match |
  | `json_extract_array(json, key[, key…])` | array | array-valued claim extraction |

  `not_empty` is specified as false for NULL as well as `''`, which is what lets `if` cover the
  merge cases (`if(not_empty(a), a, b)`) without a `coalesce`/`nullif`/`concat` family.

- **Array-typed Class-1 outputs.** `ExprSqlValidator` currently rejects every `ARRAY`-kind
  expression outright. An array expression bound to an array-typed enrichment column is accepted.
  The element type is **not** modelled — `QueryFieldType.ARRAY` stays untyped, so any array
  expression may bind to any array column.

- **`json_extract_string_ci` and `json_extract_array` join the object-field exception.** `object`
  columns are projection-only except as the first argument of a `json_extract_*` call; the two new
  extractors get the same treatment in the SQL subset and in translate-back rendering. Unlike the
  other four they cannot reuse the `object` form's subcolumn rendering — they read the document's
  serialized text instead. This matters more than it sounds: `request_tags` and `jwt_claims` are
  `object` columns, and the naive rendering returns an empty result for every row of them **without
  raising anything** (measured on ClickHouse 26.3). See design Decision 7.

- **`lowercase-json-keys` transform** added to the data-sync closed transform registry and applied
  to `request_tags`, so newly ingested header maps carry lower-case keys. History stays mixed-case
  and is read by `json_extract_string_ci`; no re-ingest is required by this change.

Not breaking: every addition is new surface. Existing expressions, queries, rules and mappings are
unaffected.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: seven rows added to the supported function catalog, with their semantics —
  `not_empty`'s null-and-empty definition, `json_extract_string_ci`'s key-matching rule, and
  `json_extract_array`'s array return.
- `query-schema-discovery`: `GET /v1/queries/functions` advertises the seven new entries; the
  "lists all supported functions" scenario enumerates names and must name them.
- `sql-query-translation`: the SQL subset accepts the seven calls; the object-field exception is
  widened to both new extractors; `json_extract_array` is projection-only for the same reason
  `group_uniq_array` is.
- `query-translation-endpoint`: DSL → SQL rendering and round-trip for the new calls, including the
  widened object-field exception.
- `class1-enrichment`: an array-typed output variable bound to an array-typed target column is
  accepted; element type is unchecked.
- `data-sync`: `lowercase-json-keys` joins the closed transform registry and the `dial_usage_log`
  mapping.

## Impact

**Surfaces.** No new endpoints. Behaviour changes on `GET /v1/queries/functions`,
`POST /v1/queries/execute`, `/execute-sql`, `/translate`, `/translate-sql`, Flight SQL,
`POST /v1/evaluators`, `POST /v1/rules`, and `POST /v1/rules/{id}/materialize`. No config keys.

**Code.** `QueryFunctionCatalog` and `translate.ExprTranslator` (kept in parity by
`QueryFunctionCatalogParityTest`); `SqlQueryOperators`, `RexToExprTranslator`,
`RelToStructuredQueryTranslator` and `SqlProjectionOnlyTypes` (the single classifier both directions
consult for the object-field carve-out) on the SQL side; `StructuredQueryToSqlRenderer` for
translate-back; `ArrayValuedAggregate`, which stops being about one function once a second
array-returning name exists; `ExprSqlValidator.toFieldType` for the array case
(`EnrichmentTypeMapping` needs a javadoc line, not a logic change); `TransformRegistry` plus
`mapping/dial-usage-log.yaml` for the ingest transform.

**Risks carried into design.** Calcite expands `COALESCE`/`NULLIF` into `CASE` via its standard
convertlet — avoided here by not adding them, but the same shadowing question applies to every new
name, which is why they are registered as custom operators ahead of Calcite's standard table (the
`DATE_TRUNC` precedent). The spelling risk is **closed**: `IF`, `CONTAINS` and `EQUALS` are
non-reserved in the Babel grammar and all seven names parse as ordinary function calls, so no
fallback spelling is needed (design Decision 5). The live risk that replaces it is the `object`-column
rendering above, which fails silently and is therefore held by a live-ClickHouse test rather than by
review. `json_extract_string_ci` must materialise the whole header map per row, so its cost against
`json_extract_string` needs an `EXPLAIN` on real ClickHouse before it is advertised.
