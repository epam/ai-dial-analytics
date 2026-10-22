## Why

ADAS can see that a user changed their mind about a rating, but not what they settled on.

`response_ratings` exposes `rate_min` and `rate_max`, so a response rated 👎 then 👍 reads
`min = -1, max = 1`. In real data **on the order of one rated response in ten** has disagreeing rate
events, usually one person flipping their own vote. For those the current schema can report only "the
events disagree" — the actual final answer is unrepresentable, because picking the value of the row
with the greatest `request_time` requires an aggregate the catalog does not have.

The closed function catalog is exactly `avg`, `count`, `group_uniq_array`, `max`, `min`, `sum`
(aggregate) plus two ordered-set percentiles. There is no `arg_max`, and a measure's `where` is a
static row predicate that cannot reference the group's own `max(request_time)`, so **"the value of the
latest row" has no expression anywhere in the query surface**. The same gap blocks every question of
that shape — the deployment of a turn's most expensive hop, the status of its last hop, the newest
comment on a response.

## What Changes

- **Add `arg_max(value, order)` to the closed function catalog** as a two-argument aggregate returning
  `value`'s type: the `value` of the group's row bearing the greatest `order`.
- **Accept it on every query surface** that resolves through the shared catalog — structured DSL
  execution, `execute-sql`, translation, schema discovery, and Flight — since all of them read the same
  catalog. On the SQL side this is not automatic: Calcite must be taught the operator, and the
  Rel→DSL translator must be taught the call and its result type (see *Code*).
- **Reject `distinct` on it**, because deduplicating values would detach a `value` from the `order`
  that selects it.
- **Define its null and tie behavior** explicitly: null `order` rows are ignored; an all-null `order`
  group, or a winning row with a null `value`, yields null; ties on the greatest `order` are
  unspecified, so `order` must be discriminating.
- **`rate_min` and `rate_max` become removable.** They are dead weight even today — for a
  single-rating response the `rate_pos_count`/`rate_zero_count`/`rate_neg_count` columns already carry
  the value, and for a conflicting one `rate_distinct_count > 1` already flags it while min/max only
  report the range. Their one job was to hint at disagreement they could not resolve. Dropping them
  from `response_ratings` and `turn_feedback` is a **runtime catalog declaration change, not code**,
  so it carries no spec delta — it is tracked as a post-merge rollout in tasks.md because this change
  is what makes it safe, and it is the one part of this change that is not finished by a green build.

Deliberately **not** in scope:

- **`arg_max` as an aggregate-pipeline measure.** A measure carries exactly one input column, and
  `AggregateSpecValidator.requireSingleColumnArity` refuses any aggregate with two required arguments
  — deliberately, and keyed on arity rather than a name list, so adding `arg_max` to the catalog is
  **safe by construction**: it becomes available at query time and is rejected as a measure with a
  clear message rather than silently breaking a pipeline. Making it declarable as a measure means
  widening the measure grammar, which reopens a documented design decision. See design.md; it belongs
  in its own change.
- **`arg_min`.** Now known to be nearly free (the rendering is a shared one-line template — see D0),
  but a catalog entry is permanent surface and nothing needs it yet. Deferred with the answer recorded
  so the next change does not have to re-derive it.
- Any change to `min`/`max`, or to how existing measures behave.

## Capabilities

### New Capabilities

None. This extends the shared function catalog that existing capabilities already define.

### Modified Capabilities

- `structured-query`: the *Supported function catalog* requirement's closed table gains `arg_max`,
  plus its operand, null, tie, and quantifier rules.
- `sql-query-translation`: the *Supported SQL subset maps onto StructuredQuery* requirement's
  1:1 aggregate list gains `arg_max`, so the SQL surface accepts the spelling the DSL→SQL renderer
  emits.
- `query-schema-discovery`: the *Supported function catalog discovery* requirement's advertised
  function list gains `arg_max` with its two-argument signature.

`query-translation-endpoint` needs **no** delta: `arg_max` renders as an ordinary two-argument call, so
the existing rule that every DSL catalog name is an accepted SQL spelling already covers the
render/translate round-trip. Unlike the percentiles, it needs no special rendered shape.

## Impact

**Surfaces**

- One new accepted function name across `/v1/queries/execute`, `/v1/queries/execute-sql`,
  `/v1/queries/translate`, `/v1/queries/translate-sql`, schema discovery, and Flight SQL. No endpoint
  contract, request shape, or error-code change; an unknown function is still a 400.
- No configuration keys, no new credentials.

**Code** — five files, one of which the first scoping of this change missed entirely:

- `QueryFunctionCatalog` — one entry, in the `AGGREGATE` group with two required expression args,
  placed between `abs` and `avg` (the list is globally alphabetical and a test asserts it).
- `ExprTranslator.toFunction` — a new branch rendering
  `DSL.field("argMax({0}, {1})", Object.class, value, order)`, and an explicit `distinct` rejection
  (the branch does not go through `toAggregate`, which is where the other aggregates' quantifier
  handling lives).
- `SqlQueryOperators` — **register an `ARG_MAX` `SqlAggFunction`.** Without it Calcite validation fails
  with "No match found for function signature" and the SQL surface never reaches the translator. This
  is the same treatment `GROUP_UNIQ_ARRAY` needed, for the same reason: it is not a standard operator.
- `RelToStructuredQueryTranslator` — two edits, not one: an `ARG_MAX` case in `aggExpr` (arity 2,
  `DISTINCT` rejected there because `SqlBasicAggFunction.withDistinct` is not public API), and an
  `arg_max` case in `fnKind` returning the type of **argument 0**. The neighbouring `min`/`max` case
  reads `args.get(args.size() - 1)`, which is the same thing at arity 1 and the wrong thing here.
- `AggregateSpecValidator` — **no behavior change, but a required javadoc edit.**
  `requireSingleColumnArity` currently asserts "*No aggregate in the catalog currently takes two
  required arguments, so this is a guard on future additions rather than a live restriction*". This
  change makes that sentence false and the guard live.

**Consumers**

- `response_ratings`/`turn_feedback` can drop `rate_min`/`rate_max`; the final rating is then a
  query-time aggregate over `rate_analytics`, keyed on a `response_id` the feed already returns.
- Two practical constraints on that second query, worth stating because they change the client's shape:
  it must run on the **DSL** surface, since `execute-sql` routes through Calcite, whose default
  `inSubQueryThreshold` of 20 rewrites an `IN` list of 20+ literals into a sub-query join the translator
  refuses — a 50-row feed page would need three `execute-sql` calls but one `/v1/queries/execute` call.
  And the final rating stops being a **column**, so it cannot be filtered or sorted on within the feed;
  it is a per-page lookup, not a predicate.
- Nothing existing changes behavior. The catalog only grows.

**Risk**

Lower than first scoped, and it has moved. The original worry — that jOOQ's *experimental* ClickHouse
dialect might not render `argMax` — does not apply: nothing asks jOOQ to know the function.
`group_uniq_array` is already rendered by a hand-built `DSL.field("arraySort(groupUniqArray({0}))", …)`
template, and `AggregateRefreshService` builds `argMax` the same way for its cursor arithmetic. The
rendering is a literal SQL string, deterministic in the way `date_bin`'s `toStartOfInterval` is, and
explicitly **not** covered by the spec's experimental-dialect caveat (see D0).

What remains: the SQL-surface plumbing is three separate registration points (Calcite operator, agg
translation, result typing) and missing any one of them fails only on that surface, so the end-to-end
live test is the thing that actually proves the change. Semantics are the second risk: `arg_max` is
**not re-aggregatable** across stored rows (like `min`/`max` and a `distinct` count), so if it is ever
materialized, summing or re-aggregating it across buckets would be silently wrong — which is part of
why the measure path is deferred rather than bundled.

**Sequencing**

This should land **after** `preserve-raw-rate-value`, and the two compose — but not by simply
substituting one column for the other, which is worth stating because it is the obvious wrong move.

`arg_max(rate, request_time)` returns the latest **normalized** value, for all of history, and cannot
say whether a final `0` was a thumbs-down or a clearing. `arg_max(rate_raw, request_time)` returns the
latest **submitted form**, which resolves that ambiguity — but only for events ingested after
`rate_raw` exists; for a response whose latest event predates the column, the winning row's `rate_raw`
is null and so is the result. So the two are read together, not swapped: `rate` for coverage,
`rate_raw` for certainty, with `rate_raw IS NULL` naming the population where the second question has
no answer. (`arg_max` returns its `value` operand's type, so a string operand is fine either way.)

Together they answer "N thumbs-up, M thumbs-down, and here is what each response settled on"; this
change alone answers only the last part, in a currency that does not distinguish the first two.
