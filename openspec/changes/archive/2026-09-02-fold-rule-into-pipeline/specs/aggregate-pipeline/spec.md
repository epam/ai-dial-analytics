## REMOVED Requirements

### Requirement: Pipeline registry object

**Reason**: The registry object is kind-independent, and documenting it inside one of its kinds is what made
two registries look like two concepts. It moves to `pipeline-registry`, where the `enrich` kind now shares
it — including the parts this requirement stated as arguments rather than as constraints: "because an
`aggregate` pipeline targets a `source` while an enrichment `rule` targets an `enrichment`, the two
registries can never contend for the same table" is replaced by a `UNIQUE (target)` that holds across kinds.

**Migration**: `pipeline-registry` → "Pipeline registry object". The input-scannability rule moves with it;
the requirement that an input be a materialized `source` declaring a `version_column`/`identity_column` pair
is unchanged. The `kind` vocabulary gains `enrich` as an implemented member, so a declaration naming it is
no longer refused.

### Requirement: Pipeline runtime state is readable

**Reason**: State is a registry-level projection, not an aggregate one, and after the fold one shape serves
both kinds — with the members that do not apply to a kind absent rather than reported empty. Keeping it here
would leave the enrich half of the same projection documented in a different capability.

**Migration**: `pipeline-registry` → "Pipeline runtime state is readable". Every scenario carries over. Two
additions the merge forces: the ADAS-owned cursor and schedule members are absent for a kind whose cursor the
Enrichment Runner owns, and the published `unclamped_reads` reason set names all four codes (with `no_rule`
renamed to `no_pipeline`) rather than the two this requirement listed.

### Requirement: Aggregate pipeline errors use the shared error contract

**Reason**: One registry serves one set of codes. This requirement's own rule — "a code is what a client
switches on and a borrowed one points at an object that does not exist" — is the reason it cannot stay
per-kind now that both kinds answer on `/v1/pipelines`.

**Migration**: `pipeline-registry` → "Pipeline errors use the shared error contract". The aggregate codes are
unchanged; the enrichment registry's are renamed onto them (`unknown_rule` → `unknown_pipeline`,
`rule_validation_failed` → `pipeline_validation_failed`, `rule_access_denied` → `pipeline_access_denied`),
and its 409 stops reporting a *validation* code — a state conflict is `pipeline_conflict`, which the
aggregate path already answered. The preview budget code and its 422-not-503 reasoning carry over unchanged.

## RENAMED Requirements

- FROM: `### Requirement: Register, preview, enable lifecycle`
- TO: `### Requirement: Dry-run preview of an aggregate declaration`

## MODIFIED Requirements

### Requirement: Aggregate spec vocabulary

An `aggregate` pipeline's `spec` SHALL declare `trigger.group_by`, `measures`, and `freshness` over its input,
plus an optional `filter`. The declaration SHALL be engine-agnostic: it names no ClickHouse function and
no engine construct.

**`trigger.group_by`** SHALL be an ordered, non-empty list of key parts, each either:

- a plain **field of the input entity** — a declared column of the input source, or an exposed column
  of one of its ACTIVE enrichments addressed by its qualified flat name — or
- a **time bucket** `{trunc: {col, unit}, as: <name>}` over a declared `date`/`timestamp` column, with
  `unit` from the closed set `{hour, day, week, month}` (week starting Monday/ISO; month starting at
  midnight on the 1st). The bucketed column SHALL likewise be any `date`/`timestamp` field of the
  input entity.

**At most one** time bucket SHALL appear in a `trigger.group_by`. A `unit` outside the supported set SHALL be
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
`state.last_error`. The pipeline SHALL be keyed on the catalog descriptor's required-argument count rather than
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
value is NULL for the reason the pipeline already gives — its input column is nullable. The quantifier
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
façade and enrich declarations use, over declared and entitled fields of the input entity. It is optional in
general and **required** when a `trigger.group_by` key reads a nullable column — see "A nullable group key requires
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

- **WHEN** a `trigger.group_by` time bucket declares a `unit` outside `{hour, day, week, month}`
- **THEN** the request is rejected with HTTP 422 stating the supported units

#### Scenario: A month bucket produces calendar months

- **WHEN** a pipeline groups by a `month` time bucket over a timestamp column
- **THEN** each bucket starts at UTC midnight on the first of its month, months of differing lengths each
  form exactly one bucket, and the refresh's pruning bound steps by one calendar month

#### Scenario: A month bucket bound to a date target column is accepted

- **WHEN** a `month` bucket's `as` name is bound to a target column declared `date`
- **THEN** the declaration is accepted, since a month start carries no time-of-day to lose

#### Scenario: Two time buckets are rejected

- **WHEN** a `trigger.group_by` declares two `trunc` parts
- **THEN** the request is rejected with HTTP 422

#### Scenario: Incremental freshness is rejected, not downgraded

- **WHEN** a pipeline declares `freshness: {mode: "incremental"}`
- **THEN** the request is rejected with HTTP 422 and no pipeline is stored

#### Scenario: An unknown aggregate function is rejected

- **WHEN** a measure names a function outside the catalog's aggregate group
- **THEN** the request is rejected with HTTP 422

#### Scenario: A group key naming an enrichment column is accepted

- **WHEN** a `trigger.group_by` key names an ACTIVE enrichment's exposed column by its qualified flat name, and
  the pipeline declares a `filter`
- **THEN** the declaration is accepted, `inputs` still naming the source the enrichment hangs off

#### Scenario: An unknown qualified name names the enrichment it was resolved against

- **WHEN** a declaration references `"<enrichment>.<column>"` where the enrichment exists but publishes no
  such column
- **THEN** the request is rejected with HTTP 422 naming the enrichment as well as the column, so a typo in
  either half is diagnosable


### Requirement: Target shape governance

The target of an `aggregate` pipeline SHALL be an existing, materialized (`ACTIVE`) `source` table whose
write discipline is `upsert_by_key`. A target that is unknown, an `enrichment`, not yet materialized, or
written `append` SHALL be rejected with HTTP 422 — an `append` target would accumulate one row per
refresh instead of replacing the recomputed key, silently multiplying every measure.

The target's `ordering_key` SHALL equal the pipeline's `trigger.group_by` output names, **in order**. The rollup's
grain is therefore not declared twice and cannot drift from the pipeline that fills it.

Being a `source`, the target MAY declare the optional scan-metadata pair, which is what makes the rollup
itself scannable by an enrich pipeline. It SHOULD do so **only when its `trigger.group_by` yields a single
column**: the target collapses by its whole `ordering_key`, so with a multi-column grain no single column
identifies a row, and `identity_column` could only name part of the key. That is not a cosmetic
inaccuracy — one refresh stamps a single `now64(3)` into `_updated_at` for every row it writes, so rows
sharing that one key member are indistinguishable to the keyset seek and all but one are silently skipped.
A single-column-grain rollup declares `identity_column` = its group key and `version_column` =
`_updated_at`, which the refresh restamps on every recomputed key, so an enrich pipeline sourced on the rollup re-reads
exactly the keys the last refresh touched.

Every `trigger.group_by` output name and every `measures[].name` SHALL resolve to a declared column of the
target, and the column's declared type SHALL accept the part's or function's result type. A target column
that no `trigger.group_by` part or measure writes SHALL be rejected, since it could only ever hold a default.

For a group key the type rule is exact rather than merely compatible: a plain key's target column SHALL be
declared the same type as the input column it groups. A group key is the input value itself, not an
aggregate of it, so a mismatch is a bad declaration — and left to execution it either surfaces as an engine
rejection attributed to the wrong cause, or is silently coerced.

A **time bucket's** target column SHALL be a temporal type wide enough for the bucket's own width: an
`hour` bucket SHALL require `timestamp`, while a `day` or `week` bucket (which starts at midnight) MAY be
either `date` or `timestamp`. An `hour` bucket bound to a `date` column is the worst case of the whole
binding, and the reason the pipeline is per-unit rather than merely "temporal": the bucket start is truncated to
midnight on insert, and because the bucket is part of the target's `ordering_key` the engine then collapses
all 24 of a day's rows onto one arbitrary survivor. There is no error and no duplicate row — 23 buckets
simply do not exist.

A measure whose result carries a fraction SHALL require a `decimal` target column: `avg` always, and `sum`
over a `decimal` input column. The shared catalog types `avg` and `sum` alike (both `numeric`), so this rule
cannot come from the return type — and ClickHouse converts rather than refusing on insert, which would store
a truncated mean or total as an exact whole number. `min`/`max` need no such rule: they return the input
value itself.

**Which name addresses which side.** An `input` column — `group_by[].column`, `group_by[].trunc.column`,
`measures[].column`, and any column in `filter` — is named by its **exposed** catalog name, the same
spelling the query DSL and the filter grammar use, because that is what the compiled `SELECT` resolves
against. A `target` column — `group_by[].as` and `measures[].name` — is named by its **physical** name,
because that is what the `INSERT` column list and the target's `ordering_key` are. Validating either side
against the other's spelling would accept declarations that cannot compile, and reject ones that can, on any
aliased column.

A `system`-owned target SHALL reject pipeline registration, consistent with every other mutation of a
system table.

#### Scenario: An append target is rejected

- **WHEN** a pipeline's `target` is a `source` whose write discipline is `append`
- **THEN** the request is rejected with HTTP 422 explaining that a rollup target must be
  `upsert_by_key`

#### Scenario: A target whose ordering key differs from group_by is rejected

- **WHEN** the target's `ordering_key` is `(day, deployment)` and `trigger.group_by` yields
  `(deployment, day)`
- **THEN** the request is rejected with HTTP 422 reporting both key lists

#### Scenario: A measure with no matching target column is rejected

- **WHEN** a `measures` entry names `cost` and the target declares no `cost` column
- **THEN** the request is rejected with HTTP 422

#### Scenario: A type-incompatible target column is rejected

- **WHEN** a `count` measure is bound to a target column declared `string`
- **THEN** the request is rejected with HTTP 422

#### Scenario: A group key bound to a differently-typed target column is rejected

- **WHEN** a plain group key over a `string` input column is bound to a target column declared `long`, or a
  time bucket is bound to a target column that is neither `date` nor `timestamp`
- **THEN** the request is rejected with HTTP 422

#### Scenario: An hour bucket bound to a date target column is rejected

- **WHEN** a `trigger.group_by` time bucket with `unit: "hour"` is bound to a target column declared `date`
- **THEN** the request is rejected with HTTP 422, because the hour would be truncated on insert and the
  day's 24 bucket rows would collapse onto one under the target's `ordering_key`

#### Scenario: A day bucket bound to a date target column is accepted

- **WHEN** a `trigger.group_by` time bucket with `unit: "day"` or `"week"` is bound to a target column declared
  `date`
- **THEN** the declaration is accepted, because such a bucket starts at midnight and loses nothing

#### Scenario: A fractional measure bound to an integer target column is rejected

- **WHEN** an `avg` measure, or a `sum` over a `decimal` input column, is bound to a target column declared
  `long` or `integer`
- **THEN** the request is rejected with HTTP 422, because ClickHouse would truncate the fraction on insert
  and the rollup would read as an exact whole number

#### Scenario: An unwritten target column is rejected

- **WHEN** the target declares a column that no group key or measure writes
- **THEN** the request is rejected with HTTP 422


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

### Requirement: Dry-run preview of an aggregate declaration

The system SHALL expose `POST /v1/pipelines/{name}/preview` — the **dry-run gate** for an `aggregate`
declaration. Preview SHALL NOT write to the target and SHALL be permitted on a disabled pipeline, so the
declaration can be checked before it is trusted. It is the reason an `aggregate` pipeline is always
registered disabled (see [pipeline-registry](../pipeline-registry/spec.md), "Register and enable
lifecycle"): the gate only means something if nothing runs before it.

Preview SHALL return **two artefacts that answer different questions**, and the response SHALL make both
legible:

- **The refresh statement.** The compiled `INSERT … SELECT` a refresh would execute *now*, carrying the
  window a refresh would apply — the membership filter, the safety lag, and the seek past any stored
  cursor — omitting only the probed window's end tuple, which no probe has yet chosen. The dirty-bucket
  range of a time-bucketed declaration is *not* omitted: a real run does not marshal it either, so it
  renders here as the same self-resolving bound, differing only in that its window is un-pinned. A
  statement rendered with no window at all would describe a recompute of the entire input, which is not
  what an enabled refresh runs, and is dangerous to copy out of a response.
- **The sample.** A bounded sample of what the declaration produces over the input **as a whole**,
  deliberately *not* window-restricted. The question a sample answers is "are these the right numbers",
  and a window obscures it: a caller cannot judge a declaration from whatever slice happens to remain
  unread. The sample SHALL be accompanied by its **own** rendered statement, so each half of the response
  carries its provenance.

The two SHALL NOT be presented as though one produced the other. Their divergence is total in the ordinary
case of a pipeline that has drained its input: the refresh statement then selects nothing, while the
sample still shows the declaration's full-input totals. Both are correct answers to their own question,
and a response that returns them unlabelled invites a reader to diff them and conclude the rollup is
broken. Preview exists to be trusted, so it SHALL be self-describing.

A `disabled` pipeline SHALL NOT be refreshable on demand: `POST /v1/pipelines/{name}/refresh` SHALL be
rejected with HTTP 409. Were the on-demand route to write anyway, `enabled` would describe only the
schedule while the gate it exists to enforce stayed open, and a target could be filled from a declaration
nobody had looked at.

There is **no preview endpoint for the `enrich` kind**, and that is a stated gap rather than an omission:
its declaration is checked by a static governance gate at registration, and a dry run of an evaluator is
a different artefact from a compiled statement. Consequently the disabled state carries no gate for that
kind, which is why its registration honours the `enabled` it is given.

#### Scenario: A disabled pipeline cannot be refreshed on demand

- **WHEN** `POST /v1/pipelines/{name}/refresh` is called on a disabled pipeline
- **THEN** the request is rejected with HTTP 409 and the target is unchanged

#### Scenario: Preview returns the statement and a sample without writing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called on a disabled pipeline
- **THEN** the response carries the compiled statement and a bounded sample of rows, and the target
  row count is unchanged

#### Scenario: Preview reports the sample's own statement

- **WHEN** `POST /v1/pipelines/{name}/preview` returns a sample
- **THEN** the response also carries the statement that produced that sample, distinct from the refresh
  statement, so neither half of the response is unattributed SQL

#### Scenario: A drained pipeline's two halves disagree, and both are correct

- **WHEN** preview is called on an enabled pipeline whose cursor has reached the end of its input
- **THEN** the refresh statement carries a cursor seek that selects no rows, while the sample still
  reports the declaration's totals over the whole input, each labelled as what it is

#### Scenario: Preview is unavailable for a kind that has no dry run

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a pipeline of the `enrich` kind
- **THEN** the request is refused, rather than answered with a statement that kind does not compile

### Requirement: A refresh window is clamped to its referenced enrichments' materialized positions

A refresh whose declaration reads an enrichment column SHALL NOT close its window past the position that
enrichment's values have actually been written through. Reading a source row whose enrichment cell is still
absent aggregates a wrong group, and nothing corrects it: that source row does not move, so no later window
revisits it, and the target's upsert leaves the wrong value in place.

The position SHALL be the one **ADAS itself recorded** while materializing the enrichment — see
[class1-enrichment](../class1-enrichment/spec.md), "A materialize records the position it wrote through".
It SHALL NOT be the writing pipeline's scan cursor: that lives in the Enrichment Runner's database and
advances at chunk *enqueue*, so it marks what is queued for evaluation rather than what is written.

Each contributing enrichment's effective position SHALL be
`max(materialized_through, drained_at − scan safety lag)`, and **both halves are load-bearing**. The
materialized position alone is correct as a record and unsound as a clamp: the probe applies the writing
pipeline's own `filter`, so it advances only to that pipeline's last *matching* source row while
non-matching rows sit above it, permanently unenriched by construction. A pipeline with no filter tracks its
source's tail and never shows this; a filtered one would pin every dependent rollup at its last match
indefinitely while looking perfectly healthy. The drained horizon is what releases the window for rows
nothing is ever going to enrich.

Where several enrichments contribute, the window SHALL be clamped to the **earliest** of their positions,
since it has to be sound for all of them.

An enrichment that **no pipeline writes** SHALL NOT clamp anything. Such an enrichment will never gain
another row, so its absent values are final rather than late and there is nothing to wait for; freezing
every dependent rollup to protect against data that cannot arrive would leave no operator action that
released it. An enrichment whose writing pipeline exists but has materialized nothing yet SHALL clamp — at
nothing — because that pipeline is expected to write, so its absences are late rather than final.

An enrichment whose writing pipeline reads a **different table** from the reading pipeline's input SHALL be
admitted and SHALL contribute no clamp, and the state SHALL report it with the reason. The clamp is sound
only where the writing pipeline derives each enrichment row from the very source row it is keyed on; where
it declares its own read source, the mapping from its position to the reading pipeline's is the grain join,
which is not order-preserving. Admitting it is deliberate — the shape exists to serve a pass-through
declaration, whose absences are typically permanent rather than late and therefore already the business of
the declared-nullable rule and the reading pipeline's own `filter`.

An enrichment that stops being materialized at all — its runner unavailable, or its writing pipeline merely
disabled — SHALL hold the window at its last recorded position rather than releasing it.

The clamp SHALL be applied at execution and re-derived on every refresh, so a pipeline held back by a lagging
enrichment resumes on its own once that enrichment advances, with no operator action.

#### Scenario: A window is held at the enrichment's written position

- **GIVEN** a pipeline whose declaration reads a column of an enrichment materialized through a position
  earlier than the input's available cursor
- **WHEN** a refresh runs
- **THEN** the window ends at that enrichment's position, not at the input's cursor, and the state reports
  the clamp

#### Scenario: A drained filtered enrichment releases the window

- **GIVEN** an enrichment whose writing pipeline declares a filter, whose last matching source row is old,
  and which was found drained more recently
- **WHEN** a refresh runs
- **THEN** the window advances to the drained horizon less the scan safety lag, so source rows the writing
  pipeline will never cover are aggregated rather than waited for

#### Scenario: The earliest position across the referenced set decides

- **GIVEN** a declaration reading two enrichments materialized through different positions
- **WHEN** a refresh runs
- **THEN** the window ends at the earlier of the two, and the state names that enrichment

#### Scenario: An enrichment no pipeline writes imposes no clamp

- **GIVEN** an enrichment whose writing pipeline has been deleted, its table and rows left in place
- **WHEN** a refresh runs
- **THEN** the window is not held back, and the state reports the enrichment as read-but-unclamped with
  the reason

#### Scenario: An enrichment whose writer reads another table is admitted and reported

- **GIVEN** an enrichment whose writing pipeline declares a read source other than the reading pipeline's
  input
- **WHEN** a refresh runs
- **THEN** the declaration is not refused, the window is not held back, and the state names that enrichment
  as read-but-unclamped with the reason

#### Scenario: An enrichment that has written nothing yet withholds the window

- **GIVEN** an enrichment whose writing pipeline exists and has recorded no position
- **WHEN** a refresh runs
- **THEN** no window is written, and the state reports the clamp naming that enrichment

#### Scenario: A stalled enrichment holds rather than releases

- **GIVEN** a referenced enrichment whose writing pipeline has been disabled, or is otherwise no longer
  materialized
- **WHEN** refreshes continue to run
- **THEN** the window stays at that enrichment's last recorded position

### Requirement: An enrichment whose materialized position cannot be established is not aggregatable

A declaration SHALL be refused when it reads a column of an enrichment for which no position on the
refresh's own axis can be established, and the refusal SHALL name the enrichment and the reason. It SHALL be
enforced at registration and re-applied at execution, like every other rule on this path — which is what
catches an upstream pipeline switched to a group trigger *after* a reading pipeline was registered: it fails
loudly instead of silently reading unprotected.

Two shapes SHALL be refused:

- an enrichment written by a **group-triggered** pipeline, whose value is derived from a group of source
  rows and changes on every recompute by design. A group's members span arbitrary source positions, so the
  only sound position is the one below which every group has settled — which one long-lived conversation
  pins at its earliest member indefinitely. Admitting it produces a rollup that is silently and permanently
  wrong in both directions.
- an enrichment written by a pipeline **ADAS does not execute itself**, whose write-through position is held
  by the Enrichment Runner and never reported. Class-1 is the only path where ADAS is the writer.

A third shape SHALL NOT be refused, and the distinction is the point: an enrichment whose writing pipeline
reads a table other than this one's input records a position on *another* axis, which cannot be compared
with the one a refresh windows over — but its values are settled rather than provisional, so refusing it
would remove working capability instead of preventing a wrong number. It is admitted, contributes no clamp,
and is reported in state.

#### Scenario: A group-written enrichment is refused

- **WHEN** a declaration names a column of an enrichment whose writing pipeline uses the group trigger
- **THEN** the request is rejected with HTTP 422 naming the enrichment and the reason

#### Scenario: A runner-executed enrichment is refused

- **WHEN** a declaration names a column of an enrichment written by a pipeline the Enrichment Runner
  executes rather than ADAS
- **THEN** the request is rejected with HTTP 422 naming the enrichment and the reason

#### Scenario: The refusal is re-applied at execution

- **GIVEN** a stored pipeline whose referenced enrichment's writing pipeline is changed to the group
  trigger after registration
- **WHEN** a refresh runs
- **THEN** the refresh fails and records the reason, rather than reading the enrichment unprotected

### Requirement: A rollup whose enrichment had history re-derived is flagged

A pipeline's state SHALL report that a rebuild is required, naming the enrichment, where an enrichment it
reads has had its **history re-derived** since that rollup's contents were built. A
re-derivation rewrites values for source rows consumers may already have read, so a rollup that aggregated
them is already wrong — and no future refresh corrects it, because those source rows did not move.

A re-derivation SHALL be recognised by its **effect** rather than by the shape of the request that caused
it. A materialize carrying explicit bounds is one; so is a full replay, which carries a cursor and no window
and is therefore byte-identical to a live tick on the wire — its request alone cannot be told apart, so the
question is asked of the write: whether it rewrote rows the writing pipeline was already recorded complete
through.

The comparison anchor SHALL be the point from which the rollup's **current contents** were built, not its
last run: a re-derivation before that point has already been absorbed by the rescan, while one after it may
not have been.

This SHALL be an instruction to the operator rather than a description of progress: no window is held back,
and the system SHALL NOT rebuild automatically. Rebuilding SHALL use the operations the API already has —
delete and re-register, or re-declare while disabled, both of which re-anchor the rollup — so no rebuild
operation is introduced.

#### Scenario: A re-derivation after the rollup was built raises the flag

- **GIVEN** a rollup reading an enrichment whose history was re-derived after that rollup's contents were
  built
- **WHEN** `GET /v1/pipelines/{name}` is called
- **THEN** the state reports that a rebuild is required and names the enrichment

#### Scenario: A re-derivation absorbed by a rebuild does not raise it

- **GIVEN** a rollup re-declared, or deleted and re-registered, after the re-derivation was recorded
- **WHEN** `GET /v1/pipelines/{name}` is called
- **THEN** no rebuild is reported, so the indicator means something when it is present

#### Scenario: A full replay is recognised as a re-derivation

- **GIVEN** a Class-1 enrichment replayed at a cursor it was already complete through, with no window on
  the request
- **WHEN** a dependent rollup's state is read
- **THEN** the rebuild is reported, even though the replay was indistinguishable from a live tick on the
  wire

### Requirement: Re-running an enrichment over history invalidates its dependent rollups

The system SHALL record, per enrichment, when its history was last re-derived, so a dependent rollup can be
told it needs rebuilding. This is a fact about **values already published**, so it SHALL NOT be qualified by
the writing pipeline's change token: editing a declaration does not make a rollup that already aggregated
the superseded values correct again.

A re-derivation SHALL be recorded when a materialize rewrites rows the writing pipeline was already recorded
complete through — whether it named an explicit window or replayed a cursor. **One false positive is
accepted deliberately**: a live tick whose response was lost re-executes an identical window on the next
fire, which is recorded as a re-derivation even though it rewrites the same values from the same expression.
Nothing on the wire or in the catalog separates that from a genuine replay of one page, and the choice is
between a rare spurious rebuild instruction and a systematic miss on the default backfill shape.

The recorded instant SHALL be readable through the reading pipeline's state rather than only through the
writing pipeline's, so an operator asking "is this rollup trustworthy" gets the answer from the object they
asked about.

#### Scenario: A windowed materialize records a re-derivation

- **WHEN** a Class-1 enrichment is materialized with explicit bounds
- **THEN** the re-derivation instant is recorded against that enrichment

#### Scenario: A live tick does not record one

- **WHEN** a Class-1 enrichment materializes a window above the position it was already complete through
- **THEN** no re-derivation is recorded, and no dependent rollup is flagged

#### Scenario: A declaration edit does not clear the record

- **GIVEN** an enrichment whose history was re-derived
- **WHEN** its writing pipeline's declaration is edited, moving its change token
- **THEN** the re-derivation instant is still reported to dependent rollups
