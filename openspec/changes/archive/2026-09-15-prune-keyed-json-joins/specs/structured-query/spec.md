## MODIFIED Requirements

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

**Where a statement declares the `object` paths it addresses, the derived table SHALL project those paths
and not the whole `object` column.** Where every expression in the statement that references an `object`
column addresses a *compile-time literal* path within it, the derived table SHALL project those paths as individual
subcolumns, each aliased to its own dotted path (`request_tags.baggage`) as a single quoted identifier, and
the outer statement SHALL address that alias. Where any reference addresses no statically known path, the
whole column SHALL be projected as before.

**A statement SHALL declare which paths it addresses, and one that declares nothing SHALL project whole
columns.** The paths are derived from the statement's own clauses and handed to the join, rather than
discovered by the join from the statement, because the join is emitted before those clauses are rendered
and is built from referenced *names*. A caller that renders a clause the declaration did not account for
would address a column its own join no longer projects, so the safe direction is explicit: no declaration
means no pruning, and the statement renders exactly as it did before this requirement existed. This is what
lets the rule be adopted per call site rather than everywhere at once.

The statements that declare their usage SHALL be the Class-1 materialize probe and its write. The query
surface, the enrichment scan, the group-fetch projection and the aggregate refresh SHALL declare none and
SHALL render exactly as they did before this requirement. Naming them is what makes the previous paragraph
falsifiable: "adopted per call site" with no list is a claim no reader can check.

A path key containing a dot SHALL fall back to the whole column. The alias joins the column name and the
path with dots into one identifier, so the single key `a.b` and the two-segment path `a`,`b` would collide
on `request_tags.a.b`; one derived table projecting both under one name resolves the outer reference to
whichever it chooses, and one of the two reads silently returns the other's value. The fallback SHALL be per column rather than per statement: one
unprunable reference costs its own column its pruning and no other's. The references that SHALL fall back
are `json_extract_string_ci`, `json_extract_array`, and any use of the column as a value in its own right —
a bare projection, a comparison, or an argument to a function taking the whole document.

Without this, keying a join makes an `object` column *more* expensive to read rather than less. ADAS
renders a single-path read of an `object` field as native subcolumn access, measured at ~20x less data than
the serialized form; a derived table projecting the whole column has materialized every path before that
expression runs, so the optimization survives only where the join is left unkeyed — the opposite of the
intent. The same single-path filter can return instantly through an unkeyed join and be unable to complete
a four-minute window through a keyed one.

An empty source-predicate set SHALL emit no restriction of either kind. A statement with no source
restriction has no value set to offer, and bounding an enrichment by an empty set would restrict it to
nothing.

This requirement exists because restricting a join to a grain key bounds what it *matches* without bounding
what it *reads*. Where an enrichment's grain key is uncorrelated with its partitioning — a hashed
`event_id` over daily partitions — a lookup for one key value lands a candidate granule in every part,
because the row genuinely might be in any of them, and the grain-key set cannot exclude one. That can cost
on the order of a GiB per heavy column to return a single row, on every path that reads
a body, keyed or unkeyed. The value that excludes the other partitions is the source's own partition
column, and a statement that identifies a row usually already carries it.

The restriction is only as sound as the declaration behind it, which is why nothing derives it from two
columns happening to share a name at query time. A false claim does not fail: it restricts the enrichment
to partitions that cannot hold the row, and the outer join then yields the column's type default —
indistinguishable from a row that legitimately has no enrichment.

#### Scenario: An aligned enrichment is bounded by the source's partition values

- **GIVEN** a source partitioned on `day` and an enrichment declaring `day` as its own partition column
- **AND** a query filtering on a source column alone and selecting an enrichment column
- **WHEN** the statement is rendered
- **THEN** the derived table carries
  `enrichment.day IN (SELECT source.day FROM source WHERE <filter>)`
- **AND** it also carries the grain-key restriction over the same predicates
- **AND** the join condition remains the single grain-key equality

#### Scenario: An enrichment that only co-stores the column name is not aligned

- **GIVEN** a source partitioned on `day` and an enrichment that holds a column named `day` but declares no
  partition column
- **WHEN** the statement is rendered
- **THEN** the derived table carries the grain-key restriction only
- **AND** no predicate names the enrichment's `day`

#### Scenario: Differently-named partition columns are not aligned

- **GIVEN** a source partitioned on `day` and an enrichment partitioned on `created_on`
- **WHEN** the statement is rendered
- **THEN** the join is keyed on the grain key alone

#### Scenario: A single-path reference prunes the derived table's projection

- **GIVEN** a statement that declares its object-path usage
- **AND** an enrichment holding an `object` column and a statement whose only reference to it is
  `json_extract_string(<column>, 'k')`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects `<column>.k` aliased as the single identifier `<column>.k`
- **AND** the whole `object` column does not appear in the derived table's projection
- **AND** the outer expression addresses that alias

#### Scenario: A case-insensitive reference falls back to the whole column

- **GIVEN** a statement that declares its object-path usage
- **AND** the same enrichment and a statement referencing it with `json_extract_string_ci`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects the whole `object` column, exactly as before

#### Scenario: One unprunable reference does not cost another column its pruning

- **GIVEN** a statement that declares its object-path usage
- **AND** a statement referencing one `object` column by a literal path and a second `object` column
  through `json_extract_array`
- **WHEN** the keyed join is rendered
- **THEN** the first column is projected as its addressed paths and the second as the whole column

#### Scenario: A statement that declares no object-path usage projects whole columns

- **GIVEN** a keyed join for an enrichment holding an `object` column
- **AND** a statement that addresses a literal path within it but declares no object-path usage
- **WHEN** the statement is rendered
- **THEN** the derived table projects the whole `object` column
- **AND** the outer statement addresses it as nested subcolumn segments, exactly as before this requirement

#### Scenario: A query with no source predicates is joined unkeyed

- **GIVEN** an aligned source and enrichment
- **AND** a query with no filter
- **WHEN** the statement is rendered
- **THEN** the enrichment is joined as a bare table with no restriction of either kind

## ADDED Requirements

### Requirement: The cost of reading a native JSON column scales with paths times parts

A reader of a native `JSON` (`object`) column through its serialized text SHALL be specified as costing one
read stream per JSON path per part, rather than as a cost per row. Registration of a pipeline whose
`filter` or output expressions read an `object` column through `json_extract_string_ci` or
`json_extract_array` SHALL report that choice back to the author, naming the cheaper single-path
alternative where the function has one, and SHALL NOT refuse it.

Reporting rather than refusing is the correct enforcement point because affordability is a property of the
table's physical layout, not of the declaration: the same expression is cheap on a compacted table and
ruinous on a fragmented one, and the layout changes under the declaration without the declaration
changing. A static refusal would reject affordable uses and still admit unaffordable ones, and
`json_extract_string_ci` is sometimes genuinely required, because the key's case is not always knowable.
The resource budget is what bounds the cost; registration's job is to make sure the author chose the
expensive form knowingly.

The previous characterisation — that these readers materialize the whole document per row — understates
the cost by the part count, which is what made the trap invisible. A column such as
`dial_usage_log_payload.request_tags` can hold only on the order of 100 MiB on disk in total, yet one part
carries hundreds of `request_tags.*` files (one pair per path) across hundreds of active parts; at ~4 MiB of
read buffer per stream a 2 GiB ceiling is exhausted by buffers alone. The cost is therefore independent of both the window and the data
volume: a window matching **zero** rows is refused identically to one matching every row.

#### Scenario: A case-insensitive read over an object column is reported at registration

- **GIVEN** a pipeline whose filter calls `json_extract_string_ci` over an `object` column
- **WHEN** it is registered or patched
- **THEN** the declaration is accepted
- **AND** the response reports that the expression reads the whole document, one stream per path per part,
  and names `json_extract_string` as the single-path alternative

#### Scenario: A single-path read over an object column is reported as nothing

- **GIVEN** a pipeline whose filter calls `json_extract_string` over an `object` column
- **WHEN** it is registered or patched
- **THEN** the declaration is accepted with no such report

#### Scenario: A case-insensitive read is never refused

- **GIVEN** a pipeline whose filter calls `json_extract_string_ci` over an `object` column whose part count
  would make the read exceed the configured ceiling
- **WHEN** it is registered
- **THEN** registration succeeds, and the cost is bounded at read time by the resource budget instead
