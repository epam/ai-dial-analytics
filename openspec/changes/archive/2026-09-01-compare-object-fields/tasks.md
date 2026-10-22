## 1. The shared classifier

- [x] 1.1 Add public `service/query/DocumentComparisons.java` holding the operator carve-out: which
      `ComparisonOp` values are legal against an `object` field (`CO`, `NC`, `ICO`, `INC`, `EQ`, `NE`,
      `IN`), which require a JSON-document right operand (`EQ`, `NE`, `IN`), and the fact that no
      operator is legal against `ARRAY` or `MAP`. Javadoc it as the single source, in the manner of
      `ArrayValuedFunctions` (D1).
- [x] 1.2 Add `SqlProjectionOnlyTypes.isComparableAs(QueryFieldType, ComparisonOp)` that **delegates**
      to `DocumentComparisons` rather than repeating the set, and extend the class javadoc to name the
      operator carve-out as its third kind alongside the two positional ones.
- [x] 1.3 Unit-test the classifier directly: every operator × every projection-only type, and the
      `eq`-against-null case that must stay rejected (D5).

## 2. DSL surface — execute and translate

- [x] 2.1 In `FilterTranslator.toComparison`, reject a comparison whose operand is a projection-only
      field that `DocumentComparisons` does not admit, with a `QueryValidationException` carrying the
      shared projection-only wording (field name, type, attempted use). Cover `filter` and `having`
      (both route through `toCondition`).
- [x] 2.2 Keep the null-literal branch rejecting an `object`/`array`/`map` operand before it reaches
      `left.isNull()` / `left.isNotNull()` (D5), and confirm the message is the projection-only one.
- [x] 2.3 Render the accepted contains family over the canonical text: wrap an `object`-typed left
      operand in `toJSONString(...)` before applying `like`/`notLike`/`likeIgnoreCase`/
      `notLikeIgnoreCase`, leaving every other operand type untouched.
- [x] 2.4 Render `eq`/`ne` on an `object` field as
      `toJSONString(<field>) = toJSONString(CAST(<literal> AS JSON))` (D2), and `in` as the same
      canonicalization applied to every list item (D7).
- [x] 2.5 Validate the right-hand literal of `eq`/`ne`/`in` on an `object` field as a well-formed JSON
      **object** with Jackson before rendering, discarding the parse result; reject a non-document with
      a 400 naming the field and the literal (D3).
- [x] 2.6 **Verify the bound-parameter cast** renders and executes — `CAST(? AS JSON)` through the
      project's jOOQ ClickHouse render context. If it does not bind, stop and reconsider rather than
      silently inlining the literal (D2 risk).

## 3. DSL→SQL renderer

- [x] 3.1 In `StructuredQueryToSqlRenderer.rejectUseInFilter`, consult
      `SqlProjectionOnlyTypes.isComparableAs` so an admitted `object` comparison is no longer rejected;
      keep the guard in place for everything else (D6).
- [x] 3.2 Confirm the accepted comparisons render with the **bare quoted identifier** — no
      `toJSONString` wrapper in the external dialect — so the statement round-trips.
- [x] 3.3 Confirm `sort`, `group_by`, `distinct` and function-argument rejection paths are untouched by
      the change.

## 4. SQL→DSL front end

- [x] 4.1 Make `RexToExprTranslator`'s comparison guard operator-aware at lines 139/140 (`comparison`)
      and 180 (`likeFilter`) only; leave `nullComparison` (151) and the five function-argument sites
      unchanged (D8).
- [x] 4.2 Verify how Calcite presents `WHERE <object col> IN ('…','…')` — a `SEARCH`/Sarg node or an OR
      chain — and make sure both shapes reach the carve-out and translate to a DSL `in` (D8 risk).
- [x] 4.3 Confirm `NOT LIKE` / `NOT ILIKE` over an `object` field reach `nc`/`inc` and not a rejection.

## 5. Tests

- [x] 5.1 `FilterTranslatorTest` (or the nearest existing home): rendering assertions for the seven
      accepted operators on an `object` field, and rejection assertions for the ordering ops, the null
      test, and every operator on `array`/`map`.
- [x] 5.2 `StructuredQueryToSqlRendererTest`: the accepted comparisons render bare-identifier SQL; the
      rejected ones still fail.
- [x] 5.3 `SqlQueryFrontEndTest`: `ILIKE`, `=`, `IN`, `NOT LIKE` over an `object` column translate;
      `IS NULL`, `>`, `GROUP BY`, `ORDER BY`, `DISTINCT`, `count(...)` still fail.
- [x] 5.4 Round-trip tests both ways for each accepted operator: DSL → SQL → DSL yields the identical
      document, per the `query-translation-endpoint` scenarios.
- [x] 5.5 ClickHouse integration test (`StructuredQueryExecutionIntegrationTest` /
      `SqlQueryExecutionIntegrationTest`) proving the canonicalization contract on real data: seed rows
      whose documents differ in key order, whitespace and number format, then assert `eq` against a
      literal written in a *different* key order matches them all, and that `ico` matches on substring.
- [x] 5.6 Integration test asserting a non-JSON literal on `eq` returns 400 `bad_request` and that
      **ClickHouse is not contacted**.
- [x] 5.7 Update the existing assertions that flip: the `object`-plus-comparison cases across the nine
      files that reference "can only be projected", and any assertion pinned to the renderer's
      "cannot be expressed in SQL" tail for a filter (D6 consequence).
- [x] 5.8 `rest-sql-endpoint` integration coverage: `execute-sql` with an `ILIKE` document filter
      returns rows, and its body is identical to the equivalent `execute` document's.

## 6. Documentation and specs

- [x] 6.1 Confirm no `docs/configuration.md` change is needed — this change adds no config property
      (state it explicitly in the change summary rather than leaving it unmentioned).
- [x] 6.2 Update the query DSL documentation under `docs/` if it enumerates which field types are
      comparable; keep property descriptions out of `README.md` and `application.yml` per CLAUDE.md.
- [x] 6.3 Re-read the four delta specs against the shipped behavior and correct any drift before
      archiving.

## 7. Verification

- [x] 7.1 `./gradlew checkstyleMain checkstyleTest` passes.
- [x] 7.2 `./gradlew clean build` passes.
