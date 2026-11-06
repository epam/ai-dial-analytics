## 1. Eligibility

- [x] 1.1 In `ReadShaping.latePlan`, keep the heavy branch unchanged (heavy late enrichments, their key-join choice and every disqualifier, a heavy column of a non-keyable enrichment included), then add light late enrichments: partition-aligned enrichments with no referenced heavy column whose every referenced field is projection-only (not in `filter`, not sorted directly or through a `select` output name) and whose `sameSourceKeys` match the key join. With no heavy late enrichment, take the key join from the light candidate whose keys defer the most (most `object` columns, then most columns, ties by grain-key name, then table name), never from `model.enrichments()` order (design D2, D3)
- [x] 1.2 Make sure a candidate that fails a light condition is left out of `tables` rather than returning `null`, and that a block with neither kind still returns `null`
- [x] 1.3 Replace "heavy" with "late" where it now means late enrichments: the `ReadShaping` class Javadoc (the late-materialization bullet), the `LatePlan` and `latePlan` Javadoc, the `StructuredQueryBuilder.build()` Javadoc that describes shaping, and the two comments in `shaped()` on the page block and the outer statement
- [x] 1.4 Do not wrap a page-keyed join in a derived table under any circumstance (design D5). If a test in group 3 shows a literal path rendered as a whole-column read, fix it in the expression rendering, not by changing the join. If 3.2 shows ClickHouse reads the whole `object` column for the join's hash build even through subcolumn access, record it in `acceptance.md` and stop before archive: revisit D5 (for example by leaving `object` columns out of light candidates) and update the spec to match
- [x] 1.5 Pre-selection guard (design D7): with no heavy late enrichment, return `null` when the filter qualifies for a partition pre-selection, whatever else the block joins. Decide "qualifies" with the test `pushdown` applies: a `ReadShaping.pushableConjuncts` conjunct for which `ReadShaping.lookup` holds and `PartitionPreselection.of` is present. Factor that into one shared predicate rather than duplicating it

## 2. Render tests

- [x] 2.1 `ReadShapingRenderTest`, header page: `request_time`, `dial_usage_log_payload.request_tags`, `trace_id`, `request_time >= T`, sort desc, `limit 25` renders the payload join as the bare table with `IN (K)` in `ON`, the outer `IN (K)` with both `IS NULL` arms, and a page block that joins no payload table with `LIMIT 25`
- [x] 2.2 A filtered light column (a `request_tags` path in `filter`, no heavy column) renders exactly as the unshaped statement, pre-selection included
- [x] 2.3 A sorted light column, sorted directly and through a `select` output name, with no heavy column, is not late-materialized. Beside a projection-only heavy column of another enrichment on the same keys, only the heavy enrichment is page-keyed and the sorted one renders as today
- [x] 2.4 A light column of an unaligned keyable enrichment is not late-materialized (the test model's `usage_client_identity` is unaligned, so use it)
- [x] 2.5 Add a third, partition-aligned enrichment to the test model, on the source's `(event_id, request_time)`, leaving `usage_client_identity` unaligned so existing tests keep their meaning. Heavy payload column + light column of the new enrichment: both late joins carry one `K` text and the page block joins neither
- [x] 2.6 Add a fourth aligned test enrichment on a different source grain key. Heavy payload column + light column of it: the payload is page-keyed and the other renders as today
- [x] 2.7 `distinct`, window, `array_join`, aggregate mode and a non-deterministic call over a light-only block are not late-materialized. The catalog has no non-deterministic function other than the exempt `now`, so that case has no fixture; its disqualifier applies to every candidate block, heavy or light, and is unchanged
- [x] 2.8 A heavy column of a non-keyable enrichment beside a light aligned column is not late-materialized
- [x] 2.9 Pre-selection guard (1.5): a lookup on an aligned enrichment + a light payload column + a column of an unaligned enrichment renders exactly as the unshaped statement, with the pre-selection keying the unaligned join. The same with an aligned enrichment on another grain key instead, and the same query without either, also render today's statement. With a heavy column added beside the unaligned one, it is late-materialized as a heavy page. `PartitionPreselectionRenderTest.preselectionKeysOnlyTheJoinsWithoutAnAlignedPair` is the first case and passes unchanged
- [x] 2.14 Key join (1.1): a light-only page projecting `trace_summary.title` beside a payload `object` column, or beside two payload scalars, or beside one payload scalar (a tie), takes the payload's `event_id` keys, with the model's enrichments in either order
- [x] 2.10 `WindowTransferRenderTest`: `windowOnlyReadTransfersItsWindowOntoTheAlignedEnrichment`, `anotherSourceConjunctKeepsBothKeySets` and `disjunctionOverThePartitionColumnKeepsBothKeySets` project the aligned `ident.session` only, so they become late-materialized. Add a sort on that column to each, so they keep asserting the derived table, as the modified scenarios do. Add a test for the window-only page that projects the aligned column only: the join is the bare table with `IN (K)`, with no transferred window and no key set
- [x] 2.11 Composite blocks: a composite SQL block that projects a light column of an aligned enrichment under a `LIMIT` renders as today, with no page-key subquery
- [x] 2.12 SQL surface: the `rest-sql-endpoint` header-page statement, translated and rendered through the read path, page-keys the payload join as 2.1 does
- [x] 2.13 Run every render suite. Every existing late-materialization test passes unchanged, which shows that blocks without an aligned light candidate keep their SQL (blocks with one change, per 2.5). Any other failure must be a test whose block projects an aligned light column only. Update it as 2.10 does, and check that a spec scenario covers the new shape, or treat the failure as a bug. The full build found two such integration suites: `AlignedEnrichmentIntegrationTest` (`windowOnlyReadTransfersItsWindowAndReturnsTheKeyedRows`, `windowBesideAnotherSourceConjunctKeepsBothKeySets`) and `QueryPathPruningIntegrationTest` (`everyExtractorInTheProjectionReturnsTheWholeColumnValues`), now sorted on the enrichment column

## 3. Object paths on a page-keyed join

- [x] 3.1 In `QueryPathPruningRenderTest`, a light-only late block that addresses literal paths of a payload `object` column (`jwt_claims` in that test's model; `request_tags` takes the same path), through a scalar extractor and through `json_extract_array`, renders the payload join as the bare table, and the outer projection reads each path as the subcolumn `"dial_usage_log_payload"."request_tags"."<path>"`, never the whole column. A `json_extract_string_ci` reference on the same block keeps the bare page-keyed join and reads the whole column
- [x] 3.2 On ClickHouse (integration test or a throwaway local instance), check with `EXPLAIN` or `system.query_log` `read_columns` that the page-keyed join's hash build reads only the addressed subcolumns of `request_tags`. Record the result in `acceptance.md` (see 1.4 for the fallback)

## 4. ClickHouse equivalence (`ReadShapingIntegrationTest`)

- [x] 4.1 Seed `request_tags` in the payload fixture (`insertPayload` writes only the bodies today), then extend the unshaped-equivalence run with a light-only payload page, with and without `offset`, over rows with no payload row, unmerged payload versions and sort ties. Assert identical names, types and values row for row. The suite reads final. Say in the test that raw-read fan-out is covered by the requirement's argument, not by this suite

## 5. Specs

- [x] 5.1 Confirm the delta specs match the shipped behaviour:
  - `structured-query`: the rename; the "SQL translation", budget, keyed-grain-join, partition-transfer, partition-pre-selection and late-materialization requirements;
  - `rest-sql-endpoint`;
  - no other spec cites the old title;
  - sweep `openspec/specs/` for any scenario whose row-mode read projects an aligned enrichment's light column only and asserts a derived table, a key set, a transferred window or a bare unrestricted join; modify each one into this change.
- [x] 5.2 `openspec validate late-materialize-projection-only-enrichments --strict`
- [x] 5.3 After archive, check that the renamed requirement (moved to the end of `## Requirements` by the archive) still sits before `## Implementation notes` in `openspec/specs/structured-query/spec.md`, and that `openspec validate --specs` reports nothing new

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes

## 7. Acceptance on a running deployment

Record order-of-magnitude peak memory and duration from `system.query_log`, before and after, in `acceptance.md`. A shape regresses when, at the same window, it is refused by the budget where today's statement completes, or its peak memory or duration is more than twice today's and above the floor design D6 sets (about 100 MiB, about 1 s). If any shape below regresses, add an eligibility guard to this change, with a spec condition and a design update (design D6), before archive.

- [x] 7.1 The header page from the proposal over a multi-day window
- [x] 7.2 A wide-window light page that succeeds today, checked against the execution-time ceiling as well as memory
- [x] 7.3 A light page over a short window (hours), where the page block's extra pass is proportionally largest
- [x] 7.4 A light page whose filter reads another enrichment without qualifying for a pre-selection (a range or substring match), which the page block and the outer statement both join
- [x] 7.5 Point lookups projecting light payload columns: by `trace_id = X`, and by a conversation-id header path
- [x] 7.6 A light page over a sparse aligned enrichment (one with rows for few source rows)
- [x] 7.7 The guard (design D7): a session lookup projecting light payload columns, with and without an unaligned enrichment's column, renders today's SQL and costs what it costs today
- [x] 7.8 A path-only light page over a wide window (`json_extract_string(request_tags, 'conversation_id')` and no bare document), checking duration as well as memory: today's window-bounded join over a pruned path is already cheap (`acceptance.md`), so the page block's second pass over the source window is the whole difference
- [x] 7.9 Replay a sample of row-mode reads that project aligned-enrichment columns, at default and explicit limits, including pages projecting identity columns on a deployment where `usage_client_identity` is aligned. Confirm identical results and no new budget refusals
