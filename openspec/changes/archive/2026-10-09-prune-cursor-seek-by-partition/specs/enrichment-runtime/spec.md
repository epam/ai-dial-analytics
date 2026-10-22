## ADDED Requirements

### Requirement: A cursor-bounded read on a partitioned source reads only the partitions its window can reach

A cursor-bounded pipeline read SHALL restrict its source to the partitions its window can reach, when the source's catalog definition declares a `partition_column`.
A cursor-bounded read is one that bounds its source from below by the cursor tuple or by a caller window. "Bounds from below by the cursor tuple" means the keyset seek
`(version, identity) > (v, id)`. "Bounds from below by a caller window" means `version > from`. The reads
covered are the pipeline scan, the Class-1 materialize probe and its write, the aggregate refresh probe
and its dirty-key sub-select, and every grain-join key sub-select built from the same source predicates.

The restriction SHALL be a predicate on the source's `partition_column`, qualified by the source table.
It requires that column to be at least the minimum `partition_column` value among the source's rows whose
version is at or after the bound. That minimum SHALL be derived **inside the same statement**, from the
data, by a sub-select that does not deduplicate (`final=0`). It SHALL NOT be a fixed lag, a value computed
in the service, or an assumption about how late rows arrive. Rows reach the source arbitrarily late
relative to their partition value, through re-ingest and backfill, so any assumed bound would skip rows.

The restriction SHALL be exact: it SHALL NOT change any observable outcome. That covers:

- the rows a scan page returns, its `nextCursor` and `hasMore`;
- the window a Class-1 or aggregate probe fixes, and the rows that window writes;
- `rows_written`, `has_more` and the stored cursor.

It holds because every row the seek admits has a version at or after the bound, so its partition value is
at or above the derived minimum. A deduplicated read (`final=1`) keeps a key's newest version, and that
version is either such a row or is excluded by the seek anyway.

A source that declares no `partition_column` SHALL render exactly the statement it rendered before this
requirement. The upper bounds (`atMost`, `version <= to`) and the safety lag gain no derived predicate.

The obvious alternative is a plain lower bound on the version column. It is not sufficient and SHALL NOT
be relied on instead. Under the pipeline's `final=1` read, ClickHouse expands a skip-index selection back
to every part whose primary-key range intersects it. On `dial_usage_log`, whose sort key leads with
`(project_id, deployment)`, that restores the full table.

#### Scenario: A window statement no longer reads the whole partitioned source

- **WHEN** a pipeline probes or writes a window on `dial_usage_log` whose cursor sits near the newest
  ingested rows
- **THEN** the statement reads only the partitions holding rows at or after the cursor's version, plus
  the minimum sub-select's own read, rather than every row of the table

#### Scenario: The bound reaches enrichments keyed through the source predicates

- **WHEN** a window statement joins `dial_usage_log_payload`, whose grain-join key sub-selects reuse the
  source's cursor predicates
- **THEN** those key sub-selects are restricted by the same bound, so the payload side is read only for
  the reachable partitions

#### Scenario: The bound changes no result

- **WHEN** the same scan page, probe, or write is executed once with the bound and once without it, at
  the same cursor, horizon and window
- **THEN** both return identical rows, the same end tuple and `has_more`, and write identical rows

#### Scenario: A late row with an old partition value is still read

- **WHEN** a row whose `partition_column` value is days older than any other row in the window is
  ingested after the cursor's version
- **THEN** the derived minimum moves back to include that row's partition, and the row appears in the
  next window exactly once

#### Scenario: An empty window still reports nothing to do

- **WHEN** no source row has a version at or after the bound
- **THEN** the read returns no rows and the cursor is unchanged, exactly as without the bound

#### Scenario: An unpartitioned source is unaffected

- **WHEN** a cursor-bounded read runs on a source whose catalog definition declares no `partition_column`
- **THEN** the rendered statement carries no partition predicate and is unchanged from before this
  requirement
