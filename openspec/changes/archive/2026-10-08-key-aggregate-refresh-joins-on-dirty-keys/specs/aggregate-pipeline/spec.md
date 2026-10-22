## MODIFIED Requirements

### Requirement: An aggregate pipeline reads its input entity, not only its input table

A pipeline's `inputs` SHALL name a **source**. An enrichment SHALL NOT be declarable as an input, and a
declaration naming one SHALL be rejected with HTTP 422 saying so. An enrichment is reached *through* its
source's grain join, never named directly — the shape the read path and the enrich declaration surface
already state.

Every field reference a declaration makes — `group_by[].column`, `group_by[].trunc.col`,
`measures[].column`, `filter` and `measures[].where` — SHALL resolve against the input's **flat entity
field set**: the source's exposed columns plus the exposed columns of each of its ACTIVE enrichments,
enrichment columns addressed by the qualified flat name (`"<enrichment>.<column>"`). This SHALL be the
same field set `GET /v1/queries/entities/{name}` publishes and the same spelling the query DSL, the SQL
front end and Flight resolve, so a rollup's declaration and a query over the same entity name a column
identically. No pipeline-specific naming scheme SHALL be introduced.

The compiled statements SHALL join an enrichment when, and only when, a reference to one of its exposed
columns survives into that statement. The join SHALL be the entity model's own grain join — a `LEFT JOIN`
on `source.grain_key = enrichment.grain_key`, cardinality 0..1, so no join can multiply the input's rows
and no measure can be inflated by one. Each join SHALL be keyed on the statement's source-only conjuncts
— the window predicates for the probe and the dirty-key sub-selects, plus the membership filter when it
names no enrichment column; for the recompute and the preview, the membership filter when it names no
enrichment column, and no keying otherwise — so the enrichment side is restricted to the grain keys
those conjuncts select rather than hashed whole. An enrichment no reference touches SHALL contribute no
join, so a pipeline referencing none SHALL compile exactly the statements it compiles today.

**The recompute SHALL instead restrict, by the window's dirty keys, an enrichment that every group key
reads.** When every group key — a plain column or a time bucket — reads a column of one and the same
enrichment, and each of those columns is declared nullable, the recompute SHALL emit that enrichment as a
derived table whose `WHERE` is the dirty-key restriction itself: the group-key tuple `IN` the same
dirty-key sub-select the outer `WHERE` applies. That restriction SHALL replace the enrichment's keying
rather than accompany it. The derived table SHALL carry no grain-key or partition restriction, because
both are drawn from source predicates and the dirty keys already select a superset of the rows the
recompute reads.

The restriction is not keying in the sense of **A keyed grain join is bounded by an aligned partition
column**. Every other case SHALL compile the recompute it compiles today:
- a grain mixing source and enrichment keys;
- a grain reading two enrichments;
- a grain of source columns only;
- an enrichment that no group key reads, joined beside a restricted one;
- a group key over an enrichment column that is not nullable (an `array`).

Without the restriction, the dirty-key `IN` applies only after the join, and the hash join is built from the
whole enrichment on every refresh. Its memory then grows with the enrichment's size rather than with the
window's (see **The cost of an enrichment-joined refresh is documented**).

The restriction SHALL narrow nothing the recompute aggregates.
- **A dirty key's rows survive.** Every input row of a dirty key reaches an enrichment row whose key is
  dirty, so that row survives the restriction.
- **Every other row stays excluded.** An input row whose enrichment row lies outside the dirty keys was
  already excluded by the outer dirty-key `IN`. With the restriction it reads as unmatched instead, and it
  stays excluded:
  - each restricted group key is declared nullable (see **An enrichment-sourced value is readable only
    where absence is representable**), so an unmatched row reads NULL;
  - a NULL never satisfies `IN` while `transform_null_in = 0`.
- **The restriction is applied after version collapsing.** The query-level `final = 1` collapses the
  enrichment's `ReplacingMergeTree` versions before the derived table's `WHERE` runs, so a superseded
  version carrying another key neither enters nor leaves the restricted set.

Every pipeline statement SHALL therefore run with `transform_null_in = 0`, so that exactness does not
depend on a deployment's settings profile.

**Every statement a refresh or preview composes SHALL carry the joins its own references require**, not
only the statement that produces the rollup's rows. A refresh composes several independent statements
over the input — the window probe, the recompute select, the dirty-key sub-select, and the pruning
bounds a bucketed declaration derives — and preview composes its sample besides. These are not
correlated sub-queries: a reference to an enrichment column standing in one of them without its join is
unresolvable where it stands, and fails the refresh rather than skewing it. Which statements a reference
reaches follows from where it was declared: a `filter` reaches all of them, a group key reaches the
recompute, the dirty-key select and the bounds, and a measure reaches only the recompute.

The join emission SHALL be the same mechanism the read path uses rather than a second implementation, so
the two cannot drift into disagreeing about what a column name means or about when a join fires.

#### Scenario: An enrichment named as an input is rejected

- **WHEN** a pipeline declares an enrichment table in `inputs`
- **THEN** the request is rejected with HTTP 422 stating that an input must be a source and that an
  enrichment is reached through its source

#### Scenario: A rollup grouped by an enrichment column is registered and refreshes

- **GIVEN** a source with an ACTIVE enrichment publishing a column, and a target keyed on it
- **WHEN** the pipeline is registered with that qualified name as its group key and a `filter` excluding
  its NULLs, then previewed, enabled and refreshed
- **THEN** the declaration is accepted and the refresh writes rows keyed by the enrichment's values

#### Scenario: A referenced enrichment is joined in every statement that needs it

- **WHEN** a declaration references an enrichment column in its `filter`
- **THEN** the window probe, the recompute select, the dirty-key sub-select, the pruning bounds and the
  preview sample each carry the grain join, and none of them references the enrichment unjoined

#### Scenario: An untouched enrichment contributes no join

- **GIVEN** a source carrying ACTIVE enrichments
- **WHEN** a pipeline references none of their columns
- **THEN** no statement it compiles contains a join, and the SQL is what the same declaration compiled
  before enrichment reads were possible

#### Scenario: The pipeline path and the query path agree on a column's value

- **WHEN** a rollup aggregates an enrichment column and a structured query reads the same column of the
  same entity over the same rows
- **THEN** the two report the same values

#### Scenario: A rollup grouped by an enrichment column joins only its dirty keys' enrichment rows

- **GIVEN** a pipeline whose only group key reads a nullable enrichment column
- **WHEN** a refresh runs over a window that dirties a few keys of a large enrichment
- **THEN** the recompute's join is built from that enrichment's rows for those keys only, and the target
  receives the same rows and measures an unrestricted recompute would write

#### Scenario: A time bucket over an enrichment column restricts by the bucket

- **GIVEN** a pipeline whose only group key is a time bucket over a nullable enrichment column
- **WHEN** its refresh is compiled
- **THEN** that enrichment's derived table is restricted by the bucket expression `IN` the dirty-key
  sub-select, and the recompute writes the same rows an unrestricted one would

#### Scenario: An input row outside the dirty keys is still excluded

- **GIVEN** a pipeline whose group key reads an enrichment column, and input rows in the dirty keys' range
  that have no enrichment row, an enrichment row with a NULL key, or an enrichment row whose key is not dirty
- **WHEN** a refresh runs
- **THEN** none of those rows contributes to any recomputed key, exactly as without the restriction, and
  the refresh write runs with `transform_null_in = 0`

#### Scenario: A superseded enrichment version does not move a row between keys

- **GIVEN** an enrichment row whose latest version carries a key outside the dirty keys and whose
  superseded version carries a dirty key
- **WHEN** a refresh runs
- **THEN** the row contributes to no dirty key, exactly as without the restriction

#### Scenario: A restriction carries no grain-key keying

- **GIVEN** a pipeline grouped by a nullable enrichment column whose `filter` names source columns only
- **WHEN** its refresh is compiled
- **THEN** the restricted derived table carries the dirty-key restriction alone, with no
  `grain_key IN (SELECT … FROM source …)` and no partition restriction

#### Scenario: Other grains compile as before

- **GIVEN** a pipeline grouped by a source column and an enrichment column, or by source columns only with
  a `filter` naming an enrichment column
- **WHEN** its refresh is compiled
- **THEN** the recompute's joins are emitted exactly as before this restriction existed, and carry no
  dirty-key restriction

#### Scenario: An enrichment no group key reads is joined as before

- **GIVEN** a pipeline grouped by a nullable column of one enrichment and measuring a column of a second
- **WHEN** its refresh is compiled
- **THEN** the first enrichment is restricted by the dirty keys and the second is joined exactly as before

### Requirement: The cost of an enrichment-joined refresh is documented

The read cost an enrichment join adds to a refresh SHALL be measured and documented as a property of the
declaration, in the same place the unbucketed-grain cost is documented, so that whoever declares an
enrichment-fed rollup can weigh it before enabling one.

The cost is not incidental. A hash join reads its right-hand table in full to build its hash table and does
not push the left side's keys down into it. So an enrichment join that nothing restricts is built from the
whole enrichment on every tick, at every statement carrying it. The window probe, the dirty-key sub-select and
the bucket bounds are keyed on the window predicates. What the recompute costs depends on the enrichment:

- **An enrichment every group key reads.** The recompute restricts it by the dirty keys (see **An aggregate
  pipeline reads its input entity, not only its input table**), so its join follows the window rather than
  the enrichment's size. What bounds it is the window's row limit (`aggregate.window-limit`): a wider window
  dirties more keys and builds a larger join. For example, a rollup grouped by
  `usage_client_identity.client_session_id` can build a join of millions of rows and use well over a GiB,
  roughly a third of a GiB per million enrichment rows, to write a handful of rows. Restricted, it builds
  thousands of rows, uses a few hundred MiB and writes the same rows.
- **Any other enrichment the recompute joins.** This covers grains mixing source and enrichment keys, source
  grains with an enrichment `filter` or measure, and a second enrichment read only by measures. The join is
  keyed at most by a source-only `filter`, which the window does not bound. With no `filter`, or one naming
  an enrichment column, it is built from the whole enrichment on every tick. The recompute cannot be bounded
  against this by construction: it must see every row of every dirty key, so the set of grain keys it needs
  from such an enrichment is not bounded by the window the way the dirty-key select's is. This is the cost
  class the bucketed pruning bound exists to avoid on the input side, arriving on the other side of the
  join. A declaration that is cheap against a small enrichment is not cheap against a large one.

The input side keeps its own cost either way. An unbucketed grain still reads the whole input on every
refresh (see **Scheduled refresh recomputes every affected group key in full**), and a restricted enrichment
is still scanned in full to evaluate its restriction, because its key column is not in its sort key. Both
reads stream, so they cost time rather than join memory.

Documenting SHALL NOT be read as accepting any cost: where the measurement shows an enrichment-joined
refresh is not viable at the sizes the capability expects, that is a finding to act on rather than a
number to publish.

#### Scenario: The cost is stated where a declarer will find it

- **WHEN** an operator consults the capability's cost guidance before declaring a rollup over an
  enrichment column
- **THEN** the guidance states that an enrichment every group key reads is joined for its dirty keys' rows
  only, so its join cost follows the window
- **AND** it states that any other enrichment the recompute joins is read in full unless a source-only
  `filter` keys it, at a cost that scales with the enrichment's size rather than with the window's
