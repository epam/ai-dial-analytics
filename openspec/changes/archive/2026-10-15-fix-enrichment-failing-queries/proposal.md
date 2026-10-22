## Why

After RC1 (keyed enrichment joins by source-only conjuncts) and RC2 (late materialization and read shaping), a
few queries of an enrichment-cost query corpus still fail (all numbers measured on ClickHouse 26.3 under the ADAS
budget of 2 GiB / 20 s). The causes are independent:

- **p3** (`json_extract_string(jwt_claims,'email')` grouped over 30 d) OOMs because the query surface renders its
  keyed enrichment derived tables with the whole `object` column. Path pruning exists, but only the Class-1
  materialize path declares its path usage.
- **j5** (a parent-span self-join with only the child side bounded) OOMs because a composite join's entity
  operands are rendered with no `WHERE` of their own. The time window stays on the outer block, so the unbounded
  operand reads the whole usage log and hashes the whole identity table.
- **p4** (`request_tags ILIKE '%claude%'`, all-time) times out because ClickHouse serializes millions of JSON documents
  (`toJSONString`) to evaluate the canonical-text match. The join is not the cost. This change leaves p4 as a
  budget refusal (see What Changes).

## What Changes

- **Part 1: query surface declares its object-path usage.** The single-entity read statement, its count statement
  and every composite block derive which `object` paths they address (select, filter, sort, group_by, having and
  the pushed-down conjuncts and calls). Their keyed or shaped enrichment derived tables then project only those
  subcolumns. A column that is referenced whole anywhere stays whole. The aggregate refresh, the enrichment scan and
  the group-fetch projection keep declaring nothing. Measured: p3 goes from OOM to about a second and under 1 GiB,
  and the `jwt_claims` email filter at 30 d roughly halves in both time and memory. This also removes most of the
  RC1 × RC3 regression on x1–x4.
- **Part 2a: predicate pushdown into composite join operands.** A top-level conjunct of a join block's `WHERE` that
  reads only one entity operand's columns moves into that operand's block. There it keys the operand's
  enrichment joins through the RC1 conjunct split. This is allowed into both sides of an INNER or CROSS join and
  into the preserved side of an outer join only (LEFT's left, RIGHT's right, neither side of a FULL join).
- **Part 2b: semi-join reduction across an equi-join.** When one entity operand carries pushed source-only
  conjuncts and the join has a column-to-column equality, the other entity operand is restricted to
  `other.k IN (SELECT k FROM <restricted source> WHERE <its pushed source-only conjuncts>)`. That restriction also
  keys the other operand's enrichment joins. It is allowed only into a side whose unmatched rows the join discards
  anyway (either side of INNER, the null-supplying side of an outer join) and goes one hop only. Measured: j5 goes
  from OOM to well under a second and a few hundred MiB, with rows equal to an independent reference.
- **Part 3 (p4) is not fixed, by decision.**
  - The cost is the document-wide substring search itself.
  - Text copies of a column are not wanted, nor is an up-front refusal.
  - A caller should address the exact path or bound the window. p4 keeps returning the budget's
    `query_budget_exceeded` at 20 s.
  - The measurement that informed the decision is in design.md D7.
- No `/v1` endpoint, request shape, response shape or config key changes. Results are identical to today's for every
  query that passes today: each rewrite is proved row-equivalent by ClickHouse integration tests that run the
  rewritten and the original statement side by side.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `structured-query`:
  - "A keyed grain join is bounded by an aligned partition column": the query surface now declares its object-path
    usage, so its derived tables project addressed subcolumns.
  - New requirements for operand predicate pushdown and semi-join reduction in composite joins.
- `rest-sql-endpoint`:
  - "SQL execution inherits the query budget": a new scenario for the bounded-child self-join.

## Impact

- Code:
  - `service/query/translate/StructuredQueryBuilder` (usage collection, rendering against the pruned model, leaf
    key conjuncts);
  - `service/query/translate/CompositeQueryBuilder` (operand pushdown and reduction);
- Data: none. There is no migration and no config key.
- Rollout: acceptance after deploy is rerunning the query corpus plus x1–x4 through `execute-sql` against a
  running deployment and comparing with the pre-change numbers. The harness is not in the repo.
- Out of scope: the remaining cost regressions around the i6 identity keying, which are a later change.
