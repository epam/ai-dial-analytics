## 1. Part 1: the query surface declares its object-path usage (TDD)

- [x] 1.1 Render tests in the `EnrichmentJoinRenderTest` / `ReadShapingRenderTest` style:
  - a select, filter or group_by `json_extract_string(<object>, 'k')` over a keyed join projects one aliased
    subcolumn, and the outer statement addresses the alias;
  - the column stays whole when it is also projected bare or sorted or grouped by name;
  - a pushed conjunct's derived-table `WHERE` copy keeps the real column while the outer copy uses the alias;
  - a pushed heavy call is unaffected;
  - the count statement prunes as its filter reads;
  - the late-materialized page block and outer statement prune their light joins;
  - the aggregate pipeline (`build(…, LeafRestriction)`) still projects whole columns.
- [x] 1.2 `StructuredQueryBuilder.render`:
  - add a `declarePaths` flag;
  - collect an `ObjectPathUsage` from select, filter, having (`collectObjectPaths`) and sort and group_by
    (`markWholeObjects`);
  - pass it to `addReferenced`;
  - re-translate the outer filter and render select, group, having and sort against the returned model;
  - keep the key predicates translated against the original model.
- [x] 1.3 `StructuredQueryBuilder.count`: the same, with the usage from the filter.
- [x] 1.4 Wire `declarePaths`:
  - `true` from the shaped and unshaped single-entity read, the page block, the late outer statement, the count
    and the composite `build(…, LimitPolicy)` overload;
  - `false` from the `build(…, LeafRestriction)` overload (aggregate refresh and preview).
- [x] 1.5 ClickHouse integration test (as `ReadShapingIntegrationTest`), comparing pruning on vs off
  byte-for-byte for:
  - unmatched source rows;
  - missing paths;
  - multiple unmerged payload versions;
  - a heavy object under `json_extract_*` in select, filter and group_by;
  - a count with `include_total`.
- [x] 1.6 Update the statement-text snapshots that change. Each change is only an object path now addressing its
  alias.

## 2. Part 2: composite join operand pushdown and semi-join reduction (TDD)

- [x] 2.1 Render tests (`CompositeQueryRenderTest` style) for INNER, LEFT, RIGHT, FULL and CROSS, with predicates
  on either side:
  - where each conjunct lands, and where it must not;
  - the renamed right-side columns (`trace_id0` becomes `trace_id`);
  - conjuncts over both sides, or with a subquery or lambda, stay above the join;
  - nested joins are left alone.
- [x] 2.2 Render tests for the reduction:
  - its shape `k IN (SELECT k FROM <restricted source> WHERE <moved source-only conjuncts>)`;
  - it keys the reduced operand's enrichment joins;
  - it is absent for a preserved side, for FULL and CROSS, for an enrichment-only restriction, and for a key on an
    enrichment column;
  - the INNER both-restricted case reduces each side by the other's own conjuncts only;
  - the j5 shape renders as measured.
- [x] 2.3 `StructuredQueryBuilder`: a package-private build overload taking `keyConjuncts`. They are ANDed into
  `WHERE` and appended to `keyPredicates`, with `declarePaths = true`.
- [x] 2.4 `CompositeQueryBuilder`:
  - split the join block's filter into conjuncts;
  - map them to operands, then apply the move rules (D3, D5) and rename the fields;
  - render the moved conjuncts as the operand leaf's filter and the rest above the join;
  - compute the outer referenced set from the remaining filter;
  - build the reduction (D4) and hand it to the reduced leaf.
- [x] 2.5 ClickHouse integration test, composite path under `join_use_nulls=1`. Each case is compared byte-for-byte
  against the same plan rendered with pushdown and reduction switched off:
  - INNER, LEFT, RIGHT and FULL;
  - NULL keys;
  - duplicate keys on the restricted side;
  - an outer join whose preserved side has a partnerless row;
  - the j5 self-join shape with an identity enrichment column.

## 3. Specs and verification

- [x] 3.1 Keep the delta specs (`structured-query`, `rest-sql-endpoint`) in step with the shipped behaviour; new
  requirements go before `## Implementation notes`.
- [x] 3.2 `./gradlew clean build` passes.
- [x] 3.3 After deploy, rerun the acceptance corpus (plus x1–x4 through `execute-sql`) against a running
  deployment. p3 and j5 must pass; p4 is expected to stay refused by the budget.
