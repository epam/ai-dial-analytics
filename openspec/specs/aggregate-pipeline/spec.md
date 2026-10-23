# aggregate-pipeline Specification

## Purpose

This capability implements the **`aggregate` kind** of pipeline: an in-DB rollup that persists a
`group_by` + `measures` declaration over one source into a target `source` table written
`upsert_by_key`. The registry object it is a kind of — CRUD, the `kind` discriminator, one writer per
target, the lifecycle, the state projection and the error codes — belongs to
[pipeline-registry](../pipeline-registry/spec.md).

Aggregation is deliberately not a table kind: a rollup's output is an ordinary source, so it is
queryable through `/v1/queries` and usable as an enrichment parent with no special case on the read path
(see [table-management](../table-management/spec.md) and
[structured-query](../structured-query/spec.md)).

What this capability governs is the transform and its execution: the spec vocabulary, the target's shape,
the measure and group-key rules, sensitive-column governance, the system identity a scheduled refresh runs
under, the dry-run preview and its resource budget, the window probe, refresh observability, and the
freshness clamp that keeps a rollup from aggregating enrichment values that are not there yet. Refreshes
run on each pipeline's own cron inside ADAS; the Enrichment Runner is not in this path.

The contract that matters most is in "Scheduled refresh recomputes every affected group key in full" —
because the target replaces rows by key, a refresh that aggregated only its window would silently
overwrite a bucket's running total, and recomputing whole keys is what makes runs idempotent and late
data self-healing.
## Requirements

### Requirement: Aggregate spec vocabulary

An `aggregate` pipeline's `spec` SHALL declare `measures` over its input, plus an optional `filter`
and an optional `trigger.group_by` (the **form declaration**), unless it declares `sql` instead (the
**SQL declaration**, see **An aggregate pipeline may be declared as one SQL statement**). The two are
mutually exclusive. `measures` SHALL be required for the form declaration only, and an SQL declaration
SHALL carry no `measures`, no `filter` and no `trigger.group_by`. The form declaration SHALL be
engine-agnostic: it names no ClickHouse function and no engine construct. The rules below describe the
form declaration's members; an SQL declaration is held to the same outcomes by the SQL requirements
named there.

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

An SQL declaration SHALL NOT have its group key derived: its keys are the statement's own `GROUP BY`,
and derivation SHALL leave `trigger.group_by` empty for it rather than fill it from the `ordering_key`.

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
before the pipeline is armed. The preview does not echo it, and shows instead the column it resolved
to in its portable `statement` (see "Dry-run preview of an aggregate declaration").

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
one the author meant is not the one whose name matches the measure — a live shape, not a hypothetical.
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

The SQL declaration is the deliberate exception: its measures are select expressions, so per-row
arithmetic inside an aggregate (`sum(a * b)`) and an expression over aggregates (`sum(x) / count(*)`) are
admitted within the rules of **An SQL declaration's expressions are deterministic and typed as the form
path's are**. A stored ratio is correct per group, because each dirty group is recomputed in full, but it
still cannot be re-aggregated across groups. Storing one is the author's choice.

A catalog aggregate requiring **more than one argument** SHALL be rejected with HTTP 422 naming the function
and its signature: a measure carries exactly one input column and cannot express such a call. No aggregate
currently in the catalog takes two, so this is a guard on future additions rather than a live restriction —
but it has to be enforced at declaration, because unrejected the declaration would validate, store and
enable, and the arity failure would then be raised inside every scheduled refresh, recorded only in
`state.last_error`. The pipeline SHALL be keyed on the catalog descriptor's required-argument count rather than
on a list of function names, so an addition is covered without a change here.

The arity rule SHALL apply to the form declaration's `measures` only. An SQL declaration calls a catalog
aggregate with the arguments its own signature takes, so a two-argument aggregate such as `arg_max` is
admitted there as a measure, subject to the SQL type rules.

**`measures[].distinct`**, when `true`, SHALL deduplicate the values of the measure's input column before
the function is applied, so the measure counts or aggregates the group's *distinct* values rather than its
rows. It SHALL default to `false`, and a declaration that omits it SHALL compile exactly as it does
without the field — the quantifier is additive and changes no stored declaration's meaning. A measure
whose `distinct` is `false` SHALL NOT echo it: a response that repeats a default carries no
information, and on the live registry it is the single most repeated member of the whole declaration.
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

#### Scenario: An SQL declaration needs no measures

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline carrying `sql` and no `measures`
- **THEN** the declaration is not refused for the missing `measures`, and the stored spec carries none

#### Scenario: An SQL declaration's group key is not derived

- **WHEN** an SQL-declared pipeline is written, armed or read
- **THEN** its stored `trigger.group_by` stays empty, the read echoes no derived key, and the keys are the
  ones the statement's `GROUP BY` names

#### Scenario: An SQL declaration may store a ratio of aggregates

- **WHEN** an SQL declaration writes `sum(total_tokens) / count(*)` to a nullable `decimal` target column
- **THEN** the declaration is accepted, unlike a form measure carrying an expression

#### Scenario: A ratio whose denominator is zero for one group writes NULL for that group

- **WHEN** an armed SQL declaration writes `count(*) / sum(total_tokens)` to a nullable `decimal` target
  column, and one group's `sum(total_tokens)` is `0`
- **THEN** the refresh succeeds, writes NULL for that group and the ratio for every other group, and does
  not fail converting the division's result into the column's `Decimal` type

### Requirement: Target shape governance

The target of an `aggregate` pipeline SHALL be an existing, materialized (`ACTIVE`) `source` table whose
write discipline is `upsert_by_key`. A target that is unknown, an `enrichment`, not yet materialized, or
written `append` SHALL be rejected with HTTP 422 — an `append` target would accumulate one row per
refresh instead of replacing the recomputed key, silently multiplying every measure.

In the form declaration, the target's `ordering_key` SHALL equal the pipeline's `trigger.group_by` output names, **in order**. The rollup's
grain is therefore not declared twice and cannot drift from the pipeline that fills it. Where the
declaration omitted `trigger.group_by` the check is satisfied by construction, because the stored key
was expanded *from* the `ordering_key`; where the declaration stated its keys, the check is what holds
the two statements together, and it SHALL be kept for exactly that case.

An SQL declaration has no `trigger.group_by`, so this equality SHALL NOT be applied to it. Its `GROUP BY`
keys SHALL instead equal the `ordering_key` **as a set**, each key matched to the target column at the
same position of the `INSERT` column list (see **An SQL declaration's group keys are exactly the target's
non-nullable ordering key**). Order is not compared, because the parser may reorder the group set.

Being a `source`, the target MAY declare the optional scan-metadata pair, which is what makes the rollup
itself scannable by an enrich pipeline. It SHOULD do so **only when its `trigger.group_by` yields a single
column**: the target collapses by its whole `ordering_key`, so with a multi-column grain no single column
identifies a row, and `identity_column` could only name part of the key. That is not a cosmetic
inaccuracy — one refresh stamps a single `now64(3)` into `_updated_at` for every row it writes, so rows
sharing that one key member are indistinguishable to the keyset seek and all but one are silently skipped.
A single-column-grain rollup declares `identity_column` = its group key and `version_column` =
`_updated_at`, which the refresh restamps on every recomputed key, so an enrich pipeline sourced on the rollup re-reads
exactly the keys the last refresh touched.

In the form declaration, every `trigger.group_by` output name and every `measures[].name` SHALL resolve to a declared column of the
target, and the column's declared type SHALL accept the part's or function's result type. A target column
that no `trigger.group_by` part or measure writes SHALL be rejected, since it could only ever hold a default.

In an SQL declaration the same two rules SHALL be applied to the `INSERT` column list. Every target column
except `_updated_at` SHALL be listed exactly once, so no column is left to hold a default (see **An SQL
declaration is exactly one INSERT … SELECT naming every target column**). Each listed column's declared
type SHALL accept the select expression at its position (see **An SQL declaration's expressions are
deterministic and typed as the form path's are**).

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

An SQL declaration follows the same split: the statement's source reads input columns by their exposed
names, and its `INSERT` column list names target columns by their physical names.

The rest of this requirement SHALL apply to both declarations: the `upsert_by_key` target, the
scan-metadata guidance, the exact group-key type rule, the time-bucket width rule, the fractional-measure
rule and the `system`-target refusal. For the scan-metadata guidance, an SQL declaration's grain is its
`GROUP BY` key set.

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

#### Scenario: An SQL declaration binds its keys by set and its columns by the INSERT list

- **GIVEN** a target whose `ordering_key` is `(day, deployment)` and which declares `day`, `deployment`
  and `tokens`
- **WHEN** an SQL declaration is `INSERT INTO usage_daily (deployment, day, tokens) SELECT deployment, date_trunc('day', request_time), sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2`
- **THEN** the declaration is accepted, although its keys are listed in another order than the
  `ordering_key`, because the key set is compared as a set and every target column is in the column list
- **AND WHEN** the column list omits `tokens`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `tokens`

### Requirement: An array measure's element type must match its input

An array-returning measure (`group_uniq_array`) SHALL bind to a target column whose declared
`element_type` equals its input column's declared type, and its input column SHALL itself be a scalar.
Either mismatch SHALL be rejected with HTTP 422 naming the measure, the input column's type and the
target's element type. The set holds the input values **unconverted**, which is what makes the element
type the input column's own.

This is the group-key type rule (see "Target shape governance") one level down, and exact for the same
reason: the stored values *are* the input values. It is enforced at declaration because the engine does
not enforce it at all in the dangerous direction — ClickHouse's `INSERT … SELECT` converts positionally,
so a `long` input collected into an `array<string>` column stores `["100","250"]`, stringified numbers
that every reader then treats as text. The reverse direction (`string` input, `array<long>` target) does
fail, but inside a scheduled refresh rather than at the declaration that caused it. This is the same
argument the fractional-measure and nullable-measure rules make: an engine that coerces rather than
refusing turns a declaration mistake into plausible-looking data.

An `array` or `object` input column SHALL be rejected outright: a set of arrays is a nested type no
`element_type` can name, and the SQL surface refuses the identical expression.

#### Scenario: An array measure whose element type differs from its input is rejected

- **WHEN** a `group_uniq_array` measure over a `long` input column is bound to a target column declared
  `array` with `element_type` `string`
- **THEN** the request is rejected with HTTP 422, because the set holds the input values themselves and
  ClickHouse would convert them on insert

#### Scenario: An array measure over an array input column is rejected

- **WHEN** a `group_uniq_array` measure names an input column declared `array` (or `object`)
- **THEN** the request is rejected with HTTP 422, because a set of arrays is not a declarable column type

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

**This rule SHALL govern the form declaration only.** An SQL declaration carries no `filter`, and its
`WHERE` SHALL NOT be accepted as the answer in its place. A nullable key in an SQL declaration SHALL be
refused outright, with HTTP 422 `pipeline_validation_failed` suggesting a non-null default such as
`coalesce(<key>, '')` (see **An SQL declaration's group keys are exactly the target's non-nullable
ordering key**). The argument below against an outright refusal does not carry over: it rests on the form
vocabulary having no way to express the correct declaration, and the SQL declaration has one, the
author's own absence mapping inside the key.

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

#### Scenario: An SQL declaration's WHERE does not admit a nullable key

- **WHEN** an SQL declaration groups by a nullable input column and its `WHERE` reads
  `<column> IS NOT NULL`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key and suggesting
  a `coalesce` wrap, and the `WHERE` is not inspected for null-handling

### Requirement: Sensitive-column governance on the aggregate path

The system SHALL apply the per-column `sensitive` rule to every input column a pipeline references — in
`filter`, `group_by`, and `measures` — at the **declaration gate** and again at execution, against the
acting identity. A pipeline referencing a sensitive input column SHALL be refused for a caller not
entitled to it, with HTTP 403. The rule SHALL follow the grain join: a `sensitive` column of an ACTIVE
enrichment reached through the input source SHALL be governed exactly as a `sensitive` column of the
source itself, in both halves of this requirement.

**The declaration-time half runs when the declaration is armed, previewed, or executed** — not when it
is stored (see [pipeline-registry](../pipeline-registry/spec.md)). An aggregate pipeline is always
registered disabled, so a declaration naming a `sensitive` input column is now **stored** before either
half of this rule has been applied to it, and both halves are applied before anything runs.

**Of the two halves, only the 422 one is observable.** Every surface that runs this gate on the
aggregate path — registration, declaration patch, preview, on-demand refresh — is `FULL_ADMIN`-only, and
the scheduled refresh runs under an identity entitled by construction, so the 403 half is kept as
defence in depth, exactly as on the enrich path (see
[enrichment-registry](../enrichment-registry/spec.md), "Static governance gate on arming and
execution"). Storage is safe because a stored declaration produces no row until it runs, and it cannot
run without passing both halves against the catalog as it stands then — not because a sensitive
column's name is already readable, which on the discovery surface it is not.

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

**Retiming the declaration-time half to the gate SHALL NOT weaken that justification, and the reason
SHALL be stated rather than assumed.** "Total" here means total with respect to *execution*, not with
respect to storage: a scheduled refresh runs only for an enabled pipeline, arming is a declaration
write, and the gate runs on every write that arms — so no declaration reaches the elevated identity
without having passed this rule against the catalog as it stood when it was armed, and the refresh
re-applies it against the catalog as it stands now. The half that does the closing is the 422 one, and
it is unconditional: a target column not declared `sensitive` refuses the declaration whatever the
acting caller's entitlement, and it refuses it before anything can run. That is deliberate rather than
incidental — the elevation exists precisely because the entitlement half cannot speak for a system
identity, so the justification was never resting on the 403 half being reachable.

Without these, a rollup would be a laundering path: `min`/`max` over a sensitive column, or a sensitive
column used as a group key, would copy protected values into a target column carrying no `sensitive`
flag, readable by anyone who can read the rollup. The entitlement check alone does not close it, because a
scheduled refresh runs under a system identity (see below) that is entitled.

#### Scenario: A sensitive column aggregated into an unprotected target column is rejected

- **WHEN** a pipeline whose measure or group key reads a `sensitive` input column, and whose target
  column is not declared `sensitive`, is armed, previewed, or refreshed
- **THEN** the request is rejected with HTTP 422, whatever the acting caller's entitlement

#### Scenario: A caller who may not see sensitive columns never reaches this gate

- **WHEN** a caller not entitled to `sensitive` columns calls registration, declaration patch, preview
  or on-demand refresh at all, with any declaration
- **THEN** the request is refused with HTTP 403 `forbidden` by method security before the declaration
  gate runs, because those surfaces require the same role the entitlement is — so the in-gate
  `sensitive_column_not_entitled` is unreachable here, and the retiming of it changes nothing a caller
  can observe

#### Scenario: Governance is re-applied at execution

- **GIVEN** an enabled pipeline whose declaration passed the gate when it was armed
- **WHEN** an input column it reads is declared `sensitive` afterwards while the target column it feeds
  is not, and a scheduled or on-demand refresh runs
- **THEN** the refresh is refused and records the reason rather than writing derived values from that
  column, because the rule resolves the flag from the catalog at execution and not from the declaration

#### Scenario: A sensitive enrichment column aggregated into an unprotected target column is rejected

- **WHEN** a pipeline whose measure or group key reads a `sensitive` column of an enrichment reached
  through its input source, and whose target column is not declared `sensitive`, is armed, previewed
  while disabled, or refreshed
- **THEN** the request is rejected with HTTP 422 naming both columns, whatever the acting caller's
  entitlement

### Requirement: Scheduled refreshes execute under a system identity

A scheduled refresh SHALL execute under an internal system identity entitled to read `sensitive` columns.
Column-level governance is decided from the acting identity, and a scheduler tick has no caller: without
this, a pipeline an entitled author registered over a `sensitive` column would validate, preview, and
refresh
on demand, yet be refused on every tick — permanently, and only where security is enabled. Scheduled
execution has to behave like the on-demand execution it automates.

The elevation SHALL be scoped to one pipeline's refresh and SHALL be unwound afterwards, including when the
refresh fails. A tick refreshes many pipelines on one thread, so an elevation left in place would silently
grant it to whichever pipeline ran next. The exposure the elevation would otherwise create is closed by the
sensitive-target-column rule above, applied at the **declaration gate** rather than at execution.

**Deferring that rule out of the storage moment SHALL NOT weaken this argument, and the reason SHALL be
stated rather than assumed.** The rule no longer runs when a declaration is merely stored, so a pipeline
whose `sensitive` input column lands in a non-`sensitive` target column can now exist in the registry. It
can never be *scheduled*: a tick selects only enabled pipelines, arming is a write that runs the gate in
full, and the gate refuses exactly that declaration. The elevation is therefore still only ever applied to
a declaration the rule has passed — the chokepoint moves from "every write" to "the one write that makes
scheduling possible", and scheduling is what the elevation serves. What changes is only the moment an
author is told, which is the whole point of the gate's re-timing.

The aggregate tick SHALL run on a task scheduler of its own, separate from the one the service's ingestion
jobs use, and its size SHALL be configurable. Refreshes run serially inside a tick, so a slow recompute
does not merely lengthen the tick — on a shared pool it occupies a thread that ingestion also needs. The
two have different consequences when late: a delayed rollup serves a stale number, while delayed ingestion
loses ground against its source, so they SHALL NOT compete for the same budget. Isolation SHALL be the only
effect: refreshes remain serial within a tick, and ticks SHALL NOT overlap by default.

Introducing the dedicated scheduler SHALL NOT disturb the scheduler the ingestion jobs already use, nor the
configuration that sizes it.

#### Scenario: A pipeline over a sensitive column refreshes on schedule

- **WHEN** an enabled pipeline reads a `sensitive` input column into a `sensitive` target column and the
  scheduler tick fires
- **THEN** the refresh executes, rather than being refused for want of a caller

#### Scenario: A declaration the sensitive rule refuses is never scheduled

- **GIVEN** a stored declaration reading a `sensitive` input column into a non-`sensitive` target column,
  which was accepted because the write that stored it left the pipeline disabled
- **WHEN** the pipeline is armed
- **THEN** the request is refused with HTTP 422, the pipeline stays disabled, and no scheduler tick ever
  applies the system-identity elevation to it

#### Scenario: The elevation does not outlive the refresh

- **WHEN** a scheduled refresh completes or fails
- **THEN** the acting identity in effect afterwards is the one that was in effect before it

#### Scenario: A slow refresh does not delay ingestion

- **WHEN** an aggregate refresh occupies its scheduler for longer than the ingestion jobs' interval
- **THEN** the scheduled poll and the daily sync still run on their own schedule, unblocked by it

#### Scenario: The ingestion scheduler keeps its configured size

- **WHEN** the service starts with the scheduled-task pool size configured
- **THEN** the ingestion jobs' scheduler is sized by that configuration, unaffected by the aggregate
  scheduler's presence

### Requirement: Preview executes under a resource budget

The dry-run preview SHALL execute its sample query under an explicit, configured **resource budget** —
a wall-clock limit, a memory ceiling, and a spill-to-disk threshold — carried to ClickHouse as query
settings on the sampling statement alone.

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

### Requirement: Scheduled refresh recomputes every affected group key in full

The system SHALL refresh an enabled `aggregate` pipeline on its declared cron, and on demand via
`POST /v1/pipelines/{name}/refresh`. One refresh SHALL run as two statements:

1. a **probe** that fixes the window end over the input's cursor tuple (its version and identity
   columns), applying the pipeline's `filter` (for an SQL declaration, the statement's own `WHERE`), the
   configured safety lag, and a keyset seek past the stored cursor; then
2. a single `INSERT … SELECT` on the writer credential that recomputes, **in full**, every group key the
   probed window touched — the set of dirty keys being a sub-select over exactly that window — and stamps
   the target's version column.

Recomputing each affected key in full SHALL be the contract, not an optimization detail. The target
collapses by key, so a write **replaces** rather than accumulates: a partial per-window aggregate would
overwrite a bucket's running total with only the newest slice. Full recomputation of the dirty keys is
the only shape that is correct under `upsert_by_key`, and it gives two further properties:

- **Idempotence** — a retried or duplicated run re-probes the same window, derives the same key set, and
  writes the same values, which the target collapses. A refresh that fails or times out therefore leaves
  the target unchanged for that window and is safe to repeat, and concurrent runs on multiple instances
  need no coordination.
- **Late-arriving data self-heals** — a row arriving for an already-materialized bucket re-dirties that
  key, and the next refresh recomputes the whole bucket rather than adding to it.

A refresh SHALL advance the pipeline's stored cursor only after the write succeeds. A refresh whose probe
matches nothing SHALL be a no-op that leaves the cursor unmoved.

Refresh SHALL be bounded: the probe SHALL apply a configured window limit, and a run that fills its limit
SHALL report that more work remains so the next tick continues from the new cursor.

When the declaration carries a time bucket, the recompute SHALL additionally be bounded to the span of the
buckets the window dirtied, on the bucketed column itself. This narrows nothing — every row of a dirty
bucket lies inside that span, so the recomputed values are identical — but it is the only bound the engine
can prune on: a tuple of *expressions* tested against a sub-select gives the partition and primary-key
indexes nothing to work with, so without it every refresh reads the whole input. The bound SHALL be derived
and stepped inside the statement rather than marshalled through the service, so the step past the last
bucket stays in the same calendar and time zone the bucketing used.

**When the declaration carries no time bucket, there is no such bound, and the recompute reads the whole
input on every refresh.** This is a property of the grain, not a gap in the implementation. The bucket
bound exists only because a time bucket *implies* it: every row of bucket `b` has its bucketed column
inside `[b, b + one unit)`, so the dirty buckets' own span bounds the rows that can belong to them, on a
raw column an index can prune. A plain group key carries no such implication — a key's rows may lie
anywhere in the input — so the only restriction left is the dirty-key tuple `IN`, which the indexes cannot
use.

A plain group key SHOULD therefore be a primary-key prefix of the input, or carry an index that prunes
usefully at the expected dirty-set size. A key that is neither costs a full input read per refresh, and
that cost is chosen at declaration time — the only moment it can still be changed. Narrowing the
pipeline's `filter` on a column that *is* a primary-key prefix prunes both halves of the write and is the
cheapest lever where such a column is populated.

The absence of a bound SHALL NOT be closed by bounding the recompute on a time column. The recompute must
see **every** row of every dirty key, and neither candidate column carries that guarantee for a plain
key: bounding on the window's own ingest-time axis excludes a key's rows ingested before the window, and
bounding on the event-time column excludes a key's rows whose events fall outside the window's span.
Either would produce plausible measures with nothing recording the loss — precisely the failure the
"recompute every affected group key in full" contract exists to prevent.

A time bucket SHALL be aligned in **UTC**, pinned explicitly in the compiled statement rather than inherited
from the bucketed column's declared zone (which, when the column declares none, is the ClickHouse server's
— deployment configuration). A `day` bucket therefore means UTC midnight on every deployment, and the same
declaration over the same rows is reproducible across them. Leaving it to the column would make a rollup's
day boundary a property of where it runs, and a server-zone change would re-align every future bucket while
already-materialized ones kept the old boundary — two alignments in one column, indistinguishable in the
output, since both render a bucket start of `00:00:00`.

An SQL declaration's time bucket SHALL be held to the same alignment. A bucket function takes no
time-zone argument, and every bucket call SHALL be compiled pinned to UTC (see **An SQL declaration
refreshes under the form path's contract**).

#### Scenario: A time-bucketed refresh does not read the whole input

- **WHEN** a pipeline grouping by a time bucket refreshes over a window covering a few buckets
- **THEN** the recompute is bounded to those buckets' span on the bucketed column, and the measures are the
  same as they would be unbounded

#### Scenario: A grain with no time bucket has no prunable bound

- **WHEN** a pipeline grouping only by plain columns refreshes
- **THEN** the recompute carries the dirty-key restriction alone, with no bound on any raw column, so it
  reads the whole input; the measures are correct, and the cost follows from the grain rather than from
  the size of the window

#### Scenario: Bucket alignment does not depend on the server's time zone

- **WHEN** a pipeline groups by a `day` bucket over a timestamp column that declares no time zone
- **THEN** the compiled statement pins the alignment to UTC, so each bucket starts at UTC midnight rather
  than at midnight in the ClickHouse server's zone

#### Scenario: A bucket recomputed after new rows arrive holds the total, not the increment

- **WHEN** a rollup bucket has been materialized from 10 input rows, 5 more rows land in the same
  bucket, and a refresh runs
- **THEN** the bucket's measures reflect all 15 rows

#### Scenario: Re-running a refresh changes nothing

- **WHEN** a refresh runs and then the identical refresh is run again with no new input rows
- **THEN** the target's rows and measures are unchanged

#### Scenario: A late row corrects its bucket rather than double-counting

- **WHEN** a row whose event time falls in an already-materialized bucket is ingested and a refresh runs
- **THEN** that bucket is recomputed to include the late row and no second row appears for the key

#### Scenario: A failed write leaves the cursor unmoved

- **WHEN** the `INSERT … SELECT` fails
- **THEN** the target is unchanged for that window, the stored cursor is not advanced, and the failure is
  reported without leaking ClickHouse internals

#### Scenario: An empty window is a no-op

- **WHEN** a refresh runs and the probe matches no rows
- **THEN** no write is issued and the cursor is unchanged

#### Scenario: Only due pipelines run

- **WHEN** the scheduler tick fires and an enabled pipeline's next run time has not arrived
- **THEN** that pipeline is not refreshed on this tick

#### Scenario: An SQL declaration's probe filters by its WHERE

- **GIVEN** an armed SQL declaration whose `WHERE` excludes every row of the next window
- **WHEN** a refresh runs
- **THEN** the probe matches nothing, no write is issued and the cursor is unchanged, exactly as for a
  form declaration whose `filter` excludes those rows

### Requirement: The window probe's cost is independent of its window's size

The probe that fixes a refresh's window SHALL derive both of the facts it needs — the window's end cursor
tuple, and whether the window filled its configured limit — **inside the engine**, returning a result whose
size does not grow with the window. It SHALL NOT transfer the window's rows to the service in order to
inspect them.

The probe needs one tuple and one boolean. Obtaining them by reading the window and taking its last row
satisfies every functional requirement above while moving up to `aggregate.window-limit` rows across the
wire on every tick of every enabled pipeline — 100,000 by default, against a default tick of one minute.
The window limit exists to bound how much a refresh *recomputes*; it is not a budget for how much the
service *reads*, and using it as one makes the cost of scheduling more pipelines superlinear in a
dimension nobody is measuring.

This matches the property the write half of the same refresh states: the derived values are computed
inside ClickHouse and no row payload crosses the wire. A refresh therefore moves a constant amount of data
regardless of how much input its window covers.

The window this yields SHALL be defined solely by its predicates, ordering and limit — the end tuple and
the more-work-remains flag are the ones that window implies — so where the two facts are computed is
invisible in every observable outcome: `rows_written`, `has_more`, `lag_seconds`, the stored cursor, and
the rows written to the target.

#### Scenario: The probe returns a bounded result regardless of window size

- **WHEN** a refresh probes a window containing far more rows than it will report on
- **THEN** the probe's result size does not grow with the number of rows in the window

#### Scenario: The window does not depend on how its bounds are derived

- **WHEN** a refresh runs over a window holding many rows
- **THEN** the stored cursor, `rows_written`, `has_more` and the rows written to the target are those the
  window's own predicates, ordering and limit define — deriving the bounds in the engine rather than in
  the service is not observable in any of them

#### Scenario: A filled window still reports more work remains

- **WHEN** a probe's window fills its configured limit
- **THEN** `has_more` is true and the next refresh continues from the newly stored cursor, exactly as
  before

#### Scenario: An empty window is still a no-op

- **WHEN** a probe matches no rows
- **THEN** no write is issued and the cursor is unchanged

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
  dirties more keys and builds a larger join. Unrestricted, a rollup grouped by
  `usage_client_identity.client_session_id` would hash the whole enrichment to write a handful of rows;
  restricted, the join holds only the dirty keys' rows and writes the same result.
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
  the reason `no_pipeline`

#### Scenario: An enrichment data-sync writes imposes no clamp and is reported as such

- **GIVEN** an enrichment no pipeline targets but a data-sync mapping does (`dial_usage_log_payload`)
- **WHEN** a refresh runs
- **THEN** the window is not held back — the ingest run flushes the enrichment ahead of its source, so a
  visible source row already has its enrichment row — and the state reports the enrichment as
  read-but-unclamped with the reason `ingest_written`, not `no_pipeline`

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
refresh's own axis can be established, and the refusal SHALL name the enrichment and the reason. It SHALL
be enforced at the **declaration gate** — when a write arms the pipeline, on preview in either state,
and at execution (see [pipeline-registry](../pipeline-registry/spec.md)) — rather than when the
declaration is stored, like every other rule on this path. That is what catches an upstream pipeline
switched to a group trigger *after* a reading pipeline was registered: it fails loudly instead of silently
reading unprotected.

Two shapes SHALL be refused:

- an enrichment written by a **group-triggered** pipeline, whose value is derived from a group of source
  rows and changes on every recompute by design. A group's members span arbitrary source positions, so the
  only sound position is the one below which every group has settled — which one long-lived conversation
  pins at its earliest member indefinitely. Admitting it produces a rollup that is silently and permanently
  wrong in both directions.
- an enrichment written by a pipeline **ADAS does not execute itself**, whose write-through position is held
  by the Enrichment Runner and never reported. Class-1 is the only path where ADAS is the writer. An
  **enabled** writer that declares no transform is this shape too — that is the signature of a pre-fold
  write, not a draft.

Two further shapes SHALL NOT be refused, and the distinction is the point.

An enrichment whose writing pipeline reads a table other than this one's input records a position on
*another* axis, which cannot be compared with the one a refresh windows over — but its values are settled
rather than provisional, so refusing it would remove working capability instead of preventing a wrong
number. It is admitted, contributes no clamp, and is reported in state.

An enrichment whose only writer is a **disabled draft whose declaration is incomplete** — any member
its kind requires absent, the `transform` or any other, and whatever its trigger kind, a `group`
trigger included, since that classification is checked first — is admitted,
contributes no clamp, and is reported in state with its own reason (`undeclared_writer`) — never as a
writer ADAS does not execute, which would tell the operator the writer's transform is not `sql` when it has
none. It is admitted rather than refused because the gate re-runs at every refresh: refusing it would let
anyone who begins authoring a draft beside a live rollup's upstream stop that rollup refreshing, for a
pipeline that writes nothing. A draft is driven by nothing, so its absent values are final until it is
armed, on the same terms as an enrichment nothing writes; arming it runs its own gate, after which it
classifies as any other writer and a reading pipeline is refused then if it must be. A **disabled**
writer whose declaration is **complete** is classified by what it declares, exactly as it would be once
armed.

#### Scenario: A group-written enrichment is refused

- **WHEN** a pipeline whose declaration names a column of an enrichment whose writing pipeline uses the
  group trigger is armed or previewed
- **THEN** the request is rejected with HTTP 422 naming the enrichment and the reason

#### Scenario: An enrichment whose writer is an undeclared draft is admitted with its own reason

- **GIVEN** a disabled `enrich` pipeline registered against enrichment `E` with no `transform` declared yet
- **WHEN** an aggregate declaration reading `E.col` is armed, previewed or refreshed
- **THEN** it is not refused on that account, no clamp is imposed for `E`, and the pipeline's state reports
  `E` read-but-unclamped with the reason `undeclared_writer` rather than as a writer whose transform is not
  `sql`

#### Scenario: A group-triggered incomplete draft writer is still undeclared

- **GIVEN** a disabled `enrich` pipeline against enrichment `E` whose `trigger.kind` is `group` and whose
  declaration is incomplete — no `transform` yet, or no `trigger.group_by`
- **WHEN** an aggregate declaration reading `E.col` is armed, previewed or refreshed
- **THEN** `E` is classified `undeclared_writer` — admitted, unclamped — rather than refused as
  group-written, because a draft writes nothing whatever trigger it has declared

#### Scenario: A disabled but complete writer is classified by what it declares

- **GIVEN** a disabled `enrich` pipeline against enrichment `E` whose declaration is complete and whose
  `trigger.kind` is `group`
- **WHEN** an aggregate declaration reading `E.col` is armed or previewed
- **THEN** `E` is classified as group-written and the request is rejected with HTTP 422, as it would be
  were the writer armed

#### Scenario: A runner-executed enrichment is refused

- **WHEN** a pipeline whose declaration names a column of an enrichment written by a pipeline the
  Enrichment Runner executes rather than ADAS is armed or previewed
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
- groups by the keys, orders by the keys ascending, and is limited to `aggregate.preview-rows`, capped at
  the query API's row ceiling (1000) so a larger setting still yields a statement the API runs.

The portable statement reproduces what the declaration computes, not how the preview samples it. It
differs from the sampling statement in three ways, and the response SHALL NOT claim otherwise:

- **Order.** The sample is ordered by each group's latest input row, which reads the input's version
  column, and that column is not exposed to queries. The portable statement orders by the keys. Its rows
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
of the rendered query, not a separate list kept beside it. The note's `subject` SHALL name an output whose
target name shadows an input field, where there is one; it is an attribution, and the message gives the
query path's own reason.

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

### Requirement: An aggregate declaration's enrichment references are protected against drop and rename

An enrichment an aggregate declaration reads SHALL be a dependency of that pipeline, protected exactly as
its declared input already is. `DELETE /v1/tables/{name}` naming such an enrichment SHALL be refused with
HTTP 409 naming the pipelines that read it, and a schema patch that would **drop or rename** a column of
that enrichment which a declaration names SHALL be refused with HTTP 409 on the same basis.

The protection is the same one the enrich declaration has always had, stated here because the aggregate
path did not have it. Its absence was not a decision: the guards parse declarations, and they parsed only
the enrichment registry's — so an enrichment a rollup grouped on was protected only when an enrich
declaration happened to read it too. That is coincidence rather than a guard, and retiring the unrelated
declaration made an enrichment several rollups read droppable, with the failure arriving at the next
refresh instead of at the delete.

The references SHALL be resolved from the declaration's own positions: each group key's input column, each
measure's column, the membership `filter`, and each measure's own `where`. An enrichment column has exactly
one spelling in a declaration's namespace — the qualified flat name — so those four positions are where a
reference can appear, and nothing repoints that name on a rename.

**An SQL declaration SHALL have its references resolved by a token scan of its stored text, never by
parsing it.** A guard runs on operations about other tables, so a parse there would cost a full SQL
front-end pass per stored declaration, and one unparseable draft would either fail an unrelated delete
or, swallowed into an empty set, silently let it through. The scan SHALL collect every qualified
`<enrichment>.<column>` token, and the contents of every double-quoted identifier, lowercased, so that a
reference spelled `alias."<enrichment>.<column>"` is found. The same scan SHALL supply the declaration's
bare source-column reads to the source half of [enrichment-runtime](../enrichment-runtime/spec.md), "A
schema edit may not repoint what a stored declaration reads". For that half the leading
`INSERT INTO <target> (<columns>)` prefix SHALL be excluded, so a target column name does not pin an
input column of the same name. If the prefix cannot be recognised, the whole text SHALL be scanned. A
false positive (a name inside a string literal or a comment) SHALL be accepted: it costs a spurious 409,
where a false negative would let a column be dropped under a stored declaration. An SQL declaration's
enrichment references do not depend on its input resolving, so an unparseable draft contributes whatever
its text names to the **enrichment** half of the guards. Its bare source-column reads SHALL be protected
only once the draft's input table has been derived, because a bare name means nothing until it is tied to
a source; an unparseable draft with no derived input pins no source column. Arming re-validates the
statement against the schema as it stands then, so a source column dropped under such a draft fails at
the gate rather than inside a refresh.

**What a pipeline reads SHALL be resolved from those positions as they are stored, so the protection
SHALL be stated as covering a declaration that is complete and no further.** A declaration may now be
stored before it is finished (see [pipeline-registry](../pipeline-registry/spec.md)), and an incomplete
one under-declares its reads by construction: an enrichment column the author has not yet typed into a
group key, a measure or a filter contributes no reference, so the column stays droppable and the
enrichment stays deletable. The bound applies to **`DELETE /v1/tables/{name}` exactly as it applies to
the schema patch** — both resolve the same references from the same stored declarations, and bounding
one while stating the other absolutely would describe a distinction the resolution does not make.

**The consequence SHALL be explicit rather than left to be discovered**, because it is the price of the
bound and it is a price worth naming: completing such a declaration afterwards fails at the declaration
gate, naming the column as unknown, and the author retypes or re-declares. What does not happen is the
failure this guard exists to prevent — a stored declaration silently reading a column that was dropped
under it — because a declaration that was never gated cannot be armed, previewed or executed without
being gated first, against the catalog as it stands then.

The column check SHALL be **per column**, not per table: a schema change to a column of the same enrichment
that no declaration names SHALL be permitted. A guard that froze an enrichment's whole schema because one
of its columns entered a rollup would be as real a failure as the missing guard it replaced.

A declaration whose input no longer resolves SHALL contribute no references rather than failing the
operation. The alternative is a delete or a patch about one table failing because an unrelated declaration
has drifted, which is the same posture the enrich guard takes.

**This SHALL be reported, not repaired.** The refusal names the pipelines; retiring or re-declaring them
stays the operator's action, and no cascade is performed.

#### Scenario: Deleting an enrichment a rollup groups on is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column an aggregate declaration's group key
  reads, and no enrich declaration reads it
- **THEN** the request is refused with HTTP 409 naming that pipeline, and the enrichment is still readable

#### Scenario: A reference from a measure's predicate counts

- **WHEN** the only position naming the enrichment column is a measure's `where`, or the pipeline `filter`
- **THEN** the delete is refused just as it is for a group key, because a reference is a reference wherever
  the statement reads it

#### Scenario: Dropping or renaming the referenced column is refused

- **WHEN** a schema patch would drop or rename a column of that enrichment which the declaration names
- **THEN** the request is refused with HTTP 409 naming that pipeline

#### Scenario: An unreferenced column of the same enrichment stays changeable

- **WHEN** a schema patch drops a column of that enrichment which no declaration names
- **THEN** the patch succeeds

#### Scenario: An incomplete declaration protects nothing it has not yet named

- **GIVEN** an `aggregate` pipeline registered with `name`, `kind` and `target` alone, whose author
  intends to group on an enrichment column but has not declared the group key
- **WHEN** `DELETE /v1/tables/{name}` names that enrichment, or a schema patch drops that column
- **THEN** the request succeeds, because the stored declaration carries no reference to it — the same
  answer at the delete and at the patch

#### Scenario: Completing the declaration afterwards fails at the gate rather than silently

- **GIVEN** that enrichment column was dropped while the declaration was incomplete
- **WHEN** the author completes the declaration naming it and arms or previews the pipeline
- **THEN** the request is rejected with HTTP 422 naming the column as unknown, and no refresh has read
  or ever reads a dropped column

#### Scenario: The dependency releases when the reader goes

- **WHEN** the last pipeline reading that enrichment is deleted and the delete is retried
- **THEN** it succeeds

#### Scenario: An enrichment named only in an SQL declaration is protected

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column only an SQL declaration reads, as
  `coalesce("usage_client_identity.client_session_id", '')`
- **THEN** the request is refused with HTTP 409 naming that pipeline

#### Scenario: An aliased quoted reference is found

- **WHEN** an SQL declaration reads the enrichment column as `u."usage_client_identity.client_session_id"`
  through a CTE alias `u`, and a schema patch would drop or rename `client_session_id` on that enrichment
- **THEN** the request is refused with HTTP 409 naming that pipeline

#### Scenario: A bare source column read only by an SQL declaration is protected

- **WHEN** a schema patch would drop or rename the input column `deployment`, which only an SQL
  declaration's `GROUP BY` reads, as a bare identifier
- **THEN** the request is refused with HTTP 409 `conflict` naming that pipeline

#### Scenario: A target column name in the INSERT list does not pin an input column

- **GIVEN** an SQL declaration whose column list names the target column `day`, and whose `SELECT` never
  reads the input column also named `day`
- **WHEN** a schema patch drops the input column `day`
- **THEN** the patch succeeds

#### Scenario: A name inside a string literal may refuse a change

- **WHEN** an SQL declaration's `WHERE` compares a column with the literal `'usage_scope.scope'`, and a
  schema patch would drop `scope` on the `usage_scope` enrichment
- **THEN** the request may be refused with HTTP 409 naming that pipeline, a false positive the token scan
  accepts so that it can never miss a real reference

### Requirement: A non-deterministic declaration is refused at the declaration gate

An aggregate declaration's `filter` and each measure's `where` SHALL be rejected when it calls a
function the query function catalog declares **non-deterministic** — the pipeline-wide rule in
[enrichment-registry](../enrichment-registry/spec.md) ("Every governed SQL fragment is
deterministic"), reported as this capability's HTTP 422 `pipeline_validation_failed`. Like every
other part of this gate, the check SHALL be applied in full **wherever the declaration gate runs**:
when a write leaves the pipeline armed, on `POST /v1/pipelines/{name}/preview` in either
state, and at every execution of the stored declaration (see
[pipeline-registry](../pipeline-registry/spec.md)). So a declaration cannot become non-deterministic
under a stored pipeline by any route that changes what the catalog declares, and it cannot become
runnable by any route that skips a re-check.

**Storing such a declaration is no longer impossible, and this requirement SHALL NOT be written as
though it were.** An aggregate pipeline registers disabled, so a registration arms nothing and the
refusal half does not run: a `filter` calling `now()` can sit in the registry as an unfinished draft.
What the gate guarantees is not that the string was never written down, but that **nothing reads it
without the check** — arming refuses, refresh refuses, and preview refuses because preview runs the
check **itself** rather than inheriting a guarantee from the moment of storage. That distinction is
the whole of the protection now: every consumer of a stored declaration re-derives the verdict, so
none of them may assume a previous one.

The reason is this capability's own: a scheduled refresh recomputes every affected group key **in
full**, so a predicate that moves with the clock would recompute a historical bucket over a
*different* row set and silently rewrite a number somebody has already read and cited — the exact
failure the rest of this gate exists to prevent, arriving without an error to point at. A rollup's
time scoping belongs in its group key and its refresh window, both of which are recorded; a moving
`filter` is scoping nothing can reconstruct after the fact.

#### Scenario: An aggregate filter reading the clock is refused when the pipeline is armed

- **GIVEN** an aggregate pipeline stored with a `filter` of `request_time >= now() - INTERVAL 7 DAY`
- **WHEN** a write attempts to leave it enabled
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, and the
  pipeline stays disabled

#### Scenario: A measure predicate reading the clock is refused

- **WHEN** an aggregate pipeline declaring a measure whose `where` calls `now()` is armed, previewed,
  or refreshed
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Preview runs the determinism check itself

- **GIVEN** a disabled aggregate pipeline whose stored `filter` calls `now()` — a declaration the
  registry accepted because the registration armed nothing
- **WHEN** `POST /v1/pipelines/{name}/preview` is called
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` naming the
  function, and no statement and no sample are returned

#### Scenario: A stored clock-reading declaration never runs

- **GIVEN** the same disabled pipeline
- **WHEN** the scheduler selects work, or the pipeline is refreshed on demand
- **THEN** nothing executes the declaration: the scheduler drives only enabled pipelines, arming it is
  refused by the check above, and an on-demand refresh of a disabled pipeline is HTTP 409 — so the
  target is never written from a predicate that moves with the clock

#### Scenario: Date arithmetic over a column still registers

- **WHEN** an aggregate pipeline is declared with a `filter` of
  `date_sub('day', 1, request_time) < _ingested_at`
- **THEN** the declaration is accepted — it is the clock that is refused, not date arithmetic

### Requirement: Pipeline endpoints are authorized by registry privileges

The pipeline surface SHALL be authorized by privilege rather than by application role, for both kinds.
Reading pipelines — `GET /v1/pipelines`, `GET /v1/pipelines/{name}` and
`GET /v1/pipelines/{name}/revision/{revision}` — SHALL require `REGISTRY_READ`. Declaring, updating and
deleting them — `POST /v1/pipelines`, `PATCH` and `DELETE /v1/pipelines/{name}` — SHALL require
`REGISTRY_ADMIN`.

Executing a stored declaration SHALL also require `REGISTRY_ADMIN`:
`POST /v1/pipelines/{name}/refresh` writes rows chosen entirely by whoever authored the declaration,
and `POST /v1/pipelines/{name}/preview` returns that declaration's output over the **entire** input,
unwindowed, with a projection the caller did not choose. Neither is usefully separable from
authoring, so no distinct execute privilege SHALL be introduced; `DATA_SYNC_OPERATE` SHALL NOT
authorize them.

Pipeline reads are reachable today by any application role, so `REGISTRY_READ` reproduces that reach
exactly: the `READ_ONLY_ADMIN` bundle holds it, and a caller that maps to no privilege loses a read it
never had a reason to hold. No endpoint in this change widens.

#### Scenario: Pipeline reads require REGISTRY_READ

- **WHEN** a caller holding the `READ_ONLY_ADMIN` bundle lists pipelines
- **THEN** the response is `200` with the declared pipelines
- **AND WHEN** a caller holding only `TABLE_READ` lists pipelines
- **THEN** the response is `403 forbidden`, because a table privilege confers no registry read

#### Scenario: Refresh requires REGISTRY_ADMIN

- **WHEN** a caller holding `REGISTRY_READ` and `DATA_SYNC_OPERATE` but not `REGISTRY_ADMIN` invokes
  a pipeline refresh
- **THEN** the response is `403 forbidden`

#### Scenario: Preview requires REGISTRY_ADMIN

- **WHEN** the same caller invokes a pipeline preview
- **THEN** the response is `403 forbidden`

### Requirement: The scheduler's system identity holds the narrowest sufficient privileges

The system principal the scheduler elevates to SHALL hold exactly `TABLE_READ` and the `TABLE_SENSITIVE`
clearance, closed under implication (`TABLE_READ` → `TABLE_GET` → `TABLE_LIST`; the clearance implies
nothing) — globally, and nothing else. It SHALL NOT hold
`TABLE_GRANT`, `TABLE_CREATE`, `TABLE_DELETE` or `REGISTRY_ADMIN`, so scheduled work cannot administer
anything.

Naming the set is what bounds the elevation. Before privileges existed the narrowest grantable thing was
the full administrative role, so the elevation had to be justified in prose rather than by construction;
the justification is now the set itself.

The narrowed principal SHALL be sufficient for every operation a scheduled refresh performs. If
service-layer writes are ever brought under `TABLE_WRITE`, the principal SHALL be extended to hold it,
because a refresh writes its target table.

#### Scenario: A scheduled refresh over a sensitive column succeeds

- **GIVEN** an enabled pipeline whose declaration reads a `sensitive` source column
- **WHEN** the scheduler runs its refresh with no caller present
- **THEN** the refresh executes and writes its target, exactly as an on-demand refresh does

#### Scenario: The system principal cannot administer

- **WHEN** the scheduled work attempts an operation requiring `TABLE_GRANT`, `TABLE_CREATE`,
  `TABLE_DELETE` or `REGISTRY_ADMIN`
- **THEN** the operation is denied, because the system principal holds no such privilege

#### Scenario: The elevated set is closed under implication

- **WHEN** the system principal's authorities are inspected
- **THEN** they are exactly `TABLE_READ`, `TABLE_SENSITIVE`, `TABLE_GET` and `TABLE_LIST`, because
  `TABLE_READ` confers the privileges it implies and a check tests exact containment

### Requirement: A rollup target may not grant a protected column more widely than its input

Where a rollup target **carries** a `sensitive` column, its `sensitive` grantee set SHALL be a
subset of the roles its input table lists under **both** `read` and `sensitive` (every role on the
target's `sensitive` list counts, whatever its `read` grant; the target's `modify` list is not
checked; and a role in the target's `read` list that `roles-mapping` maps to global `TABLE_SENSITIVE`
must be listed under `read` by the input, as `enrichment-runtime` states) — the same condition as the enrichment rule in `enrichment-runtime`, which
that capability states and defends: the target carrying such a column, **not** the narrower "a pipeline
derives one from a sensitive input column". The narrow reading is the more precise statement of the risk
and is not answerable when a grant is edited, and it is the input table the rollup reads that the subset
is taken against.

It SHALL be enforced at all four of the points `enrichment-runtime` names, reached here through the
pipeline's `input_table` rather than a catalog parent: registration and enable of an `aggregate` pipeline
(rejected `422 pipeline_validation_failed`), `PUT /v1/tables/{name}/access` on either table, and either
schema endpoint when the target first comes to carry a sensitive column (rejected
`422 sensitive_grant_widens`).

**This is a distinct rule from the sensitivity-propagation one above, and the gap between them was a
disclosure.** Requiring the derived column to carry the `sensitive` flag stops it being served to an
unentitled caller; it says nothing about *who* is entitled. A rollup target is a `SOURCE` — a
first-class queryable entity with its own grant lists and, unlike an enrichment, **no catalog
parent** — so a rule that walked source/enrichment parentage never examined it at all. The result was
more direct than the enrichment path: a rollup is addressable on its own, so a caller granted
sensitive-value reads on the target needed no grant whatsoever on the table being aggregated, and the
scheduler's elevated identity did the copying.

#### Scenario: A laxer rollup target is refused at registration

- **GIVEN** `dial_usage_log.jwt_claims` is `sensitive` and `dial_usage_log`'s `read` and `sensitive`
  lists both name `pii-team`, and no other role is on both
- **WHEN** an `aggregate` pipeline is registered whose target declares a `sensitive` column derived
  from `jwt_claims`, and whose target's `sensitive` list names `analysts`
- **THEN** the registration is rejected `422 pipeline_validation_failed`, naming the widening role
  and both tables

#### Scenario: Granting the rollup target more widely is refused

- **GIVEN** the same pipeline registered with matching lists
- **WHEN** an operator grants `sensitive` on the rollup target to a role the input does not list
  under both `read` and `sensitive`
- **THEN** the request is rejected `422 sensitive_grant_widens` and neither list changes

#### Scenario: Narrowing the input walks its rollup targets

- **WHEN** an operator revokes `sensitive` or `read` on the input table from a role while a rollup
  target of it still names that role under `sensitive` and carries a sensitive column
- **THEN** the request is rejected `422 sensitive_grant_widens`

### Requirement: Array-consuming aggregates are query-time only

A measure `fn` naming an array-consuming aggregate SHALL be refused by the declaration gate. The two
added for the query surface — `groupUniqArrayArray` and `groupArrayArray` (see
[structured-query](../structured-query/spec.md), "Array-valued aggregates over an array field") — are
refused when the pipeline is armed or previewed, with HTTP 422 and a message that names the function, states that a measure's
input column must be a scalar, and points the author at aggregating it at query time or deriving a
scalar column on the input first. The refusal is needed because the measure vocabulary is derived
from the **aggregate group of the shared query function catalog**, so a new aggregate there widens it
by default.

The refusal is stated here rather than left to the existing array-measure rule on purpose. That rule
refuses an `array` input because "a set of arrays is not a declarable column type" — true for
`group_uniq_array`, and the wrong reason for a function whose whole purpose is to flatten the arrays
it consumes. A refusal carrying a reason that does not hold is worse than no message, because the
author's next move is to work around a constraint that is not the real one.

This keeps the property the vocabulary already has: a measure is **one function over one input
column**, and an aggregate whose operand is not a scalar cannot be expressed as one. It is the same
outcome `arg_max` already gets — in the catalog, available at query time, refused as a measure — by
the same argument.

The two gates are keyed differently, and the difference is worth knowing. `arg_max`'s is keyed on a
property the catalog descriptor carries — its required arity — so a two-operand aggregate added
later is refused without anyone remembering to. This one cannot be: the descriptor does not say what
*type* an operand takes, so the gate tests membership of the array-consuming set the query surface
already maintains. A third array-consuming aggregate is therefore covered only once it joins that
set, which is the same edit that makes the query surface accept it.

Lifting it later means deciding what a flattening measure's target `element_type` must equal (the
input's `element_type`, not its declared type, which is the array itself) and whether such a measure
is re-aggregatable across buckets. Both are answerable; neither is answered here.

#### Scenario: A flattening aggregate is refused as a measure

- **WHEN** an aggregate declaration declaring a measure `fn: "groupUniqArrayArray"` over an `array`
  input column, bound to an `array` target column whose `element_type` matches the input's elements, is
  armed or previewed
- **THEN** it is rejected with HTTP 422 naming the function and stating that a measure's input column
  must be a scalar, and the pipeline is not armed

#### Scenario: The query surface is unaffected by the refusal

- **WHEN** the same aggregation is expressed as a query against the input entity
- **THEN** it executes normally, because the restriction is on materializing the measure, not on
  computing it

### Requirement: An aggregate pipeline may be declared as one SQL statement

An `aggregate` pipeline's `spec` SHALL accept, in place of the form declaration (`trigger.group_by`,
`measures`, `filter`), a single aggregate-only field **`sql`** holding exactly one
`INSERT INTO <target> (<column>, …) SELECT …` statement. The two declarations SHALL be mutually
exclusive: a request carrying `sql` together with `trigger.group_by`, `measures` or `filter` SHALL be
rejected with HTTP 422 `pipeline_validation_failed` naming the conflicting field. `filter` is checked on
its own, because it is a field the `enrich` kind shares.

`target` SHALL stay required, and the statement's `INSERT` target SHALL equal it, or the declaration
SHALL be rejected with HTTP 422 `pipeline_validation_failed` naming both. `inputs` SHALL be optional for
an SQL declaration. When it is omitted, the input SHALL be the statement's single source. When it is
given, it SHALL equal that source, or the declaration SHALL be rejected with HTTP 422 naming both.

ADAS SHALL **parse and re-render** the statement and SHALL NOT pass the authored text to ClickHouse. The
text is an authoring format. What executes is a statement ADAS compiles from the parsed block, and every
governance gate of the form declaration applies to it (see the requirements that follow). The authored
text SHALL be stored **verbatim**, and `GET /v1/pipelines/{name}` SHALL return it unchanged, comments and
layout included. Export and audit SHALL carry the same text.

#### Scenario: An SQL declaration is stored and returned verbatim

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline whose `sql` is
  `INSERT INTO usage_daily (day, deployment, tokens) SELECT date_trunc('day', request_time), deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2`
  with a `target` of `usage_daily` and no `inputs`
- **THEN** the pipeline is registered, its input is `dial_usage_log`, and `GET /v1/pipelines/{name}`
  returns the `sql` text byte for byte, with no `measures` and no `trigger.group_by`

#### Scenario: An SQL declaration mixed with a form declaration is refused

- **WHEN** a registration or patch carries `sql` together with `measures` or `trigger.group_by`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the conflicting field,
  and nothing is stored

#### Scenario: An SQL declaration with a filter is refused

- **WHEN** a registration or patch carries `sql` together with `filter`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `filter` and stating
  that the statement's own `WHERE` is the membership predicate

#### Scenario: An INSERT target other than the pipeline's target is refused

- **WHEN** the statement inserts into `usage_hourly` and the pipeline's `target` is `usage_daily`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both tables

#### Scenario: Declared inputs that disagree with the statement are refused

- **WHEN** the pipeline declares `inputs` of `dial_usage_log` and the statement reads `turns`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both tables

#### Scenario: The executed statement is ADAS's rendering, not the authored text

- **GIVEN** an armed SQL declaration whose text carries comments and a `date_trunc` with no time zone
- **WHEN** a refresh runs
- **THEN** the statement sent to ClickHouse is the one ADAS compiled, carrying the UTC pin, the window and
  the dirty-key restriction, and none of the authored comments, while the stored `sql` is unchanged

### Requirement: An SQL declaration is exactly one INSERT … SELECT naming every target column

The `sql` field SHALL hold exactly one statement, an `INSERT INTO <target> (<columns>) <query>` whose
source is a query. Any other shape SHALL be rejected with HTTP 422 `pipeline_validation_failed`, with a
message that names the clause at fault. A trailing `;` SHALL be tolerated.

The column list SHALL be present and SHALL name every column of the target except `_updated_at`, each
exactly once. This is the form path's rule that every target column is written and none defaults. The
column list SHALL map to the source's select list **by position**, and the two SHALL have the same length.
`_updated_at` SHALL NOT be named, because ADAS stamps it.

The SQL front end's known limits SHALL surface as a 422 naming the problem rather than a server error:
backtick quoting (only double-quoted identifiers are supported), a `SETTINGS` clause, and a `FORMAT`
clause. ADAS SHALL screen for `FORMAT` before parsing, because the parser can otherwise read it as JSON
value syntax and report a misleading error. The screen SHALL refuse a `FORMAT JSON`
and a trailing `FORMAT <name>` clause, and SHALL NOT refuse a column or alias that is merely named
`format`.

#### Scenario: More than one statement is refused

- **WHEN** `sql` holds two statements separated by `;`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that exactly one
  statement is accepted

#### Scenario: A bare SELECT is refused

- **WHEN** `sql` holds a `SELECT` with no `INSERT INTO`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the
  declaration is an `INSERT INTO <target> (<columns>) SELECT …`

#### Scenario: An INSERT with no column list is refused

- **WHEN** `sql` is `INSERT INTO usage_daily SELECT …` with no column list
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the column
  list is required

#### Scenario: A VALUES source is refused

- **WHEN** `sql` is `INSERT INTO usage_daily (day, deployment, tokens) VALUES (…)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the source
  must be a `SELECT` over the input

#### Scenario: A column list missing a target column is refused

- **WHEN** the target declares `day`, `deployment` and `tokens`, and the column list names only `day` and
  `deployment`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `tokens`

#### Scenario: A column list naming a target column twice is refused

- **WHEN** the column list names `deployment` twice
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the duplicated column

#### Scenario: A column list naming _updated_at is refused

- **WHEN** the column list names `_updated_at`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that ADAS stamps
  `_updated_at` itself

#### Scenario: A column list and a select list of different lengths are refused

- **WHEN** the column list names three columns and the `SELECT` projects four expressions
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` giving both counts

#### Scenario: A SETTINGS or FORMAT clause is refused

- **WHEN** the statement ends in a `SETTINGS` or a `FORMAT` clause
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the clause, rather
  than a validation error about grouping or conditions

#### Scenario: A column named format is not mistaken for a FORMAT clause

- **WHEN** the statement reads an input column, or names an alias, called `format`
- **THEN** the `FORMAT` screen does not refuse the statement

### Requirement: An SQL declaration reads its input once and nothing else

An SQL declaration's source SHALL reference the pipeline input exactly once, and SHALL reference no
other entity. Enrichments of the input SHALL be reachable only through their qualified flat names
(`"<enrichment>.<column>"`) and the implicit grain join, as in the form declaration (see **An aggregate
pipeline reads its input entity, not only its input table**). Each of the following SHALL be rejected
with HTTP 422 `pipeline_validation_failed` naming the construct:

- a reference to any entity other than the input, an enrichment table named directly included;
- a second reference to the input. References SHALL be counted per occurrence, so a self-join, or a CTE
  over the input read twice, is refused even though it names one table;
- an explicit `JOIN`;
- a set operation (`UNION`, `INTERSECT`, `EXCEPT`);
- a subquery used as an expression (scalar, `IN`, `EXISTS`);
- `SELECT *` at any level, because it expands differently under the declaring caller's visibility and the
  system identity's;
- a user-written `FINAL` modifier or hint, because pipeline reads collapse versions with the query-level
  `final = 1` setting and never with the keyword.

#### Scenario: A second entity is refused

- **WHEN** the statement reads `dial_usage_log` and also names `turns`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `turns` and stating
  that only the input and its enrichments may be read

#### Scenario: A self-join through a CTE counts as two references

- **WHEN** the statement declares `WITH t AS (SELECT … FROM dial_usage_log)` and reads `t` twice, joined
  to itself
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the input is
  referenced more than once

#### Scenario: An explicit join is refused

- **WHEN** the statement joins the input to one of its enrichments with an explicit `LEFT JOIN`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that enrichment
  columns are read through `"<enrichment>.<column>"` and the implicit grain join

#### Scenario: A set operation is refused

- **WHEN** the statement's source is a `UNION ALL` of two selects over the input
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the set operation

#### Scenario: A subquery expression is refused

- **WHEN** the statement's `WHERE` reads `deployment IN (SELECT …)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the subquery

#### Scenario: SELECT star is refused

- **WHEN** a CTE or derived table in the statement projects `*`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` asking for the columns to be
  named

#### Scenario: A user-written FINAL is refused

- **WHEN** the statement reads `FROM dial_usage_log FINAL`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that ADAS collapses
  versions itself

### Requirement: An SQL declaration flattens to one aggregating block

An SQL declaration's source SHALL translate to **one** aggregating query block, or SHALL be rejected
with HTTP 422 `pipeline_validation_failed` naming the clause. CTEs and derived tables that only filter
rows or compute columns SHALL be accepted, because they fold into that block. A source that seals into a
nested block (an aggregate, a `DISTINCT`, a sort or a window below the top level) SHALL be refused,
because the dirty-key restriction and the author's `WHERE` must apply at the same level for a dirty group
to be recomputed in full.

The block SHALL be an aggregation with a `GROUP BY`, and SHALL carry none of `HAVING`, `DISTINCT`,
`ORDER BY`, `LIMIT`/`OFFSET` or a window function. Each would make the written rows depend on which keys a
window happened to dirty, rather than on each key's own rows.

A window function anywhere in the statement, and a top-level `GROUP BY` expression that no select item
projects, SHALL each be refused **by name**, not as a nested block. The parser seals the aggregating block
around either one. Reporting the result as "a nested block carrying DISTINCT / an aggregate" would name a
construct the author never wrote.

#### Scenario: A CTE that only filters and computes is accepted

- **WHEN** the statement reads from `WITH chat AS (SELECT chat_id, total_tokens * 2 AS weighted FROM dial_usage_log WHERE chat_id != '')`
  and groups `chat` by `chat_id`
- **THEN** the declaration is accepted as one block over `dial_usage_log`

#### Scenario: An aggregate below the top level is refused

- **WHEN** a derived table in the statement aggregates, and the outer select aggregates its result
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the statement
  must flatten to one aggregating block

#### Scenario: A statement with no GROUP BY is refused

- **WHEN** the statement's select carries no aggregation and no `GROUP BY`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the block must
  be an aggregation

#### Scenario: HAVING is refused

- **WHEN** the statement carries `HAVING count(*) > 10`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `HAVING`

#### Scenario: DISTINCT is refused

- **WHEN** the statement's select is `SELECT DISTINCT …`, at the top level or in a derived table
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `DISTINCT`

#### Scenario: ORDER BY is refused

- **WHEN** the statement carries an `ORDER BY`, at the top level or in a derived table
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `ORDER BY`

#### Scenario: LIMIT is refused

- **WHEN** the statement carries a `LIMIT` or an `OFFSET`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the clause

#### Scenario: A window function is refused

- **WHEN** the statement's select carries `row_number() OVER (…)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the window function

#### Scenario: A window function over the aggregate is refused by name

- **WHEN** the statement selects `count(*) OVER ()` beside its `GROUP BY`, or a CTE computes
  `row_number() OVER (…)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the window function,
  not a nested block or `DISTINCT`

#### Scenario: An unprojected GROUP BY expression is refused by name

- **WHEN** the statement groups by `date_trunc('day', request_time), date_trunc('hour', request_time)` and
  selects only the day bucket
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the hour expression as
  a GROUP BY key no select item projects, not as a nested block carrying an aggregate

### Requirement: An SQL declaration's group keys are exactly the target's non-nullable ordering key

The block's `GROUP BY` keys SHALL equal the target's `ordering_key` **as a set**, or the declaration SHALL
be rejected with HTTP 422 `pipeline_validation_failed` reporting both lists. Each key SHALL be matched to
the target column at the same position of the column list as the key's select expression. Order is not
compared, because the parser may reorder the group set.

Each key SHALL satisfy all of the following, or be rejected with HTTP 422 `pipeline_validation_failed`
naming the key:

- **It reads the input row.** A key SHALL reference at least one field of the input entity: a source
  column, an enrichment column, or a deterministic expression over them. A constant key is refused.
- **It is not nullable.** Under the pinned `transform_null_in = 0` a NULL key component never matches the
  dirty-key tuple `IN`, so a NULL group would never be recomputed. The refusal SHALL suggest wrapping the
  key in a non-null default such as `coalesce(<key>, '')`. A `filter` is not an answer here, as it is on
  the form path, because an SQL declaration has none (see **A nullable group key requires a declared
  filter**).
- **An enrichment column under it can represent absence.** An enrichment column that a key reads, bare or
  inside an expression, SHALL be declared nullable (see **An enrichment-sourced value is readable only
  where absence is representable**). The check applies to the enrichment column, not to the key's final
  nullability: `coalesce("<enrichment>.<column>", '')` is the author's explicit absence mapping, and it is
  sound only when the absence is a NULL to map.
- **Its type equals the target column's type.** A key's type SHALL equal its target column's declared
  type exactly, as on the form path. A time bucket SHALL bind to a temporal type wide enough for its unit
  (see **Target shape governance**), so an hour bucket into a `date` column is refused.
- **At most one key is a time bucket**, and a bucket SHALL have a step of one unit. A bucket such as
  `date_bin(2, 'hour', t)` is refused, because the refresh's pruning bound steps by one
  unit and would drop the rest of the bucket's rows.
- **A bucket is `date_trunc(<unit>, t)` or `date_bin(1, <unit>, t)` over a unit in `{hour, day, week, month}`**,
  the form path's set. Any other unit SHALL be refused `422 pipeline_validation_failed` as an unsupported
  bucket unit.
- **It calls no row-expanding function.** A key whose expression calls `array_join` SHALL be refused,
  because the refresh restricts each recompute by the key, and that restriction is not proven to select
  the same expanded rows as a full recompute. The form path cannot declare such a key either.

#### Scenario: A key set equal to the ordering key in another order is accepted

- **WHEN** the target's `ordering_key` is `(day, deployment)` and the statement groups by
  `deployment, date_trunc('day', request_time)`, writing them to `deployment` and `day`
- **THEN** the declaration is accepted

#### Scenario: A key set that differs from the ordering key is refused

- **WHEN** the target's `ordering_key` is `(day, deployment)` and the statement groups only by
  `date_trunc('day', request_time)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` reporting both key lists

#### Scenario: A constant key is refused

- **WHEN** the statement groups by `'all'` and writes it to an `ordering_key` column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that a key must read
  the input row

#### Scenario: Two time buckets are refused

- **WHEN** the statement groups by `date_trunc('day', request_time)` and `date_trunc('hour', response_time)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that at most one time
  bucket is allowed

#### Scenario: A bucket with a step other than one is refused

- **WHEN** the statement groups by `date_bin(2, 'hour', request_time)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that a bucket must
  step by one unit

#### Scenario: A nullable key is refused with the coalesce remedy

- **WHEN** the statement groups by `"usage_client_identity.client_session_id"`, a nullable enrichment
  column, with no wrap
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key, stating that
  a NULL group is never recomputed, and suggesting `coalesce("usage_client_identity.client_session_id", '')`

#### Scenario: A coalesced enrichment key is accepted

- **WHEN** the statement groups by `coalesce("usage_client_identity.client_session_id", '')` and writes it
  to a non-nullable `string` target column
- **THEN** the declaration is accepted

#### Scenario: A key over a non-nullable enrichment column is refused

- **WHEN** the statement groups by `coalesce("usage_scope.scope", '')` and `usage_scope.scope` is declared
  `nullable: false`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the column and its
  enrichment, and stating that an unmatched outer join renders it as its type default rather than as NULL

#### Scenario: An hour bucket into a date column is refused

- **WHEN** the statement writes `date_trunc('hour', request_time)` to a target column declared `date`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, because the hour would be
  truncated on insert and the day's buckets would collapse onto one

#### Scenario: A row-expanding key is refused

- **WHEN** the statement groups by `array_join(execution_path)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key and stating
  that a row-expanding function cannot form a group key

#### Scenario: A key of a different type than its target column is refused

- **WHEN** the statement writes a `string` key to a target column declared `long`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the key, its type and
  the target column's type

### Requirement: An SQL declaration's expressions are deterministic and typed as the form path's are

Every expression of an SQL declaration's block SHALL call only functions the shared query function
catalog lists, and SHALL call none the catalog declares **non-deterministic**. This covers the select
list, the `WHERE`, the `GROUP BY`, and every folded CTE or derived table. A violation SHALL be rejected with HTTP 422
`pipeline_validation_failed` naming the function. The reason is the one given in **A non-deterministic
declaration is refused at the declaration gate**: a recomputed historical group must see the same rows
and the same values every time.

The measure expressions (the non-key select expressions) SHALL be held to the form path's type and
nullability rules, reproduced over the expression's own type rather than approximated. Each rule SHALL be
rejected with HTTP 422 `pipeline_validation_failed` naming the target column:

- an expression whose result carries a fraction (an `avg`, a division, a `sum` over a `decimal`) SHALL
  require a `decimal` target column;
- an expression whose result can be NULL SHALL require a nullable target column. A `/` or `%` result
  SHALL count as possibly NULL (a zero divisor yields NULL), and so SHALL a `CASE` with no `ELSE`. An
  expression reading an enrichment column SHALL count as nullable whatever that column declares, and the
  enrichment column SHALL be declared nullable (see **An enrichment-sourced value is readable only where
  absence is representable**). A column read **only in a `CASE WHEN` condition of a `CASE` inside an
  aggregate's arguments** is a row-membership read, as a form `measures[].where` is, so it SHALL NOT make
  the result nullable and its enrichment column SHALL NOT be held to the absence rule. A `CASE` above the
  aggregate, such as `CASE WHEN max(x) = '' THEN 0 ELSE 1 END`, decides the value written, so its condition
  gets no exemption;
- an array result SHALL bind to an `array` target column whose `element_type` equals the collected
  values' type, and an array-consuming aggregate SHALL be refused as on the form path (see
  **Array-consuming aggregates are query-time only**);
- an expression result with neither a numeric return class nor a numeric or array type (a `string`,
  `boolean`, `date` or `timestamp` result) SHALL bind to a target column of the same type. A string result
  MAY also bind to an `enum` or `uuid` column, because the planner types those operands as strings. ClickHouse
  would otherwise convert on insert, truncating silently (a timestamp into a `date`) or failing inside a
  refresh.

A `DISTINCT` quantifier inside an aggregate call SHALL be held to the form path's `distinct` rule, with
one exception: `min(DISTINCT x)` and `max(DISTINCT x)` SHALL be accepted in an SQL declaration, because
the quantifier cannot change their result, although the form path refuses `distinct` for them. Every other
`DISTINCT` aggregate the form path refuses SHALL stay refused with HTTP 422 `pipeline_validation_failed`
naming the function.

#### Scenario: A distinct min or max is accepted

- **WHEN** the statement writes `max(DISTINCT total_tokens)` to a target column of matching type
- **THEN** the declaration is accepted, unlike a form measure declaring `distinct: true` on `max`

#### Scenario: A distinct aggregate the form path refuses stays refused

- **WHEN** the statement writes `group_uniq_array(DISTINCT deployment)`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the function

#### Scenario: A non-deterministic function is refused

- **WHEN** the statement's `WHERE` reads `request_time >= now() - INTERVAL 7 DAY`, or a measure calls
  `rand()`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the function

#### Scenario: A function outside the catalog is refused

- **WHEN** the statement calls `countIf(success)`, which `GET /v1/queries/sql-functions` does not list
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the function and
  pointing at the SQL function catalog

#### Scenario: An expression measure is accepted

- **WHEN** the statement's measures are `sum(prompt_tokens * deployment_price)` and
  `sum(CASE WHEN success THEN 1 ELSE 0 END)`, bound to target columns of matching type and nullability
- **THEN** the declaration is accepted

#### Scenario: A fractional expression into an integer column is refused

- **WHEN** the statement writes `sum(total_tokens) / count(*)` to a target column declared `long`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, because ClickHouse would
  truncate the fraction on insert

#### Scenario: A nullable measure into a non-nullable column is refused

- **WHEN** the statement writes `max("usage_scope.score")` to a target column declared non-nullable
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the target column and
  the enrichment column, because the grain join is outer

#### Scenario: A timestamp expression measure into a date column is refused

- **WHEN** the statement writes `max(date_trunc('hour', request_time))` to a target column declared `date`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the target column and
  both types

#### Scenario: An array measure of the wrong element type is refused

- **WHEN** the statement writes `group_uniq_array(status_code)` over a `long` column to an `array` target
  column whose `element_type` is `string`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both types

### Requirement: An SQL declaration is governed for sensitive columns and enrichment positions as the form path is

The sensitive-column, lineage, grant and enrichment-position rules of the form declaration SHALL apply to
an SQL declaration over **every** column its block references, in the select list, the `WHERE` and the
`GROUP BY`:

- The statement SHALL be resolved at the declaring caller's read entitlement. A column the caller cannot
  see because it is `sensitive` and the caller is not cleared SHALL be refused with HTTP 403
  `sensitive_column_not_entitled`, not reported as an unknown column. The system identity a scheduled
  refresh runs under sees every column, so the remaining rules bite for it and for a cleared caller.
- When the statement cannot be planned at all, and its text mentions a `sensitive` column of the source
  the caller may not read, the refusal SHALL be HTTP 403 `sensitive_column_not_entitled` rather than the
  422 for the planning failure. The mention SHALL be found by an over-approximating token scan of the
  text, so the check fails closed: a protected name anywhere in an unplannable statement answers 403. With
  no declared `inputs`, the check SHALL run against every source table the text names.
- A target column whose select expression reads a `sensitive` column, of the input or of an enrichment,
  SHALL itself be declared `sensitive`, or the declaration SHALL be rejected with HTTP 422
  `pipeline_validation_failed` naming both columns (see **Sensitive-column governance on the aggregate
  path**). Lineage SHALL come from the parsed, inlined select expressions.
- A column read **only in a `CASE WHEN` condition of a `CASE` inside an aggregate's arguments** in a
  measure is a row-membership read, as a form `measures[].where` is. It SHALL be entitlement-gated and
  checked for clampability and aggregatability like every other read, and SHALL be exempt from lineage: it
  decides which rows feed the aggregate, not the value written, so it does not require the target column
  to be `sensitive`. A `CASE` condition in a group key, or in a `CASE` above the aggregate, decides the
  value written and SHALL count for lineage.
- The target's `sensitive` grants SHALL NOT widen past its input's (see **A rollup target may not grant a
  protected column more widely than its input**).
- An enrichment the statement reads SHALL be clampable, and one written by a group-triggered pipeline or
  by a pipeline ADAS does not execute SHALL be refused with HTTP 422 naming the enrichment (see **An
  enrichment whose materialized position cannot be established is not aggregatable**).
- The input SHALL carry the version and identity cursor columns a refresh windows over.

#### Scenario: A sensitive column the caller may not see is refused with 403

- **WHEN** a caller not cleared for `sensitive` columns previews or arms an SQL declaration whose `WHERE`
  alone reads the `sensitive` column `jwt_claims`
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, not a 422 for an unknown column

#### Scenario: A sensitive source column landing in a plain target column is refused

- **WHEN** a cleared caller arms an SQL declaration writing `max(user_email)` to a target column not
  declared `sensitive`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both columns

#### Scenario: A sensitive column read only in a CASE condition does not need a sensitive target

- **WHEN** a caller entitled to the `sensitive` column `user_email` arms an SQL declaration writing
  `sum(CASE WHEN user_email != '' THEN 1 ELSE 0 END)` to a target column not declared `sensitive`
- **THEN** the declaration is accepted, because the column decides row membership and no value of it is
  written

#### Scenario: A sensitive column in a group key's CASE condition needs a sensitive target

- **WHEN** a cleared caller arms an SQL declaration grouping by
  `CASE WHEN user_email LIKE '%@example.com%' THEN 'internal' ELSE 'external' END` into a target column not
  declared `sensitive`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both columns

#### Scenario: An unplannable statement naming a protected column is refused with 403

- **WHEN** a caller not cleared for `sensitive` columns submits an SQL declaration that fails to parse and
  whose text mentions the `sensitive` source column `jwt_claims`
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, not the 422 for the parse failure

#### Scenario: A non-clampable enrichment is refused

- **WHEN** the statement reads a column of an enrichment written by a group-triggered pipeline, or by a
  pipeline the Enrichment Runner executes
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the enrichment and the
  reason

#### Scenario: An input with no cursor columns is refused

- **WHEN** the statement's input declares no version or identity column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the input cannot
  be windowed

### Requirement: An SQL declaration refreshes under the form path's contract

A refresh of an SQL declaration SHALL follow **Scheduled refresh recomputes every affected group key in
full** unchanged: a probe over the input's cursor tuple, then one `INSERT … SELECT` that recomputes every
dirty key in full into the `ReplacingMergeTree(_updated_at)` target, with the cursor advanced only after
the write. The compiled statements SHALL be built from the parsed block as follows.

- **Probe.** The probe SHALL apply the block's `WHERE` as the pipeline's filter, compiled as a `filter`
  is, so it joins the enrichments that `WHERE` names.
- **Dirty-key restriction.** The recompute SHALL add, as a condition of the block's own `WHERE`, the
  group-key tuple `IN` a dirty-key sub-select over the window. That sub-select SHALL apply the block's
  `WHERE` and the window, and SHALL join the enrichments its keys read.
- **Pushdown into the grain join.** The restriction SHALL be passed into the block's compilation, not
  appended after it, so it reaches the enrichment grain join. Where every key reads columns of one and the
  same enrichment, plainly or through an expression such as `coalesce("<enrichment>.<column>", '')`, that
  enrichment SHALL be emitted as a derived table restricted by the dirty keys, as **An aggregate pipeline
  reads its input entity, not only its input table** states for the form path. For a key expression the
  restriction SHALL stay exact. A row whose enrichment row the restriction drops reads as unmatched, and
  the key's image of an unmatched row (the expression with its enrichment columns NULL, `''` for the
  `coalesce` above) may itself be a dirty key. The derived table's `WHERE` SHALL therefore admit every
  enrichment row whenever that image is among the dirty keys, so such a refresh joins the enrichment
  unrestricted rather than miscounting.
- **Bucket bound.** A time-bucket key SHALL bound the recompute to the dirty buckets' span on the bucketed
  column, stepped by one unit inside the statement, as on the form path.
- **UTC.** A bucket function SHALL take no time-zone argument, and every time-bucket call SHALL be rendered
  pinned to UTC, as the form path's buckets are, so the same statement buckets identically on every
  deployment.
- **Rendering.** The recompute SHALL carry no `LIMIT` and no per-table `FINAL` keyword. Versions are
  collapsed by the statement-level `final = 1` and `transform_null_in = 0` the pipeline executor pins.
- **Insert.** The statement SHALL be prefixed with `INSERT INTO "<target>" (<columns>, "_updated_at")`,
  with the columns in the authored column-list order, and `now64(3)` SHALL be appended to the projection.
  It SHALL be executed only by the pipeline's `INSERT … SELECT` executor on the writer credential and
  under the pipeline read budget, never by a user-facing query executor.

The enrichment freshness clamp, the pipeline read budget and the run-state recording SHALL apply
unchanged.

#### Scenario: The dirty-key restriction is in the block WHERE

- **WHEN** an SQL declaration with a `WHERE` of `chat_id != ''` is compiled for a refresh
- **THEN** the recompute's `WHERE` carries both `chat_id != ''` and the group-key tuple `IN` a dirty-key
  sub-select that applies `chat_id != ''` and the window

#### Scenario: An enrichment key restricts the grain join to the dirty keys

- **GIVEN** an SQL declaration grouped only by `"usage_client_identity.client_session_id"` wrapped as
  `coalesce("usage_client_identity.client_session_id", '')`
- **WHEN** a refresh is compiled over a window whose dirty keys do not include `''`
- **THEN** the enrichment is emitted as a derived table restricted by the key expression `IN` the dirty-key
  sub-select, and the target receives the same rows an unrestricted recompute would write

#### Scenario: A dirty absence key keeps the restricted join exact

- **GIVEN** the same declaration, and a window that dirties the `''` key along with others
- **WHEN** a refresh runs over input rows whose enrichment rows carry keys outside the dirty set
- **THEN** those rows do not contribute to the `''` group, and the target receives the same rows an
  unrestricted recompute would write

#### Scenario: A bucketed SQL declaration bounds the recompute

- **WHEN** an SQL declaration grouping by `date_trunc('day', request_time)` refreshes over a window
  covering two days
- **THEN** the recompute is bounded on `request_time` to those days' span, stepped by one day, and the
  measures equal an unbounded recompute's

#### Scenario: A zone-less bucket is pinned to UTC

- **WHEN** an SQL declaration groups by `date_trunc('day', request_time)` with no time zone and the
  ClickHouse server runs in another zone
- **THEN** the compiled statement pins the bucket to UTC, and each bucket starts at UTC midnight

#### Scenario: The recompute carries no LIMIT and no FINAL keyword

- **WHEN** an SQL declaration is compiled for a refresh
- **THEN** the statement carries no `LIMIT`, no `FINAL` keyword after any table, and ends its projection
  with `now64(3)` written to `_updated_at`

#### Scenario: The probe joins the enrichments the statement's WHERE names

- **WHEN** an SQL declaration whose `WHERE` reads `"usage_scope.scope" = 'chat'` is compiled for a refresh
- **THEN** the probe applies that predicate as its filter and carries the `usage_scope` grain join, keyed
  on the window predicates

#### Scenario: Appended rows recompute to the full result

- **GIVEN** an armed SQL declaration that has refreshed its input
- **WHEN** rows are appended to the input, including rows for already-written groups, and a refresh runs,
  and then the same refresh runs again with no new rows
- **THEN** the target equals a full recompute of the statement over the whole input after the first run,
  and is unchanged by the second

#### Scenario: An SQL declaration and its form twin write the same rows

- **GIVEN** a form declaration grouping by a `day` bucket and summing a column, and an SQL declaration
  writing the same keys and sum with a zone-less `date_trunc('day', …)`
- **WHEN** both refresh over the same input into separate targets
- **THEN** the two targets hold identical rows

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
its `GROUP BY`, ordered by the keys ascending and limited as the form path's is. It differs from
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

### Requirement: A refresh restricted through an aligned enrichment pre-selects partitions

A refresh SHALL pre-select partitions when it restricts its recompute to dirty group keys through a column of
an aligned enrichment: the dirty-key `IN` restriction SHALL be treated as a qualifying lookup conjunct and SHALL gain the
partition pre-selection of the structured-query capability ("A lookup on an aligned enrichment pre-selects
the source's partitions"), in both the form declaration (`AggregateCompiler`) and the SQL declaration
(`AggregateSqlCompiler`). The recompute's input SHALL take the restriction in its `WHERE`; the restricted enrichment's
derived table SHALL keep the dirty-key restriction alone, the pre-selection reaching it — and every other aligned join —
through the tuple join; and every join without an aligned partition pair SHALL be keyed on it. In the SQL declaration
the restriction SHALL yield wherever the dirty-key restriction does — when the absence image of the keys is itself
dirty — so that the recompute still reads every partition exactly when unmatched rows belong to a dirty key. The
recomputed aggregates SHALL equal those of the unrestricted recompute for the same dirty keys.

#### Scenario: Recomputing dirty sessions reads only their active days

- **GIVEN** `sessions_rollup` grouping by `usage_client_identity.client_session_id`, aligned
- **WHEN** a refresh recomputes two dirty sessions
- **THEN** the recompute reads `dial_usage_log` and `usage_client_identity` only in the partitions holding
  those sessions' enrichment rows, and writes the same aggregates as before

#### Scenario: The SQL declaration is restricted the same way

- **GIVEN** an SQL-mode aggregate restricted through the same aligned column
- **WHEN** it is refreshed
- **THEN** its leaf restriction carries the same partition pre-selection, and the aggregates it writes equal the
  unrestricted recompute's
