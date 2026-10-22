## Context

A measure is compiled by `AggregateCompiler.measureField`, which has two paths. Without a `where` it
builds an `FnExpr` and hands it to `ExprTranslator.toField` — and hardcodes the `FnExpr`'s `distinct`
flag to `false`. With a `where` it builds the operand itself (`CASE WHEN <pred> THEN <column> END`,
because ClickHouse has no SQL-standard `FILTER (WHERE …)`) and calls `ExprTranslator.toAggregate`,
whose signature has no quantifier at all.

Everything below that line already exists. `ExprTranslator.toFunction` renders
`DSL.countDistinct` / `DSL.sumDistinct` / `DSL.avgDistinct` for `count`, `sum` and `avg`; the catalog
carries `distinct_supported` per function (`QueryFunctionDto.distinctSupported`); `ArrayValuedAggregate.rejectDistinct`
already refuses the quantifier for `group_uniq_array` with a specific message; and the rendered SQL is
asserted as `count(DISTINCT "project_id")` in `StructuredQueryToSqlRendererTest`. The query DSL, the
SQL front end and Flight SQL all accept the quantifier today.

So this is not "add distinct support". It is "stop suppressing a quantifier the layer below already
implements" — plus one signature that has to widen for the `where` path, and a declaration-time gate so
the pipeline surface refuses exactly what the query surface refuses.

The motivating defect is in `proposal.md`. The relevant constraint for this design is that
`AggregateSpecValidator` already resolves every measure's `fn` against the catalog descriptor
(`aggregateFunction`, `requireSingleColumnArity`), so the descriptor carrying `distinctSupported` is
already in hand at the point the new gate belongs.

## Goals / Non-Goals

**Goals:**

- `count(distinct <column>)`, `sum(distinct …)` and `avg(distinct …)` declarable as pipeline measures.
- `distinct` composable with a measure's `where`, since `success_count` needs both.
- The accept/reject decision derived from the catalog descriptor, not from a function-name list, so the
  pipeline and query surfaces cannot drift.
- Zero change in rendering for every stored declaration.

**Non-Goals:**

- No new catalog function, no change to the catalog's contents or to any function's signature.
- No new aggregate semantics at the query surface — this change adds nothing there.
- Not fixing `conversations` itself. Re-declaring `conversations_rollup` is an operator action against
  `/v1/pipelines`; see Migration Plan.
- `chain_price_total` is not addressed. Its NULLs are a source-data property (the router's
  chain-starting hop carries `total_price = NULL` together with an empty `chat_id`), not a vocabulary
  limit.

## Decisions

### D1: A `distinct` quantifier on `Measure`, not a `count_distinct` catalog function

**Chosen:** add `private boolean distinct` to `Measure`.

The alternative — a `count_distinct` entry in the shared catalog — was rejected on three counts. It
would duplicate `count` in a closed catalog whose whole point is one entry per operation; it would
leave `sum`/`avg` distinct still unreachable from a measure, so the same gap reopens on the next
request; and it would contradict the catalog's own metadata, which already advertises
`distinct_supported: true` on `count` and describes the flag in the function's own description ("with
distinct counts unique values"). A `distinct_supported` flag that no pipeline declaration can ever set
is the actual inconsistency here, and the quantifier removes it.

It is also the smaller change: the no-`where` path becomes a one-argument edit, replacing a hardcoded
`false` with `measure.isDistinct()`.

### D2: Widen `toAggregate` rather than render the distinct aggregate inside `AggregateCompiler`

`toAggregate(String fn, Field<?> operand)` is the prepared-operand entry point the `where` path uses.
Its javadoc states the quantifier is deliberately not a parameter, on the stated premise that "a
pipeline measure never sets it" and that admitting it "would add a state no caller reaches". This
change retires that premise, so the signature becomes `toAggregate(String fn, Field<?> operand, boolean
distinct)` and the note is rewritten. Leaving a javadoc that contradicts the code is worse than either
option.

The alternative — `AggregateCompiler` calling `DSL.countDistinct(caseExpr)` itself — was rejected
because it breaks the property that method exists to hold: exactly one rendering per function, reached
two ways. `toFunction` delegates its non-distinct single-operand branches to `toAggregate` precisely so
the pipeline cannot drift from what the query surface emits. A second renderer for the distinct form
would reintroduce the drift the current structure was built to prevent.

Preserved by construction: `toFunction`'s distinct branches keep delegating to `toAggregate`, so both
surfaces still render through one place.

### D3: Gate on the catalog descriptor's `distinctSupported`, not on a name list

`AggregateSpecValidator` already holds the `QueryFunctionDto` for the measure's `fn`. The new check
reads `distinctSupported` off it, mirroring how `requireSingleColumnArity` keys on the descriptor's
required-argument count rather than on function names — so a future catalog function that supports the
quantifier is covered with no edit here, and one that does not is refused without one.

`group_uniq_array` gets a specific message rather than the generic one, matching
`ArrayValuedAggregate.rejectDistinct`'s wording ("it already returns the distinct values of the
group"). Two surfaces answering the same input with two different explanations is the kind of
divergence this capability's tests already guard against.

### D4: `distinct: true` requires a `column`

`count` is the one function whose `column` is optional, and a column-less `count` is a row count. The
alternative — silently treating `distinct` as a no-op there — stores a declaration whose rendering
contradicts its text. Rejecting at declaration is the same argument the rest of the gate makes.

### D5: Distinct-with-`where` composes through the existing `CASE`, unchanged

The operand stays `CASE WHEN <pred> THEN <column> END` and only the quantifier is added, giving
`count(DISTINCT CASE WHEN success = true THEN trace_id END)`. This is correct without a special case:
`count(DISTINCT x)` counts distinct **non-NULL** values, and a non-matching row contributes the `CASE`'s
implicit NULL. So the predicate selects the rows and the quantifier deduplicates the values on them,
which is exactly the declared meaning.

A group matching nothing yields all-NULL and counts `0` — the same "honest answer" the existing
`count` exemption already relies on, so no nullability rule changes and no target column has to become
nullable. This is what makes fixing the live rollup a pipeline patch with no schema patch.

### D6: The nullability requirement is deliberately left untouched

`distinct` introduces no new NULL source: a distinct `count` of nothing is `0`, and a distinct
`sum`/`avg` over a group with no non-null value is NULL for the reason the existing rule already gives
— its input column is nullable. Both cases are already covered, so "A nullable measure result requires
a nullable target column" needs no delta. The consequence is stated in the vocabulary requirement as a
cross-reference instead, which keeps the exemption's wording in one place.

## Risks / Trade-offs

**`count(DISTINCT x)` is exact only by server default** → ClickHouse rewrites `count(DISTINCT x)`
according to the `count_distinct_implementation` setting, whose default `uniqExact` is exact but whose
other values (`uniq`, `uniqCombined`) are approximate. A stored rollup silently becoming approximate
because of a server setting is a correctness hazard that no test in the application would catch. Nail
it down with an integration test asserting the written value equals a known distinct count over seeded
rows, so a changed setting fails the build rather than quietly degrading a materialized column.
Rendering `uniqExact` directly was considered and rejected: it would name a ClickHouse function in the
pipeline's compiled output where the query surface names none, splitting the single rendering D2
protects — and the query DSL already carries the identical exposure, so the setting is the right place
to hold the invariant, not this one call site.

**A distinct measure is not additive across groups** → summing a stored distinct count over several
rollup rows double-counts any value the groups share, unlike the additive measures around it. For the
motivating case this is safe and was checked against live data: no `trace_id` spans two `chat_id`s
(maximum distinct `chat_id` per `trace_id` is 1 over 1.69M rows), so summing `turn_count` across
conversations is sound. It is not sound in general, and the capability's existing "store only additive
measures" reasoning does not extend to cover it. Note it where the measure is declared rather than
trying to enforce it — the pipeline cannot know whether a caller will re-aggregate.

**Exactness costs memory at high cardinality** → `uniqExact` holds a hash set per group for the
refresh's duration, where a plain `count` holds a counter. The refresh recomputes whole groups, so the
set is sized by the group's distinct values, not the window's rows. For `conversations` that is turns
per conversation (~900 at the top of the live distribution) and is negligible; a grain with millions of
distinct values per group would not be. Not mitigated in code — it is the cost of the exactness the
feature exists to provide.

**A primitive creator property makes every older declaration a 400** → found during implementation, not
predicted here. Adding a bare `boolean distinct` to the `MeasureRequest` record made *every*
`POST /v1/pipelines` fail with 400, because a record deserializes through its canonical constructor and
an absent primitive property is rejected rather than defaulted — so every declaration written before the
quantifier existed, which is all of them, stopped binding. The fix is the idiom the codebase already
carries on `StructuredQuery.distinct`: `@JsonSetter(nulls = Nulls.AS_EMPTY)`, which makes absent-or-null
read as `false`. Worth recording because it is the one way this "additive" change could have shipped as a
breaking one, it is invisible in a unit test of the validator or the compiler, and it applies to any
future primitive added to these DTOs.

**A widened signature touches a shared translator** → `toAggregate` is on the query path as well as the
pipeline path. The change is additive (a new parameter with `false` reproducing today's behaviour at
every existing call site), and `toFunction`'s existing distinct branches already prove the renderers
work, so the exposure is a mechanical one. `-Werror` plus the existing renderer tests cover it.

## Migration Plan

No schema migration on either store: a measure lives in the pipeline's `spec` JSONB.

Deploy is ordinary. Afterwards, the live `conversations_rollup` needs an operator `PATCH
/v1/pipelines/conversations_rollup` replacing two measures:

- `turn_count` → `{fn: "count", column: "trace_id", distinct: true}`, dropping `where`
- `success_count` → `{fn: "count", column: "trace_id", distinct: true, where: "success = true"}`

Both target columns stay non-nullable (D5), so no `PATCH /v1/tables/conversations/schema` is needed.
`chain_price_total` is left as declared.

Two consequences the operator should expect, both existing behaviour rather than anything this change
introduces: re-declaring a pipeline's spec resets its cursor and rebuilds the rollup, so the whole
input is re-read and every conversation is rewritten; and the `chat_id`-only grain has no time bucket,
so each refresh reads essentially the whole input. Keep the cron coarse while it drains.

Rollback is the inverse patch. A declaration carrying `distinct` against a rolled-back build is
rejected at registration by the unknown-property path rather than silently ignored, so the ordering to
avoid is rolling back the code while leaving the patched declaration in place — patch the pipeline back
first.

## Open Questions

- Should the `conversations_rollup` patch be applied to a running deployment as part of this change's verification, or
  held until the `turns` table work lands so both re-drain once instead of twice? The re-drain is the
  expensive part, not the patch.
- `turn_count` currently counts a turn of any `event_kind`, so a chat-bearing `embedding` or `mcp`
  trace with no `llm_call` counts as a turn. Distinct-counting makes this visible rather than causing
  it (agent internals sharing a user turn's `trace_id` already collapse correctly). Whether a turn
  should be restricted to `llm_call` is a product question, expressible as a `where` once this lands,
  and deliberately not decided here.
