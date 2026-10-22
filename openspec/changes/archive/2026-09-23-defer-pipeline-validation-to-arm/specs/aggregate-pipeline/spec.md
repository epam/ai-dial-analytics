## RENAMED Requirements

- FROM: `### Requirement: A non-deterministic declaration is refused at registration and at refresh`
- TO: `### Requirement: A non-deterministic declaration is refused at the declaration gate`

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
one the author meant is not the one whose name matches the measure — a realistic shape, not a hypothetical.
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
information, and in a typical registry it is among the most repeated members of a declaration.
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

Every refusal preview produces SHALL be **HTTP 422 `pipeline_validation_failed`**, whether the
declaration is **incomplete** — a member the kind requires has not been declared at all — or complete
and defective, and SHALL report one problem at a time, as every other refusal on this surface does. Preview changes when the author is told, never what they are told: the refusal is the one
arming would have produced, against the same catalog, without arming anything.

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
  reported with the status and code arming would give, rather than a statement and a sample

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

### Requirement: Scheduled refreshes execute under a system identity

A scheduled refresh SHALL execute under an internal system identity entitled to read `sensitive` columns.
Column-level governance is decided from the acting identity, and a scheduler tick has no caller: without
this, a pipeline a `FULL_ADMIN` registered over a `sensitive` column would validate, preview, and refresh
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
