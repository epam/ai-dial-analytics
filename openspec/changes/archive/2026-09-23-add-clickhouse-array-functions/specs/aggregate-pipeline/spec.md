## ADDED Requirements

### Requirement: Array-consuming aggregates are query-time only

The measure vocabulary is derived from the **aggregate group of the shared query function catalog**,
so a new aggregate there widens it by default. The two array-consuming aggregates added for the
query surface — `groupUniqArrayArray` and `groupArrayArray` (see
[structured-query](../structured-query/spec.md), "Array-valued aggregates over an array field") —
SHALL be refused as a measure `fn` at declaration with HTTP 422, with a message that names the
function, states that a measure's input column must be a scalar, and points the author at
aggregating it at query time or deriving a scalar column on the input first.

The refusal is stated here rather than left to the existing array-measure rule on purpose. That rule
refuses an `array` input because "a set of arrays is not a declarable column type" — true for
`group_uniq_array`, and the wrong reason for a function whose whole purpose is to flatten the arrays
it consumes. A refusal carrying a reason that does not hold is worse than no message, because the
author's next move is to work around a constraint that is not the real one.

This keeps the property the vocabulary already has: a measure is **one function over one input
column**, and an aggregate whose operand is not a scalar cannot be expressed as one. It is the same
outcome `arg_max` already gets — in the catalog, available at query time, refused as a measure — by
the same argument.

The two gates are keyed differently, and the difference is worth knowing. `arg_max`'s is keyed on a
property the catalog descriptor carries — its required arity — so a two-operand aggregate added
later is refused without anyone remembering to. This one cannot be: the descriptor does not say what
*type* an operand takes, so the gate tests membership of the array-consuming set the query surface
already maintains. A third array-consuming aggregate is therefore covered only once it joins that
set, which is the same edit that makes the query surface accept it.

Lifting it later means deciding what a flattening measure's target `element_type` must equal (the
input's `element_type`, not its declared type, which is the array itself) and whether such a measure
is re-aggregatable across buckets. Both are answerable; neither is answered here.

#### Scenario: A flattening aggregate is refused as a measure

- **WHEN** an aggregate declaration declares a measure `fn: "groupUniqArrayArray"` over an `array`
  input column, bound to an `array` target column whose `element_type` matches the input's elements
- **THEN** the declaration is rejected with HTTP 422 naming the function and stating that a measure's
  input column must be a scalar, and no pipeline is stored

#### Scenario: The query surface is unaffected by the refusal

- **WHEN** the same aggregation is expressed as a query against the input entity
- **THEN** it executes normally, because the restriction is on materializing the measure, not on
  computing it
