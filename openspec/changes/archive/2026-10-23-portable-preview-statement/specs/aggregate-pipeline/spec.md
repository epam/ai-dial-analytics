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
the target column it is written to.

`statement` SHALL be the declaration's **portable** `SELECT` in the ADAS SQL dialect, with `query` its
structured-query DSL document (see the shared shape). It SHALL be an aggregate query over the input
entity that:

- projects each group key under its target column name: a plain key as the field, a `trunc` key as
  `date_trunc('<unit>', <column>)`;
- projects each measure under its name as the catalog aggregate over its column (`count(*)` when it has
  none), with `DISTINCT` where the measure declares it;
- renders a measure's own `where` as a `CASE WHEN <where> THEN <column> END` operand, or `THEN 1` for a
  column-less `count`, so the predicate narrows that aggregate alone, as the refresh applies it;
- applies the pipeline's `filter` as its `WHERE`;
- groups by the keys, orders by the keys ascending, and is limited to `aggregate.preview-rows`.

The portable statement reproduces what the declaration computes, not how the preview samples it. It
differs from the sampling statement in three ways, and the response SHALL NOT claim otherwise:

- **Order.** The sample is ordered by each group's latest input row, read from the input's version
  column. The portable statement orders by the keys instead, so that two runs of it agree: newest first
  over an input that is still being written returns a different set of groups on every run, and not every
  input exposes its version column to queries. Its rows
  are therefore the same groups as `rows` only when `total_rows` is at most `aggregate.preview-rows`.
  Each group it returns carries the same key and measure values as the sample would for that group.
- **Counting.** It carries no window count, so running it does not reproduce `total_rows`.
- **Cells.** A `trunc` key that the sample carries as a calendar date is returned by the query API as
  its UTC-midnight timestamp.

It also runs under the query budget and the query row limits rather than the preview budget, so a sample
that fits the preview budget can still be refused when the statement is run.

A declaration the dialect cannot express SHALL preview with `statement` and `query` absent and a
`statement_not_portable` note. A `trunc` key whose target column name is also a field of the input entity
is such a declaration: the structured-query DSL resolves a `group_by` name against the entity's fields
first, so the SQL front end refuses the shape as ambiguous. So is any other output the governed query
path would not run with the declaration's meaning. The check SHALL be the query path's own validation
of the rendered query, not a separate list kept beside it. The note's `subject` SHALL name the column
at fault where there is one.

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
`columns` or `rows`. The sampling statement SHALL also count every group it produced, before its `LIMIT`
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
it mattered most. What the declaration contributes to that statement (the select list, the filter and the
grouping) is in the portable `statement`. The enrichment joins are not: the statement names an
enrichment column by its exposed name, and the query path joins for it.

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
write, and the pipeline read serves them expanded. The portable `statement` shows the column each one
resolved to.

#### Scenario: A disabled pipeline cannot be refreshed on demand

- **WHEN** `POST /v1/pipelines/{name}/refresh` is called on a disabled pipeline
- **THEN** the request is rejected with HTTP 409 and the target is unchanged

#### Scenario: Preview returns a typed sample without writing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called on a disabled pipeline
- **THEN** the response carries a bounded sample of rows, `columns` naming each group key as `key` and
  each measure as `measure` with its `type` and `target_type`, the portable `statement` and `query`,
  and `total_rows`
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
- **THEN** the response carries the portable `statement` and no `INSERT` statement, and no member
  describing a refresh window

#### Scenario: A form declaration's statement returns the sample's groups

- **GIVEN** a disabled form-declared `aggregate` pipeline over `dial_usage_log` that groups by `deployment`
  and a day `trunc` of `request_time` into `day`, sums `total_tokens` into `tokens`, counts rows into
  `errors` with a measure `where` of `response_status >= 400`, and declares a `filter`; and an input on which
  the declaration yields at most `aggregate.preview-rows` groups
- **WHEN** it is previewed and its `statement` is sent to `POST /v1/queries/execute-sql`
- **THEN** the statement reads `dial_usage_log`, projects `date_trunc('day', request_time)` as `day`, and
  carries the measure predicate as a `CASE` inside `errors`' aggregate and the `filter` as its `WHERE`
- **AND** the query answers the same groups as `rows`, each with the same `deployment`, `tokens` and
  `errors`, and `day` as the UTC-midnight timestamp of the sample's calendar date

#### Scenario: The statement does not reproduce the sample's order or count

- **GIVEN** an `aggregate` pipeline whose input yields more groups than `aggregate.preview-rows`
- **WHEN** it is previewed and its `statement` is run through the query API
- **THEN** the query answers at most `aggregate.preview-rows` groups ordered by the keys ascending, which
  need not be the groups in `rows`
- **AND** it carries no count of every group

#### Scenario: A bucket named like an input field has no portable statement

- **GIVEN** an `aggregate` pipeline whose day `trunc` of `request_time` is written to a target column
  named `request_time`
- **WHEN** it is previewed
- **THEN** the preview returns its sample, `columns` and `total_rows`, carries no `statement` and no
  `query`, and carries a `statement_not_portable` note whose `subject` is `request_time`

#### Scenario: The statement names no column the caller cannot see

- **GIVEN** a caller without sensitive clearance on the input, and a declaration that reads no sensitive
  column
- **WHEN** the caller previews it
- **THEN** `statement` and `query` name only fields the caller's own schema discovery lists

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
- **AND** the preview carries neither, and its portable `statement` groups by that key and aggregates that
  column

### Requirement: Preview of an SQL declaration samples the block

`POST /v1/pipelines/{name}/preview` SHALL serve an SQL declaration in the shape **Dry-run preview of an
aggregate declaration** states, running the gate in full first. The sample SHALL be the parsed block
rendered over the whole input, unwindowed, with at most `aggregate.preview-rows` groups, under the
preview budget. It SHALL be ordered by each group's latest input row and SHALL count every group as
`total_rows`, from the same statement, as the form path's is.

`columns` SHALL list each select position the block groups by as `key` and every other as `measure`, named
by its target column, with `type` the type the engine yields for the rendered expression and `target_type`
the target column's type. A time-bucket key's `type` SHALL be reported as the form path's bucket is: the
target column's temporal type when the bucket's unit allows it, and `timestamp` otherwise. The
`held_by_enrichment`, `enrichment_behind`, `target_type_narrowing`, `value_out_of_target_range` and
`no_matching_rows` notes SHALL be produced as they are for the form path.

`statement` SHALL be the block's portable `SELECT`, as the form path's is: the parsed block's select
list with each item aliased by its target column (the `INSERT` column at its position), its `WHERE`,
its `GROUP BY`, ordered by the keys ascending and limited to `aggregate.preview-rows`. It differs from
the sample in order, count and `date` cells exactly as the form path's does. Preview SHALL NOT return the
refresh `INSERT`, neither the authored text nor ADAS's rendering of it.

The sampling statement SHALL still alias a select item `__c<i>` (its position) when its target column name
equals a field of the input entity that the item does not itself project, so the alias cannot shadow that
field elsewhere in the sample. That alias SHALL NOT appear in `statement`, `query`, `columns` or `rows`,
which name the item by its target column. Where the query path would not run the item under that name
with its meaning (a computed key, as the form path states), the preview SHALL carry
`statement_not_portable` in place of `statement` and `query`.

#### Scenario: An SQL declaration previews a bounded sample

- **WHEN** `POST /v1/pipelines/{name}/preview` is called on a disabled SQL-declared pipeline
- **THEN** the response carries at most `aggregate.preview-rows` rows, `columns` marking each group key as
  `key` and each other position as `measure`, and a `total_rows` counting every group of the whole input

#### Scenario: An SQL declaration's preview reports a lagging enrichment

- **GIVEN** an SQL declaration reading an enrichment whose freshness bound is older than the newest
  matching input row
- **WHEN** it is previewed
- **THEN** the preview carries an `enrichment_behind` note naming the enrichment and giving the gap

#### Scenario: An SQL declaration's preview carries no INSERT

- **WHEN** an SQL-declared pipeline is previewed
- **THEN** `statement` is the block's portable `SELECT` and the response carries no `INSERT` statement,
  neither the authored `sql` nor its compiled refresh

#### Scenario: An SQL declaration's statement runs through the query API

- **GIVEN** an SQL declaration `INSERT INTO usage_daily (day, deployment, tokens) SELECT date_trunc('day', request_time), deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2`
- **WHEN** it is previewed and its `statement` is sent to `POST /v1/queries/execute-sql`
- **THEN** the statement projects the three items as `day`, `deployment` and `tokens`, and the query
  answers HTTP 200 with those three columns

#### Scenario: A target name shadowing an input field is aliased positionally only in the sample

- **GIVEN** an SQL declaration writing `sum(total_tokens)` to a target column named `prompt_tokens`, a field
  the input also carries
- **WHEN** it is previewed
- **THEN** the sampling statement ADAS executes aliases that select item `__c<i>`
- **AND** `statement` aliases it `prompt_tokens`, and contains no `__c<i>` alias, while the rows and
  `columns` name it `prompt_tokens`

#### Scenario: An SQL declaration's preview runs the gate

- **GIVEN** a disabled pipeline whose stored `sql` groups by a nullable column
- **WHEN** it is previewed
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, the refusal arming would give,
  and no sample is read

