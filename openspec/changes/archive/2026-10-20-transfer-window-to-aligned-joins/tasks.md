## 1. Render tests first (TDD)

- [x] 1.1 New `WindowTransferRenderTest` over a hand-built aligned model (as in `PartitionPreselectionRenderTest`):
      window-only filter → transferred bound, no key set, tuple join kept; window + pushed enrichment conjunct →
      both in the derived table, no key set; window + another source conjunct → both key sets as before; an
      unaligned join → grain-key set as before; a disjunction over the partition column → sets kept; count
      statement transfers (a bound holding a subquery is excluded by the reused `partitionBounds`; not separately
      tested here)
- [x] 1.2 A composite case: an operand with a window and a one-hop reduction keeps its sets; one with a window and
      no reduction transfers
- [x] 1.3 Confirm the new tests fail on the current code

## 2. Implementation

- [x] 2.1 `EnrichmentJoins.addReferenced` general form: accept per-enrichment transferred bounds that replace the
      key restrictions of a keyed derived table; every other overload passes none
- [x] 2.2 `StructuredQueryBuilder`: compute the transferred bounds per aligned keyable join (every source-only
      conjunct is a `partitionBounds` bound and no key conjunct was handed in) and pass them from the row/page-block
      and count statements
- [x] 2.3 Update class notes (`EnrichmentJoins`, builder) to describe the transfer

## 3. Integration test

- [x] 3.1 ClickHouse integration test (Testcontainers) over an aligned source/enrichment pair with rows inside and
      outside a window: the transferred statement and the same statement with both key sets return identical rows,
      for a window-only row read and a window plus pushed-conjunct filter; and a window beside a `trace_id` lookup
      keeps both sets

## 4. Existing tests and specs

- [x] 4.1 Update existing render/integration expectations that asserted both key sets for a window-only aligned read
- [x] 4.2 Delta spec matches shipped behaviour; requirement inserted before `## Implementation notes` at archive

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes
