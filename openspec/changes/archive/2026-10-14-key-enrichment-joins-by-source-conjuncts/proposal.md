## Why

On the query surface, enrichment-join keying is all-or-nothing. When a query's `filter` names any enrichment
column, no join in the statement is keyed. Every referenced enrichment is then emitted as a bare
`LEFT JOIN <table> FINAL`, and the time window never reaches the enrichment side. ClickHouse hash-builds the
whole enrichment table, which for `dial_usage_log_payload` can reach hundreds of GiB. A cost sweep
measured this as the cause of
timeouts and OOMs on 1-hour windows and single-trace lookups. A filter on identity or baggage un-keys the
*payload* join, and the hand-keyed equivalents run in under a second and a few hundred MiB. Queries that
timed out after tens of seconds, or ran out of memory, dropped to under a second once keyed.

## What Changes

- A query's enrichment joins are keyed by the **source-only top-level conjuncts** of its `filter`. The filter's
  top-level `and` is flattened, recursing into nested `and`s only. Each conjunct whose referenced fields all
  belong to the source becomes a key predicate. Before this change, keying needed the whole filter to be
  source-only.
- The full filter stays in the statement's outer `WHERE`, unchanged. No enrichment predicate moves into a
  derived table.
- The grain-key (`IN`) and aligned-partition (`request_time IN`) restrictions are both built from those
  conjuncts. A filter on enrichment A therefore keys enrichment B's join as well.
- The `include_total` count statement follows the same rule.
- The change reaches every surface that renders through the structured-query builder: `execute`,
  `execute-sql`, both CSV twins, Flight SQL, and the leaf blocks of composite SQL.
- Unchanged: a filter whose conjuncts are all source-only renders exactly as before. A filter with no
  source-only top-level conjunct, such as an enrichment-only filter or an `or` mixing source and enrichment
  columns, is still joined unkeyed.
- Not in scope: pushing enrichment predicates into the derived table (RC2), subcolumn projection (RC3), the
  identity schema (RC4), composite pushdown (RC5), and the pipeline-side keying rules (aggregate compiler,
  materialize, scan, group fetch). Those already key on their window/cursor predicates.

No `/v1` contract, catalog schema, or configuration key changes. Results are unchanged, and only the cost of
the join build drops.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query`: the "SQL translation" requirement's keying rule changes from "keyed when the filter names
  source columns only" to "keyed by the filter's source-only top-level conjuncts". It gains scenarios for a mixed
  `and`, an enrichment-only filter, a mixed `or`, and an enrichment `IS NULL` conjunct beside a time window.

`rest-sql-endpoint` does not restate the keying rule. It inherits the rule from `structured-query`, so it gets no
delta.

## Impact

- Code: `StructuredQueryBuilder` (`keyPredicates`, plus a new DSL-level conjunct splitter). `EnrichmentJoins`
  is unchanged.
- Tests: render tests in `EnrichmentJoinRenderTest`, and a ClickHouse integration test showing that keyed and
  unkeyed results are identical.
- Ops: queries that used to time out or OOM in the join build now complete. No migration and no rollback
  concern beyond reverting the code.
