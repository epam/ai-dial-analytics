## 1. Shaping switch and test seams

- [x] 1.1 Add the read-shaping switch to `StructuredQueryBuilder`: the 4-argument `build` and `buildCount` shape,
  the 5-/6-argument overloads do not; add `withoutReadShaping()` returning a builder that never shapes (D3)
- [x] 1.2 Render test: a composite block (5-argument `build`) and an aggregate-compiler build render exactly as
  before for a query every part would otherwise shape

## 2. A — late materialization (tests first)

- [x] 2.1 Render tests (red): the page-last-10 shape (`ON … IN (K)` and outer `WHERE … IN (K) OR … IS NULL`, same
  `K` text, page block without heavy columns, `LIMIT 10`); offset page (`LIMIT 30` in the page block, `LIMIT 10
  OFFSET 20` outside); a sort through a select alias keeps that entry in the page block under its name; a light
  payload column in the filter; non-aligned enrichment (one-column `K`); every exclusion of D2 (heavy in filter,
  sort, sorted alias, distinct, window, `array_join`, aggregate mode, reserved alias, two heavy enrichments on
  different keys); `buildCount` never late-materialized, and a final-read count joins only what its filter reads
- [x] 2.2 Implement the applicability check and the late statement in `StructuredQueryBuilder` /
  `EnrichmentJoins` (the late join: bare table under `FINAL` with the `ON` restriction)
- [x] 2.3 ClickHouse integration test (red → green): shaped vs `withoutReadShaping()` output identical
  (`TabSeparatedWithNamesAndTypes`) over a fixture with unmatched source rows, three unmerged payload versions of
  one key, sort ties, a > 1 MB body, with and without `OFFSET`; a page cutting through a tie returns a valid page
  with every body. (A raw read is covered by the render tests only: the application runs with `query.force-final`.)
- [x] 2.4 ClickHouse integration test: the D1 shape with a `rand()`-ordered page block matches every kept row on 20
  consecutive runs (single evaluation)

## 3. B — enrichment-conjunct pushdown (tests first)

- [x] 3.1 Render tests (red): each allow-listed shape is copied into the derived table and kept outside; each
  excluded shape (`IS NULL`, `IS NOT NULL`, `ne`, `inc`, `nc`, `lt` over `length`, `gt`, `not`, `or`, `eq ''`,
  `eq 0`, `in` with a default, empty needle, `starts_with = false`, document comparison, enum field, two-field
  conjunct) stays outside only; an unkeyed enrichment with a copied conjunct becomes a derived table with that
  `WHERE` alone; `buildCount` copies the same conjuncts; a heavy body read only by the conjunct is computed as a
  column and not joined, while a body also read raw keeps the plain copy
- [x] 3.2 Implement the allow-list planner and the derived-table restriction, and the computed-column form for a
  heavy field read only through pushed conjuncts and calls (found in acceptance, design D4)
- [x] 3.3 ClickHouse integration test: shaped vs unshaped rows and counts identical, including a key whose older
  version matches and newest does not (merges stopped), and unmatched source rows

## 4. C — scalar-call pushdown (tests first)

- [x] 4.1 Render tests (red): `avg(length(body))` projects `length(…) AS "__adas_c0"` and not the body; a repeated
  call shares one column; a raw reference or a non-listed call keeps the whole column; empty-literal
  `starts_with` and a call inside a lambda are not pushed; output names unchanged
- [x] 4.2 Implement the per-statement call substitution in `ExprTranslator` / `FilterTranslator` and the pushed
  projection in `EnrichmentJoins`
- [x] 4.3 ClickHouse integration test: every allow-listed call over a `Nullable(String)` and a `String` heavy column,
  matched (NULL, `''`, JSON) and unmatched rows, returns the unshaped values; aggregate corpus shapes (h5, h13, c5)
  identical shaped vs unshaped

## 5. Existing tests, docs and specs

- [x] 5.1 Rework `QueryBudgetIntegrationTest.unboundedHeavyColumnReadIsRefusedRatherThanFailingTheConnection` to the
  shape the restated scenario names (a body filter with no partition bound); the projection-only case is covered
  by `ReadShapingIntegrationTest` (a 1 KB ceiling cannot show a page that completes)
- [x] 5.2 Update `docs/heavy-columns.md` with what a body read costs after A/B/C (no `configuration.md` change: no
  property is added)
- [x] 5.3 Update the `EnrichmentJoins` / `StructuredQueryBuilder` class notes; confirm both delta specs match the
  shipped behavior; `openspec validate late-materialize-heavy-enrichments`

## 6. Acceptance and verification

- [x] 6.1 Render corpus ids h2, h3, h4, h5, h7, h10, h13 and c5 through the shaped builder and run each read-only on
  a running ClickHouse under the query budget; record before / after (`query_log`) numbers in `acceptance.md`, and list
  the post-deploy `execute-sql` rerun as a follow-up
- [x] 6.2 `./gradlew clean build` passes
