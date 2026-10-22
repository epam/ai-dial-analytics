## MODIFIED Requirements

### Requirement: Aggregate spec vocabulary

An `aggregate` pipeline's `spec` SHALL declare `group_by`, `measures`, and `freshness` over its input,
plus an optional `filter`. The declaration SHALL be engine-agnostic: it names no ClickHouse function and
no engine construct.

**`group_by`** SHALL be an ordered, non-empty list of key parts, each either:

- a plain **field of the input entity** — a declared column of the input source, or an exposed column
  of one of its ACTIVE enrichments addressed by its qualified flat name — or
- a **time bucket** `{trunc: {col, unit}, as: <name>}` over a declared `date`/`timestamp` column, with
  `unit` from the closed set `{hour, day, week, month}` (week starting Monday/ISO; month starting at
  midnight on the 1st). The bucketed column SHALL likewise be any `date`/`timestamp` field of the
  input entity.

**At most one** time bucket SHALL appear in a `group_by`. A `unit` outside the supported set SHALL be
rejected with HTTP 422 stating the supported units. The set is narrower than the truncation function the
query catalog offers: a bucket finer than an hour would carry about as many keys as the rows it
summarizes, and a refresh costs per dirty key. Widening it further is additive.

A `month` bucket SHALL be a **calendar** month, not a fixed number of days, and SHALL be compiled through
the catalog's calendar truncation rather than its fixed-width binning. The pruning bound a bucketed
refresh applies SHALL step by one calendar month accordingly, inside the statement, so the step past the
last dirty bucket stays in the same calendar the bucketing used.

A `month` bucket SHALL be bindable to a `date` or a `timestamp` target column: a month start is midnight
on the 1st, so either temporal type holds it without loss.

**`measures`** SHALL be a non-empty list of `{name, fn, column?, where?, distinct?}` entries. `fn` SHALL
come from the aggregate functions of the shared query function catalog (`count`, `sum`, `avg`, `min`,
`max`, `group_uniq_array`), `column` SHALL be a field of the input entity and SHALL be omitted only for
`count`. A `group_uniq_array` measure SHALL bind only to an `array` target column — it collects the
group's distinct non-null values as a set, sorted so that a recompute of unchanged rows writes the
identical value and the target's collapse stays a no-op — and that column's declared `element_type`
SHALL equal its input column's declared type, whose values the set holds unconverted (see "An array
measure's element type must match its input"). A measure SHALL be **one
function over one column**: no expression is permitted inside a measure. Per-row arithmetic SHALL be a
derived column on the input, and ratios or other expressions *over* measures SHALL be computed at query
time over the stored additive measures — never stored, because a ratio cannot be re-aggregated across
buckets.

A catalog aggregate requiring **more than one argument** SHALL be rejected with HTTP 422 naming the function
and its signature: a measure carries exactly one input column and cannot express such a call. No aggregate
currently in the catalog takes two, so this is a guard on future additions rather than a live restriction —
but it has to be enforced at declaration, because unrejected the declaration would validate, store and
enable, and the arity failure would then be raised inside every scheduled refresh, recorded only in
`state.last_error`. The rule SHALL be keyed on the catalog descriptor's required-argument count rather than
on a list of function names, so an addition is covered without a change here.

**`measures[].distinct`**, when `true`, SHALL deduplicate the values of the measure's input column before
the function is applied, so the measure counts or aggregates the group's *distinct* values rather than its
rows. It SHALL default to `false`, and a declaration that omits it SHALL compile exactly as it does
without the field — the quantifier is additive and changes no stored declaration's meaning.

`distinct` SHALL be accepted only for a function the shared catalog marks `distinct_supported`, and
rejected with HTTP 422 otherwise, naming the function. The acceptability SHALL be read from the catalog
descriptor rather than from a list of function names here, so the pipeline surface and the query surface
cannot disagree about the same function: the query DSL and the SQL front end already accept the quantifier
for `count`, `sum` and `avg` and already refuse it for `group_uniq_array`, whose result is the group's
distinct values already. A rejection for `group_uniq_array` SHALL say so rather than report a generic
unsupported-flag error, matching what the query surface answers.

`distinct: true` SHALL require a `column`. A `count` with no column is a row count, and there is no value
to deduplicate — accepting the pair would store a declaration whose only honest rendering is the
non-distinct one.

`distinct` SHALL compose with `where`: the predicate chooses which of the group's rows the measure reads,
and `distinct` deduplicates the values on those rows. The two are independent, and neither weakens the
one-function-over-one-column rule — `distinct` is a quantifier on the operand, not an expression over it.

`distinct` SHALL introduce no new nullability consequence, and "A nullable measure result requires a
nullable target column" SHALL apply to a distinct measure unchanged: a distinct `count` of nothing is `0`
exactly as a plain `count` of nothing is, and a distinct `sum`/`avg` over a group holding no non-null
value is NULL for the reason the rule already gives — its input column is nullable. The quantifier
deduplicates values that are already there; it cannot make a result NULL that would otherwise not be.

**`measures[].where`**, when present, SHALL be a predicate in the same bounded SQL-boolean grammar as
`filter`, over declared and entitled fields of the input entity, scoping *that measure* to a subset of its
group's rows. It SHALL be validated at declaration, and its referenced columns SHALL run the same
sensitive-column entitlement check `filter`'s do — a predicate reads its columns even though it derives
nothing from them, and without the check a measure predicate would be a way to test a protected value one
comparison at a time. It SHALL NOT narrow the rows the declaration's other measures read, and SHALL NOT
narrow the dirty-key set a refresh recomputes: a key whose only matching rows fall outside the window must
still be recomputed in full.

A `where` does **not** relax the one-function-over-one-column rule above. It is a row-*membership*
predicate — it chooses which of the group's rows the function reads, and the function still reads one
column of each. That distinction is what makes it additive rather than a loophole: the declarations it
enables have no other expressible form, because `filter` is one predicate over the whole declaration and
narrowing it narrows every measure with it. A whole-chain cost that must sum only the rows starting a
chain is the motivating case.

**A row-membership predicate is not a substitute for `distinct`**, and the two SHALL NOT be conflated: a
`where` selects rows by a property each row carries on its own, so it cannot express "one row per distinct
value of a column" — which row of a group is the representative is a property of the group, not of the
row. Counting the entities in a group whose grain is coarser than its input's row grain is therefore a
`distinct` count and nothing else. Attempting it with a predicate is not merely imprecise but can select
no row at all, silently reporting `0` for a group that holds many entities, when the rows the predicate
would have matched are rows the declaration's `filter` necessarily excludes.

**`filter`**, when present, SHALL be a predicate in the same bounded SQL-boolean grammar the query
façade and enrichment rules use, over declared and entitled fields of the input entity. It is optional in
general and **required** when a `group_by` key reads a nullable column — see "A nullable group key requires
a declared filter".

**`freshness`** SHALL declare `{mode}`. Only `periodic` is implemented. `incremental` SHALL be rejected
with HTTP 422 naming the deferred capability rather than silently downgraded, so a caller never believes
it has real-time materialization.

**`trigger`** SHALL be `{kind: "schedule", cron: <expression>}`. Any other trigger kind SHALL be
rejected with HTTP 422.

#### Scenario: A measure carrying an expression is rejected

- **WHEN** a `measures` entry supplies an expression instead of a single column
- **THEN** the request is rejected with HTTP 422 pointing at derived columns and query-time expressions

#### Scenario: A measure naming a multi-argument aggregate is rejected

- **WHEN** a `measures` entry names a catalog aggregate whose signature takes two required arguments
- **THEN** the request is rejected with HTTP 422 naming the function, its argument count and its signature,
  and stating that a measure carries exactly one input column

#### Scenario: A distinct count counts entities, not rows

- **GIVEN** an input whose rows are one hop and a group holding 1,200 hops across 40 distinct
  `trace_id` values
- **WHEN** a measure declares `fn: "count"`, `column: "trace_id"` and `distinct: true`
- **THEN** the measure writes `40`

#### Scenario: A distinct quantifier is accepted for a function the catalog marks distinct_supported

- **WHEN** a measure declares `distinct: true` with `fn` of `count`, `sum` or `avg` and a `column`
- **THEN** the declaration is accepted

#### Scenario: A distinct quantifier on a function that does not support it is rejected

- **WHEN** a measure declares `distinct: true` with `fn` of `min` or `max`
- **THEN** the request is rejected with HTTP 422 naming the function, and the decision is taken from the
  catalog descriptor's `distinct_supported` rather than from a list of names in this capability

#### Scenario: A distinct quantifier on an array measure is rejected as redundant

- **WHEN** a measure declares `fn: "group_uniq_array"` with `distinct: true`
- **THEN** the request is rejected with HTTP 422 stating that the function already returns the group's
  distinct values, the same answer the query surface gives for the equivalent `DISTINCT`

#### Scenario: A distinct count with no column is rejected

- **WHEN** a measure declares `fn: "count"` and `distinct: true` but omits `column`
- **THEN** the request is rejected with HTTP 422 stating that a distinct count needs a column to
  deduplicate

#### Scenario: A distinct measure composes with its own predicate

- **GIVEN** a measure declaring `fn: "count"`, `column: "trace_id"`, `distinct: true` and
  `where: "success = true"`
- **WHEN** a refresh compiles the statement
- **THEN** the measure counts the distinct `trace_id` values of the group's rows satisfying the predicate,
  and a group whose rows all fail it writes `0`

#### Scenario: An omitted distinct quantifier changes nothing

- **GIVEN** a pipeline declared before this capability accepted `distinct`
- **WHEN** a refresh compiles its statement
- **THEN** every measure renders exactly as it did before, since an absent `distinct` is `false`

#### Scenario: A measure predicate scopes only its own measure

- **GIVEN** a declaration whose `chain_price` measure declares `where: "parent_deployment IS NULL"` and
  whose `total_tokens` measure declares none
- **WHEN** a refresh compiles the statement
- **THEN** `chain_price` aggregates only the group's rows satisfying the predicate, `total_tokens` aggregates
  every row of the group, and the predicate appears in neither half's `WHERE` clause

#### Scenario: A malformed measure predicate is rejected at declaration

- **WHEN** a `measures[].where` fails the bounded SQL-boolean grammar, or names a field the input entity
  does not publish
- **THEN** the request is rejected with HTTP 422, rather than stored and left to fail inside every refresh

#### Scenario: A measure predicate over a sensitive column the caller may not read is refused

- **WHEN** a `measures[].where` references a sensitive input column the calling identity is not entitled to
- **THEN** the request is refused with HTTP 403, the same as for a `filter` over that column

#### Scenario: An unsupported bucket unit is rejected

- **WHEN** a `group_by` time bucket declares a `unit` outside `{hour, day, week, month}`
- **THEN** the request is rejected with HTTP 422 stating the supported units

#### Scenario: A month bucket produces calendar months

- **WHEN** a pipeline groups by a `month` time bucket over a timestamp column
- **THEN** each bucket starts at UTC midnight on the first of its month, months of differing lengths each
  form exactly one bucket, and the refresh's pruning bound steps by one calendar month

#### Scenario: A month bucket bound to a date target column is accepted

- **WHEN** a `month` bucket's `as` name is bound to a target column declared `date`
- **THEN** the declaration is accepted, since a month start carries no time-of-day to lose

#### Scenario: Two time buckets are rejected

- **WHEN** a `group_by` declares two `trunc` parts
- **THEN** the request is rejected with HTTP 422

#### Scenario: Incremental freshness is rejected, not downgraded

- **WHEN** a pipeline declares `freshness: {mode: "incremental"}`
- **THEN** the request is rejected with HTTP 422 and no pipeline is stored

#### Scenario: An unknown aggregate function is rejected

- **WHEN** a measure names a function outside the catalog's aggregate group
- **THEN** the request is rejected with HTTP 422

#### Scenario: A group key naming an enrichment column is accepted

- **WHEN** a `group_by` key names an ACTIVE enrichment's exposed column by its qualified flat name, and
  the pipeline declares a `filter`
- **THEN** the declaration is accepted, `inputs` still naming the source the enrichment hangs off

#### Scenario: An unknown qualified name names the enrichment it was resolved against

- **WHEN** a declaration references `"<enrichment>.<column>"` where the enrichment exists but publishes no
  such column
- **THEN** the request is rejected with HTTP 422 naming the enrichment as well as the column, so a typo in
  either half is diagnosable

### Requirement: A nullable measure result requires a nullable target column

A measure whose result can be NULL SHALL require a nullable target column, or the declaration SHALL be
rejected with HTTP 422 naming both the measure's target column and the input column that makes the result
nullable.

A measure result can be NULL in **three** independent ways, and the rule SHALL apply if any holds.

The first is a nullable **input column**: `sum`, `avg`, `min` and `max` over a nullable column all return
NULL for a group that contains no non-null value. This determination SHALL come from the input column's
declared nullability rather than from the function, because the function alone cannot distinguish `sum` over
a non-nullable column (never NULL) from `sum` over a nullable one (NULL for an all-null group).

The second is a declared **`where`**: a measure scoped to part of its group returns NULL whenever *no* row
of a group satisfies the predicate, and that is independent of the aggregated column's nullability. So a
declared `where` SHALL require a nullable target column on its own — `sum(deployment_price) where
parent_deployment IS NULL` over a group whose rows are all mid-chain has nothing to sum, and a non-nullable
target would store the zero that reads as "this chain was free".

The third is an **enrichment-sourced input column**. An enrichment is reached through an outer grain
join, so a source row with no matching enrichment row yields NULL for every one of that enrichment's
columns **whatever their declared nullability**. A measure over an enrichment column SHALL therefore be
treated as nullable regardless of what the enrichment's catalog row declares — the declaration describes
the value when the row exists, and the join decides whether it exists. Reading the declared flag alone
here would defeat the rule in exactly the case it was written for: a non-nullable enrichment column would
pass the gate, bind to a non-nullable target, and store `0` for every source row the enrichment has not
reached.

`count` never returns NULL and is therefore never subject to this rule. Neither is `group_uniq_array`, which
skips NULLs and answers an all-NULL group with an empty array; for it the exemption is also the only workable
rule, since ClickHouse has no `Nullable(Array)` and an array target column therefore cannot be declared
nullable at all — keying the rule on the input alone would leave such a measure with no declarable target.
Both exemptions SHALL survive scoping, and for the same reasons: a scoped `count` of nothing is 0 and a
scoped `group_uniq_array` of nothing is the empty set, each the honest answer rather than a fabricated one.

The rule exists because ClickHouse does not refuse a NULL written to a non-nullable column — it converts
it to the column's type default and stores **`0`**. The rollup then reports a zero that a consumer cannot
distinguish from a measured zero: "there was no data for this key" and "this key's total was zero" are
different facts, and only the second one survives. Nothing in the target records which it was, and nothing
fails, so the error is discoverable only by recomputing against the source.

This is the same failure mode, and the same remedy, as the fractional-measure rule in target shape
governance: an engine that coerces on insert rather than refusing turns a declaration mistake into a
plausible-looking number. The rules are siblings and SHALL both be enforced at registration and re-applied
at execution, so a column that becomes nullable after a pipeline is registered stops the refresh instead
of silently changing what the stored zeros mean.

#### Scenario: A nullable measure bound to a non-nullable target column is rejected

- **WHEN** a `sum`, `avg`, `min` or `max` measure reads a nullable input column and the target column it
  writes is declared non-nullable
- **THEN** the request is rejected with HTTP 422 naming the target column and the nullable input column,
  because ClickHouse would store the type default and the rollup would read as a measured zero

#### Scenario: A nullable measure bound to a nullable target column is accepted

- **WHEN** the same measure's target column is declared nullable
- **THEN** the declaration is accepted, and a group with no non-null value stores NULL rather than a
  default

#### Scenario: A scoped measure bound to a non-nullable target column is rejected

- **WHEN** a `sum`, `avg`, `min` or `max` measure declares a `where` and the target column it writes is
  declared non-nullable — **even though its input column is non-nullable**
- **THEN** the request is rejected with HTTP 422 naming the target column and the scoping, because a group in
  which no row matches has nothing to aggregate and the stored default would read as a measured zero

#### Scenario: A count measure is exempt

- **WHEN** a `count` measure is bound to a non-nullable target column, with or without a `where`
- **THEN** the declaration is accepted, because `count` cannot return NULL

#### Scenario: An array measure over a nullable input is exempt

- **WHEN** a `group_uniq_array` measure reads a nullable input column and writes an `array` target column
- **THEN** the declaration is accepted, because the function skips NULLs and yields an empty array rather
  than NULL, and an array column cannot be declared nullable in the first place

#### Scenario: A measure over a non-nullable input is exempt

- **WHEN** a `sum` measure reads a non-nullable input column and writes a non-nullable target column
- **THEN** the declaration is accepted, because the result cannot be NULL

#### Scenario: A measure over an enrichment column requires a nullable target

- **WHEN** a `sum` measure reads a nullable enrichment column and the target column it writes is declared
  non-nullable
- **THEN** the request is rejected with HTTP 422 naming the target column and the enrichment column,
  because the grain join is outer, so a group can hold rows and still have nothing to sum

### Requirement: A nullable group key requires a declared filter

A `group_by` key — plain column or time bucket — whose input column is **nullable** SHALL be rejected with
HTTP 422 unless the pipeline declares a `filter`, naming the key, the nullable input column, and the
consequence. An **enrichment-sourced** group key SHALL count as nullable for this rule whatever its
declared nullability, because the grain join is outer and decides the matter independently of the flag.
Note that this makes the rule total rather than doing the whole job: a group key may only read an
enrichment column that is *declared* nullable in the first place (see **An enrichment-sourced value is
readable only where absence is representable**), so the join and the declaration agree. The rule SHALL be
enforced at registration and re-applied at execution, like its measure sibling above.

A NULL reaching a group key is silently wrong in one of two ways, and which one depends on the key's form:

- **A plain column** — NULL is its own group, and the target cannot represent it. The group-key output names
  *are* the target's `ordering_key`, and ClickHouse refuses a nullable sorting key, so no target shape
  exists that could hold the value. The engine stores the column's type default instead — an empty string, a
  zero, an epoch date — and the rollup carries a key indistinguishable from measured data.
- **A time bucket** — NULL cannot be bucketed. The pruning bound is a comparison on the raw column, so every
  row missing that value drops out of the recompute and out of the rollup, whose totals then stop
  reconciling with the input with nothing recording why.

For an enrichment-sourced key the consequence is the more severe of the two: the row leaves the rollup
entirely rather than being mis-keyed, and — unlike a NULL in a source column — the value may arrive
later, at which point nothing re-dirties the group. The `filter` this requirement demands is the
declaration-time half of that guard; the runtime half — clamping a refresh window to what its enrichments
have been materialized through — is specified by `aggregate-enrichment-freshness`.

This is the nullable-measure failure met a third time — an engine that coerces rather than refusing turns a
declaration mistake into plausible output — but with that rule's remedy unavailable: a measure can be told
to declare its target column nullable, and a group key cannot.

**A declared `filter` SHALL be accepted as the answer, and the predicate SHALL NOT be parsed for it.**
Inferring whether an arbitrary boolean excludes NULLs is something this gate would do badly, and doing it
badly means either refusing a correct declaration or admitting a wrong one. What the gate can establish is
whether the declarer considered the question at all, and a pipeline carrying no membership predicate has
not. So `filter: "col IS NOT NULL"` — the honest fix — keeps working, while the declaration that silently
ignores the NULLs is refused. Rejecting a nullable key outright is the alternative and is worse: it would
leave the correct declaration with no way to be expressed.

The two forms are held to one rule deliberately. A bucket dropping rows is not obviously better than a
plain key fabricating one, and accepting the bucket while refusing the plain column would split the same
defect on a distinction that has nothing to do with it.

#### Scenario: A nullable plain group key with no filter is rejected

- **WHEN** a pipeline groups by a nullable input column and declares no `filter`
- **THEN** the request is rejected with HTTP 422 naming the group key and the nullable input column, because
  the NULL group would be stored as the target column's type default and read as real data

#### Scenario: A nullable bucket column with no filter is rejected

- **WHEN** a pipeline's `group_by` time bucket reads a nullable date/timestamp column and no `filter` is
  declared
- **THEN** the request is rejected with HTTP 422, because those rows can be bucketed into nothing and would
  silently leave the rollup

#### Scenario: A declared filter admits the nullable group key

- **WHEN** the same pipeline declares any `filter`
- **THEN** the declaration is accepted, the predicate is not inspected for null-handling, and the filter
  stands as the declarer's statement that the NULLs are accounted for

#### Scenario: A non-nullable group key needs no filter

- **WHEN** every `group_by` key reads a non-nullable input column
- **THEN** the declaration is accepted with no `filter`, because no NULL can reach a key

#### Scenario: The rule is re-applied at execution

- **WHEN** a measure's input column becomes nullable after the pipeline was registered and a refresh runs
- **THEN** the refresh is refused and the failure recorded, rather than writing type defaults into a
  target column that cannot represent the absence of a value

#### Scenario: An enrichment group key with no filter is rejected

- **WHEN** a pipeline groups by an enrichment column declared non-nullable and declares no `filter`
- **THEN** the request is rejected with HTTP 422 naming the key and the enrichment it is drawn from,
  because the grain join can yield NULL for it regardless of the declaration

### Requirement: Sensitive-column governance on the aggregate path

The system SHALL apply the per-column `sensitive` rule to every input column a pipeline references — in
`filter`, `group_by`, and `measures` — at registration **and** again at execution, against the acting
identity. A pipeline referencing a sensitive input column SHALL be refused for a caller not entitled to
it, with HTTP 403. The rule SHALL follow the grain join: a `sensitive` column of an ACTIVE enrichment
reached through the input source SHALL be governed exactly as a `sensitive` column of the source itself,
in both halves of this requirement.

A target column derived from a `sensitive` input column SHALL itself be declared `sensitive`, or the
declaration SHALL be rejected with HTTP 422. This applies to every group key and every measure regardless
of function: a `sum` over a group of one is that row's value, so exempting the additive functions would
leave the same exposure behind a narrower door.

Extending both halves across the join is a condition of this requirement holding, not an addition to it.
The scheduled refresh's elevation to a `sensitive`-entitled identity is justified by the declaration-time
half being total — see "Scheduled refreshes execute under a system identity", which names this rule as
what closes the exposure the elevation would otherwise create. A sensitive enrichment column that could
be aggregated into a plain target column would make that justification false, and the elevation would
launder protected values by design.

Without these, a rollup would be a laundering path: `min`/`max` over a sensitive column, or a sensitive
column used as a group key, would copy protected values into a target column carrying no `sensitive`
flag, readable by anyone who can read the rollup. The entitlement check alone does not close it, because a
scheduled refresh runs under a system identity (see below) that is entitled.

#### Scenario: A sensitive column aggregated into an unprotected target column is rejected

- **WHEN** a pipeline's measure or group key reads a `sensitive` input column and the target column it
  writes is not declared `sensitive`
- **THEN** the request is rejected with HTTP 422, whatever the registering caller's entitlement

#### Scenario: A measure over a sensitive column is refused for a non-entitled caller

- **WHEN** a non-entitled caller registers a pipeline whose measure reads a `sensitive` input column
- **THEN** the request is rejected with HTTP 403 and no pipeline is stored

#### Scenario: A sensitive group key is refused for a non-entitled caller

- **WHEN** a non-entitled caller registers a pipeline grouping by a `sensitive` input column
- **THEN** the request is rejected with HTTP 403

#### Scenario: Governance is re-applied at execution

- **WHEN** a column referenced by a stored pipeline becomes `sensitive` after registration and a refresh
  runs on behalf of a non-entitled caller
- **THEN** the refresh is refused rather than writing derived values from that column

#### Scenario: A sensitive enrichment column aggregated into an unprotected target column is rejected

- **WHEN** a pipeline's measure or group key reads a `sensitive` column of an enrichment reached through
  its input source, and the target column it writes is not declared `sensitive`
- **THEN** the request is rejected with HTTP 422 naming both columns, whatever the registering caller's
  entitlement

#### Scenario: A non-entitled caller may not reach a sensitive column through the join

- **WHEN** a non-entitled caller registers a pipeline referencing a `sensitive` enrichment column
- **THEN** the request is refused with HTTP 403 and no pipeline is stored, the same as for a sensitive
  column of the source

## ADDED Requirements

### Requirement: An aggregate pipeline reads its input entity, not only its input table

A pipeline's `inputs` SHALL name a **source**. An enrichment SHALL NOT be declarable as an input, and a
declaration naming one SHALL be rejected with HTTP 422 saying so. An enrichment is reached *through* its
source's grain join, never named directly — the shape the read path and the enrichment rule surface
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
and no measure can be inflated by one. An enrichment no reference touches SHALL contribute no join, so a
pipeline referencing none SHALL compile exactly the statements it compiles today.

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

### Requirement: An enrichment-sourced value is readable only where absence is representable

A `group_by[].column`, a `group_by[].trunc.col` and a `measures[].column` SHALL resolve to an enrichment
column only where that column is declared **nullable**, and SHALL otherwise be rejected with HTTP 422
naming the column, its enrichment, and the reason. A `filter` and a `measures[].where` SHALL NOT be subject
to the rule. An `array` or `map` enrichment column SHALL be exempt.

This is the rule the rest of this capability's enrichment nullability reasoning depends on, and it exists
because that reasoning is otherwise false. Every other rule here says "the grain join is outer, so an
unreached input row carries NULL". That holds only for a column the catalog declares nullable: ClickHouse's
`join_use_nulls` setting defaults to `0`, and an unmatched outer join renders a non-nullable right-hand
column as its **type default** — an empty string, a zero, `false`, the epoch — not as NULL. Every mitigation
built on the NULL premise then misses the case it was written for: the `filter` a nullable group key
requires excludes nothing when spelled `IS NOT NULL`, the nullable target column a measure requires receives
a zero rather than a NULL, `avg` and `min` absorb one type default per unreached row, and a bucketed key's
pruning bound is dragged back to the epoch. The declaration SHALL therefore be refused rather than accepted
and silently mismeasured, because no downstream rule can recover the distinction once the value is read.

A predicate SHALL be exempt for the same reason predicates are exempt from the sensitivity-propagation rule:
it selects rows rather than producing a value, and `"<enrichment>.<column>" != ''` is the correct and only
expression of "exclude the rows this enrichment has not reached" for a column that cannot be nullable.
Refusing predicates would remove the one sound workaround along with the unsound declarations.

`array` and `map` SHALL be exempt because they are unconditionally non-nullable by construction (ClickHouse
has no `Nullable(Array)` or `Nullable(Map)`), so the rule would refuse them permanently — and it need not:
their type default is *empty*, which contributes nothing to the only aggregate that accepts them and cannot
be a group key at all, so for them absence and emptiness are equivalent by declaration rather than by
accident.

#### Scenario: A non-nullable enrichment column is refused as a group key

- **WHEN** a `group_by` key names an enrichment column declared `nullable: false`
- **THEN** the request is rejected with HTTP 422 naming the column, its enrichment, and that an unmatched
  outer join would render it as its type default rather than as NULL

#### Scenario: A non-nullable enrichment column is refused as a measure column

- **WHEN** a `measures[].column` names an enrichment column declared `nullable: false`
- **THEN** the request is rejected with HTTP 422 on the same grounds

#### Scenario: A non-nullable enrichment column may still be read by a predicate

- **WHEN** a `filter` or a `measures[].where` names an enrichment column declared `nullable: false`
- **THEN** the declaration is accepted, because a predicate selects rows rather than producing a value

#### Scenario: An array enrichment column is exempt

- **WHEN** a `group_uniq_array` measure reads an `array` enrichment column, which cannot be declared nullable
- **THEN** the declaration is accepted, because an unmatched join yields an empty array, which contributes
  nothing

### Requirement: A group key over an enrichment column must declare an alias

A plain `group_by` key naming an enrichment column SHALL declare `as`, and SHALL otherwise be rejected with
HTTP 422 naming the column and the alias it needs.

The requirement is a consequence, not a preference. A key with no `as` takes its output name from the column
it reads, and that output name is the target column it binds to and a member of the target's `ordering_key`;
a qualified flat name carries a `.`, which no target column name may. So an unaliased enrichment key can
never bind to any target. Left unstated the declaration still fails, but it fails against the target's
ordering key — pointing the declarer at the target they wrote correctly rather than at the alias they
omitted. A time bucket needs no such rule: it already requires `as` for every column it reads.

#### Scenario: An unaliased enrichment group key is rejected

- **WHEN** a `group_by` key names an enrichment column by its qualified flat name and declares no `as`
- **THEN** the request is rejected with HTTP 422 naming the column and suggesting the bare column name as
  the alias

### Requirement: Re-running an enrichment over history invalidates its dependent rollups

A rollup SHALL be treated as invalid until rebuilt where an enrichment it reads has had its values
recomputed for source rows the pipeline already aggregated — a rule's definition changed, or a backfill
re-derived history. The capability SHALL document how to rebuild one **using the operations it already
has**, and SHALL NOT introduce a rebuild operation of its own: deleting a pipeline and re-registering it
resets the cursor, since a pipeline is stored with none and a delete leaves the target's rows intact; and
where the re-run changed values that were already aggregated, deleting and recreating the target removes
the keys that no longer occur, taking the pipeline with it by cascade.

The refresh-window clamp that `aggregate-enrichment-freshness` specifies guards an enrichment value
arriving *late*. It does nothing for one that *changes*, and the two failures look nothing alike. A source row whose enrichment value moves from `A` to
`B` does not move its own cursor position, so no window revisits it; the target keeps its row for `A`
indefinitely, because an upsert-by-key target can replace a key's row but cannot delete one whose key no
longer occurs, and `B` never appears. The rollup is then wrong in both directions at once, with nothing
recording it.

This obligation SHALL be stated in the capability rather than left to operator folklore, because nothing
in the system can detect the condition: the enrichment write is indistinguishable from any other, and the
pipeline has no way to know that a value it already read has since changed.

#### Scenario: Re-registering a pipeline re-reads its whole input

- **WHEN** an operator deletes a pipeline and registers the same declaration again
- **THEN** the new pipeline carries no cursor, so its next refresh re-reads its input from the beginning
  and rewrites every group from current enrichment values

#### Scenario: A re-run that only filled absent values needs no target change

- **GIVEN** an enrichment re-run that filled values which had been absent, changing none that were
  already aggregated
- **WHEN** the pipeline is re-registered
- **THEN** every group is rewritten and no stale key remains, because a key that never occurred was
  never written

#### Scenario: A re-run that changed aggregated values needs the target recreated

- **GIVEN** an enrichment re-run that moved an already-aggregated source row from one key to another
- **WHEN** the operator recreates the target table and re-registers the pipeline
- **THEN** the superseded key is gone, because an upsert-by-key target can replace a key's row but
  cannot delete one whose key no longer occurs

#### Scenario: The obligation is documented against the enrichment surface

- **WHEN** an operator re-runs or backfills an enrichment that a pipeline reads
- **THEN** the capability states that the dependent rollups are invalid until rebuilt, and which of the
  two rebuilds above the re-run calls for

### Requirement: The cost of an enrichment-joined refresh is documented

The read cost an enrichment join adds to a refresh SHALL be measured and documented as a property of the
declaration, in the same place the unbucketed-grain cost is documented, so that whoever declares an
enrichment-fed rollup can weigh it before enabling one.

The cost is not incidental. A hash join reads its right-hand table in full to build its hash table and
does not push the left side's keys down into it, so an enrichment-joined refresh reads the whole
enrichment on every tick, at every statement carrying the join. The recompute half cannot be bounded
against this by construction: it must see every row of every dirty key, so the set of grain keys it needs
from the enrichment is not bounded by the window the way the dirty-key select's is. This is the cost
class the bucketed pruning bound exists to avoid on the input side, arriving on the other side of the
join — and a declaration that is cheap against a small enrichment is not cheap against a large one.

Documenting SHALL NOT be read as accepting any cost: where the measurement shows an enrichment-joined
refresh is not viable at the sizes the capability expects, that is a finding to act on rather than a
number to publish.

#### Scenario: The cost is stated where a declarer will find it

- **WHEN** an operator consults the capability's cost guidance before declaring a rollup over an
  enrichment column
- **THEN** the guidance states that each such refresh reads the referenced enrichment in full, and that
  the cost scales with the enrichment's size rather than with the window's
