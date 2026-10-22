## Why

A query that names a heavy enrichment column (the `dial_usage_log_payload` bodies) joins it as a derived table
holding that column for **every** row in the window, and ClickHouse builds that whole table into the hash join
before `ORDER BY`, `LIMIT`, the outer `WHERE` or the aggregation run. Bodies average tens of KB a row and a busy
deployment logs tens of thousands of rows a day, so one day can be several GiB against the 2 GiB `QUERY_BUDGET_MAX_MEMORY`. A cost sweep
found most body queries failing at that volume, including the
dominant "show me the last 10 requests with their bodies" shape at a 1-day window. An earlier fix, RC1 (keying by the source-only
conjuncts) already shipped; it bounds *which* window the join reads, but not *how much* of it is built.

## What Changes

Three independent rewrites of the single-entity read statement, all result-preserving byte for byte:

- **A. Late materialization of projection-only heavy columns.** A row-mode block with a `LIMIT` whose heavy
  enrichment columns appear only in its projection first picks its page's source keys, then joins each heavy
  enrichment for exactly those keys. The page's keys are one `IN` subquery referenced twice *in the same scope*
  (the outer `WHERE` and the heavy join's `ON`), which ClickHouse evaluates once — measured on 26.3.24.4 and 26.8.
  Representative probe: ~2 GiB → ~1 MiB, seconds → tens of ms.
- **B. Push null-rejecting enrichment-only conjuncts into the enrichment's derived table.** A top-level conjunct
  of a closed allow-list shape (`co`/`ico` with a non-empty needle, `eq`/`in` against non-default literals,
  `starts_with`/`contains` with a non-empty needle compared to `true`) that names one enrichment's text or numeric
  column is also evaluated inside that enrichment's derived table, so rejected rows never reach the hash table.
  It stays in the outer `WHERE`. Everything else (`IS NULL`, `ne`, `nc`/`inc`, `not`, `or`, `length(x) < n`,
  `coalesce`, document comparisons) stays outside only.
- **C. Push allow-listed scalar calls over heavy text columns into the derived table.** When a heavy text column is
  used only as the first argument of `length`, a `json_extract_*` function, or `starts_with`/`contains` with a
  non-empty literal, the derived table computes those calls and joins their light results; it no longer projects
  the raw column. The allow-list is limited to calls whose value on an unmatched row equals their result type's
  default, so an unmatched row reads exactly what it reads today.
- All three apply only to the single-entity read path (`POST /v1/queries/execute`, `execute-sql`, both CSV twins,
  Flight SQL). Composite (Calcite) blocks, `translate`/`translate-sql` rendering and every pipeline statement keep
  today's shape. `include_total` counts gain B and C (their filter is the only place they read a heavy column),
  never A.
- No new endpoint, config key, error code or catalog field.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `structured-query`: "SQL translation" no longer forbids moving an enrichment conjunct into a derived table (B
  copies allow-listed ones); new requirements for late materialization (A), enrichment-conjunct pushdown (B) and
  scalar-call pushdown (C); the "unbounded heavy-column read is refused" scenario is restated for the shapes A does
  not cover.
- `rest-sql-endpoint`: the heavy-body unbounded-scan scenario is restated — a projection-only body over a `LIMIT`
  is now bounded by the page, so the refusal example moves to a heavy column the page cannot bound (a filter or
  an aggregate over the body with no partition bound).

## Impact

- Code: `service/query/translate/StructuredQueryBuilder` (shape selection and the late-materialized statement),
  `service/query/EnrichmentJoins` (pushed conjuncts, pushed calls, the late join), `ExprTranslator` /
  `FilterTranslator` (per-statement substitution of pushed calls), a new package-private planner for the
  allow-lists.
- Surfaces: response bodies unchanged; statements that OOMed or timed out on body columns now complete.
- Docs: `docs/heavy-columns.md` gains a section on what a body read costs now; no `configuration.md` change.
- Out of scope: RC3 (object subcolumn projection on the read path), RC4 (identity schema), RC5 (composite
  pushdown), pipelines, and `CompositeQueryBuilder`.
