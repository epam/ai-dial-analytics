## Why

An aggregate pipeline measure cannot express `count(distinct <column>)`, so a rollup whose grain is
coarser than its input's row grain has no way to count the *entities* in a group — only the rows. The
`conversations` rollup is the live casualty: its input `dial_usage_log` is one row per hop, a
conversation turn is one `trace_id` (which may span many hops), and neither available shape is correct.
A plain `count` counts hops, inflating an agent-mediated conversation by its fan-out factor
(a deep-research application can fan out to dozens of hops per turn). The `where
parent_deployment IS NULL` predicate now on that pipeline was added to avoid exactly that inflation,
but it selects rows the pipeline's own `filter: "length(chat_id) > 0"` has already discarded — the
chain-starting hop of a router-mediated trace carries no `chat_id`, because the conversation id first
appears on the hop the router itself makes. The two clauses have an empty intersection, and the result
is a silent zero:

- **close to half** of all conversations read `turn_count = 0` and `success_count = 0`
- a chat with hundreds of real turns reads `turn_count = 0`

The zero is faithful to the declaration — `aggregate-pipeline` deliberately reports a scoped `count`
of nothing as `0` — so this is not an engine defect. It is a measure vocabulary that cannot say what
the declaration means. "One turn per `trace_id`" is a distinct count, and no row-membership predicate
can express it: "the topmost `chat_id`-bearing hop of this trace" is a per-group property, not a
per-row one.

The gap is narrow and already half-closed. The shared query function catalog advertises
`distinct_supported: true` for `count`, `sum` and `avg`; the query DSL and the SQL front end both
accept the quantifier; `ExprTranslator` already renders it. Only the pipeline measure declaration
cannot carry it — `AggregateCompiler` hardcodes the flag to `false`. Closing it makes the measure
vocabulary match the catalog metadata that describes it, which is the invariant the two surfaces are
supposed to hold to.

## What Changes

- A measure MAY declare `distinct: true`, deduplicating the values of its input column before the
  function is applied. Absent and `false` are the current behaviour, so this is **not breaking** —
  every stored declaration keeps rendering exactly as it does today.
- `distinct` is accepted only where the catalog says `distinct_supported: true` (`count`, `sum`,
  `avg`) and rejected at declaration otherwise (`min`, `max`, `group_uniq_array`, the two
  percentiles), so the pipeline surface and the query surface agree on the same closed metadata rather
  than each deciding for itself.
- `distinct` composes with a measure's `where`: the predicate chooses the rows, `distinct` deduplicates
  the values on those rows. This is the combination `success_count` needs, and it is the one shape that
  requires widening an internal signature rather than only passing a flag through.
- `distinct: true` on a `count` SHALL require a `column`. `count` with no column is a row count, and
  "distinct rows" names no value to deduplicate.
- The `count` nullability exemption is unchanged and explicitly survives `distinct`: a distinct count of
  nothing is `0`, not NULL, so `turn_count` and `success_count` need no target-column change. Fixing
  the live rollup is a pipeline patch alone — no schema patch, no migration.

Out of scope, and called out so the change is not mistaken for a full fix of the `conversations`
table:

- **`chain_price_total` stays broken and is not fixable here.** The router's chain-starting hop carries
  `total_price = NULL` alongside its empty `chat_id`, so a top-down chain cost does not exist in the
  source for those traces. No measure vocabulary recovers it.
- Re-declaring the live `conversations_rollup` measures is an operator action against `/v1/pipelines`,
  not repository code. It is recorded as a post-deploy step, not implemented by this change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: the measure vocabulary gains an optional `distinct` quantifier; the
  declaration-time gate resolves its acceptability against the shared catalog's `distinct_supported`
  metadata, requires a `column` when a `count` declares it, and the existing `count`/`group_uniq_array`
  nullability exemptions are stated to survive it.

## Impact

**Code**

- `service/pipeline/model/Measure.java` — new `distinct` field, defaulting to `false`.
- `service/pipeline/AggregateCompiler.java` — `measureField` stops hardcoding the quantifier to
  `false` on the no-`where` path, and carries it into the `where` path's prepared operand.
- `service/query/translate/ExprTranslator.java` — `toAggregate(String, Field)` gains the quantifier so
  the prepared-operand path can render a distinct aggregate. Its javadoc currently states the
  quantifier is deliberately not a parameter because "a pipeline measure never sets it"; that premise
  is what this change retires, and the note must be rewritten rather than left contradicting the code.
  The "exactly one rendering per function" property is preserved by keeping `toFunction`'s distinct
  branches delegating here.
- `service/pipeline/AggregateSpecValidator.java` — the new declaration-time rejections.

**API**

- `POST /v1/pipelines` and `PATCH /v1/pipelines/{name}` accept `distinct` on a `measures` entry;
  `GET` echoes it. Additive and optional — no client change is required. Rejections use the existing
  `PipelineValidationException` → 422 path, so `ErrorView` gains no new error code.

**Not affected**

- The function catalog, the query DSL, the SQL front end and Flight SQL. `distinct` already works
  there; this change adds no function and changes no signature they read.
- ClickHouse and PostgreSQL schema. A measure lives in the pipeline's `spec` JSONB, so there is no
  migration on either side.
