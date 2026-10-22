## 1. Declaration surface

- [x] 1.1 Add `private boolean distinct` to `service/pipeline/model/Measure.java`, defaulting to `false`, with a javadoc paragraph stating it deduplicates the input column's values before the function is applied and that it is a quantifier on the operand rather than an expression over it (so the one-function-over-one-column rule is untouched).
- [x] 1.2 Add `boolean distinct` to `MeasureRequest` in `web/dto/pipeline/PipelineRequests.java` with an `@Schema` description covering: what it does, that it is accepted only for `count`/`sum`/`avg`, that it requires a `column`, and that it composes with `where`. The record is shared by `PipelineDto`, so this covers the response echo too.
- [x] 1.3 Confirm `PipelineMapper`'s abstract `toMeasures`/`toMeasureDtos` carry the new field by name with no explicit `@Mapping` — MapStruct maps same-named properties automatically, so this is a verification step, not an edit. If it does not, add the mapping rather than hand-writing the mapper.

## 2. Compilation

- [x] 2.1 Widen `ExprTranslator.toAggregate(String, Field<?>)` to `toAggregate(String, Field<?>, boolean distinct)`, rendering `DSL.countDistinct`/`DSL.sumDistinct`/`DSL.avgDistinct` for `count`/`sum`/`avg` when the flag is set, and keeping `min`/`max`/`group_uniq_array` on their existing single rendering.
- [x] 2.2 Rewrite the `toAggregate` javadoc paragraph that states the quantifier is deliberately not a parameter because "a pipeline measure never sets it" — that premise is what this change retires. Keep and restate the property it protects: `toFunction`'s distinct branches still delegate here, so there is exactly one rendering per function reached two ways.
- [x] 2.3 Update `toFunction`'s `count`/`sum`/`avg` branches to delegate their distinct form to `toAggregate` as well, so the delegation claim in 2.2 is true of both forms rather than only the non-distinct one.
- [x] 2.4 In `AggregateCompiler.measureField`, replace the hardcoded `false` in the no-`where` path's `new FnExpr(measure.getFn(), false, args)` with `measure.isDistinct()`.
- [x] 2.5 In the same method's `where` path, pass `measure.isDistinct()` through to `toAggregate` so the operand becomes `count(DISTINCT CASE WHEN <pred> THEN <column> END)`, and extend the method's javadoc to record why that composition is correct without a special case: `count(DISTINCT x)` counts distinct non-NULL values, so a non-matching row contributes the `CASE`'s implicit NULL and is ignored.

## 3. Declaration-time gate

- [x] 3.1 In `AggregateSpecValidator`, reject `distinct: true` when the measure's resolved `QueryFunctionDto` reports `distinctSupported: false`, with HTTP 422 naming the function. Read it from the descriptor already in hand, mirroring how `requireSingleColumnArity` keys on the descriptor rather than on function names.
- [x] 3.2 Give `group_uniq_array` a specific rejection message stating the function already returns the group's distinct values, matching `ArrayValuedAggregate.rejectDistinct`'s wording so the two surfaces explain the same input the same way.
- [x] 3.3 Reject `distinct: true` with a blank/absent `column` (422), stating that a distinct count needs a column to deduplicate.
- [x] 3.4 Confirm no nullability change is needed: a distinct `count` of nothing is `0`, and a distinct `sum`/`avg` over an all-null group is already covered by the nullable-input rule. Leave `requireNullableTarget` untouched, and add no delta to "A nullable measure result requires a nullable target column".

## 4. Tests

- [x] 4.1 `AggregateCompilerTest`: assert the compiled SQL for a distinct measure without a `where` (`count(DISTINCT "trace_id")`), with a `where` (`count(DISTINCT CASE WHEN … THEN "trace_id" END)`), and for `sum`/`avg` distinct. Assert an omitted `distinct` renders byte-identically to the current expectation, since "changes no stored declaration" is the compatibility claim.
- [x] 4.2 `AggregateSpecValidatorTest`: cover each rejection from group 3 — `distinct` on `min`/`max`, on `group_uniq_array` (asserting the specific message), and `distinct` with no `column` — plus acceptance for `count`/`sum`/`avg` with a column, and acceptance of `distinct` combined with `where`. Checkstyle bans a test name whose second character is uppercase (no `aDistinct…`) and any two consecutive capitals; this fails `build`, not just the lint task.
- [x] 4.3 `AggregatePipelineIntegrationTest`: seed rows where the distinct count and the row count differ (several rows per key sharing a value), refresh, and assert the written column equals the known distinct count. This is the test that pins the `count_distinct_implementation` exactness risk from `design.md` — if the server setting ever makes `count(DISTINCT)` approximate, this fails rather than silently degrading a materialized column.
- [x] 4.4 Integration coverage for `distinct` + `where` together, including a group whose rows all fail the predicate, asserting it writes `0` and not NULL — the behaviour that keeps the target column non-nullable and so keeps the live fix a pipeline patch with no schema patch.
- [x] 4.5 Assert the round trip through the API: `POST /v1/pipelines` with `distinct: true` is stored and echoed by `GET`, and a rejected combination returns 422 with the existing `ErrorView` shape and error code (no new code is introduced).

## 5. Spec and docs

- [x] 5.1 Re-run `openspec validate measure-distinct-quantifier` after implementation and confirm the delta in `specs/aggregate-pipeline/spec.md` still matches shipped behaviour — in particular that the delta's corrected `where` rationale (a turn count is *not* one of its motivating cases) reflects what the code now does. The delta merges into `openspec/specs/aggregate-pipeline/spec.md` at archive; do not hand-edit the main spec.
- [x] 5.2 No `docs/configuration.md` change: this adds no configuration property. Confirm rather than assume — the new field lives in the pipeline's `spec` JSONB, not in `application.yml`.

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes (compile with `-Werror`, checkstyle, unit and integration tests; Docker must be running for the ClickHouse/PostgreSQL containers).

## 7. Post-deploy operator actions (not code — do not perform during apply)

- [x] 7.1 `PATCH /v1/pipelines/conversations_rollup`: set `turn_count` to `{fn: "count", column: "trace_id", distinct: true}` with no `where`, and `success_count` to `{fn: "count", column: "trace_id", distinct: true, where: "success = true"}`. Leave `chain_price_total` as declared. No `PATCH /v1/tables/conversations/schema` is required — both target columns stay non-nullable.
- [x] 7.2 Expect the re-declaration to reset the cursor and rebuild the rollup, re-reading the whole input; the `chat_id`-only grain has no time bucket to prune by, so keep the cron coarse while it drains and watch `state.has_more` and `state.last_error`.
- [x] 7.3 Verify against the recorded baseline: a reference chat's `turn_count` should equal its distinct trace count, and the count of conversations with `turn_count = 0` should fall to near zero.

Executed against a running deployment. Outcome: zero-turn conversations fell to zero; `sum(turn_count)` rose by
several-fold and equalled `count(DISTINCT trace_id)` over the chat-bearing input measured at the same moment;
`sum(success_count)` rose likewise; a reference chat with one hop per trace read `turn_count` equal to its trace
count, which also confirms `count_distinct_implementation` is exact. The drain took two refresh windows, not the
long rebuild 7.2 anticipated: the window probe applies the pipeline's own `filter`, so it read only the
chat-bearing rows, roughly a tenth of the input.

Done in the same re-declaration, beyond this change's scope: a `traces` column
(`array<string>`, non-nullable, `heavy`) on `conversations` plus a `group_uniq_array(trace_id)` measure, so a
conversation exposes its turn trace ids. Both had to land together — `AggregateSpecValidator` rejects a target
column no measure writes — and folding them in avoided a second full rebuild. `success_count` also changed
meaning as 7.1 specifies: it now counts traces with at least one successful hop (95,227 of 95,530), not
top-hop success.
