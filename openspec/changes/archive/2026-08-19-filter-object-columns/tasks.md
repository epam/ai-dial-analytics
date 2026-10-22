## 1. DSL rendering — `json_extract_*` over an `object` field

- [x] 1.1 In `service/query/translate/ExprTranslator.jsonExtract(...)`, detect that the first
      argument resolves to an `object`-typed field (a `FieldExpr` whose `QueryFieldBinding.type()` is
      `QueryFieldType.OBJECT`) and render **subcolumn access** over the literal path keys instead of
      the `JSONExtract*` text accessor. Build the path with jOOQ's `DSL.name(<column>, <key>…)` so
      every segment is emitted as a **quoted identifier** — never string-concatenated into SQL
      (design.md, Decision 2). A non-`object` JSON argument keeps its current rendering
      byte-for-byte.
- [x] 1.2 Apply the per-function coercion over that subcolumn, so the overload matches the text form
      exactly (design.md, Decisions 3 and 4):
      `json_extract_string` → `toString(<path>)`;
      `json_extract_long` → `toInt64OrZero(toString(<path>))`;
      `json_extract_double` → `toFloat64OrZero(toString(<path>))`;
      `json_extract_bool` → `toString(<path>) = 'true'`.
      Use the **untyped** subcolumn, not typed access (`.:String`) — typed access is strict and
      returns null on a type mismatch where the text form coerces. Do **not** use `toBool(...)`: it
      throws `CANNOT_PARSE_BOOL` on an absent path and would break totality.
- [x] 1.3 Update the Javadoc on `jsonExtract(...)` to record that the JSON argument may be an
      `object` field, why subcolumn access is used (the text accessors reject a native `JSON` operand
      with `ILLEGAL_TYPE_OF_ARGUMENT`; subcolumn access also reads only the addressed path), why the
      untyped-plus-coercion form is used rather than typed access, and that totality and coercion are
      preserved for both source types.
- [x] 1.4 Unit-test the rendering in `service/query/translate/` (alongside
      `DialUsageLogQueryRenderTest`): each of the four extractors over an `object` field renders its
      documented coercion over a quoted subcolumn path; a nested multi-key path renders every key as
      a separate quoted segment; a path key containing a quote or `;` is emitted escaped inside one
      identifier; and the `string`-source rendering is unchanged (still `JSONExtract*`, no subcolumn
      access).

## 2. Projection-only carve-out (shared classifier)

- [x] 2.1 In `service/query/sql/SqlProjectionOnlyTypes`, add the positional carve-out: an `object`
      field is allowed as the **first argument of a `json_extract_*` / `JSON_EXTRACT_*` call**, and
      nowhere else. Keep it as the single source of the classification and of the rejection wording
      for both directions (design.md, Decision 3) — do not duplicate the check at the call sites.
      `array` fields get no carve-out.
- [x] 2.2 In `service/query/sql/RexToExprTranslator`, stop applying `rejectProjectionOnlyOperand` to
      the first operand of a `JSON_EXTRACT_STRING|LONG|DOUBLE|BOOL` call (see its `jsonExtract(...)`
      handling); every other operand position keeps rejecting. Verify the path arguments still must
      be string literals, so a computed path is rejected for the path, not for the field type.
- [x] 2.3 In `service/query/sql/StructuredQueryToSqlRenderer.rejectNonProjectionUse`, allow an
      `object` field in that same position so a DSL document extracting from one renders instead of
      failing. Every other use it currently rejects (comparison operand, `sort`/`group_by` key,
      `distinct` projection, other function/aggregate argument) must keep rejecting with the existing
      message.
- [x] 2.4 Confirm `service/query/sql/RelToStructuredQueryTranslator` needs no change: the
      `json_extract_*` result kinds are already fixed by function name, independent of the argument
      type (design.md, Decision 4). Add a test that pins it rather than editing the class if it is
      already correct.

## 3. SQL front end and round-trip tests

- [x] 3.1 Extend `service/query/sql/SqlQueryFrontEndTest`: `JSON_EXTRACT_STRING(jwt_claims, 'sub')`
      translates in the projection, in `WHERE`, in `GROUP BY`/`ORDER BY`, and under `SELECT DISTINCT`;
      the derived result schema types the column by the function's return type, not as an object
      column.
- [x] 3.2 Extend the same test with the still-rejected cases: `jwt_claims` bare in `WHERE`,
      `GROUP BY`, `ORDER BY`, `SELECT DISTINCT`, and `count(jwt_claims)` all still fail closed with
      the projection-only message; a `JSON_EXTRACT_*` call over an **`array`** field still fails
      closed; a computed path argument fails with the literal-path message.
- [x] 3.3 Extend `service/query/sql/StructuredQueryToSqlRendererTest`: a DSL document filtering on
      `json_extract_string(jwt_claims, 'sub')` renders `JSON_EXTRACT_STRING("jwt_claims", 'sub')` in
      the `WHERE` clause; a document that also uses the bare `jwt_claims` field as a sort key still
      rejects.
- [x] 3.4 Add a translate → translate-sql **round-trip** test: the DSL document filtering on an
      extracted object path renders to SQL, and that SQL translates back to the identical DSL
      document. Implemented via the existing `roundTrip(...)` helper in
      `StructuredQueryToSqlRendererTest` (which drives render → front-end translate-back), covering
      the projection, the filter, and a nested path.

## 4. Execution against real ClickHouse

- [x] 4.1 Extend `integration/StructuredQueryExecutionIntegrationTest` (or the provisional structured
      query integration test, whichever owns the `json_extract` cases): seed a row with an `object`
      column document and assert all four extractors return the documented values and types, and that
      an absent path yields the type default rather than null.
- [x] 4.1b Add the **parity test** that guards Decision 3 — the single most important test in this
      change. Seed the *same* document into both an `object` column and a `string` column, then
      assert each of the four `json_extract_*` functions returns **equal** values from both, across:
      a present value; an absent path; a member stored as an explicit JSON `null`; and a type
      mismatch (`json_extract_string` over a numeric member must yield its text form from both, not
      null from one). This is what fails loudly if the two renderings ever drift.
- [x] 4.2 Add execution cases for the new reach: a row-mode `filter` on an extracted object path
      returns only matching rows; an aggregate-mode query groups by an aliased extraction and returns
      one row per distinct value with its count.
- [x] 4.3 Extend `integration/SqlQueryExecutionIntegrationTest`: `POST /v1/queries/execute-sql` with
      `JSON_EXTRACT_STRING(<object column>, '<key>')` in the projection and in `WHERE` executes and
      returns the expected rows.
- [x] 4.4 Add the access-control cases: extraction over a `heavy` object column succeeds; extraction
      over a `sensitive` object column is rejected as an unresolvable field for a non-`FULL_ADMIN`
      caller (no new check — assert the inherited behavior).

## 5. Specs and docs

- [x] 5.1 Re-read `openspec/changes/filter-object-columns/specs/**/spec.md` against the shipped
      behavior and correct any divergence — including the exact `ErrorView` message wording the tests
      assert. The delta specs are part of the change, not follow-up polish.
- [x] 5.2 Run `openspec validate filter-object-columns` and fix anything it reports.
- [x] 5.3 Check whether `README.md` or `docs/` documents the `json_extract_*` argument types; update
      only if an existing statement becomes wrong. No configuration property is added or renamed by
      this change, so `docs/configuration.md` needs no edit — confirm that explicitly rather than
      assuming it.

## 6. Verification

- [x] 6.1 Run `./gradlew clean build` (compile + Checkstyle + tests) and make it pass. Watch for
      `-Werror` on unused imports and the 180-char line limit.

## 7. Review round 1

- [x] 7.1 Reject a `json_extract_*` path key containing a **backslash** with a 400, for the text
      rendering as well as the object one. jOOQ doubles a `"` inside an identifier but leaves a
      backslash alone, and ClickHouse's identifier lexer honours it as an escape — so such a key
      closed the subcolumn identifier and executed as a second query (verified against 25.3.14).
      Refusing it on both forms keeps the two interchangeable. Cover it with a render-level test
      asserting the breakout key is refused and an execution test asserting the 400.
- [x] 7.2 Make `json_extract_bool` over an `object` field reproduce `JSONExtractBool`'s **type**
      coercion (`dynamicType` + `multiIf`) instead of comparing the member's text to `'true'`, which
      diverged in both directions on a non-boolean member. Extend the parity integration test with
      `json_extract_bool` over an integer, a string, and a fractional member so code and spec cannot
      drift again. Update the delta spec and Decision 4 accordingly.

## 8. Review round 2

- [x] 8.1 Make `json_extract_long` over an `object` field reproduce `JSONExtractInt`'s **numeric
      conversion** instead of parsing only integral text, which read a fractional member (`1.5`, and
      the string `"1.5"` the text accessor parses too) as `0` where the text form yields `1`. The
      rendering reads integral text directly — keeping a value near the `Int64` bounds exact — and
      falls back to a truncated float read otherwise (Decision 4a, verified member by member against
      25.3.14). Extend the parity integration test with `json_extract_long` over a fractional number
      and a fractional numeric string, plus `json_extract_double` over an integer member.
- [x] 8.2 State and pin the one divergence that is **not** closed: a member holding a nested document
      is not addressable as text through `json_extract_string` over an `object` field (it yields the
      type default, where the text form serializes the sub-document). Serializing the sub-object back
      does not reproduce the source text, so the guarantee is narrowed explicitly in the delta spec
      (Decision 4b) and pinned by an integration test that also asserts its two bounds — an array
      member still agrees, and the sub-document's members stay addressable through the path.
- [x] 8.3 Match the `json_extract_*` carve-out **case-insensitively** in
      `SqlProjectionOnlyTypes.isAllowedFunctionArgument` (and treat a null name as no match), so an
      upper-case `JSON_EXTRACT_STRING` over an `object` field cannot execute yet fail to translate.
      Add a renderer test that a mixed-case call renders and round-trips to the normalized document.
- [x] 8.4 Re-run `./gradlew clean build`.

## 9. Review round 3

- [x] 9.1 Widen the documented exception from "a member holding a **nested document**" to "a
      **composite** member — a nested document or an array — read as text". The claim that arrays
      serialize identically on both sides was probed on 25.3.14 with `[1,2]` only; it holds for an
      all-integer, null-free array and fails for every other shape, because `toString` over a JSON
      subcolumn renders ClickHouse array syntax (`['p','q']`, `['1','q']`, `[NULL,1]`,
      `['{"x":"1"}']`) where the text accessor returns the source JSON spelling. Update the
      `ExprTranslator` javadoc, Decision 4b, and the delta spec (including the scenario) accordingly.
- [x] 9.2 Change the array assertion in `jsonExtractStringOverObjectColumnDoesNotSerializeCompositeMembers`
      from `[1,2]` — the one shape where the two syntaxes coincide — to a string array `["p","q"]`,
      asserting the actual divergence so the test pins the contract rather than the coincidence.
- [x] 9.3 Scope the observational-identity guarantee, in the delta spec, to the **values the two
      renderings compute** from a stored document: how the native `JSON` type itself stores one
      (which paths it materializes, with what type) is a property of the column type, not of the
      rendering, and no overload can promise it away.
- [x] 9.4 Re-run `./gradlew clean build`.

## 10. Review round 4

- [x] 10.1 Reject an **empty** `json_extract_*` path key with a 400, for the text rendering as well
      as the object one. jOOQ's name building drops an empty segment instead of rendering it
      (`AbstractName.append` returns the receiver unchanged when the appended name is empty), so
      `json_extract_string(meta, '')` rendered as `toString("t"."meta")` — the whole serialized
      document where the text form yields the empty string — `json_extract_bool(meta, '')` failed
      outright (`dynamicType` rejects a `JSON` operand) where the text form yields `false`, and an
      empty segment among other keys silently shifted the address. Refusing it on both forms keeps
      the two interchangeable. Update the delta spec (rule plus scenario) and pin it with
      render-level and execution tests.
- [x] 10.2 Re-run `./gradlew clean build`.
