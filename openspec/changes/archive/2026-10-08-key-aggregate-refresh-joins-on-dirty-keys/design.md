## Context

A refresh composes several statements over its input (`AggregateCompiler`):
- the window probe;
- the dirty-key sub-select (`dirtyKeys`, `:301-327`), a `DISTINCT` of the group keys over exactly the window;
- the bucket bounds, for a time-bucketed grain;
- the recompute `INSERT … SELECT` (`compileRefresh`, `:120-168`).

Every statement that references an enrichment carries its grain join through `EnrichmentJoins.addReferenced`.
A join is *keyed* only when the caller hands over source-only predicates. Keyed means it is emitted as a
derived table restricted to `grain_key IN (SELECT grain_key FROM source WHERE <predicates>)`:
- the probe, the dirty-key sub-select and the bounds pass the window predicates;
- the recompute passes `keyPredicates(model, filter, List.of())`: the membership filter, and only when it names no
  enrichment column. Otherwise it passes nothing, and the join is bare.

When the grain or the filter reads an enrichment column, the recompute's join is therefore bare. It is
`LEFT JOIN enrichment` under `final = 1`, a hash join built from the whole enrichment. The dirty-key tuple `IN`
is applied after the join, in the outer `WHERE`.

`sessions_rollup` is exactly that shape. It is grouped by `usage_client_identity.client_session_id` and filtered
on `length(client_session_id) > 0`. Measured on ClickHouse 26.3 with the settings ADAS sends (`final=1`,
`do_not_merge_across_partitions_select_final=1`, `max_memory_usage=2147483648`):

| Variant | Join build rows | Memory | Time | Result |
|---|---|---|---|---|
| Today, ¼ of the enrichment | ~1 M | under 0.5 GiB | ~1 s | — |
| Today, ½ of the enrichment | a few M | ~1 GiB | ~1.5 s | — |
| Today, all of it | millions | approaching 2 GiB | a few s | a handful of rows |
| Restricted inside the derived table | thousands | a few hundred MiB | ~1 s | the same rows |
| Restricted, and the source pruned by `event_id IN …` | thousands | a few hundred MiB | ~1.5 s | the same rows |

Sustained over many runs, this write consistently took well over 1.5 GiB and was the node's daily memory peak;
no other pipeline statement came within an order of magnitude of it. The refresh write is uncapped before 0.2.3.

**What the window predicates actually prune.** The window is a cursor tuple compare,
`(toUnixTimestamp64Milli(_ingested_at), toString(event_id)) > (v, id)` (`CursorPredicates.seekPast`). ClickHouse
cannot prune on a tuple of expressions. So each window-keyed statement, including the dirty-key sub-select,
reads the whole of `dial_usage_log`. The reads stream, so the cost is time, not memory. That is a separate
defect, tracked separately. It matters here only because it makes each extra evaluation of the
dirty-key set cost one full source scan.

## Goals / Non-Goals

**Goals:**
- **Memory follows the window.** A refresh whose grain reads one enrichment builds its join from the dirty
  keys' enrichment rows only. Its memory stops growing with the enrichment's size.
- **Same values.** It writes exactly the rows and measures an unrestricted recompute writes, whatever the
  deployment's settings profile.
- **One mechanism.** The restriction is emitted through `EnrichmentJoins`, as the spec requires, not by a
  compiler-local rewrite.
- **Nothing else changes.** Every other grain compiles byte-identical SQL, and the read path is untouched.
  The one exception is the `transform_null_in = 0` setting on pipeline statements, which is the server's
  default.

**Non-Goals:**
- **Mixed grains** (source and enrichment keys), **two-enrichment grains**, and **source grains with
  enrichment filters or measures** keep their current joins. See D2 and D5.
- **The source side's full scan.** An unbucketed grain still reads the whole input on every refresh. A
  restricted enrichment is still scanned in full to evaluate its restriction, because `client_session_id`
  is not in `usage_client_identity`'s sort key. Both stream.
- **The cursor seek that prunes nothing.** It is a separate change; see Context.
- **The preview sample.** It has no dirty-key set to restrict by. It is already budgeted
  (`aggregate.preview.*`, with spill).
- **The user read path's enrichment joins.**
- **Other memory items.** Cross-pod refresh locking, a per-user memory ceiling, and a spill threshold on
  the refresh `GROUP BY` are separate items. Measurement shows the refresh's memory is the join, not its
  handful of groups.

## Decisions

### D1. Restrict by the enrichment's own key column, not by grain keys

The restriction is a predicate over the enrichment's columns, inside its derived table:

```sql
LEFT JOIN (SELECT event_id, <referenced columns> FROM usage_client_identity
           WHERE (usage_client_identity.client_session_id) IN (<the dirty-key sub-select>)) AS usage_client_identity
       ON dial_usage_log.event_id = usage_client_identity.event_id
```

The existing keying shape, `grain_key IN (SELECT grain_key FROM source WHERE …)`, cannot express this. Which
source rows belong to a dirty enrichment key is known only *after* the join, so no source-only predicate selects
them. The enrichment's own column selects the needed rows directly, and those rows are exactly the enrichment
rows the recompute aggregates.

The restriction's sub-select is the dirty-key sub-select the outer `WHERE` already uses, rendered by the same
helper. That sub-select carries its own window-keyed join to the same enrichment under the same alias. Both
26.3 and 26.8 resolve each reference to its innermost scope, with and without the analyzer.

*Rejected:*
- **Key the join on `grain_key IN (SELECT grain_key FROM enrichment WHERE key IN dirty)`.** It is the same set of
  rows, reached through one more sub-select.
- **Prune the source side too, by `event_id IN …`** (the last row of the table above). It measured no better,
  because `event_id` is not in `dial_usage_log`'s sort key.

### D2. Restrict only when every group key reads one enrichment

The restriction applies when every group key reads a column of the same enrichment, and each of those columns
is declared nullable. A group key here is a plain column or a time bucket over one, and every binding is one
physical column, so each key reads exactly one table. The restricted set is then exactly the dirty-key set,
and the restriction is `(<group keys>) IN (<the dirty-key sub-select>)`.

*Rejected:* **project the dirty-key set onto each enrichment's own components**, so that a mixed grain such as
`(deployment, uci.client_type)` also restricts. It was measured on a 2 M-row fixture on 26.3 and 26.8. With
three `client_type` values the projection selects almost the whole enrichment: build 2.00 M → 2.00 M rows,
memory unchanged, rows read +58% for the extra evaluation. The gain depends entirely on how selective the
projected component is, and no known declaration has a mixed or two-enrichment grain; the motivating
enrichment grain is `sessions_rollup`'s. Projection also adds a new statement shape that must carry its
own joins. Revisit it when a declaration needs it.

### D3. The restriction replaces keying, and needs no source predicates

For a restricted enrichment, `EnrichmentJoins` emits the derived table with the restriction as its only `WHERE`
conjunct. It adds no grain-key `IN (SELECT … FROM source …)` and no partition restriction:
- **With no source predicates**, which is `sessions_rollup`'s case, the existing `keyed()` would render
  `event_id IN (SELECT event_id FROM dial_usage_log)`, a set of every source key. That is worse than the bare
  join: measured at 2 M keys, 348 vs 259 MiB on 26.3.
- **With a source-only filter**, the filter's key set is not window-bounded. For a non-selective filter it is
  most of the source.
- **Dropping either is sound.** The dirty-key restriction already selects a superset of the rows the
  recompute reads. The partition restriction is drawn from source predicates and is left out with them, so a
  restricted derived table is not "keyed" in the sense of structured-query's partition-alignment requirement.

`EnrichmentJoins` throws `IllegalStateException` when a restriction is passed for a join that is not
`keyable()`, rather than silently falling back to the bare join. The compiler asks first, and requests a
restriction only for a keyable join. Joins built from the catalog are always keyable (`CatalogQueryService.buildModel`).
Only hand-built test models use the opaque-condition constructor, and those compile as before.

### D4. `transform_null_in = 0` on every pipeline statement

The argument for exactness, for each input row:
- **A row of a dirty key** keeps its enrichment row, because that row's key is in the dirty set.
- **A row whose enrichment row has a non-dirty key** loses the match and reads NULL, because the restricted
  key columns are declared nullable (D2, and the existing registration rule). Under `transform_null_in = 0`,
  `NULL IN (…)` never holds, so the row stays excluded, as it was before.
- **A row with a NULL key or no enrichment row** reads NULL in both forms and is excluded in both.

The argument fails only when NULL can satisfy `IN`, which is what `transform_null_in = 1` does.
- **Defaults:** checked on 26.3 and 26.8, the default is 0 and the setting is overridable per query.
- **Under `transform_null_in = 1`:** a grain with no filter excluding NULL diverges. Rows of non-dirty keys
  join the NULL group. Measured on both versions.
- **Why pin it:** ADAS sends no such setting today, but a deployment's settings profile could.

The pin goes in `FinalSettings.pipeline`, which every pipeline statement already passes through: the refresh
write, the Class-1 materialize, scans and probes. It is one line, and it adds no flag on the compiled refresh.
It changes nothing on a default server, and nothing in ADAS relies on `transform_null_in = 1`.

*Rejected:*
- **Send it only on a restricted write.** That needs a flag on `Compiled` and a settings hook in the shared
  `ClickHouseInsertSelectExecutor` that Class-1 materialize must not pass, for a setting whose value is the
  default.
- **Exclude NULL from the dirty set.** It does not help. Under `transform_null_in = 1`, a row whose enrichment
  row was filtered out still reads NULL and matches a NULL group.

### D5. Non-nullable keys are not restricted

The exactness argument needs an unmatched row to read NULL. `join_use_nulls = 0` renders an unmatched
non-nullable column as its type default instead: `''`, `0`, `[]`, or the epoch. If that default is a dirty key,
rows of non-dirty keys are counted under it. This was measured: the `''` group grew from 4 rows to 7, and the
epoch bucket from 4 to 9.

The registration rule refuses non-nullable enrichment group keys, except `array`, `map` and `object`
(`EntityFields.absenceIsUnrepresentable`).
- **`map` and `object`** cannot be ordering keys.
- **`array` can be a group key**, so a non-nullable `Array` key is reachable. The validator javadoc's "none of
  the three can be a group key" (`AggregateSpecValidator.java:599`) is wrong about it.

The compiler therefore restricts only when no group key's binding is an `array`, `map` or `object` type, and
compiles such a grain as before. The binding's `QueryFieldType` is already on the model. Every other
enrichment group key is declared nullable, because the validator refuses it otherwise, and that validator
runs on every refresh before compilation (`AggregateRefreshService.runRefresh`). It does not refuse `array` keys in the
validator: that would stall any existing declaration on its next refresh, for a hazard this change can simply
avoid.

### D6. Source-column grains stay as they are

When every group key is a source column, restricting the enrichment needs its grain keys for the dirty source
keys: `grain_key IN (SELECT grain_key FROM source WHERE (src keys) IN dirty)`. That is sound, but it re-reads the
source, and for a plain source key "every row of every dirty key" can be most of the input. A time-bucketed
source grain could instead key on its bucket bounds, which are source-only conjuncts. No motivating declaration
has a time-bucketed source grain, so neither is done here.

### D7. No flag

There is no configuration switch to fall back to the bare join. The restricted statement writes the same rows,
so a switch would only preserve a known memory hazard. The integration test pins the equivalence instead.

## Risks / Trade-offs

- **[The dirty-key set is built twice per recompute]** → ClickHouse does not share it. Both 26.3 and 26.8 plan
  two `CreatingSet` steps, and the extra rows read equal one standalone dirty-key sub-select. Because the
  cursor seek prunes nothing (Context), each build is a full, streaming scan of `dial_usage_log`. Measured, rows
  read rose by roughly 60% while time fell by more than half and memory by about 7×. Fixing the cursor seek
  makes the second build nearly free.
- **[`final = 1` and the restriction's order]** → Verified on 26.3 and 26.8. The derived table's `WHERE` on a
  non-sort-key column stays a `Filter` step above `ReadFromMergeTree … FINAL`. It is not moved to `PREWHERE`,
  even with `optimize_move_to_prewhere_if_final = 1`. So a superseded version carrying another key cannot leak
  into, or out of, the restricted set. An explicit `PREWHERE` would leak it, and ADAS never renders one. The
  integration fixture includes such a pair.
- **[The window limit bounds the join]** → The restriction follows the dirty keys, and `aggregate.window-limit`
  (100,000 rows, `@Min(1)` only) bounds those. Raising it widens the join again. Every refresh, including the
  first, a rebuild and a re-enable, goes through the probe, so no executed refresh is unbounded.
- **[A future change relaxes the nullability rule]** → D5's guard is in the compiler, beside the restriction.
  It does not rely on the validator alone.
- **[The source scan still grows with the table]** → Accepted here, as a non-goal. It takes about a second at
  moderate scale; a deployment with an order of magnitude more traffic and long retention could approach the
  120 s pipeline budget within a year. It is tracked separately.

## Migration Plan

There is no migration, catalog change or new configuration key. Deploying changes:
- the compiled recompute of rollups whose grain reads one enrichment;
- one default-valued setting on pipeline statements.

Those rollups' values do not change, so no rebuild is needed.

After deploying, check `system.query_log` for `query_id LIKE 'adas-sessions\_rollup-refresh-write-%'`.
Memory should fall by roughly an order of magnitude, and the target's rows should match a pre-deploy sample.

## Open Questions

None blocking. Mixed grains, source grains over an enrichment and a second measure-only enrichment become
worth doing only when a declaration needs them. None of the motivating declarations
(`usage-client-identity-live`, `sessions_rollup`) does.
