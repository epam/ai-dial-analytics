## Why

A row page that projects a **light** column of an aligned enrichment — for example
`SELECT request_time, "dial_usage_log_payload.request_tags", trace_id FROM dial_usage_log WHERE request_time >= T
ORDER BY request_time DESC LIMIT 25` — builds that enrichment's join over the whole window, because late
materialization only fires when the block references a `heavy` column. The window transfer and partition
alignment bound the join to the window's partitions, but ClickHouse still hashes every in-window payload row's
`request_tags` document to return 25 rows. On a deployment with enough traffic that build exceeds the
`query.budget.*` memory ceiling and the caller gets `422 query_budget_exceeded` for a 25-row page. The cost of a
projection-only enrichment column grows with the window, not the page, whether or not the column is heavy.

Marking `request_tags` heavy would make this one query page-bounded, but `heavy` also removes a column from
partition pre-selection, disqualifies late materialization whenever the column is filtered on, and splits pipeline
group fetches per column, which makes header filters and conversation-id lookups more expensive. The cost being
removed comes from the join's position (projection-only on an aligned enrichment), not from the column's size.

## What Changes

- Late-materialization eligibility gains a second way in: a block also qualifies when it references a
  **partition-aligned** enrichment whose every referenced column appears only in `select` entries (none in
  `filter`, nor sorted directly or through a `select` output name), whether or not any of those columns is heavy.
  Such an enrichment is joined in the outer statement for the page's keys only, exactly like a heavy enrichment
  today, and the page block does not join it.
- The existing heavy rule is unchanged: a block that references a heavy column outside the projection is still
  not late-materialized, and every condition on mode, `LIMIT`, `distinct`, window/`array_join` expressions,
  non-deterministic calls and the reserved `__adas_lm_*` names still applies.
- A light enrichment does not disqualify a block, except under the guard below: one that is not aligned, is
  referenced outside the projection, or is keyed on a different source grain key than the block's key join simply
  keeps the join it has today.
- One guard: a block with only light late enrichments is not late-materialized when its filter qualifies for a
  partition pre-selection and it also joins an enrichment not on the key join's grain key and partition column
  (an unaligned one, or an aligned one on another grain key). The outer statement carries no pre-selection and the
  page keys cannot reach that join, so it would lose the bound it has today. The pre-selection already bounds such a
  block's light join to the lookup's partitions.
- A page-keyed join stays the bare enrichment table, and an `object` path it is read through is a subcolumn of
  that table. This is now stated normatively, because wrapping the join in a derived table would re-evaluate the
  page keys.
- No API, configuration, catalog or migration change. Results are unchanged by construction (same rows,
  columns, values and NULL/default semantics up to ties on the sort). Only the rendered SQL and its cost change,
  including for some blocks that late-materialize today: a heavy page that also projects light columns of
  another aligned enrichment on the same keys now page-keys that enrichment too.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query`:
  - "Projection-only heavy enrichment columns are fetched for the returned page only" is renamed "Projection-only
    enrichment columns are fetched for the returned page only". It widens its eligibility to projection-only
    columns of partition-aligned enrichments, heavy or not, makes the bare-table late join and subcolumn path reads
    normative, and gains scenarios for the light-only page, header paths, the mixed heavy + light page, and the
    light enrichments that keep today's join (filtered, sorted, unaligned, other grain key, the pre-selection
    guard).
  - "SQL translation" says a late-materialized page joins its late enrichments, not only its heavy ones, and cites
    the renamed requirement.
  - "Query execution runs under a resource budget" cites the renamed requirement. There is no behaviour change in
    this one.
  - "A keyed grain join is bounded by an aligned partition column" and "A partition-only key on an aligned
    enrichment is transferred, not keyed" have scenarios whose row read projects an aligned enrichment column only,
    which the widened rule now late-materializes. Those scenarios now sort on the column, so they keep describing a
    derived table. The transfer requirement gains the page-keyed case, and the keyed-join requirement says that a
    page-keyed join has no derived table to prune.
  - "A lookup on an aligned enrichment pre-selects the source's partitions" says the page's keys reach only the joins
    on the key join's grain key and partition column, and its session-lookup scenario names a block that is not
    late-materialized.
- `rest-sql-endpoint`: the "SQL execution inherits the query budget" requirement describes the read shaping it
  inherits as late materialization of projection-only enrichment columns (no longer heavy columns alone), with a
  scenario for the light-column page.

## Impact

- **Code**: `ReadShaping.latePlan` (eligibility and the set of late tables). `StructuredQueryBuilder.shaped` and
  `EnrichmentJoins` render late tables already, so they should need no structural change. The Javadoc and comments
  that say "heavy" where they now mean "late" also change, and `docs/heavy-columns.md` gains a row for projection-only
  payload documents.
- **Surfaces**: `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`, their CSV twins and the Flight SQL
  endpoint, the single-entity read path that already late-materializes. Translation-only endpoints, composite
  SQL blocks and pipeline statements are untouched.
- **Affected queries**: row-mode reads that project light columns of an aligned enrichment. Every row read on these
  surfaces renders a `LIMIT`, the default one when the caller sends no `page`, so this is all of them, paged or
  not. That is
  `dial_usage_log_payload` (`request_tags`, `jwt_claims`), plus any aligned user enrichment, such as
  `usage_client_identity` on a deployment that laid it out aligned. Pages projecting identity columns like
  `user_email` change SQL shape on such deployments.
- **Cost profile**: a newly eligible block adds a page block that re-runs the filter, re-joins whatever the filter
  and sort read, and on a collapsing source adds a second `FINAL` pass over the window. In exchange, the
  deferred enrichment's window-sized hash build goes away. The trade is expected to be favourable for row-grain
  enrichments over windows much larger than the page. It could invert for a cheap deferred join under an expensive
  filter. There is no eligibility guard (design D6). Acceptance measures the inverting shapes on a running
  deployment before merge, and a regression adds a guard to this change.
- **Tests**: `ReadShapingRenderTest` (with a third, aligned test enrichment), `ReadShapingIntegrationTest`
  (unshaped-equivalence against ClickHouse, read final), `QueryPathPruningRenderTest` (subcolumn paths on a
  page-keyed join), `WindowTransferRenderTest` (three tests gain a sort so they keep their subject, plus the
  page-keyed case), `PartitionPreselectionRenderTest` (the guard's case), `AlignedEnrichmentIntegrationTest` and
  `QueryPathPruningIntegrationTest` (three tests gain a sort so they keep their subject), `SqlReadShapingRenderTest`
  (the SQL-surface header page).
