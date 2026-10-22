## MODIFIED Requirements

### Requirement: Read result deduplication (latest-version-wins)

The system SHALL return query results as the deduplicated, latest-version-wins view of the underlying
data. For any table backed by a `ReplacingMergeTree` (an `upsert_by_key` source and every joined
enrichment), results SHALL reflect only the surviving (latest-version) row per sort key, exactly as if
a physical merge had completed, regardless of whether ClickHouse has actually merged the parts yet. The
system SHALL achieve this by applying ClickHouse `FINAL` semantics to every read the query pipeline issues,
via the query-level `final = 1` setting attached at execution time rather than by rewriting the
rendered SQL. Because the setting is applied to the executed statement, it SHALL apply uniformly to
the source table, all LEFT-JOINed enrichment tables, and the separate `count(*)` total-count query.
The setting SHALL be a no-op for tables whose engine does not support `FINAL` (an `append` source's
plain `MergeTree`), leaving their results unchanged. Deduplication SHALL be global and
always-on; there is no per-query opt-out. This guarantee is part of the shared execution pipeline and
therefore SHALL hold identically for the structured DSL, the REST SQL endpoint, and the Flight SQL /
ADBC endpoint, which all delegate to it.

**The engine decides, and the catalog SHALL describe the engine.** Because the setting is resolved by
ClickHouse against the table's actual engine rather than against the catalog, this guarantee holds
whatever the catalog says — which means a table whose declared `write` discipline disagrees with its
engine keeps returning correct rows while the catalog misdescribes why. The `write` facet SHALL therefore
agree with the engine (see [table-management](../table-management/spec.md) "Table write discipline"), so
that "which rows does a read return" has one answer reachable from either.

The requirement is on the *description*, not on the results, because the description is what an operator
reasons from: a `ReplacingMergeTree` declared `append` presents a legitimate engine-level collapse as an
unexplained shortfall against raw SQL, and rules out the only correct reading of it. The mechanism is
deliberately left resolving against the engine, so it stays right even when the catalog is wrong — the
one direction in which these two can disagree without any read returning a wrong row.

#### Scenario: Source with unmerged duplicate rows returns one row per key

- **WHEN** a `ReplacingMergeTree` source (e.g. `dial_usage_log`) holds multiple not-yet-merged
  versions sharing its sort key and a row-mode query selects them
- **THEN** the response returns only the latest version per sort key, not the raw unmerged duplicates

#### Scenario: Total count reflects deduplicated rows

- **WHEN** a row-mode offset query with `include_total` runs against a source that has unmerged
  duplicate rows
- **THEN** the returned `totalCount` counts each sort key once (the deduplicated count), matching the
  deduplicated row set rather than the raw part-level row count

#### Scenario: Re-patched enrichment does not fan out source rows

- **WHEN** a query references an enrichment column and that enrichment has multiple unmerged versions
  for a grain key that matches source rows
- **THEN** each matching source row appears exactly once, joined to the latest enrichment version,
  because the enrichment side is deduplicated under the same `FINAL` semantics

#### Scenario: Recomputed upsert-by-key source row reads as the latest value

- **WHEN** an `upsert_by_key` source receives a second write for a key it already holds (a rollup
  bucket recomputed with a new value) and the parts have not merged
- **THEN** a query returns one row for that key carrying the later write's values, so a re-written key
  reads as a replacement rather than as two rows

#### Scenario: Append source is unaffected

- **WHEN** a query runs against an `append` source (a plain `MergeTree`, no version-collapsing engine)
- **THEN** the `final = 1` setting is a no-op and every row the source holds is returned

#### Scenario: The declared discipline predicts what a read returns

- **WHEN** a caller reads a source's `write` facet and then queries that source
- **THEN** an `upsert_by_key` source returns one row per ordering key and an `append` source returns every
  row, so the declaration accounts for any difference between an ADAS read and a raw `SELECT`

