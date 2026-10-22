## Context

See proposal.md — Why, and in particular the rule this design serves: a ClickHouse query must execute
identically.

The plumbing already exists. `extend-sql-surface` generalised the SQL front end from "one query block" to
"a tree of query blocks": `SqlQueryFrontEnd.plan` screens the statement, parses it with Calcite Babel,
runs four pre-validation `SqlNode` rewriters (star expansion, `date_trunc` rebinding, alias injection,
text rebinding), validates, and lowers to a `RelNode` **with no planner rules**;
`RelToStructuredQueryTranslator.fold` folds that plan bottom-up into a mutable `Ctx`, **sealing** the block
so far into a `DerivedRelation` whenever the next operator cannot share it; `CompositeQueryBuilder` renders
the resulting `CompositeQuery` tree into one jOOQ `SelectQuery`, every block through the ordinary
`StructuredQueryBuilder` so masking, enrichment joins and function rendering have exactly one
implementation.

Constraints that shape the approach:

- `fold` has a `case SetOp -> throw` arm and `screen` has a `SqlKind.UNION/INTERSECT/EXCEPT` rejection.
  Those two sites are the whole of the current refusal; everything below them is shape-agnostic.
- `Relation` is a sealed hierarchy (`EntityRelation`, `DerivedRelation`, `JoinRelation`) and
  `CompositeQuery` is *a block over a relation*. A set operation is a relation, not a block.
- Row governance is a property of the **outermost block**: `StructuredQueryBuilder` applies the default
  limit and the maximum only under `LimitPolicy.GOVERNED`, which only the root gets.
- Partition-local `final` deduplication and `join_use_nulls = 1` are already decided per *statement* by
  `StructuredQueryService.execute(CompositeQuery, int)`, over whatever entities the plan resolves — so a
  set operation inherits both with no new code.
- **Calcite's parse is lossy in exactly the two places ClickHouse and the standard disagree.**
  `SqlStdOperatorTable.UNION` is the operator for both `UNION` and `UNION DISTINCT` (`all = false` either
  way), and likewise for `INTERSECT`/`EXCEPT`, so what the caller wrote is unrecoverable from the AST
  alone. And a trailing `ORDER BY` parses as `SqlOrderBy` wrapping the whole set operation — standard
  scope — with no record that ClickHouse would have bound it to the last branch.

Everything asserted about ClickHouse below was measured against the local server (26.3.27.3), not derived
from documentation:

| Text | ClickHouse | Standard SQL / Calcite |
|---|---|---|
| `SELECT 1 UNION SELECT 1` | error, `union_default_mode` is empty | `UNION DISTINCT` |
| bare `INTERSECT` / `EXCEPT` | `ALL` (`intersect_default_mode`, `except_default_mode` both `ALL`) | `DISTINCT` |
| `A UNION ALL B ORDER BY x LIMIT 2` | binds to **B** alone | binds to the combined result |
| `A UNION ALL B INTERSECT ALL C ORDER BY x LIMIT 1` | binds to **C** alone — the chain's final `SELECT`, not the `B INTERSECT ALL C` node the clause sits above in a parse tree (measured on 26.3.24: returns the one row of `A`) | binds to the combined result |
| `WITH c AS (…) A UNION ALL B ORDER BY x LIMIT 1` | binds to **B** alone; the `WITH` changes nothing (measured: 2 rows, not 1) | binds to the combined result |
| `A UNION ALL (B) ORDER BY x` | **syntax error** — the clause belongs to an unparenthesized last branch | valid |
| `(A UNION ALL B) ORDER BY x`, `A UNION ALL (B INTERSECT ALL C) ORDER BY x` | **syntax error** too — any parenthesis closing before the clause | valid |
| `(A) UNION ALL B ORDER BY x LIMIT 1`, `A UNION ALL (B) INTERSECT ALL C ORDER BY x LIMIT 1` | **valid** — a parenthesized branch that is not the last one leaves the final `SELECT` bare (measured: both return 2 rows) | valid |
| `A MINUS B` | **syntax error** — no such keyword | `EXCEPT DISTINCT` (Oracle synonym) |
| `A UNION ALL B INTERSECT ALL C` | `INTERSECT` binds tighter | same |
| branch columns | matched by position, named after the first branch | same |

## Goals / Non-Goals

**Goals:**

- Accept set operations wherever a query block is accepted, on all three SQL surfaces, with the existing
  governance intact and the single-entity path byte-identical.
- For every accepted statement, return the rows ClickHouse returns for the same text — including the two
  cases where that is *not* what standard SQL means, and including refusing what ClickHouse refuses.
- Reuse the existing block rendering for every branch, so a branch cannot diverge from a single-entity
  statement in masking, enrichment or function semantics.

**Non-Goals:**

- A DSL representation of set operations. `POST /v1/queries/execute` stays single-entity; a set-operation
  statement is composite and has no `StructuredQuery` document, exactly as a join has none.
- Removing the three surface-level differences from a raw `clickhouse-client` session that predate this
  change and apply to every statement: the row cap, `final = 1`, and `join_use_nulls = 1` on composite
  statements. The rule this design serves is about what the operators *mean*.
- Revisiting the other places the SQL surface followed PostgreSQL — the strict `GROUP BY` conformance, the
  refusal to coerce `CASE` branches, PostgreSQL-derived names for unnamed select expressions. Each is
  shipped behaviour a set operation inherits; each is a breaking change to accepted statements and belongs
  to its own change now that PostgreSQL compatibility is not a goal.
- Correcting `SQL_SUPPORTED_SUBQUERIES`, `SQL_OUTER_JOIN_SUPPORT_LEVEL` and `SQL_MAX_TABLES_IN_SELECT`
  (see D9).
- `WITH RECURSIVE`, whose body is a `UNION ALL` but whose execution is a fixpoint nothing here expresses.

## Decisions

### D1. A set operation is a fourth `Relation`, with `CompositeQuery` branches

```java
record SetOpRelation(SetOpKind kind, boolean all, List<CompositeQuery> branches, List<RelationColumn> columns)
        implements Relation
enum SetOpKind { UNION, INTERSECT, EXCEPT }
```

`branches` are full blocks, not bare relations, because a branch carries its own `WHERE`, `GROUP BY`,
`DISTINCT`, `ORDER BY` and `LIMIT`. `columns` is the operation's output row, carrying both the DSL
`QueryFieldType` and the runtime `ResultColumnKind` as every other relation's columns do. `all` is
resolved by D2 before the AST reaches the fold, so by this point it is unambiguous.

*Rejected:* a fourth `SqlStatementPlan`/`SqlExecutableStatement` member. A set operation nests (a CTE body,
a derived table, a join operand, an `IN` operand), so modelling it at statement level would need a second,
parallel nesting mechanism beside `Relation`.

*Rejected:* reusing `DerivedRelation` with a list and a marker. It makes every `switch` over `Relation` lie
about what it handles, and the compiler stops helping — the sealed hierarchy's exhaustiveness is what makes
the edit sites (`CompositeQueryBuilder.render`, `CompositeQueryEntities`, the fold's operand rule)
impossible to miss.

### D2. The written qualifier is recovered from source positions, then expressed as Calcite's own ALL operator

The bare spellings cannot be distinguished from the explicit `DISTINCT` ones in the AST (see Context), so
a new pre-validation pass reads them off the **source text** and rewrites the AST to say what the caller
meant. For each set-operation `SqlBasicCall`, the operator's tokens lie strictly between the end of
`operand(0)`'s `SqlParserPos` and the start of `operand(1)`'s; that region is extracted, comments are
stripped, and its tokens are read:

- `MINUS` → rejected, naming `EXCEPT`. ClickHouse has no such keyword.
- `UNION` with no qualifier → rejected, naming `ALL`/`DISTINCT`, which is what ClickHouse's default
  `union_default_mode` does to the same statement.
- `INTERSECT`/`EXCEPT` with no qualifier → the call's operator is swapped for
  `SqlStdOperatorTable.INTERSECT_ALL`/`EXCEPT_ALL`, matching ClickHouse's `ALL` default modes.
- an explicit `ALL` or `DISTINCT` → left exactly as parsed.

Because the resolved operator is always an explicit one, the rendered SQL always carries the keyword, and a
server whose default modes were reconfigured cannot reinterpret a statement ADAS accepted.

*Rejected:* rewriting the SQL **text** before parsing (insert ` ALL` after a bare keyword). It works, but
every parse error after the insertion point would be reported at a shifted position, and the parse-error
message quotes the statement — so a caller would be shown a caret pointing at the wrong token in a
statement they did not write.

*Rejected:* deciding by in-order traversal instead of positions (the k-th set-operation keyword in the
text is the k-th set-operation node in an in-order walk). It happens to be true for a binary expression
tree, but it couples a lexical fact to a traversal order that nothing enforces, and it still needs the
tokenizer.

*Rejected:* leaving the bare forms bare in the rendered SQL and letting the server apply its own default
modes — maximal fidelity, including to a reconfigured server. It requires carrying a third
`UNSPECIFIED` state to the renderer, and it makes a statement's meaning depend on an operator's setting
that ADAS neither reads nor reports. Pinning ClickHouse's *default* modes and always rendering explicitly
is the same behaviour for every default deployment and a stable one for the rest. The single consequence
is recorded in the spec: against a server configured `union_default_mode = 'ALL'`, ClickHouse accepts a
bare `UNION` that ADAS refuses.

### D3. A trailing `ORDER BY`/`LIMIT` is re-associated into the last branch

ClickHouse binds a trailing `ORDER BY`, `LIMIT` and `OFFSET` to the last branch, and refuses the clause
outright when a parenthesis closes before it (both measured — see Context). The same pass therefore, for
a `SqlOrderBy` wrapping a set operation:

- peel a `SqlWith` first. Calcite wraps the clause around the whole query it sorts, `WITH` included, so
  `WITH c AS (…) A UNION ALL B ORDER BY x` parses as `SqlOrderBy(SqlWith(<set op>))`; the operation the
  clause trails is the `WITH`'s body, and the `WITH` stays the statement;
- find the chain's **final `SELECT`** by walking the right spine, not by taking the last operand: a
  mixed-precedence chain nests, so the last operand of `A UNION ALL B INTERSECT ALL C` is the `INTERSECT`
  node while ClickHouse's final `SELECT` is `C`;
- if that branch's own span opens with `(` — `A UNION ALL (B) ORDER BY x` — or a `)` appears between the
  end of its span and the clause keyword — `(A UNION ALL B) ORDER BY x`, and
  `A UNION ALL (B INTERSECT ALL C) ORDER BY x` — a parenthesis closes before the clause → **reject**, with
  a message naming ClickHouse's reading and the spelling that works
  (`SELECT * FROM (<set operation>) ORDER BY …`). Two readings are needed because one span cannot answer
  both: what encloses the operation cannot be read off the operation's own span, which starts at its
  *first* branch, so `(A) UNION ALL B ORDER BY x` — which ClickHouse executes — opens with `(` exactly as
  `(A UNION ALL B) ORDER BY x` does. Stopping the forward scan at the clause keyword is what keeps a
  nested operation readable: inside a derived table the text after the final branch is `" ORDER BY 1) t"`,
  whose parenthesis closes the derived table and comes *after* the clause;
- otherwise, move `orderList`, `offset` and `fetch` into that final branch and replace the statement with
  the set-operation call itself (or with the `SqlWith` whose body it is).

The clause then folds into the last branch's own block under `LimitPolicy.NESTED`, so it is executed as
written — neither defaulted nor capped — exactly as a branch limit the caller parenthesized.

**Calcite's `removeSortInSubQuery` has to be turned off for that to survive.** `SqlToRelConverter`'s
default throws away the collation of any `ORDER BY` that is not the statement's outermost clause and
carries no `LIMIT`/`OFFSET`, on the standard's reading that such a sort orders an unobservable
intermediate result. Re-association makes the trailing clause a set-operation *input's* sort, so with the
default in force `A UNION ALL B ORDER BY x` planned to no `Sort` at all and rendered with no `ORDER BY`:
not the standard's reading of the clause, not ClickHouse's, but no reading — and because the combined
result then takes the default 100-row limit with nothing ordering it, the **rows** returned could differ
from ClickHouse's answer and not only their order. The front end therefore builds its `FrameworkConfig`
with `SqlToRelConverter.config().withRemoveSortInSubQuery(false)`.

That flag is per-plan, not per-node, so it widens beyond set operations: an `ORDER BY` inside a derived
table with no row bound now survives too, where the shipped spec said the planner discarded it. That is
the same fidelity fix rather than a regression — ClickHouse honours such a sort (measured on 26.3.24, with
`query_plan_remove_redundant_sorting` at its default `1`), so the drop was a divergence of its own — and
the spec's "Derived tables nest query blocks" requirement is modified to say so.

*Rejected:* gating the flag on whether the statement contains a set operation. Calcite offers no per-node
control, so the gate would have to be a pre-scan of the parse tree feeding a second `FrameworkConfig`,
and it would buy the right to keep executing a derived-table sort differently from the database.

The **row cap is unaffected and still bounds the combined result**: the fold always seals a set operation
into a fresh `Ctx` whose source is the `SetOpRelation`, so there is always an outer block to carry
`LimitPolicy.GOVERNED`. A statement whose trailing `LIMIT` went into a branch has stated no outer bound, so
it takes the default limit of 100 and, on Flight, the clamp-and-probe — the same treatment as a statement
with no `LIMIT` at all, which is the honest reading: ClickHouse would have returned the whole of every
other branch.

*Rejected:* keeping standard scope (the clause bounds the combined result). It is the reading a
standard-SQL user expects and it makes the row cap trivially enforceable — and it silently returns
different rows than the same text does against ClickHouse, which is the one thing this change may not do.

*Rejected:* wrapping the last branch in a derived block when it is parenthesized, so the clause has
somewhere to live. It would accept a statement ClickHouse answers with a syntax error, inventing a meaning
for text that has none.

### D4. Rendered through a jOOQ plain-SQL table template, not jOOQ's set-operation API

`SetOpRelation` renders as a `Table<?>` built from a template — `({0}) UNION ALL ({1})`, left-folded over
the branches, each branch a `Select` rendered by the same `render(block, NESTED, 0)` every other nested
block goes through — and is then aliased and wrapped in a synthetic `QueryEntityModel` exactly as
`DerivedRelation` already is.

*Rejected:* `Select.union/unionAll/intersect/intersectAll/except/exceptAll`. jOOQ decides the rendered
keyword per dialect: a bare `UNION` would be refused outright by ClickHouse's empty `union_default_mode`,
and `intersectAll`/`exceptAll` are emulated on dialects jOOQ believes lack them. The keyword is the one
thing D2 makes load-bearing, so it is spelled literally and pinned by a render test. Plain-SQL templates
are already how `ExprTranslator` renders window frames and the function catalog's shapes, so this
introduces no new mechanism.

Parenthesising every branch is required, not cosmetic: a branch carrying its own `ORDER BY`/`LIMIT` only
parses inside parentheses, and the parenthesised form was verified against ClickHouse 26.3 for every
spelling.

### D5. Output columns unify the branches' own columns, falling back to the validated row type

For each ordinal, if every branch's `RelationColumn` agrees on `(type, elementType, kind, elementKind)`,
the output column is that column under the set operation's name. This is the common case and it preserves
precision the DSL type alone loses — an `enum` stays distinguishable from a `string`, an `array`'s element
kind survives, `FLOAT64` does not collapse into `DECIMAL`.

Where the branches disagree, the column is derived from the `SetOp`'s **validated** `RelDataType`, which is
Calcite's least-restrictive unification of the branches. That needs a `SqlTypeName → ResultColumnKind`
mapping, which exists today as a private method in `InformationSchemaExecutor`; it moves to `ResultColumns`
beside the declared-type mapping, so the codebase keeps one of each rather than two of one. A unified type
with no mapping is a 400 naming the column and the branch types — fail-closed, and unreachable for the
types the catalog can produce.

That unification is also where a text-against-number pair lands, and it does not land here: Calcite's
coercion *does* unify them, by inserting a `CAST` to text into the other branch's projection, and
`RexToExprTranslator` refuses that cast on purpose (lowering an injected text coercion would widen every
text function to accept any type). Left alone, the caller reads "CAST from BIGINT to VARCHAR is not
supported" for a statement containing no cast, and `validate-sql` adds "drop the CAST". So folding a
branch re-words exactly that refusal as the branch mismatch above. The injected cast is identified rather
than guessed: a caller-written cast **to** text never reaches the Rex translation as a cast — it has become
a `TO_STRING_CAST` marker before validation — so a cast to a character type in a branch's projection is
the validator's own, and the re-wording is keyed to that cast's own message, leaving every other rejection
from inside a branch untouched.

Names come from the **first branch's rendered columns** — that is, from the `RelationColumn`s the branch
block actually exposes, which is what the wrapper block's SQL references. `setOp.getRowType()` is Calcite's
copy of the first input's names (the same rule ClickHouse applies) and usually says the same thing, but not
for an unnamed expression: there the row type carries a synthesized `EXPR$0` while the branch renders the
DSL's base name, and referencing `EXPR$0` from above gives ClickHouse `Code: 47 UNKNOWN_IDENTIFIER`. D7's
alias injection hides that at the top level only — it names the *statement's* output list — so a nested
operation (CTE body, derived table, `IN`/scalar-subquery operand) needs the branch's own name. Taking it
from the branch also keeps the placeholder out of the result descriptor.

### D6. Branches are never column-pruned

A join operand is pruned to the columns the enclosing block reads (`extend-sql-surface` D3). A set
operation's branches must not be: every branch has to keep the same arity in the same order, so pruning
would have to prune identical ordinals in every branch, and a `DISTINCT` branch's row count depends on the
columns it projects. Branches therefore render their full output list, like derived blocks. The wrapper
block above the set operation still projects only what the statement asked for.

### D7. Front-end passes that must learn about set operations

- **`SqlSetOperations`** (new) runs immediately after `planner.parse`, before every existing rewriter: it
  resolves qualifiers (D2) and re-associates trailing clauses (D3), walking the statement body, every CTE
  body, every derived table and every subquery so the rules hold at each level. It needs the raw SQL text
  as well as the tree, and it owns the small helper that maps a `SqlParserPos` to a text offset and reads
  comment-stripped tokens from a region. The expression walk covers every **`SqlCall`** shape rather than
  `SqlBasicCall` alone (`SqlCase` is a call that is not a basic one, so a subquery in a `WHEN`/`THEN`
  would otherwise keep Calcite's parse) and the `FROM` walk descends a join's `ON` predicate as well as
  its two sides. A slot is written back only when the walk actually replaced the node, so a call whose
  operand list is fixed is never asked to accept one.
- **`screen`** admits `UNION`/`INTERSECT`/`EXCEPT` as statement kinds, after the existing `ORDER BY` and
  `WITH` peeling, so a recursive CTE whose body is a `UNION ALL` is still refused by the `WITH` check ahead
  of it.
- **`SqlStarExpander.expandStars`** recurses into each branch of a set-operation node. Without this arm the
  function returns early at its `instanceof SqlSelect` guard and a branch's `*` is left to the validator,
  which expands the table's **full** row type — heavy columns and dotted enrichment columns included. That
  is a masking-adjacent defect, not a cosmetic one, so it is the arm most worth a test. Set operations
  nested inside an expression already work: `expandNestedQueries`'s visitor walks the set-operation call's
  operands and reaches the branch `SqlSelect`s on its own.
- **`SqlSelectAliasInjector.selectOf`** follows a set operation to its **first** operand, the branch whose
  names become the statement's output names.
- **The fold's join-operand rule** (`asOperand`) passes a relation through un-sealed only for an
  `EntityRelation` or a left-side `JoinRelation`; a set operation used as a join operand is sealed into a
  `DerivedRelation` first. That keeps `CompositeQueryBuilder.operand` free of a set-operation arm and keeps
  the left-deep join rendering unchanged.
- `SqlDateTruncRebinder` and `SqlTextRebinder` walk every `SqlCall` and need no change.

### D8. The `INFORMATION_SCHEMA` boundary is explicit, not incidental

`InformationSchemaExecutor.isMetadataStatement` decides, after validation, whether a statement is answered
in process. Its evaluator has no set-operation support, and two of its comments currently rely on set
operations "already being rejected". A set operation over catalog relations must therefore be refused **by
name** rather than falling through to the ClickHouse translator, which would fail later with a message
about an unknown entity. A statement mixing a catalog relation with a queryable entity is already refused
by the existing mixing check; the new case is one whose branches are *all* metadata.

### D9. Flight advertises unions; three neighbouring declarations are recorded, not fixed

`SQL_SUPPORTED_UNIONS` becomes `SQL_UNION | SQL_UNION_ALL` — determined by the new requirement and
non-steering, since every statement a client generates from it is accepted (a client that generates a bare
`UNION` from `SQL_UNION` is generating the one spelling D2 refuses, which is also the one ClickHouse
refuses, so the advertisement is no worse than the database's own). `INTERSECT` and `EXCEPT` have no
`SqlInfo` code, so nothing can be said about them.

`SQL_SUPPORTED_SUBQUERIES` (empty), `SQL_OUTER_JOIN_SUPPORT_LEVEL` (empty) and `SQL_MAX_TABLES_IN_SELECT`
(`1`) are all wrong today — they describe the surface as it was before `extend-sql-surface` — and they are
left wrong here, recorded in the spec as inherited under-claims. They under-claim in the safe direction,
none of them stops a statement from executing, and correcting them is one derivation across all three
(which bitmask members hold; Flight's quantified-subquery bit is still rejected; whether `0` or a real
number is right for a table count). Folding that into a set-operation change would mean this change's
Flight diff was mostly not about set operations. The cost of the split is that a client reading
`SQL_MAX_TABLES_IN_SELECT = 1` alongside the new union declaration is told something incoherent — which
the spec now says out loud instead of leaving to be rediscovered.

### D10. `validate-sql`'s classifier loses a rule

`SqlIssueClassifier` maps the set-operation refusal's message text to `unsupported_construct` with the
remedy "run the branches as separate statements". The message it matches stops existing, so the rule goes
with it rather than being left to match nothing. A set operation now classifies as valid; a bare `UNION`,
a `MINUS` and an incompatible branch list classify through the existing construct and validation paths.

## Risks / Trade-offs

- [The qualifier pass mis-reads a region and silently changes an operator — a comment between the
  operands, an operand whose `SqlParserPos` does not end where the text does] → the pass fails closed: it
  rejects a region whose first token is not one of the four keywords rather than assuming a default, and
  unit tests cover a comment between the operands, a newline-separated operator, parenthesized operands,
  a nested chain, and a quoted identifier named `union`.
- [The `ORDER BY` re-association changes validation scope — `ORDER BY x` now resolves against the last
  branch rather than the union's output] → that *is* ClickHouse's scope, so a statement naming a column
  only the first branch exposes fails here exactly as it fails there. Tested both ways.
- [A standard-SQL user is surprised: their trailing `ORDER BY` no longer sorts the whole result] → it is
  the stated rule, the spec records it as a deliberate divergence from the standard, `docs/sql-reference.md`
  documents the working spelling, and the surprise is the same one they would get running the query against
  ClickHouse directly.
- [jOOQ renders the plain-SQL template in a way ClickHouse rejects — an unparenthesised branch, a mangled
  bind] → the rendered SQL is asserted verbatim in a render unit test for every spelling, and executed
  against real ClickHouse in integration tests.
- [Result column types differ from what ClickHouse actually returns, so the response descriptors or the
  Arrow schema mislabel a column] → D5 prefers the branches' own agreed kinds and only falls back to
  Calcite's unification; an integration test compares the declared descriptor against the values that
  arrive for a mixed-type branch pair.
- [A set operation multiplies scanned data — two full-entity branches under one budget] → the
  `query.budget.*` ceilings apply to the whole statement and the 422 already names them. A `UNION DISTINCT`
  additionally deduplicates in memory on the server, which is what the budget's memory ceiling is for.
- [The governance wrapper defeats predicate pushdown for some shapes] → ClickHouse's analyzer pushes the
  outer clauses into branches in the common cases and the query budget bounds the rest; the same trade is
  already taken for derived tables.
- [`join_use_nulls = 1` now applies to statements with no join in them] → it is a no-op without an outer
  join, and a set operation whose branch contains an outer join gets the semantics that branch would have
  had on its own. No behaviour changes for any statement accepted today.

## Migration Plan

No migration, no config key, no credential, no catalog or ClickHouse schema change. Deploy as a normal
release; rollback is a redeploy of the previous image. Previously rejected statements start succeeding; no
accepted statement changes result. Two declarations change what they report — Flight's
`SQL_SUPPORTED_UNIONS` and `validate-sql`'s verdict for a set operation — and both move from describing a
refusal to describing acceptance.

## Open Questions

None that change the specs, the approach or the task breakdown. The three Flight declarations of D9 are a
named follow-up, not an open question: what to do about them is decided (correct them together, in their
own change), only when is open.
