## 1. Carry the partition column with the cursor

- [x] 1.1 Extend `CursorColumns` with the source table name and a nullable `partitionColumn`. Keep
      `version()` / `identity()` unchanged, and update the three constructions (`ScanSourceMetadata`,
      `AggregateRefreshServiceTest`, `GroupFetchServiceTest`).
- [x] 1.2 Populate both from the `table_definition` row in `ScanSourceMetadata.cursorColumns(source)`
      (`getName()`, `getPartitionColumn()`). Extend `ScanSourceMetadataTest` for a partitioned and an
      unpartitioned source.

## 2. Render the derived bound in `CursorPredicates`

- [x] 2.1 Add a private builder for
      `"<source>"."<partition>" >= (select min("<partition>") from "<source>" where "<version>" >= fromUnixTimestamp64Milli(<v>) settings final=0)`.
      The outer column is qualified by the source and the inner names are unqualified (design D1/D2).
- [x] 2.2 Append it in both `seekPast` overloads and in the `from` half of `window(...)` when
      `partitionColumn` is non-null. Leave `atMost`, the `to` half and `safetyLag` untouched.
- [x] 2.3 Update the class Javadoc. Say why the bound exists, why it is exact, why the inner `final=0`
      is mandatory, and why a plain version-column bound is not used (PrimaryKeyExpand under `final=1`).
- [x] 2.4 Unit tests for the rendered SQL:
      - a partitioned source gets the bound on `seekPast` and on `window(from, …)`, but not on `atMost`
        or `window(…, to)`;
      - an unpartitioned source renders byte-identical SQL to before.

## 3. Integration coverage (ClickHouse testcontainer)

- [x] 3.1 Result identity: build a partitioned source spread over several days. Run the same scan page,
      Class-1 probe and write, and aggregate refresh window with and without the bound, at a pinned
      cursor and window, and assert identical rows, end tuple, `has_more` and written rows. Toggle the
      bound by an unpartitioned twin definition, or by asserting against a hand-written baseline query.
- [x] 3.2 Late row: ingest, after the cursor, a row whose `partition_column` is days older than the rest
      of the window. Assert it is returned or written exactly once in the next window.
- [x] 3.3 Pruning actually happens: for a window near the newest rows, assert through
      `EXPLAIN indexes=1`, or `system.query_log` `SelectedParts` vs `SelectedPartsTotal`, that the
      statement selects fewer parts than the source holds, under the pipeline's own settings
      (`final=1`, `do_not_merge_across_partitions_select_final=1`).
- [x] 3.4 Ambiguity guard: a window that joins `dial_usage_log_payload` (same-named `request_time`)
      executes, and its key sub-selects carry the bound.
- [x] 3.5 Update any existing test that asserts rendered window SQL verbatim
      (`Class1MaterializeIntegrationTest`, `EnrichmentScanIntegrationTest`, aggregate refresh tests) for
      the added predicate.

## 4. Spec and docs

- [x] 4.1 Confirm the shipped behavior matches the `enrichment-runtime` delta requirement. No
      `docs/configuration.md` change: this change adds no property.
- [x] 4.2 Update the memory note "Cursor seek prunes nothing" to record that the fix shipped.

## 5. Verify

- [x] 5.1 `./gradlew clean build` passes (checkstyle + full test suite).
- [ ] 5.2 After deploy to a running deployment, re-run the query_log ranking by `normalized_query_hash`, and record the
      per-family drop in rows and bytes read against the baseline in the proposal.
