## Why

An aggregate refresh whose group key is an enrichment column hash-joins the **whole** enrichment table on every
run. `sessions_rollup` (grouped by `usage_client_identity.client_session_id`), for example, can build a join of
millions of rows and use well over a GiB on every scheduled run to write a handful of rows. Its memory is linear in
the enrichment's size, roughly a third of a GiB per million rows, and does not depend on the window:

| Rows joined | Memory |
|---|---|
| ~1 M | under 0.5 GiB |
| a few M | ~1 GiB |
| millions | approaching 2 GiB |

A deployment with an order of magnitude more sessions would need on the order of 2 GiB more RAM for every week of
history the refresh keeps. The consequences:
- **Up to 0.2.2**, the refresh write has no memory ceiling and can exhaust the ClickHouse node, surfacing as
  out-of-memory refusals on unrelated queries.
- **From 0.2.3**, it is refused at the 2 GiB pipeline budget on every tick once the enrichment passes a few million
  rows, and the rollup stops advancing. A deployment already near that size reaches it within weeks.

More RAM cannot keep up. The cost grows until `dial_usage_log`'s one-year TTL.

## What Changes

- **The refresh recompute restricts the grain's enrichment by the dirty keys.** When every group key reads a
  nullable column of one enrichment, the recompute's `INSERT … SELECT` restricts that enrichment, inside its
  grain-join derived table, to the rows whose group-key value is in the window's dirty-key set. Today the
  restriction is applied only after the join. With it, the hash join builds only the rows the dirty keys can
  reach. The restriction replaces the enrichment's keying, so the derived table needs no source predicates.
  Measured against a real window:

  | | Today | With the restriction |
  |---|---|---|
  | Result | a handful of rows | the same rows, byte for byte |
  | Join build | millions of rows | thousands of rows |
  | Memory | well over 1 GiB | a few hundred MiB |
  | Time | a few seconds | about a second |

- **`transform_null_in` is pinned to `0`** on every pipeline statement. Exactness rests on a NULL key never
  satisfying `IN`. That holds under the setting's default, checked on 26.3 and 26.8, but a deployment profile
  could change it.
- **The `aggregate-pipeline` spec is corrected.** It currently says the recompute's enrichment join "cannot be
  bounded … by construction", which is false for an enrichment grain. The keying rule and the cost guidance are
  restated per enrichment, and an existing imprecision is fixed: a recompute whose filter names an enrichment
  column is unkeyed, not "keyed on the filter alone".
- **Statements that do not change:**
  - the window probe, the dirty-key sub-select and the bucket bounds;
  - the preview sample, which has no dirty set; the preview's printed `statement` does show the restricted
    recompute;
  - grains mixing source and enrichment keys, grains over two enrichments, source-only grains, `array`
    enrichment keys, and any enrichment no group key reads. These compile byte-identical SQL.
- **No API, configuration, catalog or migration change.** The change is not breaking. Rollup values are
  unchanged, so no rebuild is needed.

## Capabilities

### New Capabilities

<!-- none -->

### Modified Capabilities

- `aggregate-pipeline`: two requirements change.
  - **"An aggregate pipeline reads its input entity, not only its input table"**: the recompute's grain join is
    restricted by the dirty keys when its grain reads one enrichment, and every pipeline statement pins
    `transform_null_in = 0`.
  - **"The cost of an enrichment-joined refresh is documented"**: the guidance now separates the enrichment a
    grain reads, whose join cost follows the window, from every other joined enrichment, which is still read in
    full unless a source-only filter keys it.

## Impact

- **Code:**
  - `service/pipeline/AggregateCompiler.compileRefresh`: derives the restriction for an enrichment grain and
    updates the comment at `:132-137` and the class javadoc.
  - `service/query/EnrichmentJoins.addReferenced`: the most general overload accepts an enrichment-side
    restriction. A restricted enrichment is emitted as a derived table carrying that restriction alone. The read
    path passes none, so its SQL is unchanged.
  - `clickhouse/FinalSettings.pipeline`: adds `transform_null_in = 0`.
- **Tests:**
  - compiler unit tests on a new keyable test model: one enrichment key, bucketed enrichment key, source filter
    beside the restriction, then mixed, source-only and `array` grains unchanged, and probe, dirty-key
    sub-select, bounds and preview sample unchanged;
  - an integration test comparing refresh results with and without the restriction over unmatched rows, NULL
    keys and superseded enrichment versions.
- **Operations:**
  - `sessions_rollup`, and any rollup whose grain reads one enrichment, stops growing that enrichment's join with
    its size.
  - Nothing to configure.
  - A deployment still needs a release that budgets the refresh write (≥ 0.2.3), which this change ships in.
- **Unchanged and out of scope** (tracked separately as follow-ups):
  - the full scan of the source an unbucketed grain still performs; it streams, so it costs time, not memory;
  - the cursor seek that prunes nothing (`CursorPredicates`), so every window-keyed statement reads the whole
    source; it is its own change;
  - mixed and two-enrichment grains, and source grains over an enrichment;
  - advisory locking across ADAS pods;
  - a per-user memory ceiling;
  - enrichment joins on the user read path.
