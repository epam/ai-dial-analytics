## Context

The function catalog is shared: `QueryFunctionCatalog` is read by structured execution, SQL
translation, schema discovery, Flight, and — for its `AGGREGATE` group only — by
`AggregateSpecValidator` when it resolves a pipeline measure's `fn`. One entry therefore reaches five
surfaces, which is the reason the catalog is closed and the reason a single addition needs its
interactions thought through rather than assumed.

Three existing facts shape every decision below.

**Multi-argument aggregates already exist, in a separate group.** `percentile_cont` and
`percentile_disc` are `ORDERED_SET_AGGREGATE`, take `(fraction, column)`, and are excluded from
measures because `AggregateSpecValidator.aggregateFunction` filters on `group() == AGGREGATE`. So
precedent exists for a two-operand aggregate on the query surface — but not for one inside the
`AGGREGATE` group.

**Non-standard functions already have a full plumbing recipe.** `group_uniq_array` is not a jOOQ
built-in and not a Calcite standard operator, and it works on both surfaces because four places were
edited for it: a `DSL.field` template in `ExprTranslator`, a `SqlAggFunction` in `SqlQueryOperators`,
an `aggExpr` case and a `fnKind` case in `RelToStructuredQueryTranslator`. That list is the checklist
for this change (D0).

**The measure path already anticipates this exact function by name.**
`AggregateSpecValidator.requireSingleColumnArity` exists solely to refuse an `AGGREGATE`-group function
whose required arity exceeds one, and its javadoc says:

> *"No aggregate in the catalog currently takes two required arguments, so this is a guard on future
> additions rather than a live restriction — a two-operand aggregate such as `arg_max(value, order)` is
> the shape that would trip it… Keyed on the descriptor's required arity rather than a function-name
> list, so such an addition is covered without editing this class."*

That guard is why this change is low-risk: the author designed for it. Note the consequence for this
change's own bookkeeping — the first clause stops being true, so the javadoc must be edited even though
the code must not be (D6).

## Goals / Non-Goals

**Goals**

- Make "the value of the group's latest row" expressible at query time, on every surface.
- Add it without touching pipeline behavior, and without any pipeline silently accepting something it
  cannot execute.
- Pin the semantics that are easy to get wrong — nulls, ties, `distinct` — in the spec rather than
  leaving them to whatever ClickHouse happens to do.

**Non-Goals**

- Declaring `arg_max` as a pipeline measure (see D4).
- A general `order_by` qualifier on measures.
- `arg_min`. Now known to be cheap (D0 makes the rendering a one-line template either function could
  share), but every catalog entry is permanent surface and nothing needs it; add it when a use case
  exists, knowing the cost.
- Changing `min`/`max`, which remain the right functions for "the greatest value".

## Decisions

### D0: The rendering is a hand-built SQL template, not a jOOQ dialect feature

**Decision.** `ExprTranslator` renders `DSL.field("argMax({0}, {1})", Object.class, value, order)`.
jOOQ is never asked to know the function.

This settles the question the change was originally blocked on ("does jOOQ's experimental ClickHouse
dialect render a two-argument `argMax`?"). It does not need to. Two precedents in this repository
already do exactly this:

- `ExprTranslator.groupUniqArray` renders `DSL.field("arraySort(groupUniqArray({0}))", Object.class, …)`.
- `AggregateRefreshService` builds `DSL.field("argMax({0}, ({0}, {1}))", …)` for its cursor arithmetic —
  the same ClickHouse function, already emitted through jOOQ in shipped code.

**Consequences, all of which change how the rest of this change is scoped.**

1. The spec's experimental-dialect caveat does **not** extend to `arg_max`, and the delta must not
   imply it does. Its rendering is deterministic in the same sense `date_bin`'s
   `toStartOfInterval` is: the native function name is emitted directly.
2. The risk moves from "will it render" to "are all four plumbing points edited". A literal template
   cannot be wrong about SQL semantics, but a missing Calcite operator or a missing `fnKind` case
   breaks exactly one surface and nothing else notices.
3. The change is an hour's work with a known shape, not an open investigation.

**Still verified end-to-end against a live ClickHouse**, but for a different reason than the
percentiles are: not because the dialect is provisional, but because a hand-written SQL template and a
three-point Calcite registration are both the kind of thing a unit test over rendered text will happily
confirm while the statement fails to execute.

### D1: `arg_max(value, order)` in the `AGGREGATE` group, not `ORDERED_SET_AGGREGATE`

**Decision.** Two required expression arguments, return type same-as-argument, in the `AGGREGATE`
group.

**Alternative rejected — put it in `ORDERED_SET_AGGREGATE`.** That group's members render in SQL's
`WITHIN GROUP (ORDER BY …)` form, which is a genuinely different syntax that the SQL subset parses
specially. `arg_max` is an ordinary function call in both DSL and SQL, so shoehorning it into that
group would mean either an inaccurate rendering or a special case inside a group defined by its
rendering. It would also *hide* the arity question rather than answer it — the guard in D4 only
inspects `AGGREGATE` members, so the mismatch with the measure grammar would stop being surfaced.

**Consequence, accepted deliberately:** being in `AGGREGATE` means `aggregateFunction` resolves it for
measures and `requireSingleColumnArity` then rejects it. That is the correct outcome — a declaration
error at registration naming the two-argument signature, rather than a stored pipeline whose every
refresh fails.

### D2: `distinct` is rejected, not ignored — and rejected in three places

**Decision.** `arg_max` declares `distinct_supported = false`, and a `DISTINCT` quantifier is a 400.

Deduplicating `value` before the function runs would sever the pairing between a `value` and the
`order` that selects it — the result would be "some value, from a set, ordered by something that no
longer corresponds to it", which is meaningless rather than merely lossy. Rejecting is consistent with
`group_uniq_array`, whose spec already says a `DISTINCT` quantifier "SHALL be rejected rather than
ignored".

**Where the rejection has to live is not obvious, so it is recorded here.** The existing aggregates get
theirs from `ExprTranslator.toAggregate`, which `arg_max` does not go through (that method takes one
prepared operand, and its own javadoc already anticipates that "any future aggregate taking a second
operand could not be given one through this signature"). So:

- **DSL execute** — an explicit `fn.distinct()` check in the new `toFunction` branch.
- **SQL** — a check in `RelToStructuredQueryTranslator.aggExpr`, because
  `SqlBasicAggFunction.withDistinct` is not public API and the quantifier therefore parses.
- **DSL→SQL render** — `StructuredQueryToSqlRenderer` drops an unrenderable quantifier silently
  (`DISTINCT_AGGREGATES` holds only `count`/`sum`/`avg`). For `arg_max` that means `/v1/queries/translate`
  would quietly emit a valid statement for a DSL query that `/v1/queries/execute` rejects.
  `group_uniq_array` has this same asymmetry today, so **matching it is the decision** rather than the
  accident: `arg_max` behaves exactly as `group_uniq_array` does, and tightening both is a separate
  change to shipped behavior. Assert the behavior in a test so it is chosen, not inherited.

### D3: A row competes only when both operands are non-null; ties unspecified

**Decision.** A row is a candidate only when **both** its `order` and its `value` are non-null. The
result is the `value` of the latest qualifying row, and null exactly when no row qualifies. A tie on
the greatest `order` returns an unspecified one of the tied `value`s.

This matches ClickHouse `argMax` rather than inventing a stricter contract ADAS would have to enforce
itself. **The first draft of this decision got it wrong**, and the live test is what caught it: it said
a winning row with a null `value` yields null. It does not — ClickHouse skips that row and falls
through to the next-latest one that has a value. Verified against 25.3.

The difference matters to a caller, which is why it is spelled out rather than left as a footnote:
`arg_max` returns **the latest non-null value, not the value of the latest row**. Over a nullable
column those are different questions, and the one it answers is the less obvious of the two — "what did
this key last record" rather than "what does this key say now". A caller who wants the second has to
ask it differently.

The other half is **writing the tie behavior down**: a caller ordering by a non-discriminating column
(`toDate(request_time)`, say) gets a deterministic-looking result that is not deterministic, and that is
a trap worth documenting rather than discovering. Nullability matters downstream too — anything bound to
an `arg_max` result must be nullable.

**Alternative rejected — reject ties or ordering by a non-unique column.** Not knowable statically, and
enforcing it at execution would mean a second pass over the group purely to detect a tie.

### D4: The pipeline-measure path is deferred, and here is the shape it would take

**Decision.** Out of scope. `arg_max` is query-time only in this change.

**Why not bundle it.** A measure is `{name, fn, column, where, distinct}` — one column. Supporting
`arg_max` means a second column reference, which reopens the constraint `Measure`'s javadoc labels
**D-DECL-4**: *"One function over one column, deliberately… that restriction is structural here rather
than a rule to enforce."*

**What the extension would look like, recorded so the next change does not re-derive it.** The
principled form is a **third qualifier**, alongside the two that already exist:

| Field | Question it answers | Status |
|---|---|---|
| `where` | which of the group's *rows* the function reads | shipped |
| `distinct` | which of the operand's *values* it sees | shipped |
| `order_by` | which *row wins* | the proposed addition |

Both existing qualifiers were justified in exactly these terms — `where` is "a row-membership
predicate, not an expression over the aggregated value"; `distinct` is "a quantifier, not an
expression". An `order_by` **column** (never an expression) is the same category: it qualifies how the
function chooses among rows, and introduces no expression grammar. `requireSingleColumnArity` would
then relax from "required arity must be ≤ 1" to "required args must be satisfiable by the columns the
measure supplies", which is what its javadoc already implies by keying on arity.

**The genuine objection to weigh in that change**, and the reason it is not obviously fine: an
`arg_max` measure is **not re-aggregatable across stored rows**. `min`/`max` and `distinct` counts share
that property and are accepted, so it is not disqualifying — but a materialized "final rating" that
someone later sums across buckets is silently wrong, which is precisely the failure mode D-DECL-4 was
written to prevent. That deserves its own review, not a paragraph in this one.

### D5: Drop `rate_min`/`rate_max` rather than keep them beside a final-answer query

**Decision.** Remove them from `response_ratings` and `turn_feedback` as part of this change's rollout.

They carry no information the surrounding columns lack. For a response with one rating — the large
majority — exactly one of `rate_pos_count`/`rate_zero_count`/`rate_neg_count` is `1`, which *is* the
value. For agreeing ratings, likewise. For the disagreeing ones, `rate_distinct_count > 1` already
flags them, and min/max then report `-1`..`1`, the full range in nearly every case. Their only unique
content would be the magnitude of a rating outside {-1, 0, 1}, which `rate_sum` also reveals.

**There is one consumer, and it is documented rather than hypothetical.**
The turn-feedback plan doc publishes a rating-distribution query that groups by
`(turn_feedback.rate_min, turn_feedback.rate_max)`, and calls that grouping "deliberate". Dropping the
columns invalidates it, so it must be rewritten — as a grouping on `rate_distinct_count` plus the three
sign counts, which carries the same information for every case that query was reading. The blast radius
is still nil (the feed is unshipped and the columns are new), but "believed nothing
reads them" was wrong; something does.

**What is honestly lost.** The final rating stops being available as a *column*, so it can be looked up
per page but not filtered or sorted on. `rate_min`/`rate_max` could not express the final rating either,
so nothing that worked stops working — but this is a schema-cleanliness win, not a capability win, and
it is worth not overselling.

**Alternative rejected — keep them.** Two columns whose only purpose is to hint at a disagreement they
cannot resolve, next to a documented way to resolve it, is exactly the kind of near-duplicate that
makes a schema hard to read.

This is a **runtime catalog declaration change**, not code: a pipeline re-declaration plus an enrichment
schema patch and an evaluator version. No spec delta, because no requirement describes those columns —
and, for the same reason, no part of it is proven by `./gradlew clean build`. It is tracked as a
post-merge operational rollout, with its own ordering constraints (Migration Plan step 3).

### D6: The measure guard's javadoc changes even though its code does not

**Decision.** Edit `requireSingleColumnArity`'s javadoc in this change; change no logic.

It currently opens "**No aggregate in the catalog currently takes two required arguments**, so this is a
guard on future additions rather than a live restriction". After this change the catalog has exactly
one, the guard is live, and the sentence misleads the next reader into thinking the branch is dead —
the same branch this change adds a test for precisely because it stops being dead. Leaving stale
reasoning next to correct code is the cheapest kind of defect to introduce and the most expensive to
notice.

### D7: The array/object operand rule is stated per surface, because it differs per surface

**Decision.** The spec says a **projection-only** operand is rejected **on the SQL surface**, naming the
classification rather than the member types, and says nothing new about the DSL surface.

Checked rather than assumed, and the surfaces genuinely differ:

- **SQL** — `SqlProjectionOnlyTypes` classifies `array`, `object` and `map` as projection-only for
  *every* function, with a single positional carve-out for the `json_extract_*` JSON argument.
  `arg_max` is covered the moment it exists, with no new code. Naming the classification rather than
  its members is what keeps that true: `map` joined the set while this change was in flight (the otel
  sink added `QueryFieldType.MAP`), and an enumerated spec sentence would already have been stale.
- **DSL** — `ExprTranslator.toField` resolves a `FieldExpr` straight to its bound jOOQ field with no
  type check at all. `ArrayValuedAggregate.rejectAsOperand` rejects only a *nested `group_uniq_array`
  call*, not a declared array column. So `max(some_array_column)` is accepted by the DSL translator
  today and fails, if at all, in ClickHouse.

An earlier draft of this delta claimed the restriction held "for every other aggregate's operand" on
both surfaces. It does not. Writing that into the spec would either require new DSL-side enforcement —
scope creep, and a rule `min`/`max` would still not follow — or ship a requirement nothing implements.
Making `arg_max` stricter than `min` on one surface is worse than leaving the gap: it is an
inconsistency a caller cannot predict. The DSL-wide gap is real and worth closing, uniformly, in a
change that closes it for every aggregate.

## Risks / Trade-offs

- **A surface is missed and nothing notices.** Three separate registration points feed the SQL path
  (Calcite operator, `aggExpr`, `fnKind`) and each failure is surface-local: the DSL path can be
  perfect while `execute-sql` reports "No match found for function signature". → The live end-to-end
  test goes through the Flight/SQL path, which exercises all three; a DSL-only test would prove nothing
  about them.
- **`fnKind` typed from the wrong argument.** The neighbouring `min`/`max` case reads
  `args.get(args.size() - 1)`, which for `arg_max` is the `order`, not the `value` — a copy-paste that
  types the result column as a timestamp and would still return rows. → Called out in tasks; the
  round-trip test must assert the *result schema type*, not just the values.
- **A caller orders by a non-discriminating column and trusts the result.** → Specified as unspecified,
  and documented in the catalog entry's description, which schema discovery surfaces.
- **Someone tries `arg_max` in a pipeline measure and is confused by the rejection.** → The existing
  error names the two-argument signature and says to aggregate at query time instead. Verify that
  message actually appears (it is currently unreachable, since no two-arg aggregate exists) — this
  change makes previously dead code live, which is worth a test.
- **Removing `rate_min`/`rate_max` breaks a consumer.** → One does read them, in the plan doc (D5);
  rewrite that query in the same window. Re-confirm nothing else does before dropping rather than
  assuming.
- **`arg_max` is materialized somewhere later and re-aggregated.** Not possible through a measure today
  (D4), but the property should be written down before it is: the result is not re-aggregatable across
  stored rows.

## Migration Plan

1. Catalog entry, the `DSL.field` rendering, the Calcite operator, the two Rel-translator cases, and the
   javadoc edit — deployed together. Purely additive to the query surface: no migration, no config, no
   restart ordering constraint.
2. Verify `arg_max` **executes** against a live ClickHouse through the SQL/Flight path, not just renders.
3. Only then, the runtime cleanup: re-declare `response_ratings_rollup` without the `rate_min`/`rate_max`
   measures, patch `response_ratings` and `turn_feedback` to drop the columns, register `feedback-rollup`
   v2 without those output vars, and update the rule's bindings.

**Ordering matters in step 3** and follows the sequence this project already learned the hard way: a
target column that no measure writes fails every refresh *and* every preview, so the pipeline must be
disabled before the schema patch. Drop the measures and the columns in the same window, and re-enable
only once both are consistent.

**Rollback.** Step 1 is revertible by removing the catalog entry and its four call sites; nothing
persists. Step 3 is revertible by re-adding the columns and measures, at the cost of a cursor reset —
cheap on a table this small.

## Open Questions

- **Should `arg_min` come along?** Deferred (see Non-Goals). D0 settles the cost question that was
  blocking the decision: the rendering is a one-line template, so the mirror is nearly free, and the
  real cost is the permanent catalog surface rather than the plumbing. Revisit when something needs it.
- **Is anything besides the plan doc reading `turn_feedback.rate_min`/`rate_max` or
  `response_ratings.rate_min`/`rate_max`?** The plan doc's rating-distribution query is confirmed (D5); confirm the rest
  before step 3 rather than after.
