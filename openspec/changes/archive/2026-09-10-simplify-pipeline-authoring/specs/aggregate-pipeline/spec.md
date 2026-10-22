## RENAMED Requirements

- FROM: `### Requirement: Preview and refresh serve the aggregate kind only`
- TO: `### Requirement: Refresh serves the aggregate kind only`

## MODIFIED Requirements

### Requirement: Aggregate spec vocabulary

An `aggregate` pipeline's `spec` SHALL declare `measures` over its input, plus an optional `filter`
and an optional `trigger.group_by`. The declaration SHALL be engine-agnostic: it names no ClickHouse
function and no engine construct.

**`trigger.group_by`** SHALL be an ordered list of key parts, each either:

- a plain **field of the input entity** — a declared column of the input source, or an exposed column
  of one of its ACTIVE enrichments addressed by its qualified flat name — or
- a **time bucket** `{trunc: {col, unit}, as: <name>}` over a declared `date`/`timestamp` column, with
  `unit` from the closed set `{hour, day, week, month}` (week starting Monday/ISO; month starting at
  midnight on the 1st). The bucketed column SHALL likewise be any `date`/`timestamp` field of the
  input entity.

**`trigger.group_by` MAY be omitted**, in which case it SHALL be **derived from the target's
`ordering_key`**: one plain key part per key column, in the target's own key order, admitted only
when **every** `ordering_key` column is an input column of the same name. A declaration whose target
`ordering_key` names a column the input does not carry under that name — a namespaced enrichment
column reached through an alias, say — SHALL be rejected with HTTP 422 asking for the explicit form.
The grain is the same fact on both objects, and the equality check below has nothing to disagree with
when only one of them states it.

A derived `trigger.group_by` SHALL be **expanded into the stored spec at registration**, once, rather
than re-derived behind every consumer. The derivation source cannot move — an `ordering_key` column
can neither be dropped nor renamed — so a one-time expansion is stable, it keeps the target-binding
equality check meaningful for a declaration that *did* state its keys, and it makes the echo below
automatic. The visible consequence is that the authored projection shows the expanded key, because
that is what was stored.

Derivation matches by **name**, and cannot detect a name that means something else: an input column
sharing the ordering key's name but not what the author meant would group the wrong thing silently.
The derived key SHALL therefore be **echoed expanded** on the compiled projection and in the preview,
where an author sees it before the pipeline is armed.

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
`max`, `group_uniq_array`), and `column` SHALL be a field of the input entity.

**An omitted `column` SHALL be resolved by `fn`.** For `count` it SHALL keep meaning `count(*)` — a row
count over the group, naming no column. For **every other** function it SHALL default to the measure's
own `name`, which is the common case: the measure is named after the input column it aggregates. The
rule is deliberately function-aware rather than a flat "defaults to `name`": `count` with no column is
a row count that no column name can express, and `count(<column>)` skips NULLs where `count(*)` does
not, so a flat default would silently change what a column-less count counts.

A defaulted `column` cannot detect an input that carries **two** plausibly-named columns, where the
one the author meant is not the one whose name matches the measure — a real shape, not a hypothetical.
The resolved `column` SHALL therefore be **echoed expanded** on the compiled projection and in the
preview, in the same place and for the same reason as a derived `trigger.group_by`.

A `group_uniq_array` measure SHALL bind only to an `array` target column — it collects the
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
without the field — the quantifier is additive and changes no stored declaration's meaning. A measure
whose `distinct` is `false` SHALL NOT echo it: a response that repeats a default carries no
information, and in practice it is the single most repeated member of the whole declaration.
An omitted `distinct` on the wire SHALL continue to bind as `false`.

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

**`freshness`** SHALL NO LONGER be accepted on the wire, and SHALL NO LONGER be echoed on a read. It
declared one legal value through three levels of nesting and carried no information: `periodic` is the
only implemented mode, and every other value was already refused. It SHALL be refused at the binding
with HTTP 400 `bad_request` naming the field, on registration and on patch alike.

A stored spec carrying no `freshness` SHALL read back as `periodic`, so no stored declaration changes
meaning and no re-arm can start failing; the guard rejecting any other mode SHALL be kept, as the
check on a hand-edited stored row. An **explicitly null** stored `freshness` SHALL also validate: an
explicit null is honoured where an absent member falls back to the default, so the mapper SHALL leave
the member untouched rather than writing a null through it.

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

#### Scenario: A declared freshness is refused by name

- **WHEN** a registration or patch carries `freshness`, whatever its `mode`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is stored

#### Scenario: A stored spec with no freshness still validates

- **WHEN** a stored aggregate spec carrying no `freshness` member is re-validated on a patch or an
  enable
- **THEN** it reads back as `periodic` and the validation passes

#### Scenario: A stored spec with an explicit null freshness still validates

- **WHEN** a stored aggregate spec carries `"freshness": null` explicitly
- **THEN** re-validation passes, rather than reporting that a mode is required

#### Scenario: A read no longer echoes freshness

- **WHEN** `GET /v1/pipelines/{name}` is called for an `aggregate` pipeline in either projection
- **THEN** the response carries no `freshness` member

#### Scenario: group_by is derived from the target's ordering key

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with no `trigger.group_by`, whose
  target's `ordering_key` is a single column the input carries under the same name
- **THEN** the pipeline is accepted, the stored spec carries that column as its one plain group key,
  and the compiled projection echoes it expanded

#### Scenario: A derived group_by is refused where the ordering key is not a plain input column

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with no `trigger.group_by`, whose
  target's `ordering_key` names a column the input carries only as a namespaced enrichment column
- **THEN** the request is rejected with HTTP 422 asking for the explicit `trigger.group_by` form

#### Scenario: A patch may omit a group_by the registration omitted

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a complete declaration with no `trigger.group_by` for
  a pipeline registered without one
- **THEN** the patch is accepted, rather than demanding a field the registration said was optional

#### Scenario: A column-less count compiles to a row count

- **WHEN** a measure declares `{"name": "hop_count", "fn": "count"}` with no `column`
- **THEN** it compiles to a row count over the group, not to a count of a column named `hop_count`,
  and the registration is accepted

#### Scenario: A column-less non-count measure defaults to its own name

- **WHEN** a measure declares `{"name": "prompt_tokens", "fn": "sum"}` with no `column`, and the input
  carries a `prompt_tokens` column
- **THEN** it compiles as a sum over that column, and the compiled projection echoes
  `column: "prompt_tokens"` so the resolution is visible

#### Scenario: A defaulted column that is not the one meant is visible before arming

- **WHEN** a measure declares `{"name": "total_price", "fn": "sum"}` on an input carrying both
  `total_price` and `deployment_price`
- **THEN** the declaration is accepted as a sum over `total_price`, and both the compiled projection
  and the preview echo that resolved column

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
grain is therefore not declared twice and cannot drift from the pipeline that fills it. Where the
declaration omitted `trigger.group_by` the check is satisfied by construction, because the stored key
was expanded *from* the `ordering_key`; where the declaration stated its keys, the check is what holds
the two statements together, and it SHALL be kept for exactly that case.

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

Preview SHALL also serve the `enrich` kind, with a **different response** — a composed response
schema, a resolved output mapping and sampled input values rather than a compiled statement and rows
(see [enrichment-registry](../enrichment-registry/spec.md), "Dry-run preview of an enrich
declaration"). The two SHALL be **sibling members** of the preview response rather than one shape
reused: an aggregate preview answers with SQL, an enrich preview answers with a schema and a mapping,
and a nullable reuse would make each half's absence indistinguishable from an empty one. That the
`enrich` kind now has a dry run does **not** change its registration contract: an `enrich` pipeline is
still registered with the `enabled` it is given, because its declaration is gated by the static
governance gate rather than by a disabled state.

**A derived value SHALL be echoed expanded in the preview** — a `trigger.group_by` derived from the
target's `ordering_key`, and every measure `column` resolved from its `name`. Both derivations match
by name and neither can detect a name that means something other than the author intended, so the
preview is where they are made visible before the pipeline is armed.

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

#### Scenario: Preview of an enrich pipeline answers in its own shape

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a pipeline of the `enrich` kind
- **THEN** the response carries the enrich preview members — the composed schema, the resolved output
  mapping and sampled input values — and carries no compiled refresh statement, and the aggregate
  members are absent rather than empty

#### Scenario: Preview echoes a derived group key and a defaulted measure column

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an `aggregate` pipeline registered with
  no `trigger.group_by` and with a measure that declared no `column`
- **THEN** the response echoes the expanded group key and the resolved measure column alongside the
  compiled statement

### Requirement: Refresh serves the aggregate kind only

`POST /v1/pipelines/{name}/refresh` SHALL refuse a pipeline of any
other kind with **HTTP 422 `pipeline_validation_failed`**, naming the kind the pipeline has and the kind the
endpoint serves. The refusal SHALL be a client error and not a server error: the pipeline exists, the name
resolved, and the caller addressed the wrong endpoint for its kind — the same contract the data-plane
endpoints already hold in the other direction (see [enrichment-runtime](../enrichment-runtime/spec.md)).

`POST /v1/pipelines/{name}/preview` SHALL NO LONGER refuse the `enrich` kind: it serves both kinds, each
in its own response shape (see "Dry-run preview of an aggregate declaration"). It SHALL still refuse a
kind it has no dry run for, under the same contract. Preview and refresh therefore no longer share one
kind guard, and the guard SHALL be stated per endpoint rather than for the pair: only the aggregate
machinery is kind-exclusive, and only `refresh` reaches it.

The two registries share one path and one name space, so this input is reachable by any caller who knows a
pipeline's name; it is not an internal invariant. It is also the last of the three independent guards against
an `enrich` pipeline reaching the aggregate machinery — the other two being the `AGGREGATE`-scoped due
query, which no request path consults, and the null `next_run_at` every `enrich` row carries. Those two
protect the scheduler; only this one protects the endpoint.

#### Scenario: Refreshing an enrich pipeline is refused, not a server error

- **WHEN** `POST /v1/pipelines/{name}/refresh` names a pipeline of
  kind `enrich`
- **THEN** the response is HTTP 422 `pipeline_validation_failed` naming both kinds, no statement is compiled
  or executed, and no run state is written

#### Scenario: Previewing an enrich pipeline is served, not refused

- **WHEN** `POST /v1/pipelines/{name}/preview` names a pipeline of kind `enrich`
- **THEN** the request is served with the enrich preview response, and no aggregate statement is
  compiled or executed
