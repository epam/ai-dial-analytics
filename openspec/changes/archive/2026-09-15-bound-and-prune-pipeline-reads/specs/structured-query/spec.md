## ADDED Requirements

### Requirement: A keyed grain join is bounded by an aligned partition column

A keyed enrichment join SHALL restrict the enrichment side by the source's partition values, in addition to
its grain keys, whenever the catalog declares the enrichment's partition column to be aligned with its
source's. The restriction SHALL be
`enrichment.partition_column IN (SELECT source.partition_column FROM source WHERE <source predicates>)`,
emitted inside the derived table's `WHERE` alongside the grain-key restriction and drawn from the same
predicates.

Alignment SHALL be declared, never inferred. Both definitions SHALL declare a partition column and the two
SHALL be the **same column name**; the shared name is the whole of the claim, namely that for a given grain
key both tables hold the same value in that column. A source that declares no partitioning, an enrichment
that declares none, and a pair whose columns are named differently SHALL all be emitted exactly as before,
keyed on the grain key alone. Partition **granularity** SHALL NOT be compared: the restriction is a set of
column values, so it prunes correctly whatever expression either table partitions that column by.

The restriction SHALL be placed in the derived table rather than on the join condition. Both prune the
scan, but only the inner form also narrows what the derived table projects, and projecting a heavy column
across every part is the cost being removed.

An empty source-predicate set SHALL emit no restriction of either kind. A statement with no source
restriction has no value set to offer, and bounding an enrichment by an empty set would restrict it to
nothing.

This requirement exists because restricting a join to a grain key bounds what it *matches* without bounding
what it *reads*. Where an enrichment's grain key is uncorrelated with its partitioning — a hashed
`event_id` over daily partitions — a lookup for one key value lands a candidate granule in every part,
because the row genuinely might be in any of them, and the grain-key set cannot exclude one. That can be
on the order of 1 GiB per heavy column to return a single row, on every path that reads
a body, keyed or unkeyed. The value that excludes the other partitions is the source's own partition
column, and a statement that identifies a row usually already carries it.

The restriction is only as sound as the declaration behind it, which is why nothing derives it from two
columns happening to share a name at query time. A false claim does not fail: it restricts the enrichment
to partitions that cannot hold the row, and the outer join then yields the column's type default —
indistinguishable from a row that legitimately has no enrichment.

#### Scenario: An aligned enrichment is bounded by the source's partition values

- **Given** a source partitioned on `day` and an enrichment declaring `day` as its own partition column
- **And** a query filtering on a source column alone and selecting an enrichment column
- **When** the statement is rendered
- **Then** the derived table carries
  `enrichment.day IN (SELECT source.day FROM source WHERE <filter>)`
- **And** it also carries the grain-key restriction over the same predicates
- **And** the join condition remains the single grain-key equality

#### Scenario: An enrichment that only co-stores the column name is not aligned

- **Given** a source partitioned on `day` and an enrichment that holds a column named `day` but declares no
  partition column
- **When** the statement is rendered
- **Then** the derived table carries the grain-key restriction only
- **And** no predicate names the enrichment's `day`

#### Scenario: Differently-named partition columns are not aligned

- **Given** a source partitioned on `day` and an enrichment partitioned on `created_on`
- **When** the statement is rendered
- **Then** the join is keyed on the grain key alone

#### Scenario: A query with no source predicates is joined unkeyed

- **Given** an aligned source and enrichment
- **And** a query with no filter
- **When** the statement is rendered
- **Then** the enrichment is joined as a bare table with no restriction of either kind
