## Context

See proposal.md for the motivation. The relevant current shape, after RC1:

```
SELECT <projection>, "dial_usage_log_payload"."request_body" AS …
FROM "dial_usage_log" [FINAL]
LEFT JOIN (SELECT event_id, request_body FROM dial_usage_log_payload FINAL
           WHERE event_id IN (SELECT event_id FROM dial_usage_log WHERE <source conjuncts>)
             AND request_time IN (SELECT request_time FROM dial_usage_log WHERE <source conjuncts>)) AS "dial_usage_log_payload"
  ON "dial_usage_log"."event_id" = "dial_usage_log_payload"."event_id"
WHERE <whole filter> ORDER BY … LIMIT n OFFSET m
```

`EnrichmentJoins.addReferenced` emits the join; `StructuredQueryBuilder.build` decides keying. The same `build`
serves three kinds of caller: the single-entity read (`StructuredQueryExecutor`, 4-argument overload, run with
`join_use_nulls = 0`), composite blocks (`CompositeQueryBuilder`, 5-argument overload, `join_use_nulls = 1`) and
the aggregate pipeline compiler (6-argument overload). Payload bodies are `Nullable(String)` with an AES codec;
the payload table is `ReplacingMergeTree(_updated_at)`, `ORDER BY event_id`, `PARTITION BY toYYYYMMDD(request_time)`,
and partition-aligned with `dial_usage_log` on `request_time`. User reads send
`do_not_merge_across_partitions_select_final = 1` for partition-local entities and no prewhere setting.

All measurements below ran on throwaway local servers of **26.3.24.4** (a deployed version) and **26.8.17.4** (the
Testcontainers / compose version), with a 300k-row source and a 280k-row payload of 4 KB bodies (1.04 GiB), and
`do_not_merge_across_partitions_select_final = 1` as ADAS sends it. Scripts are in the session scratchpad.

## Goals / Non-Goals

**Goals:** bound the hash-join build of a heavy column by what the statement actually returns or keeps; prove
every rewrite returns today's rows byte for byte, unmatched rows and NULL/default semantics included.

**Non-Goals:** pushing predicates or calls into composite blocks (their `join_use_nulls = 1` changes the
unmatched-row algebra, and `CompositeQueryBuilder` stays untouched); pipelines; object subcolumn pruning on the read
path (RC3); making full-text search over a day of bodies cheap (B narrows the build, not the scan).

## Decisions

### D1. A's single-evaluation shape: one page-key subquery, referenced twice in one scope

The late statement is the **original statement** with two edits:

```
SELECT <projection, unchanged, heavy columns included>
FROM "dial_usage_log" [FINAL]
LEFT JOIN <light enrichments, exactly as today>
LEFT JOIN "dial_usage_log_payload" FINAL
  ON "dial_usage_log"."event_id" = "dial_usage_log_payload"."event_id"
 AND ("dial_usage_log_payload"."event_id", "dial_usage_log_payload"."request_time") IN (K)
WHERE <whole filter>
  AND (("dial_usage_log"."event_id", "dial_usage_log"."request_time") IN (K)
       OR "dial_usage_log"."event_id" IS NULL OR "dial_usage_log"."request_time" IS NULL)
ORDER BY <unchanged> LIMIT n OFFSET m

K = SELECT "__adas_lm_key", "__adas_lm_part" FROM (<page block>) AS "__adas_lm"
<page block> = the same block with every select entry that references a heavy field dropped (the entries a sort
               item names are kept, under their resolved names), projecting
               source_key AS "__adas_lm_key", source_partition AS "__adas_lm_part", LIMIT m + n, no OFFSET
```

Why one evaluation: ClickHouse builds one set per distinct `IN` subquery **within one query scope** and reuses it.
Measured with a `K` ordered by `rand()` (two evaluations would pick unrelated keys):

| placement of the two references | 26.3.24.4 | 26.8 |
|---|---|---|
| outer `WHERE` + `WHERE` inside the derived table | 10 of 10 rows unmatched — **two sets** | same |
| outer `WHERE` twice | 10 rows — one set | — |
| outer `WHERE` + the join's `ON` | 50 of 50 matched, 10/10 runs — **one set** | 50/50, 10/10 runs |

So `K` must not sit inside the enrichment's derived table, which is where today's keying lives. A condition in
`ON` over right-side columns only is pushed by ClickHouse into the right side's read (EXPLAIN: `Filter (Join
filter)` above `ReadFromMergeTree (dial_usage_log_payload)`, with MinMax `request_time in set` and PrimaryKey
`event_id in set`), so the build is narrowed exactly as a derived-table `WHERE` would narrow it. A predicate on
`(event_id, request_time)` is also safe *before* the `FINAL` merge, should the engine move it there: every version
of a key shares both values, so the filter keeps all of a key's versions or none.

Measured (1-day-shaped window, `ORDER BY request_time DESC LIMIT 10 OFFSET 20`):

| | 26.3.24.4 | 26.8 |
|---|---|---|
| today | 1855 ms, 1.92 GiB | 2915 ms, 868 MiB |
| D1 | 42 ms, 1.5 MiB | 21 ms, 1.5 MiB |

**Exactness.** Let `S` be the rows the original statement's `WHERE` keeps and `T` its page `[m, m+n)` under the
ordering. `K` holds the keys of `T' = top (m + n)` of `S` (the page block evaluates the same `FROM`, light joins,
filter and order). The outer keeps `S' = { r ∈ S : key(r) ∈ K }` plus any row with a NULL key or partition (whose
heavy join could not match today either). `T' ⊆ S' ⊆ S`, so `top (m + n)` of `S'` is `T'` and rows `[m, m+n)` of
`S'` are `T` — up to ties at the boundary, which the original statement leaves to the engine too. Uniqueness of
the grain key is **not** assumed: a second source row sharing a page key only enlarges `S'` with rows ranked
outside `T'`. That is why the page block takes `LIMIT m + n` without an offset rather than the page itself: with
`OFFSET` and a non-unique key, re-filtering by the page's keys alone could admit a duplicate that ranks before the
page. The heavy join for the page's rows is the one today's join gives them: for a kept row, `(event_id,
request_time) ∈ K`, so the `ON` restriction is true and the match is the key equality alone.

Alternatives rejected:
- **`K` inside the derived table** (the prompt's first candidate, as literally written): two evaluations, measured.
- **Materialized CTE**: `WITH … AS MATERIALIZED` parses on both versions but is governed by
  `enable_materialized_cte`, off by default; relying on a non-default setting, or sending one, makes correctness
  depend on a profile.
- **Deterministic tie-breaker plus an `_ingested_at` bound** on two evaluations: needs a proof that both reads see
  the same parts, which a concurrent merge or ingest can falsify; D1 removes the second evaluation instead.
- **Inner page as the `FROM` (probe side) and the payload restricted by the page keys**: still two references to
  the page in two scopes.

### D2. When A applies

A applies to a block built for the single-entity read path when **all** hold: row mode; not `distinct`; a `LIMIT`
is rendered (always true under governed paging); at least one referenced enrichment has a heavy field referenced
and that enrichment is keyable; every heavy field of every such enrichment is referenced **only** by select
entries — not by `filter`, `sort` (directly or through a sorted select alias), `group_by`, or `having`; no select
entry contains a window (it would be computed over `S'` instead of `S`) or an `array_join` (it changes the row
count `LIMIT` counts); no non-deterministic catalog function other than `now` (ClickHouse evaluates `now()` once
per statement, so both scopes see one value); every late enrichment shares one `(source key, source partition)`
pair (so one `K` serves them all); no select output name collides with the two reserved `__adas_lm_*` names. An
enrichment that is keyable but not partition-aligned uses a one-column `K`.

Anything else keeps today's statement. A light field of a late enrichment may appear anywhere: the page block
joins that enrichment for its light fields as today, and the outer reads the light value from the `K`-restricted
join, which matches every kept row.

### D3. Shaping is opt-in by overload, not inferred

The 4-argument `build(dsl, model, query, maxLimit)` — only `StructuredQueryExecutor` calls it, for execute,
execute-sql, the CSV twins and Flight — and `buildCount` shape the statement. The 5- and 6-argument overloads
(composite blocks, the aggregate compiler) render exactly as today. A test-only `withoutReadShaping()` builder
renders the unshaped statement so the integration tests can compare the two directly. Rejected: inferring the
caller from `LimitPolicy` — the composite root also runs `GOVERNED`, under `join_use_nulls = 1`.

### D4. B: which conjuncts move, and where

A top-level conjunct (RC1's flattening) is **pushable** when it names exactly one field, that field belongs to an
enrichment, its type is `string` or numeric (not `object`, `map`, `array`, `enum`, `boolean`, `timestamp`,
`uuid`), and its shape is one of:

| shape | false on the unmatched value because |
|---|---|
| `co` / `ico` with a non-empty needle | `'%x%'` cannot match `''`; NULL → NULL |
| `eq` against a non-null literal that is not the type default (`''`, `0`) | `'' ≠ 'v'`, `0 ≠ 5`; NULL → NULL |
| `in` whose every literal is non-null and not the type default | as `eq` |
| `eq(starts_with(f, 'x'), true)` / `eq(contains(f, 'x'), true)` with a non-empty literal | `startsWith('', 'x') = 0`, `position('', 'x') > 0 = 0` |

Under `join_use_nulls = 0` an unmatched column reads its type default — `''` / `0` for a non-nullable one, NULL for
a `Nullable` one (the bodies) — and every shape above is false (or NULL, which `WHERE` rejects) on both. So moving
the conjunct into the derived table turns a row the outer `WHERE` would reject anyway into an unmatched row the
outer `WHERE` still rejects. It **stays** in the outer `WHERE`. Excluded on purpose, each with a test showing it
stays outside only: `IS NULL` (true on unmatched), `IS NOT NULL` (true on a non-nullable column's `''` default —
the binding does not carry nullability), `ne`, `nc`/`inc`, `lt`/`le`/`gt`/`ge` (`length(x) < n`, `0 < 5`), `not`,
`or`, `coalesce`, document comparisons on `object` fields (their default `{}` serializes to `'{}'`, which `co '{'`
matches), and any shape the table does not name.

The pushed conjunct is added to the enrichment's derived-table `WHERE` beside the RC1 keys. An enrichment with a
pushed conjunct but no source key predicates is emitted as a derived table restricted by the pushed conjuncts
alone instead of the bare table. Measured under `FINAL` with an old version matching and the newest not, inside the
derived table, on both versions: 0 rows (the filter sees the merged row), and still 0 with
`optimize_move_to_prewhere_if_final = 1`. ADAS sends neither that setting nor any prewhere setting; the server
default is 0 on both versions.

**Copying alone did not fix h7 (found in acceptance).** With the `ILIKE` copied but still evaluated in the
outer `WHERE`, the derived table had to project the body, and a day's matching bodies could still fill 2 GiB.
So when a heavy field is read *only* through copied conjuncts and pushed calls, the derived table computes each
copied conjunct as a column (`(request_body ILIKE '%error%') AS "__adas_c0"`), stops projecting the body, and the
outer conjunct reads the column. Exact for the same reason the copy is: a matched row's column is the conjunct's
value (true, by the copy), an unmatched row reads `0`/NULL, and the conjunct is false/NULL there too. h7 then ran
in 1.9 s / 685 MiB (see `acceptance.md`). A body that is also read raw keeps the plain copy.

### D5. C: pushed calls, and why their unmatched value is today's

A call is **pushable** when its first argument is a heavy `string` enrichment field, its other arguments are
literals, and it is one of: `length`; `json_extract_string`, `json_extract_long`, `json_extract_double`,
`json_extract_bool`, `json_extract_array`, `json_extract_string_ci`; `starts_with` / `contains` with a non-empty
literal. A heavy field is pushed only when **every** reference to it in the statement is a pushable call; the
derived table then projects each distinct call once, as `<call> AS "__adas_c<i>"`, and no longer projects the raw
column. The outer statement renders each occurrence of the call as `"<enrichment>"."__adas_c<i>"`, through a
per-statement substitution in the expression translator (by value equality on the DSL expression, so repeated
occurrences share one column). Type checks keep running on the original expressions; only rendering is substituted,
so the output names (`OutputColumnNaming`) and every validation message are unchanged.

Choice of the two options the prompt offers: **allow-list only calls whose unmatched value is already today's**,
rather than wrapping each reference in `if(<matched>, x, f(''))`. With the call inside, an unmatched row reads the
default of the call's result type, under `join_use_nulls = 0`. Today it reads `f(u)` where `u` is the field's
unmatched value (NULL for a `Nullable` column, the type default otherwise). For each allow-listed call those agree:
`length(NULL)` is NULL and the column is `Nullable(UInt64)`; `JSONExtractString(NULL, …)` is NULL in a
`Nullable(String)`; the two array/ci extractions wrap their operand in `ifNull(…, '')`, so they return `[]` / `''`,
the defaults of their non-nullable result types; over a non-nullable column, `length('') = 0`,
`JSONExtract*('') = '' / 0 / false`, `startsWith('', 'x') = 0`. An integration test asserts this per call over both
a `Nullable(String)` and a `String` column, so a ClickHouse change that broke it fails the build. The wrap
alternative needs a match marker column and a second rendering of each call, and buys nothing for this list.

### D6. Precedence and the count statement

A runs first; a heavy field A materializes late is fetched raw for the page and is not a C candidate. B and C then
apply to whatever derived tables remain (including the page block's). `buildCount` gets B and C (its filter is the
only place a heavy field can appear), never A (it projects nothing).

The count had a second problem the render tests exposed: it joins every enrichment the *query* references, select
included, so `include_total` on a body page built the bodies of the whole window just to count rows. Read final,
every grain join is 0..1 and cannot change a count, so the shaped count joins only what its filter references.
Read raw, an unmerged enrichment version fans a row out in the page, so the count keeps the page's joins and keeps
counting the same rows.

### D7. Tests

- Render tests in the `EnrichmentJoinRenderTest` style for every applicability edge and every excluded shape.
- `StructuredQueryReadShapingIntegrationTest` (Testcontainers, 26.8) seeds a fixture with unmatched source rows,
  three payload versions of one key, ties on the sort key, a body over 1 MB, and builds each corpus-shaped query
  twice — `builder` and `builder.withoutReadShaping()` — and asserts identical `TabSeparatedWithNamesAndTypes`
  output (names, types and bytes), with and without `OFFSET`.
- The single-evaluation proof runs the exact D1 shape with a `rand()`-ordered page block and asserts every page row
  matched, 20 times.

## Risks / Trade-offs

- [Set reuse within a scope is an engine behavior, not a documented contract] → it is measured on both supported
  versions and pinned by the rand() integration test on the Testcontainers version; a ClickHouse upgrade that broke
  it fails the build instead of silently blanking bodies. The 26.3.24.4 result is recorded here.
- [The page block evaluates the source and light joins a second time] → light by definition; measured read of the
  late statement is 4–6 MiB against 1.1–1.4 GiB.
- [A heavy page with a large `OFFSET` fetches `m + n` bodies] → still bounded by the page, never by the window; the
  hash build is `m + n` bodies against today's whole window.
- [B/C push only closed shapes] → shapes outside the lists keep today's cost; extending a list needs an
  unmatched-value argument and a test, the same bar these rows met.
- [Full-text `ico` over a day of bodies still scans every body] → B removes the OOM (rejected rows never reach the
  build), not the scan; the report's 1.8 s / 846 MiB for h7 is the expected cost.

## Migration Plan

No data or config migration. Rollback is a revert of the change; the statements are recomputed per request.
Post-deploy, rerun corpus ids h2, h3, h4, h5, h7, h10, h13 and c5 through `execute-sql` against a running deployment and append the
`query_log` numbers to this change's acceptance record (see tasks).
