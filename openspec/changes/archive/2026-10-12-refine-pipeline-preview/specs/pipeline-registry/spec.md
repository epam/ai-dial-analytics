## ADDED Requirements

### Requirement: The dry-run preview answers in one shape for both kinds

`POST /v1/pipelines/{name}/preview` SHALL answer with one response shape for every kind it serves. That
shape SHALL carry only what a preview alone can know: the sample, the typed columns of that sample,
the statement that produced it where there is one, the number of rows an `aggregate` target will hold,
and coded notes about it.

A preview SHALL NOT echo any member of the declaration that `GET /v1/pipelines/{name}` serves, whether
authored or derived:

- group keys and measures;
- the transform and its type;
- the response schema and its source;
- the output mapping and the input bindings.

The reasoning:

- An author reaches preview from the pipeline they just wrote, so a client already holds the declaration
  and renders the preview beside it.
- The gate derives the derived members on every declaration write, so they are already on the read:
  `GET /v1/pipelines/{name}` serves an `aggregate` pipeline's expanded `group_by` and resolved measure
  columns, and `GET /v1/pipelines/{name}?view=compiled` serves an `enrich` pipeline's resolved
  `response_schema` and output mapping.
- Every echoed member is one more place where two answers to the same question can disagree.
- It is payload the reader has to wade through to reach the sample.

The response SHALL carry these members:

- **`rows`**: the sample rows. Always present, and `[]` when no row was sampled. Each row is keyed by
  column name.
- **`columns`**: one entry per key of a row, in row order. Always present, including when `rows` is
  empty, so a client can render the sample's header either way. A helper expression the sampling
  statement projects only for the preview's own checks (an ordering value, a window count) SHALL NOT
  appear in `columns` or `rows`. Each entry carries:
  - `name`.
  - `role`: `key` or `measure` on an `aggregate`; `grain_key` and `output` on a Class-1 `sql` pipeline;
    `grain_key` and `input` on a row-grain `llm` one; `input` alone on a `group` one, whose group is
    named once by `group_key` rather than repeated on every member.
  - `type`: the `QueryFieldType` code of the value as sampled.
  - `target_type`: the type of the target column the value is written to. Present for `key`,
    `measure` and `output`; absent for `grain_key` and `input`.
  - `max_bytes`: the largest UTF-8 size, in bytes, of this column's non-null values across the sampled
    rows, measured **before** any value is capped. A `string` value counts as its text and an `object`,
    `array` or `map` value as its compact JSON serialization, before the response's own JSON string escaping. Present
    for `string`, `object`, `array` and `map` columns that sampled at least one non-null value, and
    absent otherwise (including for `uuid` and `enum`, whose width is fixed or small). On an `input`
    column it is the author's hint at what one row or member costs the model.
- **`total_rows`** (`aggregate` only): the number of groups the declaration produces over the whole
  input, which is the number of rows the target holds once the pipeline has caught up. It SHALL be
  counted by the statement that produced `rows`, before its `LIMIT`, and not by a second read. It is
  `0`, present rather than absent, when the input yields no group. The preview SHALL carry no row-count
  or "more rows" member on the `enrich` kind, where a count would only measure the source table.
  Preview has no pagination, so a "more rows exist" flag would give a client nothing to act on.
- **`statement`**: the one `SELECT` that produced `rows`, rendered with its values inlined and ending in
  a `SETTINGS` clause naming every server setting ADAS set on it. That includes the read-deduplication
  settings and the budget's ceilings, so the statement run by hand reads the same rows under the same
  limits. It does not name the client-side `query_id`, nor settings a server profile applies. Run by
  hand it also returns the helper columns that `rows` and `columns` omit.
  - Present when one statement produced the sample: an `aggregate` pipeline and a Class-1 `sql` one.
  - Absent for an `llm` pipeline, whose sample is read through the governed scan or group assembly and
    has no single statement.
- **`group_key`**: the group the sample describes, as a string, for a `group` pipeline only, whether
  the caller named it or the preview chose it.
- **`notes`**: always present, and `[]` on an unremarkable preview. Each note carries a stable `code`, a
  `severity` (`info` or `warning`), a `subject` naming the column, output or enrichment it is about
  (present exactly for the codes whose meaning below names one), and a prose `message`. A client SHALL
  be able to switch on `code`; `message` is for people. A code that names a subject is emitted once per
  subject.

**Every value in `rows` SHALL be capped at 2048 bytes.** A body column runs to tens of kilobytes per
row on a usage log, so five member rows of two body columns make
a response of megabytes that a client cannot usefully render. The cap SHALL apply on every kind, because
an aggregate's `group_uniq_array` measure or a Class-1 extraction can be just as wide.

- A `string` value over the cap SHALL be cut at a Unicode code point boundary (never inside a UTF-8
  sequence or a surrogate pair; a grapheme cluster may be split) and end in `…`, so that the cell,
  `…` included, is at most 2048 bytes.
- An `object`, `array` or `map` value whose JSON serialization exceeds the cap SHALL be replaced by that
  serialization, cut the same way. The cell then holds a string, and the column's `type` still names
  the value's real type.
- Every value at or under the cap SHALL be returned unchanged.

The cap shortens what is **returned**, never what is **read**. Every check, note and count (including
`max_bytes`, `value_out_of_target_range` and `total_rows`) SHALL be computed from the full values. The
cap is a constant, like the `enrich` sample's row cap, and not a configured knob: raising it would
rebuild the response this cap exists to avoid.

A note SHALL never stand in for a refusal. A declaration that cannot be armed is refused with an error,
and a note describes a declaration that passed every check.

The note codes SHALL be the closed set below. A new code SHALL be added only by amending this list.

| `code` | `severity` | Kinds | Meaning |
|---|---|---|---|
| `no_matching_rows` | warning | all | The sample is empty because no input row matches the declaration's `filter` (and `from`/`to`, where given), or, for a named `group_key`, because that group has no member that does |
| `held_by_enrichment` | warning | all | An enrichment the declaration reads withholds every matching input row (it has no freshness bound yet, or, on a clamped `llm` read, every matching row lies above its bound), so an enabled run would write nothing yet; `subject` names it |
| `enrichment_behind` | info | `aggregate`, Class-1, row-grain `llm` | Matching input rows lie above an enrichment's freshness bound. On `aggregate` and Class-1, which sample unclamped, those rows read the enrichment's columns as unmatched (NULL, or the type default for a non-nullable column); on row-grain `llm`, whose read is clamped, they are left out of the sample. The message gives the gap; `subject` names the enrichment |
| `target_type_narrowing` | warning | `aggregate`, Class-1 | A column's sampled `type` is wider than its `target_type`, so a value outside the target's range would be coerced silently on insert; `subject` names the column |
| `value_out_of_target_range` | warning | `aggregate`, Class-1 | A sampled value cannot be held by the target column's physical type, so the insert would wrap or clamp it silently (`integer`, `long`, `date`, `timestamp`) or the refresh would fail with a decimal overflow (`decimal`); `subject` names the column |
| `no_group_found` | warning | `group` | No `group_key` was named and no matching group with a non-empty key exists to choose |
| `group_cut_by_fetch_limit` | warning | `group` | The sampled group has more members than the runner's group fetch would deliver under its row cap, beyond any `member_select` narrowing the declaration asks for; the message names the cap and both counts |
| `output_without_description` | info | `llm` | An output composed no `description`; `subject` names the output |
| `unconstrained_response` | warning | `llm` | The declaration composes no response schema, so the model's answer is held to none |
| `values_truncated` | info | all | One or more of a column's sampled values exceeded the value cap and were shortened in `rows`; the message gives how many and the cap; `subject` names the column |

**Samples SHALL be newest first.** Ordering by key or by oldest input put test traffic and months-old
rows in front of the author, often most of the sampled groups, some of them identical rows. The newest input is what an author who has just changed a declaration needs to judge,
and it is what the pipeline will process once enabled. Each kind states what "newest" orders by. On a
`group` pipeline "newest" chooses the group; its members keep the order the group assembly delivers
them in, because that is the order the model receives.

#### Scenario: Both kinds answer in the same shape

- **WHEN** `POST /v1/pipelines/{name}/preview` succeeds for an `aggregate` pipeline, and for an
  `enrich` one
- **THEN** both responses carry `columns`, `rows` and `notes` at the top level, and only the
  `aggregate` one carries `total_rows`
- **AND** neither carries `group_by`, `measures`, `transform`, `response_schema`,
  `response_schema_source`, `inputs`, `outputs`, or an `enrich` member

#### Scenario: Columns describe an empty sample

- **WHEN** a preview samples no rows
- **THEN** `rows` is `[]` and `columns` still lists each column the sample would have carried, with
  its role and types
- **AND** `notes` carries a code saying why the sample is empty

#### Scenario: A group sample its members could not be read back for is not blamed on the filter

- **GIVEN** a `group` sample whose member selection chose members, none of whose bound input columns the
  member read then found by their lookup key (the source changed between the two reads)
- **WHEN** it is previewed
- **THEN** `rows` is `[]` and `notes` carries no `no_matching_rows`, since no code in the closed set
  describes the condition and that one would send the author to a filter that is not the cause

#### Scenario: An aggregate preview reports how many rows the target will hold

- **GIVEN** an `aggregate` pipeline whose declaration produces more groups over its input than the
  preview's row cap
- **WHEN** it is previewed
- **THEN** `rows` holds exactly the cap and `total_rows` is the number of groups over the whole input
- **AND** no statement other than the sample's own was issued to count them

#### Scenario: An empty aggregate sample reports zero rows

- **WHEN** an `aggregate` preview's input yields no group
- **THEN** `total_rows` is present and `0`

#### Scenario: The sample's statement reproduces it

- **WHEN** a preview of an `aggregate` or Class-1 pipeline returns a sample
- **THEN** `statement` is the `SELECT` that produced `rows`, ending in a `SETTINGS` clause that names
  the deduplication settings and the budget's ceilings it ran under
- **AND** `columns` and `rows` carry none of the helper columns that statement projects

#### Scenario: A large value is capped and reported

- **GIVEN** a pipeline whose sampled rows carry a body column with a 90 KB value
- **WHEN** it is previewed
- **THEN** that cell holds at most 2048 bytes: the value's leading bytes, cut at a code point boundary,
  followed by `…`
- **AND** the column's `max_bytes` is the full value's size
- **AND** `notes` carries `values_truncated` whose `subject` names the column
- **AND** a column whose values all fit carries no such note and holds its values unchanged

#### Scenario: Notes are coded

- **WHEN** a preview carries a note
- **THEN** the note carries a `code` from the closed set, a `severity` of `info` or `warning`, and a
  `message`
- **AND** a `subject` wherever the code names a column or an enrichment

## MODIFIED Requirements

### Requirement: Pipeline errors use the shared error contract

Every `/v1/pipelines` failure SHALL use the `ErrorView` contract with a stable machine-readable
`error` code, and one set of codes SHALL serve every kind. A code is what a client switches on, so
the registry SHALL NOT report the same condition under two codes according to which kind produced it.

The mapping SHALL be: an unknown pipeline is 404 `unknown_pipeline`; a declaration, governance or
identity violation is 422 `pipeline_validation_failed`; a state conflict is 409 `pipeline_conflict` —
covering a target that already has a pipeline, running a disabled pipeline, and editing an enabled
pipeline's declaration where its kind forbids it; and an authorization refusal on a pipeline-scoped
data-plane call is 403 `pipeline_access_denied`. A state conflict SHALL NOT be reported under a
validation code: the declaration sent may be perfectly valid and only the timing wrong.

A sensitive-column refusal SHALL keep its own 403 code and SHALL NOT be flattened into a validation
failure. A preview refused for exceeding its resource budget SHALL carry its **own** code,
`pipeline_preview_budget_exceeded`, distinct from both the validation failure and an outage, and SHALL
carry it for **either kind** — whichever configured ceilings bounded the read, and for every statement a
preview issues, including the reads it makes only to explain an empty sample or choose a group. The `aggregate` sample
runs under `aggregate.preview.*` and an `enrich` sample under `pipeline.budget.*`, but the condition a
client switches on is the same, "this preview was too expensive", and its remedy is the author's, not
the runner's.

ClickHouse and driver internals SHALL NOT be echoed; a ClickHouse outage during any pipeline read or
write SHALL surface as 503.

A rejection the registry's own rules define SHALL be reported by those rules rather than by request
deserialization. A value that is part of a declaration's vocabulary but not implemented — an
unsupported `kind`, an unimplemented freshness or trigger mode, a bucket unit outside the supported
set — SHALL be accepted by the binding and refused by validation, so the response is the 422 above
with a message naming the alternatives, never the generic 400 an unbindable value produces.

Validation shared with other capabilities — notably the `filter` grammar, which the enrichment
declaration also uses — SHALL be re-attributed to the pipeline code rather than reported under the
sharing capability's, since a borrowed code points at an object that does not exist. The label the
shared analysis reports SHALL name the kind whose declaration was being validated, so a message
identifies which declaration was refused.

#### Scenario: Unknown pipeline

- **WHEN** any `/v1/pipelines/{name}` endpoint is called for a name not in the registry
- **THEN** the response is 404 `unknown_pipeline`, whatever the kind of pipeline the caller expected

#### Scenario: A conflict is not reported as a validation failure

- **WHEN** a registration names a target that already has a pipeline, of either kind
- **THEN** the response is 409 `pipeline_conflict`

#### Scenario: A declared-but-unimplemented value reports under the pipeline code

- **WHEN** `POST /v1/pipelines` names an unimplemented `kind`, or an unimplemented freshness mode
- **THEN** the response is 422 `pipeline_validation_failed` with a message naming the implemented
  alternatives, not a generic deserialization 400

#### Scenario: An invalid filter reports under the pipeline error code

- **WHEN** a pipeline of either kind is registered with a `filter` that does not parse under the
  bounded grammar
- **THEN** the response is 422 `pipeline_validation_failed`, and the message names the kind whose
  declaration was refused

#### Scenario: A data-plane authorization refusal has its own code

- **WHEN** a caller without the target's write grant invokes a pipeline-scoped data-plane endpoint
- **THEN** the response is 403 `pipeline_access_denied`

#### Scenario: ClickHouse unavailable

- **WHEN** a pipeline read or write is attempted while ClickHouse is unreachable
- **THEN** the response is 503 with the shared contract and no raw driver text

#### Scenario: A budget refusal is distinguishable from an outage

- **WHEN** a preview is refused for exceeding its resource budget
- **THEN** the response is 422 with the budget-exceeded code and a message naming the exceeded
  budget, never the 503 `clickhouse_unavailable` an unreachable server produces

#### Scenario: An enrich preview over its budget reports the preview budget code

- **WHEN** an `enrich` preview's sample read exceeds a `pipeline.budget.*` ceiling
- **THEN** the response is HTTP 422 `pipeline_preview_budget_exceeded`, not
  `pipeline_read_budget_exceeded`, with a message naming the ceiling exceeded and its configured value
- **AND** the same read issued by the runner through the scan or group fetch endpoint still refuses
  with `pipeline_read_budget_exceeded`

### Requirement: The declaration gate runs when a write arms the pipeline, not when it is stored

The system SHALL apply the kind's declaration gate at the moment a pipeline is **armed or executed**,
and SHALL NOT require a stored declaration to be complete or valid while the pipeline is disabled. A
write that leaves the pipeline `enabled: false` SHALL store what it was given.

This is the registry's single statement of validation timing; where another capability's requirement
says a rule is enforced "at registration", it SHALL be read as enforced at this gate.

**The gate has two halves, and only one of them is deferred.** The gate does not merely inspect a
declaration — it **derives** parts of it: an `aggregate` pipeline's `group_by` is expanded from the
target's `ordering_key` when omitted, and an `enrich` pipeline's `output_bindings` are derived from its
transform's outputs against the target's exposed columns. Neither member is authored, and the gate is
their only writer.

- **Derivation SHALL run on every declaration write**, whether or not the write arms the pipeline, and
  SHALL be best-effort: where a member cannot be derived — no transform declared yet, an output naming
  a column that does not exist — the previously stored value SHALL be left untouched. A declaration
  write SHALL NOT erase a derived member it could not re-derive. Were it to, the catalog's column
  drop/rename dependency guard, which resolves a pipeline's writes from its stored `output_bindings`,
  would stop seeing the pipeline: the column would become droppable, the ClickHouse `ALTER` would
  execute, and the catalog transaction that re-derives afterwards would roll back — leaving the column
  gone from one store and present in the other, with no operation that repairs the pair.
- **Refusal SHALL run when the write arms the pipeline**, and on the dry-run preview of a pipeline
  that is **disabled** and therefore never gated. Arming asks, besides the kind's gate, the trigger's own
  completeness — a trigger kind, and a cron on a `schedule` trigger — and the preview of a disabled
  pipeline SHALL ask the same, so that a preview that passes means arming will pass. Whether the preview of an **enabled**
  pipeline re-gates it is the kind's to state, and the two kinds state it differently: an `enrich`
  preview of an enabled pipeline SHALL NOT re-gate it, because its declaration was gated when it was
  armed and re-gating would report it as unpreviewable for drift the operator did not cause and cannot
  see from there; an `aggregate` preview SHALL run its full gate in either state, because on that kind
  the drift is the answer an operator reading a live rollup needs (see
  [aggregate-pipeline](../aggregate-pipeline/spec.md), "Dry-run preview of an aggregate declaration").
- **The pipeline-scoped data plane SHALL NOT run the whole gate.** It refuses a declaration whose
  required members are **absent**, and the materialize path additionally re-checks the two rules whose
  absence produces a silently wrong row rather than a loud failure — the result-type fit against the
  bound target column, and the row-grain rule against the read source's `identity_column`. Importing
  the rest would stop a running pipeline for drift the operator did not cause and cannot see from
  there. The two **writing** endpoints refuse a disabled pipeline outright, so what they execute has
  always passed the gate once, when it was armed. A refusal an endpoint already owns
  unconditionally stays unconditional in either state: turning a server fault into a refusal is not a
  new gate.

**Three classes of refusal SHALL remain unconditional**, on every write, armed or not, and they are
exceptions to the rule above rather than gaps in it:

- **An intra-declaration contradiction the storage layer refuses regardless** — a `member_select`
  declared against a non-`group` trigger, or a non-`schedule` trigger on an `aggregate` pipeline.
  These are refused by database constraints that hold for every row, so without a service-layer
  refusal the author would meet them as an integrity violation reported as a name collision. They are
  also not incompleteness: they are two stated members that cannot both be true, which no amount of
  further authoring resolves.
- **A malformed request body**, which is a binding failure and answered HTTP 400 naming the field, as
  it is today. Deferring governance does not make an unparseable body storable. A `trigger.cron` that
  does not parse is malformed in this sense and SHALL be refused on every write with HTTP 422; only its
  *absence* on a `schedule` trigger is deferred to arming.
- **A named table that does not exist.** `target` and `inputs` each carry a foreign key to the table
  catalog, which the database enforces on every row, so a declaration naming a table that does not exist
  SHALL be refused with HTTP 422 naming it rather than reaching storage and being reported as a name
  collision. Whether the table is the right kind of table, `ACTIVE` and scannable stays the gate's
  question.

A storage-layer refusal that is reached anyway SHALL be reported by the constraint it broke: a
collision on the pipeline's name or target as HTTP 409, and a CHECK or foreign-key violation as HTTP 422
`pipeline_validation_failed` naming the rule — never as "name or target already in use".

A derived member SHALL be stored as an **empty collection rather than null** when it has no entries, so
that a partial declaration has one representation in storage and in memory.

**The deferral is safe because a disabled pipeline runs nothing.** ADAS's scheduler selects on
`(enabled, next_run_at)`, and the Enrichment Runner's sync feed asks for `enabled=true`, so no
incomplete declaration is reached by either. What the gate protects is the moment the pipeline begins to
produce rows, and that moment has its own enforcement, unchanged.

**The gate's content SHALL NOT change.** Every check applied at registration today SHALL be applied at
arming, over the stored declaration, with the same status and the same error code.

#### Scenario: An incomplete declaration is stored while disabled

- **WHEN** `POST /v1/pipelines` registers a pipeline with `name`, `kind` and `target` and no other
  declaration member
- **THEN** the response is 201, the pipeline is stored `enabled: false`, and no kind gate has run

#### Scenario: A complete but invalid declaration is stored when the write does not arm

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with a complete declaration that
  violates a governance rule of its kind
- **THEN** the response is 201 and the declaration is stored, because an `aggregate` registration lands
  disabled and the write therefore arms nothing; the refusal is raised by `POST …/preview` or by the
  patch that arms it

#### Scenario: Arming runs the gate the registration did not

- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true` on a pipeline whose stored declaration
  does not satisfy its kind's gate
- **THEN** the request is rejected, the pipeline stays disabled, and the stored declaration is unchanged

#### Scenario: A declaration write never erases a derived member

- **GIVEN** an `enrich` pipeline whose stored `output_bindings` were derived by an earlier gated write
- **WHEN** a declaration patch is applied while the pipeline is disabled and the new declaration's
  outputs cannot be re-derived
- **THEN** the previously stored `output_bindings` are retained rather than replaced with an empty
  mapping, and the catalog's drop/rename guard continues to see the pipeline as a writer of those
  columns

#### Scenario: A derivable member is re-derived on an ungated write

- **WHEN** a declaration patch of a disabled `aggregate` pipeline omits `group_by` and the target's
  `ordering_key` columns are all plain input columns of the same name
- **THEN** the group keys are derived and stored, exactly as they would be on a gated write

#### Scenario: A disabled pipeline with an incomplete declaration produces no rows

- **WHEN** a pipeline's stored declaration is incomplete and its trigger's moment arrives
- **THEN** ADAS's scheduler does not select it, the runner's `enabled=true` sync feed does not carry
  it, and nothing is written to its target

#### Scenario: Preview of an enabled pipeline re-gates according to its kind

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an enabled pipeline whose stored
  declaration no longer passes its kind's gate
- **THEN** an `aggregate` pipeline is refused with the status and code arming would give, while an
  `enrich` pipeline runs only the checks its enabled preview runs, not the gate, and is sampled
