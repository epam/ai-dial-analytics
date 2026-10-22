## 1. Plan model

- [x] 1.1 Add `SetOpKind` (`UNION`, `INTERSECT`, `EXCEPT`) to `service/query/model/`.
- [x] 1.2 Add `SetOpRelation(SetOpKind kind, boolean all, List<CompositeQuery> branches, List<RelationColumn> columns)` to `service/query/model/` and list it in `Relation`'s `permits` clause (design D1). Javadoc it as internal-only, like `SubqueryNode`: no Jackson type id, never on the wire.
- [x] 1.3 Compile and fix every now-inexhaustive `switch` over `Relation`, adding the real arms rather than a default: `sql/CompositeQueryEntities` (a set operation's leaf entities are the union of its branches'), `translate/CompositeQueryBuilder.render` and `.operand`.

## 2. ClickHouse semantics: the source-text pass

- [x] 2.1 Add a source-region helper that maps a `SqlParserPos` to an offset in the original statement and reads comment-stripped tokens from the text between two positions (design D2). It must skip `--` and `/* */` comments and must not be confused by `'…'`, `"…"` or `` `…` ``.
- [x] 2.2 Add `SqlSetOperations`, a pre-validation pass taking the parsed tree and the raw SQL, that resolves each set-operation node's written qualifier from the region between its operands: `MINUS` rejected naming `EXCEPT`; a bare `UNION` rejected naming `ALL`/`DISTINCT`; a bare `INTERSECT`/`EXCEPT` rewritten to `SqlStdOperatorTable.INTERSECT_ALL`/`EXCEPT_ALL`; an explicit `ALL`/`DISTINCT` left untouched. Fail closed — a region whose first token is not one of the four keywords is an error, never a default.
- [x] 2.3 In the same pass, re-associate a trailing `ORDER BY`/`LIMIT`/`OFFSET` over a set operation into the last branch (design D3): reject when the last branch was parenthesized, with a message naming ClickHouse's reading and the `SELECT * FROM (<set operation>) ORDER BY …` spelling that works; otherwise move `orderList`/`offset`/`fetch` into the last operand and replace the statement with the set-operation call.
- [x] 2.4 Walk the statement body, every CTE body, every derived table and every subquery, so 2.2 and 2.3 hold at every level.
- [x] 2.5 Wire the pass into `SqlQueryFrontEnd.plan` immediately after `planner.parse`, ahead of `SqlStarExpander`.

## 3. Result-column typing

- [x] 3.1 Move the `SqlTypeName → ResultColumnKind` mapping out of `InformationSchemaExecutor` into `ResultColumns` (design D5), keeping the catalog executor's error wording for a type it cannot map and leaving its behaviour unchanged.
- [x] 3.2 Add the branch-unification rule: identical `(type, elementType, kind, elementKind)` across all branches yields that column verbatim; otherwise derive from the `SetOp`'s validated `RelDataType`; an unmappable unified type raises a `SqlTranslationException` naming the column and the branch types.

## 4. Fold: set operations become a relation

- [x] 4.1 Replace `RelToStructuredQueryTranslator.fold`'s `case SetOp -> throw` with `setOpCtx(SetOp)`: fold and `assemble` each input into a branch `CompositeQuery`, derive the output columns (task 3.2) with names from `setOp.getRowType()`, mark the plan `composite`, and return a fresh `Ctx` whose source is the `SetOpRelation` and whose outputs are `FieldExpr`s over its columns — so the statement always has an outer block to carry the governed limit (design D3).
- [x] 4.2 Map `SetOp` subclasses to `SetOpKind` (`Union`, `Intersect`, `Minus`) and read `all` from Calcite, which task 2.2 has already made unambiguous; handle an n-input chain.
- [x] 4.3 Narrow `asOperand` so only an `EntityRelation` or a left-side `JoinRelation` passes through un-sealed, sealing a set operation used as a join operand into a `DerivedRelation` (design D7).
- [x] 4.4 Extend the `NO_DSL_EQUIVALENT` message to name set operations first — `statements with set operations, joins, derived tables or subqueries — …` — matching the spec's pinned wording, and update every test that asserts it.

## 5. Rendering

- [x] 5.1 Render `SetOpRelation` in `CompositeQueryBuilder` as a jOOQ plain-SQL `Table<?>` — `({0}) <KIND> <ALL|DISTINCT> ({1})`, left-folded over the branches, each branch rendered by the existing nested-block path — then alias it and wrap it in a synthetic `QueryEntityModel` exactly as `DerivedRelation` is (design D4).
- [x] 5.2 Do not prune branch outputs (design D6); confirm the wrapper block still projects only what the statement reads.

## 6. Front end

- [x] 6.1 `SqlQueryFrontEnd.screen`: admit `SqlKind.UNION`/`INTERSECT`/`EXCEPT` as statement kinds, after the existing `ORDER BY`/`WITH` peeling so `WITH RECURSIVE` stays refused by the check ahead of it; delete the set-operation rejection.
- [x] 6.2 `SqlStarExpander.expandStars`: recurse into each branch of a set-operation node, so a branch's `*` expands to that branch's wildcard fields instead of falling through to the validator's full-row-type expansion (design D7).
- [x] 6.3 `SqlSelectAliasInjector.selectOf`: follow a set operation to its first operand, so the statement's output names are the first branch's PostgreSQL-shaped names rather than `EXPR$n`.
- [x] 6.4 Reject a set operation whose branches all read `INFORMATION_SCHEMA`, by name, before it reaches the ClickHouse translator; keep the existing rejection for one mixing a catalog relation with a queryable entity, and update the two `InformationSchemaExecutor` comments that assert set operations are already rejected (design D8).
- [x] 6.5 Confirm `SqlDateTruncRebinder` and `SqlTextRebinder` reach a branch's expressions with no change (a test over a set operation using `date_trunc` and `||` in each branch is the check).

## 7. Surfaces and declarations

- [x] 7.1 `AdasFlightSqlProducer`: `withSqlSupportedUnions(SQL_UNION, SQL_UNION_ALL)`, and update the comment that explains the empty bitmask to record the three neighbouring under-claims left in place (design D9).
- [x] 7.2 Retire `SqlIssueClassifier`'s set-operation rule and its pinning test row (design D10).
- [x] 7.3 Update `SqlQueryController`'s OpenAPI description: set operations move out of the rejected list; note the bare-`UNION` requirement and that `translate-sql` has no document for them.

## 8. Tests

- [x] 8.1 Flip the existing rejection assertions to acceptance: `SqlQueryFrontEndTest.unionRejected`, `SqlCompositeTranslationTest`'s set-op-in-a-subquery case, `SqlQueryExecutionIntegrationTest`, `SqlCompositeExecutionIntegrationTest`, `SqlValidationIntegrationTest`, `FlightSqlIntegrationTest` — plus the `SQL_SUPPORTED_UNIONS == 0` assertions.
- [x] 8.2 Unit tests for `SqlSetOperations`: each explicit spelling preserved; bare `INTERSECT`/`EXCEPT` promoted to `ALL`; bare `UNION` and `MINUS` rejected with their messages; a comment between the operands; a newline-separated operator; parenthesized operands; a nested chain; a quoted identifier named `union` left alone; the trailing-clause push-down and the parenthesized-last-branch rejection, at top level and inside a derived table.
- [x] 8.3 Unit tests for the fold: every spelling produces a `SqlCompositeTranslation` over a `SetOpRelation` with the right `kind`/`all`; an n-branch chain; `INTERSECT` binding tighter than `UNION`; a set operation as a CTE body, a derived table, a join operand and an `IN` operand; output names from the first branch; branch arity and unifiable/non-unifiable branch types.
- [x] 8.4 Render tests asserting the SQL verbatim for every spelling — the keyword explicit, each branch parenthesized, the governed `LIMIT` on the wrapper, a pushed-down `ORDER BY`/`LIMIT` inside the last branch.
- [x] 8.5 Integration tests against real ClickHouse over REST, each asserting the row set ClickHouse itself returns for the same text: the `ALL`/`DISTINCT` multiplicity of all three operators including the two bare defaults, a nested set operation, per-branch `ORDER BY`/`LIMIT`, a trailing `ORDER BY LIMIT` binding to the last branch, the wrapped spelling ordering the combined result, the default limit bounding the combined result, a trailing `LIMIT 5000` not triggering the over-maximum rejection, a branch star omitting heavy columns, and the `INFORMATION_SCHEMA` rejections.
- [x] 8.6 Integration test that a sensitive column referenced in any branch fails as an unknown column for a caller without `FULL_ADMIN`, and that a branch's enrichment column resolves under its flat name.
- [x] 8.7 Integration test that the declared result-column descriptor matches the values that arrive for a mixed-type branch pair (design D5 fallback).
- [x] 8.8 `translate-sql` returns 400 with the composite message for a set operation while `execute-sql` executes the same statement; `validate-sql` reports it valid, and a bare `UNION` and an arity-mismatched one invalid.
- [x] 8.9 Flight integration test: a `UNION ALL` streams under `flight.max-rows` with the truncation probe on the combined result, and `SQL_SUPPORTED_UNIONS` declares both bits.

## 9. Docs

- [x] 9.1 `docs/sql-reference.md`: a set-operations section — the accepted spellings, the two readings taken from ClickHouse rather than the standard (bare `INTERSECT`/`EXCEPT` mean `ALL`; a trailing `ORDER BY`/`LIMIT` binds to the last branch, with the wrapped spelling for the combined result), the bare-`UNION` and `MINUS` rejections, precedence, branch compatibility, nesting — and remove the "Not supported" bullet and the CTE/subquery sentence that say otherwise.
- [x] 9.2 `README.md`: drop `UNION`/`INTERSECT`/`EXCEPT` from the rejected-construct sentence.
- [x] 9.3 No `docs/configuration.md` change — this change adds no config property.

## 10. Verification

- [x] 10.1 Confirm the delta specs in `openspec/changes/add-set-operations/specs/` match the shipped behaviour, including the pinned composite-statement message and the two deliberate divergences from standard SQL.
- [x] 10.2 `./gradlew clean build` passes (compile + checkstyle + tests).

## 11. Review round 1 — divergences found and closed

Each item is a statement ClickHouse answers one way and ADAS answered another; all five readings were
re-measured against ClickHouse 26.3.24 before the fix.

- [x] 11.1 The trailing clause binds to the chain's **final `SELECT`**, found by walking the right spine, not to the top call's last operand: `A UNION ALL B INTERSECT ALL C ORDER BY x LIMIT 1` bounds `C` alone (design D3).
- [x] 11.2 A trailing clause under a `WITH` is re-associated too — Calcite parses `SqlOrderBy(SqlWith(<set op>))`, so the wrapper is peeled and the `WITH` stays the statement.
- [x] 11.3 A parenthesized branch that is not the last one is no longer read as a parenthesized whole operation: the enclosing parenthesis is detected by scanning from the final branch's end to the clause keyword, so `(A) UNION ALL B ORDER BY x LIMIT 1` executes as it does on ClickHouse while `(A UNION ALL B) ORDER BY x` and `A UNION ALL (B INTERSECT ALL C) ORDER BY x` stay refused.
- [x] 11.4 A join's `ON` predicate is walked, so a bare `UNION`/`INTERSECT`/`MINUS` in a subquery there is resolved or refused instead of reaching ClickHouse with Calcite's parse.
- [x] 11.5 The expression walk covers every `SqlCall`, not only `SqlBasicCall`, so a set operation nested in a `CASE` is resolved or refused (`SqlCase` is not a `SqlBasicCall`).
- [x] 11.6 Tests for all five: unit tests over the front end, render assertions for the mixed chain and for the `ON`/`CASE` qualifier, and integration tests over REST for the final-`SELECT` binding, the `WITH` shape, the parenthesized first branch and the `ON`/`CASE` bare-`UNION` rejections.

## 12. Review round 2 — the trailing ORDER BY with no row bound

- [x] 12.1 Disable Calcite's `removeSortInSubQuery` in `SqlQueryFrontEnd.plan`'s `FrameworkConfig`: with the default in force, a trailing `ORDER BY` carrying no `LIMIT`/`OFFSET` became a set-operation input's sort and `SqlToRelConverter` threw the collation away, so `A UNION ALL B ORDER BY x` rendered with no `ORDER BY` at all — and with the default 100-row limit on the combined result, the rows returned could differ from ClickHouse's, not only their order (design D3).
- [x] 12.2 Record the widening the per-plan flag implies: an `ORDER BY` inside a derived table with no row bound is now executed instead of discarded, which is what ClickHouse does with it (re-measured on 26.3.24) — spec requirement "Derived tables nest query blocks" modified accordingly, and `CompositeQueryRenderTest` re-pinned to the rendered `ORDER BY`.
- [x] 12.3 Regression tests: the last branch's `sort()` for a trailing `ORDER BY` with no `LIMIT` and for a parenthesized `ORDER BY`-only branch, the verbatim rendered SQL for the trailing form, and a REST integration case asserting the row order (`DESC`, so ascending insertion order cannot pass it by accident).

## 13. Review round 3 — nested unnamed columns and the branch-mismatch message

- [x] 13.1 `setOpColumns` names the operation's columns from the **first branch's rendered** columns rather than from `setOp.getRowType()`: the two disagree exactly for an unnamed expression, where Calcite synthesizes `EXPR$0` and the branch renders the DSL base name, so the block above a **nested** operation (CTE body, derived table, `IN`/scalar-subquery operand) referenced a column the subquery does not expose — `Code: 47 UNKNOWN_IDENTIFIER` from ClickHouse for a statement the spec says executes, and `EXPR$0` in the response's column name (design D5). The top level was masked by the alias injector, which names only the statement's own output list.
- [x] 13.2 The validator's text coercion is reported as the branch mismatch the spec promises: a text-against-number pair is "unified" by a `CAST` to text injected into the other branch, which the Rex translation refuses on purpose, so the caller read "CAST from BIGINT to VARCHAR is not supported" (plus `validate-sql`'s "drop the CAST" remedy) for a statement containing no cast. Folding a branch now re-words that one refusal as "the branches of a set operation give column '<n>' incompatible types (<a> vs <b>)", keyed to the injected cast's own message so every other in-branch rejection passes through; an unnamed column is named by position, never by `EXPR$n`. New classifier code `branch_type_mismatch`.
- [x] 13.3 Tests: fold and verbatim-render assertions for the nested unnamed-column shape in all three nesting positions, the message assertions for the text/number and timestamp/text pairs and for the by-position naming, and REST integration cases for the nested form (execute + `validate-sql` agreement) and for the rejection's code and message.
