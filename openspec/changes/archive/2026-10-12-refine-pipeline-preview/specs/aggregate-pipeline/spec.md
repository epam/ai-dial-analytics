## MODIFIED Requirements

### Requirement: Dry-run preview of an aggregate declaration

The system SHALL expose `POST /v1/pipelines/{name}/preview` — the **dry-run gate** for an `aggregate`
declaration. Preview SHALL NOT write to the target and SHALL be permitted on a disabled pipeline, so the
declaration can be checked before it is trusted. It is the reason an `aggregate` pipeline is always
registered disabled (see [pipeline-registry](../pipeline-registry/spec.md), "Register and enable
lifecycle"): the gate only means something if nothing runs before it.

**Preview SHALL run the aggregate declaration gate in full, itself, in either state**, and SHALL NOT
rely on the declaration having been gated when it was stored. Now that a declaration may be stored
before it is finished, "it was checked on the way in" is no longer available as an argument, and preview
is the surface an author uses precisely *because* they do not yet want to arm. Every refusal the arming
path produces — target binding, input resolution, the nullability rules, sensitive-column governance,
determinism, enrichment position — SHALL therefore be producible by preview, with the same status and
the same `error` code, so that "preview passes" and "arming will pass" mean the same thing against the
same catalog.

**Running it on an enabled pipeline as well is what this surface already does, and this change leaves it
alone.** Aggregate preview validates the decoded declaration before it compiles anything and has never
consulted `enabled` to decide whether to, so there is no lighter check on this arm to keep — and
describing one would *remove* shipped behaviour: sensitivity propagation, measure typing, target
binding, determinism and the inactive-input refusal would all leave the one surface an operator reads
when a live rollup is producing numbers they do not believe. That is exactly the case where the catalog
has drifted under a declaration and the drift **is** the answer, so withholding it to protect the sample
would be the wrong trade on this kind. The state-dependent split belongs to `enrich` alone, whose
enabled preview keeps its template-placeholder check because an unbound placeholder on an armed
pipeline is drift the operator did not cause (see
[enrichment-registry](../enrichment-registry/spec.md), "Dry-run preview of an enrich declaration"). On
this arm, disabled means "check my work" and enabled means "check my work and show me a sample".

A preview refusal SHALL be the refusal arming would produce, with its status and code, and SHALL report
one problem at a time, as every other refusal on this surface does:

- an **incomplete** declaration (a member the kind requires has not been declared at all) and a complete
  but defective one are both HTTP 422 `pipeline_validation_failed`;
- a `sensitive` column the caller is not entitled to is HTTP 403 `sensitive_column_not_entitled`;
- a row-confined caller is refused as the row-policy requirement states (HTTP 403);
- a sample over its budget is HTTP 422 `pipeline_preview_budget_exceeded`;
- a target another pipeline already writes, and a system-table target, are refused as arming refuses
  them (HTTP 409 `pipeline_conflict` and `table_is_system`). Registration and every patch already refuse
  both, and the target is unique in storage, so a stored pipeline does not normally reach preview in
  either state; the gate keeps the checks so that it stays the one statement of what arming asks.

Preview changes when the author is told, never what they are told: the refusal is the one arming would
have produced, against the same catalog, without arming anything. Preview SHALL accept no request body
on this kind; a non-empty body is HTTP 422 `pipeline_validation_failed`.

Preview SHALL answer in the shared preview shape (see [pipeline-registry](../pipeline-registry/spec.md),
"The dry-run preview answers in one shape for both kinds"), with a **sample** of what the declaration
produces over the input **as a whole**, deliberately *not* window-restricted. The question a sample
answers is "are these the right numbers", and a window obscures it: a caller cannot judge a declaration
from whatever slice happens to remain unread. `columns` SHALL list each group key as `key` and each
measure as `measure`, with `type` the type the compiled expression yields and `target_type` the type of
the target column it is written to. `statement` SHALL be the sample's own `SELECT`.

A column's `type` SHALL be the type the engine produces for it, which the gate derives from the measure's
function and its input binding:

| Column | `type` |
|---|---|
| `count` | `long` |
| `sum` over an `integer` or `long` column | `long` |
| `sum` over a `decimal` column, and `avg` | `decimal` |
| `min`, `max` | the input column's type |
| `group_uniq_array` | `array` |
| a plain key | the input column's type |
| a `trunc` key | the target column's type where the unit admits it (a day, week or month bucket into `date`), and `timestamp` otherwise |

A `trunc` key whose `type` is `date` SHALL carry its sampled cells as that calendar date (`2026-01-01`),
the value the `date` target stores, rather than as a UTC-midnight timestamp (what a day bucket yields). Only
the sample's cells are converted; the refresh writes the bucket expression unchanged.

The sample SHALL hold at most `aggregate.preview-rows` groups, ordered by each group's **latest input
row**, newest first: the maximum of the input's version column over the group, qualified with the
input table so that a joined enrichment's own version column cannot make it ambiguous, with the group
keys ascending as the tiebreak. The latest-row expression orders the sample and SHALL NOT appear in
`columns` or `rows`. The sample's statement SHALL also count every group it produced, before its `LIMIT`
(a window count over the grouped result), and report that count as `total_rows`. That is the number of
rows the target holds once caught up, and a wrong group key shows up there: tens of millions of rows
where a few hundred were meant. The count SHALL come from the same statement, under the same budget, and
SHALL NOT be a second read.

The window count holds the whole grouped result in memory before the sort and the `LIMIT`, and that step
does not spill to disk as the grouping itself may. A group set too large to hold under the preview budget
SHALL therefore be refused with `pipeline_preview_budget_exceeded` rather than reported as a count. The
refusal is the same signal by another route, and the budget refusal's message SHALL say that a group key
of unexpectedly high cardinality is the likely cause.

Preview SHALL NOT return the statement a refresh would execute. The window a refresh takes (the safety
lag, the seek past a stored cursor, the probed end, the window cap, the enrichment clamp) is **runtime
state**: it depends on where the pipeline is, not on what the declaration says, and the pipeline read
serves it under `state`. A refresh statement rendered without a probe was neither what a refresh runs nor
safe to copy out of a response. The two observed failures were that it had no window cap and that it
dropped the enrichment clamp exactly when the clamp would have stopped the write, so it misled most when
it mattered most. What the declaration contributes to that statement (the select list, the filter, the
grouping and the enrichment joins) is in the sample's `statement`.

Preview SHALL carry, besides the shared notes:

- `held_by_enrichment`, when an enrichment the declaration reads has no freshness bound yet, so an
  enabled refresh would write nothing until it has one;
- `enrichment_behind`, when the newest matching input row is newer than such an enrichment's freshness
  bound, because the unclamped sample reads those rows' enrichment columns as unmatched (NULL for a
  nullable key or measure column, the type default for an `array` or `map` column and inside the
  filter, since `join_use_nulls` is off) and a refresh would not write them until the enrichment catches
  up. The message SHALL give the gap between the two, so an author can tell ordinary lag from a stalled
  enrichment. It is not computed for an enrichment that has no bound, which `held_by_enrichment` already
  reports;
- `target_type_narrowing`, for a measure or key whose `type` ranks above its target column's type on
  `integer < long < decimal`, or, for a `min` or `max` measure, on `date < timestamp`. A `trunc` key
  whose unit admits its target type is not narrowing;
- `value_out_of_target_range`, for a sampled value the target column's physical type cannot hold — an
  `integer` or `long` outside its width, a `decimal` outside `Decimal(38, 12)`, a `date` outside
  1970–2149, a `timestamp` outside 1900–2299. The insert wraps an out-of-range `integer` or `long` and
  clamps an out-of-range `date` or `timestamp` without an error, but refuses an out-of-range `decimal`
  with `DECIMAL_OVERFLOW`, so the refresh fails and records it in `state.last_error`; the message SHALL
  say which of the two the target's type leads to. The ranges SHALL be the ones the row-write path
  already enforces, through the same predicates;
- `no_matching_rows`, when the sample is empty.

A `disabled` pipeline SHALL NOT be refreshable on demand: `POST /v1/pipelines/{name}/refresh` SHALL be
rejected with HTTP 409. Were the on-demand route to write anyway, `enabled` would describe only the
schedule while the gate it exists to enforce stayed open, and a target could be filled from a declaration
nobody had looked at.

Preview SHALL also serve the `enrich` kind, in the same shape (see
[enrichment-registry](../enrichment-registry/spec.md), "Dry-run preview of an enrich declaration"). That
the `enrich` kind has a dry run does **not** change its registration contract: an `enrich` pipeline is
still registered with the `enabled` it is given, because its declaration is gated by the static
governance gate rather than by a disabled state.

**A derived value SHALL be visible before the pipeline is armed, and it is shown on the read rather than
echoed here.** The derived values are a `trigger.group_by` derived from the target's `ordering_key`, and
every measure `column` resolved from its `name`. Both derivations match by name and neither can detect a
name that means something other than the author intended. The gate derives them on every declaration
write, and the pipeline read serves them expanded. The sample's `statement` shows the expression each one
compiled to.

#### Scenario: A disabled pipeline cannot be refreshed on demand

- **WHEN** `POST /v1/pipelines/{name}/refresh` is called on a disabled pipeline
- **THEN** the request is rejected with HTTP 409 and the target is unchanged

#### Scenario: Preview returns a typed sample without writing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called on a disabled pipeline
- **THEN** the response carries a bounded sample of rows, `columns` naming each group key as `key` and
  each measure as `measure` with its `type` and `target_type`, the sample's `statement`, and
  `total_rows`
- **AND** the target row count is unchanged

#### Scenario: Preview of an incomplete declaration names what is missing

- **GIVEN** an `aggregate` pipeline registered with `name`, `kind` and `target` alone
- **WHEN** `POST /v1/pipelines/{name}/preview` is called
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` reporting
  that `trigger.cron` is required — the trigger defaults to `schedule` and declares no cron, and the
  trigger is checked first, exactly as arming checks it — and nothing is compiled or sampled
- **AND WHEN** a `trigger.cron` is added and the pipeline is previewed again
- **THEN** the refusal names the next absent member, its `inputs`

#### Scenario: Preview of a disabled pipeline runs the gate rather than trusting the store

- **GIVEN** a disabled `aggregate` pipeline whose declaration is complete but whose measure binds to a
  target column of an incompatible type
- **WHEN** `POST /v1/pipelines/{name}/preview` is called
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` naming the
  measure and the column, the same refusal arming would give, and no sample is returned

#### Scenario: Preview of an enabled pipeline runs the same full gate

- **GIVEN** an enabled `aggregate` pipeline whose input has since gone `INACTIVE`, or one of whose
  referenced input columns has since been declared `sensitive` while the target column it feeds is not
- **WHEN** `POST /v1/pipelines/{name}/preview` is called
- **THEN** the declaration is gated exactly as it is while disabled — sensitivity propagation, measure
  typing, target binding, determinism and the inactive-input refusal all re-applied — and the drift is
  reported with the status and code arming would give, rather than a sample

#### Scenario: A refusal keeps the status arming gives it

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `aggregate` pipeline whose
  measure reads a `sensitive` input column the caller is not entitled to
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, not 422, and no sample is read

#### Scenario: Preview carries no refresh statement

- **WHEN** `POST /v1/pipelines/{name}/preview` returns a sample
- **THEN** the response carries the sample's own `statement` and no `INSERT` statement, and no member
  describing a refresh window

#### Scenario: A drained pipeline still previews its whole input

- **WHEN** preview is called on an enabled pipeline whose cursor has reached the end of its input
- **THEN** the sample still reports the declaration's totals over the whole input

#### Scenario: The sample shows the most recently active groups

- **GIVEN** an input whose oldest groups by key are long inactive, and whose newest input rows belong to
  other groups
- **WHEN** the pipeline is previewed
- **THEN** the sample's first rows are the groups holding the newest input rows, and the ordering
  expression is not among the `columns`

#### Scenario: Preview reports an enrichment that lags the input

- **GIVEN** an `aggregate` pipeline reading an enrichment whose freshness bound is older than the
  newest matching input row
- **WHEN** it is previewed
- **THEN** the preview carries an `enrichment_behind` note whose `subject` names the enrichment and
  whose message gives the gap

#### Scenario: Preview reports a refresh an enrichment would hold back

- **GIVEN** a disabled `aggregate` pipeline reading an enrichment that has no freshness bound yet
- **WHEN** it is previewed
- **THEN** the preview succeeds with its sample and carries a `held_by_enrichment` note whose `subject`
  names the enrichment

#### Scenario: Preview warns of a value the target cannot hold

- **GIVEN** an `aggregate` pipeline whose `sum` measure over a `long` input column feeds an `integer`
  target column, and an input on which one group's sum exceeds the 32-bit range
- **WHEN** it is previewed and that group is sampled
- **THEN** the response carries a `target_type_narrowing` note and a `value_out_of_target_range` note,
  each naming the measure's column

#### Scenario: A narrowing binding is noted even when every sampled value fits

- **GIVEN** an `aggregate` pipeline whose `count` measure feeds an `integer` target column
- **WHEN** it is previewed
- **THEN** the response carries a `target_type_narrowing` note naming the column and no
  `value_out_of_target_range` note

#### Scenario: A day bucket into a date column is not narrowing

- **GIVEN** an `aggregate` pipeline whose `trunc` key buckets by day into a `date` target column
- **WHEN** it is previewed
- **THEN** that key's `type` is `date`, its cells are calendar dates, and no `target_type_narrowing`
  note names it

#### Scenario: A group set too large to count is refused, not truncated

- **WHEN** an `aggregate` pipeline whose group key has far more distinct values than its author meant is
  previewed, and the grouped result does not fit the preview budget
- **THEN** the response is HTTP 422 `pipeline_preview_budget_exceeded`, whose message names the
  ceiling and points at the group key's cardinality

#### Scenario: An empty input is explained

- **WHEN** an `aggregate` pipeline whose `filter` matches no input row is previewed
- **THEN** `rows` is `[]`, `total_rows` is `0`, and `notes` carries `no_matching_rows`

#### Scenario: Preview of an enrich pipeline answers in the same shape

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a pipeline of the `enrich` kind
- **THEN** the response carries `columns`, `rows` and `notes` as an aggregate preview does, carries
  no `total_rows`, and carries no member specific to either kind's declaration

#### Scenario: A derived group key and a defaulted measure column are shown by the read, not the preview

- **GIVEN** an `aggregate` pipeline registered with no `trigger.group_by` and with a measure that
  declared no `column`
- **WHEN** it is read with `GET /v1/pipelines/{name}` and previewed
- **THEN** the read carries the expanded group key and the resolved measure column
- **AND** the preview carries neither, and its `statement` groups by that key and aggregates that column

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
column reached through an alias, say — SHALL be rejected with HTTP 422 asking for the explicit form,
**at the declaration gate** (see [pipeline-registry](../pipeline-registry/spec.md)) rather than at
the moment the declaration is stored: an aggregate pipeline is always registered disabled, so a
registration arms nothing and the refusal lands when the pipeline is armed, previewed,
or executed. The grain is the same fact on both objects, and the equality check below has nothing to
disagree with when only one of them states it.

A derived `trigger.group_by` SHALL be **expanded into the stored spec** by the derivation half of the
gate, which runs on **every** declaration write, rather than being re-derived behind every consumer.
The derivation source cannot move — an `ordering_key` column can neither be dropped nor renamed — so
the expansion is stable across writes, it keeps the target-binding equality check meaningful for a
declaration that *did* state its keys, and it makes the echo below automatic. The visible consequence
is that the authored projection shows the expanded key, because that is what was stored.

Derivation SHALL be **best-effort and non-destructive**: where it cannot run — a write that leaves the
declaration with no resolvable input entity, or a target whose `ordering_key` does not derive — the
stored group key SHALL be left as it was rather than replaced with an empty one. Erasing it would be
worse than never having derived it: the target-binding equality check, the enrichment drop/rename
guard and the column-writer resolution all read the *stored* keys, so a write that silently emptied
them would widen what an unrelated schema change is allowed to do. An empty stored list SHALL be
read as "not declared yet", never as "declared to group by nothing", since no valid aggregate
declaration has zero group keys.

Derivation matches by **name**, and cannot detect a name that means something else: an input column
sharing the ordering key's name but not what the author meant would group the wrong thing silently.
The derived key SHALL therefore be **echoed expanded** on the pipeline read, where an author sees it
before the pipeline is armed. The preview does not echo it, and shows instead the expression it compiled
to in its `statement` (see "Dry-run preview of an aggregate declaration").

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
one the author meant is not the one whose name matches the measure — a realistic shape, not a hypothetical.
The resolved `column` SHALL therefore be **echoed expanded** on the pipeline read, in the same place
and for the same reason as a derived `trigger.group_by`.

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

#### Scenario: A group_by that cannot be derived is refused at the declaration gate

- **GIVEN** an `aggregate` pipeline registered with no `trigger.group_by`, whose target's
  `ordering_key` names a column the input carries only as a namespaced enrichment column
- **WHEN** the registration is stored
- **THEN** it is accepted with no group key derived, because an aggregate registers disabled and
  arms nothing
- **AND WHEN** the pipeline is armed, or previewed
- **THEN** the request is rejected with HTTP 422 asking for the explicit `trigger.group_by` form

#### Scenario: A write that cannot re-derive leaves the stored group key untouched

- **GIVEN** a disabled `aggregate` pipeline whose `trigger.group_by` was derived from its target's
  `ordering_key`
- **WHEN** a declaration patch leaves the declaration with no resolvable input entity, so the key
  cannot be re-derived
- **THEN** the stored group key is left as it was rather than stored empty, and the target-binding
  and drop/rename guards that read it keep seeing what the pipeline will group by

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
- **THEN** the declaration is accepted as a sum over `total_price`, and the pipeline read echoes that
  resolved column, while the preview's `statement` shows the sum over it

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

### Requirement: Preview executes under a resource budget

The dry-run preview SHALL execute its sample query under an explicit, configured **resource budget** —
a wall-clock limit, a memory ceiling, and a spill-to-disk threshold — carried to ClickHouse as query
settings on the preview statement alone.

The budget is required because preview is deliberately **not** window-restricted: the sample answers "are
these the right numbers", which a window would obscure, so the compiled sample aggregates the pipeline's
entire input on every call. Bounding the rows *returned* (`aggregate.preview-rows`) bounds none of that
work. A declaration grouping by a high-cardinality wide column — a request body, a URI, a claims blob —
therefore builds an aggregation state proportional to the whole input — enough, on an input of only tens
of thousands of rows, to exhaust ClickHouse and take the host down with it.

The exposure this closes is specific to the gate's position in the lifecycle. Preview is the endpoint an
operator is *expected* to call on a declaration nobody has vetted, before it is ever enabled, and a
pipeline that is refused the instant it runs must not be able to take the data plane offline on the way to
being refused. Registration validates the declaration's shape, not its cost, and cost cannot be validated
from the shape alone — it depends on the input's cardinality, which is a property of the data.

The budget SHALL bound the preview's **cost**, never its **scope**. The system SHALL NOT sample, window,
or otherwise narrow the preview's input to stay inside the budget: numbers computed over a silently
reduced input would still be presented as the declaration's output, which is worse than a refusal because
the operator would act on them. A preview that cannot be completed within its budget SHALL be refused
outright.

The budget SHALL apply to preview only. A refresh is bounded by its window and by the pipeline read budget
(`pipeline.budget.*`, see [enrichment-runtime](../enrichment-runtime/spec.md)), never by this one. The two
bound different things: this budget is sized for an unvetted declaration aggregating its whole input, the
pipeline read budget for the heaviest legitimate window of an enabled pipeline, so holding a refresh to the
preview's ceiling would make a legitimate large window fail as though it were malformed.

The budget SHALL be deployment-configurable, so an installation whose ordinary previews are heavier than
the default can raise it deliberately rather than discovering the ceiling by hitting it.

#### Scenario: An affordable preview is unaffected

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a declaration whose sample completes inside
  the configured budget
- **THEN** the response is the bounded sample, its `statement` and `total_rows`, with the sample
  computed over the whole input

#### Scenario: A preview that exceeds its budget is refused

- **WHEN** a preview's sample query exceeds the configured wall-clock or memory budget
- **THEN** the request is refused with the shared `ErrorView` contract and the budget-exceeded error code,
  and no partial or reduced-input sample is returned

#### Scenario: A node-wide exhaustion is not the preview's budget refusal

- **GIVEN** a preview running under the `aggregate.preview.*` ceilings
- **WHEN** ClickHouse refuses it because the node itself ran out of memory, not because the preview
  exceeded its own ceiling
- **THEN** the response is HTTP 503 `clickhouse_unavailable` rather than the preview's budget refusal
- **AND** no message names the preview's configured ceiling

#### Scenario: The budget does not narrow the sample's input

- **WHEN** a preview runs within its budget
- **THEN** the sample is computed over the pipeline's entire input, unsampled and unwindowed, so the
  numbers are the declaration's real output

#### Scenario: Refresh is not subject to the preview budget

- **WHEN** an enabled pipeline refreshes a window whose recompute is more expensive than the preview
  budget allows, and within the pipeline read budget
- **THEN** the refresh runs to completion, because it is bounded by its window and the pipeline read budget
  rather than by the preview budget

### Requirement: Refresh observability

The service SHALL expose refresh observability through logs, traces, and Micrometer metrics, including
refresh runs by outcome, rows written, run duration, and per-pipeline staleness, attributable to the
individual pipeline. Because a rollup is served from stored rows rather than recomputed per read, a
silently stalled refresh is indistinguishable from a quiet period at the query surface — staleness SHALL
therefore be observable as a metric, not only through the API's state view.

The staleness metric SHALL be measured against the present at **observation** time rather than published as
the value a run computed. A run's lag published as a stored number is flat between runs and frozen entirely
once refreshes stop succeeding, which is exactly the condition the metric exists to expose: a stall would
render as a steady line. Deriving it from the last position actually reached makes a stall a line that keeps
climbing with no run needed to move it. A failed run SHALL NOT move that position.

Because it is sampled at observation time, the reported staleness SHALL be understood as a **sawtooth, not
a level**: its floor is approximately the safety lag plus the refresh's own duration plus the input's
ingest lag, and its ceiling is that floor plus one cron period. Two reads of the same unchanged cursor
therefore differ by the time between them, and both are correct. It is also **not** "time since the last
run" — it is derived from the cursor, so it bundles time-since-refresh with how far behind the cursor
already was. Distinguishing a stall from a healthy cadence requires reading it alongside the pipeline's
last run time, last error, and whether more input remains; an alert threshold on it MUST exceed one cron
period, or every cron slot fires. The API's state view SHALL describe it in these terms rather than as a
plain measure of how far behind the rollup is.

The service SHALL log the refresh path at INFO on the **successful** path, not only on failure. A
completed refresh SHALL log the pipeline, the rows written, the run's duration, and whether input
remains; a failure SHALL continue to log at WARN with the throwable, whose message alone (an NPE's is
null) says nothing an operator can act on. The one exception is a scheduled run refused by the pipeline read
budget: that refusal's message already names the ceiling, its configured value and the remedy, and the same
window is refused on every tick until something changes, so it SHALL be logged at WARN with its message and
without a stack, which is available at DEBUG. Accepted control-plane mutations — register, re-declare,
enable, disable, delete — SHALL likewise be logged, because they decide what subsequently gets written.

The scheduler tick SHALL emit one summary per tick reporting how many pipelines were due, refreshed,
skipped as no longer eligible, and failed, and how long the tick took. It SHALL be suppressed entirely
when nothing was due: the tick's cadence is configurable down to one minute, and a scheduled job must not
produce a log line per minute at rest.

The scheduler SHALL log one INFO line at startup giving whether aggregates are enabled, the tick cron and
the scheduler pool size, so the cadence a deployment actually runs is readable from its logs. A tick whose
duration runs past the tick's next scheduled fire SHALL log one WARN naming the tick's duration, the cron
and the pool size. With the default pool of one, an overrunning tick delays every aggregate pipeline, and
the per-tick summary alone does not say that a slot was lost.

A refresh that is held back or that finds nothing to write for the same reason tick after tick SHALL log
that reason only when it changes for that pipeline. That covers a window held back entirely because a
referenced enrichment has no usable position, and a probe that matched only rows above an enrichment's
bound. The change SHALL be logged at INFO, both on entering the state and on leaving it. A pipeline with
no new input rows SHALL stay at DEBUG. The state behind this throttle is held in memory per replica, and
the tick runs on every replica, so each replica logs its own transitions.

A refresh SHALL run inside an `INTERNAL`-kind parent span, so the `CLIENT` spans its statements raise are
attributable to the pipeline rather than appearing as disconnected roots. The compiled `INSERT … SELECT`
SHALL be available at DEBUG on the dedicated SQL logger (see [observability](../observability/spec.md)),
never on the refresh service's own logger. Preview does not return the refresh statement, only its sample's,
so a scheduled run that behaved unexpectedly otherwise leaves nothing to compare against. The statement inlines literals, so
it is reachable only by a static level set at startup, and the SQL logger's pin and denylist keep a runtime
level change on the refresh service, or on any ancestor package, from printing it.

A metric series SHALL NOT be created for a pipeline name that is not in the registry. The name tags every
series and reaches these endpoints as a path variable, so instrumenting an unknown one would let arbitrary
spellings grow the meter registry without bound. A rejection that executes no statement — an unknown
pipeline, or a disabled one — is therefore not counted as a refresh run at all.

Deleting a pipeline SHALL likewise remove its series. This follows from the same rule read forwards — a
name not in the registry has no series — and matters because of how staleness is derived: a gauge left
behind measures the present against a watermark nothing will ever move again, so it climbs indefinitely
for a pipeline that no longer exists, raising exactly the signal a stall raises. Unlike the rollup's rows,
which a delete deliberately keeps, a series is only a statement about a running process.

#### Scenario: A deleted pipeline's series end with it

- **WHEN** `DELETE /v1/pipelines/{name}` removes a pipeline that had refreshed
- **THEN** no refresh-run, rows-written, duration, or staleness series remains tagged with that name, so its
  staleness does not go on climbing after the pipeline is gone

#### Scenario: Metrics reflect a refresh run

- **WHEN** a refresh completes
- **THEN** the refresh metrics (runs by outcome, rows written, duration) are updated for that pipeline

#### Scenario: A successful refresh is visible at INFO

- **WHEN** a refresh completes successfully
- **THEN** one INFO line reports the pipeline, the rows written, the duration, and whether input remains

#### Scenario: An idle tick logs nothing

- **WHEN** the scheduler tick fires and no pipeline is due
- **THEN** no tick summary is logged

#### Scenario: A tick that did work reports what it did

- **WHEN** the scheduler tick fires with at least one due pipeline
- **THEN** exactly one INFO summary reports how many were due, refreshed, skipped, and failed, and how
  long the tick took

#### Scenario: A refresh's client spans are attributable to it

- **WHEN** a refresh runs with tracing enabled
- **THEN** the ClickHouse `CLIENT` spans its probe and write raise are children of a single
  `INTERNAL`-kind span identifying the pipeline

#### Scenario: The scheduler's configuration is logged at startup

- **WHEN** the service starts
- **THEN** one INFO line reports whether aggregates are enabled, the tick cron and the scheduler pool size

#### Scenario: An overrunning tick is reported

- **WHEN** a tick is still running when its next scheduled fire time passes
- **THEN** one WARN reports the tick's duration, the cron and the pool size

#### Scenario: A held-back pipeline is logged on the change, not every tick

- **WHEN** a pipeline stays held back entirely by an enrichment with no usable position for several ticks
- **THEN** one INFO line is logged when it becomes held back and one when it stops, and none on the ticks in
  between

#### Scenario: Tracing the refresh service does not print its statement

- **WHEN** the refresh service's logger, or an ancestor package, is set to DEBUG or TRACE at runtime
- **THEN** the compiled `INSERT … SELECT` is not logged, because it is logged only on the pinned SQL logger

#### Scenario: A control-plane mutation is recorded in the log

- **WHEN** a pipeline is registered, re-declared, enabled, disabled, or deleted
- **THEN** the accepted mutation is logged, naming the pipeline and what changed

#### Scenario: Staleness is described as a sawtooth

- **WHEN** a caller reads the pipeline state view's staleness field documentation
- **THEN** it states that the value is sampled at observation time, varies between a floor and one cron
  period above it, and is not the time since the last run

#### Scenario: A stalled pipeline is visible as staleness

- **WHEN** an enabled pipeline has not refreshed successfully for longer than its schedule implies
- **THEN** its staleness metric grows between observations, without a run needing to occur, making the
  stall observable without inspecting logs

#### Scenario: An ineligible refresh creates no metric series

- **WHEN** `POST /v1/pipelines/{name}/refresh` is called for a name not in the registry, or for a disabled
  pipeline
- **THEN** the request is rejected and no refresh-run, duration, or staleness series is registered for that
  name

### Requirement: Refresh serves the aggregate kind only

`POST /v1/pipelines/{name}/refresh` SHALL refuse a pipeline of any
other kind with **HTTP 422 `pipeline_validation_failed`**, naming the kind the pipeline has and the kind the
endpoint serves. The refusal SHALL be a client error and not a server error: the pipeline exists, the name
resolved, and the caller addressed the wrong endpoint for its kind — the same contract the data-plane
endpoints already hold in the other direction (see [enrichment-runtime](../enrichment-runtime/spec.md)).

`POST /v1/pipelines/{name}/preview` SHALL NO LONGER refuse the `enrich` kind: it serves both kinds, in
one shared response shape (see [pipeline-registry](../pipeline-registry/spec.md), "The dry-run preview
answers in one shape for both kinds"). It SHALL still refuse a
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
