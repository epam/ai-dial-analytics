## Why

"Align row grain enrichments" partitioned `usage_client_identity` by day and joined every aligned enrichment on
`(event_id, request_time)`. A keyed aligned join now builds **two** `IN` sets from the source predicates: one over
`event_id` and one over `request_time`. Over a month of history each holds millions of values. The `event_id` set
prunes nothing on a table sorted by a random key, and the `request_time` set restates a window ClickHouse could
apply as a plain range.

Rerunning a corpus of enrichment queries back to back against the previous SQL shows the cost:
- a CTE of per-user totals joined back over a month, and an all-time identity read, now exceed the memory budget;
- a per-day body-length aggregate over a week times out;
- the month-long identity and payload aggregates use noticeably more memory than before.

## What Changes

- On the query surface, a keyed join to a **partition-aligned** enrichment no longer builds its key sets when
  every source predicate that keys it is a bound on the source's partition column alone. The usual case is a time
  window. The derived table is then restricted by the same bounds, rendered on the enrichment's own partition
  column, beside any enrichment conjuncts pushed into it. Back to back, with identical rows:
  - a `user_email = X` read or a `jwt_claims` email filter over a month uses about an order of magnitude less time
    and memory;
  - the CTE of per-user totals fits the memory budget it exceeded;
  - joins and grouped reads over a month, and the week-long body-length aggregate, run several times faster.
- Any other source predicate beside the bounds (an equality on `trace_id`, `event_id`, `chat_id`, a
  `deployment` filter, a composite reduction) keeps today's two key sets. Point lookups keep their one-granule
  primary-key pruning and run unchanged.
- Unchanged: the unaligned joins, the bare (unkeyed) joins, the late-materialization page keys, the partition
  pre-selection, and every pipeline statement.
- No endpoint, response, `ErrorView` or configuration change.

Not in this change:
- The `use_index_for_in_with_subqueries_max_values` cap: faster on the remaining large sets, but it fixes neither
  of the two reads that exceed the memory budget.
- Dropping the grain-key set beside a pushed conjunct when the filter is mixed.
- Joining aligned enrichments on the grain key alone. That would fix the all-time identity read, an unkeyed read,
  and save memory, but the tuple join is what carries the partition pre-selection into the enrichment.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: a new requirement for transferring partition-column bounds onto an aligned enrichment's
  derived table in place of its key sets. Three existing requirements are modified to point at the new one:
  "A keyed grain join is bounded by an aligned partition column", "SQL translation" (its keying paragraph and
  two scenarios), and "Null-rejecting enrichment-only conjuncts narrow the enrichment's derived table" (its
  body-search scenario).

## Impact

- Code: `StructuredQueryBuilder` (row, page-block and count statements, and so composite leaves),
  `EnrichmentJoins` (a per-join replacement for its key restrictions), reusing `ReadShaping.partitionBounds`.
- Surfaces: `POST /v1/queries/execute`, `execute-sql`, their CSV twins and Flight SQL render a different SQL for
  window-only reads of an aligned enrichment. Results are unchanged.
- Acceptance: rerun the enrichment query corpus and compare the changed SQL with the previous build's back to back.
