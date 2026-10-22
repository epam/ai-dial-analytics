## Context

See proposal.md — Why. The constraints that shape the approach:

- **`SqlProjectionOnlyTypes` is the single classifier** for "array/object/map can only be projected",
  consulted by all three enforcement points so the two translation directions cannot drift. It already
  carries two *positional, type-specific* carve-outs (`json_extract_*` argument 0 for `object`; the map
  and array accessors for their types). This change adds a third kind — an **operator-specific** one —
  and it must live in the same place for the same reason.
- **`QueryTranslationService.toSql` runs a full `StructuredQueryBuilder` pass and discards it**, then
  renders. So a check placed in `FilterTranslator` (reached from `StructuredQueryBuilder.build`) fires
  on `POST /v1/queries/execute` *and* `POST /v1/queries/translate` from one site — there is no need to
  add the DSL-surface check twice.
- **`FilterTranslator.toComparison` already receives the `Map<String, QueryFieldBinding> bindings`**,
  so it can see an operand's declared type. It currently uses them only to translate, never to check.
- **ClickHouse facts measured on 26.3** (the deployed version), which the design depends on:
  `toJSONString` over a native `JSON` column is canonical (keys sorted recursively, whitespace
  stripped, numbers normalized, string/number distinction kept); `toJSONString(CAST(<text> AS JSON))`
  canonicalizes a literal by the identical rules; `doc = '<json text>'` on the raw column matches
  nothing; `doc LIKE …` raises `Code 43`; `CAST('<not json>' AS JSON)` raises `Code 117`.

## Goals / Non-Goals

**Goals:**

- One classifier decides which `(field type, comparison operator)` pairs are legal, for all three
  enforcement points.
- The DSL surface stops relaying ClickHouse type errors for operand misuse, and stops silently
  returning zero rows for `eq` on an object field.
- Canonicalization is performed by **ClickHouse**, not by ADAS, on both sides of the comparison.

**Non-Goals:**

- Widening `sort`, `group_by`, `distinct`, or function arguments over any container type.
- Any change to `array` or `map` reach — this change only *rejects* their comparisons earlier.
- Indexing or performance work. A document comparison reads the whole column; the `json_extract_*`
  path stays the fast, subcolumn-reading option and remains the documented recommendation.

## Decisions

### D1. The operator carve-out lives in `service/query/DocumentComparisons.java`, not in `SqlProjectionOnlyTypes`

`SqlProjectionOnlyTypes` is package-private in `service.query.sql`; `FilterTranslator` is in
`service.query.translate` and cannot see it. Rather than widen its visibility, the operator rule goes
in a new **public** `service/query/DocumentComparisons` — the same shape and package as the existing
public `ArrayValuedFunctions`, which `SqlProjectionOnlyTypes` already *delegates* to for the array
carve-out precisely so the two answers cannot drift. `SqlProjectionOnlyTypes` gains a
`isComparableAs(QueryFieldType, ComparisonOp)` that delegates to it, and `FilterTranslator` calls the
new class directly.

*Alternatives rejected:* making `SqlProjectionOnlyTypes` public (it documents itself as the SQL
subset's classifier, and the DSL surface's rule is not a SQL-subset fact); duplicating the operator set
in `FilterTranslator` (the exact drift the existing javadoc argues against).

*Refined during implementation:* the shared **rejection wording** had to move too, not just the rule.
`SqlProjectionOnlyTypes.message` was the single source of the "…can only be projected…" sentence but is
unreachable from `service.query.translate`, and a third enforcement point must not produce a fourth
spelling. The wording now lives in `DocumentComparisons.rejectionMessage`, with
`SqlProjectionOnlyTypes.message` delegating — the same delegation shape as the rule itself, so a caller
cannot tell which of the three points refused a query.

### D2. Canonicalization is ClickHouse's, on both sides — never ADAS's

`eq`/`ne`/`in` render as `toJSONString(<field>) = toJSONString(CAST(<literal> AS JSON))`.

*Alternative rejected:* canonicalizing the literal in Java with Jackson (sort keys, re-serialize) and
binding the resulting text. It is one fewer ClickHouse call, but ADAS would then have to reproduce
ClickHouse's number normalization (`1.0` → `1`), its key collation, and its escaping exactly — and any
divergence shows up as a filter that silently matches nothing, which is the very bug this change
exists to remove. Letting one engine canonicalize both sides makes the match correct by construction.

### D3. The literal is validated in Java *and* canonicalized in ClickHouse

`CAST('not-json' AS JSON)` raises `Code 117`, so the literal must be checked before it is rendered.
It is parsed with Jackson (`readTree`, requiring an object node) purely as a **gate** — the parsed tree
is discarded, never re-serialized. Java decides *whether* the literal is a document; ClickHouse decides
*what* its canonical form is. This keeps D2 intact while producing the stable, field-naming 400 the
specs require.

*Refined during implementation:* the gate is needed on **both** surfaces, not only the DSL one.
`QueryTranslationService.toSql` runs a builder pass (see Context), but
`QueryTranslationController.translateSql` calls `sqlQueryFrontEnd.translate(sql)` **alone** — no builder.
So `POST /v1/queries/translate-sql` would have emitted a DSL document that `POST /v1/queries/execute`
then refuses, the precise round-trip asymmetry the endpoint pair exists to rule out. The predicate and
its wording therefore live in `DocumentComparisons` (`isJsonDocument`, `jsonLiteralMessage`) and are
called from `FilterTranslator` and `RexToExprTranslator` alike, each wrapping the shared message in its
own exception type. A JSON **array** or bare scalar is well-formed JSON but not a document, and is
rejected on the same terms — the ClickHouse `JSON` type it would be cast to holds an object.

### D4. `lt`/`gt`/`le`/`ge` stay rejected even though they would render

`toJSONString(doc) > '…'` is valid ClickHouse and would round-trip through the SQL subset. It is
rejected anyway: lexicographic order over canonical JSON text is not an order over documents, so the
query would return a confident, wrong-shaped answer. The codebase already takes this position for a
computed array used as a `group_by` key ("a plausible-looking result answering a question nobody
asked"). An error is recoverable; a wrong answer is not.

### D5. `eq`/`ne` against a **null literal** stays rejected, and keeps its current message

A ClickHouse `JSON` column is never `NULL`, so `IS NULL`/`IS NOT NULL` is a constant false/true —
measured. Accepting it would hand callers a predicate that silently matches nothing or everything,
which is the same class of defect as D2's. It keeps the existing projection-only wording rather than
gaining one of its own, so the `rest-sql-endpoint` and `query-translation-endpoint` scenarios that use
`IS NULL` as their example of a refused use continue to hold unchanged.

### D6. The renderer keeps its filter guard; both guards consult the same classifier

Because `QueryTranslationService` validates before rendering (see Context), a rejected comparison now
fails in `FilterTranslator` first, and `StructuredQueryToSqlRenderer.rejectUseInFilter` becomes
unreachable for that case through the endpoint. It is kept rather than deleted: the renderer is
directly unit-tested, is documented as the guard on the round-trip guarantee, and would otherwise
silently emit un-round-trippable SQL if a future caller rendered without building. Both sites call
`SqlProjectionOnlyTypes.isComparableAs`, so keeping it costs no second source of truth.

The renderer's carve-out matches the same **shape** the other two guards do, not merely the operator:
two operands, the restricted type read off the **left** one, and a restricted field on the right taking
the query straight back out of the carve-out. Reading the type off either operand would let
`<scalar field> eq <object field>` and `<object field> eq <object field>` render as bare identifiers,
and the SQL front end refuses both (`RexToExprTranslator.rejectComparisonOperands` blanket-rejects a
projection-only right operand) — exactly the un-round-trippable SQL the guard exists to prevent.

**Consequence to expect in tests:** for the still-rejected object comparisons, `/v1/queries/translate`
now returns the DSL-surface message ("…can only be projected; …") rather than the renderer's
"cannot be expressed in SQL" tail. Assertions pinned to the renderer tail need updating; assertions on
"can only be projected" and on the field name continue to pass.

### D7. `in` is included rather than carved out

`in` is `eq` over a set, mechanically and semantically. Excluding it would leave a hole a caller would
have to work around with an `or` of `eq`s that *is* accepted. Both surfaces already fold OR-chains of
equality into `in` (`RexToExprTranslator.orFilter`) and expand Calcite `SEARCH`/Sarg nodes
(`SargExpander`), so leaving `in` out would also mean two spellings of one query disagreeing.

### D8. The SQL→DSL guard becomes operator-aware at three call sites only

In `RexToExprTranslator`, the operand guard is called from six places. Only these change:

| line | site | change |
| --- | --- | --- |
| 139/140 | `comparison(op, call)` | consult the operator carve-out — allow `object` for `EQ`/`NE`, keep rejecting the ordering ops |
| | | …but for the **left** operand only, and only when the right one is a character literal (see below) |
| 180 | `likeFilter` | allow `object` |
| 151 | `nullComparison` (`IS [NOT] NULL`) | **unchanged** — D5 |
| 295, 316, 350, 390, 426 | function arguments | **unchanged** |

`SEARCH`/Sarg expansion reaches `comparison` through `SargExpander`, so `IN` lists inherit the
carve-out without a site of their own — this needs verifying during apply rather than assuming, since
Calcite may present a points-Sarg over `VARCHAR` in more than one shape.

**The carve-out is a property of the comparison's shape, not of one operand.** Applying it to each
operand independently admits three shapes the DSL surface refuses — `'x' = jwt_claims`,
`request_tags = jwt_claims`, `jwt_claims = event_kind` — because `FilterTranslator` reads the carve-out
off the *left* operand only and refuses any projection-only field on the right. Each would have
translated to a DSL document `/v1/queries/execute` then rejected with 400, which is exactly the
round-trip asymmetry this change exists to close. So `comparisonOf` admits an `object` left operand only
when the right operand is a character literal, and the right operand keeps the blanket rejection —
worded as the DSL surface words it ("the right operand of comparison '…' over"), so a caller cannot tell
which surface refused the query.

## Risks / Trade-offs

- **[A document filter reads the whole column, where `json_extract_*` reads one subcolumn.]** The
  archived `filter-object-columns` change measured ~20× less data for subcolumn access (6.87 MiB / 15 ms
  vs 135.51 MiB / 404 ms on 300k rows). → The specs state that the extractors remain the instrument for
  a key-scoped test, and the widened operators are documented as whole-document. The existing query
  budget applies unchanged; nothing here bypasses it.
- **[Substring search matches key names and escaped forms.]** `ico '%role%'` matches a document whose
  *key* is `role`, and a value containing a quote matches its escaped spelling. → Stated as normative
  behavior in the `structured-query` delta rather than left as a surprise, with the extractors named as
  the precise alternative.
- **[`CAST(? AS JSON)` may not accept a bound parameter in every jOOQ/ClickHouse rendering path.]** →
  **Resolved during apply**: it binds. `DSL.field("CAST({0} AS JSON)", …)` renders
  `toJSONString(CAST(? AS JSON))` in the project's ClickHouse render context, and the inlined form
  executes correctly on 26.3. The inline fallback was not needed.
- **[Calcite may type an `object` column's `IN` list as a Sarg whose expansion bypasses `comparison`.]**
  → **Resolved during apply, in two parts.** Calcite lowers the `IN` lists this subset sees to OR
  chains, which `RexToExprTranslator.orFilter` folds back into `in`, so an object operand does not reach
  `SargExpander` today. Had it done so, `SargExpander.expand`'s *entry guard* would have rejected it
  before `points` ever ran — the guard used the blanket overload, so the "inherits the carve-out via
  `comparisonOf`" reasoning was true of `points` but unreachable through it. Rather than weaken the
  claim, the guard now classifies by Sarg **shape**: a point set (a membership test, which an object
  field admits) takes the operator-aware overload, a range (the ordering comparisons, which it does not)
  keeps the blanket one. That makes the code match what this decision asserts and removes the latent
  break a future Calcite version could introduce. Covered by a round-trip test regardless (D8).
- **[`nc`/`inc` do not round-trip as themselves.]** Calcite normalizes `NOT LIKE` to `NOT(LIKE)` before
  the translator sees it, so they return as a `not` node wrapping `co`/`ico`. → Pre-existing and
  semantics-preserving: it is why the existing contains-family test asserts rendering only, and it
  applies identically to character fields. Pinned by a test on both an object and a character field so
  the equivalence is documented rather than rediscovered.
- **[The error for a still-rejected comparison changes site, and therefore its exact wording.]** → D6
  records it; the spec scenarios are written against "names the field and the projection-only
  restriction", which both wordings satisfy.
- **[Behavior narrows for `array`/`map` comparisons.]** A client sending one gets a 400 where it
  previously got a relayed ClickHouse error (also a 400). → No client can have been depending on
  success; the change is in the message, not the outcome.

## Migration Plan

No migration. No new config key, no new credential, no Flyway version, no ClickHouse version bump —
every rendering used here works on the deployed 26.3 and was measured against it. The change is
backward compatible for every query that succeeds today: it only accepts more documents, and replaces
two failure modes (a relayed ClickHouse type error, and a silent empty result) with a stable 400.

Rollback is a straight revert; no persisted state is touched, and no stored artifact — saved query,
enrichment definition, or catalog row — records a document comparison that a rolled-back build would
fail to read.

## Open Questions

None. The two verification items (the bound-parameter cast, the Sarg shape for `IN`) are implementation
checks with a stated fallback, not open design decisions.
