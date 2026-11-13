## MODIFIED Requirements

### Requirement: ENRICH pipeline declaration binding a transform to a target enrichment

The system SHALL manage pipelines via `POST /v1/pipelines`, `PATCH /v1/pipelines/{name}`, `DELETE
/v1/pipelines/{name}`, and `GET /v1/pipelines/{name}`. An ENRICH pipeline SHALL declare its own
**transform** against a `target`, plus an optional single-entry `inputs`, `trigger.kind`, optional
`trigger.cron`, `trigger.group_by`, `trigger.ready_when`, `filter`, `enabled`, and an optional nested
`advanced` block carrying the execution knobs (`rows_per_scan`, `rows_per_call`, `rate_rpm`,
`sample_fraction`). The pipeline's **grain SHALL be derived** from the target enrichment's
`grain` and SHALL NOT be re-declared on the pipeline. The pipeline's **read source MAY be declared** as
its `inputs` entry and SHALL default to the target enrichment's `source_table` when omitted (see "A
pipeline's read source is declarable"). Each accepted re-declaration SHALL bump a `generation` counter;
every accepted mutation, including a change of `enabled` alone, SHALL move `updated_at` (see
[pipeline-registry](../pipeline-registry/spec.md), "A pipeline has exactly one run control"). An unknown pipeline name SHALL be rejected with HTTP 404 `unknown_pipeline`.

**The transform SHALL be one nested block, `transform`**, stored in the pipeline's `spec`, carrying
`type` (`llm` or `sql`), `model`, `preset`, `route`, `params`, `request_template`, `inputs` and `outputs`. It SHALL be
nested rather than flat for a reason that is a contract, not a matter of taste: **`outputs` already
names the derived output mapping** at the top level of a pipeline read — one entry per mapped target
column, `{name, column, jsonata?}` — and the authored outputs are a different shape,
`{name, prose|sql, values?, jsonata?}`. Two shapes under one member name, across two projections of
one object, is exactly what "a fact SHALL have exactly one spelling" forbids, and no typed client can
model it. Nesting separates them by construction: the authored list is `transform.outputs`, the derived
one stays `outputs`. Nesting also keeps `type` from sitting beside `kind` as a second top-level
discriminator, and lets the whole block be refused as `transform.<member>` on the wrong kind through
the rule `advanced` already follows.

`type` SHALL be required within the block and SHALL NOT be inferred from the presence or absence of any
other member: it selects the *grammar* of an `outputs` value, both grammars are a string on the wire,
and a `sql` transform whose `type` went missing would have its expressions read as prose and composed
into a response schema. The shape SHALL be dispatched on `type`:

- **`llm`** — `model` SHALL be required and non-blank, and `request_template` SHALL be required and
  non-blank. The template is a **shape** requirement, answerable from the block alone, and is therefore
  distinct from the placeholder check that arm time carves out: an author may save a template whose
  placeholders bind to nothing, but not a transform carrying no template at all. Without it, a patch
  replacing `transform` wholesale and omitting `request_template` would be stored on an **enabled**
  pipeline and leave it armed with no prompt, because the placeholder rule's own blank guard returns
  early and cannot see it. `preset` SHALL be optional, defaulting to
  `chat_completion`; its accepted values SHALL be exactly `chat_completion` and `dial_app_route`.
  `model` names a DIAL deployment under either preset, a model or an application alike. The preset
  selects how that deployment is called:
  - `chat_completion`: the runner POSTs to the deployment's chat-completions endpoint and reads the output
    from the first choice's message content.
  - `dial_app_route`: the runner POSTs to `/v1/deployments/{model}/route/{route}` and reads the whole
    response body as the output.
  `route` SHALL be required and SHALL match the route grammar (see "A transform may call a DIAL
  application route") when `preset` is `dial_app_route`. At the gated requests of "Static governance gate
  on arming and execution" it SHALL be absent under `chat_completion`, because a chat-completions call has
  no route to name and a stored one would be a value nothing reads; a disabled draft is stored as written.
  Each `outputs` value SHALL be either a **string** (the
  prose the model is told about that output) or an object `{prose, values?, jsonata?, source?}` carrying **at
  most one** of `values` or `jsonata`. `source` is the `dial_app_route` member described in "A route output
  reads its value from the route's response with source". `prose` SHALL be **optional**, defaulting to the target column's
  `description` — see "The response schema is composed from the target and the transform", which
  already takes an output's type, enum domain and enum order from the same column. An absent value, a
  JSON `null`, an empty object and a blank string SHALL all mean *default it*, so that one fact has one
  spelling. There SHALL be no separate refusal of "an object declaring none of the three": once `prose`
  is optional, `{}` and `{"prose": null}` are the same bound value as `null`, so a refusal could only
  reject one spelling of *default it* while accepting the others.
- **`sql`** — `preset`, `model`, `route`, `request_template`, `params` and `response_schema` SHALL be absent, and
  each `outputs` value SHALL be a non-blank SQL expression string. `response_schema` is refused for the
  same reason as the other four, and is named because it is the one the compiled projection would
  otherwise surface: a `sql` transform constrains no model response, so a stored schema on one would be
  served as a derived top-level `response_schema` for a pipeline that never calls a model. See
  [class1-enrichment](../class1-enrichment/spec.md).

A body violating its type's shape SHALL be rejected with HTTP 422 `pipeline_validation_failed`. The
`outputs` **order is load-bearing** — it is the composed response schema's property order, hence the
model's generation order — so it SHALL be preserved from the request body and stored as an **ordered
array**, never as a JSON object whose key order the storage engine may normalize.

**The two legacy output members are part of the authored surface.** A transform folded from an
evaluator version registered before the `outputs` shape carries `output_vars` and, for `llm`, a stored
`response_schema` instead (see "A transform's outputs are one fact in two stored shapes"). Both SHALL
be served under `view=source` and SHALL be accepted on `PATCH`, so a read-modify-write client round-trips
a folded declaration unchanged. This is a correctness requirement rather than a convenience: a patch
that silently dropped them would leave a declaration with no outputs at all, and dropping a stored
`response_schema` rewrites a live prompt. Three rules bound it:

- **`output_vars` SHALL be refused on `POST`** with HTTP 400 `bad_request` naming the field. A new
  declaration authors `outputs`; the legacy pair exists to be carried and patched back, never to start
  from. It is not a member of the create-side request shape at all, which is what makes the refusal a
  binding one rather than a validation one.
- **`response_schema`, unlike `output_vars`, SHALL be accepted on `POST`.** The two legacy members part
  company here, and deliberately: a stored schema is a legitimate thing for a new declaration to pin —
  an author reproducing an existing prompt byte for byte has no other way to do it — while
  `output_vars` is a shape that only ever arrives by being folded. A `POST` carrying
  `transform.response_schema` SHALL therefore be stored and SHALL win over composition, exactly as a
  folded one does.
- A declaration carrying **both** `transform.outputs` and `transform.output_vars` SHALL be rejected with
  HTTP 422 `pipeline_validation_failed`. This is reachable on **`PATCH` only**: on `POST` the
  `output_vars` member is refused at the binding with 400 before any validation runs, so the 422 never
  applies there. Stating the verb is what keeps the two rules from reading as a contradiction.
- **`response_schema` is an ordinary authored member**, so a declaration patch that omits it clears it
  and that pipeline switches to composition from its `outputs`. It is the only way to migrate a legacy
  declaration onto `outputs`, it is the operator's explicit act because a declaration patch supplies the
  whole declaration, and its consequence is that the model is then held to a newly composed schema whose
  property order is its generation order.

**The declaration gate SHALL require at least one output resolved through the single accessor** —
`transform.outputs` when present, `transform.output_vars` otherwise — never through `outputs` alone. A
folded old-shape declaration has no `outputs` at all, so a gate that required that member would refuse
every pipeline the fold migration produced.

Because the declaration now names both a target and a transform, `outputs` SHALL be validated against
the target **at declaration time**, and an output resolving to no column SHALL be rejected with HTTP
422 naming the output and the target. There SHALL be no deferral of this check to a later write.

**`transform.inputs`** SHALL be a map of variable name → the value the transform receives, each value
a source column or a JSONata expression over the read source's entity. It is the single declaration of
what the transform reads: the variable names are the map's keys, so a name cannot be declared in one
place and bound in another. What a variable *is* depends on the trigger's grain — a top-level template
placeholder at row grain, a field of each member object under `{{members}}` at group grain — see "A
transform's template placeholders are validated against the pipeline's trigger grain".

It SHALL live **inside** the block, replacing the top-level `vars` map, and the flat spelling SHALL be
refused at the binding with HTTP 400 `bad_request` naming it. Two reasons, and the second is the
structural one. It names half of one fact: `transform.inputs` is what the transform reads and
`transform.outputs` is what it writes, where `vars` named a mechanism beside a role and nothing in the
pair said they were two sides of one thing. And it is meaningful only when `transform.type` is `llm` —
a `sql` pipeline is refused for declaring any, because there is no request to render — so the one member
dispatched on `type` was the one member sitting outside the type-dispatched block.

The name collides with the top-level `inputs`, the table list, and that collision SHALL be resolved by
**nesting** rather than by a third spelling: it is the same rule that puts a group key at
`trigger.group_by` while an `aggregate` pipeline's rollup grain is the top-level `group_by`, and that
puts the authored output map at `transform.outputs` while the derived mapping is the top-level
`outputs`. The two are additionally distinguishable by shape — the table list is an array, the variable
map an object — so a member sent at the wrong level is a binding failure rather than a silent accept.
`input_vars` SHALL NOT be revived as the spelling: it is a retired member, and reusing it for a map
where it was a list would make a body written for the retired list bind cleanly and mean something else.

**`advanced`** SHALL carry the execution knobs, nested rather than flat so they stay out of the
required-looking surface of the declaration. Its members SHALL be `rows_per_scan`,
`rows_per_call`, `rate_rpm` and `sample_fraction`, each optional, and the block itself SHALL be
optional. `sample_fraction` SHALL be a fraction in `(0, 1]`: a value above `1` SHALL be rejected with
HTTP 422 rather than read as a percentage, and `0` SHALL be rejected with HTTP 422 naming
`enabled: false` as the way to declare a pipeline that evaluates nothing.

The following members SHALL NO LONGER be accepted on this kind, each refused at the binding with
HTTP 400 `bad_request` naming the field: **`evaluator_name` and `evaluator_version`** (the transform is
declared inline; there is no registry to name), `input_bindings` and `output_bindings` (replaced by
`transform.inputs` and by the derived output mapping), the flat `vars` (now `transform.inputs`), the
flat `sampling`, `cadence`, `batch_scan_limit` and
`batch_chunk` (now `advanced` members under their new names), `advanced.scan_every` (a row-grain
pipeline's only clock is its `trigger.cron`, and a group pipeline's mark scan runs on the caller's own
configured cadence — see "Trigger kinds and the default schedule"), and `priority` (nothing reads it).
`cadence` and `advanced.scan_every` are two spellings of one retired knob and are refused alike. Both
evaluator members belong to **no kind at all**, so they are refused at the binding on
either kind with 400 — not cross-kind-rejected with 422 — under the rule that a spelling the read never
echoes is refused where an unrecognized member is (see
[pipeline-registry](../pipeline-registry/spec.md), "A declaration field belonging to another kind is
rejected"). `rate_rpm` SHALL keep its spelling: a rename buys a permanent second vocabulary and is only
worth it for a name that is ambiguous or misleading.

Both `advanced` and `transform` SHALL count as a **declaration** for the purpose of distinguishing a
declaration patch from an enable-only patch, so a patch carrying only `advanced` or only `transform`
SHALL take the declaration path rather than being read as an enable-only patch that
silently discards it. Taking that path is not the same as being accepted: **a declaration patch SHALL
supply the whole declaration**, which for this kind SHALL be `target`, `transform` and `trigger`
together (plus `inputs` where the pipeline declares a read source), and a patch short of that SHALL be
rejected with HTTP 422 naming the members it must restate. A partial merge would leave a stored
declaration no caller described. `transform` therefore **replaces** the stored one wholesale: a member
it omits — a `response_schema`, an `output_vars` — is cleared rather than retained, which is what makes
the response-schema rule above an operator's act rather than a merge artefact.

`advanced` SHALL NOT be required by a declaration patch: it is optional at registration, so requiring
it on patch would make a pipeline that never set it unpatchable. A cross-kind rejection of a nested
member SHALL name `<block>.<member>`, not the block, so the caller is told which field they misplaced.

A patch carrying only `advanced` that leaves the pipeline enabled is therefore refused with 422 rather
than re-validated and stored, and bumps no `generation`; on a pipeline that stays disabled it is an
ordinary partial declaration patch. The completeness rule and the enable-only distinction are two
different questions about one request, and only the second is what "counts as a declaration" answers.

`enabled` SHALL be **required** on a registration of this kind and SHALL carry no implicit default:
whether a pipeline starts live is an operator decision, and this kind is registered with the flag it
is given, so an omitted one would leave a pipeline that looks registered and never runs. An omitted
or null `enabled` SHALL be rejected with HTTP 422 `pipeline_validation_failed` naming the field.

The status is 422 rather than the 400 this answered while enrichment had a registry of its own, and
the reason is the merge: the requirement is now **per-kind** — an `aggregate` pipeline ignores the
flag entirely, because its dry-run preview is what the disabled state exists for — and a per-kind
requirement cannot be expressed by request-shape validation. See
[pipeline-registry](../pipeline-registry/spec.md), "Register and enable lifecycle".

A returned pipeline SHALL carry its resolved `inputs` entry — its read source, declared or derived —
and SHALL additionally carry `version_column`: the **physical name of the read source's version
column**, derived from that source's catalog scan metadata (`table_definition.version_column`) — the
same fact the scan path resolves, so the two cannot disagree. `version_column` SHALL NOT be
declarable on the pipeline. A scan page projects that column under its own name, and for a `group`
pipeline its value is the group's activity timestamp; surfacing the name is what lets a caller read
it per source rather than holding one configured name for every source it scans. When the read
source is unknown or declares no scan metadata, `version_column` SHALL be **absent** and the read
SHALL still succeed — the pipeline is valid, and an unscannable source is rejected on the scan path
(HTTP 422) rather than on a registry read.

Because `version_column` is derived rather than declared, it can change without any pipeline being
mutated: `PATCH /v1/tables/{source}/schema` MAY rename the column and repoint the stored name (see
[table-management](../table-management/spec.md)). A pipeline mutation is therefore **not** the only
event that bumps `generation`: when a source's `version_column` is repointed, every pipeline **whose
read source is that source** SHALL have its `generation` incremented and its `updated_at` advanced,
in the same transaction as the catalog repoint. The bump SHALL follow the read source, so a pipeline
that declares one is bumped by the table it actually scans and not by its target enrichment's
`source_table`. Both `generation` and `updated_at`, not just `generation` — the sync feed selects by
`updated_at`, so a bumped generation the feed does not return would be no signal at all. Without
this a runner detecting change by `generation` would keep the pre-rename name and read a key its
scan pages no longer carry.

The **fold migration is the one write that bumps neither**. It rewrites `spec` in place with the
transform each pipeline already resolved to, so it changes no **fact** a consumer reads: the transform
served after the migration is the one that was served before it, reached without a second lookup. The
release *does* reshape the compiled projection — that is what the runner contract version is raised for
— but the reshaping belongs to the deploy, not to this `UPDATE`, and bumping `generation` here would
re-sync every runner lane and discard every Class-1 materialized position for a change the migration
itself did not make.

`trigger.kind` SHALL be one of `schedule` or `group` (see "Trigger kinds and the default schedule" for the
retired `on_ingest` and for the cron an enrich `schedule` trigger is stored with when it declares none). A
`group` pipeline fires once per
**group key** rather than once per row: it declares `trigger.group_by` (the group key) and `trigger.ready_when`
(`{signal?, idle?, max_staleness?, cost_ceiling?}`), the declaration of when a group is considered
complete enough to evaluate. A `group` pipeline SHALL NOT carry `trigger.cron` — its cadence is the
caller's readiness sweep, not a cron expression — which the existing cron-iff-`schedule` invariant
already enforces. A `group` pipeline writes exactly one enrichment row per group key through the existing
pipeline-scoped write-back, keyed by `trigger.group_by`, and the target enrichment's `ReplacingMergeTree` collapses
by that key.

#### Scenario: Create a pipeline derives its grain and defaults its source

- **WHEN** `POST /v1/pipelines` is called with a valid `transform` and `target` and no `inputs`
- **THEN** the pipeline is created with `grain` derived from the target enrichment and `inputs`
  defaulted to that enrichment's `source_table`, `enabled` per the request, `generation`
  initialized, and HTTP 201 returned

#### Scenario: An llm transform is declared inline

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and a `transform` carrying
  `type: "llm"`, a non-blank `model`, a `request_template` and `outputs` whose values are prose strings
  resolving to target columns
- **THEN** the pipeline is created, HTTP 201 is returned, and the stored `outputs` preserve the
  declared order

#### Scenario: An llm output declaring no prose defaults to the column description

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and an `llm` transform whose `outputs`
  carry a value that is `null`, an empty object or a blank string for a target column that has a
  `description`
- **THEN** the pipeline is created and HTTP 201 is returned, the stored declaration records **no**
  prose for that output, and the composed `response_schema` carries the column's `description` as that
  property's `description`

#### Scenario: A sql output still requires its expression

- **WHEN** `POST /v1/pipelines` is called with a `sql` transform whose `outputs` carry a blank or absent
  value for a target column that has a `description`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, because an expression is
  not prose and cannot be defaulted from a column

#### Scenario: A sql transform is declared inline

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and a `transform` carrying
  `type: "sql"`, no `model`/`preset`/`request_template`/`params`, and `outputs` mapping each target
  column name to a non-blank SQL expression
- **THEN** the pipeline is created and HTTP 201 is returned

#### Scenario: A transform with no type is rejected rather than defaulted

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and a `transform` carrying no `type`,
  whether or not `preset` is present
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, and the body is not read
  as `llm` on the strength of `preset`'s default

#### Scenario: An llm transform without a model is rejected

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "llm"` and a blank or absent `model`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: An llm transform carrying no request template is rejected

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` declares `transform.type: "llm"` with a
  `model` but no `request_template`, or a blank one
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, whether or not the
  pipeline is enabled, so a wholesale `transform` replacement cannot leave an armed pipeline promptless

#### Scenario: A sql transform carrying an llm-only member is rejected

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "sql"` and any of `preset`, `model`,
  `route`, `params`, `request_template` or `response_schema` inside the block
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the llm-only field

#### Scenario: An output matching no target column is rejected at declaration

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` declares a `transform.outputs` key
  matching no column of the target enrichment
- **THEN** the request is rejected with HTTP 422 naming the output and the target, at the moment it is
  declared rather than at a later write

#### Scenario: A folded legacy declaration round-trips through a patch

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for a pipeline whose transform was folded
  from an old-shape evaluator version, and the response body is sent back unchanged as
  `PATCH /v1/pipelines/{name}`
- **THEN** the patch is accepted, `transform.output_vars` and `transform.response_schema` are stored
  exactly as they were, and the compiled projection is unchanged

#### Scenario: A patch that omits a stored response schema clears it

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration whose `transform` carries `outputs` and
  no `response_schema`, for a pipeline that stored one
- **THEN** the stored schema is cleared, the compiled projection thereafter serves the schema composed
  from the target and those `outputs`, and `generation` is bumped

#### Scenario: A declaration carrying both output shapes is rejected

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries both `transform.outputs` and
  `transform.output_vars`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both members

#### Scenario: Authoring output_vars on a new pipeline is refused

- **WHEN** `POST /v1/pipelines` carries `transform.output_vars`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field — a new declaration
  authors `outputs`

#### Scenario: A folded legacy declaration passes the gate

- **WHEN** an `enrich` pipeline whose transform carries `output_vars` and no `outputs` is read, patched
  or enabled
- **THEN** the gate resolves its outputs through the single accessor, finds at least one, and accepts —
  it does not refuse the pipeline for declaring no `outputs`

#### Scenario: Naming an evaluator is refused by name

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries `evaluator_name` or
  `evaluator_version`, on either kind
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted

#### Scenario: Omitted enabled is rejected

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and `enabled` absent or null
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field, and
  no pipeline is created

#### Scenario: A removed declaration member is refused by name

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` carries any of `input_bindings`,
  `output_bindings`, `sampling`, `cadence`, `advanced.scan_every`, `batch_scan_limit`, `batch_chunk`
  or `priority`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field, and nothing is
  persisted — the caller is told what they sent rather than having it silently ignored

#### Scenario: A patch carrying only advanced or only the transform is a declaration patch

- **WHEN** `PATCH /v1/pipelines/{name}` carries only an `advanced` block, or only
  `transform`
- **THEN** the patch takes the declaration path rather than being read as an enable-only patch that
  discards it, and is rejected with HTTP 422 naming `target`, `transform` and `trigger` as the members
  a declaration patch must supply together

#### Scenario: A declaration patch omitting the transform is rejected

- **WHEN** `PATCH /v1/pipelines/{name}` supplies `target` and `trigger` but no `transform`
- **THEN** the request is rejected with HTTP 422 naming the members a declaration patch must supply
  together, rather than merging the stored transform into a declaration the caller did not write

#### Scenario: A patch changing only the prompt bumps generation

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a declaration differing from the stored one only in
  `transform.request_template` or in an output's prose
- **THEN** the declaration is re-validated and stored, `generation` is incremented and `updated_at`
  advances, so the change reaches the sync feed

#### Scenario: A declaration patch need not restate advanced

- **WHEN** `PATCH /v1/pipelines/{name}` supplies a complete declaration with no `advanced` block for a
  pipeline that never declared one
- **THEN** the patch is accepted

#### Scenario: A sample fraction above one is rejected

- **WHEN** `POST /v1/pipelines` declares `advanced.sample_fraction: 50`
- **THEN** the request is rejected with HTTP 422, rather than being read as 50 per cent

#### Scenario: A zero sample fraction is rejected naming the enabled flag

- **WHEN** `POST /v1/pipelines` declares `advanced.sample_fraction: 0`
- **THEN** the request is rejected with HTTP 422 naming `enabled: false` as the way to declare a
  pipeline that evaluates nothing, rather than arming a pipeline that keeps no rows

#### Scenario: A returned pipeline carries the read source's version column

- **WHEN** a pipeline is created, updated, or read and its read source declares scan metadata
- **THEN** the response carries `version_column` equal to that source's declared version column

#### Scenario: A source without scan metadata omits version_column

- **WHEN** a pipeline is read and its read source declares no version column (or the source is
  unknown)
- **THEN** `version_column` is absent from the response and the read succeeds

#### Scenario: Update bumps generation

- **WHEN** `PATCH /v1/pipelines/{name}` changes a pipeline
- **THEN** the stored pipeline is updated, `generation` is incremented, and `updated_at` advances

#### Scenario: The fold migration bumps nothing

- **WHEN** the fold migration rewrites an `enrich` pipeline's `spec` with the transform it already
  resolved to
- **THEN** that pipeline's `generation` and `updated_at` are unchanged, no revision is recorded, the
  transform its compiled projection carries is the one it already resolved to, and no runner lane
  re-syncs on account of the migration

#### Scenario: Repointing the read source's version column bumps generation

- **WHEN** `PATCH /v1/tables/{source}/schema` renames the column named by that source's
  `version_column`, and a pipeline's read source is that source
- **THEN** that pipeline's `generation` is incremented and its `updated_at` advances in the same
  transaction as the catalog repoint, and a subsequent `GET /v1/pipelines/{name}` carries the renamed
  `version_column`
- **AND** a pipeline whose read source is a different table is untouched, including a pipeline whose
  target enrichment hangs off the repointed source but which declares another read source

#### Scenario: A repointed version column reaches an incremental sync

- **WHEN** a runner has synced with `updated_since` and the source then repoints its
  `version_column`
- **THEN** the next sync at that watermark returns the affected pipelines, each carrying the new
  `version_column` and a higher `generation`

#### Scenario: Renaming a non-version column of the source bumps nothing

- **WHEN** `PATCH /v1/tables/{source}/schema` renames a source column that is neither the source's
  `version_column` nor its `identity_column`
- **THEN** no pipeline's `generation` or `updated_at` changes

#### Scenario: Delete removes the pipeline

- **WHEN** `DELETE /v1/pipelines/{name}` is called for an existing pipeline
- **THEN** the pipeline is removed and HTTP 204 returned, taking its transform with it, and the
  target enrichment keeps its rows

#### Scenario: An unknown pipeline is rejected

- **WHEN** any pipeline read/patch/delete targets a name that does not exist
- **THEN** the request is rejected with HTTP 404 and error code `unknown_pipeline`

#### Scenario: Create a group pipeline

- **WHEN** `POST /v1/pipelines` is called with `trigger.kind: "group"`, a `trigger.group_by` equal
  to the target enrichment's grain key, and a `trigger.ready_when` carrying at least one readiness
  condition
- **THEN** the pipeline is created and HTTP 201 returned

#### Scenario: An app-route transform is declared inline

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "llm"`, `preset: "dial_app_route"`,
  `model: "insights-app"`, `route: "insights"` and a non-blank `request_template`
- **THEN** the pipeline is created, and `GET` returns the block with `preset` `"dial_app_route"` and
  `route` `"insights"` as authored

#### Scenario: An app-route transform without a route is rejected

- **GIVEN** a declaration carrying `preset: "dial_app_route"` and no `route`, or a blank one
- **WHEN** that declaration is armed
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `transform.route`

#### Scenario: A blank route is stored as absent

- **WHEN** a disabled declaration carries `route: ""` or a whitespace-only `route`
- **THEN** it is stored with no `route`, and a read of the pipeline carries no `route` member

#### Scenario: A route on a chat-completion transform is rejected

- **WHEN** an armed declaration carries `transform.route` with `preset` `chat_completion` or with `preset` omitted
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `transform.route`

#### Scenario: An unknown preset is refused at the binding

- **WHEN** a declaration carries `preset: "embeddings"`
- **THEN** the request is rejected with HTTP 400 `bad_request`, as any value outside the accepted set is

### Requirement: The response schema is composed from the target and the transform

The system SHALL compose an `llm` declaration's JSON response schema from the pipeline's target
enrichment and its `transform.outputs`, at pipeline registration, patch and read. Composition happens
on the pipeline because the pipeline is the only object there is: it names both the target and the
transform, and binding the two is definitionally its job. The table SHALL stay ignorant of transforms.

Composition SHALL be driven by the `outputs` **keys**, with each key's structure looked up on the
target column of the same name:

| Schema element | Source |
|---|---|
| property name | the `outputs` key, matched against the column's **exposed `name` only** |
| `type` | the target column's declared type, through the mapping below |
| `enum` values **and order** | the column's `enum_values` verbatim, else the output's `values` |
| `description` | the output's prose, **else the target column's `description`**, else omitted |
| property order and `required` order | the `outputs` declaration order |
| `required` | **every** declared output; for `dial_app_route`, every output bound to a non-nullable column |
| `additionalProperties` | always `false` |

The type mapping SHALL be: `string` → `{"type": "string"}`; `enum` → `{"type": "string", "enum":
[...]}`; `integer` and `long` → `{"type": "integer"}`; `double` and `decimal` → `{"type": "number"}`;
`boolean` → `{"type": "boolean"}`; `timestamp` → `{"type": "string"}` with **no `format`** (strict
schema mode rejects one); `array` → `{"type": "array", "items": <element_type mapped>}`. A column of
any other type — `map`, `json` — SHALL be rejected with HTTP 422 naming the column and its type.

The `description` default SHALL be applied **at composition time and SHALL NOT be written into the
stored declaration**, so that the column remains the single authority for a fact the author did not
supply. It SHALL therefore appear in the composed `response_schema` only, and SHALL NOT appear in the
authored declaration under either projection: a derived default echoed onto the authored surface would
be sent back by the next read-modify-write `PATCH`, become authored, and silently stop tracking the
column. Where an output declares no prose **and** its target column has no `description`, the property
SHALL be composed with no `description` at all rather than refused — a description-less property is
valid under strict mode and is already what a legacy declaration with a stored schema produces — and
`POST /v1/pipelines/{name}/preview` SHALL report it as a note. The default SHALL apply to `llm`
outputs only: a `sql` output's string is an expression rather than prose, cannot be defaulted from any
column, and SHALL keep being required and non-blank.

Composition SHALL apply **only** where the declaration stores no `response_schema` of its own. A
declaration carrying a stored schema serves it verbatim, and a declaration carrying neither a stored
schema nor `outputs` — the shape a folded old-shape `llm` transform can have — composes nothing and
calls the model unconstrained, exactly as it did before the fold.

**Property order SHALL be the author's declared order, and SHALL be preserved through storage.** It
is not cosmetic: under strict schema enforcement the model generates properties in schema order, so a
field whose prose instructs it to derive itself from its siblings must be declarable *after* them.
Order SHALL NOT be taken from the target's column order, which is tag-group-then-alphabetical and
would make generation order a function of column tags. A storage form that normalizes key order SHALL
NOT be used for either the composed schema or the stored `outputs`.

For a `chat_completion` transform, `required` SHALL cover every declared output and SHALL NOT be derived
from column nullability. Strict mode requires every property to be required, so there is no optional-output
shape to express — and the two facts differ anyway: an enrichment column is nullable because a row may not
be labelled yet, while a schema field is required because the model must answer. An output that may
legitimately be absent SHALL be expressed as a sentinel value in `values` plus a `jsonata` mapping it to
NULL.

For a `dial_app_route` transform, nullability SHALL follow the bound column. An output bound to a nullable
column SHALL be left out of `required`, and its property SHALL also accept `null`: its `type` becomes the
pair of its type and `"null"`, and a closed `enum` domain gains `null`. An output bound to a non-nullable
column stays required and non-null. The strict-mode argument does not apply here, because no
`response_format` is sent and nothing holds the application to the schema. The runner validates only the
object it assembled from the outputs' sources, and the column is what says whether NULL can be stored. A
field the application omits is therefore written as NULL where the column allows it, and refused where it
does not.

Composing the enum domain **from the column** SHALL be preferred to declaring it on the output,
and the consequence SHALL be accepted rather than worked around: enum **order** in the prompt is then
whatever order the column's `enum_values` declare, which is chosen for dashboard sorting. Membership
is the half that carries correctness — a ClickHouse enum column refuses an out-of-domain value at
insert, so composing the domain from the column makes "the model returned a value the column cannot
store" unrepresentable — and an order override on the output would rebuild exactly the
two-copies-of-one-domain drift this composition removes.

An output declaring `values` for a column that already declares `enum_values` SHALL be rejected with
HTTP 422 naming both: a field has exactly one authority for its domain, chosen by which of the two
can hold it. `values` SHALL be understood to buy **schema** enforcement only — a `string` column
accepts anything at insert — and the rejection message for an out-of-domain value SHALL NOT imply
otherwise.

Because output names are matched to column names, an output matching no column SHALL be rejected with
HTTP 422 naming the output and the target. A transform belongs to exactly one pipeline, which writes
exactly one target.

**Residual, stated rather than solved.** After composition the target's schema is a live input to both
the prompt and the write mapping, and a column dropped and re-added changes the composed schema
without any pipeline being patched, so `pipeline_generation` — the per-row provenance stamp — does not
move either. Rows labelled either side of such a change therefore carry no per-row record of which
schema produced them.

#### Scenario: A schema is composed from the target's columns

- **WHEN** a pipeline is registered naming a target whose columns include an `enum` column and a
  `decimal` column, and a `transform` declaring an output for each
- **THEN** the composed schema types the first as `{"type": "string", "enum": [...]}` carrying that
  column's `enum_values` in the column's own order, and the second as `{"type": "number"}`

#### Scenario: A declared prose wins over the column description

- **WHEN** an output declares a non-blank `prose` and its target column also carries a `description`
- **THEN** the composed property's `description` is the declared prose, and the column's `description`
  is not consulted

#### Scenario: Neither prose nor column description composes no description

- **WHEN** an output declares no prose and its target column carries no `description`
- **THEN** the composed property carries no `description` member at all, the schema is still valid, and
  the pipeline is neither refused at write nor at enable

#### Scenario: The defaulted description is absent from the authored surface

- **WHEN** a pipeline whose output declares no prose is read with `view=source` and again with
  `view=compiled`
- **THEN** neither projection's authored `transform.outputs` carries a prose for that output, only the
  derived `response_schema` carries the column's `description`, and sending the `view=source` body back
  as a `PATCH` changes nothing

#### Scenario: A stored response schema is unaffected by the default

- **WHEN** a declaration stores its own `response_schema` and one of its outputs declares no prose
- **THEN** the stored schema is served verbatim, no composition runs, and no column `description` is
  read

#### Scenario: Property order follows the outputs declaration

- **WHEN** a `transform` declares outputs in the order `data_exposure`, `prohibited_request`,
  `risk_level`
- **THEN** the composed schema's `properties` and `required` carry that order, and it survives a
  round trip through storage and a pipeline read

#### Scenario: Every declared output is required

- **WHEN** a `transform` declares an output bound to a nullable target column
- **THEN** the composed schema lists that property in `required`, and `additionalProperties` is
  `false`

#### Scenario: A domain the column cannot carry is declared on the output

- **WHEN** an output declares `values` for a target column declared plain `string`
- **THEN** the composed schema carries those values as the property's `enum`, and the domain is
  enforced at the model boundary rather than at insert

#### Scenario: A column type with no strict-mode representation is rejected

- **WHEN** an output resolves to a target column of type `map` or `json`
- **THEN** the pipeline registration is rejected with HTTP 422 naming the column and its type

#### Scenario: A timestamp property carries no format

- **WHEN** an output resolves to a `timestamp` target column
- **THEN** the composed property is `{"type": "string"}` with no `format` member

#### Scenario: A declaration with no outputs composes nothing

- **WHEN** an `llm` declaration folded from an old-shape evaluator version carries `output_vars` and
  no `response_schema`
- **THEN** no schema is composed, the entry is served without one, and the model is called
  unconstrained as it was before the fold

#### Scenario: A route schema leaves a nullable column's output optional

- **GIVEN** a `dial_app_route` transform whose outputs bind a nullable `decimal` column `cost` and a
  non-nullable `string` column `title`
- **WHEN** its schema is composed
- **THEN** `required` lists `title` only, `cost` has `type` `["number", "null"]`, and `title` has `type`
  `"string"`

## ADDED Requirements

### Requirement: A transform may call a DIAL application route

An `llm` transform whose `preset` is `dial_app_route` SHALL declare which route of the application named by
`model` it calls. The runner calls `POST /v1/deployments/{model}/route/{route}`, where `/v1/deployments/{id}/route/`
is DIAL Core's prefix for an application's custom routes. The request body SHALL be the rendered
`request_template` with `transform.params` overlaid on its top-level JSON object, the same overlay a
`chat_completion` call applies: each `params` member is set on the body, replacing a member of the same
name the template rendered. The route answers with the transform's output object as its whole body. A route does not take part
in the chat-completions contract, so the declaration SHALL NOT be read as one: there is no `response_format`
to send and no assistant message to repair. The compiled `response_schema` SHALL still be served for the
pipeline, and the runner holds the route's response to it as it does a model's.

`transform.route` SHALL be checked for **shape** wherever the rest of the transform's shape is checked:
at the gated requests of "Static governance gate on arming and execution", meaning a write that arms the
pipeline and the preview of a disabled one. A disabled draft is stored as written, like every other
transform member. Nothing calls a disabled pipeline's route, and the runner re-checks the grammar when it
admits a pipeline. A route SHALL:

- be 1 to 256 characters;
- consist of one or more segments separated by single `/`, with no leading or trailing `/`;
- have segments matching `[A-Za-z0-9._~-]+`, none of them exactly `.` or `..`;
- carry no `?`, `#` or `%`. Percent-encoding is refused rather than decoded, so an encoded `..` or `/`
  cannot reach the URL by a second spelling.

A route violating any of these SHALL be rejected with HTTP 422 `pipeline_validation_failed` naming
`transform.route`. The route SHALL be stored and echoed as authored, case included; a blank one SHALL be stored as absent. It is a path DIAL
Core matches against the application's own route configuration, and ADAS SHALL NOT normalize it.

#### Scenario: A nested route is accepted

- **WHEN** a declaration carries `preset: "dial_app_route"` and `route: "v1/insights"`
- **THEN** the declaration is stored with `route` `"v1/insights"`

#### Scenario: Params are overlaid on a route's body

- **GIVEN** a `dial_app_route` declaration whose `request_template` renders to `{"text": "...", "mode": "fast"}`
  and whose `params` is `{"mode": "thorough", "limit": 5}`
- **WHEN** the runner calls the route
- **THEN** the body it POSTs is `{"text": "...", "mode": "thorough", "limit": 5}`

#### Scenario: A traversing route is rejected

- **WHEN** an armed declaration carries `route` `"../admin"`, `"a/./b"`, `"/insights"`, `"insights/"`, `"a//b"`,
  `"insights?x=1"` or `"%2e%2e/admin"`
- **THEN** each is rejected with HTTP 422 `pipeline_validation_failed` naming `transform.route`, and
  nothing is stored

#### Scenario: An over-long route is rejected

- **WHEN** an armed declaration carries a 257-character `route` of valid segment characters
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `transform.route`

### Requirement: An app-route pipeline is armed only for an allowed route

The static governance gate SHALL refuse an `llm` transform with `preset: "dial_app_route"` unless the
pair `(model, route)` is listed in the `app-routes` instance setting (see
[instance-settings](../instance-settings/spec.md)). The refusal SHALL be HTTP 422
`pipeline_validation_failed`, and its message SHALL name the deployment, the route and the setting. The
check SHALL run at exactly the gated requests of "Static governance gate on arming and execution": a
write that leaves the pipeline armed, and the preview of a disabled pipeline. A write that leaves the
pipeline disabled SHALL store the declaration whether or not its pair is allowed. An author may compose
a route pipeline before an administrator allows the route, as with any other referential check.

The comparison SHALL be exact and case-sensitive on both halves. That is the one case rule for this
lookup, because DIAL Core matches deployment ids and route paths case-sensitively. When the setting is
absent, no pair is allowed. The default is closed: a route is an arbitrary `POST` to an application
with source data in its body. A chat-completions call is compute by contract, but a route may have side
effects, so an instance calls none until an administrator names one.

The data-plane endpoints SHALL NOT re-run this check. The `app-routes` setting cannot drop a pair that
an enabled pipeline uses (see [instance-settings](../instance-settings/spec.md), "An allowed route in use
by an enabled pipeline cannot be removed"), so an enabled route pipeline's pair is allowed for as long as
it stays enabled. A re-check would only refuse what that guard already keeps true.

#### Scenario: Arming a route pipeline whose route is not allowed is refused

- **GIVEN** the `app-routes` setting lists `(insights-app, insights)` only
- **WHEN** a pipeline declaring `model: "insights-app"`, `route: "summaries"` is enabled
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `insights-app`,
  `summaries` and `app-routes`, and the pipeline stays disabled

#### Scenario: Arming with no setting stored is refused

- **WHEN** no `app-routes` setting has ever been stored and a `dial_app_route` pipeline is created with
  `enabled: true`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: A disabled route pipeline is stored before its route is allowed

- **WHEN** a `dial_app_route` pipeline whose pair is not allowed is created with `enabled: false`
- **THEN** it is created with HTTP 201, and enabling it succeeds once the pair has been added to the
  setting

#### Scenario: The comparison is case-sensitive

- **GIVEN** the setting lists `(insights-app, insights)`
- **WHEN** a pipeline declaring `route: "Insights"` is enabled
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`

### Requirement: A template placeholder may render its value as raw JSON

A `request_template` placeholder SHALL have two forms:

- `{{name}}`, the existing form, renders the value **escaped** for embedding inside a JSON string
  literal.
- `{{name:json}}` renders the value as a **JSON literal**, for a template that places the value in JSON
  value position:
  - a string becomes a quoted JSON string;
  - a number or boolean stays a bare literal;
  - a list or map becomes its JSON text;
  - a bound null becomes `null`.

  This is what lets a route body carry a group's members as structured JSON
  (`{"members": {{members:json}}}`) rather than as one escaped string. The form is not specific to
  `dial_app_route`.

The suffix SHALL be exactly `:json`, immediately after the name. The placeholder rule in "A transform's
template placeholders are validated against the pipeline's trigger grain" SHALL apply to the **name**,
the part before the suffix, unchanged in every other respect:

- at row grain, `{{v:json}}` is a use of the `transform.inputs` key `v`;
- at group grain, `{{members:json}}` satisfies the requirement that `{{members}}` appear at least once;
- the other built-in member variables accept the suffix too.

A placeholder carrying any other `:suffix` SHALL be refused at the same moments as that rule, with HTTP
422 `pipeline_validation_failed` naming the placeholder. Read as a name, it would bind to nothing and
reach the model as literal text.

Every `{{...}}` token SHALL be a placeholder. The runner's renderer reads every match of `\{\{([^{}]*)}}` as
one, trims it, and fails the unit when no variable binds it, so a token outside the grammar would fail every
unit the pipeline renders. The grammar is a name of letters, digits and underscores, optionally followed by
`:` and a suffix, with optional whitespace inside the braces. A token outside it, such as `{{a:b:json}}`,
`{{members :json}}`, `{{a-b}}` or `{{}}`, SHALL be refused at the same moments as the placeholder rule,
with HTTP 422 `pipeline_validation_failed` naming the token. Whitespace around a well-formed token, as in
`{{ members }}`, is not refused.

#### Scenario: A raw members placeholder satisfies the group rule

- **GIVEN** a group pipeline whose template is `{"members": {{members:json}}}`
- **WHEN** it is enabled
- **THEN** the gate accepts it, because `members` appears

#### Scenario: A raw row placeholder counts as a use of its input

- **GIVEN** a row pipeline binding `transform.inputs` key `body` and a template
  `{"input": {{body:json}}}`
- **WHEN** it is enabled
- **THEN** the gate accepts it, and does not report `body` as unused

#### Scenario: An unknown suffix is refused

- **WHEN** a pipeline whose template carries `{{members:yaml}}` is enabled
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming `members:yaml`

#### Scenario: A token outside the placeholder grammar is refused

- **WHEN** a pipeline whose template carries `{{a:b:json}}`, `{{members :json}}` or `{{a-b}}` is enabled
- **THEN** each is rejected with HTTP 422 `pipeline_validation_failed` naming the token

#### Scenario: Whitespace inside the braces is tolerated

- **GIVEN** a group pipeline whose template is `{{ members }} {{ members_notice }}`
- **WHEN** it is enabled
- **THEN** the gate accepts it

### Requirement: A route output reads its value from the route's response with source

The system SHALL accept an optional `source` on each output of a `dial_app_route` transform. A `source` is a
JSONata expression the runner evaluates over the route's parsed response body. Its result is that output's
value. An output with no `source` SHALL take the response member named by the output, the same direct lookup a
chat output uses. The runner assembles one object from every output's value, keyed by output name, validates
it against the compiled `response_schema`, and writes it.

A `source` per output, rather than one expression reshaping the whole response, is what makes the mapping
both authorable and checkable. Each output maps to one small expression, usually a dotted path such as
`intent_counts.check`, so nothing has to be written as a single script. Every output is mapped by
construction, either from its `source` or from its name, so there is no list of fields that can fall out of
step with the outputs. A defect is reported against the one output it belongs to, both when the pipeline is
armed and when a value fails validation.

`prose` and `values` SHALL keep their meaning on a route output. `prose` is the property's description in the
composed schema, which the feed serves and an application may use, and `values` is a closed domain the
assembled value is validated against.

The member SHALL be checked at the gated requests of "Static governance gate on arming and execution". Each
refusal is HTTP 422 `pipeline_validation_failed` naming the output:

- A `source` SHALL be refused under `chat_completion`. A model's response is held to the schema by
  `response_format` and is read by name.
- An output's `jsonata` SHALL be refused under `dial_app_route`. The value is produced once, by `source`, and
  validated after, so a second transform would only move the value past validation.
- A `source` SHALL parse as JSONata.
- A `source` equal to the output's own name SHALL be refused as the identity expression, as the identity
  `jsonata` is: an absent `source` already means a direct lookup by name.

A blank `source` SHALL mean absent. The authored `source` SHALL be echoed in `transform.outputs` under both
projections. On the compiled projection, the resolved `outputs` mapping SHALL carry it as `source` on each
entry, beside `name`, `column` and `jsonata`, and SHALL omit it where the output declares none.

#### Scenario: Nested route fields are mapped one output at a time

- **WHEN** a `dial_app_route` declaration's `outputs` carry `"title": {}` and
  `"intent_counts__check": {"source": "intent_counts.check"}`
- **THEN** the pipeline is stored, and the compiled `outputs` entry for `intent_counts__check` carries
  `source` `"intent_counts.check"` while the entry for `title` carries none

#### Scenario: A source on a chat output is refused

- **WHEN** an armed `chat_completion` declaration carries an output with a `source`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the output

#### Scenario: A jsonata on a route output is refused

- **WHEN** an armed `dial_app_route` declaration carries an output with a `jsonata`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the output

#### Scenario: An unparseable or identity source is refused

- **WHEN** an armed `dial_app_route` declaration carries an output `tox_score` whose `source` is `"{title: "`,
  or is `"tox_score"`
- **THEN** each is rejected with HTTP 422 `pipeline_validation_failed` naming `tox_score`

### Requirement: Both projections carry the transform's route

`transform.route` SHALL be an authored member of the transform block, served under both `view=source`
and `view=compiled`. Under the rule that an undeclared declaration member is absent from the projection,
it SHALL be absent when the declaration carries none, which is always the case under `chat_completion`.
The enrich feed SHALL carry it inside `transform`, beside `model` and `preset`, so a consumer needs no
second lookup to build the call. Catalog export SHALL carry it in the pipeline's source projection like
every other authored member.

Adding the `dial_app_route` preset is a **breaking** change to the compiled projection. An entry whose
`preset` is a value the consumer cannot decode is not one it may run under a default. The runner
contract version SHALL therefore be raised to `5` (see [pipeline-registry](../pipeline-registry/spec.md),
"Every pipeline response carries the runner contract version").

#### Scenario: The feed carries the route

- **GIVEN** an enabled `dial_app_route` pipeline with `route: "insights"`
- **WHEN** the runner reads `GET /v1/pipelines?kind=enrich&view=compiled`
- **THEN** that entry's `transform` carries `preset` `"dial_app_route"`, `model` and `route`
  `"insights"`, and the response carries `Adas-Contract-Version: 5`

#### Scenario: A chat-completion transform carries no route member

- **WHEN** a `chat_completion` pipeline is read under either projection
- **THEN** its `transform` has no `route` member at all, not a `null` one
