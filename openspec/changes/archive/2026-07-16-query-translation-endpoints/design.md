# Design — Query Translation Endpoints

## Context

ADAS has two query representations over the same governed pipeline:

- the **structured query DSL** (JSON), executed by `POST /v1/queries/execute`
  (`StructuredQueryService` → `StructuredQueryBuilder` → jOOQ → ClickHouse);
- an **external SQL dialect** — the DSL-expressible SELECT subset — accepted by
  `POST /v1/queries/execute-sql` and the Flight SQL endpoint, translated to the DSL by the
  Calcite front end (`SqlQueryFrontEnd` → `RelToStructuredQueryTranslator`), contract specified
  in `openspec/specs/sql-query-translation/spec.md`.

SQL→DSL translation exists but is fused to execution; DSL→SQL exists only as the *internal*
jOOQ rendering to **ClickHouse** SQL (physical table names, LEFT JOINs to enrichment tables),
which must not be exposed. This change adds translation-only endpoints for both directions.

## Goals / Non-Goals

**Goals:**

- Expose SQL → DSL translation without execution, byte-for-byte the same translation the
  execute path would use.
- Expose DSL → SQL translation without execution, rendering the **external dialect** (what
  `execute-sql` accepts), never internal ClickHouse SQL.
- Round-trip coherence: `translate` output is valid `translate-sql`/`execute-sql` input and
  translates back to a semantically equivalent DSL query; `translate-sql` output is valid
  `execute` input.
- Identical validation and error behavior to the corresponding execute endpoints (fail-closed,
  no ClickHouse contact at all on this surface).

**Non-Goals:**

- No pretty-printing/formatting options, no dialect selection, no partial translation of
  unsupported constructs (fail-closed, same as execution).
- No exposure of the internal ClickHouse SQL (physical schema, join strategy).
- No changes to execution endpoints or to the SQL subset itself.

## Decisions

### D1 — Endpoint naming mirrors the execute pair

`POST /v1/queries/translate` takes a DSL document (same body as `execute`) and returns SQL;
`POST /v1/queries/translate-sql` takes SQL (same body as `execute-sql`) and returns the DSL
document. The suffix names the *input* representation exactly as it does for
`execute`/`execute-sql`, so clients can switch a call from executing to translating by changing
only the path. Alternative (`/translate/sql-to-dsl`, `/translate/dsl-to-sql`) was rejected as a
second naming scheme inconsistent with the existing surface.

### D2 — Response envelopes are objects

Per project conventions: `translate-sql` → `{"query": {…DSL document…}}`;
`translate` → `{"sql": "SELECT …"}`. Room to add fields later (e.g. result-schema columns,
warnings) without breaking clients.

### D3 — SQL→DSL reuses `SqlQueryFrontEnd` verbatim

The controller calls `sqlQueryFrontEnd.translate(sql)` and returns `translation.query()` without
invoking `StructuredQueryService`. This guarantees the returned DSL is exactly what
`execute-sql` would have run — the `sql-query-translation` spec applies unchanged. The
`StructuredQuery` record is returned directly in the response DTO; Jackson serializes it back to
the wire shape (the `Expr` hierarchy carries `@JsonTypeInfo(property = "type")` discriminators;
`ComparisonNode`/`LogicalNode` serialize their `op` fields naturally). A dedicated round-trip
test (serialize → deserialize → equal / re-executable) pins this; if any model type serializes
to a shape `execute` does not accept, that type gets an explicit serializer in the same change.

### D4 — DSL→SQL is a new renderer over the *validated* query, external dialect only

New `StructuredQueryToSqlRenderer` (package `service.query.sql`, beside the front end whose
dialect it mirrors). Flow in a new thin `QueryTranslationService`:

1. Resolve the entity via `CatalogQueryService` exactly as `StructuredQueryService` does (an
   unknown/non-queryable entity raises the same `QueryValidationException` → 400).
2. Validate by running the existing `StructuredQueryBuilder.build(…)` against the connection-less
   render `DSLContext` and **discarding the result**. This reuses every execution-path rule
   (field allowlist, function catalog, mode coherence, limit governance `[0,1000]`, cursor-page
   and `ParamExpr` rejection) with identical `QueryValidationException` messages — no
   parallel validator to drift.
3. Render the external-dialect SQL text from the DSL model (not from jOOQ output).

Alternative — rendering via jOOQ's generated SQL — rejected: it produces ClickHouse SQL with
physical identifiers and joins (leaks internals, not round-trippable). Alternative — building a
Calcite `SqlNode` tree and unparsing — rejected as heavyweight: the DSL is small and closed; a
direct renderer is ~1 string-builder class with total function/operator coverage enforced by
exhaustive switches over the sealed model types.

### D5 — Rendering contract (external dialect)

- **Identifiers**: entity and field names are always double-quoted, with embedded `"` doubled.
  Always-quote is uniform, handles dotted enrichment columns (`"deployment_ref.display_name"`),
  and is round-trip safe (`unquotedCasing=UNCHANGED`, case-insensitive matching on the way back).
- **Select list**: an empty/absent row-mode `select` executes as the entity's *source-owned*
  columns only (enrichment columns are opt-in on the execute path), while SQL `*` expands to
  every SQL-visible column including dotted enrichment columns — so the bare projection renders
  as the explicit double-quoted source-column list; only when the entity has no enrichments
  (where the two expansions coincide) is the compact `SELECT *` kept. If the bare projection
  includes an `object`/`array`-typed source column, no SQL statement in the subset is
  semantically equivalent and the query is rejected (see the inexpressible list). Otherwise
  each `OutputColumn` renders its expression with `AS "alias"` when an alias is present.
  `distinct` renders `SELECT DISTINCT` in row mode; in aggregate mode it is rejected as
  inexpressible — execution accepts it (deduplicating aggregate rows), but the SQL front end
  rejects `DISTINCT` combined with `GROUP BY` as nested aggregation.
- **Operators**: `eq/ne/lt/gt/le/ge` → `= <> < > <= >=`; comparison against a null literal
  renders `IS NULL`/`IS NOT NULL` (`eq`/`ne` only); `in` + `ArrayExpr` → `IN (…)`;
  `co`/`nc` → `LIKE '%v%'`/`NOT LIKE '%v%'` and `ico`/`inc` → `ILIKE '%v%'`/`NOT ILIKE '%v%'`
  (see D6) — **rejected** when the value contains `%` or `_` (inexpressible in the SQL subset,
  whose contains rule forbids metacharacters in the text); logical `and`/`or`/`not` render
  parenthesized.
- **Literals**: rendered from the *parsed* value — the same `ValueExprToObjectMapper` parse the
  execute path binds (trim + validate), never the raw wire text, so padded values normalize and
  malformed values reject identically on both paths. string/uuid → single-quoted with `'`
  doubled; integer/long/decimal → plain decimal text; boolean → `TRUE`/`FALSE`; date →
  `DATE 'yyyy-MM-dd'`; timestamp (DSL epoch-millis) → `TIMESTAMP 'yyyy-MM-dd HH:mm:ss[.SSS]'`
  in UTC; null (outside `IS NULL` rewriting) is inexpressible → rejected.
- **Functions**: the closed catalog maps 1:1 by name (`lower…date_bin`, `count/sum/avg/min/max`,
  `percentile_cont/disc`); names match case-insensitively exactly as the execute path does
  (`ExprTranslator` lowercases before matching) and always render lowercase; `count` with no
  args → `COUNT(*)`, `count` with `distinct` → `COUNT(DISTINCT x)`; the `DISTINCT` quantifier
  renders only on the aggregates execution honors it for (`count`/`sum`/`avg`) — on every other
  catalog function execution silently ignores the flag, so the renderer drops it rather than
  emit SQL outside the accepted subset (e.g. `lower(DISTINCT x)` is rejected by the front
  end); the ordered-set aggregates
  render in the standard form the SQL subset parses — DSL `percentile_x(fraction, column)` →
  `percentile_x(fraction) WITHIN GROUP (ORDER BY "column")`.
- **Clauses**: `filter` → `WHERE`, `group_by` → `GROUP BY` (quoted identifiers), `having` →
  `HAVING` (aggregate mode only), `sort` → `ORDER BY … ASC|DESC [NULLS FIRST|LAST]`, offset
  page → `LIMIT n [OFFSET m]`; an absent page renders no LIMIT clause (defaults are applied by
  whichever endpoint later executes the SQL, keeping translate a pure representation change).
- **Inexpressible DSL** (fail-closed 400, message naming the construct): contains values with
  LIKE metacharacters; null literals outside `eq`/`ne`; `include_total: true` (the SQL surface
  has no total-count opt-in); `having` on a row-mode query (execution silently ignores it, and
  ungrouped `HAVING` is outside the SQL subset); `distinct` on an aggregate-mode query
  (`SELECT DISTINCT` with `GROUP BY` is outside the SQL subset); an empty/absent row-mode `select` on an entity
  whose bare projection includes an `object`/`array`-typed source column (execution projects it
  but the type has no SQL representation). Everything else the builder accepts is renderable —
  the DSL surface was designed as the SQL subset's image.

### D6 — Contains matching becomes exact: `co`/`nc` redefined case-sensitive, new `ico`/`inc` case-insensitive (BREAKING)

The DSL `co`/`nc` operators were case-insensitive only, so the SQL→DSL rule translated
case-sensitive SQL `LIKE '%text%'` to `co` — a documented but real semantic deviation, which the
new DSL→SQL direction would have compounded. Resolution (user decision at the proposal
checkpoint): make the DSL express both sensitivities via distinct operator codes with `co`
aligned to SQL `LIKE`.

- **DSL**: the contains family becomes four self-describing operators — `co`/`nc` are
  **case-sensitive** contains / not-contains (**redefined**; previously case-insensitive), and
  new `ico`/`inc` are **case-insensitive** contains / not-contains. `ComparisonOp` gains
  `ICO("ico")`/`INC("inc")`; everywhere `co`/`nc` are accepted (string-operand validation,
  filter/having trees), `ico`/`inc` are too. ClickHouse execution (`FilterTranslator`):
  `co`/`nc` → case-sensitive `like`/`notLike`, `ico`/`inc` → `likeIgnoreCase`/
  `notLikeIgnoreCase`, all with the same escaped `%…%` pattern.
- **SQL→DSL**: `LIKE '%text%'` / `NOT LIKE '%text%'` → `co`/`nc` (case-sensitive, standard
  semantics); `ILIKE '%text%'` / `NOT ILIKE '%text%'` → `ico`/`inc`. No deviation to document.
  `ILIKE` is non-ANSI but the de-facto standard (PostgreSQL, ClickHouse, Snowflake, DuckDB) and
  parsed by Calcite's Babel parser.
- **DSL→SQL**: `co`/`nc` render as `LIKE`/`NOT LIKE`; `ico`/`inc` as `ILIKE`/`NOT ILIKE`.
  Round-trips exactly in both directions.

Mechanics: `SqlLibraryOperators.ILIKE`/`NOT_ILIKE` are added to `SqlQueryOperators.TABLE`
(they are not in the std table, so ILIKE does not validate today despite both kinds reaching the
same `SqlKind.LIKE` branch); `RexToExprTranslator.likeFilter` branches on the operator's
case-sensitivity to pick `co`/`nc` vs `ico`/`inc`, keeping the `'%text%'`-only pattern rule and
no-ESCAPE rule for both forms. The query demo page's operator list (since removed) gains
the new codes.

This modifies `structured-query` (filter grammar + ClickHouse translation contract) and
`sql-query-translation` (delta specs included). **Two breaking edges, both experimental
surfaces**: DSL clients using `co`/`nc` for case-insensitive matching must switch to
`ico`/`inc`; SQL clients using `LIKE` previously got case-insensitive matching and now get
standard case-sensitive matching (`ILIKE` restores the old behavior). Alternatives rejected
(offered to the user; this shape was chosen): an optional `case_sensitive: true` flag on
`co`/`nc` (non-breaking but a two-part predicate identity); additive case-sensitive codes
(`cos`/`ncs`) keeping `co` case-insensitive (non-breaking but `co` would permanently disagree
with SQL `LIKE`, keeping translation asymmetric).

### D7 — Errors and security reuse existing machinery

`SqlTranslationException` and `QueryValidationException` are already mapped to 400
`bad_request` in `DefaultExceptionHandler`; inexpressible-DSL rejections throw
`QueryValidationException`. No new error codes. Endpoints
live under `/v1/queries`, covered by the existing `/v1/**` rule (oidc: `FULL_ADMIN` or
`READ_ONLY_ADMIN`); no security config change.

## Risks / Trade-offs

- **[Serialization drift]** The DSL model was deserialization-only; a field serializing under an
  unexpected name would break the returned document. → Mitigation: round-trip tests that feed
  `translate-sql` output back through the `execute` request deserialization, asserting equality
  with the front end's original `StructuredQuery`.
- **[Round-trip is semantic, not textual]** `translate(translate-sql(s))` normalizes (quoting,
  Sarg expansion `BETWEEN`→ conjunctions, `IN` ordering). → Documented in the spec: equivalence
  is at the DSL level, not byte-level SQL.
- **[Contains semantics change silently for existing clients]** DSL callers using `co`/`nc` and
  SQL callers using `LIKE '%text%'` matched case-insensitively before this change and
  case-sensitively after it — a silent behavior change rather than an error. → Both surfaces
  are experimental; specs, OpenAPI descriptions, demo page, and endpoint docs updated in the
  same change (D6), naming `ico`/`inc` and `ILIKE` as the case-insensitive forms.
- **[Validation cost]** DSL→SQL runs the full jOOQ build just to validate. Costs are in-memory
  only (no ClickHouse round-trip) and identical to execute-path overhead. Accepted for
  zero-drift validation.

## Open Questions

_None blocking._ Result-schema columns (`SqlTranslation.columns()`) are deliberately left out of
the `translate-sql` response envelope; D2 leaves room to add them later if clients need typing.
