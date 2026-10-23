## MODIFIED Requirements

### Requirement: Dry-run preview of an enrich declaration

The system SHALL extend `POST /v1/pipelines/{name}/preview` to the `enrich` kind. It SHALL answer in the
shared preview shape (see [pipeline-registry](../pipeline-registry/spec.md), "The dry-run preview answers
in one shape for both kinds"): a bounded **sample** of what the transform would produce or receive, its
typed `columns`, and coded `notes`, with no row count. It SHALL write nothing to the target and SHALL call no
model, and SHALL be permitted on a disabled pipeline — it is the checkpoint an author has before arming.

**What preview validates SHALL depend on the pipeline's state.** On a **disabled** pipeline preview
SHALL run the kind's full declaration gate before sampling anything, and SHALL refuse an incomplete
declaration with the same HTTP 422 `pipeline_validation_failed` a defective one draws, one problem per
refusal. It SHALL run the checks in the order arming runs them — the trigger's arm checks, then the full
gate — **before** any transform-presence check, so the first problem a disabled preview reports is the
first problem an enable-only patch arming it would report: a preview that passes means arming passes
against the same catalog, and a preview that refuses gives the refusal arming would give. Having gated
the declaration, a disabled preview SHALL sample through **that** declaration, with its output mapping
derived in memory by the gate, rather than through the stored mapping — so a draft whose stored mapping
is stale (its target's schema defined or changed after the draft was saved) previews successfully
whenever arming would succeed. On an
**enabled** pipeline preview SHALL run only the checks it runs today (the template-placeholder check,
the transform's presence and type, and the response-schema composition, whose refusal it keeps although
the schema is no longer returned), and SHALL NOT re-run the gate.

The split is not a convenience; it is the only reading under which both halves stay true. The
narrow check earns its narrowness on an armed pipeline with a stated argument — the preview is
previewing what is *stored*, not re-validating it, and asking the gate again would report a pipeline
as unpreviewable for a drift the operator did not cause (a column renamed on the read source since
the declaration was written, a folded legacy declaration the gate would now reject). That argument
rests entirely on the declaration having passed the gate when it was written, which is true of every
armed pipeline and, once the gate is deferred, true of no draft. A disabled pipeline's declaration
may never have been gated at all, so there is nothing to be loyal to and everything to check:
disabled means "check my work", enabled means "show me a sample". Preview is therefore the author's
diagnostic and the one place a declaration can be judged without arming it — which is what makes
deferring the gate safe to offer rather than merely permissive.

Preview SHALL evaluate the **stored** declaration and SHALL NOT accept a candidate transform in its
request body. A `PATCH` on an enabled pipeline makes the new prompt live at the next sync, so the
checkpoint for a prompt change is `enabled: false`, preview, then enable.

The preview response SHALL NOT carry the transform's `type`, `evaluator_name`, `evaluator_version`, the
response schema, `response_schema_source`, the input bindings or the output mapping. The pipeline read
serves every one of them: `transform.type` and the authored `transform`, the resolved `response_schema`
and `outputs` on `view=compiled`, and the schema's source. A stored `transform.response_schema` is
present on the authored read exactly when the schema is stored rather than composed.

The sample SHALL depend on the transform:

- **A Class-1 `sql` pipeline** SHALL sample the grain key (`grain_key`) and every derived value
  (`output`). Each output carries `type`, the type its translated expression yields (the value the
  registration gate compared against the target column), and `target_type`. `statement` SHALL be the
  declaration's portable `SELECT` in the ADAS SQL dialect, with `query` its structured-query DSL document
  (see the shared shape). It is a row query over the read source's entity that projects the grain key and
  each output's expression under its target column name, applies the pipeline's `filter` as its `WHERE`,
  orders by the grain key ascending and is limited to the sample's row cap. An enrichment column an
  expression or the filter reads is named by its exposed name, with no join. It reproduces what each
  output computes for a source row, not which rows the preview sampled. It carries none of the sample's
  window (the newest-first order, the safety lag, a `from`/`to` bound), all of which read the source's
  version column: ordered by the grain key, two runs of it agree while the source is still being written,
  and it needs no version column exposed to queries. Its rows therefore need not be the sampled ones. An output the dialect cannot express SHALL leave
  `statement` and `query` absent and carry `statement_not_portable` whose `subject` names that output.
- **A row-grain `llm` pipeline** SHALL sample the grain key and each column a `transform.inputs` binding
  reads (`input`), keyed by the source column name the binding names. It SHALL NOT return the
  pipeline's filter or cursor columns. Leaving them out of the projection SHALL NOT take them out of the
  read: a filter column still draws the enrichment join it needs, still counts for sensitive-column
  enforcement, and still counts towards the enrichments the clamp is computed over, exactly as in the
  runner's scan. It SHALL NOT apply an input's JSONata, which is the runner's to evaluate.
- **A `group` pipeline** SHALL sample the group's member rows, carrying only the `input` columns, in the
  order the group assembly delivers them. The group key is reported once, as `group_key`. The filter,
  version and identity columns the assembly reads to select and order members are not returned.

Preview SHALL report `output_without_description`, one note per output, for every `llm` output that
composed no `description`: one declaring no prose whose target column also carries none. Such a declaration is
storable and armable, so a refusal would be wrong. But a property the model is given no description for
is the kind of degradation an author wants told at the checkpoint, rather than inferred from an answer.
Preview SHALL report `unconstrained_response` for an `llm` declaration that composes no response schema.

Preview SHALL NOT return a rendered prompt. The template renderer and the group-input assembler live
in the runner, and their escaping is what determines what the model actually receives; a second
implementation in ADAS would be the last place to accept drift. No rendered-request dry run exists on
either service: the Enrichment Runner exposes runtime introspection and operator actions (runtime, DLQ,
pause and resume, backfill, group listing and requeue) and no rendering or test-call surface. (An earlier
revision of this requirement pointed at a runner dry-run surface; none was ever built.) A dry run
that renders the request, or calls the model on one sampled row or group, belongs to the runner, which
owns the renderer, and is a separate change.

Preview SHALL read through the **already-bounded** governed read paths, with an explicit row cap of five
rows and the optional `from`/`to` version window, rather than through the aggregate preview's
compiled-statement path. The aggregate preview's
budget bounds memory and time but not rows read, and an `llm` enrich preview reads the heavy body columns
that are refused unbounded and whose group-assembly caps apply only after the read.

**Window and order:**

- A row-grain and a Class-1 sample SHALL be read **newest first**, by the source's version and then its
  identity, both descending, within the safety lag (and, for row-grain, under the freshness clamp).
- The runner's scan SHALL keep its ascending keyset order. The preview's descending read is a separate
  read over the same predicates, not a mode of the runner's.
- The request body MAY carry `from` and `to` to restrict the sample to a version window. Without them
  the sample is the newest rows of the whole source.
- The body SHALL NOT carry a `cursor`. A seek-past cursor has no meaning under a newest-first order, and
  preview returns none to continue from, so it is refused as an unknown member with HTTP 400.

**The freshness clamp SHALL be applied by kind and reported, not left unexplained:**

- A Class-1 sample is **not** clamped. A preview stores nothing, so the permanently wrong stored value
  the clamp prevents cannot occur, and clamping would empty the sample of an author whose upstream
  enrichment happens to be behind. Preview SHALL still compute the bound the materialize write would
  apply, for its notes only. Where the newest sampled row is newer than that bound, preview SHALL
  report `enrichment_behind`, because those rows read the enrichment's columns as unmatched.
- A row-grain `llm` sample is clamped as the runner's scan is. Where the clamp leaves matching rows
  above it, preview SHALL say so: with `held_by_enrichment` when the sample is empty, and with
  `enrichment_behind` when it is not. Either way it SHALL NOT report `no_matching_rows`. Whether such
  rows exist SHALL be asked by one probe over the same predicates, past the bound, projecting only the
  version and reading at most one row; it SHALL NOT re-run the sample with the clamp lifted, which
  would read its heavy columns again.
- A `group` sample's choice of group is clamped as the runner's group marking is (below); its member
  fetch is not clamped, because the runner's group fetch is not.
- On every arm, a read enrichment with no freshness bound at all SHALL be reported as
  `held_by_enrichment` without a probe, since an enabled run would write nothing until it has one.

For a **`group`** pipeline, preview SHALL take the group key **value** to sample from an optional
`group_key` in the request body. A body naming none SHALL sample the group of the **newest** input row
that matches the declaration's filter within the safety lag, under the freshness clamp the runner's
group marking applies, and whose group key is neither NULL nor empty (the group fetch refuses a blank
key, and a keyless row belongs to no group). The choice SHALL enforce sensitive-column entitlement over
the group key and the filter columns before it reads, and SHALL resolve an enrichment-sourced group key
as the scan does. It SHALL first look within the last 24 hours of versions, which prunes partitions on
a source that declares a partition column, and only if that finds nothing look once over the whole
source. Either way, preview SHALL report the value it used as `group_key`. If no such row exists,
preview SHALL return an empty sample with `no_group_found` rather than an arbitrary group, or with
`held_by_enrichment` when an enrichment the clamp is computed over has no freshness bound.

The member sample SHALL hold at most five members, and only those five SHALL be read with their bound
input columns. The runner's cap on one group is the smaller of the configured group-fetch default row
count and the declaration's `member_select.limit`. Preview SHALL count the group's members after any
`member_select` preference, up to one past that cap and without projecting bound input columns (the
filter still applies in the count's `WHERE`, whatever columns it names). It SHALL
report `group_cut_by_fetch_limit`, naming the cap and both counts, when the count exceeds the cap and the
cap is the configured default rather than a `member_select.limit` the declaration chose. Narrowing the
declaration asked for is not a cut and SHALL NOT be reported as one. The group fetch's byte cap cannot
be predicted from five members and is not reported. A `group` body SHALL NOT carry `from` or `to`, and a
row-grain or Class-1 body SHALL NOT carry a non-empty `group_key` (a whitespace-only one included, since
the `group` arm reads only an absent or empty key as absent); either is HTTP 422 `pipeline_validation_failed`.

The `no_group_found` report is not the incomplete-declaration refusal and SHALL NOT be confused with it.
The body's group key names **which group** to sample, while `trigger.group_by` names **which column** the
declaration groups by, and a `group` declaration carrying no `trigger.group_by` is incomplete (see
[enrichment-runtime](../enrichment-runtime/spec.md)). So a disabled pipeline missing `trigger.group_by`
is refused 422 `pipeline_validation_failed` before anything is sampled and never reaches the choice of
group.

Every read a preview issues (the sample, the clamp probe, the group choice, the member count and the member read,
which on a source declaring no ordering key is a second single-phase selection limited to the sample)
SHALL run under the pipeline read budget, and a read it refuses SHALL be HTTP 422
`pipeline_preview_budget_exceeded` (see [enrichment-runtime](../enrichment-runtime/spec.md), "Every
pipeline-internal read runs under a resource budget"). Its message SHALL name the read refused, and the
remedies it offers SHALL be ones the arm accepts: a narrower `from`/`to` window is not offered on a `group` preview, which refuses one.

#### Scenario: Preview of a Class-1 pipeline returns typed values and their statement

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `schedule` pipeline whose
  `transform.type` is `sql`
- **THEN** the response carries a bounded sample of computed values, `columns` naming the grain key and
  each output with its inferred `type` and its `target_type`, and the portable `statement` and `query`
- **AND** the target is unchanged

#### Scenario: A Class-1 statement computes the outputs through the query API

- **GIVEN** a Class-1 `sql` pipeline over `dial_usage_log` with grain key `event_id`, an output
  `deployment_lower` declared as `lower(deployment)`, and a `filter`
- **WHEN** it is previewed and its `statement` is sent to `POST /v1/queries/execute-sql`
- **THEN** the statement reads `dial_usage_log`, projects `event_id` and `lower(deployment)` as `deployment_lower`,
  carries the filter as its `WHERE`, and has no `JOIN` and no `SETTINGS` clause
- **AND** the query answers HTTP 200, and for each `event_id` it shares with `rows` it answers the same
  `deployment_lower`

#### Scenario: Preview of an llm pipeline returns the input values only

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled row-grain `llm` pipeline
- **THEN** the sample's columns are the grain key, with role `grain_key`, and the source columns its
  `transform.inputs` bind, each with role `input`, and no filter or cursor column
- **AND** the response carries no `response_schema`, no `inputs`, no `outputs` and no `statement`
- **AND** no model is called

#### Scenario: The preview response carries no declaration echo

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for any `enrich` pipeline
- **THEN** the response carries none of `type`, `evaluator_name`, `evaluator_version`,
  `response_schema`, `response_schema_source`, `inputs` or `outputs`

#### Scenario: A folded legacy declaration's stored schema is visible on the read

- **GIVEN** a pipeline whose transform was folded from an old-shape evaluator version carrying a stored
  `response_schema`
- **WHEN** it is read with `GET /v1/pipelines/{name}`
- **THEN** the authored `transform` carries that stored schema, which is how a client tells a stored
  schema from a composed one

#### Scenario: Preview notes an output that composed no description

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an `llm` pipeline with an output declaring
  no prose whose target column also carries no `description`
- **THEN** the preview succeeds and carries an `output_without_description` note whose `subject` names
  that output

#### Scenario: A row-grain sample shows the newest rows

- **WHEN** a row-grain or Class-1 pipeline is previewed with no `from` or `to`
- **THEN** the sample holds the source's newest matching rows within the safety lag (and, for
  row-grain, under the freshness clamp), newest first

#### Scenario: A filter on an enrichment column still reads through its join

- **GIVEN** a row-grain `llm` pipeline whose `filter` references an enrichment column that no input binds
- **WHEN** it is previewed
- **THEN** the sample is read with that enrichment joined, the filter column is not among the `columns`,
  and the enrichment counts towards the clamp

#### Scenario: A cursor is no longer accepted

- **WHEN** `POST /v1/pipelines/{name}/preview` is called with a body carrying `cursor`
- **THEN** the request is rejected with HTTP 400

#### Scenario: A group preview names the group it sampled

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a `group` pipeline with a request body
  carrying a group key
- **THEN** the response samples that group's member rows and reports the key it used as `group_key`

#### Scenario: A group preview with no key samples the newest group

- **GIVEN** a `group` pipeline whose declaration is **complete**, so its `trigger.group_by` resolves
- **WHEN** it is previewed with a request body naming no group key value
- **THEN** the response samples the group of the newest matching input row and reports that group's key
  as `group_key`, in either pipeline state
- **AND WHEN** no input row matches
- **THEN** the sample is empty and `notes` carries `no_group_found`

#### Scenario: The newest group is chosen among rows that have a key

- **GIVEN** a `group` pipeline whose newest matching input rows carry an empty group key, and whose
  older matching rows carry non-empty keys
- **WHEN** it is previewed with no group key value
- **THEN** the sample is the group of the newest matching row with a non-empty key

#### Scenario: A group choice held by an enrichment says so

- **GIVEN** a `group` pipeline whose clamp is computed over an enrichment with no freshness bound
- **WHEN** it is previewed with no group key value
- **THEN** the sample is empty and `notes` carries `held_by_enrichment` naming that enrichment, and no
  `no_group_found`

#### Scenario: A group larger than the runner would fetch is reported

- **GIVEN** a `group` pipeline with no `member_select` limit, and a group with more members than the
  runner's group fetch row cap
- **WHEN** that group is previewed
- **THEN** the sample holds five members and `notes` carries `group_cut_by_fetch_limit` naming the cap
- **AND WHEN** the declaration's `member_select.limit` is below the configured default
- **THEN** no `group_cut_by_fetch_limit` note is carried
- **AND WHEN** the declaration's `member_select.limit` is above the configured default and the group
  exceeds the default
- **THEN** `group_cut_by_fetch_limit` is carried, naming the configured default as the cap

#### Scenario: An empty sample emptied by the clamp blames the clamp

- **GIVEN** a row-grain `llm` pipeline whose filter matches rows, reading an enrichment that has no
  freshness bound, or whose bound lies below every matching row
- **WHEN** it is previewed
- **THEN** the sample is empty and `notes` carries `held_by_enrichment` naming that enrichment, and no
  `no_matching_rows`

#### Scenario: A row-grain sample cut short by the clamp says so

- **GIVEN** a row-grain `llm` pipeline reading an enrichment whose freshness bound lies between its
  oldest and newest matching rows
- **WHEN** it is previewed
- **THEN** the sample holds the newest rows at or below the bound and `notes` carries
  `enrichment_behind` naming that enrichment and giving the gap

#### Scenario: A Class-1 sample past an enrichment's position says so

- **GIVEN** a Class-1 pipeline reading an enrichment whose freshness bound is older than the newest
  sampled rows
- **WHEN** it is previewed
- **THEN** the sample is returned unclamped and `notes` carries `enrichment_behind` naming the
  enrichment

#### Scenario: Preview of a disabled incomplete declaration reports what is missing

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled pipeline whose declaration is
  not yet complete for its kind
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming a member the
  declaration does not carry, and nothing is read or sampled

#### Scenario: Preview of a disabled draft reports the same first problem an enable-only patch would

- **GIVEN** a disabled `enrich` pipeline registered with `name`, `kind` and `target` alone
- **WHEN** it is previewed, and separately an enable-only patch arms it
- **THEN** both are rejected with HTTP 422 `pipeline_validation_failed` reporting that `trigger.kind`
  is required, rather than preview naming the absent `transform` while arming names the trigger

#### Scenario: Preview of a disabled draft samples through the mapping the gate just derived

- **GIVEN** a disabled `enrich` pipeline whose stored `output_bindings` are stale because its target's
  schema was defined after the draft was saved, and whose declaration now passes the gate
- **WHEN** `POST /v1/pipelines/{name}/preview` is called
- **THEN** the preview succeeds, sampling through the output mapping derived in memory from the gated
  declaration rather than the stored one, and nothing is written

#### Scenario: Preview of a disabled pipeline runs the full gate, not only the template check

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for a disabled `enrich` pipeline whose
  declaration is complete but whose `filter` does not parse
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the filter, so
  the author learns at the checkpoint what arming would have told them

#### Scenario: Preview of an enabled pipeline is not refused for drift the operator did not cause

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an **enabled** pipeline whose stored
  declaration no longer passes the gate — a read-source column renamed since it was written, or a
  folded legacy declaration the gate would now reject
- **THEN** the preview succeeds, running only the checks an enabled preview runs and not the gate,
  because an armed pipeline's declaration was gated when it was written and a preview is not a
  re-validation

