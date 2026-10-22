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

The statements that declare their usage SHALL be the Class-1 materialize probe and its write, and the
query surface: the single-entity read statement (including the page block and the outer statement of a
late-materialized read), its `include_total` count statement, and every block of a composite SQL statement.
The enrichment scan, the group-fetch projection and the aggregate refresh and its preview SHALL declare none
and SHALL render exactly as they did before this requirement. Naming them is what makes the previous paragraph
falsifiable: "adopted per call site" with no list is a claim no reader can check.

A query-surface statement SHALL derive its usage from every clause it renders against the joined enrichments:
its `select` expressions, `filter`, `having`, `sort` keys and `group_by` keys, and the enrichment conjuncts and
scalar calls that read shaping pushes into a derived table (their copies in the outer statement address the
same paths). A `sort` or `group_by` key naming an `object` column, and an `object` column projected bare, SHALL
make that column whole. Every expression the outer statement renders SHALL address a pruned path through its
alias, and an expression rendered *inside* the derived table (a pushed conjunct's `WHERE` copy, a pushed call)
SHALL keep addressing the enrichment's own column, which is in scope there. The count statement SHALL derive
its usage from the clauses it renders, which is its filter.

On the query surface this is what keeps a keyed join cheap for a path read:
`json_extract_string(jwt_claims, 'email')` grouped over 30 days went from a 2 GiB memory refusal to about a
second and under 1 GiB, and the same path as a filter roughly halved in time and memory, returning the same
rows.

A path key containing a dot SHALL fall back to the whole column. The alias joins the column name and the
path with dots into one identifier, so the single key `a.b` and the two-segment path `a`,`b` would collide
on `request_tags.a.b`; one derived table projecting both under one name resolves the outer reference to
whichever it chooses, and one of the two reads silently returns the other's value. The fallback SHALL be per column rather than per statement: one
unprunable reference costs its own column its pruning and no other's. The references that SHALL fall back
are `json_extract_string_ci` and any use of the column as a value in its own right —
a bare projection, a comparison, or an argument to a function taking the whole document.
`json_extract_array` SHALL NOT fall back: it addresses a compile-time literal path like the four scalar
extractors, so a statement whose only reference to an `object` column is an array extraction SHALL prune
that column's projection.

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

#### Scenario: An array extraction prunes the derived table's projection

- **GIVEN** a statement that declares its object-path usage
- **AND** an enrichment holding an `object` column and a statement referencing it with
  `json_extract_array(<column>, 'roles')` and `json_extract_string(<column>, 'sub')`
- **WHEN** the keyed join is rendered
- **THEN** the derived table projects `<column>.roles` and `<column>.sub` as aliased subcolumns
- **AND** the whole `object` column does not appear in the derived table's projection
- **AND** neither reference costs the other its pruning

#### Scenario: One unprunable reference does not cost another column its pruning

- **GIVEN** a statement that declares its object-path usage
- **AND** a statement referencing one `object` column by a literal path and a second `object` column
  through `json_extract_string_ci`
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

#### Scenario: A query-surface path read prunes its keyed derived table

- **GIVEN** a query over a source with a source-only filter that groups by
  `json_extract_string(<object column>, 'email')` of an enrichment
- **WHEN** the statement is rendered
- **THEN** the enrichment's derived table projects `<object column>.email` aliased as that single identifier and
  not the whole `object` column
- **AND** the outer projection and `GROUP BY` address that alias
- **AND** the result equals the result of the same statement rendered with whole columns

#### Scenario: A column referenced whole and by path stays whole on the query surface

- **GIVEN** a query that filters on `json_extract_string(<object column>, 'email')` and also projects the bare
  `<object column>`
- **WHEN** the statement is rendered
- **THEN** the derived table projects the whole `object` column and the filter addresses it as nested subcolumn
  segments

#### Scenario: A pushed enrichment conjunct keeps its inner copy on the real column

- **GIVEN** a query whose filter has a pushed enrichment conjunct beside a literal-path read of an `object`
  column of the same enrichment
- **WHEN** the statement is rendered
- **THEN** the derived table's `WHERE` copy of the conjunct addresses the enrichment's own columns
- **AND** every reference in the outer statement to the pruned path addresses its alias

#### Scenario: The count statement prunes as its filter reads

- **GIVEN** an offset-paged query with `include_total` whose filter reads `json_extract_string(<object column>,
  'email')` through a keyed join
- **WHEN** the count statement is rendered
- **THEN** its derived table projects `<object column>.email` and not the whole column

#### Scenario: The aggregate refresh still declares no usage

- **GIVEN** an aggregate pipeline whose block reads `json_extract_string(<object column>, 'email')` through a
  restricted grain join
- **WHEN** its refresh statement is compiled
- **THEN** the derived table projects the whole `object` column, exactly as before this change

## ADDED Requirements

### Requirement: A composite join's operand-only conjuncts restrict that operand

When a composite statement's block reads from a join, every top-level conjunct of the block's `WHERE` that
references columns of exactly one operand of that join, where that operand is an entity reference, SHALL be
evaluated inside that operand's own block instead of above the join. It then confines the rows that operand
reads, and it keys that operand's enrichment joins exactly as a single-entity filter does: its source-only
conjuncts key every join, and a conjunct reaching into an enrichment is kept beside them.

A conjunct SHALL move only where removing it from above the join cannot change the result:

- into **either** operand of an `INNER` join or of a `CROSS` join;
- into the **preserved** operand of an outer join only: the left operand of a `LEFT` join and the right operand
  of a `RIGHT` join;
- into **neither** operand of a `FULL` join.

A conjunct SHALL NOT move into the null-supplying operand of an outer join. There it would decide which rows are
null-extended instead of which joined rows are kept. It SHALL stay above the join.

A conjunct SHALL stay above the join when:

- it references no column;
- it references columns of both operands;
- it reaches through a nested join;
- it contains a subquery predicate, a scalar subquery or a lambda.

Only the operands of the join the block reads directly SHALL receive conjuncts; a join nested inside an operand
is left as it is. The `ON` condition and the `HAVING` clause SHALL NOT move.

A moved conjunct is the same predicate over the same row before the join instead of after it, and it is not
applied twice. The statement's rows SHALL be exactly those it returned before this requirement.

#### Scenario: An inner self-join's bounded side is restricted in its own block

- **WHEN** a caller posts
  `SELECT b."usage_client_identity.user_email" AS parent_user, count(*) AS c FROM dial_usage_log a JOIN dial_usage_log b ON a.trace_id = b.trace_id AND a.core_parent_span_id = b.core_span_id WHERE a.request_time >= TIMESTAMP '2026-10-13 00:00:00' AND a.request_time < TIMESTAMP '2026-10-14 00:00:00' GROUP BY 1 ORDER BY c DESC LIMIT 20`
- **THEN** both `request_time` conjuncts are rendered in the `WHERE` of `a`'s operand block and not above the
  join
- **AND** the result equals that of the statement rendered without the move

#### Scenario: A LEFT join moves a conjunct into its preserved side only

- **GIVEN** `a LEFT JOIN b ON a.k = b.k WHERE a.x = 1 AND b.y = 2`
- **WHEN** the statement is rendered
- **THEN** `a.x = 1` is rendered in `a`'s operand block
- **AND** `b.y = 2` stays above the join

#### Scenario: A RIGHT join moves a conjunct into its preserved side only

- **GIVEN** `a RIGHT JOIN b ON a.k = b.k WHERE a.x = 1 AND b.y = 2`
- **WHEN** the statement is rendered
- **THEN** `b.y = 2` is rendered in `b`'s operand block
- **AND** `a.x = 1` stays above the join

#### Scenario: A FULL join moves nothing

- **GIVEN** `a FULL JOIN b ON a.k = b.k WHERE a.x = 1 AND b.y = 2`
- **WHEN** the statement is rendered
- **THEN** both conjuncts stay above the join and neither operand block has a `WHERE`

#### Scenario: A conjunct over both operands stays above the join

- **GIVEN** `a JOIN b ON a.k = b.k WHERE a.x = b.y`
- **WHEN** the statement is rendered
- **THEN** `a.x = b.y` stays above the join

### Requirement: An equi-join operand is reduced to the keys its restricted partner can produce

When one entity operand of a composite join (the **restricted** side) receives moved conjuncts of which at least
one references its source columns alone, and the join's `ON` condition has a top-level equality between a bare
source column of each side, the other entity operand (the **reduced** side) SHALL also be restricted inside its
own block to
`reduced.k IN (SELECT restricted.k FROM <restricted source table> WHERE <the restricted side's moved source-only conjuncts>)`.
`k` is the first such equality in the `ON` condition. The restriction also keys the reduced side's enrichment joins,
as a source-only conjunct of its own filter would.

The restriction SHALL be built from the restricted side's moved **source-only** conjuncts only. It SHALL never
include a conjunct reaching into an enrichment, which would need that enrichment joined inside the key subquery.
Like the grain-join key subquery, it carries no `FINAL` modifier of its own. Under the same predicates, the raw key set is a superset of the
deduplicated one, so it never excludes a key the restricted side can produce.

A side SHALL be reduced only where the join discards that side's unmatched rows anyway:

- either side of an `INNER` join;
- the null-supplying side of an outer join, reduced by the preserved side's keys: the right operand of a `LEFT`
  join and the left operand of a `RIGHT` join;
- never the preserved side of an outer join, and neither side of a `FULL` or `CROSS` join.

A reduction SHALL go one hop. A side that is restricted only by a reduction SHALL NOT itself reduce its partner,
and no reduction SHALL be carried through a nested join. When both sides of an `INNER` join carry moved
source-only conjuncts, each SHALL be reduced by the other's own moved conjuncts.

A reduction SHALL NOT change the statement's rows. A row of the reduced side whose key the restricted side cannot
produce has no partner, so the join drops it in every allowed position. A `NULL` key never satisfies the
equality, so excluding it changes nothing.

Without the reduction, an operand with no bound of its own reads its whole source, and hashes the whole of every
enrichment it joins, whatever the other side selects. Measured, the inner self-join scenario below went from a
2 GiB memory refusal (unchanged by the move alone) to well under a second and a few hundred MiB, returning
the rows of an independently written reference.

#### Scenario: The unbounded side of a self-join is reduced by the bounded side's keys

- **GIVEN** the self-join of "An inner self-join's bounded side is restricted in its own block"
- **WHEN** the statement is rendered
- **THEN** `b`'s operand block carries
  `trace_id IN (SELECT trace_id FROM dial_usage_log WHERE <a's two request_time conjuncts>)`
- **AND** `b`'s identity enrichment join is keyed by that restriction
- **AND** the statement completes inside the query budget, with the rows of the statement rendered without the
  reduction

#### Scenario: A LEFT join reduces its null-supplying side only

- **GIVEN** `a LEFT JOIN b ON a.k = b.k WHERE a.x = 1`
- **WHEN** the statement is rendered
- **THEN** `b`'s operand block carries `k IN (SELECT k FROM <a's source> WHERE x = 1)`
- **AND** a row of `a` with no partner in `b` is still returned, null-extended

#### Scenario: The preserved side of an outer join is never reduced

- **GIVEN** `a LEFT JOIN b ON a.k = b.k WHERE b.y = 2`
- **WHEN** the statement is rendered
- **THEN** neither operand block carries a restriction and `b.y = 2` stays above the join

#### Scenario: An enrichment conjunct does not build a reduction

- **GIVEN** `a JOIN b ON a.k = b.k WHERE a."<enrichment>.col" = 'x'`
- **WHEN** the statement is rendered
- **THEN** the conjunct moves into `a`'s operand block
- **AND** `b` is not reduced, because `a` carries no moved source-only conjunct

#### Scenario: NULL and duplicate keys keep the unreduced result

- **GIVEN** a restricted side whose keys include `NULL` and repeated values, and an INNER join with that side
- **WHEN** the statement is executed with and without the reduction
- **THEN** both return the same rows, with the same multiplicity
