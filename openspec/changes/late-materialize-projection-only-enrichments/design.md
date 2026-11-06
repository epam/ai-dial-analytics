## Context

Late materialization (structured-query, "Projection-only enrichment columns are fetched for the returned page only",
titled "Projection-only heavy enrichment columns …" before this change) is planned by `ReadShaping.latePlan` and
rendered by `StructuredQueryBuilder.shaped`. The plan's `tables` are the enrichments the outer statement joins for
the page's keys. `shaped` gives each of them an `EnrichmentJoins.Shaping` that carries only the page-key subquery,
and `EnrichmentJoins` renders such a join as the bare enrichment table (under `FINAL` when read final) with
`IN (K)` in its `ON`, never as a derived table. The page block is rendered from the filter, the sort and the
sort-named `select` entries, so it joins only the enrichments those clauses read. Today `latePlan` puts an
enrichment in `tables` only when the block references one of its `heavy` columns, so a block that projects only
light columns of an aligned enrichment falls through to the unshaped join. That join is bounded by the window's
partitions (partition alignment, window transfer) but not by the page. See proposal.md, Why.

The renderer already handles any enrichment in `tables`. Nothing in `shaped` or `EnrichmentJoins` depends on the
columns being heavy, so this change is mostly in eligibility.

"Renders a `LIMIT`" holds for every row-mode read on the single-entity path: a query without a `page` gets the
default limit (`ReadShaping.governedPage`). The widened rule therefore reaches every row read that projects a light
column of an aligned enrichment, paged or not. Two requirements have scenarios of exactly that shape, written when
such a read could not be late-materialized: "A keyed grain join is bounded by an aligned partition column" and "A
partition-only key on an aligned enrichment is transferred, not keyed". The delta modifies them to sort on the
enrichment column, which keeps their subject (the derived table) in the statement, and adds the page-keyed case
beside the window-transfer one.

## Goals / Non-Goals

**Goals:**
- A row page that projects light columns of an aligned enrichment costs the page, not the window, on the same
  read surfaces that late-materialize today.
- No block loses eligibility, and a heavy late enrichment's join renders as it does today. A block that is
  late-materialized today *can* change SQL: an aligned enrichment on the same keys that it reads only in its
  projection becomes a second page-keyed join beside the heavy one. Any row page that also projects columns of an
  aligned user enrichment, for example `usage_client_identity` on a deployment that laid it out aligned, is in
  that set. Results are unchanged.

**Non-Goals:**
- Unaligned enrichments with light columns. Their keyed join has no partition bound to hang the page keys on. A
  page-key lookup on an uncorrelated grain key still lands a granule in every partition, so the trade is not
  measured. They keep today's join.
- Filtered or sorted light columns. The page block has to join those anyway, so deferring them too would only add
  a second join of the same table.
- Changing what `heavy` means, the star projection, partition pre-selection or pipeline group fetches.

## Decisions

**D1. Widen eligibility by kind, not by flipping the column to heavy.** A light late enrichment is a
partition-aligned enrichment whose referenced columns are all projection-only, on the key join's keys. Rejected:
marking `request_tags` heavy. It would fix one column, and it would also drop the column from partition
pre-selection, disqualify late materialization for any block that filters on it, and split pipeline group fetches
per column. Each of those is a regression for header filters and conversation-id lookups. Also rejected: deferring
*every* projection-only enrichment column regardless of alignment (see Non-Goals).

**D2. A light candidate never disqualifies.** Today a referenced heavy enrichment that is not keyable, or is keyed
differently, makes `latePlan` return null for the whole block. A light candidate that is on other keys than the
key join is left out of `tables` and joins as it does today. So is an aligned enrichment read outside the
projection, and so is an unaligned one. That is what guarantees no block becomes ineligible: the heavy branch is
unchanged, and the light branch can only add tables. The one exception, D7, only keeps a light-only block in today's
statement; it never applies to a block with a heavy late enrichment.

**D3. The key join.** With heavy late enrichments present, the key join is the first of them, as today. A light
candidate joins `tables` only when `sameSourceKeys(keyJoin, candidate)` holds. With none present, the key join is
the light candidate whose source keys defer the most: the most referenced `object` columns across the candidates on
those keys, then the most referenced columns, ties broken by the source grain key's name and then the table's. The
other light candidates are compared against it. Alignment only says that the enrichment's partition column is named
like the source's (`CatalogQueryService`), so every aligned join of one source shares the source's partition column,
but its grain key is per enrichment. Two aligned enrichments can therefore differ by grain key, never by partition
column. Rejected: the first light candidate in `model.enrichments()` order. That order comes from
`TableDefinitionRepository.findBySourceTable`, which has no `ORDER BY`, so it can change after an unrelated catalog
update, and it lets a cheap enrichment on a non-unique key take the page keys from the payload document this change
exists to defer. Ranking by `object` columns first targets that document; the name ties make the SQL deterministic
whichever order the catalog returns.

**D4. The page block is described as the code builds it.** The current spec says the page block is the block with
its heavy `select` entries removed. The code keeps only the sort-named entries (`ReadShaping.latePlan`). Both give
the same keys, and the sort-named form is the one that stays right now that light entries are deferred too. The
delta states the sort-named form, and requires that no sort-named entry reads a deferred column. For heavy columns
that is already a disqualifier, and for light candidates it is part of the definition.

**D5. A late join stays the bare table; object paths are subcolumns of it.** A page-keyed join has no derived
table, so the path-pruning rule (which narrows a derived table's projection) does not apply to it. What bounds the
read is that an addressed path renders as the subcolumn `"t"."col"."path"` of the joined table. The delta makes
both normative: the late join is the table itself, and a compile-time literal path of a scalar extractor or
`json_extract_array` is read as its subcolumn. A reference that reads the whole document (`json_extract_string_ci`,
which goes through `toJSONString` by design, a bare projection, a comparison) still reads the whole column, as the
path-pruning rule's fallback does, but only for the page's rows. Wrapping the
late join in a derived table to "restore" pruning is ruled out. It would move `K` into a nested scope, where
ClickHouse re-evaluates it, which the same requirement forbids. Acceptance measures on ClickHouse whether the hash
build of a page-keyed join reads only the addressed subcolumns.

**D6. No eligibility guard beyond alignment, for now.** The rule fires on any aligned projection-only column,
whatever the window or the enrichment's density. Rejected for now: a guard (requiring a time bound on the source,
or skipping enrichments below a size). A guard needs a threshold that only measurement can set, and the cost
trade (below) is favourable in every shape anyone has reasoned through. Acceptance (tasks group 7) covers the
shapes where it could invert. A shape **regresses** when, at the same window on the same deployment, it is refused
by the budget where today's statement completes, or its peak memory or its duration is more than twice today's. If
one regresses, the guard is added in this change before it is archived, and the spec gains the condition.

**D7. A pre-selected light-only block keeps today's statement.** Partition pre-selection restricts the page block
only (structured-query, "A lookup on an aligned enrichment pre-selects the source's partitions"). The outer statement
is bounded by the page keys `(grain_key, partition_col) IN (K) OR … IS NULL`. Those bound its late joins, through the
`IN (K)` in each late join's `ON`. Its other joins keep only what ClickHouse carries into them from the outer `WHERE`.
Today the pre-selection bounds them all: a join with no aligned partition pair is keyed on it, and an aligned join gets
the source's partition restriction carried through its `source.p = e.p` equality (measured in
`PartitionPreselectionIntegrationTest`). That includes the lookup's own enrichment, which is filtered, so it is never
a late join; in the outer statement it is a derived table carrying only the pushed lookup conjunct. Nothing measures
whether ClickHouse carries the tuple `IN (K)`, with its `IS NULL` arms, through a join equality the same way. Without
it, the lookup's enrichment is read across every partition once more, and an unaligned join hashes its whole table.
So a block whose only late enrichments are light is not late-materialized when its filter qualifies for a
pre-selection, whatever else it joins. Little is lost: the pre-selection already bounds the light enrichment's join to
the lookup's partitions, which for a session or conversation lookup is a few days. A block with a heavy late
enrichment is unchanged: its outer statement already lacks the pre-selection today.

Rejected: exempting a light-only lookup block whose every other join is aligned on the key join's keys. That rests on
the unmeasured carry-through above. Also rejected: giving the outer statement the
pre-selection too. That would fix heavy pages as well, but each pre-selection would be evaluated a second time (its
own scope), and the SQL of every heavy page with a lookup would change. That is a separate cost trade, to be
measured on its own. Also rejected: keying an unaligned join on the page keys through the source
(`chat_id IN (SELECT chat_id FROM source WHERE (key, part) IN (K))`), which puts `K` in a nested scope.

## Risks / Trade-offs

- [The page block costs more than a second pass over source columns] → It re-runs the whole filter, re-joins every
  enrichment the filter or sort reads, and on a collapsing source adds a second `FINAL` pass over the window. The
  outer statement's `FINAL` scan still reads the window too, because the page keys (`event_id`, `request_time`) are
  not the source's sort-key prefix, so `IN (K)` cannot be relied on to prune that scan. What it saves is the window-sized hash
  build of the deferred enrichment's columns. The trade inverts when the deferred join is cheap and the filter is
  expensive. Acceptance runs exactly those shapes (7.2–7.5) before merge.
- [An outer statement loses keying that the page block has] → The page block carries the pre-selections; the outer
  statement does not. D7 keeps every light-only block with a pre-selection in today's statement. Heavy lookup pages
  keep the shape they run today.
- [Row pages that project light columns of aligned enrichments change SQL shape] → Results are unchanged by the
  equivalence requirement. The render tests add a third, aligned enrichment to the test model to cover the mixed
  heavy + light case. The existing models declare `usage_client_identity` unaligned, and flipping it would break
  unrelated tests. Acceptance replays the read-path sweep, including identity-projecting pages.
- [A light late enrichment with unmerged versions] → The outer join keeps `FINAL` and the 0..1 match when read
  final, which `ReadShapingIntegrationTest` exercises: that suite runs force-final. The raw-read fan-out case, where
  page and unshaped statement fan out alike, rests on the equivalence argument in the requirement, as it does for
  heavy columns today. It is not covered by an integration test.
- [An API-created table later declared aligned, through enrichment relayout] → The rule keys on the catalog's
  alignment claim, which is already trusted by the keyed join. No new trust is introduced.

## Migration Plan

Code-only. No migration, config key or catalog change. Rollback is a revert. No flag: the renderer path is the
one heavy columns already exercise.
